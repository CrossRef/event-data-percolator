(ns event-data-percolator.matchers.doi-url-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [org.httpkit.fake :as fake]
            [event-data-percolator.matchers.doi-url :as doi-url]
            [event-data-percolator.test-util :as util]))



(deftest ^:component match-doi-url-candidate
  (testing "match-doi-url-candidate matches valid DOI."
    (fake/with-fake-http ["https://doi.org/api/handles/10.5555/12345678" {:status 200 :body (json/write-str {"handle" "10.5555/12345678"})}]
      (let [result (doi-url/match-doi-url-candidate {:value "https://doi.org/10.5555/12345678"} nil)]
        (is (= result {:value "https://doi.org/10.5555/12345678", :match "https://doi.org/10.5555/12345678"})))))

  (testing "match-doi-url-candidate does not match nonexistent DOI."
    ; It will try to drop a few off the end to match, with different encodings. Tolerate this.
    (fake/with-fake-http [#"https://doi.org/api/handles/10.5555/12" (util/doi-not-found)
                          #"https://doi.org/api/handles/10.5555%2F12" (util/doi-not-found)]
      (let [result (doi-url/match-doi-url-candidate {:value "http://doi.org/10.5555/12345678"} nil)]
        (is (= result {:value "http://doi.org/10.5555/12345678", :match nil})))))

  (testing "match-doi-url-candidate normalizes DOI."
    (fake/with-fake-http ["https://doi.org/api/handles/10.5555/12345678" (util/doi-ok "10.5555/12345678")]
      ; Use dx.doi.org resolver and HTTPs. Should be normalized to doi.org and HTTPS.
      (let [result (doi-url/match-doi-url-candidate {:value "http://dx.doi.org/10.5555/12345678"} nil)]
        (is (= result {:value "http://dx.doi.org/10.5555/12345678", :match "https://doi.org/10.5555/12345678"}))))))

