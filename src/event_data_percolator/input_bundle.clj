(ns event-data-percolator.input-bundle
  "Process and Input Bundle."
  (:require [event-data-percolator.action :as action]
            [schema.core :as s]))

(def bundles-schema
  {:source-token s/Str
   :source-id s/Str
   :pages
   [{:actions
     [{:url s/Str
       :relation-type-id s/Str
       :id s/Str
       :subj s/Any
       :observations [{:type s/Str
                        (s/optional-key :sensitive?) s/Bool
                        (s/optional-key :input-content) s/Str
                        (s/optional-key :input-url) s/Str
                        }]}]}]})

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

(defn dedupe-actions
  "Dedupe actions in an input bundle.
  Step 3 in docs."
  [bundle]
  (map-actions action/dedupe-action bundle))

(defn candidates
  "Produce candidates in input bundle.
  Step 4 in docs."
  [bundle]
  (map-actions action/process-observations-candidates bundle))

(defn match
  "Match candidates in input bundle.
  Step 4 in docs."
  [bundle web-trace-atom]
  (map-actions #(action/match-candidates % web-trace-atom) bundle))

(defn events
  "Generate an Event for each candidate match."
  [bundle]
  (map-actions (partial action/create-events-for-action bundle) bundle))

(defn process
  [bundle]
  ; an atom that's passed around to functions that might want to log which URLs they access
  ; and their respose codes.
  (let [web-trace-atom (atom [])
        result (-> bundle
            dedupe-actions
            candidates
            (match web-trace-atom)
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
