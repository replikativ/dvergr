(ns dvergr.participant.context
  "Unified per-participant context — generalizes `dvergr.chat.context`
   to every participant strain in dvergr.discourse-model.md §5.4a:
   LLM, human, hybrid, scripted. The bialgebra structure is the same
   across strains; what differs is the F-algebra's location (transformer
   + tools, opaque brain, mix) and the shape of the participant's
   memory.

   A `ParticipantContext` carries the **common** state any participant
   may need:

     - `:participant-id`  — stable id, matches the discourse Participant.
     - `:role`            — :llm | :human | :hybrid | :scripted.
     - `:ctx`             — the participant's private spindel execution
                            context (own signals, atoms, drain). Forked
                            uniformly by `dvergr.discourse/fork-room
                            {:isolation :ctx}` regardless of role. Same
                            name as `Room.ctx`.
     - `:memory-signal`   — Deltaable vector of memory entries. For an
                            LLM agent these are chat messages; for a
                            human they're saved notes / received items;
                            for a hybrid both interleaved. The shape is
                            uniform from spindel's POV.
     - `:budget-signal`   — `{:total :used :by-type :crossed-thresholds}`
                            in microdollars. nil for humans (no LLM
                            cost), set for LLMs and hybrids.
     - `:status-signal`   — :active | :paused | :completed | :cancelled.
     - `:db-conn`         — datahike connection for durable persistence
                            (often yggdrasil-managed so substrate-fork
                            branches it automatically).
     - `:role-data`       — map of role-specific extensions. LLM puts
                            `:sci-ctx` and `:tool-ctx` here; human can
                            stash UI prefs, presence signal, etc. Stays
                            out of the common record so role-specific
                            additions don't churn the API.

   Why this matters: with `:isolation :ctx`, `fork-room` branches every
   yggdrasil system registered on the parent ctx. If each participant's
   `db-conn` is a yggdrasil system, the fork's participant sees a
   branched DB; on `merge-room`, the merge is atomic across all
   participants' DBs via spindel.yggdrasil. Theory-of-mind probes and
   speculative coding-agent work compose under the same primitive.

   The existing `dvergr.chat.context/ChatContext` is the historical name
   for the :llm role's shape; `from-chat-context` / `to-chat-context`
   translate without copying signals (they SHARE the signal refs)."
  (:require [dvergr.chat.context :as cc]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.signal :as sig]
            [org.replikativ.spindel.core :as d]))

;; ===========================================================================
;; ParticipantContext record
;; ===========================================================================

(defrecord ParticipantContext
           [participant-id
            role             ;; :llm | :human | :hybrid | :scripted
            ctx              ;; spindel execution context
            memory-signal
            budget-signal
            status-signal
            db-conn
            role-data])

;; ===========================================================================
;; Factories
;; ===========================================================================

(defn create-human-context
  "Construct a ParticipantContext for a human participant.

   Required:
     :participant-id — stable id (uuid / keyword)

   Optional:
     :ctx       — own execution context; if not provided, a fresh one
                  is created via `ctx/create-execution-context`.
     :db-conn   — datahike conn for persistent memory (notes, prefs).
                  Often yggdrasil-managed so substrate-fork branches it.
                  nil ⇒ no durable persistence (signal-only).
     :role-data — initial role-data map (UI prefs, presence, …)."
  [{:keys [participant-id ctx db-conn role-data]}]
  {:pre [(some? participant-id)]}
  (let [sctx (or ctx (ctx/create-execution-context))
        [memory-signal status-signal]
        (binding [rtc/*execution-context* sctx]
          [(sig/signal (d/deltaable-vector []))
           (sig/signal :active)])]
    (->ParticipantContext participant-id
                          :human
                          sctx
                          memory-signal
                          nil                    ; no budget for humans
                          status-signal
                          db-conn
                          (or role-data {}))))

(defn create-llm-context
  "Construct a ParticipantContext for an LLM participant.

   Wraps an existing `dvergr.chat.context/ChatContext` so the LLM
   participant gets the full chat plumbing (messages, budget, sci-ctx,
   tools) under the unified shape. Pass an existing ChatContext via
   `:from-chat-ctx`, or pass the opts the chat-ctx factory accepts and
   it will be created.

   Required:
     :participant-id — stable id (uuid / keyword)

   One of:
     :from-chat-ctx  — an existing dvergr.chat.context/ChatContext
     :chat-ctx-opts  — opts map passed to cc/create-chat-context

   Role-data carries the chat-ctx-only fields (`:chat-ctx`, `:sci-ctx`)
   so existing dvergr.chat.context callers can still reach them via
   `(:role-data pctx)`."
  [{:keys [participant-id from-chat-ctx chat-ctx-opts role-data]}]
  {:pre [(some? participant-id)
         (or from-chat-ctx chat-ctx-opts)]}
  (let [chat-ctx (or from-chat-ctx (cc/create-chat-context chat-ctx-opts))]
    (->ParticipantContext participant-id
                          :llm
                          (:spindel-ctx chat-ctx)
                          (:messages-signal chat-ctx)
                          (:budget-signal chat-ctx)
                          (:status-signal chat-ctx)
                          (:db-conn chat-ctx)
                          (merge {:chat-ctx chat-ctx
                                  :sci-ctx (:sci-ctx chat-ctx)}
                                 role-data))))

;; ===========================================================================
;; Generic accessors
;; ===========================================================================

(defn get-memory
  "Read the current memory vector. Role-uniform: LLM messages, human
   notes, hybrid mix."
  [pctx]
  (binding [rtc/*execution-context* (:ctx pctx)]
    @(:memory-signal pctx)))

(defn append-memory!
  "Append `entry` to the participant's memory. For :llm role with a
   wrapped ChatContext this also routes through dvergr.chat.context/
   add-message! so datahike persistence + token accounting fire. For
   :human role, just appends to the signal (and to db-conn if any).

   The expected entry shape is role-specific:
     :llm    — `{:role :user|:assistant|:system :content '…' …}`
     :human  — `{:from <id> :content '…' :ts <ms>}` (mirrors
                dvergr.discourse Message)"
  [pctx entry]
  (case (:role pctx)
    :llm (when-let [chat-ctx (get-in pctx [:role-data :chat-ctx])]
           (cc/add-message! chat-ctx entry))
    ;; default: signal-only append
    (binding [rtc/*execution-context* (:ctx pctx)]
      (swap! (:memory-signal pctx) conj entry))))

(defn get-status
  "Read the participant's status signal (:active / :paused / …)."
  [pctx]
  (binding [rtc/*execution-context* (:ctx pctx)]
    @(:status-signal pctx)))

(defn set-status!
  "Set the participant's status."
  [pctx new-status]
  (binding [rtc/*execution-context* (:ctx pctx)]
    (reset! (:status-signal pctx) new-status)))

(defn get-role-data
  "Pull a key out of the role-specific extension map."
  ([pctx]     (:role-data pctx))
  ([pctx k]   (get (:role-data pctx) k))
  ([pctx k v] (get (:role-data pctx) k v)))

(defn assoc-role-data
  "Return a new ParticipantContext with k → v merged into :role-data."
  [pctx k v]
  (update pctx :role-data assoc k v))

;; ===========================================================================
;; Past-arc state accessors
;;
;; Read-only views over the participant's budget + memory. Watch-and-fire
;; primitives (threshold-crossed deferreds, compaction triggers, cancel
;; signals) live in `dvergr.discourse.generation` where they're constructed
;; per-generation and raced against the generation-handle.
;; ===========================================================================

(defn budget-fraction-used
  "Return (used / total) as a double, or 0.0 if no budget set / total 0."
  [pctx]
  (when-let [b (:budget-signal pctx)]
    (binding [rtc/*execution-context* (:ctx pctx)]
      (let [{:keys [total used]} @b]
        (if (and (number? total) (pos? total))
          (/ (double (or used 0)) total)
          0.0)))))

(defn crossed-thresholds
  "Return the set of budget threshold fractions already crossed."
  [pctx]
  (when-let [b (:budget-signal pctx)]
    (binding [rtc/*execution-context* (:ctx pctx)]
      (or (:crossed-thresholds @b) #{}))))

(defn memory-size
  "Number of entries in the participant's memory signal."
  [pctx]
  (when-let [m (:memory-signal pctx)]
    (binding [rtc/*execution-context* (:ctx pctx)]
      (count @m))))

(defn budget-update!
  "Mutate the budget-signal via `f`. `f` receives the current
   `{:total :used :by-type :crossed-thresholds}` and returns the new one.
   No-op when the participant has no budget."
  [pctx f]
  (when-let [b (:budget-signal pctx)]
    (binding [rtc/*execution-context* (:ctx pctx)]
      (swap! b f))))

;; ===========================================================================
;; Bridge: existing ChatContext interop
;; ===========================================================================

(defn from-chat-context
  "Wrap an existing `dvergr.chat.context/ChatContext` as the :llm-role
   ParticipantContext for `participant-id`. SHARES signal refs (no copy)."
  [participant-id chat-ctx]
  (create-llm-context {:participant-id participant-id
                       :from-chat-ctx chat-ctx}))

(defn ->chat-context
  "Extract the wrapped `dvergr.chat.context/ChatContext` from a
   :llm-role ParticipantContext. Returns nil for other roles."
  [pctx]
  (when (= :llm (:role pctx))
    (get-in pctx [:role-data :chat-ctx])))
