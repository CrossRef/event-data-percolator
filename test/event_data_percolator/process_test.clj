(ns event-data-percolator.process-test
  "Tests for top-level process functions."
  (:require [event-data-percolator.process :as process]
            [clojure.test :refer :all]
            [clojure.data.json :as json]
            [config.core :refer [env]]
            [org.httpkit.fake :as fake]))


(def auth-header "Bearer AUTH_TOKEN")

(deftest env-pre
  (testing "Environment variables set as expected")
  (is (= "https://bus.eventdata.crossref.org" (:event-bus-url-base env)) "Config check for expected event bus endpoint")
  (is (= "memory" (:evidence-storage env)) "Config check EVIDENCE_STORAGE is in-memory")
  (is (= event-data-percolator.input-bundle/evidence-url-prefix "evidence/") "Evidence URL prefix constant should be as expected in later tests."))

(deftest push-output-bundle
    (let [payload
          {:id "EVIDENCE_ID_1234"
            :pages
            ; Page 1
            [{:actions
             [{; Action 1.1
               :events
               [{:id "11111" :and :other-fields}
                {:id "22222" :and :other-fields}]}
               ; Action 1.2
               {:events
               [{:id "33333" :and :other-fields}
                {:id "44444" :and :other-fields}]}]}
             ; Page 2
             {:actions
              ; Action 2.1
             [{:events
               [{:id "55555" :and :other-fields}
                {:id "66666" :and :other-fields}]}]}]}

            input {:payload payload :auth-header auth-header}

          responses (atom [])]

    (fake/with-fake-http [
        "https://bus.eventdata.crossref.org/events"
        (fn [orig-fn opts callback] 
          ; Increment counter.
          (swap! responses conj opts)
          {:status 201})]

      (let [result (process/push-output-bundle input)]

      (testing "push-output-bundle should send evens with the auth header"
        (is (= 6 (count @responses)) "Six events in a payload means six successful POSTs")

        (doseq [response @responses]
          (is (= (:headers response) {"Content-Type" "application/json", "Authorization" "Bearer AUTH_TOKEN"}) "Auth header should be carried through and content type set correctly.")
          (is (= (:method response) :post) "Events should be POSTed"))

        (is (=
          (set (map :body @responses))
          #{"{\"id\":\"11111\",\"and\":\"other-fields\"}"
            "{\"id\":\"22222\",\"and\":\"other-fields\"}"
            "{\"id\":\"33333\",\"and\":\"other-fields\"}"
            "{\"id\":\"44444\",\"and\":\"other-fields\"}"
            "{\"id\":\"55555\",\"and\":\"other-fields\"}"
            "{\"id\":\"66666\",\"and\":\"other-fields\"}"})
          "All indidual events should have been sent"))


      (testing "push-output-bundle should send the evidence record to the registry"
        ; Config specifies that the 'memory' store should be used, so we just peek in there.
        ; Don't inspect the entire value, just check that a string we expect is present.
        (is (.contains 
              (-> process/evidence-store deref :data deref (get "evidence/EVIDENCE_ID_1234"))
              "44444")
            "Evidence storage contains the new evidence record"))))))


(deftest push-output-bundle-failure
  (testing "push-output-bundle should retry sending on initial failure"
    ; A single event in this one.
    (let [payload
            {:id "EVIDENCE_ID_5678" :pages [{:actions [{:events [{:id "7777777" :and :other-fields}]}]}]}
            input {:payload payload :auth-header auth-header}

          ; A signal to indicate that all attempts have been tried.
          ; Retries are sent asyncronously, so we need to block on the signal.
          done (promise)

          responses (atom [])]

    (fake/with-fake-http [
        "https://bus.eventdata.crossref.org/events"
        (fn [orig-fn opts callback] 
          ; Increment counter.
          (swap! responses conj opts)

          ; Unreliable responses. Return a 400 then a 500 then a timeout, then work OK on the fourth go.
          (condp = (count @responses)
            1 {:status 400}
            2 {:status 500}
            3 (throw (new org.httpkit.client.TimeoutException "I got bored"))
            4 (do (deliver done true) {:status 201})))]

      ; Set the delay to zero otherwise the test will take a while.
      (reset! process/retry-delay 0)

      (let [result (process/push-output-bundle input)]
        (deref done)
        (is (= 4 (count @responses)) "Four attempts should have been made.")

       (testing "push-output-bundle should send the evidence record to the registry after unreliable upload"
        ; Config specifies that the 'memory' store should be used, so we just peek in there.
        ; Don't inspect the entire value, just check that a string we expect is present.
        (is (.contains 
              (-> process/evidence-store deref :data deref (get "evidence/EVIDENCE_ID_5678"))
              "7777777")
            "Evidence storage contains the new evidence record")))))))