(ns event-data-percolator.observation-types.plaintext
  "Extract all candidates from text snippet:
   - plain DOIs
   - HTTP DOIs
   - PIIs
   - landing page URLs"
    (:require [event-data-percolator.observation-types.url :as url]
              [event-data-percolator.util.doi :as doi]
              [event-data-percolator.util.pii :as pii]
              [clojure.tools.logging :as log])
   (:import [org.jsoup Jsoup]
            [org.apache.commons.codec.digest DigestUtils]
            [java.net URL URLDecoder])
   (:gen-class))

; Look for both URLencoded and not.
(def doi-re #"(10\.\d{4,9}(?:/|%2F|%2f)[^\s]+)")

(def url-re #"(https?://[^\s]+)")


(defn possible-urls-from-text
  "Extract all the candidate URLs found in this text snippet."
  [text]
  (->> text (re-seq url-re) (map first) distinct))

(defn candidate-dois-from-text
  "Extract all the candidate DOIs found in this text snippet."
  [text]
  (->> text
    (re-seq doi-re)
    (map first)
    distinct
    (map #(hash-map :type :plain-doi :value %))))

(defn candidate-piis-from-text
  "Extract all the candidate PIIs found in this text snippet."
  [text]  
  (pii/find-candidate-piis text))

(defn process-plaintext-content-observation
  "Process an observation of type plaintext-content."
  [observation landing-page-domain-set web-trace-atom]
  (let [input (:input-content observation "")
        possible-urls (possible-urls-from-text input)

        candidates (concat (candidate-dois-from-text input)
                           (candidate-piis-from-text input)
                           (keep url/url-to-doi-url-candidate possible-urls)
                           (keep #(url/url-to-landing-page-url-candidate % landing-page-domain-set) possible-urls))]
    (assoc observation :candidates candidates)))
