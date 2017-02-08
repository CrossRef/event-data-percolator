(ns event-data-percolator.matchers.landing-page-url
  (:require [org.httpkit.client :as http]
            [event-data-percolator.web :as web]
            [clojure.tools.logging :as log]
            [crossref.util.doi :as crdoi]
            [event-data-percolator.util.doi :as doi]
            [event-data-percolator.util.pii :as pii]
            [robert.bruce :refer [try-try-again]]
            [cemerick.url :as cemerick-url])
  (:import [java.net URL]
           [org.jsoup Jsoup]))

(def whole-doi-re #"^10\.\d{4,9}/[^\s]+$")
(def doi-re #"(10\.\d{4,9}(?:/|%2F|%2f)[^\s]+)")
(def shortdoi-find-re #"(?:(?:(?:dx.)?doi.org/)|10/)(?:info:doi/|urn:|doi:)?([a-zA-Z0-9]+)")

(defn normalize-doi-if-exists [doi]
  (when doi (crdoi/normalise-doi doi)))

(defn try-from-get-params
  "If there's a DOI in a get parameter of a URL, find it"
  [url]
  (try
    (let [params (-> url cemerick-url/query->map clojure.walk/keywordize-keys)
          doi-like-values (keep (fn [[k v]] (when (re-matches whole-doi-re v) v)) params)
          extant (keep doi/resolve-doi-maybe-escaped doi-like-values)]
      (-> extant first normalize-doi-if-exists))

    ; Some things look like URLs but turn out not to be.
    (catch IllegalArgumentException _ nil)))

(defn try-doi-from-url-text
  [url]
  "Match an embedded DOI, try various treatments to make it fit."
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

        extant (keep doi/resolve-doi-maybe-escaped candidates)]

    (-> extant first normalize-doi-if-exists)))

(defn try-pii-from-url-text
  [url]
  (->>
    url
    pii/find-candidate-piis
    (map (comp pii/validate-pii :value))
    first))

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
  [
    ; e.g. http://jnci.oxfordjournals.org/content/108/6/djw160.full
    "span.slug-doi"])

(defn try-fetched-page-metadata-content
  "Extract DOI from Metadata tags."
  [text]
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

          interested-values (concat interested-attr-values interested-text-values)

          extant (keep doi/resolve-doi-maybe-escaped interested-values)]

      (-> extant first normalize-doi-if-exists))))

(defn try-fetched-page-metadata
  [url web-trace-atom]
  (-> url (web/fetch web-trace-atom) :body try-fetched-page-metadata-content))

(defn unchunk [s]
  (when (seq s)
    (lazy-seq
      (cons (first s)
            (unchunk (next s))))))

(defn match-landing-page-url
  [url web-trace-atom]
  "Try a multitude of ways to match, cheapest first."
  ; Step through lazy seq, an item at a time.
  (first
    (unchunk
      (concat
        (try-from-get-params url)
        (try-doi-from-url-text url)
        (try-pii-from-url-text url)
        (try-fetched-page-metadata url web-trace-atom)))))

(defn match-landing-page-url-candidate
  [candidate web-trace-atom]
  (assoc candidate
    :match (match-landing-page-url (:value candidate) web-trace-atom)))
