(ns event-data-percolator.matchers.landing-page-url-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [event-data-percolator.util.doi :as doi]
            [org.httpkit.fake :as fake]
            [event-data-percolator.matchers.landing-page-url :as landing-page-url]
            [event-data-common.landing-page-domain :as landing-page-domain]
            [event-data-percolator.test-util :as util]
            [clojure.java.io :refer [reader resource]]))


; Tests for individual methods

(def url-params-inputs
  "DOI embedded in a publisher URL as a query string."
  ["http://psychoceramics.labs.crossref.org/plosone/article?id=10.5555/12345678"
   "http://psychoceramics.labs.crossref.org/DOIx.php?id=10.5555/12345678"])

(deftest ^:component try-from-get-params
  (testing "try-from-get-params can find valid DOIs"
      (with-redefs [doi/validate-cached (fn [_ doi] (#{"10.5555/12345678"} doi))]
      (doseq [input url-params-inputs]
        (let [result (landing-page-url/try-from-get-params util/mock-context input)]
          (is (= result
                 {:match "https://doi.org/10.5555/12345678"
                  :method :get-params
                  :verification :confirmed-domain-prefix})))))))

  (testing "try-from-get-params nil if DOI doesn't exist"
    (with-redefs [doi/validate-cached (fn [_ doi] nil)]
      (doseq [input url-params-inputs]
        (let [result (landing-page-url/try-from-get-params util/mock-context input)]
          (is (= result nil))))))

(deftest ^:component try-doi-from-url-text-extra
  (testing "URL with embedded DOI plus text."
    (with-redefs [doi/validate-cached (fn [_ doi] (#{"10.5235/219174411798862578"} doi))]
      (is (= (landing-page-url/try-doi-from-url-text util/mock-context
               "http://www.nomos-elibrary.de/10.5235/219174411798862578/criminal-law-issues-in-the-case-law-of-the-european-court-of-justice-a-general-overview-jahrgang-1-2011-heft-2")
             {:match "https://doi.org/10.5235/219174411798862578"
              :method :url-text
              :verification :confirmed-domain-prefix})))))

(deftest ^:component try-doi-from-url-text-not-exist
  (testing "URL with embedded DOI plus text BUT the DOI doesn't exist!"
    (with-redefs [doi/validate-cached (fn [_ doi] nil)]
      (is (= (landing-page-url/try-doi-from-url-text util/mock-context
              "http://www.nomos-elibrary.de/10.5235/219174411798862578/criminal-law-issues-in-the-case-law-of-the-european-court-of-justice-a-general-overview-jahrgang-1-2011-heft-2")
             nil)))))

(deftest ^:component try-doi-from-url-text-jsessionid
  (testing "URL with SICI DOI plus jsessionid"
    ; stuff that we may look for after the end of the legit DOI
    (with-redefs [doi/validate-cached (fn [_ doi] 
      (#{"10.1002/1521-3951(200009)221:1<453::AID-PSSB453>3.0.CO;2-Q"} doi))]
    
      (is (= (landing-page-url/try-doi-from-url-text
               util/mock-context
               "http://onlinelibrary.wiley.com/doi/10.1002/1521-3951(200009)221:1<453::AID-PSSB453>3.0.CO;2-Q/abstract;jsessionid=FAD5B5661A7D092460BEEDA0D55204DF.f02t01")
             
             {:match "https://doi.org/10.1002/1521-3951(200009)221:1<453::aid-pssb453>3.0.co;2-q"
              :method :url-text
              :verification :confirmed-domain-prefix})))))

(deftest ^:component try-doi-from-url-text-slash-extras
  (testing "URL with embedded DOI and slash-delimited extras."
    (with-redefs [doi/validate-cached (fn [_ doi]
                                        ; Lookup DOI with some unwanted stuff on the end, map to the correct one.
                                        ({"10.7815/ijorcs.21.2011.012/arul-anitha"
                                          "10.7815/ijorcs.21.2011.012"} doi))]
      (is (= (landing-page-url/try-doi-from-url-text
              util/mock-context
              "http://www.ijorcs.org/manuscript/id/12/doi:10.7815/ijorcs.21.2011.012/arul-anitha/network-security-using-linux-intrusion-detection-system")
             {:match "https://doi.org/10.7815/ijorcs.21.2011.012"
              :method :url-text
              :verification :confirmed-domain-prefix})))))

(deftest ^:component try-doi-from-url-text-url-escape
  (testing "URL with embedded URL-escaped DOI."
    (with-redefs [doi/validate-cached (fn [_ doi]
                                        ({"10.1007%2Fs00423-015-1364-1"
                                          "10.1007/s00423-015-1364-1"} doi))]
      (is (= (landing-page-url/try-doi-from-url-text
                util/mock-context
                "http://link.springer.com/article/10.1007%2Fs00423-015-1364-1")
             {:match "https://doi.org/10.1007/s00423-015-1364-1"
              :method :url-text
              :verification :confirmed-domain-prefix})))))

(deftest ^:component try-pii-from-url-text-pii
  (testing "Extant PII can be extracted and matched"
    (with-redefs [doi/validate-cached (fn [_ doi] (#{"10.1016/s0169-5347(01)02380-1"} doi))]
      (fake/with-fake-http ["https://api.crossref.org/v1/works"
                            {:status 200
                             :headers {:content-type "application/json"}
                             :body (json/write-str {:message {:items [{:DOI "10.1016/s0169-5347(01)02380-1"}]}})}]
        (is (= (landing-page-url/try-pii-from-url-text util/mock-context "http://api.elsevier.com/content/article/PII:S0169534701023801?httpAccept=text/plain")
               {:match "https://doi.org/10.1016/s0169-5347(01)02380-1"
                :method :pii
                :verification :lookup}))))))

(deftest ^:component doi-from-meta-tags-citation-doi-1
  (testing "DOI can be fetched from meta tag: citation_doi"
    (with-redefs [doi/validate-cached
                  (fn [_ doi]
                    ({"https://doi.org/10.1007/s10461-013-0685-8"
      "10.1007/s10461-013-0685-8"} doi))]
      (is (= (landing-page-url/doi-from-meta-tags
               util/mock-context
               "http://www.ncbi.nlm.nih.gov/pmc/articles/PMC4852986/?report=classic"
               (slurp "resources/PMC4852986"))
            
              [:confirmed-domain-prefix "10.1007/s10461-013-0685-8"])))))

(deftest ^:component doi-from-meta-tags-citation-doi-2
  ; NB pubsonline.informs.org sends different HTML to different agents (Firefox vs Curl).
  ; So this data was captured by Chrome. 
  ; Nonetheless, a good example.
  (testing "DOI can be fetched from meta tag: dc.Identifier"
    (with-redefs [doi/validate-cached (fn [_ doi] ({"https://doi.org/10.1287/mnsc.2016.2427" "10.1287/mnsc.2016.2427"} doi))]
      (is (= (landing-page-url/doi-from-meta-tags
               util/mock-context
               "http://pubsonline.informs.org/doi/abs/10.1287/mnsc.2016.2427"
               (slurp "resources/mnsc.2016.2427"))
              
              [:confirmed-domain-prefix "10.1287/mnsc.2016.2427"])))))

(deftest ^:component doi-from-meta-tags-dc-identifier
  (testing "DOI can be fetched from meta tag: DC.identifier (different case)"
    (with-redefs [doi/validate-cached
                 (fn [_ doi]
                   ({"https://doi.org/10.5555/m9.figshare.3423371.v1" "10.5555/m9.figshare.3423371.v1"} doi))]
      
        (is (= (landing-page-url/doi-from-meta-tags
                 util/mock-context
                 "https://psychoceramics.labs.crossref.org/articles/A_Modeler_s_Tale/3423371/1"
                 (slurp "resources/A_Modeler_s_Tale"))
                [:confirmed-domain-prefix "10.5555/m9.figshare.3423371.v1"])))))

(deftest ^:component doi-from-meta-tags-dc-identifier-doi
  (with-redefs [doi/validate-cached (fn [ctx doi]
                                      ({"https://doi.org/10.3402/ijch.v71i0.18594" "10.3402/ijch.v71i0.18594"} doi))]
    (testing "DOI can be fetched from meta tag: DC.Identifier.DOI"
      (is (= (landing-page-url/doi-from-meta-tags
               util/mock-context
               "http://www.circumpolarhealthjournal.net/index.php/ijch/article/view/18594/html"
               (slurp "resources/18594"))
              [:confirmed-domain-prefix "10.3402/ijch.v71i0.18594"])))))

(deftest ^:component doi-from-meta-tags-prism-url
  (testing "DOI can be fetched from meta tag: prism.url"
    ; Block web access.
    (fake/with-fake-http []
      (with-redefs [landing-page-url/check-url-for-doi (constantly :checked-url-exact)
                    doi/validate-cached (fn [ctx doi]
                                          ({"https://doi.org/10.1186/s13054-016-1322-5"
                                             "10.1186/s13054-016-1322-5"} doi))]
        (is (= (landing-page-url/doi-from-meta-tags
                 util/mock-context
                 "http://ccforum.biomedcentral.com/articles/10.1186/s13054-016-1322-5"
                 (slurp "resources/s13054-016-1322-5"))
               [:checked-url-exact "10.1186/s13054-016-1322-5"]))))))

(deftest ^:component try-fetched-page-metadata
  (testing "DOI can be fetched from meta tag: citation_doi"
    (with-redefs [doi/validate-cached (fn [ctx doi]
                                        ({"https://doi.org/10.1093/jnci/djw160"
                                           "10.1093/jnci/djw160"} doi))]
      (is (= (landing-page-url/doi-from-meta-tags util/mock-context "http://jnci.oxfordjournals.org/content/108/6/djw160.full" (slurp "resources/djw160.full"))
              [:confirmed-domain-prefix "10.1093/jnci/djw160"])))))
  
; Regression test for https://github.com/CrossRef/event-data-percolator/issues/29
(deftest ^:component url-text-with-qmark-in-query-param
  (testing "try-doi-from-url-text should extract DOI from a URL string that contains a query parameter"
    (with-redefs [doi/validate-cached 
                  (fn [ctx doi]
                    ({"10.1007/s11906-017-0700-y?platform=hootsuite" "10.1007/s11906-017-0700-y"} doi))]
      (let [result (landing-page-url/try-doi-from-url-text util/mock-context "http://link.springer.com/article/10.1007/s11906-017-0700-y?platform=hootsuite")]
        (is (= result {:match "https://doi.org/10.1007/s11906-017-0700-y"
                       :method :url-text
                       :verification :confirmed-domain-prefix})
            "Question mark character should not be included in DOI")))))

(deftest ^:unit url-equality
  (testing "url-equality compares URLs and returns the type of equality"
    (is (nil? (landing-page-url/url-equality nil nil)) "Two nil URLs shouldn't be considered equal")
    (is (nil? (landing-page-url/url-equality nil "http://example.com")) "A nil URLs shouldn't be considered equal to anything.")
    (is (nil? (landing-page-url/url-equality "http://example.com" nil)) "A nil URLs shouldn't be considered equal to anything.")
    (is (#{:exact} (landing-page-url/url-equality "http://example.com" "http://example.com")) "Simple full equality.")
    (is (#{:basic} (landing-page-url/url-equality "http://example.com" "https://example.com")) "HTTPS mismatching HTTP is 'basic' equality.")
    (is (#{:basic} (landing-page-url/url-equality "https://example.com" "http://example.com")) "HTTPS mismatching HTTP is 'basic' equality.")

    ; Some real URLs to check that they compare to each other exactly.
    (doseq [url [;"http://scholar.dkyobobook.co.kr/searchDetail.laf?barcode=4010025052313"
                 "http://dl.lib.mrt.ac.lk/bitstream/handle/123/13258/7.pdf"
                 "http://Insights.ovid.com/crossref?an=00000539-201705000-00065"
                 "http://Platform.almanhal.com/CrossRef/Preview/?ID=2-17559"
                 "http://links.jstor.org/sici?sici=0018-098X%28194210%2F12%2911%3A4%3C365%3ATFTNOH%3E2.0.CO%3B2-E&origin=crossref"
                 "http://stacks.iop.org/0004-637X/844/i=1/a=13?key=crossref.fe7fe13734d4fef613a701421d972882"
                 "http://www.jstor.org/stable/3179886?origin=crossref"
                 ; This isn't a valid URI according to Java. It is a valid URL, and it is something we find in the wild.
                 "http://www.informaworld.com/openurl?genre=article&doi=10.1300/J148V01N03_03&magic=crossref||D404A21C5BB053405B1A640AFFD44AE3"
                 "http://www.schweizerbart.de/papers/metz/detail/15/54914/Objective_mesoscale_analyses_in_complex_terrain_ap?af=crossref"]]
                 
      (is (#{:exact} (landing-page-url/url-equality url url)) "Example real URLs should be self-equal."))

    ; Some near-misses that should be considered 'basic' match.
    (doseq [[url-a url-b]
             ; Query param different
            [["http://scholar.dkyobobook.co.kr/searchDetail.laf?barcode=4010025052313"
              "http://scholar.dkyobobook.co.kr/searchDetail.laf?barcode=99999"]

             ; HTTPS
             ["https://dl.lib.mrt.ac.lk/bitstream/handle/123/13258/7.pdf"
              "http://dl.lib.mrt.ac.lk/bitstream/handle/123/13258/7.pdf"]

             ; Query param missing
             ["http://Insights.ovid.com/crossref?an=00000539-201705000-00065"
              "http://Insights.ovid.com/crossref?"]

             ; Case different in host. This is actually a 'true'.
             ["http://platform.almanhal.com/CrossRef/Preview/?ID=2-17559"
              "http://Platform.almanhal.com/CrossRef/Preview/?ID=2-17559"]

             ; Case different in path. 
             ["http://platform.almanhal.com/CrossRef/Preview/?ID=2-17559"
              "http://Platform.almanhal.com/CROSSREF/Preview/?ID=2-17559"]

             ; Query param 'origin=crossref' removed.
             ["http://links.jstor.org/sici?sici=0018-098X%28194210%2F12%2911%3A4%3C365%3ATFTNOH%3E2.0.CO%3B2-E&origin=crossref"
              "http://links.jstor.org/sici?sici=0018-098X%28194210%2F12%2911%3A4%3C365%3ATFTNOH%3E2.0.CO%3B2-E"]

             ; Missing query param
             ["http://stacks.iop.org/0004-637X/844/i=1/a=13?key=crossref.fe7fe13734d4fef613a701421d972882"
              "http://stacks.iop.org/0004-637X/844/i=1/a=13"]

             ; origin=crossref removed
             ["http://www.jstor.org/stable/3179886?origin=crossref"
              "http://www.jstor.org/stable/3179886"]

             ; Manipulation of a non-standard url param.
             ["http://www.informaworld.com/openurl?genre=article&doi=10.1300/J148V01N03_03&magic=crossref||D404A21C5BB053405B1A640AFFD44AE3"
              "http://www.informaworld.com/openurl?genre=article&doi=10.1300/J148V01N03_03&magic=D404A21C5BB053405B1A640AFFD44AE3"]

             ; 'af=crossref' query param removed
             ["http://www.schweizerbart.de/papers/metz/detail/15/54914/Objective_mesoscale_analyses_in_complex_terrain_ap?af=crossref"
              "http://www.schweizerbart.de/papers/metz/detail/15/54914/Objective_mesoscale_analyses_in_complex_terrain_ap"]]]

      (is (#{:basic :exact} (landing-page-url/url-equality url-a url-b)) "Example almost URL matches should be :basic or true"))))

