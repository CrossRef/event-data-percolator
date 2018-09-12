(ns event-data-percolator.matchers.plain-doi
  (:require [event-data-percolator.util.doi :as doi]
            [crossref.util.doi :as crdoi]
            [event-data-common.evidence-log :as evidence-log]
            [clojure.tools.logging :as log])
  (:import [java.net URLEncoder URLDecoder]))

(defn match-plain-doi
  "Return a canonical DOI if this is a valid, extant DOI."
  [context plain-doi]

  (log/debug "match-plain-doi input:" plain-doi)

  (when-let [validated (doi/validate-cached context plain-doi)]
    (crdoi/normalise-doi validated)))

(defn match-plain-doi-candidate
  [context candidate]

  (log/debug "match-plain-doi-candidate input:" candidate)

  (let [result (match-plain-doi context (:value candidate))]
    
    (evidence-log/log!
            (assoc (:log-default context)
                 :i "p000a"
                 :c "match-plain-doi"
                 :f "match"
                 :v (:value candidate)
                 :d (:match result)
                 :e (if (:match result) "t" "f")))

    (assoc candidate
         :match result
         :method :doi-literal
         :verification :literal)))
