(ns event-data-percolator.match
  "Convert candidate DOIs and candidate URLs (DOIs and Landing Pages) into real DOIs.
   Each candidate should resolve to a single DOI. 
   Matcher functions should associate a :match field to the candidate object and return it.
   If a matcher function isn't succesful then the :match field should be nil.
   A list atom is passed around. Functions that access the web can store their requests and responses here.
   "
  (:require [event-data-percolator.matchers.doi-url :as doi-url]
            [event-data-percolator.matchers.pii :as pii]
            [event-data-percolator.matchers.plain-doi :as plain-doi]
            [event-data-percolator.matchers.shortdoi-url :as shortdoi-url]
            [event-data-percolator.matchers.landing-page-url :as landing-page-url]
            [event-data-common.evidence-log :as evidence-log]))

; Each of these names corresponds to an Evidence Log type, so be sure to keep the Evidence Logs docs in step.
(def candidate-processors {
  :doi-url doi-url/match-doi-url-candidate
  :pii pii/match-pii-candidate
  :plain-doi plain-doi/match-plain-doi-candidate
  :shortdoi-url shortdoi-url/match-shortdoi-url-candidate
  :landing-page-url landing-page-url/match-landing-page-url-candidate})

(defn match-unrecognised-type
  [context candidate]
  (assoc candidate 
         :match nil
         :error :unrecognised-candidate-type))

(defn match-candidate
  [context candidate]
  (let [t (:type candidate)
        f (candidate-processors t match-unrecognised-type)
        result (f context candidate)]
    
    ; Log the method used for matching and the result.
    ; Don't show the input, as this is diverse.
    ; Methods in the matchers namespace do their own detailed logging.
    ; The :f field for the matchers is the name of the namespace (e.g. "match-landingpage-url")
    (evidence-log/log!
            (assoc (:log-default context)
                 :c "match"
                 :f (name t)
                 :d result
                 :s (if result "t" "f")))

    result))
