(ns event-data-percolator.matchers.shortdoi-url
  (:require [event-data-percolator.util.doi :as doi]
            [crossref.util.doi :as crdoi])
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
  (assoc candidate
        :match (match-shortdoi-url context (:value candidate))))
