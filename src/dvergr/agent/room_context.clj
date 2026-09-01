(ns dvergr.agent.room-context
  "Per-[room, agent] working ChatContext (design \"D\").

   Instead of rebuilding a fresh chat-ctx every execution, each (room, agent)
   pair gets one long-lived provider working context. The shared Room log stays
   the durable source of facts; explicit attention decisions project which of
   those facts enter this participant's model input.

   ## Consistency model (see doc/per-room-chat-ctx.md)

   - The active discourse interpreter is the sole live admission point. There
     is deliberately no eager bus fold: observing a Room fact must not make an
     `:ignore` or `:remember` decision indistinguishable from `:include`.
   - Attention decisions are typed durable Room activity facts. On recovery,
     Run triggers, the agent's own outputs, and explicit `:include` decisions
     rebuild provider input; `:remember` remains Room awareness without being
     sent to the model.
   - Dedup is by message `:id` through a synchronized set, so a trigger already
     present in the seed and its live admission cannot appear twice."
  (:require [dvergr.actors :as actors]
            [dvergr.discourse :as d]
            [dvergr.agent.run :as run]
            [dvergr.agent.turn :as turn]
            [dvergr.chat.context :as chat-ctx]
            [dvergr.chat.schema :as schema]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store :as rstore]
            [dvergr.chat.accounting :as acct]
            [dvergr.system.db :as sdb]
            [dvergr.system.rooms :as srooms]
            [org.replikativ.spindel.engine.core :as ec]
            [taoensso.telemere :as tel]))

;; [room-id agent-id] → {:chat-ctx ChatContext :seen java.util.Set
;;                       :execution-ctx ExecutionContext :sandbox-opts map}
(defonce ^:private room-agent-ctxs (atom {}))

(defn room-system-id
  "The system-db room UUID for `room`, or — for a FORK — its nearest provisioned
   ancestor's. A fork isn't a system-db room; it shares its parent's yggdrasil
   systems (branched under the fork ctx), so the parent's UUID is what the system
   resolvers (`room-kb-conn` / `room-kbs` / `room-msgs`) key on, and `ygg/system`
   under the fork's bound ctx then hands back the BRANCHED conn. Walks `:parent-id`
   up the fork chain (bounded)."
  [room]
  (loop [r room, guard 0]
    (when (and r (< guard 8))
      (or (some-> (sdb/room-by-slug (:slug r)) :room/id)
          (recur (some-> (:parent-id r) rreg/lookup) (inc guard))))))

(defn stable-chat-id
  "Deterministic chat-id for a (room, agent) pair, so the same conversation
   reuses one chat row across turns and restarts (budget restore + coherent
   writes) instead of a throwaway uuid each turn."
  [room-id agent-id]
  (java.util.UUID/nameUUIDFromBytes
   (.getBytes (str "dvergr-room-ctx|" room-id "|" agent-id))))

(defn- conversational?
  "True for a message that belongs in an agent's working conversation —
   excludes tool-activity play-by-play and stray system notes."
  [m]
  (let [meta (:metadata m)]
    (and (string? (:content m)) (not= "" (:content m))
         (not= :_activity (:to m))
         (not (#{:tool :system} (or (:role m) (:role meta))))
         (not (= :activity (:kind meta))))))

(defn- append-signal-only!
  "Append a message to a chat-ctx's messages-signal WITHOUT writing datahike.
   Inbound conversational messages are already durable in the room store (the
   bus persistence listener wrote them); re-transacting them under the chat-id
   would be a redundant second write — and on the engine thread. So the seed +
   fold update only the in-memory projection; the room store stays the sole
   durable record of the conversation (consistency contract point 1)."
  [chat-ctx msg]
  (let [entity (schema/create-message-entity (assoc msg :chat-id (:chat-id chat-ctx)))]
    (binding [ec/*execution-context* (:spindel-ctx chat-ctx)]
      (swap! (:messages-signal chat-ctx) conj entity))))

(defn- fmt-clock
  "Epoch-millis or Date → \"HH:mm\", or nil."
  [ts]
  (when ts
    (try (.format (java.text.SimpleDateFormat. "HH:mm")
                  (if (instance? java.util.Date ts) ts (java.util.Date. (long ts))))
         (catch Throwable _ nil))))

(defn display-name
  "Display label for actor `from` in `room` — the actor's `:name`, else the id."
  [room from]
  (or (try (some-> (actors/lookup (sdb/get-conn) from) :name)
           (catch Throwable _ nil))
      (some-> from name)))

(defn- append-inbound-to!
  [chat-ctx seen msg-id role content author ts reasoning]
  (when (.add ^java.util.Set seen msg-id)
    (let [decorated (if (and author (not= :system role))
                      (str "[" author (when-let [t (fmt-clock ts)] (str " · " t)) "] " content)
                      content)]
      (append-signal-only! chat-ctx (cond-> {:role role :content decorated}
                                      (seq reasoning) (assoc :reasoning reasoning))))
    true))

(defn append-inbound!
  "Append one inbound conversational message to the (room, agent) ctx's signal.

   `room-or-id` should be the Room for lifecycle-safe admission during first
   hydration; the id form remains for callers that target an already-published
   cache entry.

   EXACTLY ONCE (deduped by `msg-id` via the entry's synchronized set). Used by
   the attention interpreter. Signal-only (the room store already holds it
   durably). Returns true if appended.

   `author` is the sender's display label (nil for the agent's own messages); when
   present the content is prefixed `[author · HH:mm]` so the agent knows WHO spoke
   and WHEN — essential in multi-party rooms where roles alone are ambiguous."
  ([room-or-id agent-id msg-id role content author ts]
   (append-inbound! room-or-id agent-id msg-id role content author ts nil))
  ([room-or-id agent-id msg-id role content author ts reasoning]
   (let [room? (map? room-or-id)
         room-id (if room? (:id room-or-id) room-or-id)
         k [room-id agent-id]
         room-meta (or (when room? (:meta room-or-id))
                       (:room-meta (get @room-agent-ctxs k)))]
     (when room-meta
       ;; Admission may race first hydration. Sharing the lifecycle fence makes
       ;; the append wait for the fully seeded cache publication instead of
       ;; observing an absent/partial entry and dropping a durable attention fact.
       (locking room-meta
         (binding [ec/*execution-context* (if room?
                                            (:ctx room-or-id)
                                            ec/*execution-context*)]
           (when (or (not room?) (rreg/admitted-incarnation? room-or-id))
             (when-let [{:keys [chat-ctx seen]} (get @room-agent-ctxs k)]
               ;; `reasoning` (the agent's OWN prior <think> trace) rides along so
               ;; reasoning models keep it across a rehydrate — see create-message-entity
               ;; (:reasoning → :message/reasoning) + messages->api-format.
               (append-inbound-to! chat-ctx seen msg-id role content author ts reasoning)))))))))

(defn raise-budget!
  "Set the live budget :total on every cached agent ctx of `room-id` to
   `dollars`. The embedder calls this when its room-budget setting
   changes, so an agent paused at a budget checkpoint resolves
   :extended and continues instead of wrapping up. Returns the number
   of ctxs updated."
  [room-id dollars]
  (let [micro (long (* (double dollars)
                       acct/MICRODOLLARS-PER-DOLLAR))]
    (reduce (fn [n [[rid _] {:keys [chat-ctx]}]]
              (if (and (= rid room-id) chat-ctx)
                (do (binding [ec/*execution-context* (:spindel-ctx chat-ctx)]
                      (swap! (:budget-signal chat-ctx) assoc :total micro))
                    (inc n))
                n))
            0
            @room-agent-ctxs)))

(defn- hydration-messages!
  "Complete all fallible durable hydration before allocating live signals.

   Attention baseline writes are deterministic/idempotent, so an interrupted
   attempt can retry. The returned messages are pure data ready to project into
   a newly constructed ChatContext without further store access."
  [room agent-id limit]
  (let [messages (d/messages room {:limit (or limit 100)})
        attention-store? (satisfies? rstore/PAttentionStore (:store room))
        conversation-id (d/conversation-id room)
        baseline-message-id
        (rstore/attention-id conversation-id agent-id nil nil
                             :legacy-baseline-message)
        baseline-marker-id
        (rstore/attention-id conversation-id agent-id baseline-message-id
                             nil :legacy-baseline-complete)
        baseline-complete?
        (and attention-store?
             (seq (rstore/-list-attention (:store room)
                                          conversation-id
                                          {:id baseline-marker-id})))
        ;; Upgrade cutover: pre-attention rooms already have Runs but no
        ;; participant projection. Materialize their exact provider baseline
        ;; before any new policy decision can make the projection non-empty.
        _ (when (and attention-store? (not baseline-complete?))
            (doseq [m messages :when (conversational? m)]
              (let [decision-id
                    (rstore/attention-id conversation-id agent-id (:id m) nil
                                         :legacy-baseline-decision)
                    common {:attention/participant agent-id
                            :attention/message-id (:id m)
                            :attention/memory :include
                            :attention/activation :none
                            :attention/control :continue
                            :attention/at :now
                            :attention/priority 0.0
                            :attention/reason :migration/provider-baseline
                            :attention/created-at
                            (java.util.Date. (long (or (:ts m)
                                                       (System/currentTimeMillis))))}]
                (rstore/-store-attention!
                 (:store room) conversation-id
                 (assoc common
                        :attention/id decision-id
                        :attention/status :ready))
                (rstore/-store-attention!
                 (:store room) conversation-id
                 (assoc common
                        :attention/id
                        (rstore/attention-id conversation-id agent-id (:id m) nil
                                             :legacy-baseline-applied)
                        :attention/decision-id decision-id
                        :attention/status :applied))))
            ;; Written last. If any prior write fails, the next hydration
            ;; retries every deterministic pair and completes the cutover.
            (rstore/-store-attention!
             (:store room) conversation-id
             {:attention/id baseline-marker-id
              :attention/participant agent-id
              :attention/message-id baseline-message-id
              :attention/status :baseline-complete
              :attention/reason :migration/provider-baseline
              :attention/created-at (java.util.Date.)}))
        attention-facts
        (if attention-store?
          (rstore/-list-attention (:store room)
                                  conversation-id
                                  {:participant agent-id :limit 1000})
          [])
        ;; Admission is monotone: once a fact entered provider context (most
        ;; notably when queued work becomes a Run trigger), a later observation
        ;; cannot silently erase it. Deliberate forgetting is compaction policy.
        included-message-ids
        (into #{}
              (keep #(when (and (= :applied (:attention/status %))
                                (= :include (:attention/memory %)))
                       (:attention/message-id %)))
              attention-facts)
        agent-runs (run/runs room {:actor agent-id :limit 1000})
        trigger-ids (into #{} (map :run/trigger) agent-runs)
        ;; Ephemeral/custom stores without the projection retain historical
        ;; full-transcript behavior for compatibility.
        legacy-history? (not attention-store?)]
    (into []
          (filter #(and (conversational? %)
                        (or (= agent-id (:from %))
                            (contains? included-message-ids (:id %))
                            (contains? trigger-ids (:id %))
                            legacy-history?)))
          messages)))

(defn ensure-ctx!
  "Get-or-create the long-lived working chat-ctx for `agent-id` in `room`.

   On first call, create the ctx with a stable chat-id and seed its durable
   attention projection from the Room store. Subsequent calls return the
   cached ctx; live facts enter only through explicit interpreter admission."
  [room agent-id {:keys [system-prompt budget-dollars limit]}]
  (let [room-id (:id room)
        k       [room-id agent-id]]
    ;; Room forking and unregister hold the same monitor. Cache reads belong
    ;; inside it too: a returned handle must remain live through this boundary.
    (locking (:meta room)
      (binding [ec/*execution-context* (:ctx room)]
        (let [_ (when-not (rreg/admitted-incarnation? room)
                  (throw (ex-info "Working context requested for a closed Room incarnation"
                                  {:type ::closed-room
                                   :room-id room-id
                                   :room/incarnation (:incarnation room)})))]
          (or (:chat-ctx (get @room-agent-ctxs k))
              (let [;; The room's system-db UUID — the key the system resolvers
                ;; (room-kb-conn / room-kbs / room-msgs) want; distinct from the
                ;; in-memory keyword `room-id`. Threads to the sandbox so `dvergr.room`
                ;; + the guarded `d/connect`/`list-databases` resolve THIS room's
                ;; fork-aware databases.
                    room-uuid (room-system-id room)
                    sandbox-opts
                    {:execution-ctx  (:ctx room)
                     :chat-id        (stable-chat-id room-id agent-id)
                     :title          (str (name agent-id) "-" (name room-id))
                     :budget-dollars budget-dollars
                        ;; RF5 S4: the cost ledger (account-usage!) writes to THIS
                        ;; room's own msgs store — per-room, fork-aware — not the
                        ;; legacy chat-db. nil ⇒ create-chat-context auto-resolves
                        ;; (room-less fallback only).
                     :db-conn        (some-> room :store :conn)
                        ;; The agent's sandbox reaches its room's OWN knowledge base
                        ;; (fork-aware) through `dvergr.room/*kb*` — resolved here
                        ;; under the room's bound ctx (so a fork hands the branched
                        ;; KB). nil for room-less ctxs. The sandbox must NEVER touch
                        ;; system-db for knowledge; this is how the room KB gets in.
                     :kb-conn        (some-> room-uuid srooms/room-kb-conn)
                     :room-id        room-uuid
                       ;; Room registry identity is distinct from the system-db
                       ;; UUID above. SCI's agent-programming surface needs the
                       ;; former so a hired agent can recursively hire into this
                       ;; exact live Room, including an ephemeral fork.
                     :room-runtime-id room-id
                     :room-incarnation (:incarnation room)
                        ;; Per-agent network egress scope: an actor's optional
                        ;; `:config {:allowed-domains #{"https://…"}}` restricts the
                        ;; sandbox `http` primitive (nil/empty ⇒ open).
                     :allowed-domains (some-> (actors/lookup (sdb/get-conn) agent-id)
                                              :config :allowed-domains)
                        ;; The room store (bus→store listener) is the single durable
                        ;; writer for this conversation; the agent's own turn messages
                        ;; stay signal-only (no redundant datahike write). Token
                     ;; accounting still persists.
                     :durable?       false}
                    messages (hydration-messages! room agent-id limit)
                    cctx (turn/new-working-ctx sandbox-opts)
                    bound-sandbox-opts (assoc sandbox-opts
                                              :capability-id (:capability-id cctx))
                    seen (java.util.Collections/synchronizedSet (java.util.HashSet.))
                    entry {:chat-ctx cctx
                           :seen seen
                           :execution-ctx (:ctx room)
                           :room-meta (:meta room)
                           :sandbox-opts bound-sandbox-opts}]
                (try
            ;; System prompt once, then seed the conversation from the store.
            ;; Signal-only: the prompt is regenerated each session, not durable.
                  (when system-prompt
                    (append-signal-only! cctx {:role :system :content system-prompt}))
                  ;; Durable hydration above completed before signal allocation.
                  ;; Project the staged pure messages into the fresh context.
                  (doseq [m messages]
                    (append-inbound-to! cctx seen (:id m)
                                        (or (:role m) (if (= agent-id (:from m)) :assistant :user))
                                        (:content m)
                                        ;; author nil for the agent's own history
                                        (when (not= agent-id (:from m))
                                          (display-name room (:from m)))
                                        (:ts m)
                                        ;; feed back only the agent's own reasoning
                                        (when (= agent-id (:from m)) (:reasoning m))))
                  (tel/log! {:level :debug :id ::created
                             :data {:room room-id :agent agent-id
                                    :seeded (count (chat-ctx/get-messages cctx))}})
                  ;; Publication is the commit point. Before this swap, no
                  ;; concurrent fast path can observe partially hydrated state.
                  (swap! room-agent-ctxs assoc k entry)
                  cctx
                  (catch Throwable error
                    (try
                      (chat-ctx/release-sci-in! cctx (:ctx room))
                      (catch Throwable cleanup-error
                        (.addSuppressed error cleanup-error)))
                    (throw error))))))))))

(defn fork-ctx!
  "Project an existing parent room/agent working context into `child-room`.

   Yggdrasil has already forked the Spindel world, including the ChatContext's
   signals and SCI component. The child facade replaces persistence and ambient
   capabilities without reconstructing the interpreter or replaying history;
   provider admission remains exclusively controlled by attention."
  [parent-room child-room agent-id]
  (let [parent-k [(:id parent-room) agent-id]
        child-k [(:id child-room) agent-id]]
    (when-let [{:keys [chat-ctx seen sandbox-opts]} (get @room-agent-ctxs parent-k)]
      (or (:chat-ctx (get @room-agent-ctxs child-k))
          (when (try
                  (chat-ctx/sci-context-in chat-ctx (:ctx child-room))
                  true
                  (catch clojure.lang.ExceptionInfo _ false))
            (let [room-uuid (binding [ec/*execution-context* (:ctx child-room)]
                              (room-system-id child-room))
                  child-opts (assoc sandbox-opts
                                    :execution-ctx (:ctx child-room)
                                    :db-conn (some-> child-room :store :conn)
                                    :kb-conn (some-> room-uuid srooms/room-kb-conn)
                                    :room-id room-uuid
                                    :room-runtime-id (:id child-room)
                                    :room-incarnation (:incarnation child-room)
                                    :fork-projection? true
                                    :capability-id (:capability-id chat-ctx))
                  projected (assoc chat-ctx
                                   :spindel-ctx (:ctx child-room)
                                   :db-conn (some-> child-room :store :conn))
                  _ (turn/rebind-working-ctx! projected child-opts)
                  child-seen (java.util.Collections/synchronizedSet
                              (locking seen
                                (java.util.HashSet. ^java.util.Collection seen)))
                  entry {:chat-ctx projected
                         :seen child-seen
                         :execution-ctx (:ctx child-room)
                         :room-meta (:meta child-room)
                         :sandbox-opts child-opts}]
              (swap! room-agent-ctxs
                     (fn [m]
                       (if (contains? m child-k) m (assoc m child-k entry))))
              (:chat-ctx (get @room-agent-ctxs child-k))))))))

(defn- drop-entry! [room-id agent-id]
  (when-let [{:keys [chat-ctx execution-ctx]}
             (get @room-agent-ctxs [room-id agent-id])]
    (when chat-ctx
      (try (chat-ctx/release-sci-in! chat-ctx
                                     (or execution-ctx (:spindel-ctx chat-ctx)))
           (catch Throwable _ nil)))
    (swap! room-agent-ctxs dissoc [room-id agent-id])))

(defn drop-ctx!
  "Tear down the (room, agent) provider projection."
  [room-id agent-id]
  (if-let [room-meta (:room-meta (get @room-agent-ctxs [room-id agent-id]))]
    (locking room-meta (drop-entry! room-id agent-id))
    (drop-entry! room-id agent-id))
  nil)

(defn drop-room!
  "Drop all agent ctxs for a room (room delete / fork discard)."
  [room-id]
  (doseq [[rid aid] (keys @room-agent-ctxs)
          :when (= rid room-id)]
    (drop-ctx! rid aid))
  nil)

(defn- close-room-contexts!
  "Fence one registered Room incarnation against further context access.

   Runs as a pre-unregister hook while the Room is still authoritative. The
   shared monitor makes the closed marker and complete component teardown one
   lifecycle transition, so a stale caller cannot publish after cleanup."
  [room]
  (locking (:meta room)
    (doseq [[rid aid] (keys @room-agent-ctxs)
            :when (= rid (:id room))]
      (drop-entry! rid aid)))
  nil)

(defn clear-all!
  "Drop every cached ctx (daemon stop — the cache is a defonce that survives a
   same-process restart, so a fresh start must not reuse stale projections)."
  []
  (doseq [[rid aid] (keys @room-agent-ctxs)]
    (drop-ctx! rid aid))
  nil)

(defn lookup
  "The cached chat-ctx for [room-id agent-id], or nil."
  [room-id agent-id]
  (:chat-ctx (get @room-agent-ctxs [room-id agent-id])))

;; Fence + tear down before registry removal. Keeping this in the lifecycle
;; transaction prevents stale scheduler/MCP callers from recreating a component
;; after a post-unregister key snapshot.
(rreg/add-pre-unregister-hook! ::close-room-contexts close-room-contexts!)
