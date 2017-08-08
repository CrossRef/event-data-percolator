(ns event-data-percolator.matchers.plain-doi
  (:require [event-data-percolator.util.doi :as doi]
            [crossref.util.doi :as crdoi]
            [event-data-common.evidence-log :as evidence-log])
  (:import [java.net URLEncoder URLDecoder]))

(defn match-plain-doi
  "Return a canonical DOI if this is a valid, extant DOI."
  [context plain-doi]
  (when-let [validated (doi/validate-cached context plain-doi)]
    (crdoi/normalise-doi validated)))

(defn match-plain-doi-candidate
  [context candidate]
  (let [result (match-plain-doi context (:value candidate))]
    
    (evidence-log/log!
            (assoc (:log-default context)
                 :c "match-plain-doi"
                 :f "match"
                 :v (:value candidate)
                 :d result
                 :e (if result "t" "f")))

  (assoc candidate
         :match result)))
