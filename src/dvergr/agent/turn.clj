(ns dvergr.agent.turn
  "LLM turn execution as async deferreds/spins.

   Provides the bridge between blocking LLM/tool work and spindel's
   async composition model. run-turn-async returns a deferred (await
   it directly from a spin), make-think-fn returns a spin."
  (:refer-clojure :exclude [await])
  (:require [org.replikativ.spindel.core :as sync :refer [spin await]]
            [org.replikativ.spindel.engine.core :as rtc]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.chat.context :as chat-ctx]
            [dvergr.tools :as tools]
            [dvergr.sandbox :as sandbox]))

;; ============================================================================
;; Single Turn Execution
;; ============================================================================

(defn run-turn-async
  "Execute a single agent turn asynchronously.

   Wraps the blocking LLM call + tool execution in a future,
   bridging to spindel via deferred.

   Args:
     chat-ctx - ChatContext for the conversation
     opts - {:provider :model :tools :tool-ctx :on-text}
     execution-ctx - Spindel execution context

   Returns: Deferred that resolves to turn result map.
            Await directly from a spin — do NOT wrap in another spin."
  [chat-ctx opts execution-ctx]
  (let [d (binding [rtc/*execution-context* execution-ctx]
            (sync/deferred))]
    ;; Run blocking work on thread pool — bind execution-ctx for the whole future
    ;; so that tool execution (SCI sandbox, spindel primitives) has the correct context.
    (future
      (binding [rtc/*execution-context* execution-ctx]
        (try
          (let [result (chat-agent/run-agent-turn! chat-ctx opts)]
            (sync/deliver! d {:status :ok :result result}))
          (catch Exception e
            (sync/deliver! d {:status :error :error e :message (.getMessage e)})))))
    ;; Return the deferred directly — caller awaits it from their spin
    d))

;; ============================================================================
;; Think Function (for use with agent.process)
;; ============================================================================

(defn make-think-fn
  "Create a think function for use with agent.process.

   The think function:
   1. Ensures chat context exists or creates one
   2. Adds the task as a user message
   3. Runs agent turns until complete or max-turns
   4. Returns the result

   Returns: (fn [agent task] -> spin)"
  [{:keys [max-turns budget-dollars]
    :or {max-turns 10 budget-dollars 1.0}}]
  (fn [agent task]
    (let [ctx (or (:execution-ctx agent) rtc/*execution-context*)
          config (:config agent)
          ;; Get or create chat context
          chat-ctx (or (:chat-ctx task)
                       (:chat-ctx config)
                       (chat-ctx/create-chat-context
                         {:title (str "Agent " (:id agent) " task")
                          :budget-dollars budget-dollars}))
          ;; Setup tools
          sci-ctx (sandbox/fork-for-session)
          ;; Wire context-aware namespaces (dh/transact!, sync/deferred, etc.)
          _ (sandbox/setup-agent-namespaces! sci-ctx ctx)
          all-tools (or (:tools config) (tools/all-tools))
          tools-map (if (map? all-tools)
                      all-tools
                      (into {} (map (juxt :name identity)) all-tools))
          tool-ctx (tools/make-context
                     {:cwd (System/getProperty "user.dir")
                      :sci-ctx sci-ctx
                      :tools tools-map
                      :chat-ctx chat-ctx
                      :isolation (:isolation config)
                      :execution-ctx ctx})
          opts {:provider (:provider config)
                :model (:model config)
                :tools tools-map
                :tool-ctx tool-ctx
                :on-text (:on-text config)}]

      (binding [rtc/*execution-context* ctx]
        (spin
          ;; Add system prompt if not present
          (when (and (:system-prompt config)
                     (empty? (chat-ctx/get-messages chat-ctx)))
            (chat-ctx/add-message! chat-ctx
                                   {:role :system
                                    :content (:system-prompt config)}))

          ;; Add task as user message
          (let [task-content (if (string? task) task (:content task ""))]
            (when (not (clojure.string/blank? task-content))
              (chat-ctx/add-message! chat-ctx
                                     {:role :user
                                      :content task-content})))

          ;; Run turns until complete
          (loop [turn 0]
            (cond
              ;; Max turns reached
              (>= turn max-turns)
              {:status :max-turns
               :turns turn
               :agent-id (:id agent)}

              ;; Budget exceeded
              (not (chat-ctx/check-budget! chat-ctx))
              {:status :budget-exceeded
               :turns turn
               :agent-id (:id agent)}

              ;; Run a turn
              :else
              (let [turn-result (await (run-turn-async chat-ctx opts ctx))]
                (if (= :error (:status turn-result))
                  {:status :error
                   :error (:error turn-result)
                   :turns turn
                   :agent-id (:id agent)}
                  (case (:result turn-result)
                    :continue (recur (inc turn))
                    :complete {:status :complete
                               :turns (inc turn)
                               :agent-id (:id agent)
                               :messages (chat-ctx/get-messages chat-ctx)}
                    :error {:status :error
                            :turns turn
                            :agent-id (:id agent)}))))))))))

;; ============================================================================
;; Simple Think (for testing)
;; ============================================================================

(defn make-echo-think
  "Create a simple echo think function for testing.
   Just echoes the task back after a delay."
  [& {:keys [delay-ms] :or {delay-ms 100}}]
  (fn [agent task]
    (let [ctx (or (:execution-ctx agent) rtc/*execution-context*)
          d (binding [rtc/*execution-context* ctx]
              (sync/deferred))]
      (future
        (Thread/sleep delay-ms)
        (binding [rtc/*execution-context* ctx]
          (sync/deliver! d {:status :echo
                            :task task
                            :agent-id (:id agent)
                            :turn (:turn @(:state-a agent))})))
      ;; Return deferred directly — caller awaits from their spin
      d)))

(defn make-counter-think
  "Create a think function that counts. For testing."
  [counter-atom & {:keys [delay-ms] :or {delay-ms 50}}]
  (fn [agent task]
    (let [ctx (or (:execution-ctx agent) rtc/*execution-context*)
          d (binding [rtc/*execution-context* ctx]
              (sync/deferred))]
      (future
        (Thread/sleep delay-ms)
        (let [n (swap! counter-atom inc)]
          (binding [rtc/*execution-context* ctx]
            (sync/deliver! d {:status :counted
                              :count n
                              :task task
                              :agent-id (:id agent)}))))
      ;; Return deferred directly
      d)))
