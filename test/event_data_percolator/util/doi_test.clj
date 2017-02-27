(ns event-data-percolator.util.doi-test
  (:require [clojure.test :refer :all]
            [event-data-percolator.util.doi :as doi]
            [org.httpkit.fake :as fake]))

(def ok-response
  {:status 303 :headers {:location "http://link.springer.com/10.1007/s00423-015-1364-1"}})

(deftest ^:unit resolve-escaped
  (testing "resolve-doi-maybe-escaped should return an unescaped DOI, if input unescaped and it's valid"
    (fake/with-fake-http ["https://doi.org/10.1007/s00423-015-1364-1" ok-response]
      (is (= (doi/resolve-doi-maybe-escaped "10.1007/s00423-015-1364-1")
             "10.1007/s00423-015-1364-1"))))

  (testing "resolve-doi-maybe-escaped should return an unescaped DOI, if input URL escaped and it's valid"
    (fake/with-fake-http ["https://doi.org/10.1007/s00423-015-1364-1" ok-response]
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
