(ns event-data-percolator.observation-types.html
  "Extract unlinked DOIs, unlinked URLs and linked URLs (including DOIs) from HTML snippet or document."
  (:require [event-data-percolator.observation-types.plaintext :as plaintext]
            [event-data-percolator.observation-types.url :as url])
  (:import [org.jsoup Jsoup]
           [org.apache.commons.codec.digest DigestUtils]))

(defn plaintext-from-html
  "Extract a single plaintext string from text of whole document."
  [html]
  (-> html
      Jsoup/parse
      (.body)
      (.text)))

(defn links-from-html
  "Extract a seq of all links (a hrefs) from an HTML document."
  [html]
  (->> html
      Jsoup/parse
      (#(.select % "a"))
      (map #(.attr % "href"))
      (remove empty?)
      (set)))

(defn process-html-content-observation
  "Process an observation of type html-content."
  [observation landing-page-domain-set]
  (let [input (:input-content observation "")
        candidate-urls (links-from-html input)
        text (plaintext-from-html input)

        ; Get all the candidates from the plaintext view.
        plaintext-candidates (:candidates (plaintext/process-plaintext-content-observation (assoc observation :input-content text) landing-page-domain-set))

        ; Then merge new candidates.
        candidates (concat plaintext-candidates
                    (keep #(url/url-to-landing-page-url-candidate % landing-page-domain-set) candidate-urls)
                    (keep url/url-to-doi-url-candidate candidate-urls))]
    (assoc observation :candidates candidates)))

