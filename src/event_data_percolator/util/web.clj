(ns event-data-percolator.util.web
  "Web fetching, Robot respecting."
  (:require [org.httpkit.client :as http]
            [clojure.tools.logging :as log]
            [config.core :refer [env]]
            [event-data-common.storage.redis :as redis]
            [event-data-common.storage.store :as store]
            [org.httpkit.client :as client]
            [clojure.core.memoize :as memo]
            [event-data-common.status :as status]
            [event-data-percolator.consts])
  (:import [java.net URL]
           [crawlercommons.robots SimpleRobotRulesParser BaseRobotRules]
           [org.httpkit ProtocolException]))

(def redirect-depth 4)

(def skip-cache (:skip-robots-cache env))

(defn fetch
  "Fetch the content at a URL as a string, following redirects and accepting cookies.
   Take an optional atom to which sequences of urls and status codes will be appended."
  ([url] (fetch url nil))
  ([url trace-atom]
    (status/add! "percolator" "web-fetch" "request" 1)
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
              
              
              ; Trace. Two kinds of exception handling, the returned error and the try-catch below.
              (when trace-atom
                (if-not error
                  (swap! trace-atom concat [{:url url :status (:status result)}])
                  (swap! trace-atom concat [{:url url
                                             :error (cond
                                               (instance? org.httpkit.client.TimeoutException error) :timeout-error
                                               :default :unknown-error)}])))
              (if (#{200 401} (:status result))
                (status/add! "percolator" "web-fetch" "ok" 1)
                (status/add! "percolator" "web-fetch" "fail" 1))

              (condp = (:status result)
                200 result
                ; Weirdly some Nature pages return 401 with the content. http://www.nature.com/nrendo/journal/v10/n9/full/nrendo.2014.114.html
                401 result
                301 (recur new-headers (inc depth) (-> result :headers :location))
                303 (recur new-headers (inc depth) (-> result :headers :location))
                302 (recur new-headers (inc depth) (-> result :headers :location))
                nil))))
  
      ; On error just return nil, but add exception to trace.
      (catch java.net.URISyntaxException exception (when trace-atom
                                               (do (swap! trace-atom concat [{:error :url-syntax-error :url url}])
                                                nil)))

      (catch java.net.UnknownHostException exception (when trace-atom
                                                 (do (swap! trace-atom concat [{:error :unknown-host-error :url url}])
                                                  nil)))

      (catch org.httpkit.client.TimeoutException exception (when trace-atom
                                                       (do (swap! trace-atom concat [{:error :timeout-error :url url}])
                                                        nil)))

      (catch org.httpkit.ProtocolException exception (when trace-atom
                                                       (do (swap! trace-atom concat [{:error :timeout-error :url url}])
                                                        nil)))

      (catch Exception exception (when trace-atom
                        (do (swap! trace-atom concat [{:error :unknown :exception-message (.getMessage exception) :url url}])
                         nil))))))

(def redis-cache-store
  (delay (redis/build "robot-cache:" (:robots-cache-redis-host env) (Integer/parseInt (:robots-cache-redis-port env)) (Integer/parseInt (:robots-cache-redis-db env "0")))))

; These can be reset by component tests.
(def expiry-seconds
  "Expire cache 7 days after first retrieved."
  (atom (* 60 60 24 7)))

(defn fetch-robots-cached
  "Return robots file. Return nil if it doesn't exist."
  [robots-file-url]  
  (if skip-cache
    (:body (fetch robots-file-url))
    (if-let [cached-result (store/get-string @redis-cache-store robots-file-url)]
      (if (= cached-result "NULL")
          nil
          cached-result)
      (let [result (:body (fetch robots-file-url))]
        (redis/set-string-and-expiry-seconds @redis-cache-store robots-file-url @expiry-seconds (or result "NULL"))
        result))))

(def parser (new SimpleRobotRulesParser))

(defn parse-rules
  [robots-file-url file-content]
  (.parseContent parser robots-file-url (.getBytes file-content "UTF-8") "text/plain" event-data-percolator.consts/user-agent))

(defn get-rules
  "Get a Robot Rules object for the given robots.txt file url. Or nil if there aren't any."
  [robots-file-url]
  (when-let [file-content (fetch-robots-cached robots-file-url)]
    (parse-rules robots-file-url file-content)))

; The robots files are cached in Redis, but must be re-parsed. Keep track of the thousand-odd most visited sites.
(def get-rules-cached
  (memo/lu get-rules :lu/threshold 1024))

(defn allowed?
  [url-str]
  (let [robots-file-url (new URL (new URL url-str) "/robots.txt")
        rules (get-rules-cached (str robots-file-url))
        ; If there's no robots file, proceed.
        allowed (if-not rules true (.isAllowed rules url-str))]

    (if allowed 
      (status/add! "percolator" "robot" "allowed" 1)
      (status/add! "percolator" "robot" "not-allowed" 1))

    allowed))

(defn fetch-respecting-robots
  "Fetch URL, respecting robots.txt directives for domain."
  [url trace-atom]
  (let [allowed (allowed? url)]
    (if-not allowed
      (do
        (when trace-atom
          (swap! trace-atom concat [{:error :robots-forbidden :url url}]))
        nil)
      (fetch url trace-atom))))

(defn fetch-ignoring-robots
  "Fetch URL, ignoring any robots.txt directives"
  [url trace-atom]
  ; Just an alias, but intentional.
  (fetch url trace-atom))

