(ns event-data-percolator.util.web
  "Web fetching, Robot respecting."
  (:require [org.httpkit.client :as http]
            [clojure.tools.logging :as log]
            [config.core :refer [env]]
            [event-data-common.storage.redis :as redis]
            [event-data-common.storage.store :as store]
            [clojure.core.memoize :as memo]
            [event-data-common.evidence-log :as evidence-log]
            [event-data-percolator.consts])
  (:import [java.net URL URI]
           [crawlercommons.robots SimpleRobotRulesParser BaseRobotRules]
           [org.httpkit ProtocolException]
           [org.apache.http.client.utils URLEncodedUtils URIBuilder]
           [javax.net.ssl SNIHostName SNIServerName SSLEngine SSLParameters]))

(def redirect-depth 4)

(defn sni-configure
  [^SSLEngine ssl-engine ^URI uri]
  (let [^SSLParameters ssl-params (.getSSLParameters ssl-engine)]
    (.setServerNames ssl-params [(SNIHostName. (.getHost uri))])
    (.setSSLParameters ssl-engine ssl-params)))

(def sni-client (org.httpkit.client/make-client
                  {:ssl-configurer sni-configure}))


(def timeout-ms
  "Timeout for HTTP requests."
  10000)

(def deref-timeout-ms
  "Last-ditch timeout for derefing result. This is a safety-valve to avoid threads hanging."
  100000)

(def max-bytes
  "Don't try and retrieve anything over 50 MiB."
  (* 1024 1024 50))

(def skip-cache (:percolator-skip-robots-cache env))

(defn fetch
  "Fetch the content at a URL as a string, following redirects and accepting cookies."
  [context url]
  
  (log/debug "fetch input:" url)

  (evidence-log/log! (assoc (:log-default context)
                            :i "p0018" :c "fetch" :f "request"
                            :u url))

  (try
    (loop [headers {"Referer" "https://eventdata.crossref.org"
                    "User-Agent" event-data-percolator.consts/user-agent-for-robots}
             depth 0
             url url]

        (log/debug "fetch input:" url "depth:" depth)
        (if (> depth redirect-depth)
          nil
          (let [result (deref
                         (http/get
                           url
                           {:follow-redirects false
                            :headers headers
                            :as :text
                            :timeout timeout-ms
                            :throw-exceptions true
                            :filter (http/max-body-filter max-bytes)
                            :client sni-client})
                         deref-timeout-ms
                         ; If this times out, return a special status for the condp below.
                         {:error :timeout})

                error (:error result)
                cookie (-> result :headers :set-cookie)
                new-headers (merge headers (when cookie {"Cookie" cookie}))

                ; In the case of redirects, return the most recent redirect.
                reported-result (assoc result :final-url url)]

            (log/debug "fetch input:" url "status:" (:status result))

            ; Timeout has no status.
            (when-let [status (:status result)]
              (evidence-log/log! (assoc (:log-default context)
                                 :i "p0019" :c "fetch" :f "response" :u url :v (:status result))))

            (when (= :timeout (:error result))
              (log/warn "fetch timeout input:" url)
              (evidence-log/log! (assoc (:log-default context)
                                 :c "fetch" :f "error" :u url :v "timeout")))

            (condp = (:status reported-result)
              200 reported-result
              ; Weirdly some Nature pages return 401 with the content. http://www.nature.com/nrendo/journal/v10/n9/full/nrendo.2014.114.html
              401 reported-result
              301 (recur new-headers (inc depth) (-> reported-result :headers :location))
              303 (recur new-headers (inc depth) (-> reported-result :headers :location))
              302 (recur new-headers (inc depth) (-> reported-result :headers :location))
              nil))))

    ; On error just return nil, but add exception to trace.
    (catch java.net.URISyntaxException exception
      (do
        (log/debug "Error fetching" url (.getMessage exception))
        (evidence-log/log! (assoc (:log-default context)
                                  :i "p001a" :c "fetch" :f "error" :v "uri-syntax-exception" :u url))
        nil))

    (catch java.net.UnknownHostException exception
      (do
        (log/debug "Error fetching" url (.getMessage exception))
        (evidence-log/log! (assoc (:log-default context)
                                  :i "p001a" :c "fetch" :f "error" :v "unknown-host-exception" :u url))
        nil))

    (catch org.httpkit.client.TimeoutException exception
      (do
        (log/debug "Error fetching" url (.getMessage exception))
        (evidence-log/log! (assoc (:log-default context)
                                  :i "p001a" :c "fetch" :f "error" :v "timeout-exception" :u url))
        nil))

    (catch org.httpkit.ProtocolException exception
      (do
        (log/debug "Error fetching" url (.getMessage exception))
        (evidence-log/log! (assoc (:log-default context)
                                  :i "p001a" :c "fetch" :f "error" :v "protocol-exception" :u url))
        nil))

    ; This can happen when the Content-Length is over MAX_INT, which is 2GB which we would reject anyway.
    ; see https://github.com/http-kit/http-kit/issues/340
    (catch NumberFormatException exception
      (do
        (log/debug "Error fetching" url (.getMessage exception))
        (evidence-log/log! (assoc (:log-default context)
                                  :i "p001a" :c "fetch" :f "error" :v "protocol-exception" :u url))
        nil))

    (catch Exception exception
      (do
        (log/debug "Error fetching" url (.getMessage exception))
        (evidence-log/log! (assoc (:log-default context)
                                  :i "p001a" :c "fetch" :f "error" :v "unknown-exception" :u url))

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
  (log/debug "fetch-robots-cached input:" robots-file-url)
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
  (.parseContent parser robots-file-url (.getBytes (or file-content "") "UTF-8") "text/plain" event-data-percolator.consts/user-agent))

(defn get-rules
  "Get a Robot Rules object for the given robots.txt file url. Or nil if there aren't any."
  [context robots-file-url]
  (log/debug "get-rules input:" robots-file-url)
  (when-let [file-content (fetch-robots-cached context robots-file-url)]
    (parse-rules robots-file-url file-content)))

; The robots files are cached in Redis, but must be re-parsed. Keep track of the thousand-odd most visited sites.
; Note that this calls fetch, which records Evidence Logs about its activity. Because this is cached, a prior
; request under a previous Evidence Record may have satisfy the robots.txt request.
(def get-rules-cached
  (memo/lu get-rules :lu/threshold 1024))

(defn allowed?
  [context url-str]
  (log/debug "allowed? input:" url-str)
  (try
    (let [robots-file-url (new URL (new URL url-str) "/robots.txt")
          rules (get-rules-cached context (str robots-file-url))
          
          ; If there's no robots file, proceed.
          allowed (if-not rules true (.isAllowed rules url-str))]
      (log/debug "allowed? input:" url-str "result:" allowed)
      allowed)
    ; Malformed URL should be ignored.
    (catch Exception _ nil)))

(defn fetch-respecting-robots
  "Fetch URL, respecting robots.txt directives for domain."
  [context url]
  (log/debug "fetch-respecting-robots input:" url)
  (let [allowed (allowed? context url)]
    (evidence-log/log! (assoc (:log-default context)
                              :i "p001b"
                              :c "robot-check"
                              :f "result"
                              :e (if allowed "t" "f")
                              :u url))

  (when allowed
    (fetch context url))))

(defn fetch-ignoring-robots
  "Fetch URL, ignoring any robots.txt directives"
  [context url]
  (log/debug "fetch-ignoring-robots input:" url)
  (evidence-log/log! (assoc (:log-default context)
                             :i "p001c"
                             :c "robot-check"
                             :f "skip"
                             :u url))

  (fetch context url))

; Classify URLs by the look of them.

(def url-blacklist
  "Heuristics for URLs we never want to visit."
  [#"\.pdf$"])

(defn url-valid?
  "Is the URL valid, and passes blacklist test?"
  [url]
  (let [blacklist-match (when url (first (keep #(re-find % url) url-blacklist)))
        valid (when-not blacklist-match (try (new URL url) (catch Exception e nil)))]
    (boolean valid)))

