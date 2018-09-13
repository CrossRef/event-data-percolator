(ns event-data-percolator.core
  (:require [event-data-percolator.util.util :as util]
            [event-data-percolator.process :as process]
            [event-data-common.evidence-log :as evidence-log]
            [event-data-common.core :as common]
            [event-data-common.landing-page-domain :as landing-page-domain]
            [event-data-percolator.evidence-record :as evidence-record]
            [taoensso.timbre :as timbre]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [config.core :refer [env]]
            [clojure.java.io :refer [writer]])
  (:gen-class))

(defn main-read-file
  "Read an input evidence record, write to an output evidence record. 
   This is designed for local testing and should be used with caution, 
   as duplicate processing has side-effects. Don't use with production 
   credentials."
  [input-path output-path]
  (let [input (json/read-str (slurp input-path) :key-fn keyword)
        
        context (-> {:log-default {:s "percolator"}}
                    (landing-page-domain/assoc-domain-decision-structure))
        
        processed (evidence-record/process context input)]
      (with-open [w (writer output-path)]
        (json/write processed w))))

(defn -main
  [& args]

  (common/init)

  (timbre/merge-config!
    {:ns-blacklist [
       ; Robots file generates unhelpful logging.
       "crawlercommons.robots.*"
      
       ; Kafka's DEBUG is overly chatty.
       "org.apache.kafka.clients.consumer.internals.*"
       "org.apache.kafka.clients.NetworkClient"]
     :level (condp = (env :percolator-log-level) "debug" :debug "info" :info :info)})

  ; A log message at each level.
  (log/info "Starting Percolator.")
  (log/debug "Starting Percolator.")

  (Thread/setDefaultUncaughtExceptionHandler
    (reify Thread$UncaughtExceptionHandler
      (uncaughtException [_ thread ex]
        (log/error ex "Default uncaught exception:" (.getName thread))
        ; Intentionally log and prn. There's a suggestion that sometimes logging doesn't work in exceptional circumstances.
        (prn ex "Default uncaught exception:" (.getName thread)))))

  (condp = (first args)

    ; Standard run-and-process.
    "process" (do
                (evidence-log/log!
                  {:s "percolator"
                   :c "process"
                   :f "version"
                   :v util/percolator-version})
                 (process/process-kafka-inputs)
                 (log/error "Exiting!")
                 (System/exit 1))

    ; One-off local file, for tinkering.
    ; lein run process-file input-path output-path
    ; If this is run with Docker, be aware of what files are available to you!
    "process-file" (main-read-file (second args) (nth args 2))
    (do 
      (log/error "Unrecognised command: " (first args)))))

