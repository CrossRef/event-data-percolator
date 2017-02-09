(ns event-data-percolator.action
  "An Action is something that happened about a single external object, made by a list of Observations."
  (:require [event-data-percolator.observation :as observation]
            [event-data-percolator.match :as match]
            [config.core :refer [env]]
            [crossref.util.doi :as crdoi]
            [event-data-common.storage.s3 :as s3]
            [event-data-common.storage.redis :as redis]
            [event-data-common.storage.store :as store]
            [clojure.data.json :as json])
  (:import [java.util UUID]))


(def redis-prefix
  "Unique prefix applied to every key."
  "event-data-bus:")

(def default-redis-db-str "0")

(def redis-store
  "A redis connection for storing subscription and short-term information."
  (delay (redis/build redis-prefix (:redis-host env) (Integer/parseInt (:redis-port env)) (Integer/parseInt (get env :redis-db default-redis-db-str)))))

(def action-dedupe-store
  (delay
    (condp = (:storage env "s3")
      ; Redis can be used for component testing ONLY. Reuse the redis connection.
      "redis" @redis-store
      ; S3 is suitable for production.
      "s3" (s3/build (:s3-key env) (:s3-secret env) (:s3-region-name env) (:s3-bucket-name env)))))

(def domain-set #{"example.com" "figshare.com" })

(defn into-map [f coll]
  (into {} (map (juxt identity f)) coll))

(defn dedupe-action
  "Take an Action, decorate with 'duplicate' field.
   If there's duplicate information (a chunk of JSON representing a previous Evidence Record), associate it with the Action, otherwise pass it through.
   Step 3 from docs."
  [action]
  (let [id (:id action)]
    ; If there's no ID, pass through.
    (if-not id
      action
      (let [duplicate-info (store/get-string @action-dedupe-store (str "/visit/" id))
            duplicate-info-parsed (when duplicate-info (json/read-str duplicate-info))]
        (if duplicate-info-parsed
          (assoc action :duplicate duplicate-info-parsed)
          action)))))

(defn process-observations-candidates
  "Step Process all the observations of an Action to generate Candidates. Collect Candidates.
   Step 4 from docs."
  [action]
  (let [observations (:observations action)
        processed-observations (map #(observation/process-observation % domain-set) observations)]
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
