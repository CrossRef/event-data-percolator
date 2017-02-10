(ns event-data-percolator.action
  "An Action is something that happened about a single external object, made by a list of Observations.
   This is mostly tested by input-bundle-tests"
  (:require [event-data-percolator.observation :as observation]
            [event-data-percolator.match :as match]
            [config.core :refer [env]]
            [crossref.util.doi :as crdoi]
            [event-data-common.storage.s3 :as s3]
            [event-data-common.storage.redis :as redis]
            [event-data-common.storage.memory :as memory]
            [event-data-common.storage.store :as store]
            [clojure.data.json :as json])
  (:import [java.util UUID]))


(def action-dedupe-store
  (delay
    (condp = (:storage env "s3")
      ; Memory for unit testing ONLY.
      "memory" (memory/build)
      ; Redis can be used for component testing ONLY. Reuse the redis connection.
      "s3" (s3/build (:s3-key env) (:s3-secret env) (:s3-region-name env) (:s3-bucket-name env)))))

(defn into-map [f coll]
  (into {} (map (juxt identity f)) coll))

(defn dedupe-action
  "Take an Action, decorate with 'duplicate' field.
   If there's duplicate information (a chunk of JSON representing a previous Evidence Record), associate it with the Action, otherwise pass it through.
   Step 3 from docs."
  [action evidence-record-id]

  (let [id (:id action)
        k (str "action/" id)
        duplicate-info (store/get-string @action-dedupe-store k)
        duplicate-info-parsed (when duplicate-info (json/read-str duplicate-info))]

    (when-not duplicate-info
      (store/set-string @action-dedupe-store k (json/write-str {:evidence-record-id evidence-record-id :action-id id})))

    (if duplicate-info-parsed
      (assoc action :duplicate duplicate-info-parsed)
      action)))

(defn process-observations-candidates
  "Step Process all the observations of an Action to generate Candidates. Collect Candidates.
   If it's a duplicate action, the candidate extractor won't run.
   Step 4 from docs."
  [action domain-set]
  (let [observations (:observations action)
        duplicate? (:duplicate action)
        processed-observations (map #(observation/process-observation % duplicate? domain-set) observations)]
    (-> action
        (assoc :processed-observations processed-observations)
        (dissoc :observations))))

(defn match-candidates
  "Attempt to match all candidates into DOIs.
   Step 5 from docs."
  [action web-trace-atom]
   (let [matches (mapcat (fn [observation]
                       (map #(match/match-candidate % web-trace-atom) (:candidates observation)))
                     (:processed-observations action))
         ; if the :match field wasn't set then it didn't work. Remove these.
         successful (filter :match matches)]
    (assoc action :matches successful)))

(defn create-event-from-match
  [input-bundle action match]
  ; Provide default subject metadata if not supplied.
  (let [subj (merge {:url (:url action)} (:subj action {}))]
    {:uuid (str (UUID/randomUUID))
     :source_token (:source-token input-bundle)
     :subj_id (:url action)
     :obj_id (:match match)
     :relation_type_id (:relation-type-id action)
     :source_id (:source-id input-bundle)
     :action (:action-type action "add")
     :occurred_at (str (:occurred-at action))
     :subj subj}))

(defn create-events-for-action
  "Return a seq of Events generated from the Action"
  [input-bundle action]
  (assoc action
    :events (map (partial create-event-from-match input-bundle action) (:matches action))))
