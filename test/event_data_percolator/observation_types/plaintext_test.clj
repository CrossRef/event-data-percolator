(ns event-data-percolator.observation-types.plaintext-test
  (:require [clojure.test :refer :all]
            [event-data-percolator.observation-types.plaintext :as plaintext]
            [event-data-percolator.test-util :as util]))

(def domain-set #{"example.com" "example.net"})

(deftest ^:unit process-plaintext-content-observation
  (testing "Plain DOIs can be extracted from text"
    (let [result (plaintext/process-plaintext-content-observation
                   util/mock-context
                   {:type "html" :input-content "the quick brown 10.5555/1111 jumps"})]

      (is (= result {:type "html"
                     :input-content "the quick brown 10.5555/1111 jumps"
                     :candidates [{:value "10.5555/1111" :type :plain-doi}]})
          "One plain DOI candidate returned.")))
 
  (testing "ShortDOI URL DOIs can be extracted from text"
    (let [result (plaintext/process-plaintext-content-observation
                   util/mock-context
                   {:type "html" :input-content "this is a shortdoi http://doi.org/abcd"})]

      (is (= result {:type "html"
                     :input-content "this is a shortdoi http://doi.org/abcd"
                     :candidates [{:value "http://doi.org/abcd" :type :shortdoi-url}]})
          "One ShortDOI URL candidate found when unlinked")))

  (testing "PIIs can be extracted from text"
    (let [result (plaintext/process-plaintext-content-observation
                   util/mock-context
                   {:type "html" :input-content "this is my PII S232251141300001-2 there"})]

      (is (= result {:type "html"
                     :input-content "this is my PII S232251141300001-2 there"
                     :candidates [{:value "S232251141300001-2" :type :pii}]})
          "PII candidate found in text")))

  (testing "Landing Page URLs can be extracted from text"
    (let [result (plaintext/process-plaintext-content-observation
                  util/mock-context
                  {:type "html" :input-content "one two three http://example.com/four five http://ignore.com/four"})]
    
      (is (= result {:type "html"
                     :input-content "one two three http://example.com/four five http://ignore.com/four"
                     :candidates [{:value "http://example.com/four" :type :landing-page-url}]})
          "Article landing page from known domain can be extracted from text. Non-matching domains ignored."))))
