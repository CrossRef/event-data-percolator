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
    (condp = (:percolator-duplicate-storage env "s3")
      ; Memory for unit testing ONLY.
      "memory" (memory/build)
      "s3" (s3/build (:percolator-s3-key env) (:percolator-s3-secret env) (:percolator-duplicate-region-name env) (:percolator-duplicate-bucket-name env)))))

(defn into-map [f coll]
  (into {} (map (juxt identity f)) coll))

(defn dedupe-action
  "Take an Action, decorate with 'duplicate' field IFF there's an action ID.
   If there's duplicate information (a chunk of JSON representing a previous Evidence Record), associate it with the Action, otherwise pass it through.
   The store is updated with the values in the 'push' process.
   Step 3 from docs."
  [action evidence-record-id]

  (if-let [id (:id action)]
    (let [k (str "action/" id)
         duplicate-info (store/get-string @action-dedupe-store k)
         duplicate-info-parsed (when duplicate-info (json/read-str duplicate-info))]

      (if duplicate-info-parsed
        (assoc action :duplicate duplicate-info-parsed)
        action))
    action))


(defn process-observations-candidates
  "Step Process all the observations of an Action to generate Candidates. Collect Candidates.
   If it's a duplicate action, the candidate extractor won't run.
   Step 4 from docs."
  [action domain-set  web-trace-atom]
  (let [observations (:observations action)
        duplicate? (:duplicate action)
        processed-observations (map #(observation/process-observation % duplicate? domain-set web-trace-atom) observations)]
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

(defn match-weight
  "Weighting for matches when dedupling and there's a conflict."
  [match]
  (condp = (:type match)
    :doi-url 1 
    :plain-doi 2
    :landing-page-url 3
    10))

(defn dedupe-by-val-substring
  "Dedupe matches where the value is a subtring of any another action.
   This is only called in the context of dedupe-matches, where the inputs will already have been deduped by the match"
  [matches]
  (remove (fn [match]
            (some #(and (not= match %)
                        (> (.indexOf (:value % "") (:value match "")) -1)) matches))
          matches))

(defn dedupe-matches
  "Deduplicate matches.
   Step 5.5 from docs."
  [action]
   (let [matches (:matches action)

         ; Dedupe by the :match field (i.e. the matched DOI).
         groups (vals (group-by :match matches))

         ; Within each group, dedupe by exact value duplicate, e.g. :landing-page-url and :doi-url both say the same URL.
         minus-exact-dupliates (map (fn [matches]
                                      (map #(first (sort-by match-weight %)) (vals (group-by :value matches))))
                                    groups)

         ; Within each group, if one value is a substring of another, exclude it, e.g. :plaintext-doi and :doi-url
         removing-substrings (map dedupe-by-val-substring minus-exact-dupliates)

         ; Glom groups.
         all-matches (apply concat removing-substrings)]

    (assoc action :matches all-matches)))

(defn create-event-from-match
  [input-bundle action match]
  ; Provide default subject metadata if not supplied.
  (let [subj (merge {:pid (:url action)} (:subj action {}))
        ; For the obj, include the DOI URL as :pid,
        ; but also include the input URL as the :url
        ; if it wasn't a URL, include the PID as the URL.
        obj-url (when (and (#{:landing-page-url :shortdoi-url :doi-url} (:type match))
                           (:value match))
                  (:value match))
        obj (merge {:pid (:match match)
                    :url (or obj-url
                             (:match match))} (:obj action {}))
        base-event {:id (str (UUID/randomUUID))
                    :source_token (:source-token input-bundle)
                    :subj_id (:url action)
                    :obj_id (:match match)
                    :relation_type_id (:relation-type-id action)
                    :source_id (:source-id input-bundle)
                    :action (:action-type action "add")
                    :occurred_at (str (:occurred-at action))
                    :subj subj
                    :obj obj
                    :evidence_record (:url input-bundle)}

        with-license (if-let [license (:license input-bundle)] (assoc base-event :license license) base-event)]
      with-license))

(defn create-event-from-extra-event
  "Given an extra Event in an Action, expand it to include the full compliment of fields."
  [input-bundle event]
  (let [base-event (merge {:evidence_record (:url input-bundle)
                           :id (str (UUID/randomUUID))
                           :source_token (:source-token input-bundle)
                           :source_id (:source-id input-bundle)
                           :action "add"}
                          event)
        with-license (if-let [license (:license input-bundle)]
                       (assoc base-event :license license) base-event)]
    with-license))

(defn create-events-for-action
  "Update action to include a seq of Events generated from observations in the Action. Plus extra-events if included, and if there were any matches."
  [input-bundle action]
  
  (let [events-from-matches (map (partial create-event-from-match input-bundle action) (:matches action))
        events-from-extras (when (not-empty (:matches action)) (map (partial create-event-from-extra-event input-bundle) (:extra-events action)))
        events (concat events-from-matches events-from-extras)]
  (assoc action
    :events events)))

(defn store-action-duplicates
  "Save all action IDs from a bundle into duplicate records. Called on 'push'."
  [bundle]
  (let [actions (mapcat :actions (:pages bundle))]
    (doseq [action actions]
      ; Action ID might not be set.
      (when-let [action-id (:id action)]
        (store/set-string @action-dedupe-store (str "action/" action-id) (json/write-str {:evidence-record-id (:id bundle) :action-id action-id}))))))


