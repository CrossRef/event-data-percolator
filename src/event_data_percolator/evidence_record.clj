(ns event-data-percolator.evidence-record
  "Process an Evidence Record."
  (:require [event-data-percolator.action :as action]
            [event-data-percolator.util.util :as util]
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
   (s/optional-key :artifacts) s/Any
   
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

(def failed-evidence-url-prefix
  "Prefix where failed records are stored."
  "failed-evidence/")

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
  "Map over actions within an Input Evidence Record, leaving the rest intact.
   The action ID is inserted into the log-default object in the context.
   call (f context evidence-record action)"
  [context f evidence-record]
  (assoc evidence-record
    :pages (map (fn [page]
      (assoc page
        :actions (map #(f
                        (assoc-in context [:log-default :a] (:id %))
                        evidence-record
                        %)
                        (:actions page)))) (:pages evidence-record))))

(defn url
  "Associate a URL based on the ID."
  [context evidence-record]
  (assoc evidence-record
    :url (generate-url (:id evidence-record))))

(defn dedupe-actions
  "Dedupe actions in an input Evidence Record."
  [context evidence-record]
  (log/debug "Deduping in " (:id evidence-record))
  (map-actions context action/dedupe-action evidence-record))

(defn candidates
  "Produce candidates in input evidence-record."
  [context evidence-record]
  (log/debug "Candidates in " (:id evidence-record))
  (map-actions context action/process-observations-candidates evidence-record))

(defn match
  "Match candidates in input evidence-record."
  [context evidence-record]
  (log/debug "Match in " (:id evidence-record))
  (map-actions context action/match-candidates evidence-record))

(defn dedupe-matches
  "Dedupe matches WITHIN an action."
  [context evidence-record]
  (log/debug "Dedupe in " (:id evidence-record))
  (map-actions context action/dedupe-matches evidence-record))

(defn events
  "Generate an Event for each candidate match, update extra Events."
  [context evidence-record]
  (log/debug "Events in " (:id evidence-record))
  (map-actions context action/create-events-for-action evidence-record))

(defn process
  [context evidence-record]
  (let [result (->>
          evidence-record
          (url context)
          (dedupe-actions context)
          (candidates context)
          (match context)
          (dedupe-matches context)
          (#(assoc-in % [:percolator :artifacts :domain-set-artifact-version] (:domain-list-artifact-version context)))
          (#(assoc-in % [:percolator :software-version] util/percolator-version))
          (events context))

        ; There are lazy sequences in here. Force the entire structure to be realized.
        realized (clojure.walk/postwalk identity result)]
    (log/debug "Finished processing" (:id realized))
    realized))

(defn extract-all-events
  "Extract all events for pushing downstream."
  [evidence-record]
  (mapcat
    (fn [page]
      (mapcat :events (:actions page)))
    (:pages evidence-record)))
