(ns event-data-percolator.observation-types.content-url
  "Extract unlinked DOIs, unlinked URLs and linked URLs (including DOIs) from HTML document at given URL."
  (:require [event-data-percolator.observation-types.html :as html]
            [event-data-percolator.util.web :as web]
            [event-data-common.evidence-log :as evidence-log]
            [event-data-common.landing-page-domain :as landing-page-domain]
            [clojure.tools.logging :as log])
  (:import [org.jsoup Jsoup]
           [org.apache.commons.codec.digest DigestUtils]
           [java.net URL]))

(defn process-content-url-observation
  [context observation]
  (log/debug "process-content-url-observation input:" observation)
  (if-let [input-url (:input-url observation)]
    ; Don't allow ourselves to visit pages on landing pages, as these probably have registered content,
    ; and we're not trying to replicate those links.
    (if (landing-page-domain/domain-recognised? context input-url)
      (assoc observation :error :skipped-domain)

      ; The :ignore-robots flag is passed in by Agents that have specific exemptions.
      ; E.g. Wikipedia sites' API is excluded for general-purpose robots but allowed for our uses.
      (let [content (if (:ignore-robots observation)
                      (web/fetch-ignoring-robots context input-url)
                      (web/fetch-respecting-robots context input-url))]
      
        (doseq [newsfeed-link (html/newsfeed-links-from-html (:body content) input-url)]
           (evidence-log/log! (assoc (:log-default context)
                                     :i "p0014"
                                     :c "newsfeed-link"
                                     :f "found"
                                     :v input-url
                                     :u (:href newsfeed-link)
                                     :p (:rel newsfeed-link))))
        
        (if-not content
          (assoc observation :error :failed-fetch-url)
          (let [; Attach content then pass the thing to the HTML processor for heavy lifting.
                ; Need to include the :input-url so any relative canonical URLs can be resolved. 
                ; (This does happen!)
                new-observation (assoc observation :input-url input-url :input-content (:body content))
                html-observations (html/process-html-content-observation context new-observation)
                
                ; Include the final redirect URL that we visited.
                with-final-url (if-let [url (:final-url content)]
                                 (assoc html-observations :final-url url)
                                 html-observations)]
            with-final-url))))

      ; If it was nil, return that.
      (assoc observation :error :empty-url)))
