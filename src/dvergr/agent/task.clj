(ns dvergr.agent.task
  "Agent task primitives: ask!, spawn!, tell!

   These wrap the agent turn loop in spindel spins with automatic
   context forking for git/datahike isolation via yggdrasil.

   Key features:
   - Automatic context forking (git branches, datahike isolation)
   - Parent-controlled merge/discard of agent work
   - Tool filtering based on isolation mode
   - Transparent working directory resolution"
  (:require [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [org.replikativ.spindel.core :refer [spin]]
            [org.replikativ.spindel.core :as comb]
            [org.replikativ.spindel.core :refer [await]]
            [dvergr.chat.context :as chat-ctx]
            [dvergr.agent.config :as agent]
            [dvergr.agent.phases :as phases]
            [dvergr.tools :as tools]
            [dvergr.sandbox :as sandbox]
            [yggdrasil.protocols :as ygg-proto]))

;; ============================================================================
;; Working Directory Resolution
;; ============================================================================

(defn- find-addressable-system
  "Find first Addressable system in registered yggdrasil systems."
  []
  (when-let [systems (seq (ygg/registered-systems))]
    (some (fn [[_ sys]]
            (when (satisfies? ygg-proto/Addressable sys) sys))
          systems)))

(defn- get-working-dir
  "Get working directory for current context.

   If an Addressable yggdrasil system is registered, returns its working-path.
   Otherwise returns current directory."
  []
  (if-let [sys (find-addressable-system)]
    (ygg-proto/working-path sys)
    (System/getProperty "user.dir")))

(defn- has-yggdrasil?
  "Check if yggdrasil systems are registered in current context."
  []
  (seq (ygg/registered-systems)))

;; ============================================================================
;; Tool Filtering
;; ============================================================================

(defn- tools-for-agent
  "Filter tools based on agent isolation mode.

   :native agents get full access including shell.
   :sci and :shared-sci agents don't get shell access but can read/write files
   (writes go to forked git worktree via yggdrasil)."
  [agent all-tools]
  (if (= :native (:isolation agent))
    all-tools
    (dissoc all-tools "shell")))

;; ============================================================================
;; Agent Execution
;; ============================================================================

(defn- run-agent-task-spin
  "Run an agent on a task. Returns a Spin that resolves to result.

   Automatically forks execution context (including yggdrasil systems)
   for agent isolation. The result includes :child-ctx for parent
   merge/discard control.

   Delegates turn execution to phases/run-turns (standard mode) or
   phases/run-workflow (workflow mode), eliminating loop duplication."
  [agent task-str {:keys [budget budget-dollars parent-chat-ctx on-turn context-mode setup-sci-fn
                          workflow]}]
  (let [parent-runtime rtc/*execution-context*
        ;; Determine if agent needs SCI isolation
        needs-sci? (#{:sci :shared-sci} (:isolation agent))]
    (spin
      ;; Fork execution context - yggdrasil forked automatically via PForkable
      (let [child-ctx (ctx/fork-context parent-runtime)

            ;; Get working directory (worktree if git registered)
            working-dir (binding [rtc/*execution-context* child-ctx]
                          (get-working-dir))

            ;; Filter tools based on isolation
            agent-tools (tools-for-agent agent @tools/registry)]

        ;; Run agent in forked context
        (binding [rtc/*execution-context* child-ctx]
          ;; Create ChatContext for this agent (with SCI if needed)
          ;; Budget: prefer budget-dollars, fallback to budget (microdollars), default $0.50
          (let [ctx (if parent-chat-ctx
                      (chat-ctx/fork-sub-chat parent-chat-ctx
                                              {:title (str (:name agent) ": "
                                                          (subs task-str 0 (min 30 (count task-str))))
                                               :budget-dollars (or budget-dollars 0.50)
                                               :budget budget})
                      (chat-ctx/create-chat-context
                        {:title (str (:name agent) " task")
                         :budget-dollars (or budget-dollars 0.50)
                         :budget budget
                         :with-sci? needs-sci?}))

                ;; Create tool context with SCI, db-conn, and chat-ctx from ChatContext
                ;; Pass child-ctx as execution-ctx so spawn_agent works in nested agents
                tool-ctx (tools/make-context {:cwd working-dir
                                              :sci-ctx (:sci-ctx ctx)
                                              :db-conn (:db-conn ctx)
                                              :chat-ctx ctx
                                              :isolation (:isolation agent)
                                              :execution-ctx child-ctx})

                ;; Wire agent namespaces into SCI (intake, datahike, sync, file, llm)
                _ (when (and needs-sci? (:sci-ctx ctx))
                    (sandbox/setup-agent-namespaces! (:sci-ctx ctx) child-ctx
                                                     :base-path working-dir))

                ;; Call setup function if provided (e.g., to add app namespaces to SCI)
                _ (when (and setup-sci-fn (:sci-ctx ctx))
                    (setup-sci-fn (:sci-ctx ctx)))

                ;; Build system prompt
                system-prompt (str (or (:system-prompt agent)
                                       (str "You are " (:name agent) ". Complete the task given to you."))
                                   "\n\nWorking directory: " working-dir
                                   (when (has-yggdrasil?)
                                     "\n\nYou are working in an isolated git branch. Your changes are isolated until reviewed."))

                _ (chat-ctx/add-message! ctx {:role :system :content system-prompt})
                _ (chat-ctx/add-message! ctx {:role :user :content task-str})

                isolation-mode (:isolation agent)

                ;; Shared agent opts for turn execution
                agent-opts {:provider (:provider agent)
                            :model (:model agent)
                            :tools agent-tools
                            :tool-ctx tool-ctx
                            :on-text (or on-turn (fn [_]))
                            :abort? (fn []
                                      (or (rtc/spin-is-cancelled?)
                                          (not= :active (chat-ctx/get-status ctx))))}

                ;; Run: workflow mode or standard turns
                turn-result (if workflow
                              (let [wf (if (map? workflow)
                                         workflow
                                         (phases/make-workflow workflow))]
                                (phases/run-workflow ctx agent-opts wf))
                              (phases/run-turns ctx agent-opts))

                ;; Normalize turns count (workflow uses :total-turns)
                turns (or (:total-turns turn-result) (:turns turn-result) 0)
                status (:status turn-result)]

            ;; Complete sub-chat with summary
            (when parent-chat-ctx
              (chat-ctx/complete-sub-chat! ctx
                {:summary (case status
                            :complete (str "Completed in " turns " turns")
                            :budget-exceeded "Budget exceeded"
                            :cancelled (str "Cancelled after " turns " turns")
                            (str (name status) " after " turns " turns"))}))

            (cond-> {:status status
                     :turns turns
                     :agent (:name agent)
                     :isolation isolation-mode
                     :messages (chat-ctx/get-messages ctx)
                     :child-ctx child-ctx
                     :working-dir working-dir}
              (= :complete status)
              (assoc :result (-> (chat-ctx/get-messages ctx) last :message/content))

              (and workflow (:phases-completed turn-result))
              (assoc :workflow-result turn-result))))))))

;; ============================================================================
;; Forward Declarations
;; ============================================================================

(declare successful? merge! discard!)

;; ============================================================================
;; Communication Primitives
;; ============================================================================

(defn ask!
  "Ask an agent to perform a task. Returns a Spin.

   Use @(ask! ...) at REPL to block, or (await (ask! ...)) inside spin.

   Returns spin that resolves to result with :child-ctx for merge/discard.

   Options:
   - :budget-dollars      - Budget in dollars (default: $0.50)
   - :budget              - Budget in microdollars (legacy, 1 USD = 1,000,000 u$)
   - :parent-chat-ctx     - Parent ChatContext to fork from
   - :on-turn             - Callback for each turn
   - :context-mode        - :summary, :full-history, :none (default: :summary)
   - :auto-merge-on-success? - Automatically merge if successful
   - :setup-sci-fn        - Function (fn [sci-ctx]) to customize SCI context
                            before agent runs (e.g., add app namespaces)
   - :workflow            - Vector of phase keywords (e.g. [:explore :implement :verify])
                            or a workflow map from phases/make-workflow.
                            When provided, agent runs through phases with per-phase
                            tool narrowing and self-check loops.

   Example:
     (let [result @(ask! coder \"Implement feature X\")]
       (show-diff result)
       (merge! result))"
  ([agent task] (ask! agent task {}))
  ([agent task opts]
   (if (:auto-merge-on-success? opts)
     ;; Wrap with auto-merge logic inside a spin
     (spin
       (let [result (await (run-agent-task-spin agent task opts))]
         (if (successful? result)
           (merge! result)
           (discard! result))
         result))
     ;; Just return the spin directly
     (run-agent-task-spin agent task opts))))

(defn spawn!
  "Start an agent on a task. Returns Spin immediately.

   The spin resolves to result with :child-ctx for merge/discard.
   Use with combinators for parallel execution.

   Options: same as ask!

   Example:
     (let [[a b] @(all (spawn! agent-1 task-1)
                       (spawn! agent-2 task-2))]
       (merge! a)
       (merge! b))"
  ([agent task] (spawn! agent task {}))
  ([agent task opts]
   (run-agent-task-spin agent task opts)))

(defn tell!
  "Fire-and-forget message to agent. Returns nil.

   The agent runs but result is not tracked.

   Options: same as ask!"
  ([agent message] (tell! agent message {}))
  ([agent message opts]
   (spawn! agent message opts)
   nil))

;; ============================================================================
;; Result Handling (Merge/Discard)
;; ============================================================================

(defn- auto-commit-systems!
  "Auto-commit all Committable yggdrasil systems before merge.
   Systems that don't implement Committable (e.g. Datahike) are skipped."
  [agent-name]
  (doseq [[_ sys] (ygg/registered-systems)]
    (when (satisfies? ygg-proto/Committable sys)
      (try
        (ygg-proto/commit! sys (str "Changes by " (or agent-name "agent")))
        (catch Exception _)))))

(defn merge!
  "Merge agent's work to parent context.

   - Auto-commits all Committable yggdrasil systems first
   - All yggdrasil systems merged via protocol

   Returns the result for chaining."
  [result]
  (when-let [child-ctx (:child-ctx result)]
    ;; Auto-commit all committable systems before merge
    (binding [rtc/*execution-context* child-ctx]
      (auto-commit-systems! (:agent result)))
    (ygg/merge-to-parent! child-ctx))
  result)

(defn discard!
  "Discard agent's work without merging.

   - Git branch deleted
   - Yggdrasil cleanup via protocol

   Returns the result for chaining."
  [result]
  (when-let [child-ctx (:child-ctx result)]
    (ygg/discard-from-parent! child-ctx))
  result)

;; ============================================================================
;; Result Extraction
;; ============================================================================

(defn extract-result
  "Extract the final result text from an agent execution result."
  [agent-result]
  (let [messages (:messages agent-result)]
    (or
      (->> messages
           (filter #(= :assistant (:message/role %)))
           (filter #(or (:message/text %) (:message/content %)))
           (filter #(not (and (empty? (or (:message/text %) ""))
                             (seq (:message/tool-uses %)))))
           last
           (#(or (:message/text %) (:message/content %))))
      (-> messages last :message/content)
      "")))

(defn successful?
  "Check if agent execution was successful."
  [agent-result]
  (= :complete (:status agent-result)))

(defn extract-all-text
  "Extract all assistant text responses in order."
  [agent-result]
  (->> (:messages agent-result)
       (filter #(= :assistant (:message/role %)))
       (map #(or (:message/text %) (:message/content %) ""))
       (filter seq)
       vec))

(defn extract-tool-uses
  "Extract all tool uses made by the assistant."
  [agent-result]
  (->> (:messages agent-result)
       (filter #(= :assistant (:message/role %)))
       (mapcat :message/tool-uses)
       (filter identity)
       (map #(select-keys % [:tool-use/name :tool-use/input]))
       vec))

;; ============================================================================
;; Combinators (re-export from spindel for convenience)
;; ============================================================================

(defn parallel
  "Run spins in parallel, collect all results.

   Example:
     @(parallel (spawn! a task-1) (spawn! b task-2))"
  [& spins]
  (apply comb/parallel spins))

(defn race
  "Run spins in parallel, return first to complete.

   Example:
     @(race (spawn! fast task) (spawn! slow task))"
  [& spins]
  (apply comb/race spins))

(defn timeout
  "Race a spin against a deadline.

   Example:
     @(timeout (spawn! agent task) 30000 {:status :timeout})"
  [spin timeout-ms timeout-value]
  (comb/timeout spin timeout-ms timeout-value))

(defn sleep
  "Create a spin that completes after delay-ms."
  [delay-ms]
  (comb/sleep delay-ms))

;; ============================================================================
;; Merge From Parent (Parent → Child sync)
;; ============================================================================

(defn merge-from-parent!
  "Merge parent's latest state into a result's forked context.

   Useful for long-lived agent forks that need to pick up changes
   merged by other agents into the parent context.

   Must be called from the parent context (same as merge!/discard!).

   Args:
     result - Agent result map with :child-ctx
     opts   - Optional merge opts {:strategy :ours|:theirs|:union}

   Returns the result for chaining."
  ([result] (merge-from-parent! result {}))
  ([result opts]
   (when-let [child-ctx (:child-ctx result)]
     (ygg/merge-from-parent! child-ctx opts))
   result))
