(ns dvergr.self-programming-test
  "Tests for self-programming agent framework:
   - Agent addressing (parse-agent-command)
   - Markdown agent profiles (load-agent-prompt)
   - Agent list formatting (format-agent-list)
   - Daemon dispatch routing
   - spawn_agent tool
   - Nested agent execution (integration, requires FIREWORKS_API_KEY)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [dvergr.daemon :as daemon]
            [dvergr.registry :as registry]
            [dvergr.sessions :as sessions]
            [dvergr.channels.core :as ch]
            [dvergr.tools :as tools]
            [dvergr.mcp.server :as mcp]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(use-fixtures :each
  (fn [f]
    (let [orig-registry @registry/registry
          orig-sessions @sessions/sessions
          orig-channels @ch/channels
          orig-tools @tools/registry
          orig-mcp-tools @mcp/tool-definitions
          orig-mcp-handlers @mcp/tool-handlers]
      (try
        (f)
        (finally
          (reset! registry/registry orig-registry)
          (reset! sessions/sessions orig-sessions)
          (reset! ch/channels orig-channels)
          (reset! tools/registry orig-tools)
          (reset! mcp/tool-definitions orig-mcp-tools)
          (reset! mcp/tool-handlers orig-mcp-handlers))))))

;; ============================================================================
;; Helpers — access private fns via var deref
;; ============================================================================

(def ^:private parse-agent-command
  @(resolve 'dvergr.daemon/parse-agent-command))

(def ^:private load-agent-prompt
  @(resolve 'dvergr.daemon/load-agent-prompt))

(def ^:private format-agent-list
  @(resolve 'dvergr.daemon/format-agent-list))

;; ============================================================================
;; Unit Tests — parse-agent-command
;; ============================================================================

(deftest test-parse-agents-command
  (testing "/agents returns list-agents command"
    (is (= {:command :list-agents} (parse-agent-command "/agents")))
    (is (= {:command :list-agents} (parse-agent-command "  /agents  ")))
    (is (= {:command :list-agents} (parse-agent-command "/AGENTS")))))

(deftest test-parse-slash-addressed
  (testing "/worker routes to worker agent"
    (let [result (parse-agent-command "/worker research Clojure transducers")]
      (is (= :worker (:agent-id result)))
      (is (= "research Clojure transducers" (:text result)))))

  (testing "/developer routes to developer agent"
    (let [result (parse-agent-command "/developer fix the bug")]
      (is (= :developer (:agent-id result)))
      (is (= "fix the bug" (:text result)))))

  (testing "Agent names support hyphens and underscores"
    (is (= :my-agent (:agent-id (parse-agent-command "/my-agent do X"))))
    (is (= :my_agent (:agent-id (parse-agent-command "/my_agent do X"))))))

(deftest test-parse-at-not-supported
  (testing "@worker is no longer parsed as agent addressing (conflicts with Telegram mentions)"
    (is (nil? (parse-agent-command "@worker research transducers")))))

(deftest test-parse-secretary-commands-passthrough
  (testing "Known secretary commands are not parsed as agent addresses"
    (is (nil? (parse-agent-command "/status")))
    (is (nil? (parse-agent-command "/merge do something")))
    (is (nil? (parse-agent-command "/discard all")))
    (is (nil? (parse-agent-command "/task list clojure files")))))

(deftest test-parse-plain-text
  (testing "Plain text returns nil (use session default)"
    (is (nil? (parse-agent-command "hello world")))
    (is (nil? (parse-agent-command "just a regular message")))
    (is (nil? (parse-agent-command "status")))
    (is (nil? (parse-agent-command "merge")))))

;; ============================================================================
;; Unit Tests — load-agent-prompt
;; ============================================================================

(deftest test-load-agent-prompt
  (testing "Loads existing profiles"
    (is (string? (load-agent-prompt :worker)))
    (is (str/includes? (load-agent-prompt :worker) "Worker Agent"))
    (is (string? (load-agent-prompt :var)))
    (is (str/includes? (load-agent-prompt :var) "Vár"))
    (is (string? (load-agent-prompt :developer)))
    (is (str/includes? (load-agent-prompt :developer) "Self-Programming")))

  (testing "Returns nil for missing profiles"
    (is (nil? (load-agent-prompt :nonexistent)))
    (is (nil? (load-agent-prompt :foobar)))))

;; ============================================================================
;; Unit Tests — format-agent-list
;; ============================================================================

(deftest test-format-agent-list-empty
  (testing "Empty registry"
    (is (= "No agents registered." (format-agent-list)))))

(deftest test-format-agent-list-with-agents
  (testing "Formats registered agents"
    (let [d (daemon/start! {:agents {:worker {:provider :fireworks
                                              :model "test"
                                              :tags #{:worker}
                                              :description "Test worker"}}})]
      (let [text (format-agent-list)]
        (is (str/includes? text "worker"))
        (is (str/includes? text "Test worker"))
        (is (str/includes? text "#worker")))
      (daemon/stop! d))))

;; ============================================================================
;; Unit Tests — spawn_agent tool registration
;; ============================================================================

(deftest test-spawn-agent-registered
  (testing "spawn_agent tool is registered"
    (is (some? (tools/get-tool "spawn_agent"))))

  (testing "spawn_agent has correct parameters"
    (let [tool (tools/get-tool "spawn_agent")
          params (:parameters tool)
          props (:properties params)]
      (is (= ["task"] (:required params)))
      (is (contains? props :task))
      (is (contains? props :profile))
      (is (contains? props :budget)))))

;; ============================================================================
;; Daemon Tests — dispatch routing
;; ============================================================================

(deftest test-dispatch-agents-command
  (testing "/agents sends agent list via response sinks"
    (let [responses (atom [])
          d (daemon/start! {:agents {:var {:provider :fireworks
                                                  :model "test"
                                                  :tags #{:var}
                                                  :description "Primary"}}
                            :default-agent :var})]
      (daemon/register-response-sink! d
        (fn [_agent-id text] (swap! responses conj text)))

      (daemon/dispatch! d {:chat-id 1001 :text "/agents"
                           :from {:username "tester"}})
      (Thread/sleep 100)

      (is (= 1 (count @responses)))
      (is (str/includes? (first @responses) "var"))
      (is (str/includes? (first @responses) "Primary"))

      (daemon/stop! d))))

(deftest test-dispatch-unknown-agent
  (testing "Addressing unknown agent sends error"
    (let [responses (atom [])
          d (daemon/start! {:agents {:var {:provider :fireworks
                                                  :model "test"}}
                            :default-agent :var})]
      (daemon/register-response-sink! d
        (fn [_agent-id text] (swap! responses conj text)))

      (daemon/dispatch! d {:chat-id 1002 :text "/nonexistent do thing"
                           :from {:username "tester"}})
      (Thread/sleep 100)

      (is (= 1 (count @responses)))
      (is (str/includes? (first @responses) "Unknown agent"))
      (is (str/includes? (first @responses) "nonexistent"))

      (daemon/stop! d))))

(deftest test-dispatch-agent-addressing-updates-session
  (testing "Addressing specific agent updates session"
    (let [d (daemon/start! {:agents {:var {:provider :fireworks
                                                  :model "test"
                                                  :tags #{:var}}
                                     :worker    {:provider :fireworks
                                                  :model "test"
                                                  :tags #{:worker}}}
                            :default-agent :var})]

      ;; First dispatch creates session with var
      (daemon/dispatch! d {:chat-id 2001 :text "hello"
                           :from {:username "tester"}})
      (Thread/sleep 100)
      (is (= :var (:agent-id (sessions/get-session 2001))))

      ;; Addressing worker via /worker updates session
      (daemon/dispatch! d {:chat-id 2001 :text "/worker do something"
                           :from {:username "tester"}})
      (Thread/sleep 100)
      (is (= :worker (:agent-id (sessions/get-session 2001))))

      (daemon/stop! d))))

(deftest test-dispatch-plain-text-uses-session-agent
  (testing "Plain text routes to session's current agent"
    (let [d (daemon/start! {:agents {:var {:provider :fireworks
                                                  :model "test"
                                                  :tags #{:var}}}
                            :default-agent :var})]
      ;; Dispatch plain text
      (daemon/dispatch! d {:chat-id 3001 :text "hello there"
                           :from {:username "tester"}})
      (Thread/sleep 100)

      ;; Session should exist with var
      (is (some? (sessions/get-session 3001)))
      (is (= :var (:agent-id (sessions/get-session 3001))))

      (daemon/stop! d))))

(deftest test-daemon-profile-loading-at-creation
  (testing "Agent created with :profile gets system-prompt from markdown"
    (let [d (daemon/start!
              {:agents {:worker {:provider :fireworks
                                 :model "test"
                                 :profile :worker
                                 :tags #{:worker}}}})]
      ;; Config now lives on the registry entry, not on the discourse Participant.
      (let [sys-prompt (get-in (registry/lookup :worker) [:config :system-prompt])]
        (is (some? sys-prompt) "System prompt should be loaded from profile")
        (is (str/includes? (str sys-prompt) "Worker Agent")
            "Should contain markdown profile content"))
      (daemon/stop! d))))

(deftest test-daemon-explicit-prompt-overrides-profile
  (testing "Explicit :system-prompt takes precedence over :profile"
    (let [d (daemon/start!
              {:agents {:worker {:provider :fireworks
                                 :model "test"
                                 :profile :worker
                                 :system-prompt "Custom prompt"
                                 :tags #{:worker}}}})]
      (let [sys-prompt (get-in (registry/lookup :worker) [:config :system-prompt])]
        (is (= "Custom prompt" sys-prompt)))
      (daemon/stop! d))))

;; ============================================================================
;; Integration Tests — require FIREWORKS_API_KEY
;;
;; These exercise end-to-end delegation against the live Fireworks API; the
;; legacy versions used dvergr.agent.task primitives, the current versions
;; use dvergr.discourse + dvergr.discourse.llm/llm-agent + dvergr.discourse/hire.
;; Skipped by default; opt in with kaocha --focus-meta :integration.
;; ============================================================================

(deftest ^:integration test-single-agent-eval
  (testing "Single agent completes a clojure_eval task via discourse"
    (require 'dvergr.discourse 'dvergr.discourse.llm
             'org.replikativ.spindel.engine.context
             'org.replikativ.spindel.engine.core)
    (let [d         (resolve 'dvergr.discourse/room)
          hire      (resolve 'dvergr.discourse/hire)
          llm-agent (resolve 'dvergr.discourse.llm/llm-agent)
          create-ctx (resolve 'org.replikativ.spindel.engine.context/create-execution-context)
          ctx       (create-ctx)
          room      (binding [org.replikativ.spindel.engine.core/*execution-context* ctx]
                      (d :integration-test ctx))
          worker    (binding [org.replikativ.spindel.engine.core/*execution-context* ctx]
                      (llm-agent
                        {:id     :test-eval
                         :spec   {:provider      :fireworks
                                  :model         "accounts/fireworks/models/minimax-m2p5"
                                  :system-prompt "Use clojure_eval. Be concise."}
                         :tools  #{"clojure_eval"}
                         :budget {:dollars 0.10}
                         :ctx    ctx}))
          outcome   (binding [org.replikativ.spindel.engine.core/*execution-context* ctx]
                      @(hire room worker
                             {:goal "Evaluate (reduce + (range 100)) with clojure_eval. State the number."
                              :timeout-ms 120000}))]
      (is (= :merged (:status outcome)) "Hire should resolve as :merged")
      (is (str/includes? (str (:content (:reply outcome))) "4950")
          "Reply should contain 4950"))))

(deftest ^:integration test-nested-spawn-agent
  (testing "Orchestrator delegates via the spawn_agent tool"
    (require 'dvergr.discourse 'dvergr.discourse.llm
             'org.replikativ.spindel.engine.context
             'org.replikativ.spindel.engine.core)
    (let [d            (resolve 'dvergr.discourse/room)
          hire         (resolve 'dvergr.discourse/hire)
          llm-agent    (resolve 'dvergr.discourse.llm/llm-agent)
          create-ctx   (resolve 'org.replikativ.spindel.engine.context/create-execution-context)
          ctx          (create-ctx)
          room         (binding [org.replikativ.spindel.engine.core/*execution-context* ctx]
                         (d :integration-test ctx))
          orchestrator (binding [org.replikativ.spindel.engine.core/*execution-context* ctx]
                         (llm-agent
                           {:id     :orchestrator
                            :spec   {:provider      :fireworks
                                     :model         "accounts/fireworks/models/minimax-m2p5"
                                     :system-prompt "You MUST use spawn_agent to delegate. Never do work yourself."}
                            :tools  #{"spawn_agent"}
                            :budget {:dollars 0.50}
                            :ctx    ctx}))
          outcome      (binding [org.replikativ.spindel.engine.core/*execution-context* ctx]
                         @(hire room orchestrator
                                {:goal "Use spawn_agent to have a worker evaluate (+ 21 21) and report the answer."
                                 :timeout-ms 120000}))]
      (is (= :merged (:status outcome)))
      (is (str/includes? (str (:content (:reply outcome))) "42")
          "Final result should contain 42"))))
