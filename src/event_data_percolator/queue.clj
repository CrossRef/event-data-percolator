(ns event-data-percolator.queue
  "Queue incoming input packages and outgoing data."
  (:require [clojure.data.json :as json]
            [clojure.tools.logging :as l]
            [config.core :refer [env]]
            [clj-time.core :as clj-time]
            [clj-time.format :as clj-time-format]
            [clj-time.coerce :as clj-time-coerce]
            [event-data-common.storage.redis :as redis]
            )
  (:import
           [java.net URL MalformedURLException InetAddress])
  (:gen-class))

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
      (.lpush redis-connection queue-name (into-array [json])))))

(defn process-queue
  "Process a named queue, blocking infinitely.
  Save work-in-progress (or failed items) on a working queue.
  If output-queue-name supplied, push results onto that.
  See http://redis.io/commands/rpoplpush for reliable queue pattern."
  [queue-name function done-queue-name]
  (with-open [redis-connection (redis/get-connection (:pool @redis-store) @redis-db-number)]
    (let [working-queue-key-name (str queue-name "-working")]
      (loop []
        (let [item-str (.brpoplpush redis-connection queue-name working-queue-key-name 0)
              item (json/read-str item-str :key-fn keyword)
              result (function item)
              result-json (when result (json/write-str result))]
          ; Once this is done successfully remove from the working queue.
          (when result
            (.lrem redis-connection working-queue-key-name 0 item-str)
            (when done-queue-name
              (.rpush redis-connection done-queue-name (into-array [result-json])))))
        (recur)))))
