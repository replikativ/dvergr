(ns dvergr.core
  "Main public API for Dvergr agent orchestration.

   This namespace provides the core functions for:
   - Creating and configuring agents
   - Starting agent tasks (ask/spawn)
   - Managing agent results (merge/discard)
   - Extracting and analyzing results

   For REPL-specific conveniences and interactive features, see dvergr.repl.
   For pre-built workflow patterns, see dvergr.workflows."
  (:require [dvergr.agent.config :as agent-core]
            [dvergr.agent.task :as prim]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [dvergr.git :as git]
            [yggdrasil.protocols :as ygg-proto]
            ;; Intake tools — loaded for side-effect registration
            dvergr.intake.hn
            dvergr.intake.lobsters
            dvergr.intake.reddit
            dvergr.intake.devto
            dvergr.intake.github-intake
            dvergr.intake.mastodon
            dvergr.intake.bluesky))

;; ============================================================================
;; Runtime Management
;; ============================================================================

(defonce ^{:dynamic true
           :doc "Global runtime for Dvergr sessions."}
  *runtime* nil)

(defonce ^{:dynamic true
           :doc "Global YggRef to git system (if enabled)."}
  *ygit* nil)

(defonce ^{:dynamic true
           :doc "Global YggRef to datahike system (if app-conn provided)."}
  *ydb* nil)

(defn init
  "Initialize Dvergr runtime.

   Options:
   - :with-git?   - Enable git isolation (default: true)
   - :repo-path   - Git repository path (default: current directory)

   Example:
     (init)                          ; Default: git enabled
     (init :with-git? false)         ; Without git isolation
     (init :repo-path \"/path/to/repo\")"
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

    rt))

(defn runtime
  "Get current runtime."
  ([]
   (or *runtime*
       (throw (ex-info "No runtime. Call (init) first." {}))))
  ([rt]
   (binding [*runtime* rt]
     rt)))

(defn shutdown
  "Shutdown Dvergr runtime and clean up resources."
  []
  (alter-var-root #'*runtime* (constantly nil))
  (alter-var-root #'*ygit* (constantly nil))
  (alter-var-root #'rtc/*execution-context* (constantly nil)))

;; ============================================================================
;; Pre-configured Agents
;; ============================================================================

(def coder
  "Pre-configured coder agent with SCI isolation (safe, sandboxed).
   Has access to all coding tools except shell. Use run_tests for testing.
   Safe for automated workflows."
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
   Uses SCI isolation for safe execution.
   Safe for automated workflows."
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
   Can analyze code, run static analysis, and execute tests.
   Safe for automated workflows."
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
;; Core Agent Operations
;; ============================================================================

(defn ask
  "Ask agent to perform a task. Blocks until completion.

   Returns agent result map with:
   - :result - Agent's final text response
   - :tool-uses - Sequence of tool calls made
   - :child-ctx - Child execution context (for isolation)
   - :status - :success, :error, or :cancelled

   Options:
   - :budget - Token budget for this task
   - :auto-merge-on-success? - Automatically merge if successful

   Example:
     (def result (ask coder \"Implement rate limiting\"))
     (extract result)  ; Get final text response"
  [agent task & {:as opts}]
  (binding [rtc/*execution-context* (runtime)]
    (prim/ask! agent task opts)))

(def run
  "Alias for ask. Blocks until completion.
   Usage: (run researcher \"Survey AI news\" :budget 100000)"
  ask)

(defn spawn
  "Start agent on task. Returns immediately with a spin (future-like value).

   Use with @(spawn ...) to block, or pass to combinators for coordination.

   Options:
   - :budget - Token budget for this task

   Example:
     (def spin (spawn coder \"Long task\"))
     ;; ... do other work ...
     (def result @spin)  ; Block for result
     (extract result)"
  [agent task & {:as opts}]
  (binding [rtc/*execution-context* (runtime)]
    (prim/spawn! agent task opts)))

(defn tell
  "Fire-and-forget message to agent. Returns immediately.

   Agent will process message but result is not tracked.
   Useful for notifications or async triggers."
  [agent message & {:as opts}]
  (binding [rtc/*execution-context* (runtime)]
    (prim/tell! agent message opts)))

;; ============================================================================
;; Result Management
;; ============================================================================

(defn merge!
  "Merge agent's work into parent context.

   This commits the agent's changes, including:
   - Git commits (if git isolation enabled)
   - Database transactions (if datahike enabled)

   Example:
     (-> (ask coder \"task\") merge!)"
  [result]
  (binding [rtc/*execution-context* (runtime)]
    (prim/merge! result)))

(defn discard!
  "Discard agent's work without merging.

   Cleans up resources and reverts any changes.
   Safe to call even if merge! was already called."
  [result]
  (binding [rtc/*execution-context* (runtime)]
    (prim/discard! result)))

;; ============================================================================
;; Result Analysis
;; ============================================================================

(defn successful?
  "Check if agent execution completed successfully.

   Returns true if status is :success, false otherwise."
  [result]
  (prim/successful? result))

(defn extract
  "Extract agent's final text response from result.

   Returns the agent's last assistant message content as a string.
   Useful for getting implementation code, summaries, etc."
  [result]
  (prim/extract-result result))

(defn extract-all
  "Extract all assistant text responses in order.

   Returns a sequence of strings, one for each assistant message.
   Useful for seeing the agent's thought process."
  [result]
  (prim/extract-all-text result))

(defn extract-tools
  "Extract all tool uses made by agent.

   Returns a sequence of tool call maps with:
   - :tool - Tool name
   - :input - Input parameters
   - :output - Tool output

   Useful for auditing agent actions."
  [result]
  (prim/extract-tool-uses result))

(defn branch-info
  "Get branch information for agent's work (if git enabled).

   Returns map with:
   - :parent-branch - Parent branch name
   - :child-branch - Agent's branch name
   - :working-dir - Working directory path

   Only available if git isolation was enabled during init."
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
;; Agent Creation
;; ============================================================================

(defn make-agent
  "Create a custom agent with specific configuration.

   Required options:
   - :name - Agent name (required)

   Optional options:
   - :provider - Model provider (:anthropic, :openai, :fireworks)
   - :model - Model identifier
   - :system-prompt - Custom system prompt
   - :isolation - Isolation mode (:native, :sci, :shared-sci)
                 :sci recommended for safety
   - :permissions - Set of permissions #{:use-tools :spawn-agents}

   Returns configured agent ready for ask/spawn.

   Example:
     (def specialist (make-agent {:name \"security-expert\"
                                  :system-prompt \"You specialize in security...\"}))
     (def result (ask specialist \"Review auth.clj\"))"
  [opts]
  (agent-core/make-agent opts))

;; ============================================================================
;; Git Management
;; ============================================================================

(defn current-branch
  "Get the name of the current git branch (if git enabled).

   Returns branch name as keyword, or nil if git not enabled."
  []
  (when *ygit*
    (binding [rtc/*execution-context* (runtime)]
      (ygg-proto/current-branch @*ygit*))))

(defn show-diff
  "Show git diff of agent's changes vs parent branch.

   Displays unified diff of file changes made by the agent.
   Useful for reviewing before merge."
  [result]
  (when-let [child-ctx (:child-ctx result)]
    (binding [rtc/*execution-context* (runtime)]
      (when *ygit*
        (let [parent-sys @*ygit*
              parent-branch (ygg-proto/current-branch parent-sys)
              child-sys (binding [rtc/*execution-context* child-ctx]
                          @*ygit*)
              child-branch (ygg-proto/current-branch child-sys)]
          (println)
          (println (str "Diff: " (name parent-branch) " → " (name child-branch)))
          (println (apply str (repeat 60 "─")))
          (let [diff-result (ygg-proto/diff parent-sys parent-branch child-branch)]
            (println (:diff diff-result)))
          (println (apply str (repeat 60 "─"))))))))

;; ============================================================================
;; Quick Reference
;; ============================================================================

(comment
  ;; === QUICKSTART ===

  (require '[dvergr.core :as r])

  ;; Initialize with git isolation
  (r/init)

  ;; Ask agent - blocks until completion
  (def result (r/ask r/coder "Add a hello function to src/core.clj"))

  ;; Check if successful
  (r/successful? result)

  ;; Extract response
  (r/extract result)

  ;; Review changes before merging
  (r/show-diff result)

  ;; Merge agent's work
  (r/merge! result)

  ;; === SPAWN (NON-BLOCKING) ===

  ;; Start agent in background
  (def spin (r/spawn r/coder "Long running task"))

  ;; Do other work...

  ;; Later, block for result
  (def result @spin)

  ;; === WORKFLOW PATTERNS ===

  ;; Sequential: research → implement
  (def research (r/ask r/researcher "Research JWT authentication"))
  (def impl (r/ask r/coder (str "Implement: " (r/extract research))))
  (r/merge! impl)

  ;; === CUSTOM AGENTS ===

  (def security-expert
    (r/make-agent {:name "security-expert"
                   :system-prompt "You are a security specialist.
                                   Find vulnerabilities and suggest fixes.
                                   Be thorough and explain issues clearly."}))

  (def review (r/ask security-expert "Review authentication code"))

  ;; === CLEANUP ===

  ;; Shutdown runtime
  (r/shutdown)

  ) ;; end comment