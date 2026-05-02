(ns isolation-modes-demo
  "Demonstration of agent isolation modes and agentic workflows.

  Shows three isolation levels:
  - :native - Fast, trusted execution (production code)
  - :sci - Sandboxed execution (untrusted/user-generated code)
  - :shared-sci - Collaborative agents with shared context

  Run with: clj -M:dev -m isolation-modes-demo"
  (:require [is.simm.spindel.runtime.context :as ctx]
            [is.simm.spindel.runtime.core :as rtc]
            [is.simm.spindel.spin.cps :refer [spin]]
            [is.simm.spindel.effects.await :refer [await]]
            [dvergr.agent.core :as agent]
            [dvergr.agent.primitives :as prim]))

;; =============================================================================
;; Example 1: Native vs SCI Isolation
;; =============================================================================

(defn example-1-native-vs-sci
  "Compare native (fast) vs SCI (sandboxed) execution."
  []
  (println "\n=== Example 1: Native vs SCI Isolation ===\n")

  (let [rt (ctx/create-execution-context)]
    (binding [rtc/*execution-context* rt]

      ;; Trusted agent with :native isolation (fast)
      (def trusted-agent
        (agent/make-agent
          {:name "trusted-researcher"
           :isolation :native  ; Direct execution, no sandbox
           :permissions #{:use-tools :spawn-agents}
           :max-turns 3}))

      ;; Untrusted agent with :sci isolation (sandboxed)
      (def untrusted-agent
        (agent/make-agent
          {:name "untrusted-coder"
           :isolation :sci  ; SCI sandbox, isolated runtime
           :permissions #{}  ; No special permissions
           :max-turns 3}))

      (println "Testing trusted agent (native isolation)...")
      (def trusted-result
        (prim/ask! trusted-agent "Explain what native isolation means"))

      (println "\nTrusted agent result:")
      (println "  Status:" (:status trusted-result))
      (println "  Isolation:" (:isolation trusted-result))
      (println "  Turns:" (:turns trusted-result))

      (println "\nTesting untrusted agent (SCI isolation)...")
      (def untrusted-result
        (prim/ask! untrusted-agent "Explain what SCI isolation means"))

      (println "\nUntrusted agent result:")
      (println "  Status:" (:status untrusted-result))
      (println "  Isolation:" (:isolation untrusted-result))
      (println "  Turns:" (:turns untrusted-result))

      {:trusted trusted-result
       :untrusted untrusted-result})))

;; =============================================================================
;; Example 2: Parallel Execution with Mixed Isolation
;; =============================================================================

(defn example-2-parallel-mixed-isolation
  "Run agents with different isolation modes in parallel."
  []
  (println "\n=== Example 2: Parallel Mixed Isolation ===\n")

  (let [rt (ctx/create-execution-context)]
    (binding [rtc/*execution-context* rt]

      (def fast-agent
        (agent/make-agent
          {:name "fast-native"
           :isolation :native
           :max-turns 2}))

      (def safe-agent
        (agent/make-agent
          {:name "safe-sci"
           :isolation :sci
           :max-turns 2}))

      (println "Spawning agents in parallel...")

      ;; Run both in parallel
      (def parallel-result
        (await (prim/parallel
                 (prim/spawn! fast-agent "Task for fast agent")
                 (prim/spawn! safe-agent "Task for safe agent"))))

      (println "\nParallel execution results:")
      (doseq [[idx result] (map-indexed vector parallel-result)]
        (println (str "  Agent " (inc idx) ":"))
        (println "    Name:" (:agent result))
        (println "    Isolation:" (:isolation result))
        (println "    Status:" (:status result)))

      parallel-result)))

;; =============================================================================
;; Example 3: Shared SCI Context for Collaborative Agents
;; =============================================================================

(defn example-3-shared-sci-context
  "Multiple agents sharing a common SCI context for collaboration."
  []
  (println "\n=== Example 3: Shared SCI Context ===\n")

  (let [rt (ctx/create-execution-context)]
    (binding [rtc/*execution-context* rt]

      ;; Create shared SCI context
      ;; TODO: Once full SCI integration is complete, create actual shared context
      ;; For now, agents with :shared-sci will get individual contexts
      (def shared-ctx nil)

      (def agent-1
        (agent/make-agent
          {:name "collaborator-1"
           :isolation :shared-sci
           :max-turns 2}))

      (def agent-2
        (agent/make-agent
          {:name "collaborator-2"
           :isolation :shared-sci
           :max-turns 2}))

      (println "Agent 1 defines a function in shared context...")
      (def result-1
        (prim/ask! agent-1
                   "Define a helper function"
                   {:shared-sci-ctx shared-ctx}))

      (println "Agent 2 uses the function from shared context...")
      (def result-2
        (prim/ask! agent-2
                   "Use the helper function"
                   {:shared-sci-ctx shared-ctx}))

      (println "\nShared context results:")
      (println "  Agent 1 status:" (:status result-1))
      (println "  Agent 2 status:" (:status result-2))
      (println "  Both used isolation:" (:isolation result-1))

      {:agent-1 result-1
       :agent-2 result-2})))

;; =============================================================================
;; Example 4: Research → Code → Test Pipeline with Mixed Isolation
;; =============================================================================

(defn example-4-pipeline-mixed-isolation
  "Realistic workflow: trusted research + sandboxed code + trusted test."
  []
  (println "\n=== Example 4: Research → Code → Test Pipeline ===\n")

  (let [rt (ctx/create-execution-context)]
    (binding [rtc/*execution-context* rt]

      ;; Trusted researcher (native - fast)
      (def researcher
        (agent/make-agent
          {:name "researcher"
           :isolation :native
           :permissions #{:use-tools}
           :max-turns 2}))

      ;; Sandboxed coder (SCI - safe)
      (def coder
        (agent/make-agent
          {:name "coder"
           :isolation :sci
           :permissions #{}  ; No special permissions
           :max-turns 2}))

      ;; Trusted tester (native - fast)
      (def tester
        (agent/make-agent
          {:name "tester"
           :isolation :native
           :permissions #{:use-tools}
           :max-turns 2}))

      (println "Step 1: Research (native)...")
      (def research
        (prim/ask! researcher "Research best practices for JWT auth"))

      (println "\nStep 2: Code (SCI sandbox)...")
      (def code
        (prim/ask! coder
                   (str "Implement based on research: "
                        (prim/extract-result research))))

      (println "\nStep 3: Test (native)...")
      (def tests
        (prim/ask! tester
                   (str "Create tests for: "
                        (prim/extract-result code))))

      (println "\nPipeline results:")
      (println "  Research:" (:isolation research) "-" (:status research))
      (println "  Code:" (:isolation code) "-" (:status code))
      (println "  Tests:" (:isolation tests) "-" (:status tests))

      {:research research
       :code code
       :tests tests})))

;; =============================================================================
;; Example 5: Permission-Based Tool Access
;; =============================================================================

(defn example-5-permission-based-tools
  "Demonstrate tool access control via permissions."
  []
  (println "\n=== Example 5: Permission-Based Tool Access ===\n")

  (let [rt (ctx/create-execution-context)]
    (binding [rtc/*execution-context* rt]

      ;; Agent with restricted tool access
      (def restricted-agent
        (agent/make-agent
          {:name "restricted"
           :isolation :sci
           :tools #{:read-file}  ; Can only read files
           :permissions #{}
           :max-turns 2}))

      ;; Agent with full tool access
      (def admin-agent
        (agent/make-agent
          {:name "admin"
           :isolation :native
           :tools :all  ; Can use all tools
           :permissions #{:admin}
           :max-turns 2}))

      (println "Restricted agent (read-only):")
      (println "  Can use read-file:" (agent/can-use-tool? restricted-agent :read-file))
      (println "  Can use write-file:" (agent/can-use-tool? restricted-agent :write-file))

      (println "\nAdmin agent (full access):")
      (println "  Can use read-file:" (agent/can-use-tool? admin-agent :read-file))
      (println "  Can use write-file:" (agent/can-use-tool? admin-agent :write-file))

      ;; TODO: When tool integration is complete, test actual permission checks
      (println "\nTODO: Demonstrate actual tool permission enforcement")

      {:restricted restricted-agent
       :admin admin-agent})))

;; =============================================================================
;; Example 6: Dynamic Isolation Based on Trust Level
;; =============================================================================

(defn example-6-dynamic-isolation
  "Adjust isolation level based on agent trust score."
  []
  (println "\n=== Example 6: Dynamic Isolation ===\n")

  (defn create-agent-with-trust-level
    "Create agent with isolation level based on trust score."
    [name trust-score]
    (agent/make-agent
      {:name name
       :isolation (cond
                    (>= trust-score 0.9) :native      ; High trust = fast
                    (>= trust-score 0.5) :shared-sci  ; Medium = collaborative
                    :else :sci)                       ; Low = sandboxed
       :max-turns 2}))

  (let [rt (ctx/create-execution-context)]
    (binding [rtc/*execution-context* rt]

      (def high-trust (create-agent-with-trust-level "trusted" 0.95))
      (def medium-trust (create-agent-with-trust-level "neutral" 0.7))
      (def low-trust (create-agent-with-trust-level "untrusted" 0.2))

      (println "Agent isolation based on trust:")
      (println "  Trusted (0.95):" (:isolation high-trust))
      (println "  Neutral (0.70):" (:isolation medium-trust))
      (println "  Untrusted (0.20):" (:isolation low-trust))

      ;; Run tasks with different isolation
      (def results
        (await (prim/parallel
                 (prim/spawn! high-trust "High trust task")
                 (prim/spawn! medium-trust "Medium trust task")
                 (prim/spawn! low-trust "Low trust task"))))

      (println "\nExecution results:")
      (doseq [result results]
        (println (str "  " (:agent result) ": " (:isolation result))))

      results)))

;; =============================================================================
;; Run All Examples
;; =============================================================================

(defn -main
  "Run all isolation mode examples."
  [& args]
  (println "╔════════════════════════════════════════════════════════╗")
  (println "║  Dvergr Agent Isolation Modes Demonstration         ║")
  (println "╚════════════════════════════════════════════════════════╝")

  (try
    ;; NOTE: These examples will work but won't have actual API calls
    ;; unless you have API keys configured

    (example-1-native-vs-sci)
    (example-2-parallel-mixed-isolation)
    (example-3-shared-sci-context)
    (example-4-pipeline-mixed-isolation)
    (example-5-permission-based-tools)
    (example-6-dynamic-isolation)

    (println "\n✅ All examples completed!")
    (println "\nNext steps:")
    (println "  1. Configure API keys to see actual agent execution")
    (println "  2. Integrate tools for permission checking demos")
    (println "  3. Complete SCI runtime integration for full sandboxing")

    (catch Exception e
      (println "\n❌ Error running examples:" (.getMessage e))
      (.printStackTrace e)))

  (shutdown-agents))

(comment
  ;; Run individual examples
  (example-1-native-vs-sci)
  (example-2-parallel-mixed-isolation)
  (example-3-shared-sci-context)
  (example-4-pipeline-mixed-isolation)
  (example-5-permission-based-tools)
  (example-6-dynamic-isolation)

  ;; Run all
  (-main)
  )
