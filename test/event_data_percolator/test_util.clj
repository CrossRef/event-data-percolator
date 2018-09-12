(ns event-data-percolator.test-util
  (:require [clojure.data.json :as json]
            [event-data-common.landing-page-domain :as landing-page-domain]
            [clojure.java.io :refer [reader resource]]))

(def test-structure
  (-> "artifacts/domain-decision-structure.json"
      resource
      reader
      json/read
      landing-page-domain/parse-domain-decision-structure))

(def mock-context
  "Mock context, including a known domain-decision structure.
   This can be extended as needed in tests."
  {:id "20170101-myagent-1234"
   :domain-decision-structure test-structure})

; Mock evidence record. No tests should rely on the values here.
(def mock-evidence-record
  {})

; These are Fake HTTP responses.

(defn doi-ok
  "Fake OK return from DOI Handle API."
  [handle]
  {:status 200 :body (json/write-str {"handle" handle})})

(defn short-doi-ok
  "Fake OK return from Short DOI Handle API."
  [handle]
  {:status 200 :body (json/write-str {"values" [{"type" "HS_ALIAS" "data" {"value" handle}}]})})

(defn doi-not-found
  "Fake OK return from DOI proxy."
  []
  {:status 404})

