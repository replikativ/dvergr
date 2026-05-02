(ns dvergr.agent.phases
  "Phase-based agent execution engine.

   Provides composable abstractions for structuring agent work:
   - run-turns: core turn loop (budget-controlled, no max-turns)
   - narrow-tools: filter tool registry to a subset
   - self-check-loop: test-feedback pattern for agent self-correction
   - run-phase: inject prompt + narrow tools + run-turns + optional self-check
   - run-workflow: sequential phase composition with artifact passing

   Phase definitions live in resources/phases/*.edn and are reprogrammable
   by meta agents editing those files in worktrees."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [dvergr.chat.context :as chat-ctx]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.chat.tool-schema :as tool-schema]
            [dvergr.tools :as tools]))

;; ============================================================================
;; Phase Completion Tool
;; ============================================================================

(defn- make-phase-complete-tool
  "Create a lightweight complete_phase tool for explicit phase completion.

   When the agent calls this, it sets the completed atom to true.
   run-turns sees this via on-complete and stops the loop.

   Much simpler than feedback.clj's signal_completion — no mailbox,
   no manager, no deferred. Just 'I'm done with this phase.'"
  [completed-atom]
  {"complete_phase"
   {:name "complete_phase"
    :description "Signal that you have completed the current phase. Call this when you are done with the phase's objectives."
    :parameters {:type "object"
                 :properties {:summary {:type "string"
                                        :description "Brief summary of what you accomplished in this phase"}}
                 :required ["summary"]}
    :execute (fn [params _ctx]
               (reset! completed-atom true)
               {:content (str "Phase completed: " (:summary params))
                :metadata {:phase-complete true}})}})

;; ============================================================================
;; Core Turn Loop
;; ============================================================================

(defn run-turns
  "Run agent turns until natural completion.

   This is the core turn loop used by both standard agent execution and
   phase-based workflows. Termination is controlled by:
   - Budget exhaustion (primary control)
   - Abort predicate (cancellation, status changes)
   - Agent completing (no tool calls)
   - Error

   No max-turns cap — budget and timeouts (via comb/timeout) handle limits.

   Options:
   - :provider, :model — LLM config
   - :tools — tool registry map (already narrowed if needed)
   - :tool-ctx — tool execution context
   - :on-text — optional streaming text callback
   - :on-complete — optional (fn [chat-ctx turn]) called when agent completes
                    naturally. Return :continue to keep going, anything else
                    to stop. Default: stop on completion.
   - :abort? — optional (fn []) predicate checked each iteration. When truthy,
               returns {:status :cancelled :turns turn}.
   - :run-turn-fn — optional (fn [chat-ctx opts]) to execute a single turn.
                    Defaults to chat-agent/run-agent-turn!. Useful for testing.

   Returns {:status :turns}."
  [chat-ctx {:keys [provider model tools tool-ctx on-text on-complete abort? run-turn-fn]
             :or {on-text (fn [_])}}]
  (let [run-turn (or run-turn-fn chat-agent/run-agent-turn!)]
    (loop [turn 0]
      (cond
        ;; External abort (cancellation, status change)
        (and abort? (abort?))
        {:status :cancelled :turns turn}

        ;; Budget exceeded
        (chat-ctx/budget-exceeded? chat-ctx)
        {:status :budget-exceeded :turns turn}

        ;; Run a turn
        :else
        (let [result (run-turn chat-ctx
                               {:provider provider
                                :model model
                                :tools tools
                                :tool-ctx tool-ctx
                                :on-text on-text})]
        ;; Check abort after turn too
        (if (and abort? (abort?))
          {:status :cancelled :turns (inc turn)}

          (case result
            :error
            {:status :error :turns (inc turn)}

            :complete
            (if (and on-complete (= :continue (on-complete chat-ctx (inc turn))))
              (recur (inc turn))
              {:status :complete :turns (inc turn)})

            :continue
            (recur (inc turn)))))))))

;; ============================================================================
;; Phase Loading
;; ============================================================================

(defn load-phase
  "Load a phase definition from resources/phases/<name>.edn.
   Returns the phase map or nil if not found."
  [phase-id]
  (when-let [resource (io/resource (str "phases/" (name phase-id) ".edn"))]
    (edn/read-string (slurp resource))))

(defn load-phases
  "Load multiple phase definitions by keyword. Returns map of id -> phase-def."
  [phase-ids]
  (into {} (keep (fn [id]
                   (when-let [phase (load-phase id)]
                     [id phase]))
                 phase-ids)))

;; ============================================================================
;; Tool Narrowing
;; ============================================================================

(defn narrow-tools
  "Filter a tool registry map to only the tools specified in tool-set.
   tool-set is a set of keywords like #{:read_file :glob :grep}.
   Tool names in the registry are strings."
  [all-tools tool-set]
  (if (or (nil? tool-set) (empty? tool-set))
    all-tools
    (let [allowed-names (set (map name tool-set))]
      (into {} (filter (fn [[tool-name _]]
                         (contains? allowed-names tool-name))
                       all-tools)))))

;; ============================================================================
;; Self-Check
;; ============================================================================

(defn run-self-check
  "Run a self-check tool and return the result.
   Returns {:passed true/false :result <tool-result>}."
  [check-tool-name tool-ctx all-tools]
  (if-let [check-tool (get all-tools check-tool-name)]
    (let [result (try
                   ((:execute check-tool) {} tool-ctx)
                   (catch Exception e
                     {:type :error :error (.getMessage e)}))]
      {:passed (and (not= :error (:type result))
                    (zero? (get-in result [:metadata :errors] 0))
                    (zero? (get-in result [:metadata :failed] 0)))
       :result result})
    {:passed true :result {:content "Check tool not available, skipping."}}))

(defn self-check-loop
  "Run self-check after agent signals completion.

   Extracts the test-feedback pattern from coding.clj as a reusable function:
   1. Run check tool (clj_kondo, run_tests, or custom)
   2. If fails: inject feedback message, return :continue
   3. If passes: return :passed
   4. Max retries respected

   Returns :passed or :max-retries-exceeded."
  [chat-ctx tool-ctx all-tools {:keys [test-tool max-retries] :or {max-retries 3}}]
  (loop [retry 0]
    (if (>= retry max-retries)
      :max-retries-exceeded
      (let [{:keys [passed result]} (run-self-check (or test-tool "clj_kondo") tool-ctx all-tools)]
        (if passed
          :passed
          (do
            ;; Inject feedback message for the agent
            (chat-ctx/add-message! chat-ctx
                                   {:role :user
                                    :content (str "## Automatic Check Feedback (attempt " (inc retry) "/" max-retries ")\n\n"
                                                  (if (:error result)
                                                    (str "Check failed to run:\n```\n" (:error result) "\n```\n\n")
                                                    (str "Check found issues:\n```\n" (:content result) "\n```\n\n"))
                                                  "Please fix the issues and continue.")})
            (recur (inc retry))))))))

;; ============================================================================
;; Phase Execution
;; ============================================================================

(defn- tool-use-stable?
  "Check if the agent has stopped using tools (last assistant message has no tool uses).
   Used for :tool-use-stable completion criteria."
  [chat-ctx]
  (let [messages (chat-ctx/get-messages chat-ctx)
        last-assistant (->> messages
                            (filter #(= :assistant (:message/role %)))
                            last)]
    (and last-assistant
         (empty? (:message/tool-uses last-assistant)))))

(defn- extract-last-artifact
  "Extract the last assistant message content as an artifact."
  [chat-ctx]
  (->> (chat-ctx/get-messages chat-ctx)
       (filter #(= :assistant (:message/role %)))
       last
       :message/content))

(defn run-phase
  "Run a single phase of agent execution.

   Thin wrapper: injects phase prompt, narrows tools, calls run-turns,
   applies completion criteria (self-check, tool-use-stable).

   Returns {:status :phase-id :turns :artifact}.

   Completion criteria:
   - :explicit — phase ends when agent stops naturally (no tool calls)
   - :tool-use-stable — phase ends when agent stops calling tools
   - :signal — agent calls complete_phase tool to end the phase (most reliable)
   - :self-check — runs self-check-loop after natural completion

   Budget is the primary cost control.
   Use comb/timeout to add time-based limits externally."
  [chat-ctx {:keys [provider model tools tool-ctx on-turn] :as agent-opts} phase-def]
  (let [{:keys [id system-prompt-suffix completion-criteria
                self-check output-as]} phase-def

        ;; For :signal completion, create a complete_phase tool + atom
        phase-complete-atom (atom false)
        signal-tool (when (= :signal completion-criteria)
                      (make-phase-complete-tool phase-complete-atom))

        ;; Register complete_phase in global registry + datahike schema
        ;; (needed for tool-use serialization in chat messages)
        _ (when signal-tool
            (let [tool-def (get signal-tool "complete_phase")]
              (tools/register! tool-def)
              (when-let [conn (:db-conn chat-ctx)]
                (tool-schema/install-tool-schema! conn tool-def))))

        ;; Narrow tools, then merge in complete_phase tool if :signal
        phase-tools (cond-> (narrow-tools tools (:tools phase-def))
                      signal-tool (merge signal-tool))

        ;; Build on-complete handler based on completion criteria
        on-complete (case completion-criteria
                      :tool-use-stable
                      (fn [ctx _turn]
                        (if (tool-use-stable? ctx) nil :continue))

                      :signal
                      ;; For :signal, agent completing without calling complete_phase
                      ;; means it stopped early — prompt it to use the tool
                      (fn [ctx _turn]
                        (if @phase-complete-atom
                          nil ;; Tool was called, stop
                          (do
                            (chat-ctx/add-message! ctx
                              {:role :user
                               :content "Please call the `complete_phase` tool when you are done with this phase."})
                            :continue)))

                      ;; :explicit and :self-check both stop on natural completion
                      nil)]

    ;; Inject phase transition message
    (chat-ctx/add-message! chat-ctx
                           {:role :user
                            :content (str "## Phase: " (name id) "\n\n"
                                          system-prompt-suffix
                                          (when (= :signal completion-criteria)
                                            "\n\nWhen you are done with this phase, call the `complete_phase` tool."))})

    ;; Run turns with narrowed tools
    (let [result (run-turns chat-ctx
                            (cond-> {:provider provider
                                     :model model
                                     :tools phase-tools
                                     :tool-ctx tool-ctx
                                     :on-text (or on-turn (fn [_]))
                                     :on-complete on-complete}
                              (:run-turn-fn agent-opts)
                              (assoc :run-turn-fn (:run-turn-fn agent-opts))))]

      ;; Post-completion: self-check if needed
      (if (and (= :complete (:status result))
               (= :self-check completion-criteria))
        (let [check-result (self-check-loop chat-ctx tool-ctx tools self-check)]
          (assoc result
                 :phase-id id
                 :status (if (= :passed check-result) :complete :complete-with-issues)
                 :artifact (when (= output-as :artifact) (extract-last-artifact chat-ctx))))
        ;; No self-check — return with phase metadata
        (assoc result
               :phase-id id
               :artifact (when (= output-as :artifact) (extract-last-artifact chat-ctx)))))))

;; ============================================================================
;; Workflow Execution
;; ============================================================================

(defn make-workflow
  "Create a workflow from a vector of phase keywords.
   Loads phase definitions from resources/phases/.

   Example:
     (make-workflow [:explore :plan :implement :verify])"
  [phase-ids]
  (let [phase-defs (load-phases phase-ids)]
    {:phases phase-ids
     :phase-defs phase-defs}))

(defn run-workflow
  "Run a complete workflow — a sequence of phases.

   Passes artifacts between phases: if a phase produces an :artifact,
   it's injected as context into the next phase.

   Returns {:status :phases-completed :total-turns :artifacts :phase-results}.

   The agent-opts map should contain:
   - :provider, :model — LLM config
   - :tools — full tool registry map
   - :tool-ctx — tool execution context
   - :on-turn — optional text callback"
  [chat-ctx agent-opts {:keys [phases phase-defs]}]
  (loop [remaining-phases phases
         phase-results []
         total-turns 0
         artifacts {}]
    (if (empty? remaining-phases)
      {:status :complete
       :phases-completed (count phases)
       :total-turns total-turns
       :artifacts artifacts
       :phase-results phase-results}

      (let [phase-id (first remaining-phases)
            phase-def (get phase-defs phase-id)]
        (if-not phase-def
          ;; Skip unknown phases
          (do
            (println "[WORKFLOW] Warning: unknown phase" phase-id ", skipping")
            (recur (rest remaining-phases) phase-results total-turns artifacts))

          ;; Inject artifact from previous phase if available
          (let [prev-artifact (last (vals artifacts))
                _ (when prev-artifact
                    (chat-ctx/add-message! chat-ctx
                                           {:role :user
                                            :content (str "## Artifact from previous phase\n\n"
                                                          prev-artifact)}))
                result (run-phase chat-ctx agent-opts phase-def)
                new-artifacts (if (:artifact result)
                                (assoc artifacts phase-id (:artifact result))
                                artifacts)]
            (if (#{:error :budget-exceeded} (:status result))
              ;; Stop workflow on error or budget
              {:status (:status result)
               :phases-completed (count phase-results)
               :total-turns (+ total-turns (:turns result 0))
               :artifacts new-artifacts
               :phase-results (conj phase-results result)
               :failed-phase phase-id}

              ;; Continue to next phase
              (recur (rest remaining-phases)
                     (conj phase-results result)
                     (+ total-turns (:turns result 0))
                     new-artifacts))))))))
