(ns event-data-percolator.input-bundle
  "Process and Input Bundle."
  (:require [event-data-percolator.action :as action]
            [clj-time.core :as clj-time]
            [clj-time.format :as clj-time-format]
            [config.core :refer [env]]
            [schema.core :as s])
  (:import [java.util UUID]))

(def date-format
  (clj-time-format/formatters :basic-date))

(def bundles-schema
  {:source-token s/Str
   :source-id s/Str
   (s/optional-key :agent) s/Any
   ; Extra per-bundle info.
   (s/optional-key :extra) s/Any
   :pages
   [{; Extra per-page info.
     (s/optional-key :extra) s/Any
     :actions
     [{; Extra per-action info.
       (s/optional-key :extra) s/Any
       :url s/Str
       :relation-type-id s/Str
       :occurred-at s/Str
       :id s/Str
       :subj s/Any
       :observations [{:type s/Str
                       ; Extra per-observation info.
                       (s/optional-key :extra) s/Any
                       (s/optional-key :sensitive) s/Bool
                       (s/optional-key :input-content) s/Str
                       (s/optional-key :input-url) s/Str}]}]}]})

(def evidence-url-prefix
  "Prefix where records are stored."
  "evidence/")

(defn generate-url
  "Generate a URL for the Evidence Record (where it will be accessible)"
  [id]
  (str (:evidence-url-base env) "/" evidence-url-prefix id))

(defn validation-errors
  "Return validation errors, or nil on success."
  [bundle]
  (try
    (s/validate bundles-schema bundle)
    nil
    (catch RuntimeException e e)))

(defn map-actions
  "Map over actions within an Input Bundle, leaving the rest intact."
  [f bundle]
  (assoc bundle
    :pages (map (fn [page]
      (assoc page
        :actions (map f (:actions page)))) (:pages bundle))))

(defn id-and-timestamp
  "Associate an evidence and date stamp at the same time.
   ID has YYYYMMDD prefix to make downstream analysis workflows a bit easier."
  [bundle]
  (let [now (clj-time/now)
        id (str
             (clj-time-format/unparse date-format now)
             (UUID/randomUUID))
        now-str (str now)]
    (assoc bundle
      :id id
      :timestamp now-str
      :url (generate-url id))))

(defn dedupe-actions
  "Dedupe actions in an input bundle.
  Step 3 in docs."
  [bundle]
  (map-actions #(action/dedupe-action % (:id bundle)) bundle))

(defn candidates
  "Produce candidates in input bundle.
  Step 4 in docs."
  [bundle domain-set]
  (map-actions #(action/process-observations-candidates % domain-set) bundle))

(defn match
  "Match candidates in input bundle.
  Step 4 in docs."
  [bundle web-trace-atom]
  (map-actions #(action/match-candidates % web-trace-atom) bundle))

(defn events
  "Generate an Event for each candidate match."
  [bundle]
  (map-actions (partial action/create-events-for-action bundle) bundle))

(def percolator-version (System/getProperty "event-data-percolator.version"))

(defn process
  [bundle domain-artifact-version domain-set]
  ; an atom that's passed around to functions that might want to log which URLs they access
  ; and their respose codes.
  (let [web-trace-atom (atom [])
        result (->
            bundle
            id-and-timestamp
            dedupe-actions
            (candidates domain-set)
            (match web-trace-atom)
            (assoc-in [:percolator :artifacts :domain-set-artifact-version] domain-artifact-version)
            (assoc-in [:percolator :software-version] percolator-version)
            events)
        ; There are lazy sequences in here. Force the entire structure to be realized.
        ; This is necessary because the web-trace-atom's value is observed at this point,
        ; so we need to be confident that everything that's going to happen, has happened.
        realized (clojure.walk/postwalk identity result)
        with-trace (assoc realized :web-trace @web-trace-atom)]
    with-trace))

(defn extract-all-events
  "Extract all events for pushing downstream."
  [bundle]
  (mapcat
    (fn [page]
      (mapcat :events (:actions page)))
    (:pages bundle)))
