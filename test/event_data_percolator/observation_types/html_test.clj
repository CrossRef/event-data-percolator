(ns event-data-percolator.observation-types.html-test
  "Tests for the html extractor. Unstructured extraction is passed from html to plaintext namespace functions, 
   so proper testing of DOI and URL extraction from plaintext are performed in plaintext.test"
  (:require [clojure.test :refer :all]
            [event-data-percolator.observation-types.html :as html]))

(def plain-text "this is just some text 10.5555/11111")

(def html-document-links-and-text
  "<html><body><p>This article:10.5555/11111 and this doi:10.5555/22222 and <a href='https://doi.org/10.5555/33333'>this</a></p></body></html>")

(def html-fragment-links-and-text
  "<p>This article:10.5555/11111 and this doi:10.5555/22222 and <a href='https://doi.org/10.5555/33333'>this</a>")

(def html-fragment-duplicates
  "<p>10.5555/11111 <a href=''>10.5555/11111</a></p>")

(def multiple-anchors
  "<a href=\"http://example1.com\">example1</a> <a href=\"http://example1.com\">example1 again</a> <a href=\"http://example2.com\">example2</a> <a href=\"http://example3.com\">example3</a>")

; todo encoded sici

(deftest ^:unit plaintext-from-html
  (testing "Plaintext should be carried through unchanged."
    (is (= plain-text (html/plaintext-from-html plain-text))))

  (testing "Only text from nodes should be extracted from full HTML document."
    (is (=
          (html/plaintext-from-html html-document-links-and-text)
           "This article:10.5555/11111 and this doi:10.5555/22222 and this")))

  (testing "HTML fragment can be processed"
    (is (=
          (html/plaintext-from-html html-fragment-links-and-text)
           "This article:10.5555/11111 and this doi:10.5555/22222 and this"))))

(deftest ^:unit links-from-html
  (testing "Links (not text) can be extracted from full HTML document."
    (is (= #{"https://doi.org/10.5555/33333"}
           (html/links-from-html html-document-links-and-text))))

  (testing "Links (not text) can be extracted from full HTML fragment."
    (is (= #{"https://doi.org/10.5555/33333"}
           (html/links-from-html html-fragment-links-and-text))))

  (testing "Multiple links from input, deduping."
    (is (= #{"http://example1.com" "http://example2.com" "http://example3.com"}
           (html/links-from-html multiple-anchors)))))

(def domain-set #{"example.com" "example.net"})

(deftest process-html-content-observation
  (testing "Plain DOIs can be extracted from text nodes"
    (let [result (html/process-html-content-observation {:type "html" :input-content "the quick brown 10.5555/1111 jumps"} domain-set)]
      (is (= result {:type "html"
                     :input-content "the quick brown 10.5555/1111 jumps"
                     :candidates [{:value "10.5555/1111" :type :plain-doi}]})
          "One plain DOI candidate returned.")))

  (testing "URL DOIs can be extracted from text nodes"
    (let [result (html/process-html-content-observation {:type "html" :input-content "<p>the quick <b>brown 10.5555/1111</b> jumps</p>"} domain-set)]
      (is (= result {:type "html"
                     :input-content "<p>the quick <b>brown 10.5555/1111</b> jumps</p>"
                     :candidates [{:value "10.5555/1111" :type :plain-doi}]})
          "One plain DOI candidate found in a text node with surrounding markup.")))
  
  (testing "Hyperlinked URL DOIs can be extracted from links"
    (let [result (html/process-html-content-observation {:type "html" :input-content "<a href='http://doi.org/10.5555/22222'>cliquez ici</a>"} domain-set)]
      (is (= result {:type "html"
                     :input-content "<a href='http://doi.org/10.5555/22222'>cliquez ici</a>"
                     :candidates [{:value "http://doi.org/10.5555/22222" :type :doi-url}]})
          "One DOI URL candidate found when linked.")))

  (testing "ShortDOI URL DOIs can be extracted from text nodes"
    (let [result (html/process-html-content-observation {:type "html" :input-content "<p>http://doi.org/abcd</p>"} domain-set)]
      (is (= result {:type "html"
                     :input-content "<p>http://doi.org/abcd</p>"
                     :candidates [{:value "http://doi.org/abcd" :type :shortdoi-url}]})
          "One ShortDOI URL candidate found when unlinked")))

  (testing "ShortDOI hyperlinked DOIs can be extracted from links"
    (let [result (html/process-html-content-observation {:type "html" :input-content "<a href='http://doi.org/abcd'>short and sweet</a>"} domain-set)]
      (is (= result {:type "html"
                     :input-content "<a href='http://doi.org/abcd'>short and sweet</a>"
                     :candidates [{:value "http://doi.org/abcd" :type :shortdoi-url}]})
          "One ShortDOI URL candidate found when linked")))

  (testing "PIIs can be extracted from text nodes"
    (let [result (html/process-html-content-observation {:type "html" :input-content "this is <em>my PII S232251141300001-2</em> there"} domain-set)]
      (is (= result {:type "html"
                     :input-content "this is <em>my PII S232251141300001-2</em> there"
                     :candidates [{:value "S232251141300001-2" :type :pii}]})
          "PII candidate found in text nodes")))

  (testing "Landing Page URLs can be extracted from text nodes"
    (let [result (html/process-html-content-observation {:type "html" :input-content "<b>one two <i>three http://example.com/four</i> five http://ignore.com/four"} domain-set)]
      (is (= result {:type "html"
                     :input-content "<b>one two <i>three http://example.com/four</i> five http://ignore.com/four"
                     :candidates [{:value "http://example.com/four" :type :landing-page-url}]})
          "Article landing page from known domain can be extracted from text nodes. Non-matching domains ignored.")))


  (testing "Landing Page URLs can be extracted from links"
    (let [result (html/process-html-content-observation {:type "html" :input-content "<p> <a href='http://example.com/five'>this</a> <a href='http://ignore.com/four'>ignore me!</a></p>"} domain-set)]
      (is (= result {:type "html"
                     :input-content "<p> <a href='http://example.com/five'>this</a> <a href='http://ignore.com/four'>ignore me!</a></p>"
                     :candidates [{:value "http://example.com/five" :type :landing-page-url}]})
          "Article landing page from known domain can be extracted from link. Non-matching domains ignored."))))
