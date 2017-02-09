(ns event-data-percolator.matchers.landing-page-url-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [org.httpkit.fake :as fake]
            [event-data-percolator.matchers.landing-page-url :as landing-page-url]))

(defn ok
  "Fake OK return from DOI proxy."
  [url]
  {:status 303 :headers {:location url}})

(defn not-found [] {:status 404})

; Tests for individual methods

(def url-params-inputs
  "DOI embedded in a publisher URL as a query string."
  ["http://journals.plos.org/plosone/article?id=10.5555/12345678"
   "http://synapse.koreamed.org/DOIx.php?id=10.5555/12345678"])

(deftest ^:unit try-from-get-params
  (testing "try-from-get-params can find valid DOIs"
    (fake/with-fake-http ["https://doi.org/10.5555/12345678" (ok "http://psychoceramics.labs.crossref.org/10.5555-12345678.html")]
      (doseq [input url-params-inputs]
        (let [result (landing-page-url/try-from-get-params input)]
          (is (= result "https://doi.org/10.5555/12345678"))))))

  (testing "try-from-get-params nil if DOI doesn't exist"
    (fake/with-fake-http ["https://doi.org/10.5555/12345678" (not-found)]
      (doseq [input url-params-inputs]
        (let [result (landing-page-url/try-from-get-params input)]
          (is (= result nil)))))))

(deftest ^:unit try-doi-from-url-text
  (testing "URL with embedded DOI plus text."
    (fake/with-fake-http ["https://doi.org/10.5235/219174411798862578" (ok "http://www.nomos-elibrary.de/index.php?doi=10.5235/219174411798862578")]
      (is (= (landing-page-url/try-doi-from-url-text "http://www.nomos-elibrary.de/10.5235/219174411798862578/criminal-law-issues-in-the-case-law-of-the-european-court-of-justice-a-general-overview-jahrgang-1-2011-heft-2")
             "https://doi.org/10.5235/219174411798862578"))))

  (testing "URL with embedded DOI plus text BUT the DOI doesn't exist!"
    (fake/with-fake-http ["https://doi.org/10.5235/219174411798862578" (not-found)
                          "https://doi.org/10.5235/219174411798862578/criminal-law-issues-in-the-case-law-of-the-european-court-of-justice-a-general-overview-jahrgang-1-2011-heft-2" (not-found)]
      (is (= (landing-page-url/try-doi-from-url-text "http://www.nomos-elibrary.de/10.5235/219174411798862578/criminal-law-issues-in-the-case-law-of-the-european-court-of-justice-a-general-overview-jahrgang-1-2011-heft-2")
             nil))))

  (testing "URL with SICI DOI plus jsessionid"
    (fake/with-fake-http ["https://doi.org/10.1002/1521-3951(200009)221:1<453::AID-PSSB453>3.0.CO;2-Q" (ok "http://doi.wiley.com/10.1002/1521-3951%28200009%29221%3A1%3C453%3A%3AAID-PSSB453%3E3.0.CO%3B2-Q")]
      (is (= (landing-page-url/try-doi-from-url-text "http://onlinelibrary.wiley.com/doi/10.1002/1521-3951(200009)221:1<453::AID-PSSB453>3.0.CO;2-Q/abstract;jsessionid=FAD5B5661A7D092460BEEDA0D55204DF.f02t01")
             "https://doi.org/10.1002/1521-3951(200009)221:1<453::aid-pssb453>3.0.co;2-q"))))

  (testing "URL with embedded DOI and slash-delimited extras."
    (fake/with-fake-http ["https://doi.org/10.7815/ijorcs.21.2011.012/arul-anitha" (not-found)
                          "https://doi.org/10.7815%2Fijorcs.21.2011.012%2Farul-anitha" (not-found)
                          "https://doi.org/10.7815/ijorcs.21.2011.012" (ok "http://www.ijorcs.org/manuscript/id/12/doi:10.7815/ijorcs.21.2011.012/arul-anitha/network-security-using-linux-intrusion-detection-system")]
      (is (= (landing-page-url/try-doi-from-url-text "http://www.ijorcs.org/manuscript/id/12/doi:10.7815/ijorcs.21.2011.012/arul-anitha/network-security-using-linux-intrusion-detection-system")
             "https://doi.org/10.7815/ijorcs.21.2011.012"))))

  (testing "URL with embedded URL-escaped DOI."
    (fake/with-fake-http ["https://doi.org/10.1007%2Fs00423-015-1364-1" (ok "http://link.springer.com/10.1007/s00423-015-1364-1")
                          "https://doi.org/10.1007/s00423-015-1364-1" (ok "http://link.springer.com/10.1007/s00423-015-1364-1")]
      (is (= (landing-page-url/try-doi-from-url-text "http://link.springer.com/article/10.1007%2Fs00423-015-1364-1")
             "https://doi.org/10.1007/s00423-015-1364-1")))))

(deftest ^:unit try-pii-from-url-text
  (testing "Extant PII can be extracted and matched"
    (fake/with-fake-http ["https://api.crossref.org/v1/works"
                          {:status 200
                           :headers {:content-type "application/json"}
                           :body (json/write-str {:message {:items [{:DOI "10.1016/s0169-5347(01)02380-1"}]}})}

                           "https://doi.org/10.1016/s0169-5347(01)02380-1"
                           (ok "http://linkinghub.elsevier.com/retrieve/pii/S0169534701023801")]
      (is (= (landing-page-url/try-pii-from-url-text "http://api.elsevier.com/content/article/PII:S0169534701023801?httpAccept=text/plain")
             "https://doi.org/10.1016/s0169-5347(01)02380-1")))))

(deftest ^:unit try-fetched-page-metadata
  (testing "DOI can be fetched from meta tag: prism.url"
    (fake/with-fake-http ["http://www.ncbi.nlm.nih.gov/pmc/articles/PMC4852986/?report=classic" (slurp "resources/PMC4852986")
                          "https://doi.org/10.1007/s10461-013-0685-8" (ok "http://link.springer.com/10.1007/s10461-013-0685-8")]
      (is (= (landing-page-url/try-fetched-page-metadata "http://www.ncbi.nlm.nih.gov/pmc/articles/PMC4852986/?report=classic" nil)
              "https://doi.org/10.1007/s10461-013-0685-8"))))
  
  ; NB pubsonline.informs.org sends different HTML to different agents (Firefox vs Curl).
  ; So this data was captured by Chrome. 
  ; Nonetheless, a good example.
  (testing "DOI can be fetched from meta tag: dc.Identifier"
    (fake/with-fake-http ["http://pubsonline.informs.org/doi/abs/10.1287/mnsc.2016.2427" (slurp "resources/mnsc.2016.2427")
                          ; Crazy response from doi.org but that's the way it is.
                          "https://doi.org/mnsc.2016.2427" (ok "https://www.doi.org/mnsc.2016.2427")]
      (is (= (landing-page-url/try-fetched-page-metadata "http://pubsonline.informs.org/doi/abs/10.1287/mnsc.2016.2427" nil)
              "https://doi.org/mnsc.2016.2427"))))

  (testing "DOI can be fetched from meta tag: DC.identifier (different case)"
    (fake/with-fake-http ["https://figshare.com/articles/A_Modeler_s_Tale/3423371/1" (slurp "resources/A_Modeler_s_Tale")
                          "https://doi.org/doi:10.6084/m9.figshare.3423371.v1" (ok "10.6084/m9.figshare.3423371.v1")]
      (is (= (landing-page-url/try-fetched-page-metadata "https://figshare.com/articles/A_Modeler_s_Tale/3423371/1" nil)
              "https://doi.org/10.6084/m9.figshare.3423371.v1"))))

  (testing "DOI can be fetched from meta tag: DC.Identifier.DOI"
    (fake/with-fake-http ["http://www.circumpolarhealthjournal.net/index.php/ijch/article/view/18594/html" (slurp "resources/18594")
                          "https://doi.org/10.3402/ijch.v71i0.18594" (ok "https://www.tandfonline.com/doi/full/10.3402/ijch.v71i0.18594")]
      (is (= (landing-page-url/try-fetched-page-metadata "http://www.circumpolarhealthjournal.net/index.php/ijch/article/view/18594/html" nil)
              "https://doi.org/10.3402/ijch.v71i0.18594"))))

  (testing "DOI can be fetched from meta tag: prism.url"
    (fake/with-fake-http ["http://ccforum.biomedcentral.com/articles/10.1186/s13054-016-1322-5" (slurp "resources/s13054-016-1322-5")
                          "https://doi.org/10.1186/s13054-016-1322-5" (ok "http://ccforum.biomedcentral.com/articles/10.1186/s13054-016-1322-5")]
      (is (= (landing-page-url/try-fetched-page-metadata "http://ccforum.biomedcentral.com/articles/10.1186/s13054-016-1322-5" nil)
              "https://doi.org/10.1186/s13054-016-1322-5"))))

  (testing "DOI can be fetched from meta tag: citation_doi"
    (fake/with-fake-http ["http://jnci.oxfordjournals.org/content/108/6/djw160.full" (slurp "resources/djw160.full")
                          "https://doi.org/10.1093/jnci/djw160" (ok "https://academic.oup.com/jnci/article-lookup/doi/10.1093/jnci/djw160")]
      (is (= (landing-page-url/try-fetched-page-metadata "http://jnci.oxfordjournals.org/content/108/6/djw160.full" nil)
              "https://doi.org/10.1093/jnci/djw160")))))
  
; Tests for overall multi-strategy.

; TODO