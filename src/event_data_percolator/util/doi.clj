(ns event-data-percolator.util.doi
  "Convert candidate DOIs into real DOIs."
  (:import [java.net URL URLEncoder URLDecoder])
  (:require [org.httpkit.client :as http]
            [config.core :refer [env]]
            [robert.bruce :refer [try-try-again]]
            [crossref.util.doi :as crdoi]
            [event-data-common.storage.store :as store]
            [event-data-common.storage.redis :as redis]
            [clojure.tools.logging :as log]
            [event-data-common.status :as status]
            [clojure.data.json :as json]))

(def doi-re #"(10\.\d{4,9}/[^\s]+)")
(def doi-escaped-re #"(10\.\d{4,9}%2[Ff][^\s]+)")
(def shortdoi-re #"([a-zA-Z0-9]+)")

(defn try-hostname
  "Try to get a hostname from a URL string."
  [text]
  (try (.getHost (new URL text)) (catch Exception e nil)))

(defn resolve-doi
  "Resolve and validate a DOI or ShortDOI, expressed as not-URL form. May or may not be URLEscaped. Return the DOI."
  [doi]

  (status/add! "percolator" "doi-api" "request" 1)
  (let [is-short-doi (not (re-matches #"^10\.\d+/.*" doi))
        ; if it looks like a full DOI, look that up. It it looks like a handle, different syntax.
        input-handle (if is-short-doi
                          (str "10/" doi)
                          doi)
        response @(try-try-again {:sleep 5000 :tries 2}
                    #(http/get
                      (str "https://doi.org/api/handles/" input-handle)
                      {:as :text}))
        status (:status response)
        body (when (= 200 status)
               (-> response :body (json/read-str :key-fn keyword)))
        ; Either get the validated handle, or for a short DOI, the DOI it's aliased to.
        handle (when body
                 (status/add! "percolator" "doi-api" "match" 1)
                 (if is-short-doi
                   (->> body :values (filter #(= (:type %) "HS_ALIAS")) first :data :value)
                   (:handle body)))]

      (when-not body 
        (status/add! "percolator" "doi-api" "no-match" 1))

    handle))

(defn resolve-doi-maybe-escaped
  "Try to resolve a possibly URL-encoded DOI. If it can be decoded and still resolve, return it decoded."
  [original]
  (if
    ; %2F is the URL-encoded slash which is present in every DOI.
    (and original (re-find #"%2[Ff]" original))
    (let [decoded (try (URLDecoder/decode original "UTF-8") (catch java.lang.IllegalArgumentException _ nil))]
      (if-let [resolved (when decoded (resolve-doi decoded))]
        resolved
        (resolve-doi original)))
    (resolve-doi original)))

(defn drop-right-char
  "Drop a character from the right of a string.
   If a surrogate pair is found, drop the pair."
  [input]
  (let [len (.length input)
        last-i (dec len)
        last-last-i (dec last-i)]
    (if (Character/isSurrogate (.charAt ^String input last-i))
      (if (Character/isSurrogate (.charAt ^String input last-last-i))
        (.substring input 0 last-last-i)
        (.substring input 0 last-i))
    (.substring input 0 last-i))))

(def max-drops 5)
(defn validate-doi-dropping
  "For a given suspected DOI or shortDOI, validate that it exists, possibly chopping some of the end off to get there."
  [doi]
  (loop [i 0
         doi doi]
    ; Terminate if we're at the end of clipping things off or the DOI no longer looks like an DOI. 
    ; The API will return 200 for e.g. "10.", so don't try and feed it things like that.
    (if (or (= i max-drops)
            (nil? doi)
            (< (.length doi) 3)
            ; The shortDOI regular expression is rather liberal, but it is what it is.
            (not (or (re-matches doi-re doi) (re-matches doi-escaped-re doi) (re-matches shortdoi-re doi))))
      ; Stop recursion.
      nil
      ; Or try this substring.
      (if-let [clean-doi (resolve-doi-maybe-escaped doi)] 
        ; resolve-doi may alter the DOI it returns, e.g. resolving a shortDOI to a real DOI or lower-casing.
        
        ; We have a working DOI!
        ; Just check it does't contain a sneaky question mark which would still resolve e.g. http://www.tandfonline.com/doi/full/10.1080/00325481.2016.1186487?platform=hootsuite
        ; If there is a question mark, try removing it to see if it still works.
        (if (.contains clean-doi "?")
          (let [before-q (first (.split clean-doi "\\?"))]
            (if (resolve-doi before-q)
              before-q
              clean-doi))
          clean-doi)
        
        (recur (inc i) (drop-right-char doi))))))

(def redis-db-number (delay (Integer/parseInt (get env :doi-cache-redis-db "0"))))

(def redis-cache-store
  (delay (redis/build "doi-cache:" (:doi-cache-redis-host env) (Integer/parseInt (:doi-cache-redis-port env)) @redis-db-number)))

; These can be reset by component tests.
(def success-expiry-seconds
  "Expire cache 180 days after first retrieved, if it worked."
  (atom (* 60 60 24 180)))

(def failure-expiry-seconds
  "Expire cache 30 days after first retrieved, on failure."
  (atom (* 60 60 24 30)))

; Set for component tests.
(def skip-cache (:skip-doi-cache env))

(defn validate-cached
  "Take a suspected DOI or ShortDOI and return the correct full well-formed, extant DOI.
   This is the function you want."
  [suspected-doi]
  (if skip-cache
    (validate-doi-dropping suspected-doi)
    (if-let [cached-result (store/get-string @redis-cache-store suspected-doi)]
      (if (= cached-result "NULL")
          nil
          cached-result)
      (if-let [result (validate-doi-dropping suspected-doi)]
        ; success
        (do
          (redis/set-string-and-expiry-seconds @redis-cache-store suspected-doi @success-expiry-seconds result)
          result)

        ; failure
        (do
          (redis/set-string-and-expiry-seconds @redis-cache-store suspected-doi @failure-expiry-seconds "NULL")
          nil)))))
