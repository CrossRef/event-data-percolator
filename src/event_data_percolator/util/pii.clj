(ns event-data-percolator.util.pii
  "Work with PIIs. Used by Elsevier and others.
  https://en.wikipedia.org/wiki/Publisher_Item_Identifier"
 (:require [org.httpkit.client :as http]
            [event-data-percolator.util.doi :as doi]
            [crossref.util.doi :as crdoi]
            [robert.bruce :refer [try-try-again]]
            [clojure.tools.logging :as log]
            [event-data-common.status :as status]
            [clojure.data.json :as json]))

(def pii-re #"([SB][0-9XB\-]{16,20})")

(defn find-candidate-piis
  "Extract all the PII-looking strings found in this text snippet."
  [text]
  (->> text
    (re-seq pii-re)
    (map first)
    distinct
    (map #(hash-map :type :pii :value %))))

(defn validate-pii
  "Validate a PII and return the DOI if it's been used as an alternative ID."
  [pii]
  (let [result (try-try-again {:sleep 5000 :tries 4}
                #(-> 
                  (http/get "https://api.crossref.org/v1/works" {:query-params {:filter (str "alternative-id:" pii)}})
                  deref
                  :body
                  json/read-str))
        items (get-in result ["message" "items"])]

    (status/add! "percolator" "metadata-api" "request" 1)
    
    (when-not result
      (log/error "Failed to retrieve PII from API for" pii)
      (status/add! "percolator" "metadata-api" "fail" 1))

    (when result
      (status/add! "percolator" "metadata-api" "ok" 1))

    ; Only return when there's exactly one match.
    ; If so, check that the DOI exists and in the process normalize (don't trust the API's indexed data).
    (when (= 1 (count items))
      (let [possible-doi (get (first items) "DOI")
            extant-doi (doi/validate-cached possible-doi)]
        (when extant-doi (crdoi/normalise-doi extant-doi))))))
