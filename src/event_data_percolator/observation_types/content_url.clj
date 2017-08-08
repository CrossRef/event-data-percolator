(ns event-data-percolator.observation-types.content-url
  "Extract unlinked DOIs, unlinked URLs and linked URLs (including DOIs) from HTML document at given URL."
  (:require [event-data-percolator.observation-types.html :as html]
            [event-data-percolator.util.web :as web]
            [event-data-common.evidence-log :as evidence-log])
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

(defn url-is-landing-page
  "Is the URL on a landing page domain?"
  [landing-page-domain-set url]
  (when-let [domain (try (.getHost (new URL url)) (catch Exception e nil))]
    (landing-page-domain-set (landing-page-domain-set domain))))

(defn process-content-url-observation
  [context observation]
  (let [input (:input-url observation "")
        valid? (url-valid? input)
        domain-allowed (not (url-is-landing-page (:domain-set context) input))
        proceed (and valid? domain-allowed)
        ; The :ignore-robots flag is passed in by Agents that have specific exemptions.
        ; E.g. Wikipedia sites' API is excluded for general-purpose robots but allowed for our uses.
        content (when proceed
                  (if (:ignore-robots observation)
                    (web/fetch-ignoring-robots context input)
                    (web/fetch-respecting-robots context input)))]
    
    (doseq [newsfeed-link (html/newsfeed-links-from-html (:body content) input)]
       (evidence-log/log! (assoc (:log-default context)
                                 :c "newsfeed-link"
                                 :f "found"
                                 :v input
                                 :u newsfeed-link)))
    
    (if-not domain-allowed
      (assoc observation :error :skipped-domain)
      (if-not content
        (assoc observation :error :failed-fetch-url)
        (let [; Attach content then pass the thing to the HTML processor for heavy lifting.
              new-observation (assoc observation :input-content (:body content))
              html-observations (html/process-html-content-observation context new-observation)]
          html-observations)))))
