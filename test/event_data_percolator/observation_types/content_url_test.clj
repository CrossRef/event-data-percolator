(ns event-data-percolator.observation-types.content-url-test
  "Tests for content-url. This defers to other types for the content, there are individual tests for those."
  (:require [clojure.test :refer :all]
            [event-data-percolator.observation-types.content-url :as content-url]
            [org.httpkit.fake :as fake]
            [event-data-percolator.test-util :as util]))

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
  (testing "process-content-url-observation should set error when URL isn't allowed becuase it's empty and therefore doesn't match the domain."
    (let [result (content-url/process-content-url-observation
          util/mock-context
          {:input-url nil})]
    
      (is (:error result))))

  (testing "process-content-url-observation should set error when URL can't be retrieved"
    (fake/with-fake-http ["http://cannot-be-retrieved.com/abc" {:status 404}]
      (let [result (content-url/process-content-url-observation
                      util/mock-context
                      {:input-url "http://cannot-be-retrieved.com/abc"})]
        (is (:error result)))))

  (testing "process-content-url-observation not visit landing page domains"
    ; Assert that no web call is made.
    (fake/with-fake-http []
      (let [result (content-url/process-content-url-observation
                     (assoc util/mock-context :domain-set #{"example.com"})
                     {:input-url "http://example.com/this/page"})]
        
        (is (= (:error result) :skipped-domain) "Results in :skipped-domain error")
        (is (nil? (:input-content result)) "No content is returned."))))


  (testing "process-content-url-observation should set candidates on match where there are matches"
    (fake/with-fake-http ["http://can-be-retrieved.com/abc" "Webpage content 10.5555/12345678"]
      (let [result (content-url/process-content-url-observation
                    util/mock-context
       {:input-url "http://can-be-retrieved.com/abc"})]
        (is (nil? (:error result)))

        ; Simplest possible thing that returns candidates (actually passed all the way through to plain-text).
        (is (= result {:input-url "http://can-be-retrieved.com/abc"
                       :input-content "Webpage content 10.5555/12345678"
                       :candidates [{:value "10.5555/12345678", :type :plain-doi}]}))))))

(deftest ^:unit robots-exclusion
  (testing "Fetch usually respects robots.txt Disallow"
    (fake/with-fake-http ["http://disallow-robots.com/abc" "Disalowed content 10.5555/12345678"
                          "http://disallow-robots.com/robots.txt" "User-agent: *\nDisallow: /"]
      (let [result (content-url/process-content-url-observation
                     util/mock-context
                     {:input-url "http://disallow-robots.com/abc"})]

        ; No candidates should be matched because of robots exclusion.
        (is (= result {:input-url "http://disallow-robots.com/abc"
                       :error :failed-fetch-url})
          "No matches should be made when robots prohibits.")))

  (testing "Fetch usually respects robots.txt Allow"
    (fake/with-fake-http ["http://allow-robots.com/abc" "Allowed content 10.5555/12345678"
                          "http://allow-robots.com/robots.txt" "User-agent: *\nAllow: /"]

      (let [result (content-url/process-content-url-observation
                     util/mock-context
                     {:input-url "http://allow-robots.com/abc"})]

        (is (= result {:input-url "http://allow-robots.com/abc"
                       :input-content "Allowed content 10.5555/12345678"
                       :candidates [{:value "10.5555/12345678", :type :plain-doi}]})
            "Matches should be made when robots allowed request.")))))

  (testing "If :ignore-robots is true then ignore the robots Disallow exclusions"
    (fake/with-fake-http ["http://disallow-robots.com/abc" "Disallow robots content 10.5555/12345678"
                          "http://disallow-robots.com/robots.txt" "User-agent: *\nDisallow: /"]
      (let [result (content-url/process-content-url-observation
                     util/mock-context
                     {:input-url "http://disallow-robots.com/abc"
                      :ignore-robots true})]

        ; No candidates should be matched because of robots exclusion.
        (is (= result {:input-url "http://disallow-robots.com/abc"
                       :input-content "Disallow robots content 10.5555/12345678"
                       :ignore-robots true
                       :candidates [{:value "10.5555/12345678", :type :plain-doi}]})
          "Matches should be made even when robots prohibits."))))

  (testing "If :ignore-robots is true then ignore Allow in the robots"
    (fake/with-fake-http ["http://allow-robots.com/abc" "Allow robots content 10.5555/12345678"
                          "http://allow-robots.com/robots.txt" "User-agent: *\nAllow: /"]
      (let [result (content-url/process-content-url-observation
                     util/mock-context
                     {:input-url "http://allow-robots.com/abc"
                      :ignore-robots true})]

        (is (= result {:input-url "http://allow-robots.com/abc"
                       :ignore-robots true
                       :input-content "Allow robots content 10.5555/12345678"
                       :candidates [{:value "10.5555/12345678", :type :plain-doi}]})
            "Matches should be made when robots.txt would allow anyway.")))))
