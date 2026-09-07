(ns dvergr.io.acquisition
  "Private HTTP acquisition projections. Execution audit is not work-world state.
   No receipt/body read capability is exposed to SCI by this namespace."
  (:require [datahike.api :as dh]
            [hasch.core :as hasch]
            [dvergr.artifact :as artifact]
            [org.replikativ.spindel.engine.core :as ec]))

(def ^:dynamic *scope*
  "Host-owned tool invocation scope, conveyed by supervised evaluation workers.
   Never captured in an SCI function: saved functions use the current invocation."
  nil)

(def schema
  (mapv (fn [[ident type unique?]]
          (cond-> {:db/ident ident :db/valueType type :db/cardinality :db.cardinality/one}
            (= ident :acquisition/run-id) (assoc :db/index true)
            unique? (assoc :db/unique :db.unique/identity)))
        [[:acquisition/id :db.type/uuid true]
         [:acquisition/run-id :db.type/uuid] [:acquisition/room-id :db.type/keyword]
         [:acquisition/world-id :db.type/string] [:acquisition/tool-use-id :db.type/string]
         [:acquisition/status :db.type/keyword] [:acquisition/method :db.type/keyword]
         [:acquisition/origin :db.type/string] [:acquisition/started-at :db.type/instant]
         [:acquisition/ended-at :db.type/instant] [:acquisition/http-status :db.type/long]
         [:acquisition/body-ref :db.type/string] [:acquisition/capture :db.type/keyword]
         [:acquisition/request-key :db.type/uuid]
         [:acquisition/error-class :db.type/string]]))

(defn tool-scope [ctx]
  (let [room (:control-room ctx)
        conn (some-> room :store :conn)]
    ;; Only an explicit durable control-room store owns these receipts. Do not
    ;; silently put audit into the disposable work DB or a temporary ChatContext.
    (when (and conn (:run-id ctx))
      {:conn conn :room-id (:id room) :run-id (:run-id ctx)
       :tool-use-id (:tool-use-id ctx) :execution-ctx (:execution-ctx ctx)
       :artifacts (or (some-> room :store :artifacts) (artifact/blob-store))
       :capture-policy (:http-capture @(:meta room))})))

(defn origin [url]
  (try
    (let [u (java.net.URI. (str url))]
      (when (and (#{"http" "https"} (.getScheme u)) (.getHost u))
        (str (.getScheme u) "://" (.getHost u)
             (when (not= -1 (.getPort u)) (str ":" (.getPort u))))))
    (catch Exception _ nil)))

(defn request-key
  "Fingerprint the pre-injection URL, method and query parameters for citation
   matching. No raw path/query is stored. This is not encryption or proof that
   a document supports a claim; the host must also verify the captured body."
  [opts]
  (hasch/uuid [:http-acquisition/v1 (or (:method opts) :get)
               (str (:url opts)) (:query-params opts)]))

(defn- capture [scope request-origin response]
  (let [{:keys [allowed-origins max-bytes]} (:capture-policy scope)
        body (:body response)]
    (cond
      (not (and (set? allowed-origins) (contains? allowed-origins request-origin)
                (integer? max-bytes) (pos? max-bytes)))
      {:acquisition/capture :disabled}

      (not (string? body)) {:acquisition/capture :unsupported-body}

      ;; UTF-16 length is a lower bound on UTF-8 bytes for valid text. Reject
      ;; huge responses before allocating a second full-size encoding buffer.
      (or (> (count body) max-bytes)
          (> (alength (.getBytes ^String body java.nio.charset.StandardCharsets/UTF_8)) max-bytes))
      {:acquisition/capture :too-large}

      :else {:acquisition/capture :captured
             :acquisition/body-ref
             (artifact/put-value! (:artifacts scope) {:body body})})))

(defn record-request!
  "Observe one HTTP invocation. `perform` retains existing transport/egress policy
   and returns its credential-scrubbed response. Body capture is off by default.
   A :started record after a crash means unknown outcome, never safe-to-retry.
   Completion persistence failures are surfaced without retrying the HTTP effect."
  [opts perform]
  (if-let [{:keys [conn room-id run-id tool-use-id execution-ctx] :as scope} *scope*]
    (let [id (random-uuid)
          request-origin (origin (:url opts))
          world (if (ec/execution-context-bound?) (ec/current-execution-context) execution-ctx)
          started (cond-> {:acquisition/id id :acquisition/room-id room-id
                           :acquisition/run-id run-id :acquisition/status :started
                           :acquisition/request-key (request-key opts)
                           :acquisition/method (keyword (or (:method opts) :get))
                           :acquisition/started-at (java.util.Date.)}
                    request-origin (assoc :acquisition/origin request-origin)
                    (:fork-id world) (assoc :acquisition/world-id (str (:fork-id world)))
                    tool-use-id (assoc :acquisition/tool-use-id (str tool-use-id)))]
      (dh/transact conn [started])
      (let [outcome (try {:response (perform)} (catch Exception e {:error e}))
            response (:response outcome)
            captured (try
                       (if response (capture scope request-origin response)
                           {:acquisition/capture :disabled})
                       (catch Exception e
                         (throw (ex-info "HTTP capture failed; the request may have completed. Do not retry the HTTP effect automatically."
                                         {:type ::outcome-recording-failed :acquisition/id id} e))))]
        (try
          (dh/transact conn
                       [(merge {:acquisition/id id :acquisition/ended-at (java.util.Date.)
                                :acquisition/status (if (:error outcome) :failed :completed)}
                               (when (:status response) {:acquisition/http-status (long (:status response))})
                               (when-let [e (:error outcome)]
                                 {:acquisition/error-class (.getName (class e))})
                               captured)])
          (catch Exception e
            (throw (ex-info "HTTP outcome recording failed; the request may have completed. Do not retry the HTTP effect automatically."
                            {:type ::outcome-recording-failed :acquisition/id id} e))))
        (if-let [e (:error outcome)]
          (throw e)
          ;; Correlation only, not permission to read the audit DB or artifact
          ;; store. Publish this envelope only AFTER outcome persistence succeeds.
          (assoc response :dvergr/acquisition
                 (cond-> {:id id :capture (:acquisition/capture captured)}
                   (:acquisition/body-ref captured)
                   (assoc :body-ref (:acquisition/body-ref captured)))))))
    (perform)))

(defn list-for-run
  "Trusted host query. Caller must authorize room/run access before calling.
   Returns metadata in Run index/entity order, not chronological order.
   Pulls at most limit rows; no response content or arbitrary room scan."
  [conn room-id run-id limit]
  (when-not (and (keyword? room-id) (uuid? run-id) (integer? limit) (<= 1 limit 1000))
    (throw (ex-info "Require room ID, Run UUID and limit 1..1000" {})))
  (let [db @conn]
    (->> (dh/datoms db :avet :acquisition/run-id run-id)
         (map #(dh/entity db (:e %)))
         (filter #(= room-id (:acquisition/room-id %)))
         (take limit)
         (mapv #(dh/pull db '[*] (:db/id %))))))
