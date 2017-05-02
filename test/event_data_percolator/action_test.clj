(ns event-data-percolator.action-test
  "Tests for action"
  (:require [clojure.test :refer :all]
            [clj-time.core :as clj-time]
            [org.httpkit.fake :as fake]
            [event-data-percolator.action :as action]
            [event-data-percolator.test-util :as util]))

(deftest ^:unit create-event-from-match
  (testing "create-event-from-match can build an Event from an Action"
    (let [source-token "SOURCE_TOKEN"
          subject-url "https://blog.com/1234"
          source-id "SOURCE_ID"

          object-url "http://psychoceramics.labs.crossref.org/12345"
          object-doi "https://dx.doi.org/10.5555/12345678"
          match {:type :landing-page-url :value object-url :match object-doi}
          input-action {:url subject-url
                        :occurred-at "2016-02-05"
                        :relation-type-id "cites"
                        :processed-observations [{:match match}]}
          
          input-bundle {:source-token source-token
                        :source-id source-id
                        :pages [{:actions [input-action]}]}

          result (action/create-event-from-match input-bundle input-action match)]

      (is (= (:obj_id result) object-doi) "Match URL should be output as obj_id")
      (is (= (:source_token result) source-token) "Source token should be taken from bundle")
      (is (= (:occurred_at result) "2016-02-05") "Occurred at should be taken from the Action")
      (is (= (:subj_id result) subject-url) "Subject URL should be taken from the Action")
      (is (:id result) "ID is assigned")
      (is (:action result) "Action is assigned")
      (is (= (-> result :subj :pid) subject-url)  "Subject URL should be taken from the Action")
      (is (= (-> result :obj :pid) object-doi) "The match DOI should be included as the obj metadata PID ")
      (is (= (-> result :obj :url) object-url) "The match input URL should be included as the obj metadata PID")
      (is (= (:relation_type_id result) "cites") "Relation type id is taken from the Action")))

  (testing "create-event-from-match adds license field if present"
    (let [source-token "SOURCE_TOKEN"
          subject-url "https://blog.com/1234"
          source-id "SOURCE_ID"

          object-url "http://psychoceramics.labs.crossref.org/12345"
          object-doi "https://dx.doi.org/10.5555/12345678"
          match {:type :landing-page-url :value object-url :match object-doi}
          input-action {:url subject-url
                        :occurred-at "2016-02-05"
                        :relation-type-id "cites"
                        :processed-observations [{:match match}]}
          
          input-bundle {:source-token source-token
                        :source-id source-id
                        :pages [{:actions [input-action]}]
                        :license "https://creativecommons.org/publicdomain/zero/1.0/"}

          result (action/create-event-from-match input-bundle input-action match)]
      (is (= (:license result) "https://creativecommons.org/publicdomain/zero/1.0/") "License field present")))

  (testing "create-event-from-match does not add license field if not present"
    (let [source-token "SOURCE_TOKEN"
          subject-url "https://blog.com/1234"
          source-id "SOURCE_ID"

          object-url "http://psychoceramics.labs.crossref.org/12345"
          object-doi "https://dx.doi.org/10.5555/12345678"
          match {:type :landing-page-url :value object-url :match object-doi}
          input-action {:url subject-url
                        :occurred-at "2016-02-05"
                        :relation-type-id "cites"
                        :processed-observations [{:match match}]}
          
          input-bundle {:source-token source-token
                        :source-id source-id
                        :pages [{:actions [input-action]}]}

          result (action/create-event-from-match input-bundle input-action match)]
      (is (not (contains? result :license)) "License field not present (not just nil)"))))

; This behaviour allows consumers to tell the difference between DOIs that were matched from an input URL (e.g. landing page)
; and those that were referenced directly with the DOI.
(deftest ^:unit create-event-from-match-subj-urls
  (let [; A match where there was an input-url
        match1 {:value "http://psychoceramics.labs.crossref.org/12345"
                :type :landing-page-url
                :match "https://dx.doi.org/10.5555/12345678"}

        ; A match where there wasn't.
        match2 {:match "https://dx.doi.org/10.5555/12345678"}

        input-action {:url "https://blog.com/1234"
                      :occurred-at "2016-02-05"
                      :relation-type-id "cites"}
        
        input-bundle {:source-token "SOURCE_TOKEN"
                      :source-id "SOURCE_ID"
                      :pages [{:actions [input-action]}]}

        result1 (action/create-event-from-match input-bundle input-action match1)
        result2 (action/create-event-from-match input-bundle input-action match2)]

    (testing "create-event-from-match will take the input URL as the event URL from the match where there is one"
      (is (= (-> result1 :obj :pid) "https://dx.doi.org/10.5555/12345678"))
      (is (= (-> result1 :obj :url) "http://psychoceramics.labs.crossref.org/12345")))

    (testing "create-event-from-match will take the DOI URL as the event URL from the match where there is no input url"
      (is (= (-> result2 :obj :pid) "https://dx.doi.org/10.5555/12345678"))
      (is (= (-> result2 :obj :url) "https://dx.doi.org/10.5555/12345678")))))


(deftest create-events-for-action
  (testing "When there are are extra Events but there was no match, no Events should be emitted."
    (let [extra-events [{:obj_id "https://example.com/1",
                         :occurred_at "2017-04-01T00:33:21Z",
                         :subj_id "https://example.com/1/version/2",
                         :relation_type_id "is_version_of"}
                        {:obj_id "https://example.com/2",
                         :occurred_at "2017-04-01T00:33:21Z",
                         :subj_id "https://example.com/2/version/2",
                         :relation_type_id "is_version_of"}]

          input-action {:url "https://blog.com/1234"
                        :occurred-at "2016-02-05"
                        :relation-type-id "cites"
                        ; no matches
                        :matches []
                        :extra-events extra-events}
         
          input-bundle {:source-token "SOURCE_TOKEN"
                        :source-id "SOURCE_ID"
                        :license "http://example.com/license"
                        :url "http://example.com/evidence/123456"
                        :pages [{:actions [input-action]}]}

         result-action (action/create-events-for-action input-bundle input-action)]
    (is (empty? (:events result-action)) "No Events should have been emitted.")))

  (testing "When there are are extra Events and there was at least one match, those Extra Events should be emitted, with the requisite fields."
    (let [extra-events [; These Events have minimal fields.
                        {:obj_id "https://example.com/1",
                         :occurred_at "2017-04-01T00:33:21Z",
                         :subj_id "https://example.com/1/version/2",
                         :relation_type_id "is_version_of"}
                        {:obj_id "https://example.com/2",
                         :occurred_at "2017-04-01T00:33:21Z",
                         :subj_id "https://example.com/2/version/2",
                         :relation_type_id "is_version_of"}]

          input-action {:url "https://blog.com/1234"
                        :occurred-at "2016-02-05"
                        :relation-type-id "cites"
                        ; one match
                        :matches [{:value "http://psychoceramics.labs.crossref.org/12345"
                                  :type :landing-page-url
                                  :match "https://dx.doi.org/10.5555/12345678"}]
                        :extra-events extra-events}
         
          input-bundle {:source-token "SOURCE_TOKEN"
                        :source-id "SOURCE_ID"
                        :license "http://example.com/license"
                        :url "http://example.com/evidence/123456"
                        :pages [{:actions [input-action]}]}

         result-action (action/create-events-for-action input-bundle input-action)]
    (is (= (count (:events result-action)) 3) "Three Events should have been emitted, one from the match and two from the extras.")
    
    ; compare with out :id field, that's random.
    (is (= (set (map #(dissoc % :id) (:events result-action)))
          #{{:license "http://example.com/license"
             :obj_id "https://dx.doi.org/10.5555/12345678",
             :source_token "SOURCE_TOKEN",
             :occurred_at "2016-02-05",
             :subj_id "https://blog.com/1234",
             :action "add",
             :subj {:pid "https://blog.com/1234"},
             :source_id "SOURCE_ID",
             :obj
             {:pid "https://dx.doi.org/10.5555/12345678",
              :url "http://psychoceramics.labs.crossref.org/12345"},
             :evidence_record "http://example.com/evidence/123456",
             :relation_type_id "cites"}

            ; Emitted ones have :id, :evidence_record, :action, :source_id added 
            {:license "http://example.com/license"
             :evidence_record "http://example.com/evidence/123456",
             :source_token "SOURCE_TOKEN",
             :source_id "SOURCE_ID",
             :action "add",
             :obj_id "https://example.com/1",
             :occurred_at "2017-04-01T00:33:21Z",
             :subj_id "https://example.com/1/version/2",
             :relation_type_id "is_version_of"}
            {:license "http://example.com/license"
             :evidence_record "http://example.com/evidence/123456",
             :source_token "SOURCE_TOKEN",
             :source_id "SOURCE_ID",
             :action "add",
             :obj_id "https://example.com/2",
             :occurred_at "2017-04-01T00:33:21Z",
             :subj_id "https://example.com/2/version/2",
             :relation_type_id "is_version_of"}}))))

  (testing "When there are are no extra Events but there were matches, an Event should be created for each match"
    (let [input-action {:url "https://blog.com/1234"
                        :occurred-at "2016-02-05"
                        :relation-type-id "cites"
                        ; one match
                        :matches [{:value "http://psychoceramics.labs.crossref.org/12345"
                                  :type :landing-page-url
                                  :match "https://dx.doi.org/10.5555/12345678"}]
                        ; no extra events
                        :extra-events []}
         
          input-bundle {:source-token "SOURCE_TOKEN"
                       :source-id "SOURCE_ID"
                       :license "http://example.com/license"
                       :url "http://example.com/evidence/123456"
                       :pages [{:actions [input-action]}]}

         result-action (action/create-events-for-action input-bundle input-action)]
    (is (= (count (:events result-action)) 1) "One Events should have been emitted, from the match.")
    (is (= (map #(dissoc % :id) (:events result-action)))
      [{:license "http://example.com/license"
        :obj_id "https://dx.doi.org/10.5555/12345678",
        :source_token "SOURCE_TOKEN",
        :occurred_at "2016-02-05",
        :subj_id "https://blog.com/1234",
        :action "add",
        :subj {:pid "https://blog.com/1234"},
        :source_id "SOURCE_ID",
        :obj
        {:pid "https://dx.doi.org/10.5555/12345678",
         :url "http://psychoceramics.labs.crossref.org/12345"},
        :evidence_record "http://example.com/evidence/123456",
        :relation_type_id "cites"}]))))

(deftest dedupe-by-val-substring
  (testing "dedupe-by-val-substring removes all matches that are a substring of any other in the group."
    (is (= (action/dedupe-by-val-substring [{:value "1"} {:value "1234"} {:value "12"} {:value "oops"}])
          [{:value "1234"} {:value "oops"}])
      "Substrings should be removed. Non-dupes should be untouched")))

(deftest dedupe-matches
  (testing "When there are duplicate matches that represent the same thing match-candidates should de-duplicate them.
            See event-data-percolator.observation-types.html-test/html-with-duplicates for when this might occur."
    (let [input-action {:matches [; This should be removed because the matched DOI is a duplicate of another one and the value is a substring of another.
                                  {:type :plain-doi :value "10.5555/12345678" :match "https://doi.org/10.5555/12345678"}
                                  ; This should be removed because the matche DOI is a duplicate of another one, as is the value, and :doi-urls are prioritised.
                                  {:type :landing-page-url :value "https://doi.org/10.5555/12345678" :match "https://doi.org/10.5555/12345678"}
                                  ; This should be retained because its' the only one left of the duplicates and it's prioritised.
                                  {:type :doi-url :value "https://doi.org/10.5555/12345678" :match "https://doi.org/10.5555/12345678"}
                                  ; This has the same matched DOI, but is different in that it came from a landing page. Should be retained.
                                  {:type :landing-page-url :value "https://www.example.com/article/123456789" :match "https://doi.org/10.5555/12345678"}
                                  ; And something that's not a duplicate, so should be retained.
                                  {:type :doi-url :value "https://doi.org/10.6666/24242424" :match "https://doi.org/10.6666/24242424"}]}

          expected-result {:matches [{:type :doi-url, :value "https://doi.org/10.5555/12345678" :match "https://doi.org/10.5555/12345678"}
                                     {:type :landing-page-url :value "https://www.example.com/article/123456789" :match "https://doi.org/10.5555/12345678"}
                                     {:type :doi-url, :value "https://doi.org/10.6666/24242424" :match "https://doi.org/10.6666/24242424"}]}

         result (action/dedupe-matches input-action)]
      (is (= result expected-result)))))

