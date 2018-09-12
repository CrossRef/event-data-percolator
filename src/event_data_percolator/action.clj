(ns event-data-percolator.action
  "An Action is something that happened about a single external object, made by a list of Observations.
   This is mostly tested by evidence-record-tests"
  (:require [event-data-percolator.observation :as observation]
            [event-data-percolator.match :as match]
            [event-data-percolator.util.web :as web]
            [config.core :refer [env]]
            [crossref.util.doi :as crdoi]
            [event-data-common.storage.s3 :as s3]
            [event-data-common.storage.redis :as redis]
            [event-data-common.storage.memory :as memory]
            [event-data-common.storage.store :as store]
            [event-data-common.url-cleanup :as url-cleanup]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log])
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
   The store is updated with the values in the 'push' process."
  [context evidence-record action]
  (log/debug "dedupe-action id:" (:id action))
  (if-let [id (:id action)]
    (let [k (str "action/" id)
         duplicate-info (store/get-string @action-dedupe-store k)
         duplicate-info-parsed (when duplicate-info (json/read-str duplicate-info))]

      (if duplicate-info-parsed
        (assoc action :duplicate duplicate-info-parsed)
        action))
    action))


(defn process-observations-candidates
  "Process all the observations of an Action to generate Candidates. Collect Candidates.
   If it's a duplicate action, the candidate extractor won't run."
  [context evidence-record action]
  (log/debug "process-observations-candidates")
  (let [observations (:observations action)
        duplicate? (:duplicate action)
        processed-observations (map
                                #(observation/process-observation context % duplicate?)
                                observations)]
    (-> action
        (assoc :processed-observations processed-observations)
        (dissoc :observations))))

(defn match-candidates
  "Attempt to match all candidates into DOIs."
  [context evidence-record action]
   (log/debug "match-candidates")
   (let [matches (mapcat (fn [observation]
                       (map #(match/match-candidate context %) (:candidates observation)))
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
  "Deduplicate matches."
  [context evidence-record action]
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

(defn canonical-url-for-action
  "If a canonical URL was detected in one of the observations, return this. Else nil."
  [action]
  (let [observations (:processed-observations action)
        unique-canonical-urls (set (keep :canonical-url observations))]
    (when (= 1 (count unique-canonical-urls))
      (first unique-canonical-urls))))

(defn final-url-for-action
  "Return the final URL from any observations. This can occur if there were redirects."
  [action]
  (let [observations (:processed-observations action)
        final-urls (set (keep :final-url observations))]
    ; We only expect to get one of these per Action.
    ; If we do get duplicates, predictable default behaviour is take the first.
    (first final-urls)))

(defn best-subj-url-for-action
  "Return the best URL that represents the subject. This is, in order of preference:
   - the canonical URL
   - the final URL that was visited in any sequence of redirects
   - the action's :url parameter"
  [action]
  (or (canonical-url-for-action action)
      
      (when-let [x (final-url-for-action action)]
        (url-cleanup/remove-tracking-params x))
      
      (when-let [x (:url action)]
        (url-cleanup/remove-tracking-params x))))

(defn create-event-from-match
  [evidence-record action match]
  (log/debug "create-event-from-match")
  (let [; subj.url is the URL supplied in the Action.
        subj-url (:url action)

        subj-id (best-subj-url-for-action action)

        ; Provide default subject metadata if not supplied.
        subj (merge {:pid subj-id :url subj-url}
                    (:subj action {}))

        ; For the obj, include the DOI URL as :pid, but also include the input URL as the :url
        ; Only the following sources provide a URL as the value (others have e.g. text DOI).
        obj-url (when (and (#{:landing-page-url :shortdoi-url :doi-url} (:type match))
                           (:value match))
                  (:value match))

        obj (merge {:pid (:match match)
                    :url (or obj-url
                             (:match match))} (:obj action {}))

        ; If the match included a matching method and verifiaction, include this.
        obj (if-let [method (:method match)]
                    (assoc obj :method method)
                    obj)

        obj (if-let [verification (:verification match)]
                    (assoc obj :verification verification)
                    obj)

        base-event {:id (str (UUID/randomUUID))
                    :source_token (:source-token evidence-record)
                    :subj_id subj-id
                    :obj_id (:match match)
                    :relation_type_id (:relation-type-id action)
                    :source_id (:source-id evidence-record)
                    :action (:action-type action "add")
                    :occurred_at (str (:occurred-at action))
                    :subj subj
                    :obj obj
                    :evidence_record (:url evidence-record)}

        with-license (if-let [license (:license evidence-record)] (assoc base-event :license license) base-event)]

      with-license))

(defn create-event-from-extra-event
  "Given an extra Event in an Action, expand it to include the full compliment of fields."
  [evidence-record event]
  (log/debug "create-event-from-extra-event")
  (let [base-event (merge {:evidence_record (:url evidence-record)
                           :id (str (UUID/randomUUID))
                           :source_token (:source-token evidence-record)
                           :source_id (:source-id evidence-record)
                           :action "add"}
                          event)
        with-license (if-let [license (:license evidence-record)]
                       (assoc base-event :license license) base-event)]
    with-license))

(defn create-events-for-action
  "Update action to include a seq of Events generated from observations in the Action. Plus extra-events if included, and if there were any matches."
  [context evidence-record action]
  (log/debug "create-events-for-action")
  (let [events-from-matches (map (partial create-event-from-match evidence-record action) (:matches action))
        events-from-extras (when (not-empty (:matches action)) (map (partial create-event-from-extra-event evidence-record) (:extra-events action)))
        events (concat events-from-matches events-from-extras)]
  (assoc action
    :events events)))

(defn store-action-duplicates
  "Save all action IDs from an evidence record into duplicate records. Called on 'push'."
  [evidence-record]
  (log/debug "store-action-duplicates")
  (let [actions (mapcat :actions (:pages evidence-record))]
    (doseq [action actions]
      ; Action ID might not be set.
      (when-let [action-id (:id action)]
        (store/set-string @action-dedupe-store (str "action/" action-id) (json/write-str {:evidence-record-id (:id evidence-record) :action-id action-id}))))))


