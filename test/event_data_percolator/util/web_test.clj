(ns event-data-percolator.util.web-test
  (:require [clojure.test :refer :all]
            [event-data-percolator.util.web :as web]
            [org.httpkit.fake :as fake]
            [event-data-percolator.test-util :as util]))

(deftest ^:unit final-redirect-url-recorded
  (testing "When a URL is redirected, the final URL should be included in the response."
    (fake/with-fake-http ["http://feedproxy.google.com/~r/thebreakthrough/~3/Kr57lBR2w1w/stuck-in-the-s-curve"
                          {:status 301 :headers {:location "http://interrim.com/test"}}
                          
                          ; An extra redirect to ensure that we follow more than one hop.
                          "http://interrim.com/test"
                          {:status 302 :headers {:location "https://thebreakthrough.org/index.php/voices/stuck-in-the-s-curve?utm_source=feedburner&utm_medium=feed&utm_campaign=Feed%3A+thebreakthrough+%28The+Breakthrough+Institute+Full+Site+RSS%29"}}

                          "https://thebreakthrough.org/index.php/voices/stuck-in-the-s-curve?utm_source=feedburner&utm_medium=feed&utm_campaign=Feed%3A+thebreakthrough+%28The+Breakthrough+Institute+Full+Site+RSS%29"
                          {:status 200 :body "<html>hello</html>"}]

      (let [result (web/fetch {} "http://feedproxy.google.com/~r/thebreakthrough/~3/Kr57lBR2w1w/stuck-in-the-s-curve")]
        (is (= (:status result) 200)
          "Final redirect status should be returned")

        (is (= (:body result) "<html>hello</html>")
          "Body of final hop should be returned")

        (is (= (:final-url result) "https://thebreakthrough.org/index.php/voices/stuck-in-the-s-curve?utm_source=feedburner&utm_medium=feed&utm_campaign=Feed%3A+thebreakthrough+%28The+Breakthrough+Institute+Full+Site+RSS%29")
          "URL of last hop should be recorded")))))

