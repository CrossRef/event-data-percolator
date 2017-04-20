(ns event-data-percolator.process
  "Top level process inputs."
  (:require [event-data-percolator.queue :as queue]
            [event-data-percolator.input-bundle :as input-bundle]
            [event-data-percolator.action :as action]
            [clojure.tools.logging :as log]
            [clojure.core.async :refer [thread go alts! buffer chan <!! >!! >! <! timeout alts!! close!]]
            [config.core :refer [env]]
            [event-data-common.backoff :as backoff]
            [event-data-common.storage.s3 :as s3]
            [event-data-common.artifact :as artifact]
            [event-data-common.storage.memory :as memory]
            [event-data-common.storage.store :as store]
            [clojure.core.memoize :as memo]
            [clojure.data.json :as json]
            [org.httpkit.client :as client]
            [event-data-common.status :as status]
            [robert.bruce :refer [try-try-again]]))

(def input-bundle-queue-name "input-bundle")
(def output-bundle-queue-name "output-bundle")

(def domain-list-artifact-name "crossref-domain-list")

(def evidence-store
  (delay
    (condp = (:evidence-storage env "s3")
      ; Memory for unit testing ONLY.
      "memory" (memory/build)
      "s3" (s3/build (:s3-key env) (:s3-secret env) (:evidence-region-name env) (:evidence-bucket-name env)))))

(defn retrieve-domain-list
  "Return tuple of [version-url, domain-list-set]"
  []
  (log/info "Retrieving domain list artifact")
  (status/send! "percolator" "artifact" "fetch" 1)
  ; Fetch the cached copy of the domain list.
  (let [domain-list-artifact-version (artifact/fetch-latest-version-link domain-list-artifact-name)
        ; ~ 5KB string, set of ~ 8000
        domain-list (-> domain-list-artifact-name artifact/fetch-latest-artifact-stream clojure.java.io/reader line-seq set)]
    [domain-list-artifact-version domain-list]))

(def cache-milliseconds
  "One hour"
  3600000)

(def cached-domain-list
  "Cache the domain list and version url. It's very rarely actually updated."
  (memo/ttl retrieve-domain-list {} :ttl/threshold cache-milliseconds))

(defn process-input-bundle
  [{payload :payload auth-header :auth-header}]
  ; Fetch the cached copy of the domain list.
  (log/debug "Process input bundle")
  (status/send! "percolator" "input" "process" 1)
  (let [[domain-list-artifact-version domain-list] (cached-domain-list)]
    {:auth-header auth-header :payload (input-bundle/process payload domain-list-artifact-version domain-list)}))

(def process-concurrency
  (delay (Integer/parseInt (:process-concurrency env "10"))))

(defn run-process
  "Run processing input bundles from the input queue, place on output queue. Block."
  []
  (log/info "Registering shutdown hook...")
  (.addShutdownHook
      (Runtime/getRuntime)
      (new Thread
        (try
        (fn []
          (log/info "Shutdown hook started")
          (let [stopped-signal (promise)]
            ; Pass the promise to queue processing. 
            (reset! queue/stopped-signal stopped-signal)

            ; It will deliver when it's ready.
            (log/info "Waiting for queue processing to stop...")
            (deref stopped-signal)
            (log/info "Queue processing happily stopped.")
            (log/info "Shutdown hook finished gracefully.")))
        (catch Exception ex (.printStackTrace ex)))))

  (log/info "Start process queue")
  (queue/start-heartbeat)
  (let [threads (map (fn [thread-number]
                       (log/info "Starting processing thread number" thread-number)
                       (thread (queue/process-queue input-bundle-queue-name process-input-bundle output-bundle-queue-name)))
                     (range @process-concurrency))]

    ; Wait for any threads to exit. They shoudln't.
    (alts!! threads)

    (log/warn "One thread died, exiting.")))

(def retries
  "Try to deliver this many times downstream."
  5)

(def retry-delay
  "Wait in milliseconds before first redelivery attempt. Retry will double each time."
  ; Atom so this can be altered in unit tests.
  (atom (* 10 1000)))

(defn push-output-bundle
  "This is called as a queue-processing function."
  [{payload :payload auth-header :auth-header}]
  
  (log/info "Pushing output bundle" (:id payload))
  (status/send! "percolator" "output" "sent" 1)

  (let [payload-json (json/write-str payload) 
        evidence-record-id (:id payload)
        storage-key (str input-bundle/evidence-url-prefix evidence-record-id)
        events (input-bundle/extract-all-events payload)]
    
    (log/info "Got output bundle id" (:id payload) "with" (count events) "Events")

    ; Send all events.
    (doseq [event events]
      (log/debug "Sending event: " (:id event))
      (status/send! "percolator" "output-event" "sent" 1)

      (backoff/try-backoff
          ; Exception thrown if not 200 or 201, also if some other exception is thrown during the client posting.
          #(let [response @(client/post (str (:event-bus-url-base env) "/events") {:headers {"Content-Type" "application/json"
                                                                                            "Authorization" auth-header} :body (json/write-str event)})]
              (when-not (#{201 200} (:status response)) (throw (new Exception (str "Failed to send to Event Bus with status code: " (:status response) (:body response))))))
          @retry-delay
          retries
          ; Only log info on retry because it'll be tried again.
          #(log/info "Error sending Evidence Record" (:id event) "with exception" (.getMessage %))
          ; But if terminate is called, that's a serious problem.
          #(log/error "Failed to send event" (:id event) "to downstream")
          #(log/debug "Finished broadcasting" (:id event) "to downstream")))

  ; Store record
  (log/info "Store Evidence Record" (:id payload))
  (store/set-string @evidence-store storage-key payload-json)

  ; If everything went ok, save Actions for deduplication.
  (log/debug "Setting deduplication info for" (:id payload))
  (action/store-action-duplicates payload))

  true)



(defn run-push
  "Push output bundles from output queue. Block."
  []
  ; Shutdown hook stops queue ingestion, waits for the processing to finish.
  ; Of course, it could just be SIGKILLED, which would mean we had left-over processing in the working queue.
  (log/info "Registering shutdown hook...")
  (.addShutdownHook
      (Runtime/getRuntime)
      (new Thread
        (fn []
          (log/info "Shutdown hook started")
          (let [stopped-signal (promise)]
            ; Pass the promise to queue processing. 
            (reset! queue/stopped-signal stopped-signal)

            ; It will deliver when it's ready.
            (log/info "Waiting for queue processing to stop...")
            (deref stopped-signal)
            (log/info "Queue processing happily stopped.")
            (log/info "Shutdown hook finished gracefully.")))))

  (queue/start-heartbeat)
  (log/info "Start push queue")
  (queue/process-queue output-bundle-queue-name push-output-bundle nil))
