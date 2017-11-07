(ns event-data-percolator.core
  (:require [event-data-percolator.process :as process]
            [event-data-common.core :as common]
            [taoensso.timbre :as timbre]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn -main
  [& args]

  (common/init)

  ; Robots file generates unhelpful logging.
  (timbre/merge-config!
    {:ns-blacklist ["crawlercommons.robots.*"]})


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
