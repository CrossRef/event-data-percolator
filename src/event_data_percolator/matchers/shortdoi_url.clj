(ns event-data-percolator.matchers.shortdoi-url
  (:require [event-data-percolator.util.doi :as doi]
            [crossref.util.doi :as crdoi])
  (:import [java.net URL]))

(defn match-shortdoi-url
  "Return a canonical DOI if this is a valid, extant Short DOI."
  [short-doi-url]
  (let [valid-url (try (new URL short-doi-url) (catch Exception _ nil))
        shortdoi-path (when valid-url (.substring (or (.getPath valid-url) "") 1))
        validated (doi/resolve-doi shortdoi-path)]
    (crdoi/normalise-doi validated)))

(defn match-shortdoi-url-candidate
  [candidate web-trace-atom]
  (assoc candidate
        :match (match-shortdoi-url (:value candidate))))
