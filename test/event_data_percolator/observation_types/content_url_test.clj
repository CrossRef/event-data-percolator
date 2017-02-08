(ns event-data-percolator.observation-types.content-url-test
  "Tests for content-url. This defers to other types for the content, there are individual tests for those."
  (:require [clojure.test :refer :all]
            [event-data-percolator.observation-types.content-url :as content-url]
            [org.httpkit.fake :as fake]))

(deftest ^:unit url-valid
  (testing "url-valid? should return true for good URLs and false for invalid urls"
    (is (true? (content-url/url-valid? "https://example.com/")))
    (is (true? (content-url/url-valid? "http://example.com")))
    (is (false? (content-url/url-valid? "")))
    (is (false? (content-url/url-valid? nil)))
    (is (false? (content-url/url-valid? "example.com/"))))

  (testing "url-valid? should return false for well-formed URLs on blacklist"
    (is (false? (content-url/url-valid? "http://example.com/somefile.pdf")))))

(deftest ^:unit process-content-url-observation
  (testing "process-content-url-observation should set error when URL isn't allowed"
    (let [result (content-url/process-content-url-observation {:input-url nil} #{"example.com"})]
      (is (:error result))))

  (testing "process-content-url-observation should set error when URL can't be retrieved"
    (fake/with-fake-http ["http://cannot-be-retrieved.com/abc" {:status 404}]
      (let [result (content-url/process-content-url-observation {:input-url "http://cannot-be-retrieved.com/abc"} #{"cannot-be-retrieved.com"})]
        (is (:error result)))))

  (testing "process-content-url-observation should set candidates on match where there are matches"
    (fake/with-fake-http ["http://can-be-retrieved.com/abc" "Webpage content 10.5555/12345678"]
      (let [result (content-url/process-content-url-observation {:input-url "http://can-be-retrieved.com/abc"} #{"can-be-retrieved.com"})]
        (is (nil? (:error result)))

        ; Simplest possible thing that returns candidates (actually passed all the way through to plain-text).
        (is (= result {:input-url "http://can-be-retrieved.com/abc"
                       :input-content "Webpage content 10.5555/12345678"
                       :candidates [{:value "10.5555/12345678", :type :plain-doi}]}))))))

