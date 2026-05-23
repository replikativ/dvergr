(ns dvergr.repl
  "DEPRECATED: Use dvergr.core instead.

   This namespace provides REPL-specific conveniences and is maintained
   for backward compatibility. New code should use dvergr.core directly."
  (:require [dvergr.core :as core]
            [clojure.string :as str]))

;; ============================================================================
;; Runtime State
;; ============================================================================

(defonce ^{:dynamic true
           :doc "Global runtime for REPL sessions."}
  *runtime* nil)

(defonce ^{:dynamic true
           :doc "Global YggRef to git system (if enabled)."}
  *ygit* nil)

(defonce ^{:dynamic true
           :doc "Global YggRef to datahike system (if app-conn provided)."}
  *ydb* nil)

;; ============================================================================
;; Runtime Management
;; ============================================================================

(defn start
  "Start REPL session with spindel runtime.

   Options:
   - :with-git?   - Enable git isolation (default: true)
   - :repo-path   - Git repository path (default: current directory)

   Example:
     (start)                          ; Default: git enabled
     (start :with-git? false)         ; Without git isolation
     (start :repo-path \"/path/to/repo\")"
  [& {:keys [with-git? repo-path]
      :or {with-git? true}}]
  (let [rt (ctx/create-execution-context)]
    (alter-var-root #'*runtime* (constantly rt))
    (alter-var-root #'rtc/*execution-context* (constantly rt))

    ;; Register git system if enabled
    (when with-git?
      (binding [rtc/*execution-context* rt]
        (let [ygit (ygg/register! (git/create-git-system
                                    :repo-path (or repo-path ".")))]
          (alter-var-root #'*ygit* (constantly ygit)))))

    (println "Dvergr started")
    (when with-git?
      (println "  Git isolation: enabled")
      (binding [rtc/*execution-context* rt]
        (println "  Branch:" (name (ygg-proto/current-branch @*ygit*)))))
    (println "")
    (println "Usage:")
    (println "  (ask coder \"task\")     - Ask agent, get result")
    (println "  (spawn coder \"task\")   - Start agent, get spin")
    (println "  (merge! result)         - Merge agent's work")
    (println "  (discard! result)       - Discard agent's work")
    (println "  (show-diff result)      - Show git diff")
    (println "  (stop)                  - Clean up")
    rt))

(defn stop
  "Stop REPL session and clean up runtime."
  []
  (alter-var-root #'*runtime* (constantly nil))
  (alter-var-root #'*ygit* (constantly nil))
  (alter-var-root #'rtc/*execution-context* (constantly nil))
  (println "Dvergr stopped"))

(defn runtime
  "Get current runtime."
  []
  (or *runtime*
      (throw (ex-info "No runtime. Call (start) first." {}))))

;; ============================================================================
;; Pre-configured Agents
;; ============================================================================

(def coder
  "Pre-configured coder agent with SCI isolation (safe, sandboxed).
   Has access to all coding tools except shell. Use run_tests for testing."
  (agent-core/make-agent
    {:name "coder"
     :provider :fireworks
     :model "accounts/fireworks/models/kimi-k2-thinking"
     :permissions #{:use-tools :spawn-agents}
     :isolation :sci  ; SAFE: SCI sandbox, no shell access
     :system-prompt "You are a skilled programmer. Write clean, idiomatic code.
                     Use run_tests tool to verify your code - you don't have shell access.
                     Test your changes thoroughly. Explain your reasoning."}))

(def researcher
  "Pre-configured researcher agent for information gathering.
   Uses SCI isolation for safe execution."
  (agent-core/make-agent
    {:name "researcher"
     :provider :fireworks
     :model "accounts/fireworks/models/kimi-k2-thinking"
     :permissions #{:use-tools}
     :isolation :sci  ; SAFE: SCI sandbox
     :system-prompt "You are a thorough researcher. Find relevant information,
                     summarize findings clearly, and cite sources when possible."}))

(def reviewer
  "Pre-configured code reviewer agent with SCI isolation.
   Can analyze code, run static analysis, and execute tests."
  (agent-core/make-agent
    {:name "reviewer"
     :provider :fireworks
     :model "accounts/fireworks/models/kimi-k2-thinking"
     :permissions #{:use-tools}
     :isolation :sci  ; SAFE: SCI sandbox
     :system-prompt "You are a meticulous code reviewer. Look for bugs,
                     security issues, performance problems, and style issues.
                     Use clj_kondo and run_tests tools for analysis.
                     Provide actionable feedback."}))

;; ============================================================================
;; Agent Primitives (REPL-friendly wrappers)
;; ============================================================================

(defn ask
  "Ask agent to perform task. Returns a Spin.

   Use @(ask ...) at REPL to block.

   Options:
   - :budget                  - Token budget
   - :auto-merge-on-success?  - Auto-merge if successful

   Example:
     (def result @(ask coder \"Implement rate limiting\"))
     (show-diff result)
     (merge! result)"
  [agent task & {:as opts}]
  (binding [rtc/*execution-context* (runtime)]
    (prim/ask! agent task opts)))

(defn spawn
  "Start agent on task. Returns spin immediately.

   Use with combinators for parallel execution.

   Example:
     (def spin (spawn coder \"Long task\"))
     ;; ... do other work ...
     (def result @spin)
     (merge! result)"
  [agent task & {:as opts}]
  (binding [rtc/*execution-context* (runtime)]
    (prim/spawn! agent task opts)))

(defn tell
  "Fire-and-forget message to agent."
  [agent message & {:as opts}]
  (binding [rtc/*execution-context* (runtime)]
    (prim/tell! agent message opts)))

;; ============================================================================
;; Result Handling
;; ============================================================================

(defn merge!
  "Merge agent's work to parent context.

   Merges git branch and any other yggdrasil systems.

   Example:
     (-> (ask coder \"task\") merge!)"
  [result]
  (binding [rtc/*execution-context* (runtime)]
    (prim/merge! result)))

(defn discard!
  "Discard agent's work without merging.

   Cleans up git branch and other yggdrasil systems.

   Example:
     (-> (ask coder \"task\") discard!)"
  [result]
  (binding [rtc/*execution-context* (runtime)]
    (prim/discard! result)))

(defn show-diff
  "Show git diff of agent's changes vs parent.

   Example:
     (show-diff result)"
  [result]
  (when-let [child-ctx (:child-ctx result)]
    (binding [rtc/*execution-context* (runtime)]
      (when *ygit*
        ;; Get branches
        (let [parent-sys @*ygit*
              parent-branch (ygg-proto/current-branch parent-sys)
              child-sys (binding [rtc/*execution-context* child-ctx]
                          @*ygit*)
              child-branch (ygg-proto/current-branch child-sys)]
          (println "")
          (println (str "Diff: " (name parent-branch) " → " (name child-branch)))
          (println (apply str (repeat 60 "─")))
          (let [diff-result (ygg-proto/diff parent-sys parent-branch child-branch)]
            (println (:diff diff-result)))
          (println (apply str (repeat 60 "─"))))))))

(defn branch-info
  "Get branch information for agent's work."
  [result]
  (when-let [child-ctx (:child-ctx result)]
    (binding [rtc/*execution-context* (runtime)]
      (when *ygit*
        (let [parent-sys @*ygit*
              parent-branch (ygg-proto/current-branch parent-sys)
              child-sys (binding [rtc/*execution-context* child-ctx]
                          @*ygit*)
              child-branch (ygg-proto/current-branch child-sys)]
          {:parent-branch parent-branch
           :child-branch child-branch
           :working-dir (:working-dir result)})))))

;; ============================================================================
;; Combinators
;; ============================================================================

(defn parallel
  "Run spins in parallel, collect all results.

   Example:
     (let [[a b] @(parallel (spawn coder \"task-1\")
                            (spawn coder \"task-2\"))]
       (merge! a)
       (merge! b))"
  [& spins]
  (apply prim/parallel spins))

(defn race
  "Run spins in parallel, return first to complete.

   Example:
     @(race (spawn fast-agent task)
            (spawn slow-agent task))"
  [& spins]
  (apply prim/race spins))

(defn timeout
  "Race spin against deadline.

   Example:
     @(timeout (spawn agent task) 30000 {:status :timeout})"
  [spin timeout-ms timeout-value]
  (prim/timeout spin timeout-ms timeout-value))

(defn sleep
  "Create spin that completes after delay-ms."
  [delay-ms]
  (prim/sleep delay-ms))

;; ============================================================================
;; Result Helpers
;; ============================================================================

(defn successful?
  "Check if agent execution was successful."
  [result]
  (prim/successful? result))

(defn extract
  "Extract final result text from agent result."
  [result]
  (prim/extract-result result))

(defn extract-all
  "Extract all assistant text responses in order."
  [result]
  (prim/extract-all-text result))

(defn extract-tools
  "Extract all tool uses made by assistant."
  [result]
  (prim/extract-tool-uses result))

;; ============================================================================
;; Workflow Patterns
;; ============================================================================

(defn research-then-implement
  "Research → Implement pattern.

   Returns a spin - use @(...) at REPL boundary.

   Example:
     @(research-then-implement \"JWT authentication\")"
  [topic]
  (spin
    (let [;; Research phase
          research-result (await (prim/spawn! researcher (str "Research: " topic)))
          _ (when (successful? research-result)
              (prim/merge! research-result))

          ;; Implementation phase (builds on merged research)
          impl-result (await (prim/spawn! coder
                                          (str "Implement based on research:\n"
                                               (extract research-result)
                                               "\n\nTopic: " topic)))]
      impl-result)))

(defn parallel-then-merge
  "Run multiple agents in parallel, merge all successful results.

   Returns a spin - use @(...) at REPL boundary.

   Example:
     @(parallel-then-merge
        [[coder \"Implement feature A\"]
         [coder \"Implement feature B\"]])"
  [agent-tasks]
  (spin
    (let [spins (mapv (fn [[agent task]]
                        (prim/spawn! agent task))
                      agent-tasks)
          results (await (apply prim/parallel spins))]
      ;; Merge all successful
      (doseq [r results]
        (when (successful? r)
          (prim/merge! r)))
      results)))

(defn try-approaches
  "Try multiple approaches, keep best.

   decision-fn takes results and returns index of winner.
   Returns a spin - use @(...) at REPL boundary.

   Example:
     @(try-approaches
        [[coder \"Implement with approach A\"]
         [coder \"Implement with approach B\"]]
        (fn [results] 0))  ; Pick first"
  [agent-tasks decision-fn]
  (spin
    (let [spins (mapv (fn [[agent task]]
                        (prim/spawn! agent task))
                      agent-tasks)
          results (await (apply prim/parallel spins))
          winner-idx (decision-fn results)]
      ;; Merge winner, discard others
      (doseq [[idx r] (map-indexed vector results)]
        (if (= idx winner-idx)
          (prim/merge! r)
          (prim/discard! r)))
      (nth results winner-idx))))

;; ============================================================================
;; Custom Agent Creation
;; ============================================================================

(defn make-agent
  "Create custom agent.

   Options:
   - :name         - Agent name (required)
   - :isolation    - :native, :sci, :shared-sci (default: :native)
   - :permissions  - Set of permissions
   - :system-prompt - Custom system prompt

   Agents run until natural termination:
   - Budget exhausted (primary control)
   - Task complete
   - Error occurs

   Use FRP combinators for constraints:
   - (timeout (ask! agent task) ms fallback) - deadline
   - (race (ask! a1 t1) (ask! a2 t2)) - first wins

   Example:
     (def specialist (make-agent {:name \"security-expert\"
                                  :system-prompt \"You are a security expert...\"}))"
  [opts]
  (agent-core/make-agent opts))

;; ============================================================================
;; REPL Usage Examples
;; ============================================================================

(comment
  ;; === QUICKSTART ===

  (require '[dvergr.repl :as r])

  ;; Start with git isolation
  (r/start)

  ;; Ask agent - returns result with :child-ctx
  (def result (r/ask r/coder "Add a hello function to src/core.clj"))

  ;; Review diff
  (r/show-diff result)

  ;; Merge or discard
  (r/merge! result)
  ;; or
  (r/discard! result)

  ;; === PARALLEL WORK ===

  ;; Two agents work in parallel
  (let [[a b] @(r/parallel (r/spawn r/coder "Implement feature A")
                      (r/spawn r/coder "Implement feature B"))]
    (r/show-diff a)
    (r/show-diff b)
    (r/merge! a)
    (r/merge! b))

  ;; === AUTO-MERGE ===

  ;; Merge automatically if successful
  (r/ask r/coder "Fix the typo" :auto-merge-on-success? true)

  ;; === WORKFLOWS ===

  ;; Research then implement
  (def impl (r/research-then-implement "rate limiting"))
  (r/show-diff impl)
  (r/merge! impl)

  ;; Try multiple approaches
  (def winner (r/try-approaches
                [[r/coder "Implement with recursion"]
                 [r/coder "Implement with loop"]]
                (fn [results]
                  ;; Pick first successful
                  (or (first (keep-indexed
                               (fn [i r] (when (r/successful? r) i))
                               results))
                      0))))
  (r/extract winner)

  ;; === CUSTOM AGENTS ===

  (def expert (r/make-agent {:name "security-expert"
                             :system-prompt "You specialize in security..."}))
  (def review (r/ask expert "Review auth.clj for vulnerabilities"))

  ;; Stop when done
  (r/stop))
