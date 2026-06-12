(ns dvergr.agent.room-context
  "Per-[room, agent] working ChatContext (design \"D\").

   Instead of rebuilding a fresh chat-ctx every turn (re-reading + re-writing
   the whole room log under a throwaway chat-id, all on the engine thread),
   each (room, agent) pair gets ONE long-lived chat-ctx, created once with a
   stable chat-id and kept current by a fold over the room bus.

   ## Consistency model (see doc/per-room-chat-ctx.md)

   The room bus is a per-room, totally-ordered message log. The room store
   (disk) and this chat-ctx's messages-signal (memory) are two folds of that
   same log — consistent by construction as long as every durable message
   write goes through the bus.

   - The signal is SEEDED once from the room store (conversation), then a fold
     spin appends each new bus message FROM OTHERS (`:from` ≠ self), skipping
     tool-activity. The agent's own turns are appended by the turn loop; the
     fold skips self, so there is no double-add.
   - Dedup is by message `:id` via a synchronized set (`.add` is atomic and
     returns true only on first insert), so the seed and the live fold are
     idempotent and race-free regardless of ordering — `append-inbound!` is the
     single deduped appender, called by both the fold and the turn handler (for
     the just-arrived message).
   - Recovery = re-seed from the store. The store is the durable bottom; the
     signal is always rebuildable from it."
  (:require [dvergr.actors :as actors]
            [dvergr.discourse :as d]
            [dvergr.agent.turn :as turn]
            [dvergr.chat.context :as chat-ctx]
            [dvergr.chat.schema :as schema]
            [dvergr.room.registry :as rreg]
            [dvergr.runtime.bus :as bus]
            [dvergr.system.db :as sdb]
            [dvergr.system.rooms :as srooms]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.engine.core :as ec]
            [is.simm.partial-cps.sequence :refer [anext]]
            [taoensso.telemere :as tel]))

;; [room-id agent-id] → {:chat-ctx ChatContext :seen java.util.Set :sub Subscription}
(defonce ^:private room-agent-ctxs (atom {}))

(defn- provisioned-uuid
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

(defn append-inbound!
  "Append one inbound conversational message to the (room, agent) ctx's signal,
   EXACTLY ONCE (deduped by `msg-id` via the entry's synchronized set). Used by
   both the bus fold and the turn handler (for the just-arrived message), so
   whichever runs first wins and the other no-ops. Signal-only (the room store
   already holds it durably). Returns true if appended.

   `author` is the sender's display label (nil for the agent's own messages); when
   present the content is prefixed `[author · HH:mm]` so the agent knows WHO spoke
   and WHEN — essential in multi-party rooms where roles alone are ambiguous."
  ([room-id agent-id msg-id role content author ts]
   (append-inbound! room-id agent-id msg-id role content author ts nil))
  ([room-id agent-id msg-id role content author ts reasoning]
   (when-let [{:keys [chat-ctx seen]} (get @room-agent-ctxs [room-id agent-id])]
     (when (.add ^java.util.Set seen msg-id)
       (let [decorated (if (and author (not= :system role))
                         (str "[" author (when-let [t (fmt-clock ts)] (str " · " t)) "] " content)
                         content)]
         ;; `reasoning` (the agent's OWN prior <think> trace) rides along so
         ;; reasoning models keep it across a rehydrate — see create-message-entity
         ;; (:reasoning → :message/reasoning) + messages->api-format.
         (append-signal-only! chat-ctx (cond-> {:role role :content decorated}
                                         (seq reasoning) (assoc :reasoning reasoning))))
       true))))

(defn- start-fold!
  "Subscribe to `room`'s bus and append each new conversational message FROM
   OTHERS (≠ self) to `cctx`, deduped by id. Returns the Subscription. The
   pump exits when the subscription is closed (drop-ctx!)."
  [room self-id]
  (binding [ec/*execution-context* (:ctx room)]
    (let [sub (bus/subscribe! (:bus room) [:type :user/message])]
      (sp/spawn!
       (spin
        (loop [s (:aseq sub)]
          (when-let [r (sp/await (anext s))]
            (let [[m rest-s] r]
              (when (and (not= self-id (:from m)) (conversational? m))
                (let [role (or (:role m) (:role (:metadata m)) :user)]
                  (append-inbound! (:id room) self-id (:id m) role (:content m)
                                   (display-name room (:from m)) (:ts m))))
              (recur rest-s))))))
      sub)))

(defn ensure-ctx!
  "Get-or-create the long-lived working chat-ctx for `agent-id` in `room`.

   On first call: create the ctx with a stable chat-id, register it (so the
   fold + seed can find it), subscribe the fold, then SEED the conversation
   from the room store. Subscribe-before-seed + id-dedup closes the seed/live
   race. Subsequent calls return the cached ctx — no per-turn rebuild."
  [room agent-id {:keys [system-prompt budget-dollars limit]}]
  (let [room-id (:id room)
        k       [room-id agent-id]]
    (or (:chat-ctx (get @room-agent-ctxs k))
        (binding [ec/*execution-context* (:ctx room)]
          (let [;; The room's system-db UUID — the key the system resolvers
                ;; (room-kb-conn / room-kbs / room-msgs) want; distinct from the
                ;; in-memory keyword `room-id`. Threads to the sandbox so `dvergr.room`
                ;; + the guarded `d/connect`/`list-databases` resolve THIS room's
                ;; fork-aware databases.
                room-uuid (provisioned-uuid room)
                cctx (turn/new-working-ctx
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
                        ;; Per-agent network egress scope: an actor's optional
                        ;; `:config {:allowed-domains #{"https://…"}}` restricts the
                        ;; sandbox `http` primitive (nil/empty ⇒ open).
                       :allowed-domains (some-> (actors/lookup (sdb/get-conn) agent-id)
                                                :config :allowed-domains)
                        ;; The room store (bus→store listener) is the single durable
                        ;; writer for this conversation; the agent's own turn messages
                        ;; stay signal-only (no redundant datahike write). Token
                        ;; accounting still persists.
                       :durable?       false})
                seen (java.util.Collections/synchronizedSet (java.util.HashSet.))]
            ;; Register BEFORE the fold/seed so append-inbound! finds the entry.
            (swap! room-agent-ctxs assoc k {:chat-ctx cctx :seen seen :sub nil})
            ;; Subscribe first (catch live messages during seeding)…
            (let [sub (start-fold! room agent-id)]
              (swap! room-agent-ctxs assoc-in [k :sub] sub))
            ;; System prompt once, then seed the conversation from the store.
            ;; Signal-only: the prompt is regenerated each session, not durable.
            (when system-prompt
              (append-signal-only! cctx {:role :system :content system-prompt}))
            ;; Seed the conversation from ONE query: `d/messages` reads the room's
            ;; (for a fork, branched) store under the conversation :chat/id, so a
            ;; fork already returns inherited (pre-fork) + its own messages — the
            ;; agent sees exactly what the UI seeds. (doc/unified-fork-conversation.md)
            (doseq [m (d/messages room {:limit (or limit 100)})]
              (when (conversational? m)
                (append-inbound! room-id agent-id (:id m)
                                 (or (:role m) (if (= agent-id (:from m)) :assistant :user))
                                 (:content m)
                                 ;; author nil for the agent's OWN past messages
                                 (when (not= agent-id (:from m)) (display-name room (:from m)))
                                 (:ts m)
                                 ;; feed back only the agent's OWN prior reasoning,
                                 ;; not another participant's <think>
                                 (when (= agent-id (:from m)) (:reasoning m)))))
            (tel/log! {:level :debug :id ::created
                       :data {:room room-id :agent agent-id
                              :seeded (count (chat-ctx/get-messages cctx))}})
            cctx)))))

(defn drop-ctx!
  "Tear down the (room, agent) ctx: unsubscribe the fold and forget it.
   Call on agent leave / room delete / fork discard."
  [room-id agent-id]
  (when-let [{:keys [sub]} (get @room-agent-ctxs [room-id agent-id])]
    (when sub (try (bus/unsubscribe! sub) (catch Throwable _ nil)))
    (swap! room-agent-ctxs dissoc [room-id agent-id]))
  nil)

(defn drop-room!
  "Drop all agent ctxs for a room (room delete / fork discard)."
  [room-id]
  (doseq [[rid aid] (keys @room-agent-ctxs)
          :when (= rid room-id)]
    (drop-ctx! rid aid))
  nil)

(defn clear-all!
  "Drop every cached ctx (daemon stop — the cache is a defonce that survives a
   same-process restart, so a fresh start must not reuse ctxs whose bus folds
   point at the previous run's rooms)."
  []
  (doseq [[rid aid] (keys @room-agent-ctxs)]
    (drop-ctx! rid aid))
  nil)

(defn lookup
  "The cached chat-ctx for [room-id agent-id], or nil."
  [room-id agent-id]
  (:chat-ctx (get @room-agent-ctxs [room-id agent-id])))

;; Tear down a room's cached ctxs whenever it leaves the registry (room delete,
;; fork discard) — one hook covers all teardown paths. Idempotent across reload.
(rreg/add-unregister-hook! ::drop-room drop-room!)
