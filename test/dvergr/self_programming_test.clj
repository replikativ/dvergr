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
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.channels.core :as ch]
            [dvergr.tools :as tools]
            [dvergr.mcp.server :as mcp]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- with-daemon-ctx
  "Run thunk with the daemon's execution-context bound — needed for
   ctx-scoped state lookups (sessions, registry, …) to see what the
   daemon's dispatch path wrote."
  [d thunk]
  (binding [ec/*execution-context* (or (:execution-ctx d)
                                       ec/*execution-context*)]
    (thunk)))

;; ============================================================================
;; Fixtures
;; ============================================================================

(use-fixtures :each
  (fn [f]
    (let [orig-channels @ch/channels
          orig-tools @tools/registry
          orig-mcp-tools @mcp/tool-definitions
          orig-mcp-handlers @mcp/tool-handlers]
      (try
        (f)
        (finally
          (reset! ch/channels orig-channels)
          (reset! tools/registry orig-tools)
          (reset! mcp/tool-definitions orig-mcp-tools)
          (reset! mcp/tool-handlers orig-mcp-handlers))))))

;; ============================================================================
;; Helpers — access private fns via var deref
;; ============================================================================

;; Agent addressing + listing moved to dvergr.channels.telegram (the medium
;; that owns the /agents + /<agent> grammar).
(def ^:private parse-agent-command
  @(resolve 'dvergr.channels.telegram/parse-command))

(def ^:private load-agent-prompt
  @(resolve 'dvergr.orchestration.daemon/load-agent-prompt))

(def ^:private format-agent-list
  @(resolve 'dvergr.channels.telegram/format-agent-list))

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
    (is (nil? (parse-agent-command "/approve do something")))
    (is (nil? (parse-agent-command "/reject all")))
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
    (let [d (daemon/start! {:db-path (str (System/getProperty "java.io.tmpdir")
                                          "/dvergr-sp-test-" (random-uuid))
                            :agents {:worker {:provider :fireworks
                                              :model "test"
                                              :tags #{:worker}
                                              :description "Test worker"}}})]
      (with-daemon-ctx d
        (fn []
          (let [text (format-agent-list)]
            (is (str/includes? text "worker"))
            (is (str/includes? text "Test worker"))
            (is (str/includes? text "#worker")))))
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

(deftest test-daemon-profile-loading-at-creation
  (testing "resolve-system-prompt loads the system-prompt from profile markdown"
    ;; Pure resolution logic — explicit prompt wins, else profile markdown.
    (let [sys-prompt (daemon/resolve-system-prompt {:id :worker :profile :worker})]
      (is (some? sys-prompt) "System prompt should be loaded from profile")
      (is (str/includes? (str sys-prompt) "Worker Agent")
          "Should contain markdown profile content"))))

(deftest test-daemon-explicit-prompt-overrides-profile
  (testing "Explicit :system-prompt takes precedence over :profile"
    (is (= "Custom prompt"
           (daemon/resolve-system-prompt {:id :worker :profile :worker
                                          :system-prompt "Custom prompt"})))))

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
