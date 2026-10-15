(ns dvergr.web.api-test
  "Wiring checks for the spec-derived JSON HTTP API. Exercises the reitit
   ring-handler directly with synthetic requests — no daemon, no socket — so the
   routing, malli coercion, Swagger generation, JSON encoding and nil-fallthrough
   are all locked deterministically. Live op round-trips run against a daemon."
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [muuntaja.core :as muu]
            [dvergr.agent.ops :as aops]
            [dvergr.ops :as ops]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.web.api :as api]))

(defn- GET  [uri] (api/handler {:request-method :get  :uri uri :headers {}}))
(defn- body-json [resp] (some-> resp :body slurp (json/read-value json/keyword-keys-object-mapper)))

(deftest swagger-derived-from-spec
  (testing "GET /api/v1/swagger.json lists a path per op"
    (let [resp (GET "/api/v1/swagger.json")
          doc  (body-json resp)
          ;; swagger :paths keys are keywords like :/api/v1/room_list — `name`
          ;; drops the leading slash, so compare without it.
          paths (set (map name (keys (:paths doc))))]
      (is (= 200 (:status resp)))
      (doseq [op (keys ops/specification)]
        (is (contains? paths (str "api/v1/" (ops/op->name op)))
            (str op " has an OpenAPI path"))))))

(deftest non-api-uri-falls-through
  (testing "the handler returns nil off /api/v1, so the html server takes over"
    (is (nil? (api/handler {:request-method :get :uri "/dashboard" :headers {}})))
    (is (nil? (api/handler {:request-method :get :uri "/" :headers {}})))))

(deftest reads-without-daemon-error-cleanly
  (testing "a read with no running daemon → 503, never a crash"
    (let [resp (GET "/api/v1/system_stats")]
      (is (= 503 (:status resp)))
      (is (= "No dvergr daemon running." (:error (body-json resp)))))))

(deftest coercion-rejects-missing-required
  (testing "malli coercion runs before the handler: a missing required arg → 400"
    ;; room_stats needs :room; omit it → coercion 400 regardless of daemon state.
    (is (= 400 (:status (GET "/api/v1/room_stats"))))))

(deftest json-encoder-tolerates-op-value-shapes
  (testing "the muuntaja JSON encoder handles the value types ops return"
    (let [sample {:slug "boardroom" :role :user :ts (java.time.Instant/ofEpochMilli 0)
                  :cost-dollars 0.25 :agents [:var :scribe]}
          s (slurp (muu/encode @#'api/m "application/json" sample))]
      (is (string? s))
      (is (re-find #"boardroom" s)))))

(defn- POST [uri body]
  (api/handler {:request-method :post :uri uri
                :headers {"content-type" "application/json"}
                :body (java.io.ByteArrayInputStream. (.getBytes (json/write-value-as-string body) "UTF-8"))
                :dvergr/daemon {:execution-ctx nil} :dvergr/principal dvergr.principal/local-principal}))

(deftest a-failed-op-is-an-http-error
  (testing "an op's client error is a 4xx with the message, never a 200"
    (let [resp (POST "/api/v1/agent_create" {:id "   "})]
      (is (= 400 (:status resp)))
      (is (re-find #"id" (str (:error (body-json resp)))))))
  (testing "an op error without a status is a 500"
    (with-redefs [ops/invoke (fn [& _] (throw (ex-info "boom" {})))]
      (is (= 500 (:status (POST "/api/v1/agent_create" {:id "x"})))))))

(deftest agent-and-invite-failures-carry-their-status
  (with-redefs [aops/get-agent (fn [id] (when (= :taken id) {:id id}))
                aops/update-agent! (fn [& _] nil)
                ops/resolve-room (fn [& _] nil)]
    (is (= 409 (:status (POST "/api/v1/agent_create" {:id "taken"}))))
    (is (= 404 (:status (POST "/api/v1/agent_update" {:id "x" :patch {:budget-dollars 1.0 :tags ""}})))
        "unknown agent after a valid patch")
    (is (= 404 (:status (POST "/api/v1/room_invite" {:room "nowhere" :agent "x"}))))))

(deftest create-takes-the-agents-tools-over-the-wire
  (let [seen (atom nil)]
    (with-redefs [aops/get-agent (fn [_] nil)
                  daemon/provision-agent! (fn [_ fields] (reset! seen fields) {:id (:id fields)})]
      (is (= 200 (:status (POST "/api/v1/agent_create" {:id "t" :tools []}))))
      (is (= #{} (:tools @seen)) "an empty list is no tools")
      (is (= 200 (:status (POST "/api/v1/agent_create" {:id "t" :tools ["read_file"]}))))
      (is (= #{:read_file} (:tools @seen))))))

(deftest room-write-takes-text-base64-and-null-from-json
  (testing "a JSON body's files reach the op as path -> {:text}/{:base64}/nil"
    (let [seen (atom nil)
          body "{\"room\":\"r\",\"files\":{\"data/cases.csv\":{\"text\":\"a\"},\"x.bin\":{\"base64\":\"AAE=\"},\"gone.txt\":null}}"
          resp (with-redefs [ops/invoke (fn [_ _ _ args] (reset! seen args) {:ok true})]
                 (api/handler {:request-method :post :uri "/api/v1/room_write" :dvergr/daemon {} :dvergr/principal dvergr.principal/local-principal
                               :headers {"content-type" "application/json" "accept" "application/json"}
                               :body (java.io.ByteArrayInputStream. (.getBytes body "UTF-8"))}))]
      (is (= 200 (:status resp)))
      (is (= {"data/cases.csv" {:text "a"} "x.bin" {:base64 "AAE="} "gone.txt" nil} (:files @seen))))))

(deftest room-file-failures-carry-their-status
  (with-redefs [ops/resolve-room (fn [_ room] (when (= "r" room) {:id :r}))
                dvergr.rooms.files/write! (fn [_ files _]
                                            (if (contains? files "stale.txt")
                                              (throw (ex-info "changed" {:type :dvergr.tools/changed-underneath}))
                                              (throw (ex-info "bad path" {:type :dvergr.rooms.files/invalid}))))]
    (is (= 404 (:status (POST "/api/v1/room_write" {:room "nowhere" :files {"a" {:text "x"}}}))))
    (is (= 400 (:status (POST "/api/v1/room_write" {:room "r" :files {"../a" {:text "x"}}}))))
    (is (= 409 (:status (POST "/api/v1/room_write" {:room "r" :files {"stale.txt" {:text "x"}}}))))))
