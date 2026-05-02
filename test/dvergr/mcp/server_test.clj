(ns dvergr.mcp.server-test
  "Tests for the dvergr MCP server.

   Level 1: Protocol tests (no external deps, fast)
   Level 2: TCP transport tests
   Level 3: Integration tests with datahike/spindel (tagged :integration)
   Level 4: OpenClaw container tests (tagged :openclaw)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.mcp.server :as server]
            [dvergr.mcp.json-rpc :as json-rpc]
            [jsonista.core :as json])
  (:import (java.net Socket)
           (java.io BufferedReader InputStreamReader BufferedWriter OutputStreamWriter)))

;; ============================================================================
;; Helper: in-process MCP message exchange (no network)
;; ============================================================================

(defn- make-test-context []
  (let [session (server/make-session)]
    {:session session
     :send-fn (fn [_message] nil) ;; no-op for in-process tests
     :tool-defs server/tool-definitions
     :tool-handlers server/tool-handlers}))

(def ^:private test-context (make-test-context))

(defn- reset-test! []
  (alter-var-root #'test-context (fn [_] (make-test-context))))

(use-fixtures :each (fn [f] (reset-test!) (f)))

(defn- call-server
  "Simulate sending a JSON-RPC request and return the response directly."
  [method params & {:keys [id] :or {id 1}}]
  (json-rpc/handle-message test-context
                           {:jsonrpc "2.0" :id id :method method :params params}))

(defn- notify-server
  "Simulate sending a JSON-RPC notification. Returns nil."
  [method params]
  (json-rpc/handle-message test-context
                           {:jsonrpc "2.0" :method method :params params}))

(defn- init-session!
  "Run initialize + notifications/initialized handshake."
  []
  (call-server "initialize"
               {:protocolVersion "2025-11-25"
                :capabilities {}
                :clientInfo {:name "test" :version "1.0"}})
  (notify-server "notifications/initialized" {}))

;; ============================================================================
;; Level 1: MCP Protocol Tests (in-process)
;; ============================================================================

(deftest test-initialize
  (testing "MCP initialize handshake"
    (let [response (call-server "initialize"
                                {:protocolVersion "2025-11-25"
                                 :capabilities {}
                                 :clientInfo {:name "test" :version "1.0"}})]
      (is (= "2.0" (:jsonrpc response)))
      (is (= 1 (:id response)))
      (is (nil? (:error response)))
      (let [result (:result response)]
        (is (= "2025-11-25" (:protocolVersion result)))
        (is (= "dvergr-mcp" (get-in result [:serverInfo :name])))
        (is (contains? (:capabilities result) :tools))))))

(deftest test-initialize-old-version
  (testing "MCP initialize with 2024-11-05 version"
    (let [response (call-server "initialize"
                                {:protocolVersion "2024-11-05"
                                 :capabilities {}
                                 :clientInfo {:name "test" :version "1.0"}})
          result (:result response)]
      (is (= "2024-11-05" (:protocolVersion result))))))

(deftest test-tools-list
  (testing "List all available tools"
    (init-session!)
    (let [response (call-server "tools/list" {})
          tools (get-in response [:result :tools])]
      (is (= 12 (count tools)) "Should have 8 runtime/memory/repl + 4 agent tools")
      (let [tool-names (set (map :name tools))]
        (testing "Runtime management tools"
          (is (contains? tool-names "runtime_fork"))
          (is (contains? tool-names "runtime_merge"))
          (is (contains? tool-names "runtime_list"))
          (is (contains? tool-names "runtime_discard")))
        (testing "Memory tools"
          (is (contains? tool-names "memory_query"))
          (is (contains? tool-names "memory_transact"))
          (is (contains? tool-names "memory_history")))
        (testing "REPL tool"
          (is (contains? tool-names "repl_eval")))
        (testing "Agent tools"
          (is (contains? tool-names "agent_list"))
          (is (contains? tool-names "agent_create"))
          (is (contains? tool-names "agent_send_message"))
          (is (contains? tool-names "agent_stop"))))
      (testing "Each tool has required schema"
        (doseq [tool tools]
          (is (string? (:name tool)) (str "Tool missing name: " tool))
          (is (string? (:description tool)) (str "Tool missing description: " (:name tool)))
          (is (map? (:inputSchema tool)) (str "Tool missing inputSchema: " (:name tool))))))))

(deftest test-tool-call-stub
  (testing "Tool call returns stub response (pre-implementation)"
    (init-session!)
    (let [response (call-server "tools/call"
                                {:name "runtime_list"
                                 :arguments {}})
          content (get-in response [:result :content])]
      (is (seq content) "Should have content")
      (is (= "text" (:type (first content))))
      (is (false? (get-in response [:result :isError]))))))

(deftest test-tool-call-with-arguments
  (testing "Tool call passes arguments correctly"
    (init-session!)
    (let [response (call-server "tools/call"
                                {:name "runtime_fork"
                                 :arguments {:branch_name "test-branch"
                                             :description "Test fork"}})
          text (get-in response [:result :content 0 :text])]
      (is (string? text))
      (is (.contains text "test-branch")))))

(deftest test-hot-swap-tool-handler
  (testing "Tool handlers can be swapped at runtime"
    (init-session!)
    ;; Install a custom handler
    (swap! server/tool-handlers assoc "runtime_list"
           (fn [_ctx _args]
             {:content [{:type "text" :text "custom-handler-response"}]
              :isError false}))
    (try
      (let [response (call-server "tools/call"
                                  {:name "runtime_list" :arguments {}})
            text (get-in response [:result :content 0 :text])]
        (is (= "custom-handler-response" text)))
      (finally
        (swap! server/tool-handlers dissoc "runtime_list")))))

(deftest test-unknown-method
  (testing "Unknown method returns error"
    (init-session!)
    (let [response (call-server "nonexistent/method" {})]
      (is (some? (:error response)))
      (is (= -32601 (get-in response [:error :code]))))))

(deftest test-notifications-ignored
  (testing "Notifications (no id) don't produce responses"
    (call-server "initialize"
                 {:protocolVersion "2025-11-25"
                  :capabilities {}
                  :clientInfo {:name "test" :version "1.0"}})
    (let [response (notify-server "notifications/initialized" {})]
      (is (nil? response)))))

(deftest test-agent-list-tool
  (testing "agent_list returns empty list initially"
    (init-session!)
    (let [response (call-server "tools/call"
                                {:name "agent_list" :arguments {}})
          text (get-in response [:result :content 0 :text])]
      (is (= "No active agents." text)))))

(deftest test-agent-create-and-list
  (testing "agent_create registers an agent, agent_list shows it"
    (init-session!)
    (let [create-resp (call-server "tools/call"
                                   {:name "agent_create"
                                    :arguments {:agent_id "test-agent"
                                                :provider "fireworks"
                                                :model "test-model"}})]
      (is (false? (get-in create-resp [:result :isError])))
      (is (.contains (get-in create-resp [:result :content 0 :text]) "test-agent")))
    ;; Verify it shows up in list
    (let [list-resp (call-server "tools/call"
                                 {:name "agent_list" :arguments {}})
          text (get-in list-resp [:result :content 0 :text])]
      (is (.contains text "test-agent")))
    ;; Cleanup
    (swap! server/agent-registry dissoc :test-agent)))

(deftest test-agent-stop
  (testing "agent_stop removes agent from registry"
    (init-session!)
    (call-server "tools/call"
                 {:name "agent_create"
                  :arguments {:agent_id "stop-me"}})
    (let [stop-resp (call-server "tools/call"
                                 {:name "agent_stop"
                                  :arguments {:agent_id "stop-me"}})]
      (is (false? (get-in stop-resp [:result :isError]))))
    ;; Verify it's gone
    (let [list-resp (call-server "tools/call"
                                 {:name "agent_list" :arguments {}})
          text (get-in list-resp [:result :content 0 :text])]
      (is (= "No active agents." text)))))

(deftest test-dynamic-tool-registration
  (testing "register-tool! and unregister-tool!"
    (init-session!)
    (let [initial-count (count @server/tool-definitions)]
      (server/register-tool!
       {:name "test_dynamic_tool"
        :description "A dynamically registered tool"
        :inputSchema {:type "object" :properties {}}}
       (fn [_ctx _args]
         {:content [{:type "text" :text "dynamic-response"}]
          :isError false}))
      (try
        (is (= (inc initial-count) (count @server/tool-definitions)))
        ;; Call it
        (let [resp (call-server "tools/call"
                                {:name "test_dynamic_tool" :arguments {}})
              text (get-in resp [:result :content 0 :text])]
          (is (= "dynamic-response" text)))
        (finally
          (server/unregister-tool! "test_dynamic_tool")))
      ;; Verify removed
      (is (= initial-count (count @server/tool-definitions))))))

;; ============================================================================
;; Level 2: TCP Transport Tests
;; ============================================================================

(def ^:private json-mapper (json/object-mapper {:decode-key-fn keyword
                                                :encode-key-fn name}))

(defn- tcp-roundtrip
  "Send a JSON-RPC message over TCP and read the response."
  [^BufferedWriter writer ^BufferedReader reader msg]
  (.write writer (json/write-value-as-string msg json-mapper))
  (.write writer "\n")
  (.flush writer)
  (when-some [line (.readLine reader)]
    (json/read-value line json-mapper)))

(deftest test-tcp-transport
  (testing "TCP transport handles MCP protocol"
    (let [port (+ 19000 (rand-int 1000))]
      (server/start! :port port :bind "127.0.0.1")
      (try
        (is (:running (server/status)))
        (with-open [sock (doto (Socket. "127.0.0.1" port)
                          (.setSoTimeout 5000))
                    reader (BufferedReader. (InputStreamReader. (.getInputStream sock)))
                    writer (BufferedWriter. (OutputStreamWriter. (.getOutputStream sock)))]
          ;; Initialize
          (let [resp (tcp-roundtrip writer reader
                                   {:jsonrpc "2.0" :id 1 :method "initialize"
                                    :params {:protocolVersion "2025-11-25"
                                             :capabilities {}
                                             :clientInfo {:name "tcp-test" :version "1.0"}}})]
            (is (= "dvergr-mcp" (get-in resp [:result :serverInfo :name]))))
          ;; Notification (no response expected)
          (.write writer (json/write-value-as-string
                          {:jsonrpc "2.0" :method "notifications/initialized" :params {}}
                          json-mapper))
          (.write writer "\n")
          (.flush writer)
          ;; Tools list
          (let [resp (tcp-roundtrip writer reader
                                   {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}})]
            (is (= 12 (count (get-in resp [:result :tools]))))))
        (finally
          (server/stop!))))))

;; ============================================================================
;; Level 3: Integration tests (with datahike/spindel)
;; ============================================================================

(deftest ^:integration test-runtime-fork-merge-workflow
  (testing "Fork, transact, query, merge workflow"
    (is true "Placeholder for integration test")))

(deftest ^:integration test-memory-query-with-datahike
  (testing "Datalog query against real datahike database"
    (is true "Placeholder for integration test")))

(deftest ^:integration test-repl-eval
  (testing "Evaluate Clojure code in runtime context"
    (is true "Placeholder for integration test")))

;; ============================================================================
;; Level 4: OpenClaw container tests
;; ============================================================================

(deftest ^:openclaw test-openclaw-can-list-dvergr-tools
  (testing "OpenClaw agent discovers dvergr MCP tools"
    (is true "Placeholder for OpenClaw integration test")))
