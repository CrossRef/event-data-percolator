(ns event-data-percolator.observation-types.content-url
  "Extract unlinked DOIs, unlinked URLs and linked URLs (including DOIs) from HTML document at given URL."
  (:require [event-data-percolator.observation-types.html :as html]
            [event-data-percolator.util.web :as web]
            [event-data-common.evidence-log :as evidence-log])
  (:import [org.jsoup Jsoup]
           [org.apache.commons.codec.digest DigestUtils]
           [java.net URL]))

(defn process-content-url-observation
  [context observation]
  (let [input-url (:input-url observation "")
        should-visit (web/should-visit-content-page? (:domain-set context) input-url)

        ; The :ignore-robots flag is passed in by Agents that have specific exemptions.
        ; E.g. Wikipedia sites' API is excluded for general-purpose robots but allowed for our uses.
        content (when should-visit
                  (if (:ignore-robots observation)
                    (web/fetch-ignoring-robots context input-url)
                    (web/fetch-respecting-robots context input-url)))]

    ; Log the decision on whether not to visit the URL.
    (evidence-log/log! (assoc (:log-default context)
                                 :c "observation"
                                 :f "should-visit-content-page"
                                 :v (if should-visit "t" "f")
                                 :u input-url))
    
    (doseq [newsfeed-link (html/newsfeed-links-from-html (:body content) input-url)]
       (evidence-log/log! (assoc (:log-default context)
                                 :c "newsfeed-link"
                                 :f "found"
                                 :v input-url
                                 :u newsfeed-link)))
    
    (if-not should-visit
      (assoc observation :error :skipped-domain)
      (if-not content
        (assoc observation :error :failed-fetch-url)
        (let [; Attach content then pass the thing to the HTML processor for heavy lifting.
              new-observation (assoc observation :input-content (:body content))
              html-observations (html/process-html-content-observation context new-observation)]
          html-observations)))))
