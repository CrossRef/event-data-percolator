(ns event-data-percolator.observation-types.html
  "Extract unlinked DOIs, unlinked URLs and linked URLs (including DOIs) from HTML snippet or document."
  (:require [event-data-percolator.observation-types.plaintext :as plaintext]
            [event-data-percolator.observation-types.url :as url])
  (:import [org.jsoup Jsoup]
           [org.apache.commons.codec.digest DigestUtils]
           [java.net URI]))

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

(defn newsfeed-links-from-html
  "Extract a seq of all newsfeed links (RSS and Atom) from an HTML document.
   If URLs are relative, resolve them to absolute ones."
  [html original-url]
  (try
    (when html
      (let [parsed (Jsoup/parse html)
            base-uri (new URI original-url)
            rss-links (->> parsed
                           (#(.select % "link[type=application/rss+xml]"))
                           (map #(hash-map :rel (.attr % "rel") :href (str (.resolve base-uri (new URI (.attr % "href"))))))
                           set)
            atom-links (->> parsed
                            (#(.select % "link[type=application/atom+xml]"))
                            (map #(hash-map :rel (.attr % "rel") :href (str (.resolve base-uri (new URI (.attr % "href"))))))
                            set)
            all-links (clojure.set/union rss-links atom-links)]
        all-links))

    ; Constructing URIs from inputs may throw IllegalArgumentExceptions, NPE etc.
    ; This isn't mission-critical, so just ignore.
    (catch Exception _ nil)))

(defn process-html-content-observation
  "Process an observation of type html-content."
  [observation landing-page-domain-set web-trace-atom]
  (let [input (:input-content observation "")
        candidate-urls (links-from-html input)
        text (plaintext-from-html input)

        ; Get all the candidates from the plaintext view.
        plaintext-candidates (:candidates (plaintext/process-plaintext-content-observation (assoc observation :input-content text) landing-page-domain-set web-trace-atom))

        ; Then merge new candidates.
        candidates (concat plaintext-candidates
                    (keep #(url/url-to-landing-page-url-candidate % landing-page-domain-set) candidate-urls)
                    (keep url/url-to-doi-url-candidate candidate-urls))]
    (assoc observation :candidates candidates)))

