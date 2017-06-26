(ns event-data-percolator.evidence-record
  "Process an Evidence Record."
  (:require [event-data-percolator.action :as action]
            [clj-time.core :as clj-time]
            [clj-time.format :as clj-time-format]
            [config.core :refer [env]]
            [schema.core :as s]
            [clojure.tools.logging :as log]))

(def evidence-record-schema
  {; id like 20170101-twitter-23781b07-a198-4607-a1b7-0752224411c6
   :id s/Str

   ; ISO8601 timestamp
   :timestamp s/Str
   
   ; JWT for claims. This will be removed during processing.
   :jwt s/Str 
   
   :source-token s/Str
   :source-id s/Str
   (s/optional-key :license) s/Str

   (s/optional-key :agent) s/Any
   
   ; Extra per-evidence-record info.
   (s/optional-key :extra) s/Any
  
   :pages
   [{; Extra per-page info.
     (s/optional-key :extra) s/Any
     (s/optional-key :url) s/Str
     :actions
     [{; Extra per-action info.
       (s/optional-key :extra) s/Any
       :url s/Str
       :relation-type-id s/Str
       :occurred-at s/Str
       ; Action ID is optional but recommended except for specific cases. 
       ; See evidence-record-test/action-id-can-be-ommitted
       (s/optional-key :id) s/Str
       :subj s/Any
       ; Extra Events that are only carried through if observations match.
       (s/optional-key :extra-events) s/Any
       :observations [{:type s/Str
                       ; Extra per-observation info.
                       (s/optional-key :extra) s/Any
                       (s/optional-key :sensitive) s/Bool
                       (s/optional-key :input-content) s/Str
                       (s/optional-key :input-url) s/Str
                       (s/optional-key :ignore-robots) s/Bool}]}]}]})

(def evidence-url-prefix
  "Prefix where records are stored."
  "evidence/")

(defn generate-url
  "Generate a URL for the Evidence Record (where it will be accessible)"
  [id]
  (str (:global-evidence-url-base env) "/" evidence-url-prefix id))

(defn validation-errors
  "Return validation errors, or nil on success."
  [evidence-record]
  (try
    (s/validate evidence-record-schema evidence-record)
    nil
    (catch RuntimeException e e)))

(defn map-actions
  "Map over actions within an Input Evidence Record, leaving the rest intact."
  [f evidence-record]
  (assoc evidence-record
    :pages (map (fn [page]
      (assoc page
        :actions (map f (:actions page)))) (:pages evidence-record))))

(defn url
  "Associate a URL based on the ID."
  [evidence-record]
  (assoc evidence-record
    :url (generate-url (:id evidence-record))))

(defn dedupe-actions
  "Dedupe actions in an input Evidence Record."
  [evidence-record]
  (log/debug "Deduping in " (:id evidence-record))
  (map-actions #(action/dedupe-action % (:id evidence-record)) evidence-record))

(defn candidates
  "Produce candidates in input evidence-record."
  [evidence-record domain-set web-trace-atom]
  (log/debug "Candidates in " (:id evidence-record))
  (map-actions #(action/process-observations-candidates % domain-set web-trace-atom) evidence-record))

(defn match
  "Match candidates in input evidence-record."
  [evidence-record web-trace-atom]
  (log/debug "Match in " (:id evidence-record))
  (map-actions #(action/match-candidates % web-trace-atom) evidence-record))

(defn dedupe-matches
  "Dedupe matches WITHIN an action."
  [evidence-record]
  (log/debug "Dedupe in " (:id evidence-record))
  (map-actions action/dedupe-matches evidence-record))

(defn events
  "Generate an Event for each candidate match, update extra Events."
  [evidence-record]
  (log/debug "Events in " (:id evidence-record))
  (->> evidence-record
      (map-actions (partial action/create-events-for-action evidence-record))))

(def percolator-version (System/getProperty "event-data-percolator.version"))

(defn process
  [evidence-record domain-artifact-version domain-set]
  ; an atom that's passed around to functions that might want to log which URLs they access
  ; and their respose codes.
  (let [web-trace-atom (atom [])
        result (->
            evidence-record
            url
            dedupe-actions
            (candidates domain-set web-trace-atom)
            (match web-trace-atom)
            dedupe-matches
            (assoc-in [:percolator :artifacts :domain-set-artifact-version] domain-artifact-version)
            (assoc-in [:percolator :software-version] percolator-version)
            events)
        ; There are lazy sequences in here. Force the entire structure to be realized.
        ; This is necessary because the web-trace-atom's value is observed at this point,
        ; so we need to be confident that everything that's going to happen, has happened.
        realized (clojure.walk/postwalk identity result)
        with-trace (assoc realized :web-trace @web-trace-atom)]
    (log/debug "Finished processing" (:id with-trace))
    with-trace))

(defn extract-all-events
  "Extract all events for pushing downstream."
  [evidence-record]
  (mapcat
    (fn [page]
      (mapcat :events (:actions page)))
    (:pages evidence-record)))
