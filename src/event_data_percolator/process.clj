(ns event-data-percolator.process
  "Top level process inputs."
  (:require [event-data-percolator.evidence-record :as evidence-record]
            [event-data-percolator.util.util :as util]
            [event-data-percolator.action :as action]
            [clojure.tools.logging :as log]
            [clojure.core.async :refer [thread alts!!]]
            [config.core :refer [env]]
            [event-data-common.storage.s3 :as s3]
            [event-data-common.storage.redis :as redis]
            [event-data-common.artifact :as artifact]
            [event-data-common.storage.memory :as memory]
            [event-data-common.storage.store :as store]
            [clojure.core.memoize :as memo]
            [clojure.data.json :as json]
            [org.httpkit.client :as client]
            [event-data-common.evidence-log :as evidence-log]
            [robert.bruce :refer [try-try-again]])
  (:import [org.apache.kafka.clients.producer KafkaProducer Producer ProducerRecord]
           [org.apache.kafka.clients.consumer KafkaConsumer Consumer ConsumerRecords]
           [org.apache.kafka.common TopicPartition PartitionInfo]))

(def domain-list-artifact-name "crossref-domain-list")

(defn retrieve-domain-set
  "Return tuple of [version-url, domain-list-set]"
  []
  (log/info "Retrieving domain list artifact")

  ; Fetch the cached copy of the domain list.
  (let [domain-list-artifact-version (artifact/fetch-latest-version-link domain-list-artifact-name)
        ; ~ 5KB string, set of ~ 8000
        domain-set (-> domain-list-artifact-name artifact/fetch-latest-artifact-stream clojure.java.io/reader line-seq set)]
    [domain-list-artifact-version domain-set]))

(def cache-milliseconds
  "One hour"
  3600000)

(def cached-domain-set
  "Cache the domain list and version url. It's very rarely actually updated."
  (memo/ttl retrieve-domain-set {} :ttl/threshold cache-milliseconds))

(def evidence-store
  (delay
    (condp = (:percolator-evidence-storage env "s3")
      ; Memory for unit testing ONLY.
      "memory" (memory/build)
      "s3" (s3/build (:percolator-s3-key env)
                     (:percolator-s3-secret env)
                     (:percolator-evidence-region-name env)
                     (:percolator-evidence-bucket-name env)))))

(def redis-mutex-store
  "Store for timing-out-mutex store"
  (delay (redis/build "doi-cache:"
                      (:percolator-mutex-redis-host env)
                      (Integer/parseInt (:percolator-mutex-redis-port env))
                      (Integer/parseInt (:percolator-mutex-redis-db env "0")))))

(def kafka-producer
  (delay
    (KafkaProducer. {
      "bootstrap.servers" (:global-kafka-bootstrap-servers env)
      "acks" "all"
      "retries" (int 5)
      "key.serializer" "org.apache.kafka.common.serialization.StringSerializer"
      "value.serializer" "org.apache.kafka.common.serialization.StringSerializer"})))

(defn storage-key-for-evidence-record-id
  [id]
  (str evidence-record/evidence-url-prefix id))

; The mutex is used as a pessimistic lock.
; The timeout is used so that the process can crash if it needs to.
; The mutex is only used to account for accidental re-delivery, as can happen with Kafka.

(def mutex-millseconds
  "How long do we have to process a record before mutex expires?"
  120000)

(defn duplicate-guard
  "Call process-f if this Evidence Record should be processed.
   - Ignore and log if already present in Registry (i.e. already processed duplicate).
   - Ignore and log if timeout lock present.
   - Set lock with timeout.
   - Call Process Evidence Record function, which has side-effects."
  [context evidence-record-input process-f]
  (let [id (:id evidence-record-input)]
    (cond
      ; This will also set the mutex.
      (not (redis/expiring-mutex!? @redis-mutex-store id mutex-millseconds))
      (do
        (log/info "Skipping Evidence Record due to mutex:" id)
        (evidence-log/log!
             (assoc (:log-default context)
               :c "process"
               :f "skip-processing-elsewhere")))

      (store/get-string @evidence-store (storage-key-for-evidence-record-id id))
      (do
        (log/info "Skipping Evidence Record, already processed:" id)
        (evidence-log/log!
             (assoc (:log-default context)
               :c "process"
               :f "skip-already-processed")))

      :default
      (process-f evidence-record-input))))

(defn process-and-save
  "Accept an Evidence Record input, process, save and send Events downstream."
  [context evidence-record-input]
  (log/info "Processing" (:id evidence-record-input))
  (let [id (:id evidence-record-input)

        [domain-list-artifact-version domain-set] (cached-domain-set)

        context (assoc context
                      :id id
                      :domain-set domain-set
                      :domain-list-artifact-version domain-list-artifact-version)

        
        ; Actually do the work of processing an Evidence Record.
        evidence-record-processed (evidence-record/process context evidence-record-input)
        
        ; Remove the JWT before saving as a public record.
        public-evidence-record (dissoc evidence-record-processed :jwt)

        storage-key (storage-key-for-evidence-record-id id)
        events (evidence-record/extract-all-events evidence-record-processed)
        jwt (:jwt evidence-record-input)
        topic (:global-event-input-topic env)]

    (log/info "Saving" id)
    (store/set-string @evidence-store storage-key (json/write-str public-evidence-record))

    ; If everything went ok, save Actions for deduplication.
    (log/debug "Setting deduplication info for" id)
    (action/store-action-duplicates evidence-record-processed)

    (doseq [event events]
      (log/debug "Sending event: " (:id event))
      ; Piggy-back the JWT in the Event. Bus will understand.
      (.send @kafka-producer
             (ProducerRecord. topic
                              (:id event)
                              (json/write-str (assoc event :jwt jwt))))

      (evidence-log/log! (assoc (:log-default context)
                                :c "process" :f "send-event" :n (:id event))))

    (log/info "Finished saving" id)))

(defn run-process-concurrently
  "Run the same function in a number of threads. Exit if any of them exits."
  [concurrency run-f]
  (log/info "Start process queue")
  (let [threads (map (fn [thread-number]
                       (log/info "Starting processing thread number" thread-number)
                       (thread 
                        (log/info "Running processing thread number" thread-number)
                        (run-f)
                        (log/error "Stopped processing thread number" thread-number)))
                     (range concurrency))]

    ; Wait for any threads to exit. They shoudln't.
    (alts!! threads)

    (log/warn "One thread died, exiting.")))

(defn lag-for-assigned-partitions
  "Return map of topic partition number to message lag."
  [consumer]
  (let [partitions (.assignment consumer)
        end-offsets (.endOffsets consumer partitions)]
    (map #(vector (.partition %) (- (get end-offsets %) (.position consumer %))) partitions)))

(defn process-kafka-inputs
  "Process an input stream from Kafka in this thread."
  []
  (let [; Execution context for all processing involved in processing this Evidence Record.
        ; This context is passed to all functions that need it.
        context {; A default evidence log message with common fields present.
                 ; As we get down the call stack, more detail is added.
                 :log-default {:s "percolator"}}

        consumer (KafkaConsumer.
          {"bootstrap.servers" (:global-kafka-bootstrap-servers env)
           "group.id"  "percolator-process"
           "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"
           "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"
           "auto.offset.reset" "earliest"})

       topic-name (:percolator-input-evidence-record-topic env)]

   (log/info "Subscribing to" topic-name)
   (.subscribe consumer (list topic-name))

   (log/info "Subscribed to" topic-name "got" (count (or (.assignment consumer) [])) "assigned partitions")
   
   ; Loop forever, recording how many batches ever processed.
   (loop [batch-c 0]
     (log/info "Polling...")
     (let [^ConsumerRecords records (.poll consumer (int 10000))
           lag (lag-for-assigned-partitions consumer)

           ; Count of number of records processed in this batch.
           c (atom 0)]

       (log/info "Lag for partitions:" lag)

       ; Only need to log this infrequently, as it's very chatty.
       (when (zero? (rem batch-c 100))
         (doseq [[partition-number partition-lag] lag]
           (evidence-log/log!
              (assoc (:log-default context)
                :c "process" :f "input-message-lag"
                :p partition-number :v partition-lag))))
       
       (log/info "Got" (.count records) "records." (.hashCode records))
       (doseq [^ConsumerRecords record records]
         (swap! c inc)
         (let [value (.value record)
               evidence-record (json/read-str value :key-fn keyword)
               schema-errors (evidence-record/validation-errors evidence-record)
               context (assoc-in context [:log-default :r] (:id evidence-record))
               start-time (System/currentTimeMillis)]

           (log/info "Look at" (:id evidence-record))

           (log/info "Start processing:" (.key record)
                     "size:" (.serializedValueSize record)
                     @c "/" (.count records))

           (evidence-log/log!
             (assoc (:log-default context)
               :c "process"
               :f "input-message-time-lag"
               :v (- (System/currentTimeMillis) (.timestamp record))))

           (evidence-log/log!
             (assoc (:log-default context)
               :c "process"
               :f "start"))


           (evidence-log/log!
             (assoc (:log-default context)
               :c "process"
               :f "input-message-size"
               :v (.serializedValueSize record)))

          (if schema-errors
            (log/error "Schema errors with input Evidence Record id" (:id evidence-record) schema-errors)
            (duplicate-guard
              context
              (json/read-str (.value record) :key-fn keyword)
              
              ; This is where all the work happens!
              (partial process-and-save context)))
        
          (log/info "Finished processing record" (.key record))

          (evidence-log/log!
             (assoc (:log-default context)
               :c "process"
               :f "finish"
               :v (- (System/currentTimeMillis) start-time)))))
        
        (log/info "Finished processing records" (.count records) "records." (.hashCode records)))
        ; The only way this ends is violently.
        (recur (inc batch-c)))))

(defn process-kafka-inputs-concurrently
  "Run a number of threads to process inputs."
  []
  (evidence-log/log!
    {:s "percolator"
     :c "process"
     :f "version"
     :v util/percolator-version})

  (let [concurrency (Integer/parseInt (:percolator-process-concurrency env "1"))]
    (log/info "Starting to process in" concurrency "threads.")
    (run-process-concurrently concurrency process-kafka-inputs)
    (log/error "Stopped processing!")))

