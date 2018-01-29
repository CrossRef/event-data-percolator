(ns event-data-percolator.matchers.doi-url
  (:require [crossref.util.doi :as crdoi]
            [event-data-percolator.util.doi :as doi]
            [event-data-common.evidence-log :as evidence-log]
            [event-data-percolator.matchers.plain-doi :as plain-doi]
            [clojure.tools.logging :as log]))

(defn match-doi-url-candidate
  [context candidate]
  (let [result (->> candidate :value crdoi/non-url-doi (plain-doi/match-plain-doi context))]
    
    (log/debug "match-doi-url-candidate input:" candidate)

    (evidence-log/log!
            (assoc (:log-default context)
                 :i "p0004"
                 :c "match-doi-url"
                 :f "match"
                 :v (:value candidate)
                 :d (:match result)
                 :e (if (:match result) "t" "f")))

    (assoc candidate
           :match result)))

