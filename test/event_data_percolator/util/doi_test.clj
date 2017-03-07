(ns event-data-percolator.util.doi-test
  (:require [clojure.test :refer :all]
            [event-data-percolator.util.doi :as doi]
            [org.httpkit.fake :as fake]
            [event-data-percolator.test-util :as util]))

(def ok-response
  {:status 303 :headers {:location "http://link.springer.com/10.1007/s00423-015-1364-1"}})

(deftest ^:unit resolve-escaped
  (testing "resolve-doi-maybe-escaped should return an unescaped DOI, if input unescaped and it's valid"
    (fake/with-fake-http ["https://doi.org/api/handles/10.1007/s00423-015-1364-1" (util/doi-ok "10.1007/s00423-015-1364-1")]
      (is (= (doi/resolve-doi-maybe-escaped "10.1007/s00423-015-1364-1")
             "10.1007/s00423-015-1364-1"))))

  (testing "resolve-doi-maybe-escaped should return an unescaped DOI, if input URL escaped and it's valid"
    (fake/with-fake-http ["https://doi.org/api/handles/10.1007/s00423-015-1364-1" (util/doi-ok "10.1007/s00423-015-1364-1")]
      (is (= (doi/resolve-doi-maybe-escaped "10.1007%2fs00423-015-1364-1")
               "10.1007/s00423-015-1364-1")
          "Works with lower case escaped slash.")

      (is (= (doi/resolve-doi-maybe-escaped "10.1007%2Fs00423-015-1364-1")
               "10.1007/s00423-015-1364-1")
          "Works with upper case escaped slash."))))


; Regression for https://github.com/CrossRef/event-data-percolator/issues/27
(deftest ^:unit drop-right-char-surrogates
  (is (=
        (doi/drop-right-char "10.1007/s12520-015-0279-7😀")
        "10.1007/s12520-015-0279-7")
    "When there's a surrogate pair, remove both.")

  (is (=
        (doi/drop-right-char (.substring "10.1007/s12520-015-0279-7😀" 0 26))
        "10.1007/s12520-015-0279-7")
    "When there's a dangling surrogate, remove it.")

  (is (=
        (doi/drop-right-char "10.1007/s12520-015-0279-7")
        "10.1007/s12520-015-0279-")
      "Normal strings, right character dropped."))

; Regression for https://github.com/CrossRef/event-data-percolator/issues/33 and 25
(deftest ^:unit qmarks-hash-not-included-resolve-doi
  (testing "Trailing question marks and hashes are discarded by resolve-doi"
    (is (= (doi/resolve-doi "10.1111/nicc.12290#.wlw5yueemak.twitter")
           "10.1111/nicc.12290"))

    (is (= (doi/resolve-doi "10.2752/136270497779613666?journalcode=rfft20")
           "10.2752/136270497779613666"))

    (is (= (doi/resolve-doi "10.1111/(issn)1475-6811?hootpostid=cdae1d8ac3a881bcc0152faf4bb970a1")
           "10.1111/(issn)1475-6811"))

    (is (= (doi/resolve-doi "10.1007/s11739-017-1643-7?utm_content=bufferd3cda&utm_medium=social&utm_source=twitter.com&utm_campaign=buffer")
           "10.1007/s11739-017-1643-7"))

    ; https://github.com/CrossRef/event-data-percolator/issues/25
    (is (= (doi/resolve-doi "10.1007/s00127-017-1346-4?wt_mc=internal.event.1.sem.articleauthoronlinefirst")
           "10.1007/s00127-017-1346-4"))))

(deftest ^:unit shortdoi-resolve-doi
  (testing "ShortDOIs are resolved to normal DOIs by resolve-doi"
    (is (= (doi/resolve-doi "hvx")
              "10.5555/12345678"))))

; Regression for https://github.com/CrossRef/event-data-percolator/issues/31
(deftest ^:unit empty-doi-resolve-doi
  (testing "An empty DOI doesn't resolve as extant"
    (is (= (doi/resolve-doi "") nil))
    (is (= (doi/validate-doi-dropping "") nil))
    (is (= (doi/validate-doi-dropping "https://doi.org/") nil))))

; Regression for https://github.com/CrossRef/event-data-percolator/issues/30
(deftest ^:unit empty-doi-resolve-doi
  (testing "Nonexisting DOIs that return 303 from proxy shouldn't be accepted as extant."
    (is (= (doi/resolve-doi "www.uclouvain.be/784506.html") nil))
    (is (= (doi/validate-doi-dropping "https://doi.org/www.uclouvain.be/784506.html") nil))))

