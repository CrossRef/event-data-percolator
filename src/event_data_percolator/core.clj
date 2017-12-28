(ns event-data-percolator.core
  (:require [event-data-percolator.process :as process]
            [event-data-common.core :as common]
            [taoensso.timbre :as timbre]
            [clojure.tools.logging :as log]
            [config.core :refer [env]])
  (:gen-class))

(defn -main
  [& args]

  (common/init)

  
  (timbre/merge-config!
    {:ns-blacklist [
       ; Robots file generates unhelpful logging.
       "crawlercommons.robots.*"
      
       ; Kafka's DEBUG is overly chatty.
       "org.apache.kafka.clients.consumer.internals.*"]
     :level (condp = (env :percolator-log-level) "debug" :debug "info" :info :info)})

  ; A log message at each level.
  (log/info "Starting Percolator.")
  (log/debug "Starting Percolator.")

  (Thread/setDefaultUncaughtExceptionHandler
    (reify Thread$UncaughtExceptionHandler
      (uncaughtException [_ thread ex]
        (log/error ex "Uncaught exception on" (.getName thread)))))

  (condp = (first args)
    "process" (do
                (process/process-kafka-inputs-concurrently)
                (log/error "Exiting!")
                (System/exit 1))
    (log/error "Unrecognised command: " (first args))))
