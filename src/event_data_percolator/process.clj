(ns event-data-percolator.process
  "Top level process inputs."
  (:require [event-data-percolator.queue :as queue]
            [event-data-percolator.util.storage :as storage]
            [clojure.tools.logging :as log]
            [event-data-percolator.input-bundle :as input-bundle]))

(def input-bundle-queue-name "input-bundle")
(def output-bundle-queue-name "output-bundle")

(defn process-input-bundle
  [input]
  ; Fetch the cached copy of the domain list.
  (let [[domain-list domain-list-artifact-version] (storage/get-domain-list)]
    (input-bundle/process input domain-list)))

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
