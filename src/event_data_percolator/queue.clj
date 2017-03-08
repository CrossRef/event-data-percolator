(ns event-data-percolator.queue
  "Queue incoming input packages and outgoing data."
  (:require [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [config.core :refer [env]]
            [clj-time.core :as clj-time]
            [clj-time.format :as clj-time-format]
            [clj-time.coerce :as clj-time-coerce]
            [event-data-common.storage.redis :as redis]
            [overtone.at-at :as at-at]
            [event-data-common.status :as status])
  (:import [java.net URL MalformedURLException InetAddress]
           [redis.clients.jedis.exceptions JedisConnectionException])
  (:gen-class))


; To co-ordinate graceful shutdown. Set to an empty promise. 
; When non-nil, queue should stop and deliver promise to signal it's over.
(def stopped-signal (atom nil))

(def redis-prefix
  "Unique prefix applied to every key."
  "edb:q")

(def default-redis-db-str "0")

(def redis-db-number (delay (Integer/parseInt (get env :redis-db default-redis-db-str))))

(def redis-store
  "A redis connection for storing subscription and short-term information."
  (delay (redis/build redis-prefix (:redis-host env) (Integer/parseInt (:redis-port env)) @redis-db-number)))

(defn enqueue
  [input-bundle queue-name]
  (let [json (json/write-str input-bundle)]
    (with-open [redis-connection (redis/get-connection (:pool @redis-store) @redis-db-number)]
      (.lpush redis-connection queue-name (into-array [json]))
      (log/info "Enqueue to " queue-name "length now" (.llen redis-connection queue-name)))))

(defn process-queue-item
  "Process a single item from a named queue, blocking indefinitely.
  Save work-in-progress (or failed items) on a working queue.
  If output-queue-name supplied, push results onto that.
  See http://redis.io/commands/rpoplpush for reliable queue pattern."
  [queue-name function done-queue-name]
  ; If there's a time-out re-connect. This could happen if there's a long time gap between inputs.
  (let [working-queue-key-name (str queue-name "-working")]
    (log/info "Queue listen:" queue-name)
    ; Catch Redis connection issues.
    (try 
      (with-open [redis-connection (redis/get-connection (:pool @redis-store) @redis-db-number)]
        (try
          ; Take value from input queue, push it to working queue. If processing fails, it will remain on working queue.
          ; BRPOPLPUSH can return null. Skip if it does.
          (when-let [item-str (.brpoplpush redis-connection queue-name working-queue-key-name 0)]
            (log/info "Got item from" queue-name
                      "length now" (.llen redis-connection queue-name)
                      "working queue length now" (.llen redis-connection working-queue-key-name))

            (let [item (json/read-str item-str :key-fn keyword)
                  result (function item)
                  result-json (when result (json/write-str result))]
              
              (when-not result
                (log/error "Null result returned!"))

              (when result
                (log/info "Processed item from queue" queue-name
                          "length now" (.llen redis-connection queue-name)
                          "working queue length now" (.llen redis-connection working-queue-key-name))

                ; The original may have timed out by now, depending on how long it took to process.
                (with-open [new-redis-connection (redis/get-connection (:pool @redis-store) @redis-db-number)]
                  (.lrem new-redis-connection working-queue-key-name 0 item-str)

                  (when done-queue-name
                    (.rpush redis-connection done-queue-name (into-array [result-json])))))))

          (catch Exception ex
            (log/error "Exception processing queue message:" (.getMessage ex)))))

    (catch JedisConnectionException ex
      (log/info "Timeout getting queue, re-starting."))
    (catch Exception ex
      (log/error "Unhandled exception in queue processing:" (.getMessage ex))))))

(defn process-queue
  [queue-name function done-queue-name]
  (loop []
    (process-queue-item queue-name function done-queue-name)
    ; Stop signal is normally an atom containing nil.
    ; When we get the shutdown signal, set this to a promise. Deliver the promise here to say that we've happily stopped.
    ; If we get a timeout during `process-queue-item`, we're either waiting for a queue item to come in (no harm done) or
    ; processing took too long and was skilled.
    (if-not @stopped-signal
      (recur)
      (do
        (log/info "Queue stopped")
        (deliver @stopped-signal true)))))

(def schedule-pool (at-at/mk-pool))

(defn start-heartbeat
  "Schedule a heartbeat to check on the Redis connection "
  []
    ; Send a heartbeat every second via pubsub. Log if there was an error sending it.
    (at-at/every 1000
      #(try
        (with-open [redis-connection (redis/get-connection (:pool @redis-store) @redis-db-number)]
          (.set redis-connection "PERCOLATOR_HEARTBEAT" "1")
          (.get redis-connection "PERCOLATOR_HEARTBEAT")
          (status/send! "percolator" "queue-heartbeat" "tick" 1))
         (catch Exception e (log/error "Error with Redis queue heartbeat:" (.getMessage e))))
    schedule-pool))
