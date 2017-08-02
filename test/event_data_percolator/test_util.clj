(ns event-data-percolator.test-util
  (:require [clojure.data.json :as json]))

; Mock context. No tests should rely on the values here.
(def mock-context
  {:id "20170101-myagent-1234"
   :domain-set #{"example.com"}
   :domain-list-artifact-version "http://d1v52iseus4yyg.cloudfront.net/a/crossref-domain-list/versions/1482489046417"})

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

