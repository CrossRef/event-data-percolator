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
            [robert.bruce :refer [try-try-again]]
            [event-data-common.status :as status])
  (:import [java.net URL MalformedURLException InetAddress]
           [java.io StringWriter PrintWriter]
           [redis.clients.jedis.exceptions JedisConnectionException]
           [javax.jms Session])
  (:gen-class))

(def amq-connection-factory
  (delay (new org.apache.activemq.ActiveMQConnectionFactory (:activemq-username env) (:activemq-password env) (:activemq-url env))))

(defn process-queue
  [queue-name process-f]
  (log/info "Starting to process queue" queue-name)
  (with-open [connection (.createConnection @amq-connection-factory)]
    (let [session (.createSession connection false, Session/AUTO_ACKNOWLEDGE)
          destination (.createQueue session queue-name)
          consumer (.createConsumer session destination)]
      (.start connection)
      (loop [message (.receive consumer)]
        (process-f (json/read-str (.getText ^org.apache.activemq.command.ActiveMQTextMessage message) :key-fn keyword))
        (recur (.receive consumer))))))

(defn enqueue
  [data queue-name]
  (with-open [connection (.createConnection @amq-connection-factory)]
    (let [session (.createSession connection false, Session/AUTO_ACKNOWLEDGE)
          destination (.createQueue session queue-name)
          producer (.createProducer session destination)
          message (.createTextMessage session (json/write-str data))]
      (.send producer message))))
