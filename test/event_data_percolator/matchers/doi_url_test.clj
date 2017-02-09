(ns event-data-percolator.matchers.doi-url-test
  (:require [clojure.test :refer :all]
            [org.httpkit.fake :as fake]
            [event-data-percolator.matchers.doi-url :as doi-url]))

(deftest ^:unit match-doi-url-candidate
  (testing "match-doi-url-candidate matches valid DOI."
    (fake/with-fake-http ["https://doi.org/10.5555/12345678" {:status 303 :headers {:location "http://psychoceramics.labs.crossref.org/10.5555-12345678.html"}}]
      (let [result (doi-url/match-doi-url-candidate {:value "https://doi.org/10.5555/12345678"} nil)]
        (is (= result {:value "https://doi.org/10.5555/12345678", :match "https://doi.org/10.5555/12345678"})))))

  (testing "match-doi-url-candidate does not match nonexistent DOI."
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
      (let [result (doi-url/match-doi-url-candidate {:value "http://doi.org/10.5555/12345678"} nil)]
        (is (= result {:value "http://doi.org/10.5555/12345678", :match nil})))))

  (testing "match-doi-url-candidate normalizes DOI."
    (fake/with-fake-http ["https://doi.org/10.5555/12345678" {:status 303 :headers {:location "http://psychoceramics.labs.crossref.org/10.5555-12345678.html"}}]
      ; Use dx.doi.org resolver and HTTPs. Should be normalized to doi.org and HTTPS.
      (let [result (doi-url/match-doi-url-candidate {:value "http://dx.doi.org/10.5555/12345678"} nil)]
        (is (= result {:value "http://dx.doi.org/10.5555/12345678", :match "https://doi.org/10.5555/12345678"}))))))

