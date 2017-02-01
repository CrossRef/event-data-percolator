(ns event-data-percolator.core-test
  (:require [clojure.test :refer :all]
            [event-data-percolator.core :refer :all]))

(deftest ^:unit input-package-schema
  (testing "Schema should validate shape of Input Package")
  (testing "Input Package should have pages")
  (testing "Pages must be list")
  (testing "Pages can be empty")
  (testing "Page must have actions")
  (testing "Actions can be empty")
  (testing "Action must have url")
  (testing "Action must have id")
  (testing "Action must have observations")
  (testing "Observations may be empty")
  (testing "Observation must have type"))

(deftest ^:unit dedupe-action-false
  (testing "dedupe-action should mark Action as duplicate false first time")
  (testing "dedupe-action should mark Action with duplicate information if seen before"))

(deftest ^:unit process-plaintext
  (testing "process-plaintext should return input")
  (testing "process-plaintext should extract candidate unlinked DOIs")
  (testing "process-plaintext should extract candidate unlinked landing pages")
  (testing "process-plaintext should match unlinked DOIs")
  (testing "process-plaintext should match unlinked landing pages")
  (testing "process-plaintext should return union of matched DOIs"))

(deftest ^:unit process-plaintext-sensitive
  (testing "process-plaintext-sensitive should return all fields of process-plaintext except input")
  (testing "process-plaintext-sensitive should return sha-1 of input"))

(deftest ^:unit process-html-content
  (testing "process-html-content should return input")
  (testing "process-html-content should extract candidate unlinked DOIs")
  (testing "process-html-content should extract candidate unlinked landing pages")
  (testing "process-html-content should extract candidate linked DOIs")
  (testing "process-html-content should extract candidate linked landing pages")
  (testing "process-html-content should match unlinked DOIs")
  (testing "process-html-content should match unlinked landing pages")
  (testing "process-html-content should match linked DOIs")
  (testing "process-html-content should match linked landing pages")
  (testing "process-html-content should return union of matched DOIs"))

(deftest ^:unit process-html-content-sensitive
  (testing "process-html-content-sensitive should return all fields of process-html-content except input")
  (testing "process-html-content-sensitive should return sha-1 of input"))

(deftest ^:unit process-url
  (testing "process-url should return input")
  (testing "process-url should return candidate unlinked-article-landing-page")
  (testing "process-url should return candidate unlinked-doi")
  (testing "process-url should return matched DOI"))

(deftest ^:unit process-html-content-url
  (testing "process-html-content should return input")
  (testing "process-html-content should extract candidate unlinked DOIs")
  (testing "process-html-content should extract candidate unlinked landing pages")
  (testing "process-html-content should extract candidate linked DOIs")
  (testing "process-html-content should extract candidate linked landing pages")
  (testing "process-html-content should match unlinked DOIs")
  (testing "process-html-content should match unlinked landing pages")
  (testing "process-html-content should match linked DOIs")
  (testing "process-html-content should match linked landing pages")
  (testing "process-html-content should return union of matched DOIs"))

; Defering html-content-url-version until we can make a decision about how the output is represented.

(deftest ^:unit build-evidence-record
  (testing "build-evidence-record should take a processed input bundle and create an event for each unique observation output")
  (testing "build-evidence-record should create an id")
  (testing "build-evidence-record should nest the input")
  (testing "process-input-package should include domain-list artifact version"))

(deftest ^:component send-evidence-record
  (testing "send-evidence-record should send whole evidence record to evidence repository")
  (testing "send-evidence-record should send all events to bus"))