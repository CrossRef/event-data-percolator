(ns event-data-percolator.matchers.shortdoi-url
  (:require [event-data-percolator.util.doi :as doi]
            [crossref.util.doi :as crdoi]
            [event-data-common.evidence-log :as evidence-log])
  (:import [java.net URL]))

(defn match-shortdoi-url
  "Return a canonical DOI if this is a valid, extant Short DOI."
  [context short-doi-url]
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
  (let [result (match-shortdoi-url context (:value candidate))]
    (evidence-log/log!
      (assoc (:log-default context)
             :c "match-shortdoi-url"
             :f "validate"
             :v (:value candidate)
             :d result
             :e (if result "t" "f")))

    (assoc candidate
          :match result)))
