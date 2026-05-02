(ns dvergr.chat.feedback
  "Feedback loop primitives for agent-manager interaction.

   This module provides FRP-based coordination between:
   - Agent spin: runs turns, signals completion via tool
   - Manager spin: reviews work, provides feedback or approves

   Key primitives:
   - feedback! - Manager gives feedback, agent continues
   - approve! - Manager approves work, agent stops
   - reject! - Manager rejects work, agent stops

   Uses Spindel mailbox for feedback passing and deferred for completion signals."
  (:refer-clojure :exclude [await atom])
  (:require [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.core :refer [spin]]
            [org.replikativ.spindel.core :as sync]
            [org.replikativ.spindel.core :as comb]
            [org.replikativ.spindel.core :refer [await]]
            [org.replikativ.spindel.core :refer [track]]
            [org.replikativ.spindel.atom :as ratom]
            [org.replikativ.spindel.signal :as sig]
            [dvergr.chat.context :as chat-ctx]
            [dvergr.chat.agent :as agent]
            [dvergr.chat.tool-schema :as tool-schema]
            [dvergr.tools :as tools]
            [dvergr.sandbox :as sandbox]))

;; ============================================================================
;; FeedbackContext Record
;; ============================================================================

(defrecord FeedbackContext
    [chat-ctx           ; Underlying ChatContext
     completion-d       ; Spindel Atom<Deferred> - current completion deferred (fork-safe)
     feedback-mbx       ; Mailbox - for manager→agent feedback
     approval-signal    ; Signal<:pending|:approved|:rejected> - reactive status
     iteration-atom     ; Spindel Atom<int> - feedback rounds so far (fork-safe)
     max-iterations     ; int - limit before auto-reject
     completion-signal  ; Signal - last completion result from agent (reactive)
     tool-registry])    ; Local tool registry for this context (avoids global pollution)

;; ============================================================================
;; FeedbackContext Creation
;; ============================================================================

(defn create-feedback-context
  "Create a feedback context wrapping a chat context.

   Args:
     chat-ctx - Existing ChatContext
     opts - {:max-iterations N} (default 5)

   Returns FeedbackContext with:
   - completion-d: Spindel atom holding current completion deferred (fork-safe)
   - feedback-mbx: Mailbox for feedback messages
   - approval-signal: Reactive signal for status tracking
   - completion-signal: Reactive signal for completion data
   - tool-registry: Local tool registry (avoids global pollution)"
  [chat-ctx & {:keys [max-iterations] :or {max-iterations 5}}]
  (binding [rtc/*execution-context* (:spindel-ctx chat-ctx)]
    (map->FeedbackContext
     {:chat-ctx chat-ctx
      ;; Fork-safe atom for the completion deferred (gets replaced on each feedback round)
      :completion-d (ratom/create-atom (sync/deferred))
      ;; Mailbox for manager→agent communication
      :feedback-mbx (sync/mailbox)
      ;; Reactive signal for approval status - spins can track this
      :approval-signal (sig/signal :pending)
      ;; Fork-safe atom for iteration count
      :iteration-atom (ratom/create-atom 0)
      :max-iterations max-iterations
      ;; Reactive signal for completion data - spins can track this
      :completion-signal (sig/signal nil)
      ;; Local tool registry - maps tool name to tool def
      :tool-registry (ratom/create-atom {})})))

;; ============================================================================
;; Completion Tool
;; ============================================================================

(defn make-completion-tool
  "Create the signal_completion tool for an agent.

   When the agent calls this tool, it:
   1. Stores the completion data
   2. Delivers to the completion deferred (wakes manager)
   3. Returns acknowledgment to agent"
  [feedback-ctx]
  {:name "signal_completion"
   :description "Signal that you have completed the assigned task and are ready for review.
You MUST call this tool when you believe your work is done. Do not just stop responding.
After calling this, wait for feedback - you may need to make changes."
   ;; Simple parameters to avoid Datahike schema issues with dynamic tool inputs
   :parameters {:type "object"
                :properties {:summary {:type "string"
                                       :description "Brief summary of what you accomplished"}
                             :confidence {:type "string"
                                          :enum ["high" "medium" "low"]
                                          :description "Your confidence level that the task is complete"}}
                :required ["summary"]}
   :execute (fn [{:keys [summary confidence]} _ctx]
              (binding [rtc/*execution-context* (:spindel-ctx (:chat-ctx feedback-ctx))]
                (let [result {:summary summary
                              :confidence (keyword (or confidence "medium"))
                              :iteration @(:iteration-atom feedback-ctx)}]
                  ;; Store completion in reactive signal
                  (reset! (:completion-signal feedback-ctx) result)

                  ;; Deliver to manager (if deferred exists)
                  (when-let [d @(:completion-d feedback-ctx)]
                    (sync/deliver! d result))

                  ;; Return confirmation
                  {:status "completion_signaled"
                   :message "Your work has been submitted for review. Wait for feedback or approval."})))})

;; ============================================================================
;; Manager Primitives
;; ============================================================================

(defn feedback!
  "Give feedback to the agent.

   This:
   1. Increments iteration count
   2. Creates new completion deferred for next round
   3. Adds feedback as :user message to chat
   4. Posts to feedback mailbox (wakes agent if waiting)

   Returns the new completion deferred that will be delivered
   when the agent signals completion again."
  [feedback-ctx message]
  (let [{:keys [chat-ctx completion-d feedback-mbx iteration-atom]} feedback-ctx
        spindel-ctx (:spindel-ctx chat-ctx)]

    (binding [rtc/*execution-context* spindel-ctx]
      ;; Increment iteration
      (swap! iteration-atom inc)

      ;; Create new completion deferred
      (reset! completion-d (sync/deferred))

      ;; Add feedback to chat history
      (chat-ctx/add-message! chat-ctx
                             {:role :user
                              :content (str "## Feedback (iteration " @iteration-atom ")\n\n" message)
                              :important? true})

      ;; Post to mailbox (wakes agent spin if it's awaiting)
      (feedback-mbx {:type :feedback
                     :message message
                     :iteration @iteration-atom})

      ;; Return the new completion deferred
      @completion-d)))

(defn approve!
  "Approve the agent's work. Stops the agent loop.

   Returns map with :status :approved and iteration count."
  [feedback-ctx]
  (let [{:keys [chat-ctx feedback-mbx approval-signal iteration-atom completion-signal]} feedback-ctx
        spindel-ctx (:spindel-ctx chat-ctx)]

    (binding [rtc/*execution-context* spindel-ctx]
      ;; Update status signal (reactive - watchers notified)
      (reset! approval-signal :approved)

      ;; Update chat status
      (chat-ctx/set-status! chat-ctx :completed)

      ;; Notify agent via mailbox
      (feedback-mbx {:type :approved})

      {:status :approved
       :iterations @iteration-atom
       :result @completion-signal})))

(defn reject!
  "Reject the agent's work. Stops the agent loop.

   Returns map with :status :rejected, reason, and iteration count."
  [feedback-ctx reason]
  (let [{:keys [chat-ctx feedback-mbx approval-signal iteration-atom]} feedback-ctx
        spindel-ctx (:spindel-ctx chat-ctx)]

    (binding [rtc/*execution-context* spindel-ctx]
      ;; Update status signal (reactive - watchers notified)
      (reset! approval-signal :rejected)

      ;; Update chat status
      (chat-ctx/set-status! chat-ctx :completed)

      ;; Notify agent via mailbox
      (feedback-mbx {:type :rejected :reason reason})

      {:status :rejected
       :reason reason
       :iterations @iteration-atom})))

;; ============================================================================
;; Agent Spin Pattern
;; ============================================================================

(defn run-turns-until-completion!
  "Run agent turns until signal_completion tool is called.

   This runs the normal agent turn loop but watches for the
   completion tool to be invoked. Returns when:
   - signal_completion is called
   - Budget exceeded
   - Error occurs"
  [feedback-ctx {:keys [provider model tools tool-ctx on-text] :as opts}]
  (let [{:keys [chat-ctx completion-signal]} feedback-ctx
        spindel-ctx (:spindel-ctx chat-ctx)
        completion-tool (make-completion-tool feedback-ctx)
        ;; Build tools map (keyed by name) as expected by tool-definitions
        base-tools (if tools
                     (vec tools)
                     (tools/all-tools))
        all-tools-vec (conj base-tools completion-tool)
        all-tools-map (into {} (map (juxt :name identity)) all-tools-vec)]

    ;; Install schema for signal_completion tool so Datahike can store its inputs
    (tool-schema/install-tool-schema! (:db-conn chat-ctx) completion-tool)

    ;; Also register in global registry so serialize-tool-use can find it
    ;; (needed for Datahike message storage)
    (tools/register! completion-tool)

    ;; Create SCI context for sandboxed code evaluation
    (let [sci-ctx (sandbox/fork-for-session)
          ;; Create tool context with local tools (no global registration needed!)
          local-tool-ctx (tools/make-context
                          {:cwd (System/getProperty "user.dir")
                           :sci-ctx sci-ctx       ; SCI context for clojure_eval
                           :tools all-tools-map   ; Local tools passed via ctx
                           :chat-ctx chat-ctx})
          initial-completion (binding [rtc/*execution-context* spindel-ctx]
                               @completion-signal)]

      ;; Run turns until completion changes (meaning tool was called)
      (loop [turn-count 0]
        (let [current-completion (binding [rtc/*execution-context* spindel-ctx]
                                   @completion-signal)]
          (cond
            ;; Check if completion was signaled
            (not= initial-completion current-completion)
            {:status :completion-signaled
             :turns turn-count
             :completion current-completion}

            ;; Budget check
            (not (chat-ctx/check-budget! chat-ctx))
            {:status :budget-exceeded
             :turns turn-count}

            ;; Run a turn
            :else
            (let [result (agent/run-agent-turn! chat-ctx
                                                (assoc opts
                                                       :tools all-tools-map
                                                       :tool-ctx local-tool-ctx))]
              (case result
                :continue (recur (inc turn-count))
                :complete {:status :agent-stopped
                           :turns turn-count}
                :error {:status :error
                        :turns turn-count}))))))))

(defn run-agent-with-feedback
  "Run an agent that can receive feedback and iterate.

   The agent:
   1. Runs turns until it calls signal_completion tool
   2. Waits for feedback via mailbox
   3. If feedback received, incorporates it and continues
   4. If approved/rejected, exits

   Returns spin that resolves to final status."
  [feedback-ctx opts]
  (let [{:keys [chat-ctx approval-signal feedback-mbx]} feedback-ctx
        spindel-ctx (:spindel-ctx chat-ctx)]
    (binding [rtc/*execution-context* spindel-ctx]
      (spin
        (loop []
          ;; Check if we should continue
          (if (not= :pending @approval-signal)
            ;; Already resolved
            {:status @approval-signal}

            ;; Run turns until completion signaled
            (let [turn-result (run-turns-until-completion! feedback-ctx opts)]
              (case (:status turn-result)
                ;; Completion signaled - wait for manager response
                :completion-signaled
                (let [response (await feedback-mbx)]
                  (case (:type response)
                    :feedback (recur)  ; Continue with feedback
                    :approved {:status :approved}
                    :rejected {:status :rejected :reason (:reason response)}
                    (recur)))  ; Unknown, keep going

                ;; Budget exceeded
                :budget-exceeded
                {:status :budget-exceeded}

                ;; Agent stopped without completion tool
                :agent-stopped
                (do
                  ;; Prompt agent to use the tool
                  (chat-ctx/add-message! chat-ctx
                                         {:role :user
                                          :content "Please use the signal_completion tool to submit your work for review."})
                  (recur))

                ;; Error
                :error
                {:status :error}))))))))

;; ============================================================================
;; Manager Spin Pattern
;; ============================================================================

(defn run-manager-review
  "Run manager review loop.

   The manager:
   1. Waits for agent to signal completion
   2. Calls review-fn to evaluate the work
   3. Either approves or provides feedback
   4. Repeats until approved or max iterations

   review-fn signature: (fn [feedback-ctx completion] -> {:approved true} or {:feedback \"...\"})

   Returns spin that resolves to final status."
  [feedback-ctx review-fn]
  (let [{:keys [chat-ctx completion-d max-iterations iteration-atom]} feedback-ctx
        spindel-ctx (:spindel-ctx chat-ctx)]
    (binding [rtc/*execution-context* spindel-ctx]
      (spin
        (loop []
          ;; Wait for agent to signal completion
          (let [completion (await @completion-d)]

            ;; Review the work
            (let [review-result (review-fn feedback-ctx completion)]
              (cond
                ;; Approved!
                (:approved review-result)
                (approve! feedback-ctx)

                ;; Too many iterations
                (>= @iteration-atom max-iterations)
                (reject! feedback-ctx "Maximum iterations exceeded")

                ;; Needs work - give feedback and continue
                :else
                (do
                  (feedback! feedback-ctx (:feedback review-result))
                  (recur))))))))))

;; ============================================================================
;; High-Level API
;; ============================================================================

(defn run-with-review
  "Run an agent task with manager review loop.

   This is the main entry point for feedback-driven agent execution.

   Parameters:
   - task: The task description string
   - opts: Map with:
     - :provider - LLM provider (:anthropic, :openai, :fireworks)
     - :model - Model identifier
     - :review-fn - (fn [feedback-ctx completion] -> {:approved true} or {:feedback \"...\"})
     - :max-iterations - Max feedback rounds (default 5)
     - :budget-dollars - Budget in dollars (default 1.0)
     - :system-prompt - Optional custom system prompt
     - :on-text - Optional streaming callback

   Returns spin that resolves to:
   {:status :approved | :rejected | :budget-exceeded | :error
    :result completion-data
    :iterations feedback-round-count
    :chat-id uuid
    :messages [...all messages...]}"
  [task {:keys [provider model review-fn max-iterations budget-dollars system-prompt on-text]
         :or {provider :anthropic
              model "claude-sonnet-4-20250514"
              max-iterations 5
              budget-dollars 1.0}}]
  (let [;; Create chat context
        chat-ctx (chat-ctx/create-chat-context
                  {:title (str "Task: " (subs task 0 (min 50 (count task))))
                   :budget-dollars budget-dollars})

        ;; Create feedback context
        feedback-ctx (create-feedback-context chat-ctx :max-iterations max-iterations)

        spindel-ctx (:spindel-ctx chat-ctx)]

    ;; Add system prompt and task OUTSIDE spin (synchronous)
    (chat-ctx/add-message! chat-ctx
                           {:role :system
                            :content (or system-prompt
                                         "You are a helpful coding assistant. When you complete a task, you MUST call the signal_completion tool to submit your work for review.")})
    (chat-ctx/add-message! chat-ctx
                           {:role :user
                            :content task})

    ;; Create the component spins OUTSIDE the outer spin
    ;; This avoids nested spin creation which breaks CPS bindings
    (let [agent-opts {:provider provider
                      :model model
                      :on-text on-text}
          agent-spin (run-agent-with-feedback feedback-ctx agent-opts)
          manager-spin (run-manager-review feedback-ctx review-fn)]

      ;; Now create outer spin that awaits the race
      (binding [rtc/*execution-context* spindel-ctx]
        (spin
          (let [result (await (comb/race manager-spin agent-spin))]

            ;; Return final result
            {:status (:status result)
             :result @(:completion-signal feedback-ctx)
             :iterations @(:iteration-atom feedback-ctx)
             :chat-id (:chat-id chat-ctx)
             :messages (chat-ctx/get-messages chat-ctx)}))))))

;; ============================================================================
;; REPL Helpers
;; ============================================================================

(defn start-task
  "Start a task for manual review from the REPL.

   Returns feedback-ctx that you can use with:
   - (get-completion fctx) - see last completion
   - (feedback! fctx \"message\") - give feedback
   - (approve! fctx) - approve and finish
   - (reject! fctx \"reason\") - reject and finish

   The agent runs in the background."
  [task {:keys [provider model budget-dollars]
         :or {provider :anthropic
              model "claude-sonnet-4-20250514"
              budget-dollars 1.0}}]
  (let [chat-ctx (chat-ctx/create-chat-context
                  {:title (str "Task: " (subs task 0 (min 50 (count task))))
                   :budget-dollars budget-dollars})
        feedback-ctx (create-feedback-context chat-ctx)
        spindel-ctx (:spindel-ctx chat-ctx)]

    ;; Add system prompt and task
    (chat-ctx/add-message! chat-ctx
                           {:role :system
                            :content "You are a helpful coding assistant. When you complete a task, you MUST call the signal_completion tool to submit your work for review."})
    (chat-ctx/add-message! chat-ctx
                           {:role :user
                            :content task})

    ;; Start agent in background (it will signal completion and wait)
    (binding [rtc/*execution-context* spindel-ctx]
      (let [agent-spin (run-agent-with-feedback feedback-ctx
                                                 {:provider provider
                                                  :model model})]
        ;; Deref in background thread to not block REPL
        (future
          (try
            @agent-spin
            (catch Exception e
              (println "Agent error:" (.getMessage e)))))))

    ;; Return feedback context for manual interaction
    feedback-ctx))

(defn get-completion
  "Get the last completion from a feedback context."
  [feedback-ctx]
  (binding [rtc/*execution-context* (:spindel-ctx (:chat-ctx feedback-ctx))]
    @(:completion-signal feedback-ctx)))

(defn get-status
  "Get the current approval status."
  [feedback-ctx]
  (binding [rtc/*execution-context* (:spindel-ctx (:chat-ctx feedback-ctx))]
    @(:approval-signal feedback-ctx)))

(defn get-messages
  "Get all messages from the chat."
  [feedback-ctx]
  (chat-ctx/get-messages (:chat-ctx feedback-ctx)))
