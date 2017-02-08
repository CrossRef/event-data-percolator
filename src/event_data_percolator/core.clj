(ns event-data-percolator.core
  (:require [event-data-percolator.server :as server]
            [event-data-percolator.process :as process]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn -main
  [& args]
  (condp = (first args)
    "accept" (server/run-server)
    "process" (process/run-process)
    "push" (process/run-push)
    (log/error "Unrecognised command: " (first args))))
