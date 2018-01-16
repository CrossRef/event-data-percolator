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
            [event-data-common.evidence-log :as evidence-log]
            [robert.bruce :refer [try-try-again]])
  (:import [org.apache.kafka.clients.producer KafkaProducer Producer ProducerRecord]
           [org.apache.kafka.clients.consumer KafkaConsumer Consumer ConsumerRecords ConsumerRecord]
           [org.apache.kafka.common TopicPartition PartitionInfo]
           [java.util.concurrent Executors]))

(def domain-list-artifact-name "crossref-domain-list")

(def percolator-version (System/getProperty "event-data-percolator.version"))
(assert percolator-version "Failed to detect version.")
(def percolator-version-major-minor (->> percolator-version (re-find #"^(\d+\.\d+)\.\d+$") second))
(assert percolator-version-major-minor "Failed to detect major/minor version.")

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
  "S3 path for storing processed public Evidence Records."
  [id]
  (str evidence-record/evidence-url-prefix id))

(defn storage-key-for-failed-evidence-record-id
  [id]
  "S3 path for storing public, unprocessed Evidence Records that we failed to process."
  (str evidence-record/failed-evidence-url-prefix id))

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
               :i "p0012" :c "process" :f "skip-processing-elsewhere")))

      (store/get-string @evidence-store (storage-key-for-evidence-record-id id))
      (do
        (log/info "Skipping Evidence Record, already processed:" id)
        (evidence-log/log!
             (assoc (:log-default context)
               :i "p0011" :c "process" :f "skip-already-processed")))

      :default
      (process-f evidence-record-input))))

(defn process-and-save
  "Accept an Evidence Record input, process, save and send Events downstream.
   If there's an exception, just log it."
  [context evidence-record-input]
  (log/info "Processing" (:id evidence-record-input))
  (try
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
        (log/debug "Sending event from" (:id evidence-record-input) "event:" (:id event))
        
        (try
          ; Wait for the future, so sending is synchronous.
          (.get (.send @kafka-producer
                 (ProducerRecord. topic
                                  (:id event)

                                  ; Piggy-back the JWT in the Event. Bus will understand.
                                  (json/write-str (assoc event :jwt jwt)))))

          (evidence-log/log! (assoc (:log-default context)
                                    :i "p000c"
                                    :c "process" :f "send-event" :n (:id event)))

          (catch Exception ex
            (log/error "Exception sending Event ID to Kafka" (:id event) ":" (.getMessage ex))

            (evidence-log/log! (assoc (:log-default context)
                            :i "p001d"
                            :c "process" :f "send-event-error" :n (:id event))))))

      (log/info "Finished saving" id))

    ; Last line of defence for if processing an Evidence Record wasn't possible.
    ; Log the error, save the input for later inspection, and continue.
    (catch Exception e
      (let [id (:id evidence-record-input)
            ; Remove the JWT before saving as a public record.
            public-input-evidence-record (dissoc evidence-record-input :jwt)
            storage-key (storage-key-for-failed-evidence-record-id id)]

        (log/error "Exception processing evidence record, so skipping:" (:id evidence-record-input))
        (log/error e)
        (log/error "Saving at:" storage-key)
        (store/set-string @evidence-store storage-key (json/write-str public-input-evidence-record))))))


(defn lag-for-assigned-partitions
  "Return map of topic partition number to message lag."
  [consumer]
  (let [partitions (.assignment consumer)
        end-offsets (.endOffsets consumer partitions)]
    (map #(vector (.partition %) (- (get end-offsets %) (.position consumer %))) partitions)))

(defn process-kafka-record
  [context fetch-max-bytes ^ConsumerRecord record]
  (let [value (.value record)
        evidence-record (json/read-str value :key-fn keyword)
        schema-errors (evidence-record/validation-errors evidence-record)
       
        context (assoc-in context [:log-default :r] (:id evidence-record))
        start-time (System/currentTimeMillis)]

   (when (nil? (:id evidence-record))
    (log/error "No ID in Record! Input was:" value))

   (when (:id evidence-record)
     (log/info "Look at Evidence Record ID:" (:id evidence-record))

     ; Log both absolute size and proportion of maxmum possible size.
     (log/info (json/write-str
                {:type "EvidenceRecordSize"
                 :bytes (.serializedValueSize record)
                 :proportion-max (/ (float (.serializedValueSize record)) (float fetch-max-bytes))}))

     (evidence-log/log!
       (assoc (:log-default context)
         :i "p0003" :c "process" :f "input-message-time-lag"
         :v (- (System/currentTimeMillis) (.timestamp record))))

     (evidence-log/log!
       (assoc (:log-default context)
         :i "p0010" :c "process" :f "start"))


     (evidence-log/log!
       (assoc (:log-default context)
         :i "p000f" :c "process" :f "input-message-size"
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
         :i "p0013" :c "process" :f "finish"
         :v (- (System/currentTimeMillis) start-time)))))

    ; Return true to signal completion, can be useful for debugging parallel execution.
    true)

; Concurrency is used both to se the default thread pool for `future` but also as a parallelism parameter
; to do-parallel. Both must be set to get any effect. NB other bits of the code, and dependent libraries may
; draw from this threadpool, so do-parallel partitions will never be 1:1 with the thread pool.
(def concurrency (atom 1))
(when-let [concurrency-str (:percolator-process-concurrency env)]
  (let [concurrency-val (Integer/parseInt concurrency-str)]
    (reset! concurrency concurrency-val)
    (set-agent-send-off-executor! (Executors/newFixedThreadPool concurrency-val))))

(def future-timeout
  "Timeout for running background parallel processes. 
  This is an emergency circuit-breaker, so can be reasonably high."
 ; 1 hour
 3600000)

(defn do-parallel
  "Apply to to collection in parallel. Coupled to the threadpool size configured by percolator-process-concurrency.
   f should return true to indicate success."
  [f coll]
  (let [chunks (partition-all @concurrency coll)
        ; Start with "chunk 1" for readability.
        chunk-count (atom 1)
        size (.count coll)]
    (log/info "Process batch with size:" size " concurrency:" @concurrency)
    (doseq [chunk chunks]
      (log/info "Processing chunk" @chunk-count "of" (count chunks) "in a batch size" size)

      (let [futures (doall
                      (map #(future
                              (try
                                (f %)
                              (catch Exception e
                                (log/error "Unhandled error in do-parallel:" (.getMessage e))
                                :exception)))
                           chunk))
            results (doall (map #(deref % future-timeout :timeout) futures))
            all-ok (every? true? results)]
      
      (when all-ok
        (log/info "Chunk" @chunk-count "successful."))

      (when-not all-ok
        (log/error "Chunk" @chunk-count "unsuccessful.")
        (log/warn "Parallel execution results:" results))

      (swap! chunk-count inc)))
  (log/info "Finished batch with size:" size " concurrency:" @concurrency)))


(defn process-kafka-inputs
  "Process an input stream from Kafka in this thread."
  []
  (let [; Hardcoded size in bytes for the maximum chunk size that Kafka Consumer can retrieve.
        ; This hardcoded to Kafka's default of 50 MiB.
        ; A typical Evidence Record cab be up to around 10KiB.
        ; Hopefully this strikes the righ tbalance between sufficiently large chunks for parallelism, lag, and coordination overhead.
        ; We're logging the size of each Evidence Record as a proportion of maximum for operations monitoring.
        fetch-max-bytes 52428800

        ; Execution context for all processing involved in processing this Evidence Record.
        ; This context is passed to all functions that need it.
        context {:log-default {:s "percolator"}}

        consumer (KafkaConsumer.
          {"bootstrap.servers" (:global-kafka-bootstrap-servers env)
           "group.id"  (str "percolator-process" percolator-version-major-minor)
           "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"
           "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"
           "auto.offset.reset" "earliest"
           "fetch.max.bytes" (str fetch-max-bytes)
           "session.timeout.ms" (int 60000)})

       topic-name (:percolator-input-evidence-record-topic env)]

   (log/info "Subscribing to" topic-name)
   (.subscribe consumer (list topic-name))

   (log/info "Subscribed to" topic-name "got" (count (or (.assignment consumer) [])) "assigned partitions")
   
   ; Loop forever, recording how many batches ever processed.
   (loop [batch-c 0]
     (log/info "Polling...")
     (let [^ConsumerRecords records (.poll consumer (int 10000))
           lag (lag-for-assigned-partitions consumer)]

       ; Report on the partition lag. As batches are large and far between, it's OK to do this every batch.
       (doseq [[partition-number partition-lag] lag]
        ; Log for operations analytics.
        (log/info (json/write-str {:type "PartitionLag" :partition partition-number :lag partition-lag}))

        ; Useful for Evidence Logs to explain for delays and interruptions.
        (evidence-log/log!
            (assoc (:log-default context)
              :i "p000d" :c "process" :f "input-message-lag"
              :p partition-number :v partition-lag)))
       
       ; We don't know the memory size of the batch, just the number of records.
       ; We do log the size of each one though.
       (log/info (json/write-str {:type "BatchSize" :count (.count records)}))
       
       ; Each Evidence Record gets processed in parallel.
       ; This should strike the right balance between Evidence Records with few Actions (quick to get over with but take overhead)
       ; and large, slow ones.
       (let [before (System/currentTimeMillis)]
         (do-parallel
           (partial process-kafka-record context fetch-max-bytes)
           records)

         (let [diff (- (System/currentTimeMillis) before)]
            (log/info (json/write-str {:type "BatchDuration" :ms diff}))))
          
       (log/info "Finished processing records" (.count records) "records." (.hashCode records)))
       ; The only way this ends is violently.
       (recur (inc batch-c)))))

