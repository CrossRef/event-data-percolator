(ns event-data-percolator.matchers.plain-doi-test
  (:require [clojure.test :refer :all]
            [org.httpkit.fake :as fake]
            [event-data-percolator.matchers.plain-doi :as plain-doi]
            [event-data-percolator.test-util :as util]))

(deftest ^:unit match-plain-doi-candidate
  (testing "match-plain-doi-candidate matches valid DOI."
    (fake/with-fake-http ["https://doi.org/api/handles/10.5555/12345678" (util/doi-ok "10.5555/12345678")]
      (let [result (plain-doi/match-plain-doi-candidate {:value "10.5555/12345678"} nil)]
        (is (= result {:value "10.5555/12345678", :match "https://doi.org/10.5555/12345678"})))))

  (testing "match-plain-doi-candidate does not match nonexistent DOI."
    ; It will try to drop a few off the end to match, with different encodings. Tolerate this.
    (fake/with-fake-http [; Initial encoded/unencoded don't match.
                          "https://doi.org/api/handles/10.5555/12345678" (util/doi-not-found)
                          "https://doi.org/api/handles/10.5555%2F12345678" (util/doi-not-found)

                          ; Nor do subsequent ones.
                          #"https://doi.org/api/handles/10.5555.*" (util/doi-not-found)]
      (let [result (plain-doi/match-plain-doi-candidate {:value "10.5555/12345678"} nil)]
        (is (= result {:value "10.5555/12345678", :match nil}))))))

