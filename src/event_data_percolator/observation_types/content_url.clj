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
        ; The :ignore-robots flag is passed in by Agents that have specific exemptions.
        ; E.g. Wikipedia sites' API is excluded for general-purpose robots but allowed for our uses.
        content (when valid?
                  (if (:ignore-robots observation)
                    (web/fetch-ignoring-robots input web-trace-atom)
                    (web/fetch-respecting-robots input web-trace-atom)))]
    
    (when-let [newsfeed-links (html/newsfeed-links-from-html (:body content) input)]
      (when web-trace-atom
        (swap! web-trace-atom concat (map (fn [link] {:url link :type :newsfeed-url}) newsfeed-links))))
  
    (if-not content
      (assoc observation :error :failed-fetch-url)
      (let [; Attach content then pass the thing to the HTML processor for heavy lifting.
            new-observation (assoc observation :input-content (:body content))
            html-observations (html/process-html-content-observation new-observation landing-page-domain-set web-trace-atom)]
        html-observations))))
