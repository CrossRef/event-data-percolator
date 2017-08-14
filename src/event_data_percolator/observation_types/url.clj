(ns event-data-percolator.observation-types.url
  "Filter a URL by whether or not it looks like a candidate."
  (:require [event-data-percolator.util.web :as web])
  (:import [java.net URL]))

(def doi-proxies #{"doi.org" "dx.doi.org" "dx.crossref.org"})

(defn is-valid-matching-domain
  "Is this URL valid and does it match the domain set?"
  [url domain-set]
  (try (-> (new URL url)
           (.getHost)
           domain-set)
    (catch Exception e nil)))

(defn url-to-doi-url-candidate
  "If this looks like a DOI url or a ShortDOI url, return it."
  [url]
  (let [has-doi-resolver (is-valid-matching-domain url doi-proxies)]
    (when has-doi-resolver
      ; It's a valid URL by this point.
      (let [url-path (-> (new URL url) (.getPath))]
        (if (re-matches #"^/\d+\.\d+/.*$" url-path)
          {:type :doi-url :value url}
          {:type :shortdoi-url :value url})))))

(defn url-to-short-doi-url-candidate
  "If this looks like a ShortDOI url, return it."
  [url]
  (when
    (is-valid-matching-domain url doi-proxies)
    {:type :shortdoi-url :value url}))

(defn url-to-landing-page-url-candidate
  "If this looks like a landing page url, return it."
  [url landing-page-domain-set]
  (let [doi-candidate (url-to-doi-url-candidate url)
        should-visit (web/should-visit-landing-page? landing-page-domain-set url)]

    ; Don't treat this as a landing page URL if it looks like a DOI.
    (when (and (not doi-candidate) should-visit)
      {:type :landing-page-url :value url})))

(defn process-url-observation
  "Process a url observation into a candidate url. Check if valid and if on the domain list."
  [context observation]

  (let [input (:input-url observation "")
        
        ; single input input, but candidate responses are always lists.
        candidates (remove nil? [(url-to-doi-url-candidate input)
                                 (url-to-landing-page-url-candidate input (:domain-set context))])]
    (assoc observation :candidates candidates)))
