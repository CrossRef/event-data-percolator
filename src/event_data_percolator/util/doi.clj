(ns event-data-percolator.util.doi
  "Convert candidate DOIs into real DOIs."
  (:import [java.net URL URLEncoder URLDecoder])
  (:require [org.httpkit.client :as http]
            [robert.bruce :refer [try-try-again]]
            [crossref.util.doi :as crdoi]))

(def doi-re #"(10\.\d{4,9}/[^\s]+)")
(def shortdoi-re #"(?:(?:(?:dx.)?doi.org/)|10/)(?:info:doi/|urn:|doi:)?([a-zA-Z0-9]+)")

(defn try-hostname
  "Try to get a hostname from a URL string."
  [text]
  (try (.getHost (new URL text)) (catch Exception e nil)))

(defn resolve-doi
  "Resolve and validate a DOI or ShortDOI, expressed as not-URL form. May or may not be URLEscaped. Return the DOI."
  [doi]
  (let [response @(try-try-again {:sleep 500 :tries 2}
                    #(http/get
                      (str "https://doi.org/" doi)
                      {:follow-redirects false}))
        status (:status response)
        redirect-header (-> response :headers :location)]
        
      (cond
        (:error response) nil

        ; If it's a shortDOI it will redirect to the real one. Use this.
        (= (try-hostname redirect-header) "doi.org") (crdoi/non-url-doi redirect-header)

        ; If it's a real DOI it will return a 30x. 
        (= (quot status 100) 3) (crdoi/non-url-doi doi)

        ; If it's not anything then don't return anything.
        :default nil)))

(defn resolve-doi-maybe-escaped
  "Try to resolve a possibly URL-encoded DOI. If it can be decoded and still resolve, return it decoded."
  [original]
  (if
    ; %2F is the URL-encoded slash which is present in every DOI.
    (and original (re-find #"%2[Ff]" original))
    (let [decoded (URLDecoder/decode original "UTF-8")]
      (if-let [resolved (resolve-doi decoded)]
        resolved
        (resolve-doi original)))
    (resolve-doi original)))

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
            (< (.length doi) i)
            ; The shortDOI regular expression is rather liberal, but it is what it is.
            (not (or (re-matches doi-re doi) (re-matches shortdoi-re doi))))
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
        
        (recur (inc i) (.substring doi 0 (- (.length doi) 1)))))))