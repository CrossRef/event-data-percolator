(ns event-data-percolator.observation-types.html-test
  "Tests for the html extractor. Unstructured extraction is passed from html to plaintext namespace functions, 
   so proper testing of DOI and URL extraction from plaintext are performed in plaintext.test"
  (:require [clojure.test :refer :all]
            [event-data-percolator.observation-types.html :as html]
            [event-data-percolator.test-util :as util]))

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
    (is (= plain-text (html/plaintext-from-html
                        plain-text))))

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
           (html/links-from-html
             html-fragment-links-and-text))))

  (testing "Multiple links from input, deduping."
    (is (= #{"http://example1.com" "http://example2.com" "http://example3.com"}
           (html/links-from-html
             multiple-anchors)))))

(def domain-set #{"example.com" "example.net" "doi.org" "dx.doi.org"})

(deftest ^:unit process-html-content-observation
  (testing "Plain DOIs can be extracted from text nodes"
    (let [result (html/process-html-content-observation
                   util/mock-context
                   {:type "html" :input-content "the quick brown 10.5555/1111 jumps"})]

      (is (= result {:type "html"
                     :canonical-url nil
                     :input-content "the quick brown 10.5555/1111 jumps"
                     :candidates [{:value "10.5555/1111" :type :plain-doi}]})
          "One plain DOI candidate returned.")))

  (testing "URL DOIs can be extracted from text nodes"
    (let [result (html/process-html-content-observation
                   util/mock-context
                   {:type "html" :input-content "<p>the quick <b>brown 10.5555/1111</b> jumps</p>"})]

      (is (= result {:type "html"
                     :canonical-url nil
                     :input-content "<p>the quick <b>brown 10.5555/1111</b> jumps</p>"
                     :candidates [{:value "10.5555/1111" :type :plain-doi}]})
          "One plain DOI candidate found in a text node with surrounding markup.")))
  
  (testing "Hyperlinked URL DOIs can be extracted from links"
    (let [result (html/process-html-content-observation
                   (assoc util/mock-context :domain-set #{"doi.org"})
                   {:type "html" :input-content "<a href='http://doi.org/10.5555/22222'>cliquez ici</a>"})

          expected {:type "html"
                    :canonical-url nil
                    :input-content "<a href='http://doi.org/10.5555/22222'>cliquez ici</a>"
                    :candidates [{:type :doi-url, :value "http://doi.org/10.5555/22222"}]}]

      (is (= result expected))))

  (testing "ShortDOI URL DOIs can be extracted from text nodes"
    (let [result (html/process-html-content-observation
                   (assoc util/mock-context :domain-set #{"doi.org"})
                   {:type "html" :input-content "<p>http://doi.org/abcd</p>"})]

      (is (= result {:type "html"
                     :canonical-url nil
                     :input-content "<p>http://doi.org/abcd</p>"
                     :candidates [{:type :shortdoi-url, :value "http://doi.org/abcd"}]})
          "One ShortDOI URL candidate found when unlinked")))

  (testing "ShortDOI hyperlinked DOIs can be extracted from links"
    (let [result (html/process-html-content-observation
                   (assoc util/mock-context :domain-set #{"doi.org"})
                   {:type "html" :input-content "<a href='http://doi.org/abcd'>short and sweet</a>"})

          expected {:type "html"
                    :canonical-url nil
                    :input-content "<a href='http://doi.org/abcd'>short and sweet</a>"
                    :candidates [{:type :shortdoi-url, :value "http://doi.org/abcd"}]}]

      (is (= result expected))))

  (testing "PIIs can be extracted from text nodes"
    (let [result (html/process-html-content-observation
                   util/mock-context
                   {:type "html" :input-content "this is <em>my PII S232251141300001-2</em> there"})]

      (is (= result {:type "html"
                     :canonical-url nil
                     :input-content "this is <em>my PII S232251141300001-2</em> there"
                     :candidates [{:value "S232251141300001-2" :type :pii}]})
          "PII candidate found in text nodes")))

  (testing "Landing Page URLs can be extracted from text nodes"
    (let [result (html/process-html-content-observation
                   (assoc util/mock-context :domain-set #{"example.com"})
                   {:type "html" :input-content "<b>one two <i>three http://example.com/four</i> five http://ignore.com/four"})]

      (is (= result {:type "html"
                     :canonical-url nil
                     :input-content "<b>one two <i>three http://example.com/four</i> five http://ignore.com/four"
                     :candidates [{:value "http://example.com/four" :type :landing-page-url}]})
          "Article landing page from known domain can be extracted from text nodes. Non-matching domains ignored.")))


  (testing "Landing Page URLs can be extracted from links"
    (let [result (html/process-html-content-observation
                   (assoc util/mock-context :domain-set #{"example.com"})
                   {:type "html" :input-content "<p> <a href='http://example.com/five'>this</a> <a href='http://ignore.com/four'>ignore me!</a></p>"})]

      (is (= result {:type "html"
                     :canonical-url nil
                     :input-content "<p> <a href='http://example.com/five'>this</a> <a href='http://ignore.com/four'>ignore me!</a></p>"
                     :candidates [{:value "http://example.com/five" :type :landing-page-url}]})
          "Article landing page from known domain can be extracted from link. Non-matching domains ignored."))))

(deftest ^:unit html-with-duplicates
  (testing "HTML that contains a DOI in the link and in the text returns one candidate per match type. This will later be de-duped in event-data-percolator.action-test/match-candidates-deupe ."
    (let [html "<a href='https://doi.org/10.5555/12345678'>10.5555/12345678</a>"
          result (html/process-html-content-observation
                   (assoc util/mock-context :domain-set #{"example.com" "doi.org"})
                   {:type "html" :input-content html})]
          
      (is (= result {:type "html"
                     :input-content html
                     :canonical-url nil
                     :candidates [{:type :plain-doi :value "10.5555/12345678"}
                                  {:type :doi-url :value "https://doi.org/10.5555/12345678"}]})

          "Three different kinds of candidates retrieved when DOI is linked and in text."))))

(def rss-html
  "A webpage with a number of RSS feeds"
"<html>
<link href='/site-relative.xml' rel='feed' type='application/atom+xml'/>
<link href='page-relative.xml' rel='feed' type='application/atom+xml' />
<link href='https://www.crossref.org/index.xml' rel='feed' type='application/atom+xml' title='Crossref Feed' />
<link href='https://www.crossref.org/blog/index.xml' rel='alternate' type='application/atom+xml' title='Crossref Blog Feed' />
<link rel='service.post' type='application/atom+xml' title='russlings - Atom' href='https://www.blogger.com/feeds/10966011/posts/default' />
<link rel='alternate' type='application/rss+xml' title='The SkeptVet &raquo; Feed' href='http://skeptvet.com/Blog/feed/' />
<link rel='alternate' type='application/rss+xml' title='The SkeptVet &raquo; Comments Feed' href='http://skeptvet.com/Blog/comments/feed/' />
<link rel='alternate' type='application/rss+xml' title='The SkeptVet &raquo; Latest Integrative Nonsense from the Integrative Veterinary Care Journal- Spring 2017 Comments Feed' href='http://skeptvet.com/Blog/2017/04/latest-integrative-nonsense-from-the-integrative-veterinary-care-journal-spring-2017/feed/' />
<link rel='alternate' type='application/rss+xml' title='Companion Animal Psychology - RSS' href='http://www.companionanimalpsychology.com/feeds/posts/default?alt=rss' />
<link rel='service.post' type='application/atom+xml' title='Companion Animal Psychology - Atom' href='https://www.blogger.com/feeds/4990755601078984403/posts/default' />
</html>")

(deftest ^:unit newsfeed-detection
  (testing "Newsfeeds links are identified and extracted from HTML. Makes relative URIs absolute."
    (is (= (html/newsfeed-links-from-html rss-html "http://www.example.com/my-blog/page")
           #{{:rel "alternate", :href "http://www.companionanimalpsychology.com/feeds/posts/default?alt=rss"}
             {:rel "alternate", :href "http://skeptvet.com/Blog/2017/04/latest-integrative-nonsense-from-the-integrative-veterinary-care-journal-spring-2017/feed/"}
             {:rel "alternate", :href "http://skeptvet.com/Blog/comments/feed/"}
             {:rel "alternate", :href "http://skeptvet.com/Blog/feed/"}
             {:rel "feed", :href "http://www.example.com/site-relative.xml"}
             {:rel "feed", :href "http://www.example.com/my-blog/page-relative.xml"}
             {:rel "feed", :href "https://www.crossref.org/index.xml"}
             {:rel "alternate", :href "https://www.crossref.org/blog/index.xml"}
             {:rel "service.post", :href "https://www.blogger.com/feeds/10966011/posts/default"}
             {:rel "service.post", :href "https://www.blogger.com/feeds/4990755601078984403/posts/default"}}))))


(def multiple-canonical "<html><head><link rel=\"canonical\" href=\"https://www.example.com/i-am-canonical-url\" /><link rel=\"canonical\" href=\"https://www.example.com/no-i-am-canonical\" /></head></html>")
(def single-canonical "<html><head><link rel=\"canonical\" href=\"https://www.example.com/i-am-canonical-url\" /></head></html>")
(def no-canonical "<html><head></head>hello</html>")


(deftest ^:unit canonical-url-detection
  (testing "If an HTML page has a canonical URL, it should be included in the observations"
    
    (is (= (html/canonical-link-from-html no-canonical) nil)
       "No canonical link, no result")

    (is (= (html/canonical-link-from-html single-canonical) "https://www.example.com/i-am-canonical-url")
        "One canonical link, correct result")

    (is (= (html/canonical-link-from-html multiple-canonical) nil)
      "Multiple canonical links are ambuguous, should result in no result"))

  (testing "Observation should include canonical URL if supplied."
    (let [result (html/process-html-content-observation
                   util/mock-context
                   {:type "html" :input-content single-canonical})]

      (is (= (:canonical-url result) "https://www.example.com/i-am-canonical-url")))))
      
