(ns dvergr.web.api-test
  "Wiring checks for the spec-derived JSON HTTP API. Exercises the reitit
   ring-handler directly with synthetic requests — no daemon, no socket — so the
   routing, malli coercion, Swagger generation, JSON encoding and nil-fallthrough
   are all locked deterministically. Live op round-trips run against a daemon."
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [muuntaja.core :as muu]
            [dvergr.ops :as ops]
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
