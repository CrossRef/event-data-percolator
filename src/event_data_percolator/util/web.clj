(ns event-data-percolator.util.web
  (:require [org.httpkit.client :as http])
  (:import [org.httpkit ProtocolException]))

(def redirect-depth 4)

(defn fetch
  "Fetch the content at a URL as a string, following redirects and accepting cookies.
   Take an optional atom to which sequences of urls and status codes will be appended."
  ([url] (fetch url nil))
  ([url trace-atom]
    (try
      (loop [headers {"Referer" "https://eventdata.crossref.org"
                        "User-Agent" "CrossrefEventDataBot (eventdata@crossref.org)"}
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



