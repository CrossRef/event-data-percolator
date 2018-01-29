(ns event-data-percolator.matchers.shortdoi-url
  (:require [event-data-percolator.util.doi :as doi]
            [crossref.util.doi :as crdoi]
            [event-data-common.evidence-log :as evidence-log]
            [clojure.tools.logging :as log])
  (:import [java.net URL]))

(defn match-shortdoi-url
  "Return a canonical DOI if this is a valid, extant Short DOI."
  [context short-doi-url]
  (log/debug "match-shortdoi-url input:" short-doi-url)

  (let [valid-url (try (new URL short-doi-url) (catch Exception _ nil))
        shortdoi-path (when valid-url
                    (let [the-path (.getPath valid-url)]
                      ; Drop leading slash, unless there isn't a path.
                      (when-not (clojure.string/blank? the-path)
                        (.substring the-path 1))))
        validated (doi/validate-cached context shortdoi-path)]
    (when validated
      (crdoi/normalise-doi validated))))

(defn match-shortdoi-url-candidate
  [context candidate]
  (log/debug "match-shortdoi-url-candidate input:" candidate)

  (let [result (match-shortdoi-url context (:value candidate))]
    (evidence-log/log!
      (assoc (:log-default context)
             :i "p000b"
             :c "match-shortdoi-url"
             :f "validate"
             :v (:value candidate)
             :d (:match result)
             :e (if (:match result) "t" "f")))

    (assoc candidate
          :match result)))
