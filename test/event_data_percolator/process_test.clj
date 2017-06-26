(ns event-data-percolator.process-test
  "Tests for top-level process functions."
  (:require [event-data-percolator.process :as process]
            [event-data-percolator.action :as action]
            [clojure.test :refer :all]
            [clojure.data.json :as json]
            [config.core :refer [env]]
            [org.httpkit.fake :as fake]
            [event-data-percolator.test-util :as util]))

(deftest ^:unit env-pre
  (testing "Environment variables set as expected")
  (is (= "memory" (:percolator-evidence-storage env)) "Config check EVIDENCE_STORAGE is in-memory")
  (is (= event-data-percolator.evidence-record/evidence-url-prefix "evidence/") "Evidence URL prefix constant should be as expected in later tests."))
