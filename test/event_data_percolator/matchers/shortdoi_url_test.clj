(ns event-data-percolator.matchers.shortdoi-url-test
  (:require [clojure.test :refer :all]
            [org.httpkit.fake :as fake]
            [event-data-percolator.matchers.shortdoi-url :as shortdoi-url]
            [event-data-percolator.test-util :as util]))

(deftest ^:component match-shortdoi-url-candidate
  (testing "match-shortdoi-url-candidate matches valid shortDOI and converts to full DOI."
    (fake/with-fake-http ["https://doi.org/api/handles/10/hvx" (util/short-doi-ok "10.5555/12345678")]
      (let [result (shortdoi-url/match-shortdoi-url-candidate util/mock-context {:value "http://doi.org/hvx"})]
        (is (= result {:value "http://doi.org/hvx" :match "https://doi.org/10.5555/12345678"}))))))

; Regression for https://github.com/CrossRef/event-data-percolator/issues/40
(deftest ^:component match-shortdoi-url-candidate-empty
  (testing "match-shortdoi-url-candidate handles empty-doi"
    (fake/with-fake-http []
      (let [result (shortdoi-url/match-shortdoi-url-candidate util/mock-context {:value "http://doi.org/"})]
        (is (nil? (:match result)) "Should return nil without throwing exception."))))

  (testing "match-shortdoi-url-candidate handles empty value"
    (fake/with-fake-http []
      (let [result (shortdoi-url/match-shortdoi-url-candidate util/mock-context {:value ""})]
        (is (nil? (:match result)) "Should return nil without throwing exception."))))

  (testing "match-shortdoi-url-candidate handles URL with no path"
    (fake/with-fake-http []
      (let [result (shortdoi-url/match-shortdoi-url-candidate util/mock-context {:value "http://example.com"})]
        (is (nil? (:match result)) "Should return nil without throwing exception."))))

  (testing "match-shortdoi-url-candidate handles null value"
    (fake/with-fake-http []
      (let [result (shortdoi-url/match-shortdoi-url-candidate util/mock-context {:value nil})]
        (is (nil? (:match result)) "Should return nil without throwing exception.")))))
