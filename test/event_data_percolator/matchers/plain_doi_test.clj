(ns event-data-percolator.matchers.plain-doi-test
  (:require [clojure.test :refer :all]
            [org.httpkit.fake :as fake]
            [event-data-percolator.matchers.plain-doi :as plain-doi]))

(deftest match-plain-doi-candidate
  (testing "match-plain-doi-candidate matches valid DOI."
    (fake/with-fake-http ["https://doi.org/10.5555/12345678" {:status 303 :headers {:location "http://psychoceramics.labs.crossref.org/10.5555-12345678.html"}}]
      (let [result (plain-doi/match-plain-doi-candidate {:value "10.5555/12345678"} nil)]
        (is (= result {:value "10.5555/12345678", :match "https://doi.org/10.5555/12345678"})))))

  (testing "match-plain-doi-candidate does not match nonexistent DOI."
    ; It will try to drop a few off the end to match, with different encodings. Tolerate this.
    (fake/with-fake-http ["https://doi.org/10.5555/12345678" {:status 404}
                          "https://doi.org/10.5555%2F12345678" {:status 404}
                          "https://doi.org/10.5555%2F1234567" {:status 404}
                          "https://doi.org/10.5555/1234567" {:status 404}
                          "https://doi.org/10.5555%2F123456" {:status 404}
                          "https://doi.org/10.5555/123456" {:status 404}
                          "https://doi.org/10.5555%2F12345" {:status 404}
                          "https://doi.org/10.5555/12345" {:status 404}
                          "https://doi.org/10.5555%2F1234" {:status 404}
                          "https://doi.org/10.5555/1234" {:status 404}]
      (let [result (plain-doi/match-plain-doi-candidate {:value "10.5555/12345678"} nil)]
        (is (= result {:value "10.5555/12345678", :match nil}))))))
