(ns event-data-percolator.core
  (:require [event-data-percolator.process :as process]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn -main
  [& args]

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
