(ns dvergr.discourse
  "Multi-agent linguistic FRP for dvergr — continuous-time participants
   exchanging messages in forkable rooms.

   The substrate of `dvergr.discourse-model.md`. Eleven primitives:

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
            [dvergr.bus :as bus]
            [dvergr.git :as dgit]
            [dvergr.peer-bus :as peer-bus]))

;; ============================================================================
;; Records
;; ============================================================================

(defrecord Message     [id from to content ts in-reply-to metadata])

(defrecord Participant
  ;; on-message :: (fn [participant envelope] -> Spin[ReplySpec | nil])
  ;;   envelope is either a Message (from the :to inbox subscription) or a
  ;;   synthetic event from an additional subscription:
  ;;     {:type :tick}                          — periodic self-tick
  ;;     {:type :source :name kw :msg evt-data} — external source event
  ;; inbox-sub  :: dvergr.bus.Subscription on [:to id] (the default channel)
  ;; inbox-mbx  :: the merge mailbox the participant-spin awaits (all subs
  ;;               pump into it)
  ;; subs       :: atom of {topic → Subscription} for dynamic extra channels
  ;; factory    :: (fn [new-ctx] -> Participant) — for cloning into a fork
  ;; process    :: the spindel spin driving this participant (set by `join`)
  ;; tick-ms    :: nil or interval in ms (`with-cadence`); fires {:type :tick}
  ;; sources    :: nil or [{:name kw :source PAsyncSeq}] (`with-sources`)
  [id inbox-sub inbox-mbx subs on-message factory process tick-ms sources])

(defrecord Room
  ;; participants : atom of {id → Participant}
  ;; bus          : dvergr.bus.Bus — the routing substrate
  ;; ctx          : spindel ExecutionContext (== bus's ctx)
  ;; forked-at-len: index into bus's log at fork time (nil/0 for root rooms)
  [id participants bus ctx forked-at-len])

(defn message
  "Construct a Message. Auto-fills id and ts."
  ([from to content] (message from to content nil nil))
  ([from to content in-reply-to] (message from to content in-reply-to nil))
  ([from to content in-reply-to metadata]
   (->Message (random-uuid) from to content (System/currentTimeMillis)
              in-reply-to metadata)))

;; ============================================================================
;; Delivery — backed by dvergr.bus
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

(defn room
  "Create a Room. With one arg, allocates a fresh ExecutionContext;
   with two, uses the provided ctx (useful for nested rooms / forks)."
  ([id] (room id (sp/create-execution-context)))
  ([id ctx]
   (let [b (bus-with-peer-relay ctx id :room)]
     (->Room id (atom {}) b ctx 0))))

(defn participant
  "Construct a Participant.

   :id          — keyword identifier (unique per room)
   :on-message  — (fn [p envelope] -> Spin[ReplySpec | nil]) where envelope is
                  a Message (inbox) or {:type :tick} / {:type :source :name K
                  :msg E} for driver-supplied events; ReplySpec is
                  {:to id :content str} or nil for no reply
   :factory     — (fn [new-ctx] -> Participant); enables fork-room cloning
   :ctx         — execution context (default: *execution-context*).
                  The Participant's `:inbox-sub` is created at `join` time
                  when the room's bus is in scope; until then it is nil.

   Drivers (tick / sources) are added with `with-cadence` / `with-sources`."
  [{:keys [id on-message factory ctx]}]
  (let [_ctx (or ctx ec/*execution-context*)]
    (->Participant id nil nil (atom {}) on-message factory nil nil nil)))

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
               in-reply-to nil))))

(defn- start-driver-pumps!
  "Spawn pumps that post tick / source events onto the room's bus as
   typed messages (`{:type :tick}` and `{:type :source :name K :msg E}`).
   Subscriptions are wired by `join` so the participant receives them
   alongside their inbox."
  [room p]
  (when-let [tick-ms (:tick-ms p)]
    (sp/spawn!
      (sp/spin
        (loop []
          (sp/await (comb/sleep tick-ms))
          (bus/post! (:bus room)
                     {:to (:id p) :type :tick})
          (recur)))))
  (doseq [{:keys [name source]} (:sources p)]
    (sp/spawn!
      (sp/spin
        (loop []
          (when-let [[v _] (sp/await (anext source))]
            (bus/post! (:bus room)
                       {:to (:id p) :type :source :name name :msg v})
            (recur))))))
  nil)

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

   The inbox is a mailbox merging the participant's `[:to id]` subscription
   plus any extra subs in `(:subs p)`. Each subscription has its own pump
   that forwards events into this single mailbox; the spin awaits the
   mailbox uniformly.

   This avoids `comb/race` over mailboxes (which drops queued items on
   loser cancel) and provides a single linearization point for ordering."
  [p room mbx]
  (sp/spin
    (loop []
      (let [env         (sp/await mbx)
            in-reply-to (when (instance? Message env) (:id env))
            reply-spec  (sp/await ((:on-message p) p env))]
        (emit-reply! room p reply-spec in-reply-to))
      (recur))))

(defn join
  "Register participant in room, subscribe its inbox + drivers on the bus,
   and start its spin. Returns the participant with :inbox-sub, :inbox-mbx
   and :process set."
  [room p]
  (binding [ec/*execution-context* (:ctx room)]
    (let [inbox-sub (bus/subscribe! (:bus room) [:to (:id p)])
          ;; Merge all subscriptions into one mailbox so the spin awaits
          ;; a single source. Each sub gets a small pump.
          merge-mbx (sync/create-mailbox (:ctx room))
          _ (drain-into! inbox-sub merge-mbx)
          ;; Tick + sources will land via [:to id] (their pumps post
          ;; with :to (:id p)), so no extra subs needed for them.
          _ (start-driver-pumps! room p)
          p' (-> p
                 (assoc :inbox-sub inbox-sub
                        :inbox-mbx merge-mbx))
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

   Buffer defaults to dvergr.bus's policy table; pass an explicit buffer
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

(defn with-cadence
  "Attach a self-tick driver: the participant receives `{:type :tick}` every
   `interval-ms`. Ticks fired while on-message is processing are dropped
   (no queue). Returns a Participant with :tick-ms set."
  [p interval-ms]
  (assoc p :tick-ms interval-ms))

(defn with-sources
  "Attach external event sources: each `{:name kw :source PAsyncSeq}` is
   raced alongside the inbox. When a source fires, on-message receives
   `{:type :source :name kw :msg event}`. Source events that arrive while
   on-message is processing queue in the source's underlying buffer
   (typically a mailbox). Returns a Participant with :sources set."
  [p sources]
  (assoc p :sources (vec sources)))

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
      (post! room (message asker-id target-id (:content msg-spec) nil
                           (:metadata msg-spec)))
      ;; Take exactly one message off the asker's subscription.
      (let [[reply _rest] (sp/await (anext (:aseq asker-sub)))]
        (bus/unsubscribe! asker-sub)
        (swap! (:participants room) dissoc asker-id)
        reply))))

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
   (let [new-id     (keyword (str (name (:id room))
                                  "-fork-" (subs (str (random-uuid)) 0 8)))
         parent-log (log room)
         child-ctx  (case isolation
                      :none (:ctx room)
                      :ctx  (ctx/fork-context (:ctx room)))
         child-bus  (bus-with-peer-relay child-ctx new-id :fork)
         ;; Seed the fork's bus log with parent history so log-based
         ;; consumers see a continuous record. Forks have their OWN bus
         ;; so live messages do not leak between parent and fork.
         _          (reset! (:log child-bus) parent-log)
         new-room   (->Room new-id (atom {}) child-bus child-ctx
                            (count parent-log))]
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

(defn- ctx-was-forked?
  "True if `fork`'s ctx is a child of some parent ctx — i.e. it was
   created via `ctx/fork-context`. Determines whether merge/discard
   should also merge/discard yggdrasil branches."
  [fork]
  (some? (:parent-ctx (:ctx fork))))

(defn discard
  "Discard a fork: deregister its participants and, if the fork's ctx
   was forked (`:isolation :ctx`), delete all branched yggdrasil systems
   via `spindel.yggdrasil/discard-from-parent!`. Participant spins on a
   shared ctx (`:isolation :none`) continue running indefinitely on
   their mailboxes (harmless; cleaned up when the shared context
   eventually stops).

   Emits `:dvergr/fork-discarded` on the peer-bus."
  [fork]
  (reset! (:participants fork) {})
  (when (ctx-was-forked? fork)
    (ygg/discard-from-parent! (:ctx fork)))
  (binding [ec/*execution-context* (:ctx fork)]
    (peer-bus/post! {:type :dvergr/fork-discarded
                     :dvergr/origin (:id fork)}))
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

   Assumes parent's log was not modified between fork and merge (the
   standard ToM/what-if pattern). For ctx-forked merges, the
   yggdrasil-level merge is atomic per-system via the workspace's
   coordinated commit; cross-system atomicity is best-effort (per
   workspace coordination)."
  [parent fork]
  (let [fork-log  (log fork)
        forked-at (or (:forked-at-len fork) 0)
        new-entries (when (> (count fork-log) forked-at)
                      (subvec fork-log forked-at))]
    (when (seq new-entries)
      ;; Append fork-only messages directly to the parent's log. This is
      ;; merge-as-history: subscribers in the parent didn't see the fork's
      ;; exchange live (separate bus) and re-fanning would re-fire their
      ;; handlers. The log captures the merged history; live observation
      ;; is a separate concern for callers that need it.
      (swap! (:log (:bus parent)) into new-entries)))
  (when (ctx-was-forked? fork)
    (ygg/merge-to-parent! (:ctx fork)))
  (reset! (:participants fork) {})
  (binding [ec/*execution-context* (:ctx fork)]
    (peer-bus/post! {:type            :dvergr/fork-merged
                     :dvergr/origin   (:id fork)
                     :dvergr/parent   (:id parent)}))
  parent)

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
   (§6.5 of discourse-model.md).

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
