(ns event-data-percolator.process
  "Top level process inputs."
  (:require [event-data-percolator.queue :as queue]
            [event-data-percolator.util.storage :as storage]
            [clojure.tools.logging :as log]
            [event-data-percolator.input-bundle :as input-bundle]
            [event-data-common.artifact :as artifact]
            [clojure.core.memoize :as memo]))

(def input-bundle-queue-name "input-bundle")
(def output-bundle-queue-name "output-bundle")

(def domain-list-artifact-name "crossref-domain-list")

(defn retrieve-domain-list
  "Return tuple of [version-url, domain-list-set]"
  []
  ; Fetch the cached copy of the domain list.
  (let [domain-list-artifact-version (artifact/fetch-latest-version-link domain-list-artifact-name)
        ; ~ 5KB string, set of ~ 8000
        domain-list (-> domain-list-artifact-name artifact/fetch-latest-artifact-stream clojure.java.io/reader line-seq set)]
    [domain-list-artifact-version domain-list]))

(def cache-milliseconds
  "One hour"
  3600000)

(def cached-domain-list
  "Cache the domain list and version url. It's very rarely actually updated."
  (memo/ttl retrieve-domain-list {} :ttl/threshold cache-milliseconds))

(defn process-input-bundle
  [input]
  ; Fetch the cached copy of the domain list.
  (let [[domain-list-artifact-version domain-list] (cached-domain-list)]
    (input-bundle/process input domain-list-artifact-version domain-list)))

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
