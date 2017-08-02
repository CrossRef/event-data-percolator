(ns event-data-percolator.matchers.pii-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [org.httpkit.fake :as fake]
            [event-data-percolator.matchers.pii :as pii]
            [event-data-percolator.test-util :as util]))



(deftest ^:component match-pii-candidate
  (testing "match-pii-candidate matches valid DOI."
    (fake/with-fake-http ["https://api.crossref.org/v1/works"
                          {:status 200
                           :headers {:content-type "application/json"}
                           :body (json/write-str {:message {:items [{:DOI "10.5555/12345678"}]}})}

                          "https://doi.org/api/handles/10.5555/12345678" (util/doi-ok "10.5555/12345678")]
      (let [result (pii/match-pii-candidate util/mock-context {:value "S232251141300001-2"})]
        (is (= result {:value "S232251141300001-2", :match "https://doi.org/10.5555/12345678"})))))

  (testing "match-pii-candidate doesn't match DOI if not unique mapping."
    (fake/with-fake-http ["https://api.crossref.org/v1/works"
                          {:status 200
                           :headers {:content-type "application/json"}
                           :body (json/write-str {:message {:items [{:DOI "10.5555/12345678"}
                                                                    {:DOI "10.5555/11111"}]}})}]

      (let [result (pii/match-pii-candidate util/mock-context {:value "S232251141300001-2"})]
        (is (= result {:value "S232251141300001-2", :match nil})))))

  (testing "match-pii-candidate doesn't match DOI if it doesn't exist."
    (fake/with-fake-http ["https://api.crossref.org/v1/works"
                          {:status 200
                           :headers {:content-type "application/json"}
                           :body (json/write-str {:message {:items [{:DOI "10.5555/NOT_FOUND"}]}})}

                          "https://doi.org/api/handles/10.5555/NOT_FOUND" (util/doi-not-found)
                          ; And attempts to chop the end off
                          #"https://doi.org/api/handles/1.*" (util/doi-not-found)]
      (let [result (pii/match-pii-candidate nil {:value "S232251141300001-2"})]
        (is (= result {:value "S232251141300001-2", :match nil})))))

  (testing "empty PII should never result in a match or query"
    ; Ensure that no network activity is made.
    (fake/with-fake-http []
      (let [result (pii/match-pii-candidate util/mock-context {:value ""})]
        (is (= result {:value "", :match nil})))))

  (testing "nill PII should never result in a match or query"
    ; Ensure that no network activity is made.
    (fake/with-fake-http []
      (let [result (pii/match-pii-candidate nil {:value nil})]
        (is (= result {:value nil, :match nil})))))

  (testing "match-pii-candidate can deal with non-JSON response."
    (fake/with-fake-http ["https://api.crossref.org/v1/works"
                          {:status 200
                           :headers {:content-type "application/json"}
                           :body "<xml>BANG</xml>"}]
      (let [result (pii/match-pii-candidate util/mock-context {:value "CRASHING-XML"})]
        (is (= result {:value "CRASHING-XML", :match nil})))))

  (testing "match-pii-candidate can deal with empty response."
    (fake/with-fake-http ["https://api.crossref.org/v1/works"
                          {:status 200
                           :headers {:content-type "application/json"}
                           :body ""}]
      (let [result (pii/match-pii-candidate util/mock-context {:value "CRASHING-EMPTY"})]
        (is (= result {:value "CRASHING-EMPTY", :match nil})))))

    (testing "match-pii-candidate can deal with exception."
    (fake/with-fake-http ["https://api.crossref.org/v1/works"
                          #(throw (new Exception "Something went wrong."))]
      (let [result (pii/match-pii-candidate util/mock-context {:value "CRASHING-EXCEPTION"})]
        (is (= result {:value "CRASHING-EXCEPTION", :match nil}))))))

