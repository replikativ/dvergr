(ns dvergr.io.acquisition-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as dh]
            [dvergr.artifact :as artifact]
            [dvergr.chat.schema :as schema]
            [dvergr.discourse :as d]
            [dvergr.io.acquisition :as acquisition]
            [dvergr.room.store :as room-store]
            [dvergr.room.store.datahike :as store]
            [dvergr.sandbox.ns.io :as io]
            [dvergr.tools :as tools]
            [hato.client :as http]
            [sci.core :as sci]))

(deftest durable-tool-acquisition
  (let [path (str (java.nio.file.Files/createTempDirectory
                   "dvergr-acquisition-" (make-array java.nio.file.attribute.FileAttribute 0)) "/db")
        cfg {:store {:backend :file :path path :id (random-uuid)} :schema-flexibility :write :keep-history? true}
        _ (dh/create-database cfg)
        conn (dh/connect cfg)
        artifacts (artifact/datahike-store conn)
        captured-ref (atom nil)
        run-id (random-uuid)
        room-id (keyword (str "receipt-" (random-uuid)))]
    (try
      (schema/ensure-full-schema! conn)
      (let [room (d/make-room {:id room-id :store (store/make conn artifacts)})
            sci-ctx (sci/init {})
            _ (io/add-http-ns! sci-ctx)
            ;; A saved function must select the invocation scope, not its setup room.
            _ (sci/eval-string* sci-ctx "(def saved-get babashka.http-client/get)")
            ctx {:control-room room :run-id run-id :tool-use-id "call-1"
                 :sci-ctx sci-ctx :execution-ctx (:ctx room)}]
        (try
          ;; Hato/JDK returns Integer, not Clojure's default Long literal.
          (with-redefs [http/request (fn [opts]
                                       (is (= :never (get-in opts [:http-client :redirect-policy])))
                                       {:status (int 200) :headers {} :body "private fixture"})]
            (is (= :success (:type (tools/execute "clojure_eval"
                                                  {:code "(saved-get \"https://93.184.216.34/?secret=value\")"} ctx))))
            (let [rows (acquisition/list-for-run conn room-id run-id 10)]
              (is (= 1 (count rows)))
              (is (= :completed (:acquisition/status (first rows))))
              (is (= (acquisition/request-key {:url "https://93.184.216.34/?secret=value" :method :get})
                     (:acquisition/request-key (first rows))))
              (is (= "call-1" (:acquisition/tool-use-id (first rows))))
              (is (= :disabled (:acquisition/capture (first rows))))
              (is (not (.contains (pr-str rows) "secret"))))
            ;; Pull only the requested prefix; do not materialize the whole Run
            ;; before imposing the public result limit.
            (let [pull dh/pull
                  pulls (atom 0)]
              (with-redefs [dh/pull (fn [& args] (swap! pulls inc) (apply pull args))]
                (is (= 1 (count (acquisition/list-for-run conn room-id run-id 1))))
                (is (= 1 @pulls))))
            (swap! (:meta room) assoc :http-capture
                   {:allowed-origins #{"https://93.184.216.34"} :max-bytes 1024})
            (let [child (d/fork-room room {:isolation :ctx})
                  second-run (random-uuid)]
              (try
                (is (= :success (:type (tools/execute "clojure_eval"
                                                      {:code "(saved-get \"https://93.184.216.34/evidence\")"}
                                                      (assoc ctx :run-id second-run :execution-ctx (:ctx child))))))
                (let [row (first (acquisition/list-for-run conn room-id second-run 10))]
                  (is (= :captured (:acquisition/capture row)))
                  (reset! captured-ref (:acquisition/body-store-ref row))
                  (is (= {:body "private fixture"}
                         (artifact/get-value artifacts (:acquisition/body-store-ref row))))
                  (is (not= (:acquisition/world-id (first (acquisition/list-for-run conn room-id run-id 10)))
                            (:acquisition/world-id row))))
                (finally (d/discard child)))
              (is (= 1 (count (acquisition/list-for-run conn room-id second-run 10))))))
          (finally (d/close-room! room))))
      (dh/release conn)
      (let [reopened (dh/connect cfg)]
        (try (is (= 1 (count (acquisition/list-for-run reopened room-id run-id 10))))
             (is (= {:body "private fixture"}
                    (artifact/get-value (artifact/datahike-store reopened) @captured-ref)))
             (is (empty? (acquisition/list-for-run reopened :other-room run-id 10)))
             (room-store/-delete-room! (store/make reopened artifacts) room-id)
             (is (empty? (acquisition/list-for-run reopened room-id run-id 10)))
             (finally (dh/release reopened))))
      (finally (dh/release conn) (dh/delete-database cfg)))))

(deftest recording-failure-does-not-retry
  (let [calls (atom 0) txs (atom 0)
        scope {:conn :stub :room-id :research :run-id (random-uuid)}
        perform #(do (swap! calls inc) {:status 200 :body "fixture"})]
    (binding [acquisition/*scope* scope]
      (with-redefs [dh/transact (fn [& _] (throw (ex-info "offline" {})))]
        (is (thrown? Exception (acquisition/record-request! {} perform)))
        (is (zero? @calls)))
      (with-redefs [dh/transact (fn [& _] (when (= 2 (swap! txs inc)) (throw (ex-info "offline" {}))))]
        (is (thrown-with-msg? Exception #"Do not retry"
                              (acquisition/record-request! {} perform)))
        (is (= 1 @calls))))))

(deftest request-fingerprint-distinguishes-citation-targets
  (let [request {:url "https://example.org/a" :method :get}]
    (is (= (acquisition/request-key request)
           (acquisition/request-key (dissoc request :method))))
    (doseq [other [(assoc request :url "https://example.org/b")
                   (assoc request :method :post)
                   (assoc request :query-params {:page 2})]]
      (is (not= (acquisition/request-key request) (acquisition/request-key other))))))

(deftest capture-and-failure-projections
  (let [txs (atom [])
        scope {:conn :stub :room-id :research :run-id (random-uuid)
               :artifacts (artifact/memory-store)
               :capture-policy {:allowed-origins #{"https://example.org"} :max-bytes 2}}]
    (binding [acquisition/*scope* scope]
      (with-redefs [dh/transact (fn [_ tx] (swap! txs into tx))]
        (acquisition/record-request! {:url "https://example.org/path?token=private"}
                                     #(identity {:status 200 :body "éé"}))
        (is (= :too-large (:acquisition/capture (last @txs))))
        (is (nil? (:acquisition/body-store-ref (last @txs))))
        (is (not (.contains (pr-str @txs) "private")))
        (acquisition/record-request! {:url "https://other.example/path"}
                                     #(identity {:status 200 :body "x"}))
        (is (= :disabled (:acquisition/capture (last @txs))))
        (is (thrown? Exception
                     (acquisition/record-request! {:url "bad-url"}
                                                  #(throw (ex-info "private details" {})))))
        (is (= :failed (:acquisition/status (last @txs))))
        (is (not (.contains (pr-str @txs) "private details")))))))

(deftest response-provenance-is-issued-after-persistence
  (let [txs (atom [])
        artifacts (artifact/memory-store)
        response {:status 200 :headers {} :body "source"}
        scope {:conn :stub :room-id :research :run-id (random-uuid)
               :artifacts artifacts
               :capture-policy {:allowed-origins #{"https://example.org"} :max-bytes 1024}}]
    (is (= response (acquisition/record-request! {} (constantly response))))
    (binding [acquisition/*scope* scope]
      (with-redefs [dh/transact (fn [_ tx] (swap! txs into tx))]
        (let [actual (acquisition/record-request! {:url "https://example.org/"} (constantly response))
              provenance (:dvergr/acquisition actual)]
          (is (= response (dissoc actual :dvergr/acquisition)))
          (is (= (:acquisition/id (first @txs)) (:id provenance)))
          (is (= :completed (:acquisition/status (last @txs))))
          (is (= :captured (:capture provenance)))
          (is (= (:acquisition/body-store-ref (last @txs)) (:body-ref provenance)))
          (is (= {:body "source"} (artifact/get-value artifacts (:body-ref provenance)))))))))
