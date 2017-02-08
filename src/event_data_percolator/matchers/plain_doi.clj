(ns event-data-percolator.matchers.plain-doi
  (:require [event-data-percolator.util.doi :as doi]
            [crossref.util.doi :as crdoi])
  (:import [java.net URLEncoder URLDecoder]))

(defn match-plain-doi
  [plain-doi]
  "Return a canonical DOI if this is a valid, extant DOI."
  (when-let [validated (doi/validate-doi-dropping plain-doi)]
    (crdoi/normalise-doi validated)))

(defn match-plain-doi-candidate
  [candidate web-trace-atom]
  (assoc candidate
         :match (match-plain-doi (:value candidate))))
