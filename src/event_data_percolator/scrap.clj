(ns event-data-percolator.scrap
  (:require [event-data-percolator.util.doi :as doi]
    [clojure.data.json :as json]
    [clojure.tools.logging :as log]
    [crossref.util.doi :as crdoi]
    [clj-time.core :as clj-time]
                [event-data-common.storage.s3 :as s3]
            [event-data-common.storage.store :as store]


    )
  (:import [java.io File])
  )

(defonce bad-dois (atom #{}))

(def path "/Users/jwass/data/reports")



(defn fetch-all-bad 
  []
  (let [dirs (filter #(.isDirectory %) (.listFiles (new File path)))
        reports (map #(new File % "doi-validity.json") dirs)]
    (doseq [report reports]
      (let [content (json/read-str (slurp report) :key-fn keyword)
            data (:machine-data content)]

        (doseq [doi (:not-matching-regex data)]
          (swap! bad-dois conj doi))

        (doseq [doi (:containing-question-mark data)]
          (swap! bad-dois conj doi))

        (doseq [doi (:non-existant data)]
          (swap! bad-dois conj doi))

        (doseq [doi (:containing-hash data)]
          (swap! bad-dois conj doi))))
    (prn (count @bad-dois))
    
    )
  )

; (def extant {})
; (def non-existent [])
; (def non-existent-set #{})
; (def extant (into {} (filter #(-> % second ) cleaned)))
; (def non-existent (map first (filter #(-> % second nil?) cleaned)))

(defn cleanup
  [bad-dois]
  (let [counter (atom 0)
        total (count bad-dois)]
    (into {} (pmap (fn [candidate] (log/info (swap! counter inc) "/" total) [candidate (doi/validate-doi-dropping (crdoi/non-url-doi candidate))]) bad-dois))))

(def notice "https://evidence.eventdata.crossref.org/announcements/2017-03-09T12-19-00Z-CED-7.json")

(def bus-storage (s3/build "AKIAJMBNIOATG2JIPZ5A" "Rx9/25Zm7ALtpYfQPQr2FowAxuZsDsJM5EyIuvjF" "eu-west-1" "event-data-event-bus-prod"))

(defn treat-event [event updated-count deleted-count deleted-output-dir ]
  (let [corrected-obj (get extant (:obj_id event))
        corrected-obj (when corrected-obj (crdoi/normalise-doi corrected-obj))
        is-deleted (non-existent-set (:obj_id event))]
    
    (when corrected-obj
      (log/info "Corrected" (:id event) (swap! updated-count inc))
      (let [corrected (assoc event :obj_id corrected-obj :updated "edited" :updated-reason notice :updated-date (str (clj-time/now)))
            ; f (new File updated-output-dir (:id event))
            json (json/write-str corrected)
            ev-path (str "e/" (:id event))]

        (log/info ev-path)
        (store/set-string bus-storage ev-path json)
        ; (with-open [w (clojure.java.io/writer f)] (json/write corrected w))
        ; (prn "OLD" (store/get-string bus-storage ev-path))
        ; (prn "NEW" json)


        ))

    (when is-deleted
      (log/info "Deleted" (:id event) (swap! deleted-count inc))
      (let [corrected (assoc event :updated "deleted" :updated-reason notice :updated-date (str (clj-time/now)))
            f (new File deleted-output-dir (:id event))]
        (with-open [w (clojure.java.io/writer f)] (json/write corrected w))))))

(defn treat-list
  [archive deleted-output-dir updated-count deleted-count]
      (log/info "archive" archive)
      (let [events (:events (json/read (clojure.java.io/reader archive) :key-fn keyword))]
        (doall (pmap #(treat-event % updated-count deleted-count deleted-output-dir ) events ))

        )
      )
  

(defn find-bad-events
  []
  (let [archives (filter #(and (.isFile %) (not (.startsWith (.getName %) "."))) (.listFiles (new File "/Users/jwass/data/bus-archives")))
        deleted-output-dir (new File "/Users/jwass/data/fixed/deleted")
        updated-count (atom 0)
        deleted-count (atom 0)]
    (doall (pmap #(treat-list % deleted-output-dir updated-count deleted-count) archives))
    (log/info "Updated" @updated-count)
    (log/info "Deleted" @deleted-count)


  ))



(defn review-deleted
  []
  (let [evfs (filter #(and (.isFile %) (not (.startsWith (.getName %) "."))) (.listFiles (new File "/Users/jwass/data/fixed/deleted")))
        doi-count (atom 0)
        all-count (atom 0)]
    (doseq [evf evfs]
      (let [parsed (with-open [r (clojure.java.io/reader evf)] (json/read r :key-fn keyword))
            id (:id parsed)
            ]

        (swap! all-count inc)

        (when (= "https://doi.org/" (:obj_id parsed))
          (swap! doi-count inc))

        (when-not (= "https://doi.org/" (:obj_id parsed))
          (prn (:obj_id parsed)))


      ))
    (prn "DOI" @doi-count "/" @all-count)

  ))