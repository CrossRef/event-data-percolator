(ns event-data-percolator.server
  (:require [clojure.data.json :as json]
            [clojure.tools.logging :as l]
            [event-data-percolator.input-bundle :as input-bundle]
            [event-data-percolator.process :as process]
            [event-data-percolator.queue :as queue]
            [org.httpkit.server :as server]
            [config.core :refer [env]]
            [compojure.core :refer [defroutes GET POST]]
            [ring.util.response :as ring-response]
            [ring.middleware.params :as middleware-params]
            [ring.middleware.content-type :as middleware-content-type]
            [liberator.core :refer [defresource]]
            [liberator.representation :as representation]
            [clj-time.core :as clj-time]
            [clj-time.format :as clj-time-format]
            [clj-time.coerce :as clj-time-coerce]
            [clojure.java.io :refer [reader input-stream]]
            [event-data-common.jwt :as jwt]
            [event-data-common.date :as date]
            [event-data-common.status :as status])
  (:import
           [java.net URL MalformedURLException InetAddress])
  (:gen-class))

(defresource home
  []
  :available-media-types ["text/html"]
  :handle-ok (fn [ctx]
                (representation/ring-response
                  (ring-response/redirect "https://eventdata.crossref.org"))))

;  "Expose heartbeat."
(defresource heartbeat
  []
  :allowed-methods [:get]
  :available-media-types ["application/json"]
  :handle-ok (fn [ctx]
              (let [now (clj-time/now)]
                (let [report {:machine_name (.getHostName (InetAddress/getLocalHost))
                              :version (System/getProperty "event-data-percolator.version")
                              :now (str now)
                              :status "OK"}]
                  report))))



; "Accept input bundles for diagnostic purposes."
(defresource input-bundle-diagnostic
  []
  :allowed-methods [:post]
  :available-media-types ["application/json"]
  
  :authorized? (fn
                [ctx]
                ; Authorized if the JWT claims are correctly signed.
                (-> ctx :request :jwt-claims))

  :malformed? (fn [ctx]
                (let [payload (try (-> ctx :request :body reader (json/read :key-fn keyword)) (catch Exception _ nil))
                      schema-errors (input-bundle/validation-errors payload)]
                  [schema-errors {::payload payload ::schema-errors schema-errors}]))

  :handle-malformed (fn [ctx]
                      (json/write-str (if-let [schema-errors (::schema-errors ctx)]
                        {:status "Malformed"
                         :schema-errors (str schema-errors)}
                        {:status "Malformed"})))

  :post! (fn [ctx]
    (let [result (input-bundle/process (::payload ctx))
          serialized-result (json/write-str result)]
      {::serialized-result serialized-result}))

  :handle-created (fn [ctx]
      (::serialized-result ctx)))

(defresource input-bundle
  []
  :allowed-methods [:post]
  :available-media-types ["application/json"]
  
  :authorized? (fn
                [ctx]
                ; Authorized if the JWT claims are correctly signed.
                (-> ctx :request :jwt-claims))

  :malformed? (fn [ctx]
                (let [payload (try (-> ctx :request :body reader (json/read :key-fn keyword)) (catch Exception _ nil))
                      schema-errors (input-bundle/validation-errors payload)]
                  [schema-errors {::payload payload ::schema-errors schema-errors}]))

  :handle-malformed (fn [ctx]
                      (json/write-str (if-let [schema-errors (::schema-errors ctx)]
                        {:status "Malformed"
                         :schema-errors (str schema-errors)}
                        {:status "Malformed"})))

  :post! (fn [ctx]
           (queue/enqueue (::payload ctx) process/input-bundle-queue-name)
           true)

  :handle-created (fn [ctx]
      {"Status" "accepted"}))

(defroutes app-routes
  (POST "/input/diagnostic" [] (input-bundle-diagnostic))
  (POST "/input" [] (input-bundle))
  (GET "/heartbeat" [] (heartbeat)))

(def app
  ; Delay construction to runtime for secrets config value.
  (delay
    (-> app-routes
       middleware-params/wrap-params
       (jwt/wrap-jwt (:jwt-secrets env))
       (middleware-content-type/wrap-content-type))))


(defn run-server []
  (let [port (Integer/parseInt (:port env))]
    ; (l/info "Start heartbeat")
    ; (at-at/every 10000 #(status/send! "event-bus" "heartbeat" "tick" 1) schedule-pool)

    (l/info "Start server on " port)
    (server/run-server @app {:port port})))
