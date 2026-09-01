(ns dvergr.chat.context
  "SpindelContext-based chat management.

   Each chat is backed by:
   1. SpindelContext - for reactive state (messages, budget)
   2. Datahike - for durable persistence (uses Yggdrasil-registered db if available)
   3. SCI context - for sandboxed agent computation

   Budget tracking uses microdollars (μ$) as the numéraire:
   1 USD = 1,000,000 μ$

   Yggdrasil Integration:
   When a DatahikeSystem is registered in the execution context (via agents/core),
   ChatContext will use that connection instead of creating its own. This enables:
   - File-based persistence (survives REPL crashes)
   - Automatic forking when agents use with-fork
   - Branch-based isolation for agent work"
  (:require [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.signal :as sig]
            [org.replikativ.spindel.core :as d]
            [dvergr.runtime.ctx :as runtime-ctx]
            [dvergr.chat.schema :as schema]
            [dvergr.chat.accounting :as acct]
            [dvergr.chat.persist :as persist]
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
            sci-component     ; stable ref; resolves to this world's SCI interpreter
            ])

(defn sci-context-in
  "Resolve `chat-ctx`'s SCI interpreter in explicit `execution-context`.

   The ChatContext retains only a stable world-component reference. A forked
   Spindel context therefore selects a forked interpreter instead of retaining
   the parent's mutable SCI heap."
  [chat-ctx execution-context]
  (sandbox/sci-context-in execution-context (:sci-component chat-ctx)))

(defn selected-execution-context
  "The world selected for ChatContext signals and ambient capabilities.

   A bound descendant wins; calls made outside execution fall back to the
   context's owner. This keeps the ChatContext value stable while its signals
   and SCI interpreter select fork-local realizations."
  [chat-ctx]
  (runtime-ctx/selected-context (:spindel-ctx chat-ctx)))

(defn sci-context
  "Resolve `chat-ctx`'s SCI interpreter in the bound world, falling back to
   the ChatContext's owning world when called outside a Spindel binding."
  [chat-ctx]
  (sci-context-in chat-ctx (selected-execution-context chat-ctx)))
;; ============================================================================
;; Signal Accessors (must be called with spindel context bound)
;; ============================================================================

(defn get-messages
  "Get current messages vector from chat context."
  [chat-ctx]
  (binding [rtc/*execution-context* (selected-execution-context chat-ctx)]
    @(:messages-signal chat-ctx)))

(defn get-budget
  "Get current budget map from chat context."
  [chat-ctx]
  (binding [rtc/*execution-context* (selected-execution-context chat-ctx)]
    @(:budget-signal chat-ctx)))

(defn get-status
  "Get current status from chat context."
  [chat-ctx]
  (binding [rtc/*execution-context* (selected-execution-context chat-ctx)]
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
        ;; Atomic threshold detection: the crossed threshold is computed INSIDE
        ;; the swap (pure) and read back from the resulting state — never via a
        ;; side-effected outer atom, since swap!'s fn may retry under contention
        ;; and a side effect in it would record an abandoned computation. The
        ;; transient `::just-crossed` key always reflects this call (nil if none).
        new-state
        (binding [rtc/*execution-context* (selected-execution-context chat-ctx)]
          (swap! (:budget-signal chat-ctx)
                 (fn [{:keys [total used by-type crossed-thresholds]}]
                   (let [new-used (+ used cost)
                         pct-used (if (pos? total) (/ (double new-used) total) 0.0)
                         threshold (acct/check-thresholds pct-used (or crossed-thresholds #{}))
                         new-crossed (if threshold
                                       (conj (or crossed-thresholds #{}) (:pct threshold))
                                       crossed-thresholds)]
                     {:total total
                      :used new-used
                      :by-type (update by-type resource-type (fnil + 0) amount)
                      :crossed-thresholds new-crossed
                      ::just-crossed threshold}))))
        threshold-info (::just-crossed new-state)]

    ;; Persist via THE ledger writer (acct/record-usage!): the ledger row +
    ;; the :chat/budget-used rollup land in one atomic transact there. This
    ;; used to be an inline duplicate of that transact — which meant new
    ;; ledger dimensions (e.g. the kontor commodity/settlement stamping)
    ;; only reached the simmis billing path, never the room-turn rows.
    (when-let [conn (:db-conn chat-ctx)]
      (acct/record-usage! conn (:chat-id chat-ctx) resource-type amount
                          :model model :provider provider :tool tool))

    ;; Return cost info
    (cond-> {:cost-microdollars cost}
      threshold-info (assoc :threshold-crossed? true
                            :threshold-level (:level threshold-info)
                            :threshold-message (:message threshold-info)))))

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
     message - Map with :role, :content, :tokens, etc."
  [chat-ctx message]
  (let [msg-entity (schema/create-message-entity
                    (assoc message :chat-id (:chat-id chat-ctx)))]
    ;; Update spindel signal (reactive)
    (binding [rtc/*execution-context* (selected-execution-context chat-ctx)]
      (swap! (:messages-signal chat-ctx) conj msg-entity))

    ;; Persist to datahike (durable) — UNLESS this ctx delegates durability to
    ;; the room store (`:durable? false`, set by dvergr.agent.room-context).
    ;; In the room model the bus→store listener is the single durable writer for
    ;; the conversation; re-writing message entities under the chat-id would be a
    ;; redundant second write. Token accounting (ledger, below) still runs — the
    ;; budget is reconstructed from the ledger on restore.
    (when-let [conn (:db-conn chat-ctx)]
      (when-not (false? (:durable? chat-ctx))
        (persist/persist-tx! conn [msg-entity]
                             {:op :add-message :msg-id (:message/id msg-entity)})))

    ;; Account tokens if provided
    (when-let [tokens (:tokens message)]
      (account-tokens! chat-ctx (if (= :assistant (:role message))
                                  :output-tokens
                                  :input-tokens)
                       tokens))

    msg-entity))

(defn add-system-note!
  "Inject a system note the agent reads on its NEXT turn — the single seam for
   out-of-band, model-directed feedback: budget alerts, embedder reply notes,
   and correction nudges (code-in-prose, repeated-tool-call), with a
   language-drift guard to come. It is an `:system` message; naming it gives
   these one home and one place to evolve (dedup, or a queue drained at turn
   start). `:important?` marks it protected from compaction pruning."
  [chat-ctx content & {:keys [important?]}]
  (add-message! chat-ctx (cond-> {:role :system :content content}
                           important? (assoc :important? true))))

(defn replace-messages!
  "Replace all messages in the chat context.
   Used by pruning to swap in pruned message versions without adding new messages.

   Args:
     chat-ctx - ChatContext
     new-messages - Complete replacement message vector"
  [chat-ctx new-messages]
  (binding [rtc/*execution-context* (selected-execution-context chat-ctx)]
    (reset! (:messages-signal chat-ctx) (d/deltaable-vector (vec new-messages)))))

(defn set-status!
  "Set chat status.

   Args:
     chat-ctx - ChatContext
     status - :active :paused :completed :cancelled :budget-exceeded"
  [chat-ctx status]
  (binding [rtc/*execution-context* (selected-execution-context chat-ctx)]
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

(defn create-chat-context
  "Create a new root chat context.

   Args:
     opts - Map with:
       :title - Chat title
       :budget-dollars - Budget in dollars (default $1.00)
       :budget - Legacy: budget in microdollars
       :db-path - Path for datahike (default in-memory, or uses Yggdrasil db if registered)
       :with-sci? - Register a fork-selected SCI world component (default true)

   Yggdrasil Integration:
     If a DatahikeSystem is registered in the current execution context
     (via agents/create-shared-context), that connection will be used instead
     of creating a new one. This enables:
     - Persistent file-based storage
     - Automatic branching when agents fork
     - Database inspection after agent runs

   Spindel-ctx selection (priority):
     1. Explicit `:execution-context` opt
     2. The current dynamically-bound `*execution-context*` (so the
        chat-ctx anchors on the surrounding room / daemon ctx)
     3. A fresh root ctx via `ctx/create-execution-context`
        (back-compat path for callers without a room — e.g. standalone
        chat.agent flows, tests)

   The dynamic-bind path is what makes the room (not the chat-ctx)
   the unit of work isolation: when a room is forked via
   `d/fork-room :isolation :ctx`, yggdrasil's registered systems
   (git worktree, datahike branch) live on the fork's ctx. The
   ChatContext retains a stable SCI component ref; callers resolve its
   interpreter through `sci-context-in`, so a fork selects a fork-local heap
   without reconstructing the interpreter."
  [{:keys [chat-id title budget-dollars budget db-path with-sci?
           db-conn execution-context]
    :or {budget-dollars 1.0
         with-sci? true}}]
  (let [chat-id (or chat-id (random-uuid))

        ;; Convert budget to microdollars (numéraire)
        budget-microdollars (or budget
                                (long (* budget-dollars acct/MICRODOLLARS-PER-DOLLAR)))

        ;; Spindel ctx: prefer explicit > bound > fresh.
        spindel-ctx (or execution-context
                        (when (rtc/execution-context-bound?)
                          rtc/*execution-context*)
                        (ctx/create-execution-context))

        ;; Datahike connection — priority: explicit :db-conn > a local ephemeral DB.
        ;; RF5 S4.3: there is no shared chat-db to auto-resolve. The room path passes
        ;; :db-conn = the room's OWN per-room store (where the cost ledger lives);
        ;; room-less callers (sidecar/tests) get the local DB below.
        db-conn (cond
                  db-conn
                  ;; Use explicitly provided connection (e.g. per-room DB)
                  (do
                    (tel/log! {:level :debug :id :chat-ctx/explicit-db-conn} "Using explicitly provided Datahike connection")
                    (schema/ensure-full-schema! db-conn)
                    db-conn)

                  :else
                  ;; Create local connection. Konserve requires :id on
                  ;; every store config — derive it from chat-id for
                  ;; both backends so the file path/memory pool is
                  ;; stable across rebuilds.
                  (let [db-cfg (if db-path
                                 {:store {:backend :file :path db-path :id chat-id}}
                                 {:store {:backend :memory :id chat-id}})]
                    (schema/create-chat-db! db-cfg)))

        ;; Check whether the chat row already exists (true after a
        ;; restart when an upstream caller passes a deterministic
        ;; chat-id). Drives both budget restoration and skipping the
        ;; idempotent re-transact below.
        existing-chat (try
                        (dh/q '[:find (pull ?c [:chat/budget-total]) .
                                :in $ ?cid
                                :where [?c :chat/id ?cid]]
                              @db-conn chat-id)
                        (catch Exception _ nil))

        ;; Reconstruct used + per-type usage from the LEDGER SUM — the
        ;; ledger is the source of truth; :chat/budget-used is a derived
        ;; rollup (record-usage! keeps it in sync in the same transact,
        ;; but the sum is the authoritative restore).
        [restored-used restored-by-type]
        (if existing-chat
          (try
            (let [cost-q (dh/q '[:find (sum ?c) .
                                 :in $ ?cid
                                 :where
                                 [?l :ledger/context ?ctx]
                                 [?ctx :chat/id ?cid]
                                 [?l :ledger/cost-microdollars ?c]]
                               @db-conn chat-id)
                  by-type (dh/q '[:find ?rsrc (sum ?amt)
                                  :in $ ?cid
                                  :where
                                  [?l :ledger/context ?ctx]
                                  [?ctx :chat/id ?cid]
                                  [?l :ledger/resource ?rsrc]
                                  [?l :ledger/amount ?amt]]
                                @db-conn chat-id)]
              [(or cost-q 0)
               (into {} (map (fn [[t amt]] [t amt])) by-type)])
            (catch Exception _ [0 {}]))
          [0 {}])

        initial-budget
        (if existing-chat
          {:total (or (:chat/budget-total existing-chat) budget-microdollars)
           :used  restored-used
           :by-type restored-by-type
           :crossed-thresholds #{}}
          {:total budget-microdollars
           :used 0
           :by-type {}
           :crossed-thresholds #{}})

        ;; Create signals within the owning execution context.
        [messages-signal budget-signal status-signal]
        (binding [rtc/*execution-context* spindel-ctx]
          [(sig/signal (d/deltaable-vector []))
           (sig/signal initial-budget)
           (sig/signal :active)])

        ;; Create chat entity in datahike — only when fresh; on restore
        ;; we don't want :chat/budget-total / :chat/budget-used clobbered.
        _ (when-not existing-chat
            (dh/transact db-conn
                         [(schema/create-chat-entity
                           {:id chat-id
                            :title (or title "Untitled Chat")
                            :budget budget-microdollars})]))

        ;; Allocate the process-local interpreter only after every fallible
        ;; durable/signal initialization step. Once registered, no operation
        ;; below can strand the component without returning its ChatContext.
        sci-component (when with-sci?
                        (sandbox/create-spindel-sci-world! spindel-ctx))]

    (->ChatContext
     chat-id
     (or title "Untitled Chat")
     spindel-ctx
     messages-signal
     budget-signal
     status-signal
     db-conn
     sci-component)))

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
  "Cancel a chat. Owned child computations are cancelled through their Runs."
  [chat-ctx]
  (set-status! chat-ctx :cancelled))

(defn release-sci-in!
  "Release `chat-ctx`'s SCI realization from one execution world.

   This is idempotent and does not close the chat's durable connection. Forked
   room caches use it when a child world is discarded."
  [chat-ctx execution-context]
  (try
    (sandbox/release-agent-resources! execution-context (:capability-id chat-ctx))
    (finally
      ;; Disposable-resource cleanup is best-effort with respect to component
      ;; reachability: even an unexpected backend failure must not strand the
      ;; interpreter in Spindel after its cache handle is removed.
      (sandbox/release-spindel-sci-world!
       execution-context (:sci-component chat-ctx)))))

(defn close-chat!
  "Close chat and release resources."
  [chat-ctx]
  (binding [rtc/*execution-context* (:spindel-ctx chat-ctx)]
    (release-sci-in! chat-ctx (:spindel-ctx chat-ctx))
    ;; Close datahike connection
    (when-let [conn (:db-conn chat-ctx)]
      (dh/release conn)))

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

(defn load-messages
  "Load persisted messages for `chat-id` from datahike, ordered by
   :message/created-at. Returns a vector of entity maps in the same
   shape `add-message!` writes — empty vector if the chat row is
   absent or the connection is nil.

   Used to re-hydrate a freshly-created ChatContext from durable state
   after a daemon restart."
  [db-conn chat-id]
  (if (nil? db-conn)
    []
    (try
      (let [db @db-conn
            results (dh/q '[:find ?m ?ts
                            :in $ ?cid
                            :where
                            [?c :chat/id ?cid]
                            [?m :message/chat ?c]
                            [?m :message/created-at ?ts]]
                          db chat-id)
            ordered (sort-by second results)]
        (mapv (fn [[eid _]]
                (dh/pull db '[*] eid))
              ordered))
      (catch Exception e
        (tel/log! {:level :warn :id :chat-ctx/load-messages-error
                   :data {:chat-id chat-id :error (.getMessage e)}}
                  "Failed to load persisted messages")
        []))))

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
      (binding [rtc/*execution-context* (selected-execution-context chat-ctx)]
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

  ;; Bounded child work is a Run in a canonical world. Compose its result Spin
  ;; through dvergr.agent instead of creating a second ChatContext hierarchy.

  ;; Cleanup
  (close-chat! chat))
