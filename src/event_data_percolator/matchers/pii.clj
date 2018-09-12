(ns event-data-percolator.matchers.pii
  "Lookup PIIs from the Crossref API's publisher-supplied mapping."
  (:require [event-data-percolator.util.pii :as pii]
            [event-data-common.evidence-log :as evidence-log]
            [clojure.tools.logging :as log]))

(defn match-pii-candidate
  [context candidate]

  (log/debug "match-pii-candidate input:" candidate)

  (let [result (pii/validate-pii context (:value candidate))]

    (evidence-log/log!
      (assoc (:log-default context)
             :i "p0009"
             :c "match-pii"
             :f "validate"
             :v (:value candidate)
             :d (:match result)
             :e (if (:match result) "t" "f")))

    (assoc candidate
           :match result
           :method :pii
           :verification :lookup)))
