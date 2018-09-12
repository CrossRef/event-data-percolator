(ns event-data-percolator.observation-types.content-url-test
  "Tests for content-url. This defers to other types for the content, there are individual tests for those."
  (:require [clojure.test :refer :all]
            [event-data-percolator.observation-types.content-url :as content-url]
            [event-data-percolator.util.web :as web]
            [org.httpkit.fake :as fake]
            [event-data-percolator.test-util :as util]))



(deftest ^:unit process-content-url-observation-1
  (testing "process-content-url-observation should set error when URL isn't allowed because it's empty and therefore doesn't match the domain."
    (let [result (content-url/process-content-url-observation
          util/mock-context
          {:input-url nil})]
      (is (:error result)))))

(deftest ^:unit process-content-url-observation-2
  (testing "process-content-url-observation should set error when URL can't be retrieved"
    (with-redefs [web/allowed? (constantly true)]
      (fake/with-fake-http ["http://www.zombo.com/nope" {:status 404}]
        (let [result (content-url/process-content-url-observation
                        util/mock-context
                        {:input-url "http://www.zombo.com/nope"})]
          (is (:error result)))))))

(deftest ^:unit process-content-url-observation-3
  (testing "process-content-url-observation not visit landing page domains"
    (with-redefs [web/allowed? (constantly true)]
    ; Assert that no web call is made.
      (fake/with-fake-http []
        (let [result (content-url/process-content-url-observation
                       util/mock-context
                       {:input-url "http://psychoceramics.labs.crossref.org/this/page"})]
          
          (is (= (:error result) :skipped-domain) "Results in :skipped-domain error")
          (is (nil? (:input-content result)) "No content is returned."))))))


(deftest ^:unit process-content-url-observation-4
  (testing "process-content-url-observation should set candidates on match where there are matches"
    (with-redefs [web/allowed? (constantly true)]    
      (fake/with-fake-http ["http://mywebsite.com/abc" "Webpage content 10.5555/12345678"]
        (let [result (content-url/process-content-url-observation
                      util/mock-context
         {:input-url "http://mywebsite.com/abc"})]
          (is (nil? (:error result)))

          ; Simplest possible thing that returns candidates (actually passed all the way through to plain-text).
          (is (= result {:input-url "http://mywebsite.com/abc"
                         :final-url "http://mywebsite.com/abc"
                         :input-content "Webpage content 10.5555/12345678"
                         :canonical-url nil
                         :candidates [{:value "10.5555/12345678", :type :plain-doi}]})))))))

(deftest ^:unit robots-exclusion
  (testing "Fetch usually respects robots.txt Disallow"
    (with-redefs [web/allowed? (constantly false)]
        (let [result (content-url/process-content-url-observation
                       util/mock-context
                       {:input-url "http://mywebsite.com/abc"})]

          ; No candidates should be matched because of robots exclusion.
          (is (= result {:input-url "http://mywebsite.com/abc"
                         :error :failed-fetch-url})
            "No matches should be made when robots prohibits.")))))


(deftest ^:unit robots-allow
  (testing "Fetch usually respects robots.txt Allow"
    (with-redefs [web/allowed? (constantly true)]
    (fake/with-fake-http ["http://mywebsite.com/abc" "Allowed content 10.5555/12345678"]

      (let [result (content-url/process-content-url-observation
                     util/mock-context
                     {:input-url "http://mywebsite.com/abc"})]

        (is (= result {:input-url "http://mywebsite.com/abc"
                       :final-url "http://mywebsite.com/abc"
                       :input-content "Allowed content 10.5555/12345678"
                       :canonical-url nil
                       :candidates [{:value "10.5555/12345678", :type :plain-doi}]})
            "Matches should be made when robots allowed request."))))))

(deftest ^:unit robots-exclusion-ignore
  (testing "If :ignore-robots is true then ignore the robots Disallow exclusions"
    (fake/with-fake-http ["http://mywebsite.com/abc" "Disallow robots content 10.5555/12345678"]
    (with-redefs [web/allowed? (constantly false)]
      (let [result (content-url/process-content-url-observation
                     util/mock-context
                     {:input-url "http://mywebsite.com/abc"
                      :ignore-robots true})]

        (is (= result {:input-url "http://mywebsite.com/abc"
                       :final-url "http://mywebsite.com/abc"
                       :input-content "Disallow robots content 10.5555/12345678"
                       :ignore-robots true
                       :canonical-url nil
                       :candidates [{:value "10.5555/12345678", :type :plain-doi}]})
          "Matches should be made even when robots prohibits."))))))

(deftest ^:unit robots-exclusion-ignore-allow
  (testing "If :ignore-robots is true then ignore Allow in the robots"
    (with-redefs [web/allowed? (constantly true)]
    (fake/with-fake-http ["http://mywebsite.com/abc" "Allow robots content 10.5555/12345678"]
      (let [result (content-url/process-content-url-observation
                     util/mock-context
                     {:input-url "http://mywebsite.com/abc"
                      :ignore-robots true})]

        (is (= result {:input-url "http://mywebsite.com/abc"
                       :final-url "http://mywebsite.com/abc"
                       :ignore-robots true
                       :canonical-url nil
                       :input-content "Allow robots content 10.5555/12345678"
                       :candidates [{:value "10.5555/12345678", :type :plain-doi}]})
            "Matches should be made when robots.txt would allow anyway."))))))


(def content-with-canonical "<html><head><link rel=\"canonical\" href=\"https://www.example.com/i-am-canonical-url\" /></head>look at this doi 10.5555/12345678</html>")

(deftest ^:unit canonical-url-carried-through
  (testing "When there's a canonical URL identified in the event-data-percolator.observation-types.html namespace it should be carried throgh "
    (with-redefs [web/allowed? (constantly true)]
      (fake/with-fake-http ["http://www.example-website.com/1234"
                             content-with-canonical]
        
        (let [result (content-url/process-content-url-observation
                      util/mock-context
                      {:input-url "http://www.example-website.com/1234"})]
          (is (nil? (:error result)))

          ; Simplest possible thing that returns candidates (actually passed all the way through to plain-text).
          (is (= result {:input-url "http://www.example-website.com/1234"
                         :final-url "http://www.example-website.com/1234"
                         :input-content content-with-canonical
                         :canonical-url "https://www.example.com/i-am-canonical-url"
                         :candidates [{:value "10.5555/12345678", :type :plain-doi}]})))))))

(deftest ^:unit final-url-recorded
  (testing "When there is a sequence of redirects, the final URL should be included in the observation."
    (with-redefs [web/allowed? (constantly true)]
      (fake/with-fake-http ["http://feedproxy.google.com/~r/thebreakthrough/~3/Kr57lBR2w1w/stuck-in-the-s-curve"
                            {:status 301 :headers {:location "http://interrim.com/test"}}
                            
                            ; An extra redirect to ensure that we follow more than one hop.
                            "http://interrim.com/test"
                            {:status 302 :headers {:location "https://thebreakthrough.org/index.php/voices/stuck-in-the-s-curve?utm_source=feedburner&utm_medium=feed&utm_campaign=Feed%3A+thebreakthrough+%28The+Breakthrough+Institute+Full+Site+RSS%29"}}

                            "https://thebreakthrough.org/index.php/voices/stuck-in-the-s-curve?utm_source=feedburner&utm_medium=feed&utm_campaign=Feed%3A+thebreakthrough+%28The+Breakthrough+Institute+Full+Site+RSS%29"
                            {:status 200 :body "<html>hello</html>"}]
                            
        (let [result (content-url/process-content-url-observation
                      util/mock-context
                      {:input-url "http://feedproxy.google.com/~r/thebreakthrough/~3/Kr57lBR2w1w/stuck-in-the-s-curve"})]
          (is (nil? (:error result)))

          ; Simplest possible thing that returns candidates (actually passed all the way through to plain-text).
          (is (= (:input-url result)
                 "http://feedproxy.google.com/~r/thebreakthrough/~3/Kr57lBR2w1w/stuck-in-the-s-curve")
              "Input URL should be preserved")

          (is (= (:input-content "<html>hello</html>"))
                 "Content of final redirect should be returned.")

          (is (= (:final-url result)
                 "https://thebreakthrough.org/index.php/voices/stuck-in-the-s-curve?utm_source=feedburner&utm_medium=feed&utm_campaign=Feed%3A+thebreakthrough+%28The+Breakthrough+Institute+Full+Site+RSS%29")
              "Final URL of redirect chain should be reported."))))))

