(ns event-data-percolator.process
  "Top level process inputs."
  (:require [event-data-percolator.input-bundle :as input-bundle]
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
            [event-data-common.status :as status]
            [robert.bruce :refer [try-try-again]])
  (:import [org.apache.kafka.clients.producer KafkaProducer Producer ProducerRecord]
           [org.apache.kafka.clients.consumer KafkaConsumer Consumer ConsumerRecords]
           [org.apache.kafka.common TopicPartition PartitionInfo]))

(def domain-list-artifact-name "crossref-domain-list")

(defn retrieve-domain-list
  "Return tuple of [version-url, domain-list-set]"
  []
  (log/info "Retrieving domain list artifact")
  (status/send! "percolator" "artifact" "fetch" -1 1 "domain-list")
  ; Fetch the cached copy of the domain list.
  (let [domain-list-artifact-version (artifact/fetch-latest-version-link domain-list-artifact-name)
        ; ~ 5KB string, set of ~ 8000
        domain-list (-> domain-list-artifact-name artifact/fetch-latest-artifact-stream clojure.java.io/reader line-seq set)]
    [domain-list-artifact-version domain-list]))

(def cache-milliseconds
  "One hour"
  3600000)

(def cached-domain-list
  "Cache the domain list and version url. It's very rarely actually updated."
  (memo/ttl retrieve-domain-list {} :ttl/threshold cache-milliseconds))

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
    (let [properties (java.util.Properties.)]
      (.put properties "bootstrap.servers" (:global-kafka-bootstrap-servers env))
      (.put properties "acks", "all")
      (.put properties "retries", (int 5))
      (.put properties "key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
      (.put properties "value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
      (KafkaProducer. properties))))

(defn storage-key-for-evidence-record-id
  [id]
  (str input-bundle/evidence-url-prefix id))

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
  [evidence-record-input process-f]
  (let [id (:id evidence-record-input)]
    (cond
      ; This will also set the mutex.
      (not (redis/expiring-mutex!? @redis-mutex-store id mutex-millseconds))
      (log/info "Skipping Evidence Record due to mutex:" id)

      (store/get-string @evidence-store (storage-key-for-evidence-record-id id))
      (log/info "Skipping Evidence Record, already processed:" id)

      :default
      (process-f evidence-record-input))))

(defn process-and-save
  "Accept an Evidence Record input, process, save and send Events downstream."
  [evidence-record-input]
  (log/info "Processing" (:id evidence-record-input))
  (let [id (:id evidence-record-input)
        [domain-list-artifact-version domain-list] (cached-domain-list)
        
        ; Actually do the work of processing an Evidence Record.
        evidence-record-processed (input-bundle/process evidence-record-input domain-list-artifact-version domain-list)
        
        ; Remove the JWT before saving as a public record.
        public-evidence-record (dissoc evidence-record-processed :jwt)

        storage-key (storage-key-for-evidence-record-id id)
        events (input-bundle/extract-all-events evidence-record-processed)
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
                              (json/write-str (assoc event :jwt jwt)))))

    (log/info "Finished saving" id)))

(defn run-process-concurrently
  "Run the same function in a number of threads. Exit if any of them exits."
  [concurrency run-f]
  (log/info "Start process queue")
  (let [threads (map (fn [thread-number]
                       (log/info "Starting processing thread number" thread-number)
                       (thread (run-f)))
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
 (let [properties (java.util.Properties.)]
     (.put properties "bootstrap.servers" (:global-kafka-bootstrap-servers env))
     (.put properties "group.id"  "percolator-process")
     (.put properties "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
     (.put properties "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
     
     ; This is only used in the absence of an existing marker for the group.
     (.put properties "auto.offset.reset" "earliest")

     (let [consumer (KafkaConsumer. properties)
           topic-name (:percolator-input-evidence-record-topic env)]
       (log/info "Subscribing to" topic-name)
       (.subscribe consumer (list topic-name))
       (log/info "Subscribed to" topic-name "got" (count (or (.assignment consumer) [])) "assigned partitions")
       (loop []
         (log/info "Polling...")
         (let [^ConsumerRecords records (.poll consumer (int 10000))
               lag (lag-for-assigned-partitions consumer)
               c (atom 0)]
           (log/info "Lag for partitions:" lag)

           (doseq [[topic-number topic-lag] lag]
             (status/send! "percolator" "input-queue" "lag" topic-number topic-lag))
           
           (log/info "Got" (.count records) "records." (.hashCode records))
           (doseq [^ConsumerRecords record records]
            (swap! c inc)
            
            (log/info "Start processing:" (.key record) "size:" (.serializedValueSize record) @c "/" (.count records))

            (status/send! "percolator" "input-queue" "message-size" (.serializedValueSize record))
            (status/send! "percolator" "input-queue" "time-lag" (- (System/currentTimeMillis) (.timestamp record)))

            (let [value (.value record)
                  ; payload (try (json/read value :key-fn keyword) (catch Exception _ nil))
                  payload (json/read-str value :key-fn keyword)
                  schema-errors (input-bundle/validation-errors payload)]
              (log/info "Look at" (:id payload))
              (if schema-errors
                (log/error "Schema errors with input bundle id" (:id payload) schema-errors)
                (duplicate-guard
                  (json/read-str (.value record) :key-fn keyword)
                  process-and-save)))
            (log/info "Finished processing record" (.key record)))
            
            (log/info "Finished processing records" (.count records) "records." (.hashCode records))
            ; The only way this ends is violently.
            (recur))))))

(defn process-kafka-inputs-concurrently
  "Run a number of threads to process inputs."
  []
  (let [concurrency (Integer/parseInt (:percolator-process-concurrency env "1"))]
    (log/info "Starting to process in" concurrency "threads.")
    (run-process-concurrently concurrency process-kafka-inputs)
    (log/error "Stopped processing!")))

