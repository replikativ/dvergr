(ns dvergr.web.server-test
  "Tests for the HTTP server, API routes, and agent UI mounting."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.web.server :as server]
            [dvergr.tools :as tools]
            [hato.client :as http]
            [jsonista.core :as json]
            [org.replikativ.spindel.engine.context :as ctx]))

(def ^:private json-mapper
  (json/object-mapper {:decode-key-fn keyword}))

(def ^:private test-port 18765)

;; ============================================================================
;; Fixtures
;; ============================================================================

(defn- make-mock-daemon []
  ;; :execution-ctx must satisfy spindel's PState protocol — the
  ;; handler binds it as *execution-context* and registry/* calls
  ;; ec/get-state on it. A bare atom doesn't implement PState; use
  ;; a real (empty) execution context instead.
  {:config {}
   :execution-ctx (ctx/create-execution-context)
   :discourse-room nil
   :telegram-ch nil
   :http-server nil
   :status (atom :running)})

(use-fixtures :each
  (fn [f]
    (let [orig-server @server/server-state]
      (try
        (let [daemon (make-mock-daemon)]
          (server/start! daemon :port test-port)
          (Thread/sleep 100) ; Give server time to start
          (f))
        (finally
          (server/stop!)
          (reset! server/server-state orig-server))))))

(defn- get-json [path]
  (let [resp (http/get (str "http://localhost:" test-port path)
                       {:as :string :throw-exceptions false})]
    {:status (:status resp)
     :body (json/read-value (:body resp) json-mapper)}))

(defn- get-html [path]
  (let [resp (http/get (str "http://localhost:" test-port path)
                       {:as :string
                        :throw-exceptions false
                        :headers {"Accept" "text/html"}})]
    {:status (:status resp)
     :body (:body resp)}))

;; ============================================================================
;; Tests
;; ============================================================================

(deftest test-health-endpoint
  (testing "GET /api/health returns JSON status"
    (let [{:keys [status body]} (get-json "/api/health")]
      (is (= 200 status))
      (is (= "running" (:status body)))
      (is (number? (:agents body))))))

(deftest test-agents-endpoint-json
  (testing "GET /api/agents returns JSON agent list"
    (let [{:keys [status body]} (get-json "/api/agents")]
      (is (= 200 status))
      (is (vector? (:agents body))))))

(deftest test-agents-endpoint-html
  (testing "GET /api/agents returns the roster HTML fragment when Accept: text/html"
    (let [{:keys [status body]} (get-html "/api/agents")]
      (is (= 200 status))
      (is (string? body))
      (is (clojure.string/includes? body "agent-roster")))))

(deftest test-schedules-endpoint
  (testing "GET /api/schedules returns JSON schedule list"
    (let [{:keys [status body]} (get-json "/api/schedules")]
      (is (= 200 status))
      (is (vector? (:schedules body))))))

(deftest test-dashboard
  (testing "GET /dashboard serves HTML dashboard"
    (let [{:keys [status body]} (get-html "/dashboard")]
      (is (= 200 status))
      (is (clojure.string/includes? body "dvergr"))
      (is (clojure.string/includes? body "htmx")))))

(deftest test-root-serves-dashboard
  (testing "GET / serves the dashboard"
    (let [{:keys [status body]} (get-html "/")]
      (is (= 200 status))
      (is (clojure.string/includes? body "dvergr")))))

(deftest test-404
  (testing "Unknown paths return 404"
    (let [{:keys [status]} (get-json "/nonexistent")]
      (is (= 404 status)))))

(deftest test-agents-redirects-to-dashboard
  (testing "GET /agents redirects to the dashboard's Agents section"
    (let [resp (http/get (str "http://localhost:" test-port "/agents")
                         {:as :string :throw-exceptions false :redirect-policy :none})]
      (is (= 303 (:status resp)))
      (is (clojure.string/includes? (get-in resp [:headers "location"]) "#agents")))))

(deftest test-agent-api-unknown
  (testing "POST to unknown agent returns 404"
    (let [resp (http/post (str "http://localhost:" test-port "/api/agents/nonexistent/inbox")
                          {:as :string :throw-exceptions false
                           :content-type :json
                           :body "{}"})]
      (is (= 404 (:status resp))))))

(deftest test-server-lifecycle
  (testing "running? reflects server state"
    (is (server/running?))
    (is (= test-port (server/server-port)))))
