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

(deftest ^:unit final-redirect-url-recorded
  (testing "When a URL is redirected, the final URL should be included in the response."
    (fake/with-fake-http ["http://feedproxy.google.com/~r/thebreakthrough/~3/Kr57lBR2w1w/stuck-in-the-s-curve"
                          {:status 301 :headers {:location "http://interrim.com/test"}}
                          
                          ; An extra redirect to ensure that we follow more than one hop.
                          "http://interrim.com/test"
                          {:status 302 :headers {:location "https://thebreakthrough.org/index.php/voices/stuck-in-the-s-curve?utm_source=feedburner&utm_medium=feed&utm_campaign=Feed%3A+thebreakthrough+%28The+Breakthrough+Institute+Full+Site+RSS%29"}}

                          "https://thebreakthrough.org/index.php/voices/stuck-in-the-s-curve?utm_source=feedburner&utm_medium=feed&utm_campaign=Feed%3A+thebreakthrough+%28The+Breakthrough+Institute+Full+Site+RSS%29"
                          {:status 200 :body "<html>hello</html>"}]

      (let [result (web/fetch {} "http://feedproxy.google.com/~r/thebreakthrough/~3/Kr57lBR2w1w/stuck-in-the-s-curve")]
        (is (= (:status result) 200)
          "Final redirect status should be returned")

        (is (= (:body result) "<html>hello</html>")
          "Body of final hop should be returned")

        (is (= (:final-url result) "https://thebreakthrough.org/index.php/voices/stuck-in-the-s-curve?utm_source=feedburner&utm_medium=feed&utm_campaign=Feed%3A+thebreakthrough+%28The+Breakthrough+Institute+Full+Site+RSS%29")
          "URL of last hop should be recorded")))))
