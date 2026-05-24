(ns dvergr.chat.context
  "SpindelContext-based chat management.

   Each chat is backed by:
   1. SpindelContext - for reactive state (messages, budget)
   2. Datahike - for durable persistence (uses Yggdrasil-registered db if available)
   3. SCI context - for sandboxed agent computation

   Sub-chats use OverlayBackend for O(1) forking with CoW semantics.

   Budget tracking uses microdollars (μ$) as the numéraire:
   1 USD = 1,000,000 μ$

   Yggdrasil Integration:
   When a DatahikeSystem is registered in the execution context (via agents/core),
   ChatContext will use that connection instead of creating its own. This enables:
   - File-based persistence (survives REPL crashes)
   - Automatic forking when agents use with-fork
   - Branch-based isolation for agent work"
  (:require [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.signal :as sig]
            [org.replikativ.spindel.atom :as ratom]
            [org.replikativ.spindel.core :as d]
            [dvergr.chat.schema :as schema]
            [dvergr.chat.accounting :as acct]
            [dvergr.sandbox :as sandbox]
            [taoensso.telemere :as tel]
            [datahike.api :as dh]))

;; ============================================================================
;; Chat Context Record
;; ============================================================================

(defrecord ChatContext
    [;; Identity
     chat-id
     title

     ;; Spindel
     spindel-ctx       ; ExecutionContext for reactive state

     ;; Reactive signals (stored in spindel-ctx)
     messages-signal   ; Deltaable vector of messages
     budget-signal     ; {:total :used :by-type}
     status-signal     ; :active :paused :completed etc

     ;; Datahike
     db-conn           ; Datahike connection for persistence

     ;; SCI (optional - for agent computation)
     sci-ctx           ; SCI context for sandboxed eval

     ;; Hierarchy
     parent-ctx        ; Parent ChatContext if this is a sub-chat
     child-ctxs        ; Spindel Atom of child ChatContexts (fork-safe)
     ])

;; ============================================================================
;; Signal Accessors (must be called with spindel context bound)
;; ============================================================================

(defn get-messages
  "Get current messages vector from chat context."
  [chat-ctx]
  (binding [rtc/*execution-context* (:spindel-ctx chat-ctx)]
    @(:messages-signal chat-ctx)))

(defn get-budget
  "Get current budget map from chat context."
  [chat-ctx]
  (binding [rtc/*execution-context* (:spindel-ctx chat-ctx)]
    @(:budget-signal chat-ctx)))

(defn get-status
  "Get current status from chat context."
  [chat-ctx]
  (binding [rtc/*execution-context* (:spindel-ctx chat-ctx)]
    @(:status-signal chat-ctx)))

;; ============================================================================
;; Signal Mutations
;; ============================================================================

(defn account-usage!
  "Account resource usage in the chat with cost calculation.

   Args:
     chat-ctx      - ChatContext
     resource-type - :input-tokens :output-tokens :web-search :tool-invoke etc.
     amount        - Amount in natural units
     opts          - {:model :provider :tool} for cost calculation

   Returns:
     {:cost-microdollars N :threshold-crossed? bool :threshold-level kw}"
  [chat-ctx resource-type amount & {:keys [model provider tool] :as opts}]
  (let [cost (acct/calculate-cost resource-type amount opts)
        threshold-info (atom nil)]

    ;; Update spindel signal
    (binding [rtc/*execution-context* (:spindel-ctx chat-ctx)]
      (swap! (:budget-signal chat-ctx)
             (fn [{:keys [total used by-type crossed-thresholds]}]
               (let [new-used (+ used cost)
                     pct-used (if (pos? total) (/ (double new-used) total) 0.0)
                     ;; Check for threshold crossing
                     threshold (acct/check-thresholds pct-used (or crossed-thresholds #{}))
                     new-crossed (if threshold
                                   (conj (or crossed-thresholds #{}) (:pct threshold))
                                   crossed-thresholds)]
                 (when threshold
                   (reset! threshold-info threshold))
                 {:total total
                  :used new-used
                  :by-type (update by-type resource-type (fnil + 0) amount)
                  :crossed-thresholds new-crossed}))))

    ;; Persist ledger entry
    (when-let [conn (:db-conn chat-ctx)]
      (dh/transact conn
        [(cond-> {:ledger/id (random-uuid)
                  :ledger/context [:chat/id (:chat-id chat-ctx)]
                  :ledger/timestamp (java.util.Date.)
                  :ledger/resource resource-type
                  :ledger/amount (long amount)
                  :ledger/cost-microdollars (long cost)}
           model (assoc :ledger/model model)
           provider (assoc :ledger/provider provider)
           tool (assoc :ledger/tool tool))]))

    ;; Return cost info
    (cond-> {:cost-microdollars cost}
      @threshold-info (assoc :threshold-crossed? true
                             :threshold-level (:level @threshold-info)
                             :threshold-message (:message @threshold-info)))))

(defn account-tokens!
  "Account token usage in the chat.

   Args:
     chat-ctx - ChatContext
     type - :input-tokens or :output-tokens
     amount - Token count
     opts - {:model model-id} for cost calculation"
  ([chat-ctx type amount]
   (account-tokens! chat-ctx type amount {}))
  ([chat-ctx type amount {:keys [model] :as opts}]
   (account-usage! chat-ctx type amount :model model)))

(defn add-message!
  "Add a message to the chat.

   Args:
     chat-ctx - ChatContext
     message - Map with :role, :content, :author-id, :tokens, etc."
  [chat-ctx message]
  (let [msg-entity (schema/create-message-entity
                    (assoc message :chat-id (:chat-id chat-ctx)))]
    ;; Update spindel signal (reactive)
    (binding [rtc/*execution-context* (:spindel-ctx chat-ctx)]
      (swap! (:messages-signal chat-ctx) conj msg-entity))

    ;; Persist to datahike (durable)
    (when-let [conn (:db-conn chat-ctx)]
      (dh/transact conn [msg-entity]))

    ;; Account tokens if provided
    (when-let [tokens (:tokens message)]
      (account-tokens! chat-ctx (if (= :assistant (:role message))
                                  :output-tokens
                                  :input-tokens)
                       tokens))

    msg-entity))

(defn replace-messages!
  "Replace all messages in the chat context.
   Used by pruning to swap in pruned message versions without adding new messages.

   Args:
     chat-ctx - ChatContext
     new-messages - Complete replacement message vector"
  [chat-ctx new-messages]
  (binding [rtc/*execution-context* (:spindel-ctx chat-ctx)]
    (reset! (:messages-signal chat-ctx) (d/deltaable-vector (vec new-messages)))))

(defn set-status!
  "Set chat status.

   Args:
     chat-ctx - ChatContext
     status - :active :paused :completed :cancelled :budget-exceeded"
  [chat-ctx status]
  (binding [rtc/*execution-context* (:spindel-ctx chat-ctx)]
    (reset! (:status-signal chat-ctx) status))

  ;; Persist status change
  (when-let [conn (:db-conn chat-ctx)]
    (dh/transact conn [{:db/id [:chat/id (:chat-id chat-ctx)]
                        :chat/status status
                        :chat/updated-at (java.util.Date.)}])))

;; ============================================================================
;; Budget Checking
;; ============================================================================

(defn budget-remaining
  "Get remaining budget for chat in microdollars."
  [chat-ctx]
  (let [{:keys [total used]} (get-budget chat-ctx)]
    (- total used)))

(defn budget-remaining-dollars
  "Get remaining budget for chat in dollars (for display)."
  [chat-ctx]
  (/ (budget-remaining chat-ctx) (double acct/MICRODOLLARS-PER-DOLLAR)))

(defn budget-exceeded?
  "Check if budget is exceeded."
  [chat-ctx]
  (<= (budget-remaining chat-ctx) 0))

(defn budget-pct-used
  "Get percentage of budget used (0.0 to 1.0)."
  [chat-ctx]
  (let [{:keys [total used]} (get-budget chat-ctx)]
    (if (pos? total)
      (/ (double used) total)
      0.0)))

(defn format-budget-status
  "Format budget status for display."
  [chat-ctx]
  (let [{:keys [total used]} (get-budget chat-ctx)
        status (acct/budget-status total used)]
    (acct/format-budget status)))

(defn check-budget!
  "Check budget and update status if exceeded.
   Returns true if budget is OK, false if exceeded."
  [chat-ctx]
  (if (budget-exceeded? chat-ctx)
    (do
      (set-status! chat-ctx :budget-exceeded)
      false)
    true))

;; ============================================================================
;; Chat Context Creation
;; ============================================================================

(defn- get-yggdrasil-datahike-conn
  "Get Datahike connection from Yggdrasil-registered system if available.
   Returns the connection or nil."
  []
  (try
    (when-let [dh-sys (rtc/get-state [:external-refs "dvergr-chat-db"])]
      (:conn dh-sys))
    (catch Exception _ nil)))

(defn create-chat-context
  "Create a new root chat context.

   Args:
     opts - Map with:
       :title - Chat title
       :budget-dollars - Budget in dollars (default $1.00)
       :budget - Legacy: budget in microdollars
       :db-path - Path for datahike (default in-memory, or uses Yggdrasil db if registered)
       :with-sci? - Create SCI context (default true)

   Yggdrasil Integration:
     If a DatahikeSystem is registered in the current execution context
     (via agents/create-shared-context), that connection will be used instead
     of creating a new one. This enables:
     - Persistent file-based storage
     - Automatic branching when agents fork
     - Database inspection after agent runs"
  [{:keys [title budget-dollars budget db-path with-sci? db-conn]
    :or {budget-dollars 1.0
         with-sci? true}}]
  (let [chat-id (random-uuid)

        ;; Convert budget to microdollars (numéraire)
        budget-microdollars (or budget
                               (long (* budget-dollars acct/MICRODOLLARS-PER-DOLLAR)))

        ;; Create spindel execution context
        spindel-ctx (ctx/create-execution-context)

        ;; Get datahike connection - priority: explicit :db-conn > Yggdrasil > local
        ygg-conn (when-not db-conn (get-yggdrasil-datahike-conn))
        db-conn (cond
                  db-conn
                  ;; Use explicitly provided connection (e.g. per-room DB in simmis)
                  (do
                    (tel/log! {:level :debug :id :chat-ctx/explicit-db-conn} "Using explicitly provided Datahike connection")
                    (schema/ensure-full-schema! db-conn)
                    db-conn)

                  ygg-conn
                  ;; Use Yggdrasil-managed connection (file-based, forkable)
                  (do
                    (tel/log! {:level :debug :id :chat-ctx/yggdrasil-db} "Using Yggdrasil-registered Datahike connection")
                    ;; Ensure full schema is installed (core + tools + web search)
                    (schema/ensure-full-schema! ygg-conn)
                    ygg-conn)

                  :else
                  ;; Create local connection
                  (let [db-cfg (if db-path
                                 {:store {:backend :file :path db-path}}
                                 {:store {:backend :memory :id chat-id}})]
                    (schema/create-chat-db! db-cfg)))

        ;; Create SCI context if requested
        ;; When *execution-context* is bound, create spindel-backed SCI with
        ;; full FRP support (spin/await/track). Otherwise plain SCI.
        sci-ctx (when with-sci?
                  (sandbox/fork-for-session rtc/*execution-context*))

        ;; Create signals and atoms within spindel context
        [messages-signal budget-signal status-signal child-ctxs-atom]
        (binding [rtc/*execution-context* spindel-ctx]
          [(sig/signal (d/deltaable-vector []))
           (sig/signal {:total budget-microdollars
                        :used 0
                        :by-type {}
                        :crossed-thresholds #{}})
           (sig/signal :active)
           (ratom/create-atom {})])  ; fork-safe child contexts map

        ;; Create chat entity in datahike
        chat-entity (schema/create-chat-entity
                     {:id chat-id
                      :title (or title "Untitled Chat")
                      :budget budget-microdollars})
        _ (dh/transact db-conn [chat-entity])]

    (->ChatContext
     chat-id
     (or title "Untitled Chat")
     spindel-ctx
     messages-signal
     budget-signal
     status-signal
     db-conn
     sci-ctx
     nil                ; parent-ctx
     child-ctxs-atom))) ; spindel atom for child contexts

;; ============================================================================
;; Sub-Chat Forking
;; ============================================================================

(defn fork-sub-chat
  "Fork a sub-chat from parent chat.

   Uses spindel's OverlayBackend for O(1) CoW forking.
   Sub-chat gets allocated budget from parent.

   Args:
     parent-ctx - Parent ChatContext
     opts - Map with:
       :title - Sub-chat title
       :budget-dollars - Budget to allocate in dollars
       :budget - Budget in microdollars (legacy)"
  [parent-ctx {:keys [title budget-dollars budget]}]
  (let [chat-id (random-uuid)
        parent-remaining (budget-remaining parent-ctx)

        ;; Convert to microdollars if needed
        requested-budget (or budget
                            (when budget-dollars
                              (long (* budget-dollars acct/MICRODOLLARS-PER-DOLLAR)))
                            100000)  ; Default $0.10

        ;; Validate parent has budget remaining
        _ (when (<= parent-remaining 0)
            (throw (ex-info "Parent has no budget remaining"
                           {:parent-id (:chat-id parent-ctx)
                            :parent-remaining parent-remaining})))

        ;; Validate requested budget doesn't exceed parent's remaining
        _ (when (> requested-budget parent-remaining)
            (throw (ex-info "Requested budget exceeds parent's remaining budget"
                           {:parent-id (:chat-id parent-ctx)
                            :requested requested-budget
                            :parent-remaining parent-remaining})))

        ;; Allocate the requested amount (now validated to be <= parent-remaining)
        allocated-budget requested-budget

        ;; Deduct from parent (no cost, just transfer)
        _ (account-usage! parent-ctx :sub-chat-allocation allocated-budget)

        ;; Fork spindel context (O(1) with OverlayBackend)
        forked-spindel (ctx/fork-context (:spindel-ctx parent-ctx))

        ;; Create sub-chat's own datahike (could also use overlay in future)
        db-cfg {:store {:backend :memory :id chat-id}}
        db-conn (schema/create-chat-db! db-cfg)

        ;; Fork SCI context if parent has one — use current execution context
        ;; for spindel-backed SCI with full FRP support
        sci-ctx (when (:sci-ctx parent-ctx)
                  (sandbox/fork-for-session rtc/*execution-context*))

        ;; Create signals and atoms in forked context
        [messages-signal budget-signal status-signal child-ctxs-atom]
        (binding [rtc/*execution-context* forked-spindel]
          [(sig/signal (d/deltaable-vector []))
           (sig/signal {:total allocated-budget
                        :used 0
                        :by-type {}
                        :crossed-thresholds #{}})
           (sig/signal :active)
           (ratom/create-atom {})])

        ;; Create chat entity
        chat-entity (schema/create-chat-entity
                     {:id chat-id
                      :title (or title "Sub-chat")
                      :budget allocated-budget
                      :parent-id (:chat-id parent-ctx)})
        _ (dh/transact db-conn [chat-entity])

        sub-ctx (->ChatContext
                 chat-id
                 (or title "Sub-chat")
                 forked-spindel
                 messages-signal
                 budget-signal
                 status-signal
                 db-conn
                 sci-ctx
                 parent-ctx
                 child-ctxs-atom)]

    ;; Register sub-chat with parent (in parent's spindel context)
    (binding [rtc/*execution-context* (:spindel-ctx parent-ctx)]
      (swap! (:child-ctxs parent-ctx) assoc chat-id sub-ctx))

    sub-ctx))

;; ============================================================================
;; Sub-Chat Completion
;; ============================================================================

(defn complete-sub-chat!
  "Complete a sub-chat and propagate summary to parent.

   Args:
     sub-ctx - Sub-chat ChatContext
     opts - Map with:
       :summary - Summary text (or will be generated)
       :merge-unused-budget? - Return unused budget to parent (default true)"
  [sub-ctx {:keys [summary merge-unused-budget?]
            :or {merge-unused-budget? true}}]
  (let [parent-ctx (:parent-ctx sub-ctx)]
    ;; Set status
    (set-status! sub-ctx :completed)

    ;; Store summary
    (when summary
      (dh/transact (:db-conn sub-ctx)
                   [{:db/id [:chat/id (:chat-id sub-ctx)]
                     :chat/summary summary}]))

    ;; Return unused budget to parent
    (when (and merge-unused-budget? parent-ctx)
      (let [unused (budget-remaining sub-ctx)]
        (when (pos? unused)
          ;; Add back to parent (negative accounting)
          (account-tokens! parent-ctx :sub-chat-return (- unused)))))

    ;; Add summary message to parent
    (when (and parent-ctx summary)
      (add-message! parent-ctx
                    {:role :system
                     :content (str "Sub-chat completed: " summary)
                     :important? true}))

    ;; Unregister from parent (in parent's spindel context)
    (when parent-ctx
      (binding [rtc/*execution-context* (:spindel-ctx parent-ctx)]
        (swap! (:child-ctxs parent-ctx) dissoc (:chat-id sub-ctx))))

    :completed))

;; ============================================================================
;; Chat Lifecycle
;; ============================================================================

(defn pause-chat!
  "Pause a chat (cooperative - agents check status)."
  [chat-ctx]
  (set-status! chat-ctx :paused))

(defn resume-chat!
  "Resume a paused chat."
  [chat-ctx]
  (when (= :paused (get-status chat-ctx))
    (set-status! chat-ctx :active)))

(defn cancel-chat!
  "Cancel a chat and all sub-chats."
  [chat-ctx]
  (set-status! chat-ctx :cancelled)
  ;; Cancel all children (in this chat's spindel context)
  (binding [rtc/*execution-context* (:spindel-ctx chat-ctx)]
    (doseq [[_ child-ctx] @(:child-ctxs chat-ctx)]
      (cancel-chat! child-ctx))))

(defn close-chat!
  "Close chat and release resources."
  [chat-ctx]
  (binding [rtc/*execution-context* (:spindel-ctx chat-ctx)]
    ;; Close children first
    (doseq [[_ child-ctx] @(:child-ctxs chat-ctx)]
      (close-chat! child-ctx))

    ;; Close datahike connection
    (when-let [conn (:db-conn chat-ctx)]
      (dh/release conn))

    ;; Clear references
    (reset! (:child-ctxs chat-ctx) {}))

  :closed)

;; ============================================================================
;; Serialization (for checkpointing)
;; ============================================================================

(defn snapshot-chat
  "Create a serializable snapshot of chat state.

   Returns map that can be serialized and restored later."
  [chat-ctx]
  {:chat-id (:chat-id chat-ctx)
   :title (:title chat-ctx)
   :messages (get-messages chat-ctx)
   :budget (get-budget chat-ctx)
   :status (get-status chat-ctx)
   ;; Spindel context can be serialized too
   :spindel-snapshot (ctx/serialize-context (:spindel-ctx chat-ctx))})

(defn restore-chat
  "Restore chat from a snapshot produced by snapshot-chat.

   Creates a fresh ChatContext with a new spindel execution context,
   then loads messages, budget, and status from the snapshot.
   The spindel context is not deserialized — only the data is restored.

   Returns the restored ChatContext."
  [{:keys [chat-id title messages budget status] :as snapshot}]
  (let [chat-ctx (create-chat-context
                  {:title (or title "Restored chat")
                   :budget (or (:total budget) (* 1000000 1))})]
    ;; Restore messages
    (when (seq messages)
      (replace-messages! chat-ctx messages))
    ;; Restore budget (overwrite the fresh budget with saved state)
    (when budget
      (binding [rtc/*execution-context* (:spindel-ctx chat-ctx)]
        (reset! (:budget-signal chat-ctx) budget)))
    ;; Restore status
    (when (and status (not= status :active))
      (set-status! chat-ctx status))
    chat-ctx))

(comment
  ;; Example usage:

  ;; Create a root chat with $1.00 budget
  (def chat (create-chat-context
             {:title "Implement JWT Auth"
              :budget-dollars 1.0}))

  ;; Check initial state
  (get-messages chat)  ; => []
  (get-budget chat)    ; => {:total 1000000 :used 0 :by-type {} :crossed-thresholds #{}}
  (get-status chat)    ; => :active

  ;; Check budget in dollars
  (budget-remaining-dollars chat)  ; => 1.0
  (format-budget-status chat)      ; => "Budget Status:\n  Total: $1.0000\n..."

  ;; Account token usage with cost tracking
  (account-usage! chat :input-tokens 1000
                 :model "claude-sonnet-4-5")
  ;; => {:cost-microdollars 3000}  ; $0.003

  ;; Add a user message
  (add-message! chat
                {:role :user
                 :content "Please implement JWT authentication."
                 :tokens 10})

  ;; Check budget
  (get-budget chat)             ; => {:total 1000000 :used 3000 :by-type {...}}
  (budget-remaining-dollars chat)  ; => 0.997

  ;; Fork a sub-chat for research with $0.20 budget
  (def research-chat (fork-sub-chat chat
                                     {:title "Research JWT patterns"
                                      :budget-dollars 0.20}))

  ;; Sub-chat tracks its own budget
  (budget-remaining-dollars research-chat)  ; => 0.20

  ;; Complete sub-chat
  (complete-sub-chat! research-chat
                      {:summary "JWT best practices: use RS256, short expiry, refresh tokens"})

  ;; Parent now has summary message
  (last (get-messages chat))

  ;; Cleanup
  (close-chat! chat)
  )
