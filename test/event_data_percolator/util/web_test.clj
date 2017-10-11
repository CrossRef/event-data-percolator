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

(deftest ^:unit remove-tracking-params
  (testing "URLs with no tracking aren't affected"
    (is (= (web/remove-tracking-params "http://www.example.com")
           "http://www.example.com")
        "No parameters, should be unaffected")

    (is (= (web/remove-tracking-params "http://www.example.com:8080/")
           "http://www.example.com:8080/")
        "Port number should be included.")

    (is (= (web/remove-tracking-params "http://www.example.com:80/")
           "http://www.example.com:80/")
        "Port number should be included.")

    (is (= (web/remove-tracking-params "https://www.example.com")
           "https://www.example.com")
        "No parameters, should be unaffected")

    (is (= (web/remove-tracking-params "http://www.example.com?q=1")
           "http://www.example.com?q=1")
        "Parameters carried through")

    (is (= (web/remove-tracking-params "http://www.example.com?q=1&a=2&c=3&k=4")
           "http://www.example.com?q=1&a=2&c=3&k=4")
        "Parameters carried through in same order")

    (is (= (web/remove-tracking-params "http://www.example.com?c=1&h=2&i=3&c=4&k=5&e=6&n=7")
           "http://www.example.com?c=1&h=2&i=3&c=4&k=5&e=6&n=7")
        "Duplicate parameters carried through in the same order."))

  (testing "URLs with fragments aren't affected by removal"
    (is (= (web/remove-tracking-params "http://www.example.com/animals#pangolion")
           "http://www.example.com/animals#pangolion")
        "Simple fragment")

    (is (= (web/remove-tracking-params "http://www.example.com/animals?beginswith=h#hoarse")
           "http://www.example.com/animals?beginswith=h#hoarse")
        "Irrelevant query params, all preserved.")

    (is (= (web/remove-tracking-params "http://www.example.com/animals?beginswith=h&utm_medium=xyz#hoarse")
           (web/remove-tracking-params "http://www.example.com/animals?utm_keyword=throat&beginswith=h&utm_medium=xyz#hoarse")
           "http://www.example.com/animals?beginswith=h#hoarse")
        "Mixture of params. Tracking removed, fragment remains"))

  (testing "URLs with a mixture of tracking params and non-tracking params should have only those tracking params removed"
    (is (= (web/remove-tracking-params "http://www.example.com/animals?beginswith=h")
           (web/remove-tracking-params "http://www.example.com/animals?beginswith=h&utm_medium=xyz")
           (web/remove-tracking-params "http://www.example.com/animals?utm_keyword=throat&beginswith=h")
           (web/remove-tracking-params "http://www.example.com/animals?utm_keyword=throat&beginswith=h&utm_medium=xyz")
           "http://www.example.com/animals?beginswith=h")
        "Single tracking param removed")

    (is (= (web/remove-tracking-params "http://www.example.com/animals?beginswith=h&endswith=e")
           (web/remove-tracking-params "http://www.example.com/animals?beginswith=h&utm_medium=xyz&endswith=e")
           (web/remove-tracking-params "http://www.example.com/animals?utm_keyword=throat&beginswith=h&endswith=e")
           (web/remove-tracking-params "http://www.example.com/animals?utm_keyword=throat&beginswith=h&utm_medium=xyz&endswith=e")
           "http://www.example.com/animals?beginswith=h&endswith=e")
        "Multiple mixed-up tracking params removed."))

  (testing "Similar but different params should not be removed"
    (is (= (web/remove-tracking-params "http://www.example.com/animals?this=that&xutm_medium=1234&utm_keyword")
           "http://www.example.com/animals?this=that&xutm_medium=1234")
        "Similar, but not actual, params intact"))

  (testing "All params are removed, the trailing question mark should also be removed"
    (is (= (web/remove-tracking-params "http://www.example.com/animals")
           (web/remove-tracking-params "http://www.example.com/animals?")
           (web/remove-tracking-params "http://www.example.com/animals?utm_keyword=ears")
           (web/remove-tracking-params "http://www.example.com/animals?utm_keyword=ears&utm_medium=nose")
           (web/remove-tracking-params "http://www.example.com/animals?utm_keyword=ears&utm_medium=nose&utm_campaign=throat")
           "http://www.example.com/animals")
        "When all params removed, question mark also removed"))

  (testing "Repeated parameters left intact"
    (is (= (web/remove-tracking-params "http://www.example.com/animals?animal=cat&utm_campaign=mammals&purpose=porpoise&purpose=download&purpose=porpoise")
           "http://www.example.com/animals?animal=cat&purpose=porpoise&purpose=download&purpose=porpoise")
        "Dupliate parameters intact and in order."))

  (testing "If a malformed URL causes an exception, just return it verbatim."
    (is (= (web/remove-tracking-params nil) nil) "NullPointerException causing input should return input verbatim.")
    (is (= (web/remove-tracking-params "http://") "http://") "URISyntaxException causing input should return input verbatim.")))

