(ns event-data-percolator.process
  "Top level process inputs."
  (:require [event-data-percolator.queue :as queue]
            [clojure.tools.logging :as log]
            [event-data-percolator.input-bundle :as input-bundle]))

(def input-bundle-queue-name "input-bundle")
(def output-bundle-queue-name "output-bundle")

(defn process-input-bundle
  [input]
  (input-bundle/process input))

(defn run-process
  "Run processing input bundles from the input queue, place on output queue. Block."
  []
  (queue/process-queue input-bundle-queue-name process-input-bundle output-bundle-queue-name))

(defn push-output-bundle
  [input]
  (log/info "Got output bundle")
  (clojure.pprint/pprint input)
  (let [events (input-bundle/extract-all-events input)]
    (doseq [event events]
      (log/info "Push event")
      (clojure.pprint/pprint event)))
  true)

(defn run-push
  "Push output bundles from output queue. Block."
  []
  (queue/process-queue output-bundle-queue-name push-output-bundle nil))
