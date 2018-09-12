(ns event-data-percolator.observation-types.url-test
  (:require [clojure.test :refer :all]
            [event-data-percolator.observation-types.url :as url]
            [event-data-common.landing-page-domain :as landing-page-domain]
            [clojure.java.io :refer [reader resource]]
            [clojure.data.json :as json]
            [event-data-percolator.test-util :as util]))

(deftest ^:unit process-url-observation-1
  (testing "URL DOIs on doi.org can be matched"
    (let [result (url/process-url-observation
                   util/mock-context
                   {:type "url" :input-url "https://doi.org/10.5555/1111"})]
    
      (is (= result {:type "url"
                     :input-url "https://doi.org/10.5555/1111"
                     :candidates [{:value "https://doi.org/10.5555/1111" :type :doi-url}]})))))

(deftest ^:unit process-url-observation-2
  (testing "URL DOIs on dx.doi.org can be matched"
    (let [result (url/process-url-observation
                   util/mock-context
                   {:type "url" :input-url "http://dx.doi.org/10.5555/1111"})]

      (is (= result {:type "url"
                     :input-url "http://dx.doi.org/10.5555/1111"
                     :candidates [{:value "http://dx.doi.org/10.5555/1111" :type :doi-url}]})))))

(deftest ^:unit process-url-observation-3
  (testing "ShortDOI URL DOIs can be extracted from text"
    (let [result (url/process-url-observation
                   util/mock-context
                   {:type "url" :input-url "http://doi.org/abcd"})]

      (is (= result {:type "url"
                     :input-url "http://doi.org/abcd"
                     :candidates [{:value "http://doi.org/abcd" :type :shortdoi-url}]})))))

(deftest ^:unit process-url-observation-4
  (testing "Landing Page URLs can be extracted from text"
    (let [result (url/process-url-observation
                   util/mock-context
                   {:type "url" :input-url "http://psychoceramics.labs.crossref.org/four"})]

      (is (= result {:type "url"
                     :input-url "http://psychoceramics.labs.crossref.org/four"
                     :candidates [{:value "http://psychoceramics.labs.crossref.org/four" :type :landing-page-url}]})))))

(deftest ^:unit process-url-observation-5
  (testing "Landing Page URLs not on recognised domain list are not extracted."
    (let [result (url/process-url-observation
                   util/mock-context
                   {:type "url" :input-url "http://bad-psychoceramics.labs.crossref.org/four"})]

      (is (= result {:type "url"
                     :input-url "http://bad-psychoceramics.labs.crossref.org/four"
                     :candidates []})))))

(deftest ^:unit process-url-observation-6
  (testing "Nil input handled ok."
    (let [result (url/process-url-observation
      util/mock-context
      {:type "url" :input-url nil})]

      (is (= result {:type "url"
                     :input-url nil
                     :candidates []})))))
