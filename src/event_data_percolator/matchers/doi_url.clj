(ns event-data-percolator.matchers.doi-url
  (:require [crossref.util.doi :as crdoi]
            [event-data-percolator.util.doi :as doi]
            [event-data-percolator.matchers.plain-doi :as plain-doi]))

(defn match-doi-url-candidate
  [candidate web-trace-atom]
  (assoc candidate
         :match (-> candidate :value crdoi/non-url-doi plain-doi/match-plain-doi)))

