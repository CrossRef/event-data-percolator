(ns event-data-percolator.observation-test
  "Most of the observation functions are tested via input-bundle-test"
  (:require [event-data-percolator.observation :as observation]
            [clojure.test :refer :all]
            [event-data-percolator.test-util :as util]))

(def valid-candidate
  "A valid candidate that's used against positive and negative tests, so we know it's the same one each time."
  {:type "plaintext" :input-content "text 10.5555/12345678"})

(def unrecognised-candidate
  "A candidate with unrecognised type."
  {:type "plaintext" :input-content "text 10.5555/12345678"})


(deftest ^:unit process-observation-negative
  (testing "Unrecognised observation types should be passed through, with 'unrecognised' flag set."
    (let [result (observation/process-observation (assoc valid-candidate :type "YOU-PROBABLY-HAVENT-HEARD-OF-IT") false #{} (atom []))]
      (is (-> result :candidates empty?) "Candidates should not be attached when type not recognised.")
      (is (= :unrecognised-observation-type (-> result :error)) "Unrecognised flag should be set.")))

  (testing "When sensitive, input-content should be removed in all cases."
    ; Sensitive false first.
    (let [result (observation/process-observation valid-candidate false #{} (atom []))]
      (is (-> result :candidates not-empty) "The type is recognised, indicated by presence of candidates.")
      (is (-> result :input-content) "The input content is passed through when sensitive is false"))

    (let [result (observation/process-observation (assoc valid-candidate :sensitive true) false #{} (atom []))]
      (is (-> result :candidates not-empty) "The type is recognised, indicated by presence of candidates.")
      (is (nil? (-> result :input-content)) "The input content is removed when sensitive is true")))

  (testing "When there is a duplicate, candidates should not be extracted"
    (let [result (observation/process-observation valid-candidate true #{} (atom []))]

      (is (-> result :candidates empty?) "Candidates should not be extracted when duplicate.")))

  (testing "When there is a duplicate, sensitive should still result in input-content being removed.")
    (let [result (observation/process-observation (assoc valid-candidate :sensitive true) true #{} (atom []))]
      (is (-> result :input-content empty?) "Input content should be removed.")
      (is (-> result :input-content-hash) "Input content hash should be included.")))

(deftest ^:unit process-observation
  (testing "When the observation type is recognised, the appropriate observation processor should be applied and candidates produced."
    (let [result (observation/process-observation valid-candidate false #{} (atom []))]
      ; Content of candidates is tested elsewhere.
      (is (-> result :candidates not-empty) "Candidates are attached as a result of processor running.")))

  (testing "When the observation type is recognised, any extra fields should be carried through."
    (let [result (observation/process-observation (assoc valid-candidate :random-extra :value) false #{} (atom []))]
      (is (= :value (:random-extra result)) "Extra field passed through.")))

  (testing "When the observation type is recognised, input-content-hash should be supplied for input-content."
    (let [result (observation/process-observation valid-candidate false #{} (atom []))]
      (is (:input-content-hash result) "Input hash attached."))))
