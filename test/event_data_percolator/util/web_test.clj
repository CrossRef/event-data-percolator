(ns event-data-percolator.util.web-test
  (:require [clojure.test :refer :all]
            [event-data-percolator.util.web :as web]
            [org.httpkit.fake :as fake]
            [event-data-percolator.test-util :as util]))


(deftest ^:unit should-visit-landing-page?
  (let [domain-set #{"example.com" "example.net"}]
    (testing "should-visit-landing-page? should return false if blacklisted."
      (is (false? (web/should-visit-landing-page? domain-set "http://example.com/xyz.pdf")) "pdf extension should be excluded."))

    (testing "should-visit-landing-page? should return false if invalid URL."
      (is (false? (web/should-visit-landing-page? domain-set "")) "Invalid URL")
      (is (false? (web/should-visit-landing-page? domain-set nil)) "Invalid URL")
      (is (false? (web/should-visit-landing-page? domain-set "example.com/")) "Invalid URL"))

    (testing "should-visit-landing-page? should return false if url not on domain list."
      (is (false? (web/should-visit-landing-page? domain-set "http://example.org/abcdefg"))
           "Domain not in set so should not visit as a landing page."))

    (testing "should-visit-landing-page? should return true if url on domain list."
      (is (true? (web/should-visit-landing-page? domain-set "http://example.com/abcdefg"))
           "Domain in set so should visit as a landing page."))))

(deftest ^:unit should-visit-content-page?
  (let [domain-set #{"example.com" "example.net"}]
    (testing "should-visit-content-page? should return false if blacklisted."
      (is (false? (web/should-visit-content-page? domain-set "http://example.com/xyz.pdf")) "pdf extension should be excluded."))

    (testing "should-visit-content-page? should return false if invalid URL."
      (is (false? (web/should-visit-content-page? domain-set "")) "Invalid URL")
      (is (false? (web/should-visit-content-page? domain-set nil)) "Invalid URL")
      (is (false? (web/should-visit-content-page? domain-set "example.com/")) "Invalid URL"))

    (testing "should-visit-content-page? should return true if not on domain list."
      (is (true? (web/should-visit-content-page? domain-set "http://example.org/abcdefg"))
           "Domain not in set so may visit it as a content page."))

    (testing "should-visit-content-page? should return false if on domain list."
      (is (false? (web/should-visit-content-page? domain-set "http://example.com/abcdefg"))
           "Domain in set so not allowed to visit as a content url page."))))
