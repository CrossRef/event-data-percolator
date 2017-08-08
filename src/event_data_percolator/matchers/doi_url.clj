(ns event-data-percolator.matchers.doi-url
  (:require [crossref.util.doi :as crdoi]
            [event-data-percolator.util.doi :as doi]
            [event-data-common.evidence-log :as evidence-log]
            [event-data-percolator.matchers.plain-doi :as plain-doi]))

(defn match-doi-url-candidate
  [context candidate]
  (let [result (->> candidate :value crdoi/non-url-doi (plain-doi/match-plain-doi context))]

    (evidence-log/log!
            (assoc (:log-default context)
                 :c "match-doi-url"
                 :f "match"
                 :v (:value candidate)
                 :d (:match result)
                 :e (if (:match result) "t" "f")))

    (assoc candidate
           :match result)))

