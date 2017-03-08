(ns event-data-percolator.observation-types.url-test
  (:require [clojure.test :refer :all]
            [event-data-percolator.observation-types.url :as url]))

(def domain-set #{"example.com" "example.net"})

(deftest ^:unit process-url-observation
  (testing "URL DOIs on doi.org can be matched"
    (let [result (url/process-url-observation {:type "url" :input-url "https://doi.org/10.5555/1111"} domain-set (atom []))]
      (is (= result {:type "url"
                     :input-url "https://doi.org/10.5555/1111"
                     :candidates [{:value "https://doi.org/10.5555/1111" :type :doi-url}]}))))
 
  (testing "URL DOIs on dx.doi.org can be matched"
    (let [result (url/process-url-observation {:type "url" :input-url "http://dx.doi.org/10.5555/1111"} domain-set (atom []))]
      (is (= result {:type "url"
                     :input-url "http://dx.doi.org/10.5555/1111"
                     :candidates [{:value "http://dx.doi.org/10.5555/1111" :type :doi-url}]}))))

  (testing "ShortDOI URL DOIs can be extracted from text"
    (let [result (url/process-url-observation {:type "url" :input-url "http://doi.org/abcd"} domain-set (atom []))]
      (is (= result {:type "url"
                     :input-url "http://doi.org/abcd"
                     :candidates [{:value "http://doi.org/abcd" :type :shortdoi-url}]}))))

  (testing "Landing Page URLs can be extracted from text"
    (let [result (url/process-url-observation {:type "url" :input-url "http://example.com/four"} domain-set (atom []))]
      (is (= result {:type "url"
                     :input-url "http://example.com/four"
                     :candidates [{:value "http://example.com/four" :type :landing-page-url}]}))))

  (testing "Landing Page URLs not on recognised domain list are not extracted."
    (let [result (url/process-url-observation {:type "url" :input-url "http://bad-example.com/four"} domain-set (atom []))]
      (is (= result {:type "url"
                     :input-url "http://bad-example.com/four"
                     :candidates []}))))

  (testing "Nil input handled ok."
    (let [result (url/process-url-observation {:type "url" :input-url nil} domain-set (atom []))]
      (is (= result {:type "url"
                     :input-url nil
                     :candidates []})))))

