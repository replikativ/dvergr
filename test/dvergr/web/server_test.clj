(ns dvergr.web.server-test
  "Tests for the HTTP server, API routes, and agent UI mounting."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.web.server :as server]
            [dvergr.registry :as registry]
            [dvergr.tools :as tools]
            [hato.client :as http]
            [jsonista.core :as json]))

(def ^:private json-mapper
  (json/object-mapper {:decode-key-fn keyword}))

(def ^:private test-port 18765)

;; ============================================================================
;; Fixtures
;; ============================================================================

(defn- make-mock-daemon []
  {:config {}
   :execution-ctx (atom nil)
   :discourse-room nil
   :telegram-ch nil
   :http-server nil
   :system-watcher (atom nil)
   :response-sinks (atom [])
   :status (atom :running)})

(use-fixtures :each
  (fn [f]
    (let [orig-registry (registry/snapshot)
          orig-handlers @server/agent-handlers
          orig-server @server/server-state]
      (try
        (let [daemon (make-mock-daemon)]
          (server/start! daemon :port test-port)
          (Thread/sleep 100) ; Give server time to start
          (f))
        (finally
          (server/stop!)
          (registry/restore! orig-registry)
          (reset! server/agent-handlers orig-handlers)
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
  (testing "GET /api/agents returns HTML when Accept: text/html"
    (let [{:keys [status body]} (get-html "/api/agents")]
      (is (= 200 status))
      (is (string? body))
      (is (clojure.string/includes? body "<div>")))))

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

(deftest test-agent-handler-mounting
  (testing "Mount and unmount agent handler"
    (let [handler (fn [req]
                    {:status 200
                     :headers {"Content-Type" "text/plain"}
                     :body (str "Hello from agent! Path: " (:uri req))})
          url (server/mount-agent-handler! :test-agent handler)]

      ;; Handler should be mounted
      (is (clojure.string/includes? url "/agents/test-agent/"))
      (is (some? (get @server/agent-handlers :test-agent)))

      ;; Request should reach handler with prefix stripped
      (let [resp (http/get (str "http://localhost:" test-port "/agents/test-agent/")
                           {:as :string :throw-exceptions false})]
        (is (= 200 (:status resp)))
        (is (= "Hello from agent! Path: /" (:body resp))))

      ;; Sub-path should work with prefix stripped
      (let [resp (http/get (str "http://localhost:" test-port "/agents/test-agent/data")
                           {:as :string :throw-exceptions false})]
        (is (= 200 (:status resp)))
        (is (= "Hello from agent! Path: /data" (:body resp))))

      ;; Unmount
      (server/unmount-agent-handler! :test-agent)
      (is (nil? (get @server/agent-handlers :test-agent)))

      ;; Should 404 after unmount
      (let [resp (http/get (str "http://localhost:" test-port "/agents/test-agent/")
                           {:as :string :throw-exceptions false})]
        (is (= 404 (:status resp)))))))

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
