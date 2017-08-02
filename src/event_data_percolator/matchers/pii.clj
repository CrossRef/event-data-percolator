(ns event-data-percolator.matchers.pii
  "Lookup PIIs from the Crossref API's publisher-supplied mapping."
  (:require [event-data-percolator.util.pii :as pii]))

(defn match-pii-candidate
  [context candidate]
  (assoc candidate
         :match (pii/validate-pii context (:value candidate))))
