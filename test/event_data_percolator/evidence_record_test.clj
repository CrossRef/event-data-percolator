(ns event-data-percolator.evidence-record-test
  "Tests for Evidence Record processing.
   These call a chain of extraction and matching functions. They are fully exercised in their own tests."
  (:require [clojure.test :refer :all]
            [clj-time.core :as clj-time]
            [org.httpkit.fake :as fake]
            [clojure.data.json :as json]
            [event-data-percolator.evidence-record :as evidence-record]
            [event-data-percolator.action :as action]
            [event-data-percolator.test-util :as util]))


(deftest ^:unit url
  (testing "url should add url based on the id."
      (let [bundle {:id "20170101-twitter-123456789"}
            result (evidence-record/url bundle)]
        (is (:id result) "Original bundle data preserved.")
        (is (= (:url result) "https://evidence.eventdata.crossref.org/evidence/20170101-twitter-123456789") "URL should be set"))))
        
(deftest ^:unit candidates
  (testing "candidates should generate candidates based on input"
    ; Just to guarantee there are no external calls at this stage.
    (fake/with-fake-http []
      (let [evidence-record {:id "1234"
                             :pages [
                              {:actions [
                                {:unrelated :junk
                                 :url "http://example.com"
                                 :occurred-at "2017-05-02T00:00:00.000Z"
                                 :observations [
                                   {:more :unrelated-stuff
                                     :type "plaintext"
                                     :input-content "10.5555/11111"}
                                   {:type "url"
                                    :input-url "http://doi.org/10.5555/22222"}]}]}]}
            
            ; Supply empty domain list as we're not testing landing page extraction.
            result (evidence-record/candidates evidence-record #{} (atom []))]
        (is (= result {:id "1234"
                       :pages [{:actions
                                [{:unrelated :junk
                                  :url "http://example.com"
                                  :occurred-at "2017-05-02T00:00:00.000Z"
                                  :processed-observations
                                  [{:more :unrelated-stuff
                                    :type "plaintext"
                                    :input-content "10.5555/11111"
                                    :candidates [{:value "10.5555/11111" :type :plain-doi}]
                                    :input-content-hash "e45fed4d47822fdc4791d6d9d38ec40650eb06dc"}
                                   {:type "url"
                                    :input-url "http://doi.org/10.5555/22222"
                                    :candidates [{:type :doi-url, :value "http://doi.org/10.5555/22222"}]}]}]}]})
            "Overall structure preserved. Candidates are attached to actions and input-content-hash where appropriate. Unrelated information at all levels carried through.")))))

(defn doi-ok
  "Fake OK return from DOI proxy."
  [handle]
  {:status 200 :body (json/write-str {"handle" handle})})

(deftest ^:component match
  (testing "match should transform candidates, but leave structure intact"
    ; DOI resolver consulted to check existence of DOIs.
    (fake/with-fake-http ["https://doi.org/api/handles/10.5555/11111" (util/doi-ok "10.5555/11111")
                          "https://doi.org/api/handles/10.5555/22222" (util/doi-ok "10.5555/22222")]
      
      (let [; Input bundle that came out of candidates.
            evidence-record {:id "1234"
                             :pages [{:actions
                                   [{:unrelated :junk
                                     :url "http://example.com"
                                     :occurred-at "2017-05-02T00:00:00.000Z"
                                     :processed-observations
                                     [{:more :unrelated-stuff
                                       :type "plaintext"
                                       :input-content "10.5555/11111"
                                       :candidates [{:value "10.5555/11111" :type :plain-doi}]
                                       :input-content-hash "e45fed4d47822fdc4791d6d9d38ec40650eb06dc"}
                                      {:type "url"
                                       :input-url "http://doi.org/10.5555/22222"
                                       :candidates [{:type :doi-url, :value "http://doi.org/10.5555/22222"}]}]}]}]}
            result (evidence-record/match evidence-record nil)]

        (is (= result {:id "1234"
                       :pages [{:actions
                                [{:unrelated :junk,
                                  :url "http://example.com"
                                  :occurred-at "2017-05-02T00:00:00.000Z"
                                  :processed-observations
                                    [{:more :unrelated-stuff
                                      :type "plaintext"
                                      :input-content "10.5555/11111"
                                      :candidates [{:value "10.5555/11111"
                                                    :type :plain-doi}]
                                      :input-content-hash "e45fed4d47822fdc4791d6d9d38ec40650eb06dc"}
                                     {:type "url"
                                      :input-url "http://doi.org/10.5555/22222"
                                      :candidates
                                      [{:type :doi-url
                                        :value "http://doi.org/10.5555/22222"}]}]
                                  :matches [{:value "10.5555/11111"
                                             :type :plain-doi
                                             :match "https://doi.org/10.5555/11111"}
                                            {:type :doi-url
                                             :value "http://doi.org/10.5555/22222"
                                             :match "https://doi.org/10.5555/22222"}]}]}]})
              "Overall structure preserved. Matches gathered for each action over candidates. Occurred-at carried through to match.")))))


(deftest ^:unit events
  (testing "events should transform matches into events"
    ; Ensure that no HTTP requests are made.
    (fake/with-fake-http []
      (let [; Input bundle that came out of candidates.
            evidence-record {:source-token "ABCDEFGH"
                             :source-id "THE_SOURCE_NAME"
                             :timestamp "2017-05-02T00:00:00.000Z"
                             :id "1234"
                             :pages [{:actions
                                       [{:unrelated :junk,
                                         :url "http://example.com"
                                         :subj {:title "My example Subject" :custom "value"}
                                         :relation-type-id "contemplates"
                                         :occurred-at "2017-05-02T00:00:00.000Z"
                                         :processed-observations
                                           [{:more :unrelated-stuff
                                             :type "plaintext"
                                             :input-content "10.5555/11111"
                                             :candidates [{:value "10.5555/11111"
                                                           :type :plain-doi}]
                                             :input-content-hash "e45fed4d47822fdc4791d6d9d38ec40650eb06dc"}
                                            {:type "url"
                                             :input-url "http://doi.org/10.5555/22222"
                                             :candidates
                                             [{:type :doi-url
                                               :value "http://doi.org/10.5555/22222"}]}]
                                         :matches [{:value "10.5555/11111"
                                                    :type :plain-doi
                                                    :match "https://doi.org/10.5555/11111"}
                                                   {:type :doi-url
                                                    :value "http://doi.org/10.5555/22222"
                                                    :match "https://doi.org/10.5555/22222"}]}]}]}
            result (evidence-record/events evidence-record)
            events (-> result :pages first :actions first :events)]

        (is (= (count events) 2) "Two events produced from two matches.")
        (is (= (->> events (map :obj_id) set) #{"https://doi.org/10.5555/11111" "https://doi.org/10.5555/22222"}) "Events each have correct subj_id from the match.")
        (is (= (->> events (map :subj_id) set) #{"http://example.com"}) "Events each have correct (same) obj_id, taken from the action.")
        (is (= (->> events (map :source_token) set) #{"ABCDEFGH"}) "Events each have correct (same) source_token, taken from the bundle.")
        (is (= (->> events (map :source_id) set) #{"THE_SOURCE_NAME"}) "Events each have correct (same) source_id, taken from the bundle.")
        (is (= (->> events (map :occurred_at) set) #{"2017-05-02T00:00:00.000Z"}) "Events each have correct (same) occurred_at, taken from the Action.")
        (is (= (->> events (map :action) set) #{"add"}) "Events each have default 'add' action, taken from the match.")
        (is (= (->> events (map :subj) set) #{{:pid "http://example.com" :title "My example Subject" :custom "value"}}) "Custom subject metadata via subj is merged with URL.")
        (is (= (->> events (map :relation_type_id) set) #{"contemplates"}) "relation_type_id should be taken from the actions")
        (is (= (->> events (map :id) set count) 2) "Each event has a different ID.")
        (is (= (->> events (map :timestamp) set) #{nil}) "Events do not have timestamps (though the Evidence Record does). They are assigned downstream when accepted by the Event Bus.")))))

(deftest ^:unit extract-all-events
  (testing "extract-all-events should take all events from all actions and merge them together."
    ; Processed, finished bundle, now it's an output bundle!
    ; Except without the irrelevant bits.
    (let [output-bundle
          {:id "1234"
           :pages
            ; Page 1
            [{:actions
             [{; Action 1.1
               :events
               [{:id "11111" :and :other-fields}
                {:id "22222" :and :other-fields}]}
               ; Action 1.2
               {:events
               [{:id "33333" :and :other-fields}
                {:id "44444" :and :other-fields}]}]}
             ; Page 2
             {:actions
              ; Action 2.1
             [{:events
               [{:id "55555" :and :other-fields}
                {:id "66666" :and :other-fields}]}]}]}

        result (evidence-record/extract-all-events output-bundle)]

      (is (= (set result) #{{:id "11111" :and :other-fields}
                            {:id "22222" :and :other-fields}
                            {:id "33333" :and :other-fields}
                            {:id "44444" :and :other-fields}
                            {:id "55555" :and :other-fields}
                            {:id "66666" :and :other-fields}})
          "Events collected from all pages and all actions."))))

(deftest ^:unit map-actions
  (testing "map-actions should apply a function to all actions within an input bundle, preserving the rest of the structure"
    ; Doesn't matter what the actions are. This test just applying str function to a load of keywords as it's the simplet input/output.
    (let [input {:id "1234"
                 :pages [
                  {:other-stuff :in-page
                   :actions [:some-dummy-action-object-1]}
                  
                  {:ignore-this :stuff
                   :actions [:some-dummy-action-object-2 :some-dummy-action-object-3]}]}

          result (evidence-record/map-actions str input)]
      (is (= result
             {:id "1234"
              :pages [
               {:other-stuff :in-page
                :actions [":some-dummy-action-object-1"]}

               {:ignore-this :stuff
                :actions [":some-dummy-action-object-2"
                          ":some-dummy-action-object-3"]}]})))))

(deftest ^:component end-to-end-process
  (testing "End-to-end processing of Input Bundle should result in an Evidence Record with Events and HTTP tracing."
    ; A single redirect so that we can demonstrate that the trace is captured.
    (fake/with-fake-http ["http://article.com/article/22222" {:status 303 :headers {:location "http://article.com/article/22222-X"}}
                          "http://article.com/article/22222-X" {:status 200 :body "<html><head><meta name='dc.identifier' content='https://doi.org/10.5555/12345678'></head></html>"}

                          "https://doi.org/api/handles/10.5555/12345678" (util/doi-ok "10.5555/12345678")

                          ; This one throws a timeout error, which should be reported
                          "http://article.com/article/XXXXX" (fn [a b c] (throw (new org.httpkit.client.TimeoutException "I got bored")))]
      (let [domain-list #{"article.com"}
            evidence-record {:id "1234"
                             :artifacts {:other :value} ; pass-through any artifact info from input package.
                             :pages [
                              {:actions [
                                {:url "http://example.com/page/11111"
                                 :occurred-at "2017-05-02T00:00:00.000Z"
                                 :observations [
                                   {:type "url"
                                    :input-url "http://article.com/article/22222"}
                                   {:type "url"
                                    :input-url "http://article.com/article/XXXXX"}]}]}]}
            
            result (evidence-record/process evidence-record "http://d1v52iseus4yyg.cloudfront.net/a/crossref-domain-list/versions/1482489046417" domain-list)]
        
        (is (= (-> result :percolator :artifacts :domain-set-artifact-version)
                "http://d1v52iseus4yyg.cloudfront.net/a/crossref-domain-list/versions/1482489046417")
            "Domain list artifact version should be correctly set.")

        (is (= (-> result :artifacts :other) :value) "Pre-existing values in artifacts are passed through.")

        (is (= (set (:web-trace result))
                #{{:type :request :url "http://article.com/article/22222" :status 303 }
                  {:type :request :url "http://article.com/article/22222-X" :status 200 }
                  {:type :request :url "http://article.com/article/XXXXX" :error :timeout-error}})
            "All HTTP access should be recorded")

        ; The rest of the pieces are tested above.
        (is (= 1 (-> result :pages first :actions first :events count)) "One event should be found")
        (is (-> result :id))))))


        
(deftest ^:component deduplication-across-bundles
  ; This is the most likely case.
  (testing "Duplicates can be detected between a evidence-records"
    (fake/with-fake-http ["https://doi.org/api/handles/10.5555/12345678" (doi-ok "10.5555/12345678")]
      (let [domain-list #{}
            ; We submit the same input bundle twice. 
            evidence-record {:id "1234"
                             :pages [
                              {:actions [
                                {:url "http://example.com/page/11111"
                                 :id "88888"
                                 :occurred-at "2017-05-02T00:00:00.000Z"
                                 :observations [
                                   {:type "plaintext"
                                    :input-content "10.5555/12345678"}]}]}]}
            
            result-1 (evidence-record/process evidence-record "http://d1v52iseus4yyg.cloudfront.net/a/crossref-domain-list/versions/1482489046417" domain-list)
            
            ; Now save the action IDs. This is normally triggered in 'push'.
            push-output-bundle-result (action/store-action-duplicates result-1)

            result-2 (evidence-record/process evidence-record "http://d1v52iseus4yyg.cloudfront.net/a/crossref-domain-list/versions/1482489046417" domain-list)

            evidence-record-id-1 (:id result-1)
            evidence-record-id-2 (:id result-2)

            ; A duplicate record, built from the action ID and the input bundle ID. We'll expect to see this.
            ; This has string keys because it's fetched back from serialized storage.
            expected-duplicate {"evidence-record-id" evidence-record-id-1 "action-id" "88888"}]

      (is (-> result-1 :pages first :actions first :duplicate nil?) "Action in first bundle not marked as duplicate")
      (is (= (-> result-2 :pages first :actions first :duplicate) expected-duplicate) "Action in second bundle not marked as duplicate, with ID of first bundle.")

      
      (is (-> result-1 :pages first :actions first :processed-observations first :candidates not-empty) "First bundle action isn't a duplicate, so should have candidates")
      (is (-> result-2 :pages first :actions first :processed-observations first :candidates empty?) "Second bundle action is a duplicate, so should not have candidates")

      (is (-> result-1 :pages first :actions first :matches not-empty) "First bundle action isn't a duplicate, so should have matches")
      (is (-> result-2 :pages first :actions second :matches empty?) "Second bundle action is a duplicate, so should not have matches")

      (is (-> result-1 :pages first :actions first :events not-empty) "First bundle action isn't a duplicate, so should have events")
      (is (-> result-2 :pages first :actions second :events empty?) "Second bundle action is a duplicate, so should not have events")))))



(deftest ^:component action-id-can-be-ommitted
  (testing "Action IDs can be ommitted if it's sensible to do so, e.g. low chance of collision, very high rate of input per Wikipedia"
    (fake/with-fake-http ["https://doi.org/api/handles/10.5555/9898989898" (doi-ok "10.5555/9898989898")]
      (let [domain-list #{}
            evidence-record {:id "1234"
                             :pages [
                              {:actions [
                                ; Same actions in the input bundle. In reality this shouldn't happen, but do it to verify that they aren't deduplicated.
                                {:url "https://en.wikipedia.org/w/index.php?title=Bus&oldid=776981387"
                                 :occurred-at "2017-05-02T00:00:00.000Z"
                                 :observations [
                                   {:type "plaintext"
                                    :input-content "10.5555/9898989898"}]}
                                {:url "https://en.wikipedia.org/w/index.php?title=Bus&oldid=776981387"
                                 :occurred-at "2017-05-02T00:00:00.000Z"
                                 :observations [
                                   {:type "plaintext"
                                    :input-content "10.5555/9898989898"}]}]}]}

            ; Also send twice.
            result-1 (evidence-record/process evidence-record "http://d1v52iseus4yyg.cloudfront.net/a/crossref-domain-list/versions/1482489046417" domain-list)
            
            ; Now save the action IDs. This is normally triggered in 'push'.
            push-output-bundle-result (action/store-action-duplicates result-1)

            result-2 (evidence-record/process evidence-record "http://d1v52iseus4yyg.cloudfront.net/a/crossref-domain-list/versions/1482489046417" domain-list)]

      (is (= (dissoc result-1
               :id
               :timestamp
               :url
               :pages)
             (dissoc result-2
               :id
               :timestamp
               :url
               :pages)) "Both results should have been processed to give identical results (except for those fields that are non-deterministic).")

      (is (= (->> result-1 :pages first :actions first :events (map #(dissoc % :id :evidence_record)))
             (->> result-2 :pages first :actions first :events (map #(dissoc % :id :evidence_record)))) "Events should be the same except for non-deterministic fields.")))))

