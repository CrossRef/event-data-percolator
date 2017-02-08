(ns event-data-percolator.observation-types.url
  "Filter a URL by whether or not it looks like a candidate."
  (:import [java.net URL]))

(def doi-proxies #{"doi.org" "dx.doi.org" "dx.crossref.org"})

(defn is-valid-matching-domain
  [url domain-set]
  "Is this URL valid and does it match the domain set?"
  (try (-> (new URL url)
           (.getHost)
           domain-set)
    (catch Exception e nil)))

(defn url-to-doi-url-candidate
  [url]
  "If this looks like a DOI url or a ShortDOI url, return it."
  (let [has-doi-resolver (is-valid-matching-domain url doi-proxies)]
    (when has-doi-resolver
      ; It's a valid URL by this point.
      (let [url-path (-> (new URL url) (.getPath))]
        (if (re-matches #"^/\d+\.\d+/.*$" url-path)
          {:type :doi-url :value url}
          {:type :shortdoi-url :value url})))))

(defn url-to-short-doi-url-candidate
  [url]
  "If this looks like a ShortDOI url, return it."
  (when
    (is-valid-matching-domain url doi-proxies)
    {:type :shortdoi-url :value url}))

(defn url-to-landing-page-url-candidate
  [url landing-page-domain-set]
  "If this looks like a landing page url, return it."
  (when
    (is-valid-matching-domain url landing-page-domain-set)
    {:type :landing-page-url :value url}))

(defn process-url-observation
  [observation landing-page-domain-set]
  "Process a url observation into a candidate url. Check if valid and if on the domain list."
  (let [input (:input-url observation "")
        
        ; single input input, but candidate responses are always lists.
        candidates (remove nil? [(url-to-doi-url-candidate input)
                                 (url-to-landing-page-url-candidate input landing-page-domain-set)])]
    (assoc observation :candidates candidates)))
