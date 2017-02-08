(ns event-data-percolator.matchers.pii-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [org.httpkit.fake :as fake]
            [event-data-percolator.matchers.pii :as pii]))

(deftest match-pii-candidate
  (testing "match-pii-candidate matches valid DOI."
    (fake/with-fake-http ["https://api.crossref.org/v1/works"
                          {:status 200
                           :headers {:content-type "application/json"}
                           :body (json/write-str {:message {:items [{:DOI "10.5555/12345678"}]}})}

                          "https://doi.org/10.5555/12345678"
                          {:status 303
                           :headers {:location "http://psychoceramics.labs.crossref.org/10.5555-12345678.html"}}]
      (let [result (pii/match-pii-candidate {:value "S232251141300001-2"} nil)]
        (is (= result {:value "S232251141300001-2", :match "https://doi.org/10.5555/12345678"})))))

  (testing "match-pii-candidate doesn't match DOI if not unique mapping."
    (fake/with-fake-http ["https://api.crossref.org/v1/works"
                          {:status 200
                           :headers {:content-type "application/json"}
                           :body (json/write-str {:message {:items [{:DOI "10.5555/12345678"}
                                                                    {:DOI "10.5555/11111"}]}})}]

      (let [result (pii/match-pii-candidate {:value "S232251141300001-2"} nil)]
        (is (= result {:value "S232251141300001-2", :match nil})))))

  (testing "match-pii-candidate doesn't match DOI if it doesn't exist."
    (fake/with-fake-http ["https://api.crossref.org/v1/works"
                          {:status 200
                           :headers {:content-type "application/json"}
                           :body (json/write-str {:message {:items [{:DOI "10.5555/12345678"}]}})}

                          "https://doi.org/10.5555/12345678"
                          {:status 404}]
      (let [result (pii/match-pii-candidate {:value "S232251141300001-2"} nil)]
        (is (= result {:value "S232251141300001-2", :match nil}))))))