(ns event-data-percolator.util.html
  "Functions to deal with HTML"
  (:require [clojure.tools.logging :as log]
            [crossref.util.doi :as crdoi]
            [event-data-percolator.util.doi :as doi]
            [event-data-common.evidence-log :as evidence-log]
            [config.core :refer [env]]
            [robert.bruce :refer [try-try-again]]
            [cemerick.url :as cemerick-url])
  (:import [java.net URL]
           [org.jsoup Jsoup]))

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

(defn try-fetched-page-metadata-content
  "Extract DOI from Metadata tags."
  [context text]
  (try
    (when text
      (let [document (Jsoup/parse text)

            ; Get specific attribute values from named elements.
            interested-values (mapcat (fn [[selector attr-name]]
                                                    (->>
                                                      (.select document selector)
                                                      (map #(.attr % attr-name))
                                                      distinct))
                                                  interested-tag-attrs)

            ; Try to normalize by removing recognised prefixes, then resolve
            extant-dois (doall (keep (comp (partial doi/validate-cached context) crdoi/normalise-doi) interested-values))]

        (distinct extant-dois)))

    ; We're getting text from anywhere. Anything could happen.
    (catch Exception ex (do
      (log/warn "Error parsing HTML for DOI.")
      (.printStackTrace ex)
      nil))))
