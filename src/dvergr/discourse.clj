(ns dvergr.discourse
  "Multi-agent linguistic FRP for dvergr — continuous-time participants
   exchanging messages in forkable rooms.

   Eleven primitives:

   Substrate (records + delivery):
     room, participant, scripted, echo, join, leave, post!, post-batch!

   Algebra (combinators returning Spins):
     ask, fan-out, race, quorum, pipeline

   Fork (yggdrasil substrate fork via spindel/fork-context):
     fork-room, merge-room, discard

   Patterns (decomposing to the algebra):
     iterative-refinement, debate, moderate, align-on

   Theory of mind (fork + ask + discard):
     simulate-reply, imagine-conversation

   Inference (orthogonal — Open Q #11): use spindel's inference primitives
   (`choose`, `observe`, `sample`) inside any participant or room-evolution
   spin; select an inference kernel via `kernel-infer` from
   `org.replikativ.spindel.inference.inference`. No discourse-specific
   inference primitive is needed."
  (:require [clojure.string :as str]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.spin.sync :as sync]
            [org.replikativ.spindel.spin.combinators :as comb]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [is.simm.partial-cps.sequence :refer [anext]]
            [dvergr.runtime.bus :as bus]
            [dvergr.substrate.git :as dgit]
            [dvergr.runtime.peer-bus :as peer-bus]
            [dvergr.room.store :as rstore]
            [dvergr.room.store.datahike :as store-dh]
            [dvergr.room.registry :as rreg]
            [dvergr.system.rooms :as srooms]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; Records
;; ============================================================================

(defrecord Message     [id from to content ts in-reply-to metadata])

(defrecord Participant
  ;; on-message :: (fn [participant envelope] -> Spin[ReplySpec | nil])
  ;;   envelope is a Message (from the :to inbox subscription). Periodic /
  ;;   scheduled work is delivered the same way — as a Message posted by
  ;;   `dvergr.scheduler` (the engine has no special wake/driver concept).
  ;; inbox-sub  :: dvergr.runtime.bus.Subscription on [:to id] (the default channel)
  ;; inbox-mbx  :: the merge mailbox the participant-spin awaits (all subs
  ;;               pump into it)
  ;; subs       :: atom of {topic → Subscription} for dynamic extra channels
  ;; factory    :: (fn [new-ctx] -> Participant) — for cloning into a fork
  ;; process    :: the spindel spin driving this participant (set by `join`)
           [id inbox-sub inbox-mbx subs on-message factory process])

(defrecord Room
  ;; id            : keyword — stable identity (e.g. :daemon, :boardroom)
  ;; slug          : string  — human-facing alias (= (name id) by default).
  ;;                           For forks, slug is "<parent>/fork-<short>".
  ;; title         : string  — display title.
  ;; parent-id     : keyword/nil — parent Room's :id (nesting + forks).
  ;; participants  : atom of {id → Participant}
  ;; bus           : dvergr.runtime.bus.Bus — the routing substrate
  ;; ctx           : spindel ExecutionContext (== bus's ctx)
  ;; forked-at-len : index into bus's log at fork time (nil/0 for root rooms)
  ;; store         : nil or PRoomStore for durability. When non-nil, an
  ;;                 internal spin mirrors every message posted to the
  ;;                 bus into the store.
  ;; meta          : atom of arbitrary metadata (:telegram-chat-id,
  ;;                 :type :internal | :telegram-mirror, etc.)
           [id slug title parent-id participants bus ctx forked-at-len store meta])

;; A Room's bus + participants reference back to the Room, so the default record
;; printer recurses forever and StackOverflows at the REPL (e.g. when `(d/room …)`
;; or an env map holding a Room is auto-printed). Print a compact, acyclic summary
;; — same treatment spindel gives ExecutionContext.
(defmethod print-method Room [^Room r ^java.io.Writer w]
  (.write w (str "#Room{:id " (pr-str (:id r))
                 ", :participants " (count @(:participants r))
                 (when (:parent-id r) (str ", :parent " (pr-str (:parent-id r))))
                 (when (:store r) ", :store true")
                 "}")))

(defn message
  "Construct a Message. Auto-fills id and ts."
  ([from to content] (message from to content nil nil))
  ([from to content in-reply-to] (message from to content in-reply-to nil))
  ([from to content in-reply-to metadata]
   (->Message (random-uuid) from to content (System/currentTimeMillis)
              in-reply-to metadata)))

;; ============================================================================
;; Delivery — backed by dvergr.runtime.bus
;; ============================================================================

(defn- route-and-log!
  "Post msg to the room's bus. The bus's mult fans the message out to
   every matching :to / :type subscription; its log captures history.

   `Message` records carry no :type — they default to :user/message so
   capability subscriptions on `[:type :user/message]` route them. Plain
   maps pass through unchanged so callers can tag freely."
  [room msg]
  (let [msg' (if (and (instance? Message msg) (nil? (:type msg)))
               (assoc msg :type :user/message)
               msg)]
    (bus/post! (:bus room) msg')
    msg'))

(defn post!
  "Route a Message into the room. Safe to call from any thread."
  [room msg]
  (route-and-log! room msg))

(defn room-target
  "The canonical addressing target for user input into `room`, derived from
   its participants: the single agent participant (reserved `:_…` ids dropped)
   when there is exactly one, else `nil` (broadcast — a multi-agent room, or
   none).

   This is the ONE addressing rule every frontend (TUI, web, REPL, medium
   adapters) routes through, so a room behaves identically regardless of how it
   was entered. A DM — or a fork of one — has a single agent and addresses it
   (`[:to <agent>]`); a group room broadcasts (`[:to nil]`). Deriving the target
   from the room (not from UI state) is what prevents the echo-loop class where
   broadcast input feeds an agent that is also subscribed to `[:to nil]`."
  [room]
  (let [agents (->> (some-> room :participants deref keys)
                    (remove #(str/starts-with? (name %) "_")))]
    (when (= 1 (count agents)) (first agents))))

(defn post-batch!
  "Route msgs into the room in order."
  [room msgs]
  (bus/post-many! (:bus room) msgs)
  msgs)

(defn log
  "Return the room's full message log (vector). Mirrors `bus/log`."
  [room]
  (bus/log (:bus room)))

;; ============================================================================
;; Construction
;; ============================================================================

(defn- bus-with-peer-relay
  "Build a bus that mirrors every message to the daemon-wide peer-bus
   (when one is registered in the current ctx). Falls back to a plain
   bus if not — keeps tests / library use happy without daemon
   bootstrap."
  [ctx room-id scope]
  (let [peer (binding [ec/*execution-context* ctx] (peer-bus/current))]
    (bus/create-bus
     (cond-> {:ctx ctx}
       peer (assoc :relay-to  peer
                   :relay-tag {:dvergr/origin room-id
                               :dvergr/scope  scope})))))

(declare spawn-persistence-listener!)

(defn make-room
  "Create a Room from an opts map. The unified Room constructor.

   Opts:
     :id        — keyword identity (required)
     :slug      — string slug (default: (name id))
     :title     — display title (default: slug)
     :parent-id — parent room id keyword (default: nil)
     :ctx       — spindel ExecutionContext (default: create fresh)
     :store     — optional PRoomStore for durability. When provided,
                  every Message that flows through the room's bus is
                  mirrored to the store; consumers reading via
                  `room/messages` get the persisted log instead of
                  the in-memory bus log.
     :meta      — arbitrary metadata map (telegram-chat-id, room
                  type, etc.). Stored on the Room and persisted to
                  the store on creation."
  [{:keys [id slug title parent-id ctx store meta]}]
  (assert id ":id is required")
  (let [ctx   (or ctx (sp/create-execution-context))
        slug  (or slug (name id))
        title (or title slug)
        b     (bus-with-peer-relay ctx id :room)
        room  (->Room id slug title parent-id (atom {}) b ctx 0 store (atom (or meta {})))]
    ;; Persist metadata on creation so the store has it for re-hydration.
    (when store
      (rstore/-store-room! store id (cond-> {:id id :slug slug :title title}
                                      parent-id (assoc :parent-id parent-id)
                                      (seq meta) (assoc :meta meta)))
      ;; Spawn the durability listener — every message-shaped event on
      ;; the bus gets mirrored into the store.
      (spawn-persistence-listener! room))
    ;; Auto-register so every Room is discoverable via the registry.
    (binding [ec/*execution-context* ctx]
      (rreg/register! room))
    room))

(defn room
  "Backwards-compatible Room constructor. Prefer `make-room` for new
   callers.

   With one arg, allocates a fresh ExecutionContext; with two, uses
   the provided ctx (useful for nested rooms / forks). The room has
   no store, no parent-id, and an empty meta — purely ephemeral."
  ([id] (room id (sp/create-execution-context)))
  ([id ctx] (make-room {:id id :ctx ctx})))

(defn participant
  "Construct a Participant.

   :id          — keyword identifier (unique per room)
   :on-message  — (fn [p envelope] -> Spin[ReplySpec | nil]) where envelope is
                  a Message; ReplySpec is {:to id :content str} or nil for no
                  reply. Scheduled work arrives as a Message too (posted by
                  `dvergr.scheduler`) — there is no special driver envelope.
   :factory     — (fn [new-ctx] -> Participant); enables fork-room cloning
   :ctx         — execution context (default: *execution-context*).
                  The Participant's `:inbox-sub` is created at `join` time
                  when the room's bus is in scope; until then it is nil."
  [{:keys [id on-message factory ctx]}]
  (let [_ctx (or ctx ec/*execution-context*)]
    (->Participant id nil nil (atom {}) on-message factory nil)))

;; ============================================================================
;; Built-in participant helpers
;; ============================================================================

(defn scripted
  "Reply-to-sender scripted participant. `contents` is a vector of strings;
   each incoming message gets the next content as reply, addressed back to
   whoever sent it. When exhausted, emits nothing."
  ([id contents] (scripted id contents ec/*execution-context*))
  ([id contents ctx]
   (let [remaining (atom (vec contents))]
     (participant
      {:id id :ctx ctx
       :on-message (fn [_p msg]
                     (sp/spin
                      (when-let [next-content (first @remaining)]
                        (swap! remaining subvec 1)
                        {:to (:from msg) :content next-content})))
       :factory (fn [new-ctx] (scripted id (vec @remaining) new-ctx))}))))

(defn echo
  "Participant that replies to sender with 'echo: <content>'."
  ([id] (echo id ec/*execution-context*))
  ([id ctx]
   (participant
    {:id id :ctx ctx
     :on-message (fn [_p msg]
                   (sp/spin
                    {:to (:from msg) :content (str "echo: " (:content msg))}))
     :factory (fn [new-ctx] (echo id new-ctx))})))

;; ============================================================================
;; Participant lifecycle in a room
;; ============================================================================

(defn- emit-reply!
  "Route a reply-spec from `p` into `room`. `in-reply-to` is the message id
   when the envelope was a Message, nil otherwise (tick / source events)."
  [room p reply-spec in-reply-to]
  (when reply-spec
    (route-and-log!
     room
     (message (:id p) (:to reply-spec) (:content reply-spec)
              in-reply-to (:metadata reply-spec)))))

(defn- drain-into!
  "Spawn a spin that drains `(:aseq sub)` and posts each item into `mbx`."
  [sub mbx]
  (sp/spawn!
   (sp/spin
    (loop [s (:aseq sub)]
      (when-let [r (sp/await (anext s))]
        (let [[m rest-s] r]
          (sync/post! mbx m)
          (recur rest-s)))))))

(defn- participant-spin
  "Continuous-time loop: drain inbox events → run on-message → emit reply.

   The inbox is a mailbox merging the participant's `[:to id]` +
   `[:to nil]` subscriptions plus any extra subs in `(:subs p)`. Each
   subscription has its own pump that forwards events into this single
   mailbox; the spin awaits the mailbox uniformly.

   Exceptions inside on-message are caught and logged so a single bad
   turn doesn't kill the participant — without this, an LLM error or
   tool-call exception in one message handler permanently stopped the
   agent from receiving further messages in the room."
  [p room mbx]
  (sp/spin
   (loop []
     (let [env         (sp/await mbx)
           in-reply-to (when (instance? Message env) (:id env))
           reply-spec
           (sp/await
            (sp/spin
             (try
               (sp/await ((:on-message p) p env))
               (catch Throwable t
                 (binding [*out* *err*]
                   (println "participant" (:id p)
                            "on-message error:" (.getMessage t)))
                 nil))))]
       (try (emit-reply! room p reply-spec in-reply-to)
            (catch Throwable t
              (binding [*out* *err*]
                (println "participant" (:id p)
                         "emit-reply error:" (.getMessage t))))))
     (recur))))

(defn conversation-id
  "The id of the CONVERSATION this room persists into. For a normal room that's
   its own `:id`. A fork BRANCHES a conversation rather than starting a new one,
   so a fork persists under the ROOT of its fork chain — stored in the room's
   meta as `:conversation-id`. Messages are thus one logical conversation that a
   fork's datahike branch isolates, and `merge-to-parent!` collapses natively
   (no out-of-band `append-log!`). See doc/unified-fork-conversation.md."
  [room]
  (or (some-> room :meta deref :conversation-id) (:id room)))

(defn- spawn-persistence-listener!
  "When the Room has a `:store`, mirror every message-shaped event on
   the bus into the store. Idempotent at the store layer (re-stores on
   the same `:message/id` are no-ops), so re-firing across drain cycles
   is harmless."
  [room]
  (binding [ec/*execution-context* (:ctx room)]
    (let [sub (bus/subscribe! (:bus room) [:type :user/message])]
      (sp/spawn!
       (sp/spin
        (loop [s (:aseq sub)]
          (when-let [r (sp/await (anext s))]
            (let [[msg rest-s] r]
              (try
                (when (rstore/message-shape? msg)
                  (rstore/-store-message! (:store room) (conversation-id room) msg))
                (catch Throwable t
                    ;; Durability boundary — a failure here means a message did
                    ;; NOT persist to the room store. Must be visible, never lost.
                  (tel/log! {:level :error :id :room/persist-failed
                             :data {:room (:id room) :from (:from msg)
                                    :error (.getMessage t)}})))
              (recur rest-s)))))))))

(defn on-each-message
  "Spawn a listener that calls `(f msg)` for EVERY conversational message posted
   to `room` (every `[:type :user/message]` on the bus), in order — the whole
   room stream, not a `:to`-addressed subset. Use for mirrors/relays that must
   reflect the entire room (a channel egress that shows what a rich UI shows).
   Errors in `f` are logged, never swallowed, and don't stop the listener."
  [room f]
  (binding [ec/*execution-context* (:ctx room)]
    (let [sub (bus/subscribe! (:bus room) [:type :user/message])]
      (sp/spawn!
       (sp/spin
        (loop [s (:aseq sub)]
          (when-let [r (sp/await (anext s))]
            (let [[msg rest-s] r]
              (try (when (map? msg) (f msg))
                   (catch Throwable t
                     (tel/log! {:level :error :id :room/listener-failed
                                :data {:room (:id room) :error (.getMessage t)}})))
              (recur rest-s)))))))))

(defn messages
  "Return a Room's message history.

   When the Room is persistent (has a :store), reads from the store —
   surviving daemon restarts. For ephemeral rooms, returns the bus's
   in-memory log filtered to message-shaped events.

   Opts:
     :limit  — cap result size (default 100)
     :since  — only messages after this java.util.Date"
  ([room] (messages room {}))
  ([room {:keys [limit since] :as opts}]
   (if-let [store (:store room)]
     (rstore/-list-messages store (conversation-id room) (merge {:limit (or limit 100)} opts))
     (let [filtered (->> (bus/log (:bus room))
                         (filter rstore/message-shape?)
                         (filter (if since
                                   #(when-let [t (:ts %)]
                                      (> (.getTime ^java.util.Date (java.util.Date. ^long t))
                                         (.getTime ^java.util.Date since)))
                                   identity)))]
       (vec (take-last (or limit 100) filtered))))))

(defn metadata-update!
  "Update the Room's :meta atom AND mirror the change to the store
   if persistent. The :meta atom holds open-ended room metadata
   (telegram-chat-id, type, etc.); use this whenever it changes so
   restarts hydrate the same values."
  [room update-fn & args]
  (let [new-meta (apply swap! (:meta room) update-fn args)]
    (when-let [store (:store room)]
      (rstore/-store-room! store (:id room)
                           {:slug (:slug room)
                            :title (:title room)
                            :parent-id (:parent-id room)
                            :meta new-meta}))
    new-meta))

(defn join
  "Register participant in room, subscribe its inbox + drivers on the bus,
   and start its spin. Returns the participant with :inbox-sub, :inbox-mbx
   and :process set.

   Each participant subscribes to TWO topics:
     [:to (:id p)] — directly-addressed messages
     [:to nil]     — broadcast messages (no specific recipient)
   Because every Message has exactly one `:to` value, no message
   reaches a participant twice. The chat-room semantic — \"every
   participant sees every broadcast post; targeted posts only the
   addressed one\" — falls out naturally."
  [room p]
  (binding [ec/*execution-context* (:ctx room)]
    (let [inbox-sub     (bus/subscribe! (:bus room) [:to (:id p)])
          broadcast-sub (bus/subscribe! (:bus room) [:to nil])
          ;; Merge all subscriptions into one mailbox so the spin awaits
          ;; a single source. Each sub gets a small pump.
          merge-mbx (sync/create-mailbox (:ctx room))
          _ (drain-into! inbox-sub     merge-mbx)
          _ (drain-into! broadcast-sub merge-mbx)
          p' (-> p
                 (assoc :inbox-sub inbox-sub
                        :inbox-mbx merge-mbx
                        ;; The participant carries the room it is JOINED to, so a
                        ;; handler operates on its actual room — not one baked
                        ;; into a closure. Critical for fork clones: the same
                        ;; on-message, joined to a fork, must rehydrate/account
                        ;; against the FORK, not the parent it was built for.
                        :room room)
                 (update :subs (fn [s] (swap! s assoc [:to nil] broadcast-sub) s)))
          proc (participant-spin p' room merge-mbx)]
      (sp/spawn! proc)
      (let [p'' (assoc p' :process proc)]
        (swap! (:participants room) assoc (:id p) p'')
        p''))))

;; ============================================================================
;; Dynamic subscriptions — let participants listen to extra tagged channels
;; ============================================================================

(defn subscribe!
  "Add an extra bus subscription on `topic` that pumps into `p`'s inbox
   mailbox. Use inside on-message bodies (or after join) when a participant
   needs to receive messages NOT addressed by `:to`, e.g. an auditor
   watching `[:type :escalation/budget]` regardless of recipient.

   Returns the Subscription. Idempotent on (already-subscribed topic).

   Buffer defaults to dvergr.runtime.bus's policy table; pass an explicit buffer
   via the 4-arg form."
  ([room p topic]
   (subscribe! room p topic nil))
  ([room p topic buffer]
   (or (get @(:subs p) topic)
       (let [sub (binding [ec/*execution-context* (:ctx room)]
                   (if buffer
                     (bus/subscribe! (:bus room) topic buffer)
                     (bus/subscribe! (:bus room) topic)))]
         (binding [ec/*execution-context* (:ctx room)]
           (drain-into! sub (:inbox-mbx p)))
         (swap! (:subs p) assoc topic sub)
         sub))))

(defn unsubscribe!
  "Remove a previously added subscription on `topic` for `p`. The drain
   pump exits the next time the bus closes the subscription's aseq."
  [_room p topic]
  (when-let [sub (get @(:subs p) topic)]
    (bus/unsubscribe! sub)
    (swap! (:subs p) dissoc topic))
  nil)

(defn leave
  "Remove participant from room's routing table and unsubscribe its inbox.
   The participant's spin continues running (no per-spin cancel in spindel
   today); messages addressed to it after leaving are not delivered."
  [room participant-id]
  (when-let [p (get @(:participants room) participant-id)]
    (when-let [sub (:inbox-sub p)]
      (bus/unsubscribe! sub)))
  (swap! (:participants room) dissoc participant-id)
  nil)

(defn close-room!
  "Tear down a room: deregister every participant and stop the room's
   execution context. The context's drain thread (a virtual thread on
   JDK 21+, see spindel.engine.context) exits and any in-flight drain
   completes before this returns.

   Use this when a room's lifecycle is bounded — tests, the daemon's
   shutdown path, an MCP session ending — so the room doesn't depend on
   GC running before the JVM reclaims its resources. Closing a fork is
   a no-op for the context (forks share the parent's drain); only the
   participants atom is cleared.

   No-op on already-closed rooms."
  [room]
  (when room
    (reset! (:participants room) {})
    (let [ctx (:ctx room)]
      ;; stop-context! only does work on root contexts; forks share the
      ;; parent's drain thread and treat this as a no-op.
      (when ctx
        (when-let [stop! (try (requiring-resolve
                               'org.replikativ.spindel.engine.context/stop-context!)
                              (catch Exception _ nil))]
          (stop! ctx)))))
  nil)

;; ============================================================================
;; Drivers — §5.5 (multi-channel event sources alongside the inbox)
;;
;; The inbox channel is intrinsic to every participant. Additional drivers
;; are attached via these helpers; the participant-spin races them. Note
;; these set fields directly and do NOT wrap :factory — by design, a fork
;; of the participant (e.g. via fork-room for ToM probes) starts WITHOUT
;; drivers. External event subscriptions and tick cadences belong to the
;; original participant's lifecycle; the fork is hypothetical.
;; ============================================================================

;; NOTE: with-cadence / with-sources (the engine's self-tick / source drivers)
;; were removed — periodic + external work now arrives as ordinary Messages
;; posted by `dvergr.scheduler` (and channels), so the engine has no special
;; wake/driver concept and no `:tick`/`:source` envelopes.

;; ============================================================================
;; Combinators — the asymmetric algebra
;;
;; ask uses a stub asker {:id :inbox} (not a full Participant) — no spin is
;; spawned, so there is no per-ask leak. The asker is registered just long
;; enough for the reply to be routed, then dissoc'd.
;; ============================================================================

(defn ask
  "Send a message to target-id and await their reply. Returns Spin[Message].
   The asker is a transient bus subscription on [:to asker-id] — no spin
   spawned beyond the await.

   `msg-spec` is `{:content str & opts}` where `:metadata` (optional) is
   attached to the dispatched Message — used by agent handlers that pull
   per-session state (chat-ctx, source provenance) from the envelope."
  [room target-id msg-spec]
  (sp/spin
   (let [asker-id (keyword (str "ask-" (random-uuid)))
         asker-sub (binding [ec/*execution-context* (:ctx room)]
                     (bus/subscribe! (:bus room) [:to asker-id]))]
      ;; Register a stub so the participants map can be inspected if needed.
     (swap! (:participants room) assoc asker-id
            {:id asker-id :inbox-sub asker-sub})
      ;; try/finally so the transient subscription + stub are reclaimed on BOTH
      ;; a normal reply AND cancellation. spindel's cancel-spin! unwinds a spin
      ;; suspended at this `await` through the finally — and reaches here even
      ;; when the ask is wrapped in comb/timeout/race — so a cancelled ask never
      ;; leaks its bus subscription.
     (try
       (post! room (message asker-id target-id (:content msg-spec) nil
                            (:metadata msg-spec)))
        ;; Take exactly one message off the asker's subscription.
       (let [[reply _rest] (sp/await (anext (:aseq asker-sub)))]
         reply)
       (finally
         (bus/unsubscribe! asker-sub)
         (swap! (:participants room) dissoc asker-id))))))

(defn fan-out
  "Parallel ask to all targets; await all replies. Returns Spin[Vector[Message]]."
  [room targets msg-spec]
  (sp/spin
   (sp/await (apply comb/parallel (mapv #(ask room % msg-spec) targets)))))

(defn race
  "Send to all targets; return the first reply. Losers cancelled."
  [room targets msg-spec]
  (sp/spin
   (sp/await (apply comb/race (mapv #(ask room % msg-spec) targets)))))

(defn quorum
  "Send to all targets; return the first n replies."
  [room targets msg-spec n]
  (sp/spin
   (let [d         (sync/create-deferred (:ctx room))
         collected (atom [])]
     (doseq [target targets]
       (sp/spawn!
        (sp/spin
         (let [reply   (sp/await (ask room target msg-spec))
               current (swap! collected conj reply)]
           (when (= n (count current))
             (sync/deliver! d current))))))
     (sp/await d))))

(defn pipeline
  "Chain ask through targets: each reply becomes the next's content."
  [room targets msg-spec]
  (sp/spin
   (loop [remaining       targets
          current-content (:content msg-spec)
          last-reply      nil]
     (if (empty? remaining)
       last-reply
       (let [reply (sp/await (ask room (first remaining)
                                  {:content current-content}))]
         (recur (rest remaining) (:content reply) reply))))))

;; ============================================================================
;; Forking — the substrate primitive enabling speculation
;; ============================================================================

(defn fork-room
  "Create a sibling room with cloned participants.

   Two isolation modes:

   `:none` (default) — share the parent's execution context. Participants
   are re-created via their `:factory` and joined into the fork's own
   `:participants` map, but they share the parent's spindel ec — so
   mailboxes, deferreds, and signal subscriptions remain coherent
   across the fork boundary. This is what makes `simulate-reply` and
   `imagine-conversation` compose with the rest of the algebra: the
   parent's spin can `await` the fork's `ask` because both speak the
   same ctx.

   `:ctx` — fork the spindel execution context via `ctx/fork-context`,
   which automatically branches all yggdrasil systems registered as
   `[:external-refs]` (datahike, git worktrees, btrfs subvolumes,
   ZFS datasets, …) via spindel's PForkable extension. The fork
   becomes substrate-isolated: an agent's writes to its chat-ctx
   datahike, KB writes, file edits, etc. happen on branched copies
   and are only visible inside the fork until `merge-room` atomically
   merges them back via `spindel.yggdrasil/merge-to-parent!` (or
   `discard` deletes the branches via `discard-from-parent!`).

   Use `:ctx` when the fork must hold real side effects in isolation
   (proposals, speculative coding-agent work). Use `:none` (default)
   for message-only ToM probes where nothing in the fork commits.

   `:ctx` requires that subsequent operations on the fork (e.g.
   `(ask fork :agent …)` from outside the fork's body) bind
   `*execution-context*` to the fork's ctx — see `with-fork-ctx`."
  ([room] (fork-room room {}))
  ([room {:keys [isolation] :or {isolation :none}}]
   (let [short-uuid (subs (str (random-uuid)) 0 8)
         new-slug   (str (:slug room) "/fork-" short-uuid)
         ;; Use the canonical slug→id encoding so id ↔ slug stay in
         ;; lock-step across the entire system (registry, store,
         ;; peer-bus events).
         new-id     (rstore/slug->room-id new-slug)
         parent-log (log room)
         child-ctx  (case isolation
                      :none (:ctx room)
                      :ctx  (ctx/fork-context (:ctx room)))
         ;; Mark a `:ctx` fork TRANSIENT so create-room-db! defers the GLOBAL
         ;; system-db grant (reconciled on merge / dropped on discard) — a fork's
         ;; agent-created DB must not resurrect on restart (P2).
         _          (when (= :ctx isolation)
                      (binding [ec/*execution-context* child-ctx]
                        (ec/swap-state! [:dvergr/transient-fork?] (constantly true))))
         child-bus  (bus-with-peer-relay child-ctx new-id :fork)
         ;; Seed the fork's bus log with parent history so log-based
         ;; consumers see a continuous record. Forks have their OWN bus
         ;; so live messages do not leak between parent and fork.
         _          (bus/seed-log! child-bus parent-log)
         ;; `:isolation :ctx` forks BRANCH the parent's conversation. The fork
         ;; gets its OWN store wrapping the fork ctx's BRANCHED chat-db conn
         ;; (NOT the parent's fixed-conn store), and persists under the parent's
         ;; CONVERSATION id (root of the fork chain) — so a fork's messages are
         ;; the same logical conversation on a datahike branch, and
         ;; merge-to-parent! collapses them natively (no append-log!). It writes
         ;; no separate :chat entity. (doc/unified-fork-conversation.md)
         conv-id    (conversation-id room)
         ;; RF5: the fork's store wraps the PARENT room's OWN messages conn under
         ;; the fork ctx (branched), so fork messages ride the per-room store's
         ;; branch and merge-to-parent! collapses them natively. (RF5 S4.3: no
         ;; chat-db fallback — every room is provisioned with its own :msgs system.)
         fork-store (when (= :ctx isolation)
                      (some-> (binding [ec/*execution-context* child-ctx]
                                (srooms/msgs-conn-for-slug (:slug room)))
                              store-dh/make))
         new-room   (->Room new-id new-slug
                            (str (:title room) " · fork " short-uuid)
                            (:id room)
                            (atom {})
                            child-bus
                            child-ctx
                            (count parent-log)
                            fork-store
                            (atom (assoc @(:meta room)
                                         :forked-from (:id room)
                                         :conversation-id conv-id)))]
     ;; Persist fork-local messages onto the branch under the root conversation.
     (when (and (= :ctx isolation) fork-store)
       (spawn-persistence-listener! new-room))
     ;; Register the fork in the PARENT ctx — where the daemon UI reads the
     ;; registry. A `:ctx` fork's own child-ctx is invisible to the parent
     ;; (CoW), so registering there would hide the fork from the tree (and it
     ;; would render empty). The Room still carries its child-ctx in `:ctx`
     ;; for merge/diff/git. For `:none`, child-ctx == parent ctx (no change).
     (binding [ec/*execution-context* (:ctx room)]
       (rreg/register! new-room))
     (doseq [[_id p] @(:participants room)]
       (when-let [fac (:factory p)]
         (binding [ec/*execution-context* child-ctx]
           (join new-room (fac child-ctx)))))
     ;; Control-plane: announce the fork on the peer-bus so dashboards,
     ;; audit logs, and oversight agents see it without subscribing to
     ;; the fork's bus directly.
     (binding [ec/*execution-context* child-ctx]
       (peer-bus/post! {:type            :dvergr/fork-created
                        :dvergr/origin   new-id
                        :dvergr/parent   (:id room)
                        :isolation       isolation
                        :worktree-path   (when (= :ctx isolation)
                                           (dgit/current-worktree-path))}))
     new-room)))

(defmacro with-fork-ctx
  "Execute `body` with the fork's execution context bound. Required for
   operations like `(ask fork :agent …)` initiated from OUTSIDE the
   fork's participant spins when the fork was created with
   `:isolation :ctx` — without this binding, the asker's mailbox would
   be created in the wrong ctx and the await would silently miss the
   reply. For `:isolation :none` (default), no-op-ish (the binding is
   the same ctx anyway)."
  [fork & body]
  `(binding [ec/*execution-context* (:ctx ~fork)]
     ~@body))

(defmacro with-room
  "Evaluate `body` with the room's execution context bound. Every room
   operation — `join`, `post!`, `subscribe!`, `ask`, `fork-room`, … — runs
   inside the room's spindel execution context (its `:ctx`); bind it once per
   top-level block instead of sprinkling `binding` on each call. `room` may be a
   Room (its `:ctx` is used) or a raw execution context.

       (with-room room
         (join room agent)
         (post! room (message :you :agent \"hi\")))

   (`dvergr.clients.client` binds the room's ctx for you — code on that surface
   doesn't need this; `with-fork-ctx` is the fork-specific sibling.)"
  [room & body]
  `(let [r# ~room]
     (binding [ec/*execution-context* (or (:ctx r#) r#)]
       ~@body)))

(defn- ctx-was-forked?
  "True if `fork`'s ctx is a child of some parent ctx — i.e. it was
   created via `ctx/fork-context`. Determines whether merge/discard
   should also merge/discard yggdrasil branches."
  [fork]
  (some? (:parent-ctx (:ctx fork))))

(defn- fork-home-ctx
  "The ctx where a fork's registry entry + control-plane events live — its
   PARENT ctx, matching `fork-room`'s registration (a `:ctx` fork's own
   child-ctx is invisible to the parent under CoW, so registry ops must run in
   the parent). For `:none` forks (no distinct parent ctx) this is the fork's
   own ctx."
  [fork]
  (or (:parent-ctx (:ctx fork)) (:ctx fork)))

(defn- drain-fork-turns!
  "Cancel any in-flight agent turn in `fork` and wait (bounded, ~5s) for it to
   stop, so merge/discard doesn't yank the fork's worktree + branched conns out
   from under a running turn (security audit P4). Best-effort."
  [fork]
  (let [fid      (:id fork)
        cancel!  (requiring-resolve 'dvergr.agent.turn/cancel-room-turn!)
        running? (requiring-resolve 'dvergr.agent.turn/room-turn-running?)]
    (try (cancel! fid) (catch Throwable _))
    (loop [n 0]
      (when (and (< n 50) (try (running? fid) (catch Throwable _ false)))
        (Thread/sleep 100)
        (recur (inc n))))))

(defn discard
  "Discard a fork: deregister its participants, drop it from the
   registry, and if the fork's ctx was forked (`:isolation :ctx`),
   delete all branched yggdrasil systems via
   `spindel.yggdrasil/discard-from-parent!`. Participant spins on a
   shared ctx (`:isolation :none`) continue running indefinitely on
   their mailboxes (harmless; cleaned up when the shared context
   eventually stops).

   Emits `:dvergr/fork-discarded` on the peer-bus. Idempotent — a second
   discard of the same fork is a no-op (the branched systems are deleted only
   once)."
  [fork]
  ;; Idempotence: once unregistered, the fork is a zombie — re-discarding would
  ;; double-delete the yggdrasil branch (which errors). Guard on registry.
  (when (binding [ec/*execution-context* (fork-home-ctx fork)] (rreg/lookup (:id fork)))
    (reset! (:participants fork) {})
    (when (ctx-was-forked? fork)
      (drain-fork-turns! fork)                 ; P4: stop in-flight turns first
      ;; P2: read the fork's deferred data-DB grants (fork-local ctx-state) and
      ;; delete the stores it created — they were never granted, so on discard they
      ;; must not linger. :on-discard fires inside discard-from-parent!.
      (let [pending (binding [ec/*execution-context* (:ctx fork)]
                      (ec/get-state [:dvergr/pending-grants]))]
        (ygg/discard-from-parent! (:ctx fork)
                                  {:on-discard (fn [_] (srooms/drop-fork-grants! pending))})))
    (binding [ec/*execution-context* (fork-home-ctx fork)]
      (rreg/unregister! (:id fork))
      (peer-bus/post! {:type :dvergr/fork-discarded
                       :dvergr/origin (:id fork)})))
  fork)

(defn merge-room
  "Merge fork into parent.

   1. Append the fork's new log entries (those added after the fork
      point) to the parent's log.
   2. If the fork's ctx was forked (`:isolation :ctx`), merge all
      branched yggdrasil systems back into the parent via
      `spindel.yggdrasil/merge-to-parent!` — datahike branches collapse,
      git branches fast-forward or three-way merge, etc.
   3. Deregister the fork's participants.

   Concurrent sibling forks UNION (identity-keyed datahike merge); a genuine
   field clash (same entity+attr changed differently on both sides) is a 3-way
   CONFLICT that `merge-to-parent!` refuses by default. Pass `{:merge-opts
   {:force true}}` (the agent reconciler does, after resolving) to force past it.
   (doc/unified-fork-conversation.md, dvergr.rooms.forks/reconcile-merge!.)"
  ([parent fork] (merge-room parent fork {}))
  ([parent fork {:keys [merge-opts]}]
   (if (and (ctx-was-forked? fork) (:store fork))
    ;; `:ctx` fork with a branched conversation store — a BRANCH of the parent's
    ;; conversation. The merge is native:
    ;; merge-to-parent! collapses the fork's datahike (+ git) branch into the
    ;; parent, bringing the fork's messages into the parent's conversation under
    ;; the shared :chat/id (no in-memory carry). (doc/unified-fork-conversation.md)
     (try
       (drain-fork-turns! fork)                ; P4: stop in-flight turns before merge
      ;; P2: the fork's deferred data-DB grants become durable room systems only on
      ;; accept — replay them into the global system-db in the :on-merge callback.
       (let [pending (binding [ec/*execution-context* (:ctx fork)]
                       (ec/get-state [:dvergr/pending-grants]))]
         (ygg/merge-to-parent! (:ctx fork)
                               (merge (or merge-opts {})
                                      {:on-merge (fn [_] (srooms/commit-fork-grants! pending))})))
       (catch Throwable e
         (tel/log! {:level :error :id :dvergr/merge-failed
                    :data {:fork (:id fork) :parent (:id parent) :error (str e)}}
                   "merge-room: yggdrasil merge-to-parent! failed — parent state may be partial")
         (binding [ec/*execution-context* (fork-home-ctx fork)]
           (peer-bus/post! {:type :dvergr/merge-failed :dvergr/origin (:id fork)
                            :dvergr/parent (:id parent) :error (str e)}))
         (throw e)))
    ;; Branchless fork (`:isolation :none`/ephemeral) — no datahike branch to
    ;; merge, so absorb the fork's post-fork bus entries into the parent's log
    ;; (merge-as-history; no re-firing of live handlers, separate buses).
     (let [forked-at   (or (:forked-at-len fork) 0)
           fork-log    (log fork)
           new-entries (when (> (count fork-log) forked-at) (subvec fork-log forked-at))]
       (when (seq new-entries)
         (bus/append-log! (:bus parent) new-entries))))
  ;; Surface the merged conversation: re-seed the parent's shared message signal
  ;; (no-op if the parent has no signal) so every frontend re-renders — the merge
  ;; is a datahike collapse / log append, not a bus post.
   (try ((requiring-resolve 'dvergr.rooms.messages/refresh!) parent)
        (catch Throwable _ nil))
   (reset! (:participants fork) {})
   (binding [ec/*execution-context* (fork-home-ctx fork)]
     (rreg/unregister! (:id fork))
     (peer-bus/post! {:type            :dvergr/fork-merged
                      :dvergr/origin   (:id fork)
                      :dvergr/parent   (:id parent)}))
   parent))

;; ============================================================================
;; PR-style merge review
;; ============================================================================

(defn propose-merge!
  "Agent-side: signal that the fork-room's work is ready for the
   manager's review. Posts two things:

   1. A chat message on the fork's bus with `:dvergr/proposal`
      metadata carrying a diff summary (commits + changed files +
      `git diff --stat`). Participants subscribed to the fork can
      ask follow-up questions in the usual way.

   2. A `:dvergr/merge-proposed` event on the peer-bus so dashboards
      and oversight agents see the proposal without joining the
      fork's bus.

   Options:
     :from     participant id posting the proposal (default: :worker)
     :note     human-readable rationale to attach to the message
               (default: empty)

   Returns the proposal payload."
  [fork & {:keys [from note] :or {from :worker note ""}}]
  (let [diff      (when (ctx-was-forked? fork)
                    (dgit/diff-since-fork (:ctx fork)))
        proposal  (cond-> {:fork-id (:id fork)
                           :note    note}
                    diff (assoc :diff diff))
        msg       {:type            :proposal/merge
                   :from            from
                   :body            (str "Ready for review."
                                         (when (seq note) (str " " note)))
                   :dvergr/proposal proposal}]
    (bus/post! (:bus fork) msg)
    (binding [ec/*execution-context* (:ctx fork)]
      (peer-bus/post! {:type            :dvergr/merge-proposed
                       :dvergr/origin   (:id fork)
                       :proposal        proposal}))
    proposal))

(defn pending-proposals
  "Scan a room's log for `:dvergr/proposal`-tagged messages that
   haven't been followed by a merge or discard. Useful for the TUI
   pending-review badge and for agents that want to enumerate open
   review threads.

   Returns a vector of proposal payloads in log order."
  [room]
  (->> (log room)
       (keep :dvergr/proposal)
       vec))

;; ============================================================================
;; Hire — fork + spawn + send + merge|discard (the §5.8 primitive)
;; ============================================================================

(defn hire
  "Spawn `worker` (a Participant) in a forked room, send it `goal`, wait
   for its reply or timeout. Then:
     - if `accept-fn` returns truthy, merge the fork into parent
     - otherwise, discard the fork

   Returns Spin yielding one of:
     {:status :merged    :reply Message :log [Message]}
     {:status :discarded :reply Message :log [Message]}
     {:status :timeout                  :log [Message]}

   Options:
     :goal        — string content of the initial message (required)
     :accept-fn   — (fn [reply] -> bool) — decides merge vs discard
                    (default: always merge if reply received)
     :timeout-ms  — wall-clock timeout (default 60000)
     :from        — :from id for the goal message (default :hire-caller)

   The worker should be a fresh Participant not joined elsewhere; its
   on-message will receive the goal as the first message it sees. Pair
   with `dvergr.discourse.llm/llm-agent` for real LLM workers, or with
   `scripted`/`echo` for tests."
  [room worker {:keys [goal accept-fn timeout-ms from]
                :or   {accept-fn (constantly true)
                       timeout-ms 60000
                       from :hire-caller}}]
  (sp/spin
   (let [fork  (fork-room room)
         _     (join fork worker)
         reply (sp/await
                (comb/timeout
                 (ask fork (:id worker) {:content goal})
                 timeout-ms
                 ::timeout))
         log   (log fork)]
     (cond
       (= reply ::timeout)
       (do (discard fork)
           {:status :timeout :log log})

       (accept-fn reply)
       (do (merge-room room fork)
           {:status :merged :reply reply :log log})

       :else
       (do (discard fork)
           {:status :discarded :reply reply :log log})))))

;; ============================================================================
;; Patterns — decomposing to the algebra
;; ============================================================================

(defn iterative-refinement
  "Producer drafts; critic reviews; loop until `accept?` fires on a critique
   or max-iter rounds elapse.

   Returns Spin yielding
     {:result :accepted     :iterations n :draft m :review m} or
     {:result ::max-iter    :iterations n :last m}"
  [room producer-id critic-id initial-msg
   {:keys [accept? max-iter] :or {max-iter 5}}]
  (sp/spin
   (loop [i 0
          current initial-msg]
     (if (>= i max-iter)
       {:result ::max-iter :iterations i :last current}
       (let [draft  (sp/await (ask room producer-id current))
             review (sp/await (ask room critic-id
                                   {:content (:content draft)}))]
         (if (accept? review)
           {:result :accepted :iterations i :draft draft :review review}
           (recur (inc i)
                  {:content (str "Refine. Last: " (:content draft)
                                 " | Feedback: " (:content review))})))))))

(defn debate
  "Round-robin between targets for N rounds. Each round, all targets reply
   to the concatenated previous-round content. Returns Spin yielding a
   vector of round-vectors of replies."
  [room targets
   {:keys [rounds initial-content] :or {rounds 2 initial-content ""}}]
  (sp/spin
   (loop [round   0
          content initial-content
          history []]
     (if (>= round rounds)
       history
       (let [replies (sp/await (fan-out room targets {:content content}))
             next-content (str/join " | " (map :content replies))]
         (recur (inc round) next-content (conj history replies)))))))

(defn moderate
  "Moderator-driven turn-taking. `pick-fn :: history → next-speaker-id | nil`.
   Returns nil to stop. Each picked speaker replies to the last message
   in history. Returns Spin yielding history."
  [room initial-msg
   {:keys [pick-fn max-rounds] :or {max-rounds 10}}]
  (sp/spin
   (loop [round 0
          history [initial-msg]]
     (if (>= round max-rounds)
       history
       (if-let [next-id (pick-fn history)]
         (let [reply (sp/await (ask room next-id
                                    {:content (:content (peek history))}))]
           (recur (inc round) (conj history reply)))
         history)))))

(defn align-on
  "Habermas-Machine pattern (§8.3). Mediator drafts a statement; all
   participants critique; mediator re-drafts incorporating critiques. Loop
   until every critique satisfies `accept?` (consensus) or max-rounds.

   accept? :: Message → bool      (a critique counts as accepting)
   Returns Spin yielding
     {:result :converged   :draft str :rounds n :final-critiques [Message]} or
     {:result ::max-rounds :draft str :rounds n}"
  [room mediator-id participants topic
   {:keys [accept? max-rounds] :or {max-rounds 5}}]
  (sp/spin
   (loop [round 0
          draft topic]
     (if (>= round max-rounds)
       {:result ::max-rounds :draft draft :rounds round}
       (let [critiques   (sp/await (fan-out room participants
                                            {:content draft}))
             all-accept? (every? accept? critiques)]
         (if all-accept?
           {:result :converged :draft draft :rounds round
            :final-critiques critiques}
           (let [feedback (str/join "\n"
                                    (map #(str "- " (name (:from %))
                                               ": " (:content %))
                                         critiques))
                 refined  (sp/await
                           (ask room mediator-id
                                {:content (str "Draft: " draft
                                               "\nCritiques:\n" feedback)}))]
             (recur (inc round) (:content refined)))))))))

;; ============================================================================
;; Theory of Mind — fork + ask + discard
;; ============================================================================

(defn simulate-reply
  "Fork the room, ask `other-id` a hypothetical message, capture their reply,
   discard the fork. Parent room untouched. The operational ToM primitive
   (see doc/programming-model.md).

   Cheap: O(1) fork + one LLM call. Composes with every other combinator —
   e.g. inside an iterative-refinement step a participant can simulate the
   critic's response before committing."
  [room other-id hypothetical-msg]
  (sp/spin
   (let [fork  (fork-room room)
         reply (sp/await (ask fork other-id hypothetical-msg))]
     (discard fork)
     reply)))

(defn imagine-conversation
  "Fork, run a workflow function (Room → Spin) in the fork, capture the
   imagined log, discard. Returns Spin yielding
     {:outcome any :imagined-log [Message]}."
  [room workflow-fn]
  (sp/spin
   (let [fork    (fork-room room)
         outcome (sp/await (workflow-fn fork))
         log     (log fork)]
     (discard fork)
     {:outcome outcome :imagined-log log})))
