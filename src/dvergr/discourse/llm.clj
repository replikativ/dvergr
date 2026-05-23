(ns dvergr.discourse.llm
  "LLM-backed participant constructor for `dvergr.discourse` rooms.

   `(llm-agent {…})` returns a Participant whose `on-message` handler runs
   one or more LLM turns via `dvergr.chat.agent/run-agent-turn!`. The
   blocking LLM call is bridged into the spin via a Clojure future that
   delivers to a spindel deferred — the participant's loop remains
   non-blocking. Tools, accounting, and compaction flow through
   `dvergr.chat.context` and `dvergr.tools` unchanged.

   Usage:

     (require '[dvergr.discourse :as d] '[dvergr.discourse.llm :as llm])

     (d/join room
       (llm/llm-agent
         {:id     :researcher
          :spec   {:provider :anthropic
                   :model    \"claude-sonnet-4-6\"
                   :system-prompt \"You are a research assistant.\"}
          :tools  #{:web_fetch :clojure_eval}
          :budget {:dollars 0.50 :max-turns 8}}))

   Tests pass `:run-turn-fn` (a stub returning :continue/:complete and
   writing to chat-ctx directly) to avoid real LLM calls — see
   `dvergr.discourse.llm-test/make-mock-turn-fn`.

   Defaults override-able via :ctx, :tool-ctx, :compaction options.
   Conversation state is per-participant (one chat-ctx per agent instance).
   On fork-room, the agent is re-created fresh (no history carryover; this
   matches ToM probe semantics). Future: snapshot-based history carryover."
  (:require [clojure.string :as str]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.sync :as sync]
            [dvergr.discourse :as d]
            [dvergr.chat.context :as cc]
            [dvergr.chat.agent :as ca]
            [dvergr.tools :as tools]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- default-run-turn
  "Default `run-turn-fn`: delegates to `dvergr.chat.agent/run-agent-turn!`."
  [chat-ctx opts]
  (ca/run-agent-turn! chat-ctx opts))

(defn- assistant-text
  "Extract a string from an assistant message entity. Handles plain-string
   content and block-vector content (Anthropic-style)."
  [msg]
  (let [content (or (:message/content msg) (:content msg))]
    (cond
      (nil?        content) nil
      (string?     content) content
      (sequential? content)
      (->> content
           (filter #(or (= "text" (:type %)) (= :text (:type %))))
           (map :text)
           (str/join "\n"))
      :else (str content))))

(defn- last-assistant-message
  [chat-ctx]
  (->> (cc/get-messages chat-ctx)
       reverse
       (filter #(let [r (or (:role %) (:message/role %))]
                  (or (= r :assistant) (= r "assistant"))))
       first))

;; ============================================================================
;; Public API
;; ============================================================================

(defn llm-agent
  "Construct a discourse Participant backed by an LLM.

   :id          — keyword participant id (required)
   :spec        — {:provider :model :system-prompt} (required)
   :tools       — set or vector of tool names from `dvergr.tools` registry,
                  or nil for no tools
   :db-conn     — datahike connection for chat persistence (optional)
   :budget      — {:dollars n :max-turns n} (default {:dollars 1.0 :max-turns 8})
   :compaction  — {:auto? bool :model str} (default {:auto? true})
   :run-turn-fn — (fn [chat-ctx opts] → :complete | :continue | :error)
                  Override for testing; default calls
                  `dvergr.chat.agent/run-agent-turn!`
   :ctx         — discourse room's execution context
                  (default: `*execution-context*`)"
  [{:keys [id spec tools db-conn budget compaction run-turn-fn ctx]
    :or   {budget      {:dollars 1.0 :max-turns 8}
           compaction  {:auto? true}
           run-turn-fn default-run-turn}}]
  {:pre [(keyword? id) (map? spec)]}
  (let [ctx       (or ctx ec/*execution-context*)
        chat-ctx  (cc/create-chat-context
                    {:title          (str "agent " (name id))
                     :budget-dollars (:dollars budget 1.0)
                     :db-conn        db-conn
                     :with-sci?      false})
        ;; Seed system prompt if provided
        _         (when-let [sp (:system-prompt spec)]
                    (cc/add-message! chat-ctx {:role :system :content sp}))
        max-turns (:max-turns budget 8)
        tool-ctx  (tools/make-context
                    {:db-conn  db-conn
                     :chat-ctx chat-ctx})
        turn-opts {:provider         (:provider spec)
                   :model            (:model spec)
                   :tools            tools
                   :tool-ctx         tool-ctx
                   :auto-compact?    (:auto? compaction true)
                   :compaction-model (:model compaction)}]
    (d/participant
      {:id  id
       :ctx ctx
       :on-message
       (fn [_p msg]
         (sp/spin
           ;; 1. Append incoming as a user message in the agent's chat-ctx.
           ;; We deliberately don't pass :author-id — chat-ctx's datahike
           ;; schema would require a [:participant/id author-id] lookup that
           ;; the transient ask-XXX stubs (and ad-hoc senders) can't satisfy.
           ;; The discourse Message keeps the :from field for routing; the
           ;; LLM context doesn't need author identity at this layer.
           (cc/add-message! chat-ctx
                            {:role    :user
                             :content (:content msg)})

           ;; 2. Run turns until :complete, :error, or max-turns reached.
           ;;    Bridge each blocking turn through a future + deferred so
           ;;    the spin executor isn't tied up.
           (loop [turn 0]
             (let [bridge (sync/create-deferred ctx)
                   _ (future
                       (try
                         (sync/deliver!
                           bridge
                           (run-turn-fn chat-ctx
                                        (assoc turn-opts :turn-number turn)))
                         (catch Throwable t
                           (sync/deliver! bridge {::error (.getMessage t)}))))
                   result (sp/await bridge)]
               (cond
                 (= result :continue)
                 (if (< (inc turn) max-turns) (recur (inc turn)) nil)

                 ;; :complete, :error, ::error map, or anything else terminates
                 :else nil)))

           ;; 3. Return the last assistant message as the reply
           (when-let [reply (some-> (last-assistant-message chat-ctx)
                                    assistant-text)]
             {:to (:from msg) :content reply})))

       :factory
       (fn [new-ctx]
         ;; Fork-room semantics for an LLM agent: re-create fresh in the
         ;; new context. The fork's agent has no prior conversation,
         ;; matching the §6.5 ToM-probe semantics ("what would they say,
         ;; given only the priming I set up?"). Future enhancement: pass
         ;; an :init-snapshot to carry conversation forward.
         (llm-agent {:id          id
                     :spec        spec
                     :tools       tools
                     :db-conn     db-conn
                     :budget      budget
                     :compaction  compaction
                     :run-turn-fn run-turn-fn
                     :ctx         new-ctx}))})))
