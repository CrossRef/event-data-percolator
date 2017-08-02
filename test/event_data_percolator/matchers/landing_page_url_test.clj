(ns event-data-percolator.matchers.landing-page-url-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [org.httpkit.fake :as fake]
            [event-data-percolator.matchers.landing-page-url :as landing-page-url]
            [event-data-percolator.test-util :as util]))



; Tests for individual methods

(def url-params-inputs
  "DOI embedded in a publisher URL as a query string."
  ["http://journals.plos.org/plosone/article?id=10.5555/12345678"
   "http://synapse.koreamed.org/DOIx.php?id=10.5555/12345678"])

(deftest ^:component try-from-get-params
  (testing "try-from-get-params can find valid DOIs"
    (fake/with-fake-http ["https://doi.org/api/handles/10.5555/12345678" (util/doi-ok "10.5555/12345678")]
      (doseq [input url-params-inputs]
        (let [result (landing-page-url/try-from-get-params util/mock-context input)]
          (is (= result "https://doi.org/10.5555/12345678"))))))

  (testing "try-from-get-params nil if DOI doesn't exist"
    (fake/with-fake-http ["https://doi.org/api/handles/10.5555/12345678" (util/doi-not-found)]
      (doseq [input url-params-inputs]
        (let [result (landing-page-url/try-from-get-params util/mock-context input)]
          (is (= result nil)))))))

(deftest ^:component try-doi-from-url-text-extra
  (testing "URL with embedded DOI plus text."
    (fake/with-fake-http ["https://doi.org/api/handles/10.5235/219174411798862578" (util/doi-ok "10.5235/219174411798862578")
                          #"https://doi.org/api/handles/10.5235/219174411798862578/.*" (util/doi-not-found)]
      (is (= (landing-page-url/try-doi-from-url-text util/mock-context "http://www.nomos-elibrary.de/10.5235/219174411798862578/criminal-law-issues-in-the-case-law-of-the-european-court-of-justice-a-general-overview-jahrgang-1-2011-heft-2")
             "https://doi.org/10.5235/219174411798862578")))))

(deftest ^:component try-doi-from-url-text-not-exist
  (testing "URL with embedded DOI plus text BUT the DOI doesn't exist!"
    (fake/with-fake-http ["https://doi.org/api/handles/10.5235/219174411798862XXX" (util/doi-not-found)
                          #"https://doi.org/api/handles/10.5235/.*" (util/doi-not-found)
                          "https://doi.org/api/handles/10.5235/219174411798862XXX/criminal-law-issues-in-the-case-law-of-the-european-court-of-justice-a-general-overview-jahrgang-1-2011-heft-2" (util/doi-not-found)]
      (is (= (landing-page-url/try-doi-from-url-text util/mock-context "http://www.nomos-elibrary.de/10.5235/219174411798862578/criminal-law-issues-in-the-case-law-of-the-european-court-of-justice-a-general-overview-jahrgang-1-2011-heft-2")
             nil)))))

(deftest ^:component try-doi-from-url-text-jsessionid
  (testing "URL with SICI DOI plus jsessionid"
    (fake/with-fake-http ["https://doi.org/api/handles/10.1002/1521-3951(200009)221:1<453::AID-PSSB453>3.0.CO;2-Q" (util/doi-ok "10.1002/1521-3951(200009)221:1<453::AID-PSSB453>3.0.CO;2-Q")
                          ; stuff that we may look for after the end of the legit DOI
                          ; NB regex escaped
                          #"https://doi.org/api/handles/10.1002/1521-3951\(200009\)221:1<453::AID-PSSB453>3.0.CO;2-Q/.*" (util/doi-not-found)]
      (is (= (landing-page-url/try-doi-from-url-text util/mock-context "http://onlinelibrary.wiley.com/doi/10.1002/1521-3951(200009)221:1<453::AID-PSSB453>3.0.CO;2-Q/abstract;jsessionid=FAD5B5661A7D092460BEEDA0D55204DF.f02t01")
             "https://doi.org/10.1002/1521-3951(200009)221:1<453::aid-pssb453>3.0.co;2-q")))))

(deftest ^:component try-doi-from-url-text-slash-extras
  (testing "URL with embedded DOI and slash-delimited extras."
    (fake/with-fake-http [#"https://doi.org/api/handles/10.7815/ijorcs.21.2011.012/arul-.*" (util/doi-not-found)
                           
                           
                           "https://doi.org/api/handles/10.7815%2Fijorcs.21.2011.012%2Farul-anitha" (util/doi-not-found)
                           "https://doi.org/api/handles/10.7815/ijorcs.21.2011.012" (util/doi-ok "10.7815/ijorcs.21.2011.012")]
      (is (= (landing-page-url/try-doi-from-url-text util/mock-context "http://www.ijorcs.org/manuscript/id/12/doi:10.7815/ijorcs.21.2011.012/arul-anitha/network-security-using-linux-intrusion-detection-system")
             "https://doi.org/10.7815/ijorcs.21.2011.012")))))

(deftest ^:component try-doi-from-url-text-url-escape
  (testing "URL with embedded URL-escaped DOI."
    (fake/with-fake-http ["https://doi.org/api/handles/10.1007%2Fs00423-015-1364-1" (util/doi-ok "10.1007/s00423-015-1364-1")
                          "https://doi.org/api/handles/10.1007/s00423-015-1364-1" (util/doi-ok "10.1007/s00423-015-1364-1")]
      (is (= (landing-page-url/try-doi-from-url-text util/mock-context "http://link.springer.com/article/10.1007%2Fs00423-015-1364-1")
             "https://doi.org/10.1007/s00423-015-1364-1")))))

(deftest ^:component try-pii-from-url-text-pii
  (testing "Extant PII can be extracted and matched"
    (fake/with-fake-http ["https://api.crossref.org/v1/works"
                          {:status 200
                           :headers {:content-type "application/json"}
                           :body (json/write-str {:message {:items [{:DOI "10.1016/s0169-5347(01)02380-1"}]}})}

                           "https://doi.org/api/handles/10.1016/s0169-5347(01)02380-1"
                           (util/doi-ok "10.1016/s0169-5347(01)02380-1")]
      (is (= (landing-page-url/try-pii-from-url-text util/mock-context "http://api.elsevier.com/content/article/PII:S0169534701023801?httpAccept=text/plain")
             "https://doi.org/10.1016/s0169-5347(01)02380-1")))))

(deftest ^:component try-fetched-page-metadata-citation-doi
  (testing "DOI can be fetched from meta tag: citation_doi"
    (fake/with-fake-http ["http://www.ncbi.nlm.nih.gov/pmc/articles/PMC4852986/?report=classic" (slurp "resources/PMC4852986")
                          "https://doi.org/api/handles/10.1007/s10461-013-0685-8" (util/doi-ok "10.1007/s10461-013-0685-8")]
      (is (= (landing-page-url/try-fetched-page-metadata util/mock-context "http://www.ncbi.nlm.nih.gov/pmc/articles/PMC4852986/?report=classic")
              "https://doi.org/10.1007/s10461-013-0685-8"))))
  
  ; NB pubsonline.informs.org sends different HTML to different agents (Firefox vs Curl).
  ; So this data was captured by Chrome. 
  ; Nonetheless, a good example.
  (testing "DOI can be fetched from meta tag: dc.Identifier"
    (fake/with-fake-http ["http://pubsonline.informs.org/doi/abs/10.1287/mnsc.2016.2427" (slurp "resources/mnsc.2016.2427")
                          ; "https://doi.org/mnsc.2016.2427" (util/doi-ok "https://www.doi.org/mnsc.2016.2427")
                          "https://doi.org/api/handles/10.1287/mnsc.2016.2427" (util/doi-ok "10.1287/mnsc.2016.2427")

                          
                          ; Misidentified short-dois
                          #"https://doi.org/api/handles/.*" (util/doi-not-found)
                          ]
      (is (= (landing-page-url/try-fetched-page-metadata util/mock-context "http://pubsonline.informs.org/doi/abs/10.1287/mnsc.2016.2427")
              "https://doi.org/10.1287/mnsc.2016.2427")))))

(deftest ^:component try-fetched-page-metadat-dc-identifier
  (testing "DOI can be fetched from meta tag: DC.identifier (different case)"
    (fake/with-fake-http ["https://figshare.com/articles/A_Modeler_s_Tale/3423371/1" (slurp "resources/A_Modeler_s_Tale")
                          "https://doi.org/api/handles/10.6084/m9.figshare.3423371.v1" (util/doi-ok "10.6084/m9.figshare.3423371.v1")]
      (is (= (landing-page-url/try-fetched-page-metadata util/mock-context "https://figshare.com/articles/A_Modeler_s_Tale/3423371/1")
              "https://doi.org/10.6084/m9.figshare.3423371.v1")))))

(deftest ^:component try-fetched-page-metadata-dc-identifier-doi
  (testing "DOI can be fetched from meta tag: DC.Identifier.DOI"
    (fake/with-fake-http ["http://www.circumpolarhealthjournal.net/index.php/ijch/article/view/18594/html" (slurp "resources/18594")
                          "https://doi.org/api/handles/10.3402/ijch.v71i0.18594" (util/doi-ok "10.3402/ijch.v71i0.18594")

                          ; Misidentified short-dois
                          #"https://doi.org/api/handles/.*" (util/doi-not-found)]
      (is (= (landing-page-url/try-fetched-page-metadata util/mock-context "http://www.circumpolarhealthjournal.net/index.php/ijch/article/view/18594/html")
              "https://doi.org/10.3402/ijch.v71i0.18594")))))

(deftest ^:component try-fetched-page-metadata-prism-url
  (testing "DOI can be fetched from meta tag: prism.url"
    (fake/with-fake-http ["http://ccforum.biomedcentral.com/articles/10.1186/s13054-016-1322-5" (slurp "resources/s13054-016-1322-5")
                          "https://doi.org/api/handles/10.1186/s13054-016-1322-5" (util/doi-ok "10.1186/s13054-016-1322-5")
                          ; Misidentified short-dois
                          #"https://doi.org/api/handles/.*" (util/doi-not-found)]
      (is (= (landing-page-url/try-fetched-page-metadata util/mock-context "http://ccforum.biomedcentral.com/articles/10.1186/s13054-016-1322-5")
              "https://doi.org/10.1186/s13054-016-1322-5")))))

(deftest ^:component try-fetched-page-metadata
  (testing "DOI can be fetched from meta tag: citation_doi"
    (fake/with-fake-http ["http://jnci.oxfordjournals.org/content/108/6/djw160.full" (slurp "resources/djw160.full")
                          "https://doi.org/api/handles/10.1093/jnci/djw160" (util/doi-ok "10.1093/jnci/djw160")]
      (is (= (landing-page-url/try-fetched-page-metadata util/mock-context "http://jnci.oxfordjournals.org/content/108/6/djw160.full")
              "https://doi.org/10.1093/jnci/djw160")))))
  
; Regression test for https://github.com/CrossRef/event-data-percolator/issues/29
(deftest ^:component qmark-in-query-param
  (testing "try-doi-from-url-text should extract DOI from a URL string that contains a query parameter"
    (fake/with-fake-http [; This first URL was getting called. Now shouldn't be, but left as an illustration.
                          "https://doi.org/api/handles/10.1007/s11906-017-0700-y?platform=hootsuite" (util/doi-ok "10.1007/s11906-017-0700-y")
                          "https://doi.org/api/handles/10.1007/s11906-017-0700-y" (util/doi-ok "10.1007/s11906-017-0700-y")]
      (let [result (landing-page-url/try-doi-from-url-text util/mock-context "http://link.springer.com/article/10.1007/s11906-017-0700-y?platform=hootsuite")]
        (is (= result "https://doi.org/10.1007/s11906-017-0700-y")
            "Question mark character should not be included in DOI")))))
