(ns event-data-percolator.matchers.plain-doi
  (:require [event-data-percolator.util.doi :as doi]
            [crossref.util.doi :as crdoi])
  (:import [java.net URLEncoder URLDecoder]))

(defn match-plain-doi
  "Return a canonical DOI if this is a valid, extant DOI."
  [plain-doi]
  (when-let [validated (doi/validate-cached plain-doi)]
    (crdoi/normalise-doi validated)))

(defn match-plain-doi-candidate
  [candidate web-trace-atom]
  (assoc candidate
         :match (match-plain-doi (:value candidate))))
