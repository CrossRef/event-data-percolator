(ns event-data-percolator.observation-types.content-url
  "Extract unlinked DOIs, unlinked URLs and linked URLs (including DOIs) from HTML document at given URL."
  (:require [event-data-percolator.observation-types.html :as html]
            [event-data-percolator.util.web :as web])
  (:import [org.jsoup Jsoup]
           [org.apache.commons.codec.digest DigestUtils]
           [java.net URL]))

(def url-blacklist
  [#"\.pdf$"])

(defn url-valid?
  "Is the URL valid, and passes blacklist test?"
  [url]
  (let [blacklist-match (when url (first (keep #(re-find % url) url-blacklist)))
        valid (when-not blacklist-match (try (new URL url) (catch Exception e nil)))]
    (boolean valid)))

(defn process-content-url-observation
  [observation landing-page-domain-set web-trace-atom]
  (let [input (:input-url observation "")
        valid? (url-valid? input)
        content (when valid? (web/fetch-respecting-robots input web-trace-atom))]
    (if-not content
      (assoc observation :error :failed-fetch-url)
      (let [; Attach content then pass the thing to the HTML processor for heavy lifting.
            new-observation (assoc observation :input-content (:body content))
            html-observations (html/process-html-content-observation new-observation landing-page-domain-set web-trace-atom)]
        html-observations))))

