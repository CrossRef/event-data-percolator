(ns event-data-percolator.matchers.landing-page-url
  (:require [event-data-percolator.util.web :as web]
            [cemerick.url :as cemerick-url]
            [clojure.core.memoize :as memo]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [config.core :refer [env]]
            [crossref.util.doi :as crdoi]
            [event-data-common.artifact :as artifact]
            [event-data-common.evidence-log :as evidence-log]
            [event-data-common.landing-page-domain :as landing-page-domain]
            [event-data-common.storage.redis :as redis]
            [event-data-common.storage.store :as store]
            [event-data-percolator.observation-types.html :as html-match]
            [event-data-percolator.util.doi :as doi]
            [event-data-percolator.util.html :as html]
            [event-data-percolator.util.pii :as pii]
            [event-data-percolator.util.web :as web]
            [robert.bruce :refer [try-try-again]])
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

  (log/debug "try-from-get-params input:" url)

  (try
    (let [params (-> url cemerick-url/query->map clojure.walk/keywordize-keys)
          doi-like-values (keep (fn [[k v]] (when (re-matches whole-doi-re v) v)) params)
          extant (keep (partial doi/validate-cached context) doi-like-values)
          doi (-> extant first normalize-doi-if-exists)
          
          ; Check that the domain in question has a relationship with this DOI.
          verification (cond
                         (landing-page-domain/domain-confirmed-for-doi? context url doi) :confirmed-domain-prefix
                         (landing-page-domain/domain-recognised-for-doi? context url doi) :recognised-domain-prefix
                         (landing-page-domain/domain-recognised? context url) :recognised-domain
                         :default nil)]
      
      (evidence-log/log!
        (assoc (:log-default context)
               :i "p0005"
               :c "match-landingpage-url"
               :f "from-get-params"
               :u url
               :d doi
               :e (e doi)))

      (log/debug "try-from-get-params result" doi)

        
        (when (and doi verification)
          {:match doi
           :method :get-params
           :verification verification}))

    ; Some things look like URLs but turn out not to be.
    (catch IllegalArgumentException _ nil)))

(defn try-doi-from-url-text
  "Match an embedded DOI, try various treatments to make it fit."
  [context url]

  (log/debug "try-doi-from-url-text input:" url)

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

        result (-> extant first normalize-doi-if-exists)

        ; Check that the domain in question has a relationship with this DOI.
        verification (cond
                       (landing-page-domain/domain-confirmed-for-doi? context url result) :confirmed-domain-prefix
                       (landing-page-domain/domain-recognised-for-doi? context url result) :recognised-domain-prefix
                       (landing-page-domain/domain-recognised? context url) :recognised-domain
                       :default nil)]

    (log/debug "try-doi-from-url-text result:" result)

    (evidence-log/log!
      (assoc (:log-default context)
             :i "p0006"
             :c "match-landingpage-url"
             :f "from-url-text"
             :u url
             :d result
             :e (e result)))

         (when (and result verification)
          {:match result
           :method :url-text
           :verification verification})))

(defn try-pii-from-url-text
  [context url]
  
  (log/debug "try-pii-from-url-text input:" url)

  (let [result (->> url
                    pii/find-candidate-piis
                    (map (comp (partial pii/validate-pii context) :value))
                    first)]
    
    (log/debug "try-pii-from-url-text result:" result)

    (evidence-log/log!
      (assoc (:log-default context)
             :i "p0007"
             :c "match-landingpage-url"
             :f "from-pii-from-url-text"
             :u url
             :d result
             :e (e result)))

    (when result
      {:match result
       :method :pii
       ; Trust the Crossref metadata lookup to report correctly.
       :verification :lookup})))

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

(defn url-equality
  "Are the two URLs equal? If so, how equal? Return one of:
   - :exact - the two paths match exactly
   - :basic - The domains and paths match but the query strings and schemes don't necessarily. 
              This is a good middle-ground heuristic.
   - nil - The two URLs don't match. "
  [a b]
  (try
    ; java.net.URI doesn't parse all URLs we find.
    (let [a-url (URL. a)
          b-url (URL. b)
          
          ; Try simple string equality first.
          equal (= a b)

          ; Otherwise check just paths and host. Ignore scheme and query params.
          basic-equal (and 
                        (= (-> a-url (.getHost) (.toLowerCase)) (-> a-url (.getHost) (.toLowerCase)))
                        (= (-> a-url (.getPath) (.toLowerCase)) (-> b-url (.getPath) (.toLowerCase))))]
      
      (cond equal :exact
            basic-equal :basic))

    ; If either is invalid, swallow it.
    (catch Exception e nil)))

(defn check-url-for-doi
  "Is the DOI checked as redirecting to this URL? Return one of :exact, :basic or nil."
  [context url doi]
  ; Don't retrieve robots as we check URL to DOI redirects,
  ; as we already have a reason to follow and we're not extracting content.
  (let [response (web/fetch-ignoring-robots context (crdoi/normalise-doi doi))
        final-url (:final-url response)
        equality (url-equality url final-url)]
    (condp = equality
      :exact :checked-url-exact
      :basic :checked-url-basic
      nil)))

(def verification-priorities
  "Mapping of match type to preference, lowest best."
  {; The DOI was visited and redirects back to this URL exactly.
    :checked-url-exact 1

   ; The DOI was visited and redirects back to this URL almost exactly.
   :checked-url-basic 2

   ; The DOI has a prefix that has been confirmed to be represented correctly by this domain.
   :confirmed-domain-prefix 3

   ; The DOI has a prefix that has been recognised for this domain.
   :recognised-domain-prefix 4

   ; The DOI is specified, and we recognise the domain, as belonging to some member, but no more.
   :recognised-domain 5

   ; The DOI is specified, but we can't vouch for it.
   :unrecognised-domain 6})

(defn sort-result-pairs
  "Take a list of [match-type doi] and sort by preference."
  [matches]
  (->> matches 
    ; First remove those items that don't have priorities. 
    ; This should never happen, but a mistaken nil would 
    ; otherwise be sorted into first position.
    (filter #(some-> % first verification-priorities))
    (sort-by #(some-> % first verification-priorities))))

(defn doi-from-meta-tags
  "Return the DOI from meta tags in the retrieved page.
   Return [verification-type doi], per the `priorities` data."
  [context url body-content]
  {:pre [(:domain-decision-structure context)]}
  (let [dois (when body-content (html/try-fetched-page-metadata-content context body-content))
        
        ; Now we build up a list of pairs of [doi method].

        ; Check them to see if they redirect here. Best case.
        ; Into [:checked-url-exact doi] or [:checked-url-basic doi]
        checked-dois (keep #(when-let [x (check-url-for-doi context url %)] [x %]) dois)

        ; Check them to see the prefix is confirmed against the domain.
        ; Into [doi :confirmed]
        confirmed-domain-dois (keep #(when (landing-page-domain/domain-confirmed-for-doi? context url %) [:confirmed-domain-prefix %]) dois)

        ; Check to see if the prefix is recognised as leading to the domain.
        recognised-domain-dois (keep #(when (landing-page-domain/domain-recognised-for-doi? context url %) [:recognised-domain-prefix %]) dois)

        ; Check to see if the domain is recognised at all.
        recognised-domain (keep #(when (landing-page-domain/domain-recognised? context %) [:recognised-domain %]) dois)

        ; Fall-back.
        fallbacks (map #(vector :unrecognised-domain %) dois)

        matches (concat checked-dois confirmed-domain-dois recognised-domain-dois recognised-domain fallbacks)

        ; Sort them best first, then take the first.
        result (-> matches sort-result-pairs first)]

    result))
    

(defn confirmed-doi-in-text-from-url
  "Visit the URL, scrape the text, return any DOI that redirects back to that page."
  [context url body-content]
  (let [response (html-match/process-html-content-observation
                   context
                   {:input-content body-content})

        candidates (->> response
                        :candidates
                        (map :value)
                        (map crdoi/normalise-doi)
                        distinct)

        ; Keep only those DOIs that have a prefix that has led to this domain.
        ; This avoids visiting every single DOI when we don't think they'll match.
        dois (filter (partial landing-page-domain/domain-recognised-for-doi? context url) candidates)

        ; Check them to see if they redirect here. Best case.
        ; As we're scraping the text, we can't afford to return anything that doesn't match.
        ; Into [:checked-url-exact doi] or [:checked-url-basic doi]
        matches (keep #(when-let [x (check-url-for-doi context url %)] [x %]) dois)

        ; Unlike the meta tags, there's no fallback in this case. Because we're scraping all DOIs from a page,
        ; and those DOIs aren't expressed with a specific purpose (they could be citations of other works or 
        ; the identiier of this one), if none redirect, we have to say that's a failure.
        ; Sort them best first, then take the first.
        result (-> matches sort-result-pairs first)]
    result))
    


(defn try-from-page-content
  "Failing previous checks, we need to go and retrieve the content."
  [context url]
  ; We're not indexing links from this page, so it's OK to ignore robots.txt .
  (let [body-content (:body (web/fetch-ignoring-robots context url))
        from-meta-tags (doi-from-meta-tags context url body-content)
        ; Only try this if we didn't get data from the meta tags.
        from-body (confirmed-doi-in-text-from-url context url body-content)]
    (cond
      from-meta-tags
      {:match (second from-meta-tags) :method "landing-page-meta-tag" :verification (first from-meta-tags)}
      
      from-body
      {:match (second from-body) :method "landing-page-text" :verification (first from-body)}

      :default nil)))



; This one function is responsible for all outgoing web traffic. Cache its results.
; Other results are derived algorithmically, so there's no use caching those.
(defn try-from-page-content-cached
  [context url]
  (log/debug "try-fetched-page-metadata-cached input:" url "skip-cache:" skip-cache)

  (if skip-cache
    
    ; Skip cache.
    (let [result (try-from-page-content context url)]
      ; Log type p0008 happens once per branch.
      (evidence-log/log!
        (assoc (:log-default context)
               :i "p0008"
               :c "match-landingpage-url"
               :f "from-page-metadata"
               :u url
               :d (:match result)
               :e (e result)
               :o "e"))
       result)

    ; Don't skip cache.
    ; This is JSON, so nils will be handled.
    (let [cached-result (when-let [value (store/get-string @redis-cache-store url)]
                          (json/read-str value :key-fn keyword))]

      (if cached-result
      ; Success or failure from Cache.
        (do
          (evidence-log/log!
            (assoc (:log-default context)
                 :i "p0008"
                 :c "match-landingpage-url"
                 :f "from-page-metadata"
                 :u url
                 :d (:match cached-result)
                 :e (e cached-result)
                 :o "c"))
           cached-result)

        ; No result in cache, 
        (let [result (try-from-page-content context url)]
          (if result
            (redis/set-string-and-expiry-seconds @redis-cache-store url @success-expiry-seconds (json/write-str result))
            (redis/set-string-and-expiry-seconds @redis-cache-store url @failure-expiry-seconds "NULL"))

            (evidence-log/log!
              (assoc (:log-default context)
                 :i "p0008"
                 :c "match-landingpage-url"
                 :f "from-page-metadata"
                 :u url
                 :d (:match result)
                 :e (e (:match result))
                 :o "e"))
            result)))))



(defn match-landing-page-url-candidate
  "Try a multitude of ways to match, cheapest first."
  [context candidate]

  (let [url (:value candidate)
        result (or
                 (try-from-get-params context url)
                 (try-doi-from-url-text context url)
                 (try-pii-from-url-text context url)
                 (try-from-page-content-cached context url))]
    (when result (merge candidate result))))
