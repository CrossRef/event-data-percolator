(ns event-data-percolator.observation
  "An Observation is a particular view of a potential input with a type (see README).
   Each Observation Type has a different handler. Each observation handler produces a list of candidates.
   There are various candidate types, and each handler can emit the following:
    - :landing-page-urls-candidates
    - :doi-url-candidates
    - :plain-doi-candidates
    - :pii-candidates

   Some Observation processes wrap others (e.g. text from HTML content is passed through to the plaintext handler)."
   (:require [event-data-percolator.observation-types.content-url :as content-url]
             [event-data-percolator.observation-types.html :as html]
             [event-data-percolator.observation-types.plaintext :as plaintext]
             [event-data-percolator.observation-types.url :as url])
   (:import [org.apache.commons.codec.digest DigestUtils]))

(def process-types {
  "plaintext" plaintext/process-plaintext-content-observation
  "html" html/process-html-content-observation
  "url" url/process-url-observation
  "content-url" content-url/process-content-url-observation})

(defn postflight-process
  "Common post-flight. Remove sensitive info if required."
  [observation sensitive?]
  (-> 
    observation

    ; :input-content could have come from the outside (e.g. plaintext) or downloaded / extracted during processing (e.g. content-url)
    (#(if-let [input-content (:input-content %)]
          (assoc % :input-content-hash (DigestUtils/sha1Hex ^String input-content))
            %))

    ; if sensitive flag set, remove the input-content but the hash will remain behind.
    (#(if sensitive?
        (dissoc % :input-content)
        %))))

(defn unrecognised-observation-type
  "An observation processor for unrecognised types. Just pass through and set unrecognised flag."
  [context observation]
  (assoc observation :error :unrecognised-observation-type))

(defn process-observation
  "Process an observation, extracting candidates unless it's part of a duplicate action."
  [context observation duplicate?]
  ; Choose a dispatch function or pass-through if unrecognised-observation-type.
  (let [sensitive? (:sensitive observation)
        typ (:type observation)
        
        ; How to process this? If it's a duplicate, pass through and don't do anything.
        ; Otherwise choose the right processing function, or 'unrecognised'.
        f (if duplicate?
              (fn [_ observation] observation)
              (process-types typ unrecognised-observation-type))
        
        processed (f context observation)

        ; Now assign common hash, and remove sensitive info if required.
        result (postflight-process processed sensitive?)]

    result))
