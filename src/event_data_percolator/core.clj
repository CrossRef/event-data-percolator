(ns event-data-percolator.core
  (:require [event-data-percolator.process :as process]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn -main
  [& args]
  (condp = (first args)
    "process" (do
                (process/process-kafka-inputs-concurrently)
                (log/error "Exiting!")
                (System/exit 1))
    (log/error "Unrecognised command: " (first args))))
