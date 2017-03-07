(ns event-data-percolator.matchers.shortdoi-url-test
  (:require [clojure.test :refer :all]
            [org.httpkit.fake :as fake]
            [event-data-percolator.matchers.shortdoi-url :as shortdoi-url]
            [event-data-percolator.test-util :as util]))

(deftest ^:unit match-shortdoi-url-candidate
  (testing "match-shortdoi-url-candidate matches valid shortDOI and converts to full DOI."
    (fake/with-fake-http ["https://doi.org/api/handles/10/hvx" (util/short-doi-ok "10.5555/12345678")]
      (let [result (shortdoi-url/match-shortdoi-url-candidate {:value "http://doi.org/hvx"} nil)]
        (is (= result {:value "http://doi.org/hvx" :match "https://doi.org/10.5555/12345678"}))))))
