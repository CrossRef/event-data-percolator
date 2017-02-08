(ns event-data-percolator.web
  (:require [org.httpkit.client :as http]))

; TODO MOVE INTO UTIL

(def redirect-depth 4)

(defn fetch
  "Fetch the content at a URL as a string, following redirects and accepting cookies.
   Take an optional atom to which sequences of urls and status codes will be appended."
  ([url] (fetch url nil))
  ([url trace-atom]
    (loop [headers {"Referer" "https://eventdata.crossref.org"
                    "User-Agent" "CrossrefEventDataBot (eventdata@crossref.org)"}
           depth 0
           url url]
      (if (> depth redirect-depth)
        nil
        (let [result @(http/get url {:follow-redirects false :headers headers :as :text})
              cookie (-> result :headers :set-cookie)
              new-headers (merge headers (when cookie {"Cookie" cookie}))]
          
          (when trace-atom
            (swap! trace-atom concat [{:status (:status result) :url url}]))
          
          (condp = (:status result)
            200 result
            ; Weirdly some Nature pages return 401 with the content. http://www.nature.com/nrendo/journal/v10/n9/full/nrendo.2014.114.html
            401 result
            303 (recur new-headers (inc depth) (-> result :headers :location))
            302 (recur new-headers (inc depth) (-> result :headers :location))
            nil))))))
