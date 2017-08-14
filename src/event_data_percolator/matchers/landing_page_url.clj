(ns event-data-percolator.matchers.landing-page-url
  (:require [event-data-percolator.util.web :as web]
            [clojure.tools.logging :as log]
            [crossref.util.doi :as crdoi]
            [event-data-percolator.util.doi :as doi]
            [event-data-percolator.util.pii :as pii]
            [event-data-common.storage.store :as store]
            [event-data-common.storage.redis :as redis]
            [event-data-common.evidence-log :as evidence-log]
            [config.core :refer [env]]
            [robert.bruce :refer [try-try-again]]
            [cemerick.url :as cemerick-url])
  (:import [java.net URL]
           [org.jsoup Jsoup]))

(def whole-doi-re #"^10\.\d{4,9}/[^\s]+$")
(def doi-re #"(10\.\d{4,9}(?:/|%2F|%2f)[^\s]+)")
(def shortdoi-find-re #"(?:(?:(?:dx.)?doi.org/)|10/)(?:info:doi/|urn:|doi:)?([a-zA-Z0-9]+)")

(defn normalize-doi-if-exists [doi]
  (when doi (crdoi/normalise-doi doi)))

(defn e
  "Produce a success code from the presence or absence of a result."
  [result]
  (if (nil? result)
    "f" "t"))

(defn try-from-get-params
  "If there's a DOI in a get parameter of a URL, find it"
  [context url]
  (try
    (let [params (-> url cemerick-url/query->map clojure.walk/keywordize-keys)
          doi-like-values (keep (fn [[k v]] (when (re-matches whole-doi-re v) v)) params)
          extant (keep (partial doi/validate-cached context) doi-like-values)
          result (-> extant first normalize-doi-if-exists)]
      
      (evidence-log/log!
        (assoc (:log-default context)
               :c "match-landingpage-url"
               :f "from-get-params"
               :u url
               :d result
               :e (e result)))

        result)

    ; Some things look like URLs but turn out not to be.
    (catch IllegalArgumentException _ nil)))

(defn try-doi-from-url-text
  "Match an embedded DOI, try various treatments to make it fit."
  [context url]
  (let [matches (map second (re-seq doi-re url))

        last-slash (map #(clojure.string/replace % #"^(10\.\d+/(.*))/.*$" "$1") matches)

        ; e.g. ijorcs.org
        first-slash (map #(clojure.string/replace % #"^(10\.\d+/(.*?))/.*$" "$1") matches)

        ; e.g. SICIs
        semicolon (map #(clojure.string/replace % #"^(10\.\d+/(.*));.*$" "$1") matches)
        
        ; eg. JSOR
        hashchar (map #(clojure.string/replace % #"^(10\.\d+/(.*?))#.*$" "$1") matches)

        ; e.g. biomedcentral
        question-mark (map #(clojure.string/replace % #"^(10\.\d+/(.*?))\?.*$" "$1") matches)

        ; e.g. citeseerx
        amp-mark (map #(clojure.string/replace % #"^(10\.\d+/(.*?))&.*$" "$1") matches)

        candidates (distinct (concat last-slash first-slash semicolon hashchar question-mark amp-mark))

        extant (keep (partial doi/validate-cached context) candidates)

        result (-> extant first normalize-doi-if-exists)]

    (evidence-log/log!
      (assoc (:log-default context)
             :c "match-landingpage-url"
             :f "from-url-text"
             :u url
             :d result
             :e (e result)))

        result))

(defn try-pii-from-url-text
  [context url]
  (let [result (->> url
                    pii/find-candidate-piis
                    (map (comp (partial pii/validate-pii context) :value))
                    first)]
    
    (evidence-log/log!
      (assoc (:log-default context)
             :c "match-landingpage-url"
             :f "from-pii-from-url-text"
             :u url
             :d result
             :e (e result)))

    result))

(def interested-tag-attrs
  "List of selectors whose attrs we're interested in."
  [
    ; e.g. https://www.ncbi.nlm.nih.gov/pmc/articles/PMC4852986/?report=classic
    ["meta[name=citation_doi]" "content"] 

    ; e.g. http://pubsonline.informs.org/doi/abs/10.1287/mnsc.2016.2427
    ["meta[name=DC.Identifier]" "content"]

    ; e.g. https://figshare.com/articles/A_Modeler_s_Tale/3423371/1
    ["meta[name=DC.identifier]" "content"]

    ; e.g. http://www.circumpolarhealthjournal.net/index.php/ijch/article/view/18594/html
    ["meta[name=DC.Identifier.DOI]" "content"] 

    ; e.g. http://pubsonline.informs.org/doi/abs/10.1287/mnsc.2016.2427
    ["meta[name=DC.Source]" "content"]

    ; e.g.  http://ccforum.biomedcentral.com/articles/10.1186/s13054-016-1322-5
    ["meta[name=prism.doi]" "content"]])

(def interested-tag-text
  "List of selectors whose text content we're interested in."
  [; e.g. http://jnci.oxfordjournals.org/content/108/6/djw160.full
    "span.slug-doi"])

; Logging for this happens in try-fetched-page-metadata-cached so we can include the :o parameter.
(defn try-fetched-page-metadata-content
  "Extract DOI from Metadata tags."
  [context text]
  (try
    (when text
      (let [document (Jsoup/parse text)

            ; Get specific attribute values from named elements.
            interested-attr-values (mapcat (fn [[selector attr-name]]
                                                    (->>
                                                      (.select document selector)
                                                      (map #(.attr % attr-name))))
                                                  interested-tag-attrs)

            ; Get text values from named elements.
            interested-text-values (mapcat (fn [selector]
                                                    (->>
                                                      (.select document selector)
                                                      (map #(.text %))))
                                                  interested-tag-text)

            interested-values (distinct (concat interested-attr-values interested-text-values))

            ; Try to normalize by removing recognised prefixes, then resolve
            extant (keep (comp (partial doi/validate-cached context) crdoi/non-url-doi) interested-values)]

        (-> extant first normalize-doi-if-exists)))
    ; We're getting text from anywhere. Anything could happen.
    (catch Exception ex (do
      (log/warn "Error parsing HTML for DOI.")
      (.printStackTrace ex)
      nil))))

(defn try-fetched-page-metadata
  [context url]
  (let [should-visit (web/should-visit-landing-page? (:domain-set context) url)]
    (evidence-log/log!
        (assoc (:log-default context)
               :c "match-landingpage-url"
               :f "should-visit-landing-page"
               :u url
               :v (if should-visit "t" "f")))

    (when should-visit
      (->> url (web/fetch-respecting-robots context)
               :body
               (try-fetched-page-metadata-content context)))))

(def redis-db-number (delay (Integer/parseInt (get env :landing-page-cache-redis-db "0"))))

(def redis-cache-store
  (delay (redis/build "landing-page-cache:" (:percolator-landing-page-cache-redis-host env) (Integer/parseInt (:percolator-landing-page-cache-redis-port env)) @redis-db-number)))

; These can be reset by component tests.
(def success-expiry-seconds
  "Expire cache 30 days after first retrieved, if it worked."
  (atom (* 60 60 24 30)))

(def failure-expiry-seconds
  "Expire cache 10 days after first retrieved, on failure."
  (atom (* 60 60 24 10)))

; Set for component tests.
(def skip-cache (:percolator-skip-landing-page-cache env))
 
; This one function is responsible for all outgoing web traffic. Cache its results.
; Other results are derived algorithmically, so there's no use caching those.
(defn try-fetched-page-metadata-cached
  [context url]
  (if skip-cache
    
    ; Skip cache.
    (let [result (try-fetched-page-metadata context url)]
      (evidence-log/log!
        (assoc (:log-default context)
               :c "match-landingpage-url"
               :f "from-page-metadata"
               :u url
               :d result
               :e (e result)
               :o "e"))
       result)

    ; Don't skip cache.
    ; Cached result will be nil (not found) NULL (preivously failed) or successful result.
    (let [cached-value (store/get-string @redis-cache-store url)
          cached-result (if (= "NULL" cached-value) nil cached-value)]

      (if cached-value
      ; Success or failure from Cache.
        (do
          (evidence-log/log!
            (assoc (:log-default context)
                 :c "match-landingpage-url"
                 :f "from-page-metadata"
                 :u url
                 :d cached-result
                 :e (e cached-result)
                 :o "c"))
           cached-result)

        ; No result in cache, 
        (let [result (try-fetched-page-metadata context url)]
          (if result
            (redis/set-string-and-expiry-seconds @redis-cache-store url @success-expiry-seconds result)
            (redis/set-string-and-expiry-seconds @redis-cache-store url @failure-expiry-seconds "NULL"))

            (evidence-log/log!
              (assoc (:log-default context)
                 :c "match-landingpage-url"
                 :f "from-page-metadata"
                 :u url
                 :d result
                 :e (e result)
                 :o "e"))
            result)))))

(defn unchunk [s]
  (when (seq s)
    (lazy-seq
      (cons (first s)
            (unchunk (next s))))))

(defn match-landing-page-url
  "Try a multitude of ways to match, cheapest first."
  [context url]
  ; Step through lazy seq, an item at a time.
  (or
    (try-from-get-params context url)
    (try-doi-from-url-text context url)
    (try-pii-from-url-text context url)
    (try-fetched-page-metadata-cached context url)))

(defn match-landing-page-url-candidate
  [context candidate]
  (assoc candidate
    :match (match-landing-page-url context (:value candidate))))
