(ns event-data-percolator.test-util
  (:require [clojure.data.json :as json]))

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

