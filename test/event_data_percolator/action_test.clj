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




(deftest ^:unit update-extra-events
  (testing "update-extra-events should incorporate all the required values from the Input Bundle to make a valid event"
    (let [bundle {:url "http://example.com/evidence-record-1"
                  :license "http://example.com/license"
                  :source-token "THE_SOURCE_TOKEN"
                  :source-id "THE_SOURCE_ID"
                  :extra-events [
                    {:obj_id "https://example.com/1",
                     :occurred_at "2017-04-01T00:33:21Z",
                     :subj_id "https://example.com/1/version/2",
                     :relation_type_id "is_version_of"}
                    {:obj_id "https://example.com/2",
                     :occurred_at "2017-04-01T00:33:21Z",
                     :subj_id "https://example.com/2/version/2",
                     :relation_type_id "is_version_of"}]}
          result (action/update-extra-events bundle)

          result-extra-events (:extra-events result)]

        (is (= (count result-extra-events) 2) "Both Events carried through")
        (is (-> result-extra-events first :id) "ID added")
        (is (-> result-extra-events second :id) "ID added")
        
        (is (= (-> result-extra-events first :subj_id) "https://example.com/1/version/2"))
        (is (= (-> result-extra-events first :obj_id) "https://example.com/1"))
        
        (is (= (-> result-extra-events second :subj_id) "https://example.com/2/version/2"))
        (is (= (-> result-extra-events second :obj_id) "https://example.com/2"))

        (is (= (-> result-extra-events first :evidence-record) "http://example.com/evidence-record-1"))
        (is (= (-> result-extra-events second :evidence-record) "http://example.com/evidence-record-1"))

        (is (= (-> result-extra-events first :license) "http://example.com/license"))
        (is (= (-> result-extra-events second :license) "http://example.com/license")))))

