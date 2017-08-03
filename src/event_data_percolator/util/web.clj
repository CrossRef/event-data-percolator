(ns event-data-percolator.util.web
  "Web fetching, Robot respecting."
  (:require [org.httpkit.client :as http]
            [clojure.tools.logging :as log]
            [config.core :refer [env]]
            [event-data-common.storage.redis :as redis]
            [event-data-common.storage.store :as store]
            [org.httpkit.client :as client]
            [clojure.core.memoize :as memo]
            [event-data-common.evidence-log :as evidence-log]
            [event-data-percolator.consts])
  (:import [java.net URL]
           [crawlercommons.robots SimpleRobotRulesParser BaseRobotRules]
           [org.httpkit ProtocolException]))

(def redirect-depth 4)

(def skip-cache (:percolator-skip-robots-cache env))

(defn fetch
  "Fetch the content at a URL as a string, following redirects and accepting cookies.
   Take an optional atom to which sequences of urls and status codes will be appended."
  [context url]
  
  (evidence-log/log! (assoc (:log-default context)
                            :c "fetch" :f "request" :u url))

  (try
    (loop [headers {"Referer" "https://eventdata.crossref.org"
                    "User-Agent" event-data-percolator.consts/user-agent-for-robots}
             depth 0
             url url]
        (if (> depth redirect-depth)
          nil
          (let [result @(http/get url {:follow-redirects false :headers headers :as :text :throw-exceptions true})
                error (:error result)
                cookie (-> result :headers :set-cookie)
                new-headers (merge headers (when cookie {"Cookie" cookie}))]
             
            (evidence-log/log! (assoc (:log-default context)
                               :c "fetch" :f "response" :u url :v (:status result)))

            (condp = (:status result)
              200 result
              ; Weirdly some Nature pages return 401 with the content. http://www.nature.com/nrendo/journal/v10/n9/full/nrendo.2014.114.html
              401 result
              301 (recur new-headers (inc depth) (-> result :headers :location))
              303 (recur new-headers (inc depth) (-> result :headers :location))
              302 (recur new-headers (inc depth) (-> result :headers :location))
              nil))))

    ; On error just return nil, but add exception to trace.
    (catch java.net.URISyntaxException exception
      (do
        (evidence-log/log! (assoc (:log-default context)
                                  :c "fetch" :f "error" :v "uri-syntax-exception" :u url))
        nil))

    (catch java.net.UnknownHostException exception
      (do
        (evidence-log/log! (assoc (:log-default context)
                                  :c "fetch" :f "error" :v "unknown-host-exception" :u url))
        nil))

    (catch org.httpkit.client.TimeoutException exception
      (do
        (evidence-log/log! (assoc (:log-default context)
                                  :c "fetch" :f "error" :v "timeout-exception" :u url))
        nil))

    (catch org.httpkit.ProtocolException exception
      (do
        (evidence-log/log! (assoc (:log-default context)
                                  :c "fetch" :f "error" :v "protocol-exception" :u url))
        nil))

    (catch Exception exception
      (do
        (evidence-log/log! (assoc (:log-default context)
                                  :c "fetch" :f "error" :v "unknown-exception" :u url))

        nil))))

(def redis-cache-store
  (delay (redis/build "robot-cache:" (:percolator-robots-cache-redis-host env)
                                     (Integer/parseInt (:percolator-robots-cache-redis-port env))
                                     (Integer/parseInt (:percolator-robots-cache-redis-db env "0")))))

; These can be reset by component tests.
(def expiry-seconds
  "Expire cache 7 days after first retrieved."
  (atom (* 60 60 24 7)))

(defn fetch-robots-cached
  "Return robots file. Return nil if it doesn't exist."
  [context robots-file-url]  
  (if skip-cache
    (:body (fetch nil robots-file-url))
    (if-let [cached-result (store/get-string @redis-cache-store robots-file-url)]
      (if (= cached-result "NULL")
          nil
          cached-result)
      (let [result (:body (fetch context robots-file-url))]
        (redis/set-string-and-expiry-seconds @redis-cache-store robots-file-url @expiry-seconds (or result "NULL"))
        result))))

(def parser (new SimpleRobotRulesParser))

(defn parse-rules
  [robots-file-url file-content]
  (.parseContent parser robots-file-url (.getBytes file-content "UTF-8") "text/plain" event-data-percolator.consts/user-agent))

(defn get-rules
  "Get a Robot Rules object for the given robots.txt file url. Or nil if there aren't any."
  [context robots-file-url]
  (when-let [file-content (fetch-robots-cached context robots-file-url)]
    (parse-rules robots-file-url file-content)))

; The robots files are cached in Redis, but must be re-parsed. Keep track of the thousand-odd most visited sites.
; Note that this calls fetch, which records Evidence Logs about its activity. Because this is cached, a prior
; request under a previous Evidence Record may have satisfy the robots.txt request.
(def get-rules-cached
  (memo/lu get-rules :lu/threshold 1024))

(defn allowed?
  [context url-str]
  (let [robots-file-url (new URL (new URL url-str) "/robots.txt")
        rules (get-rules-cached context (str robots-file-url))
        
        ; If there's no robots file, proceed.
        allowed (if-not rules true (.isAllowed rules url-str))]

    allowed))

(defn fetch-respecting-robots
  "Fetch URL, respecting robots.txt directives for domain."
  [context url]

  (let [allowed (allowed? context url)]
    (evidence-log/log! (assoc (:log-default context)
                              :c "robot-check"
                              :f "result"
                              :v (boolean allowed)
                              :u url))

  (when allowed
    (fetch context url))))

(defn fetch-ignoring-robots
  "Fetch URL, ignoring any robots.txt directives"
  [context url]
  (evidence-log/log! (assoc (:log-default context)
                             :c "robot-check"
                             :f "skip"
                             :u url))

  (fetch context url))
