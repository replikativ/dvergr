(ns dvergr.discourse
  "Multi-agent linguistic FRP for dvergr — continuous-time participants
   exchanging messages in forkable rooms.

   Eleven primitives:

   Substrate (records + delivery):
     room, participant, scripted, echo, join, leave, post!, post-batch!

   Algebra (combinators returning Spins):
     ask, fan-out, race, quorum, pipeline

   Fork (canonical Spindel authority, optionally partitioned into a tree):
     fork-room, merge-room, discard, transfer-fork!, partition-transferred-fork!

   Patterns (decomposing to the algebra):
     iterative-refinement, debate, moderate, align-on

   Theory of mind (fork + ask + discard):
     simulate-reply, imagine-conversation

   Inference (orthogonal — Open Q #11): use spindel's inference primitives
   (`choose`, `observe`, `sample`) inside any participant or room-evolution
   spin; select an inference kernel via `kernel-infer` from
   `org.replikativ.spindel.inference.inference`. No discourse-specific
   inference primitive is needed."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.spin.core :as spin-core]
            [org.replikativ.spindel.spin.sync :as sync]
            [org.replikativ.spindel.spin.combinators :as comb]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [is.simm.partial-cps.sequence :refer [anext]]
            [dvergr.runtime.bus :as bus]
            [dvergr.substrate.geschichte :as geschichte]
            [dvergr.runtime.peer-bus :as peer-bus]
            [dvergr.agent.run :as agent-run]
            [dvergr.sandbox.work :as sandbox-work]
            [dvergr.room.store :as rstore]
            [dvergr.room.store.datahike :as store-dh]
            [dvergr.room.registry :as rreg]
            [dvergr.system.rooms :as srooms]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; Records
;; ============================================================================

(defrecord Message
  ;; `in-reply-to` is the immediate conversational parent. `thread-root-id` is
  ;; the stable root of the topic projection inside the Room. Keeping both is
  ;; what lets clients render either a reply tree or a flat thread without
  ;; recursively loading parents.
           [id from to content ts in-reply-to thread-root-id metadata])

(defrecord Participant
  ;; on-message :: (fn [participant envelope] -> Spin[ReplySpec | nil])
  ;;   envelope is a Message (from the :to inbox subscription). Periodic /
  ;;   scheduled work is delivered the same way — as a Message posted by
  ;;   `dvergr.scheduler` (the engine has no special wake/driver concept).
  ;; inbox-sub  :: dvergr.runtime.bus.Subscription on [:to id] (the default channel)
  ;; inbox-mbx  :: the merge mailbox the participant-spin awaits (all subs
  ;;               pump into it)
  ;; deferred-inbox :: atom of PersistentQueue — messages consumed by an active
  ;;                   handler but deferred to later executions. It has priority
  ;;                   over inbox-mbx so hand-back cannot reorder older work
  ;;                   behind a concurrently arriving message.
  ;; subs       :: atom of {topic → Subscription} for dynamic extra channels
  ;; pumps      :: atom of {lifecycle-key → Spin}; live subscription drains
  ;;               owned by this participant and cancelled with its process
  ;; active     :: atom of nil | Spin | ::participant-closed; the handler Spin
  ;;               currently awaited by the participant process
  ;; factory    :: (fn [new-ctx] -> Participant) — for cloning into a fork
  ;; process    :: the spindel spin driving this participant (set by `join`)
           [id inbox-sub inbox-mbx deferred-inbox subs pumps active on-message factory process])

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
  ;; store         : nil or PRoomStore for durability. When non-nil, the
  ;;                 bus persists every message-shaped event inside
  ;;                 post! — durability BEFORE visibility (the bus's
  ;;                 :durable-append! hook, wired in make-room).
  ;; meta          : atom of arbitrary metadata (:telegram-chat-id,
  ;;                 :type :internal | :telegram-mirror, etc.)
  ;; fork-handle   : nil or atom containing the process-local canonical
  ;;                 spindel.yggdrasil/ForkHandle for an isolated fork. Durable
  ;;                 identity is exposed through fork-descriptor; the live
  ;;                 settlement capability is deliberately never persisted.
           [id slug title parent-id participants bus ctx forked-at-len store meta fork-handle
            incarnation])

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

;; Spin implements IDeref, so the default record printer would dereference the
;; participant process and pumps outside their execution context. Besides being
;; noisy, that makes merely inspecting `(join room agent)` fail at the REPL.
(defmethod print-method Participant [^Participant p ^java.io.Writer w]
  (.write w (str "#Participant{:id " (pr-str (:id p))
                 ", :subscriptions " (+ (if (:inbox-sub p) 1 0)
                                        (count (some-> p :subs deref)))
                 (when (:process p) ", :process true")
                 "}")))

(defn message
  "Construct a Message. Auto-fills id, timestamp, and thread root.

   A top-level message is its own thread root. The legacy arities accepting only
   an `in-reply-to` UUID conservatively treat that parent as the root; callers
   constructing nested replies should use `reply` or the explicit six-argument
   arity so the ancestor root is preserved."
  ([from to content] (message from to content nil nil nil))
  ([from to content in-reply-to]
   (message from to content in-reply-to in-reply-to nil))
  ([from to content in-reply-to metadata]
   (message from to content in-reply-to in-reply-to metadata))
  ([from to content in-reply-to thread-root-id metadata]
   (let [id (random-uuid)]
     (->Message id from to content (System/currentTimeMillis)
                in-reply-to (or thread-root-id id) metadata))))

(defn thread-root-id
  "Return the stable topical root of `msg`, tolerating legacy envelopes.

   New Message values always carry `:thread-root-id`. The fallbacks keep old
   persisted/imported envelopes readable while their stores are migrated."
  [msg]
  (or (:thread-root-id msg) (:in-reply-to msg) (:id msg)))

(defn same-thread?
  "True when two message envelopes belong to the same Room thread."
  [a b]
  (let [aroot (thread-root-id a)
        broot (thread-root-id b)]
    (and (some? aroot) (= aroot broot))))

(defn reply
  "Construct a reply to `parent`, preserving its root and immediate parent id."
  ([from to content parent] (reply from to content parent nil))
  ([from to content parent metadata]
   (message from to content (:id parent) (thread-root-id parent) metadata)))

;; ============================================================================
;; Delivery — backed by dvergr.runtime.bus
;; ============================================================================

(declare conversation-id)

(defn- route-and-log!
  "Post msg to the room's bus. The bus's mult fans the message out to
   every matching :to / :type subscription; its log captures history.

   `Message` records carry no :type — they default to :user/message so
   capability subscriptions on `[:type :user/message]` route them. Plain
   maps pass through unchanged so callers can tag freely."
  [room msg]
  (let [;; Thread membership is authoritative at the Room boundary. Clients need
        ;; only name the immediate parent; if it is available in the live log or
        ;; durable store, derive its ancestor root and override any stale/client-
        ;; supplied value. Out-of-order import may retain an explicit root until
        ;; the parent arrives.
        msg (if (instance? Message msg)
              (if-let [parent-id (:in-reply-to msg)]
                (let [live-parent (some #(when (= parent-id (:id %)) %)
                                        (rseq (vec (bus/log (:bus room)))))
                      root (or (some-> live-parent thread-root-id)
                               (when-let [store (:store room)]
                                 (rstore/-message-thread-root
                                  store (conversation-id room) parent-id)))]
                  (if root
                    (assoc msg :thread-root-id root)
                    msg))
                (assoc msg :thread-root-id (:id msg)))
              msg)
        msg' (if (and (instance? Message msg) (nil? (:type msg)))
               (assoc msg :type :user/message)
               msg)]
    (bus/post! (:bus room) msg')
    msg'))

(declare ensure-room-work-admitted!)

(defn post!
  "Route a Message into the room. Safe to call from any thread."
  [room msg]
  (locking (:meta room)
    (ensure-room-work-admitted! room :post msg)
    (route-and-log! room msg)))

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
  (locking (:meta room)
    (ensure-room-work-admitted! room :post-batch)
    (bus/post-many! (:bus room) msgs)
    msgs))

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
  [ctx room-id scope & [{:keys [durable-append!]}]]
  (let [peer (binding [ec/*execution-context* ctx] (peer-bus/current))]
    (bus/create-bus
     (cond-> {:ctx ctx}
       durable-append! (assoc :durable-append! durable-append!)
       peer (assoc :relay-to  peer
                   :relay-tag {:dvergr/origin room-id
                               :dvergr/scope  scope})))))

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
        ;; Durability-first (durable-cursor bus): the bus persists each
        ;; message-shaped event into the store INSIDE post!, before the
        ;; message becomes visible anywhere. A store failure fails the
        ;; post loudly — the inverse of the old persistence LISTENER,
        ;; which observed the ephemeral pipeline downstream and could
        ;; silently lose durability (or, if the pipeline lost the
        ;; message, lose it entirely). The conversation id is resolved
        ;; from :meta here (a fork persists under its chain root — same
        ;; rule as `conversation-id`, computed pre-construction).
        conv-id (or (:conversation-id meta) id)
        durable-append! (when store
                          (fn [msg]
                            (when (rstore/message-shape? msg)
                              (rstore/-store-message! store conv-id msg))))
        b     (bus-with-peer-relay ctx id :room
                                   (when durable-append!
                                     {:durable-append! durable-append!}))
        room  (->Room id slug title parent-id (atom {}) b ctx 0 store
                      (atom (or meta {})) nil (random-uuid))]
    (agent-run/open-room-admission! id ctx)
    ;; Persist metadata on creation so the store has it for re-hydration.
    (when store
      (rstore/-store-room! store id (cond-> {:id id :slug slug :title title}
                                      parent-id (assoc :parent-id parent-id)
                                      (seq meta) (assoc :meta meta))))
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
    (->Participant id nil nil (atom clojure.lang.PersistentQueue/EMPTY)
                   (atom {}) (atom {}) (atom nil) on-message factory nil)))

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

(def ^:private reply-emitted-key ::reply-emitted)
(def ^:private reply-emit-failed-key ::reply-emit-failed)

(defn after-reply-emission
  "Attach process-local lifecycle callbacks to a ReplySpec. `on-emitted` runs
   only after the reply is durably posted to the Room; `on-failed` receives a
   posting error. The callbacks are control data and never enter the Message."
  [reply-spec on-emitted on-failed]
  (cond-> reply-spec
    on-emitted (assoc reply-emitted-key on-emitted)
    on-failed (assoc reply-emit-failed-key on-failed)))

(defn- emit-reply!
  "Route a reply-spec from `p` into `room`, preserving the triggering Message's
   immediate-parent and thread-root identities. Non-message events start a new
   thread if they produce conversational output."
  [room p reply-spec parent-message]
  (when reply-spec
    (let [on-emitted (get reply-spec reply-emitted-key)
          on-failed  (get reply-spec reply-emit-failed-key)
          emitted
          (try
            (route-and-log!
             room
             (if parent-message
               (reply (:id p) (:to reply-spec) (:content reply-spec)
                      parent-message (:metadata reply-spec))
               (message (:id p) (:to reply-spec) (:content reply-spec)
                        nil (:metadata reply-spec))))
            (catch Throwable t
              (when on-failed
                (try (on-failed t)
                     (catch Throwable callback-error
                       (tel/log! {:level :error :id ::reply-failure-callback-error
                                  :data {:participant (:id p)
                                         :error (.getMessage callback-error)}}
                                 "reply failure callback failed"))))
              (throw t)))]
      (when on-emitted
        (try (on-emitted emitted)
             (catch Throwable t
               ;; The reply is already durable. Do not misreport this as an
               ;; emission failure; the lifecycle owner retained its retryable
               ;; active projection when its terminal write failed.
               (tel/log! {:level :error :id ::reply-emitted-callback-error
                          :data {:participant (:id p) :error (.getMessage t)}}
                         "reply emitted but its lifecycle callback failed"))))
      emitted)))

(defn- drain-into!
  "Spawn a spin that drains `(:aseq sub)` and posts each item into `mbx`.
   Returns the Spin so its participant owner can cancel it deterministically."
  [sub mbx]
  (let [pump (sp/spin
              (loop [s (:aseq sub)]
                (when-let [r (sp/await (anext s))]
                  (let [[m rest-s] r]
                    (sync/post! mbx m)
                    (recur rest-s)))))]
    (sp/spawn! pump
               {:on-error
                (fn [error]
                  (when-not (= spin-core/spin-cancelled (:type (ex-data error)))
                    (tel/log! {:level :error :id ::subscription-pump-error
                               :data {:topic (:topic sub)
                                      :error (.getMessage ^Throwable error)}}
                              "participant subscription pump failed")))})
    pump))

(defn- cleanup-owned-spin!
  "Cancel `spin` when live and detach its continuations and dependencies.

   Spindel cancellation marks a Spin terminal, while graph cleanup releases the
   engine-side await registrations immediately instead of waiting for GC."
  [room spin]
  (when spin
    (binding [ec/*execution-context* (:ctx room)]
      (let [spin-id (spin-core/spin-id spin)]
        (when-not (ec/spin-current-result spin-id)
          (spin-core/cancel-spin! spin))
        (ec/graph-clear-deps! spin-id))))
  nil)

(defn defer-inbox!
  "Append `msg` to a participant's deferred FIFO.

   Active handlers use this when they must consume the live mailbox to arbitrate
   control messages but discover ordinary work that belongs to a later
   execution. The participant loop always drains this FIFO before awaiting the
   live mailbox, preserving global arrival order across the hand-back boundary."
  [participant msg]
  (swap! (:deferred-inbox participant) conj msg)
  nil)

(defn take-deferred-inbox!
  "Atomically remove and return the oldest deferred message, or nil."
  [participant]
  (let [queue-atom (:deferred-inbox participant)]
    (loop []
      (let [queue @queue-atom]
        (when (seq queue)
          (let [msg (peek queue)]
            (if (compare-and-set! queue-atom queue (pop queue))
              msg
              (recur))))))))

(defn- participant-spin
  "Continuous-time loop: drain inbox events → run on-message → emit reply.

   The inbox is a mailbox merging the participant's `[:to id]` +
   `[:to nil]` subscriptions plus any extra subs in `(:subs p)`. Each
   subscription has its own pump that forwards events into this single
   mailbox; the spin awaits the mailbox uniformly.

   DELIVER-ONCE: a participant's subscriptions form a UNION filter. A message
   that matches SEVERAL of them — e.g. a broadcast (`[:to nil]`) that also
   carries a `:type` the participant subscribed to, so it matches both
   `[:to nil]` and `[:type …]` — is pumped into the mailbox once per matching
   subscription. This loop dedups by message `:id` (stamped by `bus/post!`) over
   a bounded recent window, so `on-message` runs EXACTLY ONCE per message. The
   duplicates arrive in the same publish wave, so a modest window suffices; on
   eviction the worst case is a rare double, never a dropped distinct message
   (ids are random-uuids and don't collide).

   Exceptions inside on-message are caught and logged so a single bad
   turn doesn't kill the participant — without this, an LLM error or
   tool-call exception in one message handler permanently stopped the
   agent from receiving further messages in the room."
  [p room mbx]
  (sp/spin
   (loop [seen  #{}
          order clojure.lang.PersistentQueue/EMPTY]
     (let [env (if-let [deferred (take-deferred-inbox! p)]
                 deferred
                 (sp/await mbx))
           id  (:id env)]
       (if (and id (contains? seen id))
         ;; already handled via another matching subscription — skip the duplicate
         (recur seen order)
         (let [parent-message (when (instance? Message env) env)
               ;; Await the on-message spin DIRECTLY, with the error isolation
               ;; inline in THIS body. The previous shape awaited an anonymous
               ;; wrapper spin that awaited the handler — the documented
               ;; nested-spin-await anti-pattern ("hangs with non-trivial
               ;; closures"), and one more GC-reapable suspended awaiter in the
               ;; chain (see spindel fix/gc-reap-suspended-awaiter). partial-cps
               ;; supports try/catch around await in-body, so the wrapper bought
               ;; nothing but risk.
               handler ((:on-message p) p env)
               ;; `leave` swaps this atom to ::participant-closed before
               ;; teardown. CAS prevents a concurrently entering handler from
               ;; escaping the participant's lifecycle boundary.
               handler-owned? (compare-and-set! (:active p) nil handler)
               reply-spec
               (when handler-owned?
                 (try
                   (sp/await handler)
                   (catch Throwable t
                     (tel/log! {:level :error :id ::on-message-error
                                :data {:participant (:id p) :error (.getMessage t)}}
                               "participant on-message error — turn dropped, participant continues")
                     nil)
                   (finally
                     (compare-and-set! (:active p) handler nil))))]
           (try (emit-reply! room p reply-spec parent-message)
                (catch Throwable t
                  (tel/log! {:level :error :id ::emit-reply-error
                             :data {:participant (:id p) :error (.getMessage t)}}
                            "participant emit-reply error")))
           (if id
             (let [order' (conj order id)
                   seen'  (conj seen id)]
               (if (> (count order') 512)
                 (recur (disj seen' (peek order')) (pop order'))
                 (recur seen' order')))
             (recur seen order))))))))

(defn conversation-id
  "The id of the CONVERSATION this room persists into. For a normal room that's
   its own `:id`. A fork BRANCHES a conversation rather than starting a new one,
   so a fork persists under the ROOT of its fork chain — stored in the room's
   meta as `:conversation-id`. Messages are thus one logical conversation that a
   fork's datahike branch isolates, and `merge-fork!` collapses natively
   (no out-of-band `append-log!`). See doc/unified-fork-conversation.md."
  [room]
  (or (some-> room :meta deref :conversation-id) (:id room)))

(def ^:private fork-transfer-state-key :dvergr/fork-transfer-state)

(defn- install-room-listener!
  [room f]
  (binding [ec/*execution-context* (:ctx room)]
    (let [sub (bus/subscribe! (:bus room) [:type :user/message])
          lifecycle (atom {:status :open})
          listener (sp/spin
                    (loop [s (:aseq sub)]
                      (when-let [r (sp/await (anext s))]
                        (let [[msg rest-s] r
                              done (promise)
                              claimed?
                              (loop []
                                (let [state @lifecycle]
                                  (if (= :open (:status state))
                                    (if (compare-and-set! lifecycle state
                                                          {:status :active :done done})
                                      true
                                      (recur))
                                    false)))]
                          (when claimed?
                            (try
                              (when (map? msg) (f msg))
                              (catch Throwable t
                                (tel/log! {:level :error :id :room/listener-failed
                                           :data {:room (:id room) :error (.getMessage t)}}))
                              (finally
                                (swap! lifecycle
                                       (fn [state]
                                         {:status (if (= :closing (:status state))
                                                    :closed
                                                    :open)}))
                                (deliver done true))))
                          (recur rest-s)))))
          _ (sp/spawn! listener
                       {:on-error
                        (fn [error]
                          (when-not (= spin-core/spin-cancelled (:type (ex-data error)))
                            (tel/log! {:level :error :id ::room-listener-error
                                       :data {:room (:id room)
                                              :error (ex-message error)}}
                                      "Room listener process failed")))})
          owned (or (:dvergr/owned-listeners @(:meta room)) (atom #{}))
          entry {:subscription sub :spin listener :lifecycle lifecycle :callback f}]
      (swap! owned conj entry)
      (swap! (:meta room) assoc :dvergr/owned-listeners owned)
      listener)))

(defn on-each-message
  "Spawn a listener that calls `(f msg)` for EVERY conversational message posted
   to `room` (every `[:type :user/message]` on the bus), in order — the whole
   room stream, not a `:to`-addressed subset. Use for mirrors/relays that must
   reflect the entire room (a channel egress that shows what a rich UI shows).
   Errors in `f` are logged, never swallowed, and don't stop the listener."
  [room f]
  (locking (:meta room)
    (ensure-room-work-admitted! room :on-each-message)
    (install-room-listener! room f)))

(declare fork-handle)

(defn- begin-room-quiescence!
  [room operation]
  (let [token (random-uuid)]
    (agent-run/fence-room-admission!
     room
     (fn [admitted active]
       (locking (:meta room)
         (if-let [state (get @(:meta room) fork-transfer-state-key)]
           (throw (ex-info "Room is already fenced for lifecycle work"
                           {:type ::room-lifecycle-in-progress
                            :fork/id (:id room)
                            :operation operation
                            :fork/transfer-state state}))
           (swap! (:meta room) assoc fork-transfer-state-key
                  {:state :tearing-down
                   :token token
                   :operation operation
                   :admitted-run-ids admitted
                   :admitted-trigger-ids (into #{} (keep :run/trigger) active)})))))))

(defn- drain-room-listeners!
  "Stop Room-owned listeners and wait for active callbacks to physically exit.
   Returns their callbacks so a recoverable transfer failure can reinstall them."
  [room operation]
  (begin-room-quiescence! room operation)
  (if-let [owned (:dvergr/owned-listeners @(:meta room))]
    (let [entries (vec @owned)]
      (doseq [{:keys [subscription spin lifecycle]} entries]
        (bus/unsubscribe! subscription)
        (let [state (swap! lifecycle
                           (fn [state]
                             (case (:status state)
                               :open {:status :closed}
                               :active (assoc state :status :closing)
                               state)))]
          (cleanup-owned-spin! room spin)
          (when-let [done (:done state)]
            (when (= ::timeout (deref done 5000 ::timeout))
              (swap! (:meta room) update fork-transfer-state-key
                     assoc
                     :state :recovery-required
                     :operation operation
                     :error "listener callback drain timed out")
              (throw (ex-info "Timed out waiting for a Room listener callback"
                              {:type ::listener-drain-timeout
                               :room-id (:id room)}))))))
      (reset! owned #{})
      (mapv :callback entries))
    []))

(defn- seal-room-quiescence!
  "Retire the causal-post handoff after every admitted Run has acknowledged
   cancellation. No Room write may cross this boundary into settlement."
  [room operation]
  (locking (:meta room)
    (let [state (get @(:meta room) fork-transfer-state-key)]
      (when (= :tearing-down (:state state))
        (swap! (:meta room) update fork-transfer-state-key
               assoc :state :settling :operation operation))))
  nil)

(defn- restore-room-listeners!
  [room callbacks]
  (locking (:meta room)
    (doseq [f callbacks]
      (install-room-listener! room f))))

(defn- recover-room-quiescence!
  [room callbacks work-fence error]
  (let [handle (fork-handle room)
        token (get-in @(:meta room) [fork-transfer-state-key :token])]
    (if (or (nil? handle) (ygg/open-fork? handle))
      (let [restore-error (try
                            (restore-room-listeners! room callbacks)
                            nil
                            (catch Throwable listener-error listener-error))]
        (if restore-error
          (do
            (swap! (:meta room) update fork-transfer-state-key
                   assoc :state :recovery-required :error (ex-message restore-error))
            (throw (ex-info "Room listener restoration failed; recovery is required"
                            {:type ::fork-transfer-recovery-required
                             :fork/id (:id room)
                             :operation :teardown
                             :error (ex-message error)
                             :restore-error (ex-message restore-error)}
                            restore-error)))
          (let [recovered?
                (agent-run/recover-room-admission!
                 room
                 (fn []
                   (locking (:meta room)
                     (when (and (= token (get-in @(:meta room)
                                                 [fork-transfer-state-key :token]))
                                (or (nil? work-fence)
                                    (sandbox-work/recover-room-work! room work-fence)))
                       (swap! (:meta room) dissoc fork-transfer-state-key)
                       true))))]
            (if recovered?
              (throw error)
              (throw (ex-info "Room lifecycle changed during recovery"
                              {:type ::fork-transfer-recovery-required
                               :fork/id (:id room)
                               :operation :teardown
                               :error (ex-message error)}
                              error))))))
      (do
        (swap! (:meta room) update fork-transfer-state-key
               assoc :state :recovery-required :error (ex-message error))
        (throw error)))))

(defn- mark-room-recovery-if-owned!
  "Mark a failed lifecycle operation without overwriting a newer contender's
   fence. A nil or stale token owns no lifecycle state."
  [room token operation error]
  (when token
    (locking (:meta room)
      (when (= token (get-in @(:meta room)
                             [fork-transfer-state-key :token]))
        (swap! (:meta room) update fork-transfer-state-key
               assoc
               :state :recovery-required
               :operation operation
               :error (ex-message error)))))
  nil)

(defn messages
  "Return a Room's message history.

   When the Room is persistent (has a :store), reads from the store —
   surviving daemon restarts. For ephemeral rooms, returns the bus's
   in-memory log filtered to message-shaped events.

   Opts:
     :limit  — cap result size (default 100)
     :since  — only messages after this java.util.Date
     :thread-root-id — restrict to one topical projection inside the Room"
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
                                   identity))
                         (filter (if-let [root (:thread-root-id opts)]
                                   #(= root (thread-root-id %))
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

(defn- ensure-room-work-admitted!
  [room operation & [payload]]
  (when-let [state (get @(:meta room) fork-transfer-state-key)]
    (let [run-id (some-> payload :metadata :run-id)
          admitted-trigger? (and (= :post operation)
                                 (= :tearing-down (:state state))
                                 (or (contains? (:admitted-trigger-ids state) (:id payload))
                                     (contains? (:admitted-run-ids state) run-id)))]
      (when-not admitted-trigger?
        (throw (ex-info "Room is fenced for fork authority transfer"
                        {:type ::fork-transfer-in-progress
                         :fork/id (:id room)
                         :operation operation
                         :fork/transfer-state state}))))))

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
  (locking (:meta room)
    (ensure-room-work-admitted! room :join)
    (binding [ec/*execution-context* (:ctx room)]
      (let [inbox-sub     (bus/subscribe! (:bus room) [:to (:id p)])
            broadcast-sub (bus/subscribe! (:bus room) [:to nil])
          ;; A Participant may be explicitly left and then joined again. Re-open
          ;; its active-handler slot before the new process can receive work.
            _ (reset! (:active p) nil)
          ;; Merge all subscriptions into one mailbox so the spin awaits
          ;; a single source. Each sub gets a small pump.
            merge-mbx (sync/create-mailbox (:ctx room))
            inbox-pump (drain-into! inbox-sub merge-mbx)
            broadcast-pump (drain-into! broadcast-sub merge-mbx)
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
        (swap! (:pumps p') assoc ::direct inbox-pump [:to nil] broadcast-pump)
        (sp/spawn! proc
                   {:on-error
                    (fn [error]
                      (when-not (= spin-core/spin-cancelled (:type (ex-data error)))
                        (tel/log! {:level :error :id ::participant-process-error
                                   :data {:participant (:id p')
                                          :error (.getMessage ^Throwable error)}}
                                  "participant process failed")))})
        (let [p'' (assoc p' :process proc)]
          (swap! (:participants room) assoc (:id p) p'')
          p'')))))

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
   (locking (:meta room)
     (ensure-room-work-admitted! room :subscribe)
     (or (get @(:subs p) topic)
         (let [sub (binding [ec/*execution-context* (:ctx room)]
                     (if buffer
                       (bus/subscribe! (:bus room) topic buffer)
                       (bus/subscribe! (:bus room) topic)))
               pump (binding [ec/*execution-context* (:ctx room)]
                      (drain-into! sub (:inbox-mbx p)))]
           (swap! (:subs p) assoc topic sub)
           (swap! (:pumps p) assoc topic pump)
           sub)))))

(defn unsubscribe!
  "Remove a previously added subscription on `topic` for `p`. The drain
   pump exits the next time the bus closes the subscription's aseq."
  [room p topic]
  (when-let [sub (get @(:subs p) topic)]
    (bus/unsubscribe! sub)
    (when-let [pump (get @(:pumps p) topic)]
      (cleanup-owned-spin! room pump)
      (swap! (:pumps p) dissoc topic))
    (swap! (:subs p) dissoc topic))
  nil)

(defn leave
  "Remove participant from room's routing table and unsubscribe ALL of
   its bus feeds: the direct inbox, the broadcast sub, and every dynamic
   `:subs` entry (e.g. the llm-agent's [:type :user/message]). Leaving
   only the inbox — the previous behavior — left the broadcast/type
   subscriptions alive feeding the still-running participant spin: a
   leave/re-join cycle then had TWO live agents answering every message
   (observed live: duplicate replies 5s apart; one zombie accumulated
   PER cycle).

   Teardown is deterministic: the participant process, active handler, and
   every subscription pump are cancelled and detached from the engine; their
   bus subscriptions are closed. Calling leave repeatedly is safe."
  [room participant-id]
  (when-let [p (get @(:participants room) participant-id)]
    ;; Close the handler slot first. A concurrent participant slice can install
    ;; a handler only via CAS from nil, so no new handler escapes this boundary.
    (let [active-spin (when-let [active (:active p)]
                        (let [[before _after]
                              (swap-vals! active (constantly ::participant-closed))]
                          (when-not (= ::participant-closed before) before)))]
      (cleanup-owned-spin! room (:process p))
      (cleanup-owned-spin! room active-spin))
    ;; Closing subscriptions releases their sequence waits; engine cleanup below
    ;; removes any pump continuation that has not observed closure yet.
    (when-let [sub (:inbox-sub p)]
      (bus/unsubscribe! sub))
    (when-let [subs (:subs p)]
      (doseq [[topic sub] @subs]
        (bus/unsubscribe! sub)
        (swap! subs dissoc topic)))
    (when-let [pumps (:pumps p)]
      (doseq [pump (vals @pumps)]
        (cleanup-owned-spin! room pump))
      (reset! pumps {}))
    (when-let [deferred-inbox (:deferred-inbox p)]
      (reset! deferred-inbox clojure.lang.PersistentQueue/EMPTY)))
  (swap! (:participants room) dissoc participant-id)
  nil)

(defn- leave-all!
  "Cancel and remove every participant currently registered in `room`."
  [room]
  (doseq [participant-id (keys @(:participants room))]
    (leave room participant-id))
  nil)

(defn- fork-room?
  "True for rooms produced by `fork-room`; nested non-fork rooms do not count."
  [room]
  (boolean (some-> room :meta deref :forked-from)))

(defn- room-home-ctx
  "Context holding `room`'s registry entry. Isolated forks register in parent."
  [room]
  (or (some-> room :ctx :parent-ctx) (:ctx room)))

(defn- drain-room-runs!
  "Request cancellation of every live Run owned by `room` and wait up to five
   seconds for their executors to acknowledge a terminal state. The lifecycle
   watch is installed before cancellation so the final transition cannot race
   the wait. This covers LLM turns and directly interpreted agent programs."
  [room]
  (let [room-id (:id room)
        admitted (agent-run/close-room-admission! room)
        stopped (promise)
        watch-key (Object.)]
    (try
      (agent-run/watch-runs!
       watch-key
       (fn [_]
         (let [live-ids (into #{} (map :run/id) (agent-run/active-runs room-id))]
           (when (empty? (set/intersection admitted live-ids))
             (deliver stopped true)))))
      (doseq [run-id admitted]
        (agent-run/cancel-room-run! room-id run-id))
      (when (seq admitted)
        (deref stopped 5000 ::timeout))
      (let [live-ids (into #{} (map :run/id) (agent-run/active-runs room-id))
            remaining (set/intersection admitted live-ids)]
        (when (seq remaining)
          (throw (ex-info "Room teardown timed out waiting for live Runs"
                          {:type ::run-drain-timeout
                           :room-id room-id
                           :run-ids remaining}))))
      (finally
        (agent-run/unwatch-runs! watch-key)))))

(defn close-room!
  "Tear down a room: leave every participant, unregister the Room, and close
   its execution context. Any in-flight drain completes before this returns.

   Use this when a room's lifecycle is bounded — tests, the daemon's
   shutdown path, an MCP session ending — so the room doesn't depend on
   GC running before the JVM reclaims its resources. Closing a fork leaves its
   participants and unregisters it, but never closes the root context: `:none`
   forks share the exact parent context and `:ctx` forks share its executor.

   No-op on already-closed rooms."
  [room]
  (when (and room (not= :closed (get-in @(:meta room)
                                        [fork-transfer-state-key :state])))
    (let [fence-token* (volatile! nil)]
      (try
        (drain-room-listeners! room :close)
        (vreset! fence-token*
                 (get-in @(:meta room) [fork-transfer-state-key :token]))
        (sandbox-work/close-room-work! room)
        (drain-room-runs! room)
        (seal-room-quiescence! room :close)
        (leave-all! room)
        (binding [ec/*execution-context* (room-home-ctx room)]
          (rreg/unregister! (:id room)))
        ;; A branchless fork carries the exact root ctx, so Spindel cannot infer from
        ;; the context alone that closing it here would kill the parent. The durable
        ;; room marker is the ownership boundary.
        (when-not (fork-room? room)
          (when-let [room-ctx (:ctx room)]
            (ctx/close-context! room-ctx)))
        (swap! (:meta room) assoc fork-transfer-state-key
               {:state :closed :operation :close})
        (catch Throwable error
          (mark-room-recovery-if-owned! room @fence-token* :close error)
          (throw error)))))
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
         asker-sub (locking (:meta room)
                     (ensure-room-work-admitted! room :ask)
                     (let [sub (binding [ec/*execution-context* (:ctx room)]
                                 (bus/subscribe! (:bus room) [:to asker-id]))]
                        ;; Register the stub under the same lifecycle lock, so
                        ;; transfer either sees it or fences the ask first.
                       (swap! (:participants room) assoc asker-id
                              {:id asker-id :inbox-sub sub})
                       sub))]
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

   `:ctx` — create the canonical `spindel.yggdrasil/ForkHandle`, which forks
   the execution context and automatically branches all registered yggdrasil
   systems (datahike, git worktrees, btrfs subvolumes, ZFS datasets, …). The fork
   becomes substrate-isolated: an agent's writes to its chat-ctx
   datahike, KB writes, file edits, etc. happen on branched copies
   and are only visible inside the fork until `merge-room` merges them back via
   that handle (or `discard` deletes the branches).

   Use `:ctx` when the fork must hold real side effects in isolation
   (proposals, speculative coding-agent work). Use `:none` (default)
   for message-only ToM probes where nothing in the fork commits.

   `:clone-participants?` defaults true for conversational forks. Internal Run
   worlds pass false: they need an isolated substrate, while any nested agent
   participation must be an explicit hire rather than an accidental clone of
   every participant in the parent Room.

   `:ctx` requires that subsequent operations on the fork (e.g.
   `(ask fork :agent …)` from outside the fork's body) bind
   `*execution-context*` to the fork's ctx — see `with-fork-ctx`."
  ([room] (fork-room room {}))
  ([room {:keys [isolation clone-participants? fork-opts]
          :or {isolation :none clone-participants? true}}]
   (locking (:meta room)
     (ensure-room-work-admitted! room :fork-room)
     (let [short-uuid (subs (str (random-uuid)) 0 8)
           new-slug   (str (:slug room) "/fork-" short-uuid)
         ;; Use the canonical slug→id encoding so id ↔ slug stay in
         ;; lock-step across the entire system (registry, store,
         ;; peer-bus events).
           new-id     (rstore/slug->room-id new-slug)
           parent-log (log room)
           room*       (volatile! nil)
           fork-handle (when (= :ctx isolation)
                         (binding [ec/*execution-context* (:ctx room)]
                           (ygg/fork! (merge {:purpose :workroom
                                              :owner new-id}
                                             fork-opts))))]
       (try
         (let [child-ctx  (case isolation
                            :none (:ctx room)
                            :ctx  (:child-ctx fork-handle))
         ;; Mark a `:ctx` fork TRANSIENT so create-room-db! defers the GLOBAL
         ;; system-db grant (reconciled on merge / dropped on discard) — a fork's
         ;; agent-created DB must not resurrect on restart (P2).
               _          (when (= :ctx isolation)
                            (binding [ec/*execution-context* child-ctx]
                              (ec/swap-state! [:dvergr/transient-fork?] (constantly true))))
         ;; `:isolation :ctx` forks BRANCH the parent's conversation. The fork
         ;; gets its OWN store wrapping the fork ctx's BRANCHED chat-db conn
         ;; (NOT the parent's fixed-conn store), and persists under the parent's
         ;; CONVERSATION id (root of the fork chain) — so a fork's messages are
         ;; the same logical conversation on a datahike branch, and
         ;; merge-fork! collapses them natively (no append-log!). It writes
         ;; no separate :chat entity. (doc/unified-fork-conversation.md)
               conv-id    (conversation-id room)
         ;; RF5: the fork's store wraps the PARENT room's OWN messages conn under
         ;; the fork ctx (branched), so fork messages ride the per-room store's
         ;; branch and merge-fork! collapses them natively. (RF5 S4.3: no
         ;; chat-db fallback — every room is provisioned with its own :msgs system.)
               fork-store (when (= :ctx isolation)
                            (some-> (binding [ec/*execution-context* child-ctx]
                                      (srooms/msgs-conn-for-slug (:slug room)))
                                    store-dh/make))
         ;; Durability-first for the fork too: fork-local messages persist
         ;; onto the BRANCH (under the root conversation id) inside post!,
         ;; before visibility — replacing the fork's persistence listener.
               child-bus  (bus-with-peer-relay child-ctx new-id :fork
                                               (when fork-store
                                                 {:durable-append!
                                                  (fn [msg]
                                                    (when (rstore/message-shape? msg)
                                                      (rstore/-store-message! fork-store conv-id msg)))}))
         ;; Seed the fork's bus log with parent history so log-based
         ;; consumers see a continuous record (the cursor starts past it —
         ;; history is never re-delivered). Forks have their OWN bus so
         ;; live messages do not leak between parent and fork.
               _          (bus/seed-log! child-bus parent-log)
               new-room   (->Room new-id new-slug
                                  (str (:title room) " · fork " short-uuid)
                                  (:id room)
                                  (atom {})
                                  child-bus
                                  child-ctx
                                  (count parent-log)
                                  fork-store
                                  (atom (assoc (dissoc @(:meta room) :dvergr/owned-listeners)
                                               :forked-from (:id room)
                                               :conversation-id conv-id))
                                  (when fork-handle (atom fork-handle))
                                  (random-uuid))
           ;; The child is not registered or otherwise visible yet. Initializing its
       ;; admission without the global lifecycle lock avoids parent-meta ->
       ;; lifecycle lock inversion during concurrent parent teardown.
               _ (vreset! room* new-room)
               _ (agent-run/initialize-unpublished-room-admission! new-id child-ctx)]
     ;; (Fork-local persistence rides the bus's durable-append! hook now —
     ;; wired at child-bus construction above.)
     ;; Initialize participant/cache projections before publishing the Room.
     ;; Otherwise an external registry reader could send work into a partially
     ;; constructed child and win the inherited-context installation race.
           (when clone-participants?
             (doseq [[_id p] @(:participants room)]
               (when-let [fac (:factory p)]
             ;; Yggdrasil already forked every registered world component. If
             ;; this participant has a live room working context, install its
             ;; child projection before the factory runs so the clone selects
             ;; that inherited SCI heap instead of constructing a second one.
             ;; `requiring-resolve` avoids discourse <-> room-context cycles.
                 (when (= :ctx isolation)
                   (when-let [fork-working-ctx!
                              (requiring-resolve 'dvergr.agent.room-context/fork-ctx!)]
                     (fork-working-ctx! room new-room (:id p))))
                 (binding [ec/*execution-context* child-ctx]
                   (join new-room (fac child-ctx))))))
     ;; Register the fully initialized fork in the PARENT ctx — where the daemon
     ;; UI reads the registry. A `:ctx` fork's own child-ctx is invisible to the
     ;; parent (CoW), so registering there would hide the fork from the tree.
           ;; Publication and its control event share the child's lifecycle
           ;; monitor. A reader may discover the complete Room+topology pair,
           ;; but cannot begin settlement until the creation event is posted.
           (locking (:meta new-room)
             (binding [ec/*execution-context* (:ctx room)]
               (if (= :ctx isolation)
                 (rreg/register-fork! new-room (:id room) (:fork-id fork-handle))
                 (rreg/register! new-room)))
     ;; Control-plane: announce the fork on the peer-bus so dashboards,
     ;; audit logs, and oversight agents see it without subscribing to
     ;; the fork's bus directly.
             (binding [ec/*execution-context* child-ctx]
               ;; Publication above is the authoritative commit point.  This
               ;; observability event must therefore never turn successful
               ;; construction into rollback after another thread has admitted
               ;; settlement of the published affine handle.
               (try
                 (peer-bus/post! {:type            :dvergr/fork-created
                                  :dvergr/origin   new-id
                                  :dvergr/parent   (:id room)
                                  :isolation       isolation
                                  :workspace-id    (when (= :ctx isolation)
                                                     (:id (geschichte/current-workspace)))})
                 (catch Throwable event-error
                   (tel/log! {:level :error :id ::fork-created-event-failed
                              :error event-error
                              :data {:room new-id :parent (:id room)}})))))
           new-room)
         (catch Throwable error
           ;; Construction owns the affine handle until successful return. Any
           ;; failure before then must settle it and retract every process-local
           ;; projection; otherwise the caller has no capability with which to
           ;; recover the unreachable branch.
           (binding [ec/*execution-context* (:ctx room)]
             (when-let [constructed @room*]
               (try (leave-all! constructed)
                    (catch Throwable cleanup-error
                      (.addSuppressed error cleanup-error)))
               (try
                 (when-let [drop-room!
                            (requiring-resolve 'dvergr.agent.room-context/drop-room!)]
                   (drop-room! new-id))
                 (catch Throwable cleanup-error
                   (.addSuppressed error cleanup-error)))
               (when (rreg/lookup new-id)
                 (try
                   (when fork-handle
                     (rreg/untrack-fork! new-id (:fork-id fork-handle)))
                   (catch Throwable cleanup-error
                     (.addSuppressed error cleanup-error)))
                 (rreg/unregister! new-id))))
           (when fork-handle
             (let [pending (try
                             (binding [ec/*execution-context*
                                       (or (some-> @room* :ctx)
                                           (:child-ctx fork-handle))]
                               (ec/get-state [:dvergr/pending-grants]))
                             (catch Throwable cleanup-error
                               (.addSuppressed error cleanup-error)
                               nil))]
               (try
                 (ygg/discard-fork! fork-handle)
                 (srooms/drop-fork-grants! pending)
                 (catch Throwable cleanup-error
                   (.addSuppressed error cleanup-error)))))
           (throw error)))))))

(defn fork-handle
  "Return an isolated Room fork's process-local canonical ForkHandle, or nil.
   The handle owns settlement authority and must never be stored durably."
  [room]
  (some-> room :fork-handle deref))

(defn fork-descriptor
  "Return the portable world descriptor for an isolated Room fork, or nil."
  [room]
  (some-> (fork-handle room) ygg/fork-descriptor))

(declare fork-home-ctx)

(def ^:dynamic ^:private *deferred-settlement-authority* nil)

(defn- assert-settlement-released! [fork operation]
  (locking (:meta fork)
    (let [meta @(:meta fork)
          live (binding [ec/*execution-context* (fork-home-ctx fork)]
                 (rreg/lookup (:id fork)))]
      (when (and (= :deferred (:settlement-policy meta))
                 (= fork live)
                 (not (:settlement-released? meta))
                 (not= (:id fork) *deferred-settlement-authority*))
        (throw (ex-info "Deferred Run world must be released before settlement"
                        {:type ::settlement-deferred
                         :operation operation
                         :fork/id (:id fork)
                         :run/id (:run-id meta)}))))))

(defn- fork-transfer-children [home fork-id]
  (binding [ec/*execution-context* home]
    (rreg/structural-children fork-id)))

(defn- assert-fork-quiescent! [fork home]
  (when-let [run-id (some-> fork :meta deref :run-id)]
    (when (some #(= run-id (:run/id %)) (agent-run/active-runs))
      (throw (ex-info "A Run world cannot transfer while its executor is live"
                      {:type ::fork-not-quiescent :fork/id (:id fork) :run/id run-id}))))
  (when (seq @(:participants fork))
    (throw (ex-info "Fork must be quiescent before authority transfer"
                    {:type ::fork-not-quiescent
                     :fork/id (:id fork)
                     :participants (vec (keys @(:participants fork)))})))
  (when-let [children (seq (fork-transfer-children home (:id fork)))]
    (throw (ex-info "Settle transferred child worlds before their parent"
                    {:type ::fork-has-open-children
                     :fork/id (:id fork)
                     :children (mapv :fork/id children)}))))

(defn- detach-transferred-room! [fork home owner]
  (swap! (:meta fork) assoc fork-transfer-state-key
         {:state :transferred :owner owner})
  (binding [ec/*execution-context* home]
    (rreg/mark-fork-transferred! (:id fork) owner)
    (rreg/unregister! (:id fork))))

(defn transfer-fork!
  "Transfer an isolated Room fork's settlement authority to a durable owner.

   `prepare!` is the durability boundary. It is called with the prospective
   portable descriptor (already naming `new-owner`) *before* live authority is
   transferred. It must durably record the owner/descriptor as a retained GC
   root, or throw. A preparation failure leaves the Room registered and its
   original ForkHandle open.

   On success the executor is quiesced, the original handle becomes stale, and
   the transient Room disappears from the registry so its Merge/Discard UI
   cannot compete with the adopter. The returned `:fork/handle` is the sole
   process-local settlement capability; callers must never persist it. The
   returned `:fork/descriptor` is data and is the canonical durable identity.

  `abort!` is required and is called with the preparation receipt if the live
   affine transfer fails. A failed compensation leaves the Room fenced in a
   visible recovery-required state. Once transfer succeeds the durable owner is
   authoritative; later projection/event failures never roll it back."
  [fork new-owner prepare-or-opts]
  (assert-settlement-released! fork :transfer)
  (let [{:keys [prepare! abort!]}
        (if (fn? prepare-or-opts)
          {:prepare! prepare-or-opts}
          prepare-or-opts)]
    (when-not (fn? prepare!)
      (throw (ex-info "Fork transfer requires a durable prepare! callback"
                      {:type ::durable-prepare-required
                       :fork/id (:id fork)})))
    (when-not (fn? abort!)
      (throw (ex-info "Fork transfer requires a durable abort! callback"
                      {:type ::durable-abort-required
                       :fork/id (:id fork)})))
    (when (nil? new-owner)
      (throw (ex-info "Fork transfer owner cannot be nil"
                      {:type ::invalid-fork-owner
                       :fork/id (:id fork)})))
    (let [home (fork-home-ctx fork)]
      (when-not (binding [ec/*execution-context* home]
                  (rreg/lookup (:id fork)))
        (throw (ex-info "Fork is not live in its parent registry"
                        {:type ::fork-not-live
                         :fork/id (:id fork)})))
      (let [handle (or (fork-handle fork)
                       (throw (ex-info "Only an isolated context fork can transfer"
                                       {:type ::fork-not-isolated
                                        :fork/id (:id fork)})))]
        (when-not (ygg/open-fork? handle)
          (throw (ex-info "Fork no longer owns open settlement authority"
                          {:type ::fork-not-open
                           :fork/id (:id fork)
                           :fork/descriptor (ygg/fork-descriptor handle)})))
        (let [prepared? (volatile! false)
              receipt* (volatile! nil)
              listeners* (volatile! [])
              fence-token* (volatile! nil)
              work-fence-token* (volatile! nil)
              {:keys [adopted receipt descriptor]}
              (try
                ;; No worker may retain a capability into a substrate once
                ;; ownership moves. Listener drain atomically installs the Room
                ;; fence and closes Run admission at one lifecycle frontier.
                (vreset! listeners* (drain-room-listeners! fork :transfer))
                (vreset! fence-token*
                         (get-in @(:meta fork) [fork-transfer-state-key :token]))
                (try
                  (vreset! work-fence-token* (sandbox-work/close-room-work! fork))
                  (catch Throwable error
                    (vreset! work-fence-token* (:fence (ex-data error)))
                    (throw error)))
                (drain-room-runs! fork)
                ;; Once every admitted Run has physically stopped, retire its
                ;; causal-post allowance before durable preparation or authority
                ;; transfer can observe/move the branch.
                (seal-room-quiescence! fork :transfer)
                (assert-fork-quiescent! fork home)
                (let [parent (binding [ec/*execution-context* home]
                               (rreg/lookup (:parent-id fork)))
                      parent-fork-id (some-> parent fork-handle ygg/fork-descriptor :fork/id)
                      portable-ancestry {:dvergr/room-id (:id fork)
                                         :dvergr/parent-room-id (:parent-id fork)
                                         :dvergr/parent-fork-id parent-fork-id}
                      prospective (merge (ygg/fork-descriptor handle)
                                         portable-ancestry
                                         {:fork/owner new-owner
                                          :fork/status :open})
                      receipt (prepare! prospective)]
                  (vreset! prepared? true)
                  (vreset! receipt* receipt)
                  ;; Defense in depth against code bypassing public APIs and
                  ;; mutating participant/topology state during preparation.
                  (assert-fork-quiescent! fork home)
                  (let [adopted (ygg/transfer-fork! handle new-owner)]
                    {:adopted adopted
                     :receipt receipt
                     :descriptor (merge (ygg/fork-descriptor adopted)
                                        portable-ancestry)}))
                (catch Throwable error
                  (let [abort-error (when @prepared?
                                      (try
                                        (abort! @receipt*)
                                        nil
                                        (catch Throwable compensation-error
                                          compensation-error)))]
                    (cond
                      (= ::listener-drain-timeout (:type (ex-data error)))
                      (do
                        (swap! (:meta fork) update fork-transfer-state-key
                               assoc
                               :state :recovery-required
                               :owner new-owner
                               :error (ex-message error))
                        (throw error))

                      abort-error
                      (do
                        (swap! (:meta fork) update fork-transfer-state-key
                               assoc
                               :state :recovery-required
                               :owner new-owner
                               :error (ex-message abort-error))
                        (throw (ex-info "Fork transfer compensation failed; recovery is required"
                                        {:type ::fork-transfer-recovery-required
                                         :fork/id (:id fork)
                                         :fork/owner new-owner
                                         :transfer-error (ex-message error)
                                         :abort-error (ex-message abort-error)}
                                        abort-error)))

                      (ygg/open-fork? handle)
                      (let [restore-error (try
                                            (restore-room-listeners! fork @listeners*)
                                            nil
                                            (catch Throwable listener-error
                                              listener-error))]
                        (if restore-error
                          (do
                            (swap! (:meta fork) update fork-transfer-state-key
                                   assoc
                                   :state :recovery-required
                                   :owner new-owner
                                   :error (ex-message restore-error))
                            (throw (ex-info "Fork listener restoration failed; recovery is required"
                                            {:type ::fork-transfer-recovery-required
                                             :fork/id (:id fork)
                                             :fork/owner new-owner
                                             :transfer-error (ex-message error)
                                             :restore-error (ex-message restore-error)}
                                            restore-error)))
                          (let [recovered?
                                (agent-run/recover-room-admission!
                                 fork
                                 (fn []
                                   (locking (:meta fork)
                                     (when (and (= @fence-token*
                                                   (get-in @(:meta fork)
                                                           [fork-transfer-state-key :token]))
                                                (or (nil? @work-fence-token*)
                                                    (sandbox-work/recover-room-work!
                                                     fork @work-fence-token*)))
                                       (swap! (:meta fork) dissoc fork-transfer-state-key)
                                       true))))]
                            (if recovered?
                              (throw error)
                              (throw (ex-info "Fork lifecycle changed during transfer recovery"
                                              {:type ::fork-transfer-recovery-required
                                               :fork/id (:id fork)
                                               :fork/owner new-owner
                                               :transfer-error (ex-message error)}
                                              error))))))

                      :else
                      (do
                        ;; Another claimant owns the affine capability. Keep
                        ;; the stale Room inert and detach it from execution.
                        (detach-transferred-room! fork home :external-claimant)
                        (throw error))))))]
         ;; Keep the stale original handle on the detached Room object. Any
         ;; leaked reference fails affine authority checks instead of silently
         ;; becoming a store-less conversational fork.
          (detach-transferred-room! fork home new-owner)
          (swap! (:meta fork) assoc :fork-transferred-to new-owner)
          (binding [ec/*execution-context* home]
            (try
              (peer-bus/post! {:type :dvergr/fork-transferred
                               :dvergr/origin (:id fork)
                               :dvergr/parent (:parent-id fork)
                               :fork/id (:fork/id descriptor)
                               :fork/owner new-owner})
              (catch Throwable error
                (tel/log! {:level :warn
                           :id ::fork-transfer-event-failed
                           :data {:fork (:id fork)
                                  :owner new-owner
                                  :error (ex-message error)}}
                          "Fork authority transferred but its peer event failed"))))
          {:fork/handle adopted
           :fork/descriptor descriptor
           :fork/receipt receipt
           :fork/room-id (:id fork)
           :fork/parent-id (:parent-id fork)})))))

(defn- assert-transferred-node-identity!
  [{:fork/keys [handle descriptor room-id]}]
  (when-not (and (ygg/fork-handle? handle)
                 (= (:fork/id descriptor) (:fork-id handle)))
    (throw (ex-info "Transferred fork handle does not match its descriptor"
                    {:type ::transferred-fork-identity-mismatch
                     :fork/id room-id
                     :fork/descriptor-id (:fork/id descriptor)
                     :fork/handle-id (some-> handle :fork-id)})))
  (when-not (= room-id (:dvergr/room-id descriptor))
    (throw (ex-info "Transferred fork Room identity does not match its descriptor"
                    {:type ::transferred-fork-identity-mismatch
                     :fork/id room-id
                     :fork/descriptor-room-id (:dvergr/room-id descriptor)})))
  (let [authoritative (ygg/fork-descriptor handle)
        identity-keys [:fork/id :fork/settlement-id :fork/partition-of
                       :fork/world-systems :fork/systems]]
    (when-not (= (select-keys authoritative identity-keys)
                 (select-keys descriptor identity-keys))
      (throw (ex-info "Transferred fork descriptor does not name this settlement capability"
                      {:type ::transferred-fork-capability-mismatch
                       :fork/id room-id
                       :fork/descriptor (select-keys descriptor identity-keys)
                       :fork/authoritative (select-keys authoritative identity-keys)})))))

(defn partition-transferred-fork!
  "Consume a transferred world's aggregate authority into disjoint scopes.

   `transfer` is the value returned by `transfer-fork!` (or a partition node
   returned here). `partitions` has Spindel's exhaustive shape:

     [{:systems #{system-id ...} :owner owner-id :purpose optional-tag} ...]

   The optional lifecycle callbacks make the destructive authority transition
   honest at a durable boundary:

   - `prepare!` durably records the intended partition plan before Spindel
     consumes the aggregate handle and returns a receipt.
   - `abort!` compensates that preparation if partition validation/CAS fails.
   - `commit!` records the exact child descriptors after their settlement IDs
     exist. A commit failure cannot undo partitioning, so it is retained under
     `:fork/partition-commit` alongside every live child capability and receipt.

   With no callbacks, partitioning is intentionally ephemeral. If `prepare!`
   is supplied, `abort!` and `commit!` are required. The result is a persistent
   capability tree; callers may recursively partition a child node and replace
   that node in the tree. Never persist any `:fork/handle`."
  ([transfer partitions]
   (partition-transferred-fork! transfer partitions {}))
  ([{:fork/keys [handle descriptor room-id] :as transfer}
    partitions
    {:keys [prepare! abort! commit!]}]
   (assert-transferred-node-identity! transfer)
   (when (and (or abort! commit!) (not prepare!))
     (throw (ex-info "Partition lifecycle callbacks require prepare!"
                     {:type ::invalid-partition-lifecycle
                      :fork/id room-id})))
   (when (and prepare! (not (and (fn? prepare!) (fn? abort!) (fn? commit!))))
     (throw (ex-info "Durable partitioning requires prepare!, abort!, and commit! callbacks"
                     {:type ::invalid-partition-lifecycle
                      :fork/id room-id})))
   (locking (:authority handle)
     (let [plan {:fork/descriptor descriptor
                 :fork/partitions (vec partitions)}
           prepared? (volatile! false)
           receipt* (volatile! nil)
           handles
           (try
             (when prepare!
               (vreset! receipt* (prepare! plan))
               (vreset! prepared? true))
             (ygg/partition-fork! handle partitions)
             (catch Throwable error
               (when @prepared?
                 (try
                   (abort! @receipt*)
                   (catch Throwable abort-error
                     (throw (ex-info "Fork partition compensation failed; recovery is required"
                                     {:type ::fork-partition-recovery-required
                                      :fork/id room-id
                                      :fork/receipt @receipt*
                                      :partition-error (ex-message error)
                                      :abort-error (ex-message abort-error)}
                                     abort-error)))))
               (throw error)))
           ancestry (select-keys descriptor [:dvergr/room-id
                                             :dvergr/parent-room-id
                                             :dvergr/parent-fork-id])
           nodes (mapv (fn [part-handle]
                         {:fork/handle part-handle
                          :fork/descriptor (merge (ygg/fork-descriptor part-handle)
                                                  ancestry)
                          :fork/room-id room-id
                          :fork/parent-id (:fork/parent-id transfer)})
                       handles)
           result (assoc transfer
                         :fork/descriptor (merge (ygg/fork-descriptor handle) ancestry)
                         :fork/partitions nodes)
           commit-error (when commit!
                          (try
                            (commit! @receipt* (mapv :fork/descriptor nodes))
                            nil
                            (catch Throwable error error)))]
       (cond-> result
         commit!
         (assoc :fork/partition-commit
                (cond-> {:status (if commit-error :failed :committed)
                         :receipt @receipt*}
                  commit-error (assoc :error commit-error))))))))

(defn retry-partition-commit!
  "Retry the durable exact-descriptor commit after partitioning succeeded.

   Returns the same capability tree with a committed durability marker. No
   substrate authority changes during this operation."
  [{:fork/keys [partitions partition-commit] :as transfer} commit!]
  (when-not (= :failed (:status partition-commit))
    (throw (ex-info "Fork partition has no failed durable commit to retry"
                    {:type ::partition-commit-not-retryable
                     :fork/status (:status partition-commit)})))
  (commit! (:receipt partition-commit) (mapv :fork/descriptor partitions))
  (assoc transfer :fork/partition-commit
         {:status :committed :receipt (:receipt partition-commit)}))

(defn settle-transferred-fork!
  "Settle one transferred leaf through an optional durable governance boundary.

   `operation` is `:merge` or `:discard`. With lifecycle callbacks, `prepare!`
   durably claims the decision before substrate mutation, `commit!` runs as
   Spindel's exactly-once post-commit callback, and `abort!` compensates only a
   failed preflight that left the capability open. A post-mutation or durable
   commit failure is returned with the live terminal/incomplete capability and
   receipt; it is never disguised as an open review world.

   `retry-settlement-commit!` re-drives only the durable commit. The returned
   node must replace its predecessor in the capability tree before release."
  ([transfer operation]
   (settle-transferred-fork! transfer operation {}))
  ([{:fork/keys [handle descriptor room-id] :as transfer}
    operation
    {:keys [prepare! abort! commit!] :as lifecycle}]
   (assert-transferred-node-identity! transfer)
   (when-not (contains? #{:merge :discard} operation)
     (throw (ex-info "Unknown transferred fork settlement operation"
                     {:type ::invalid-transferred-settlement
                      :operation operation})))
   (when (seq (:fork/partitions transfer))
     (throw (ex-info "Settle partition leaves, not their aggregate handle"
                     {:type ::partition-aggregate-cannot-settle
                      :fork/id room-id})))
   (when (and (seq lifecycle)
              (not (and (fn? prepare!) (fn? abort!) (fn? commit!))))
     (throw (ex-info "Durable settlement requires prepare!, abort!, and commit! callbacks"
                     {:type ::invalid-settlement-lifecycle
                      :fork/id room-id})))
   ;; Durable intent and the Spindel CAS form one Dvergr-owned frontier. Raw
   ;; handle calls remain trusted-host internals and must not bypass this API.
   (locking (:authority handle)
     (when-not (ygg/open-fork? handle)
       (throw (ex-info "Transferred fork leaf is not open"
                       {:type ::transferred-fork-not-open
                        :fork/id room-id
                        :fork/status (:status (ygg/fork-disposition handle))})))
     (let [ancestry (select-keys descriptor [:dvergr/room-id
                                             :dvergr/parent-room-id
                                             :dvergr/parent-fork-id])
           plan {:fork/operation operation
                 :fork/descriptor (merge (ygg/fork-descriptor handle) ancestry)}
           receipt (when prepare! (prepare! plan))
           commit-value* (atom nil)
           commit-callback
           (when commit!
             (fn [_payload]
               (let [value {:fork/operation operation
                            :fork/descriptor (merge (ygg/fork-descriptor handle) ancestry)}]
                 (reset! commit-value* value)
                 (commit! receipt value))))]
       (try
         (let [result (case operation
                        :merge (ygg/merge-fork! handle {:on-merge commit-callback})
                        :discard (ygg/discard-fork! handle {:on-discard commit-callback}))]
           (cond-> (assoc transfer
                          :fork/descriptor (merge (ygg/fork-descriptor handle) ancestry)
                          :fork/result result)
             commit! (assoc :fork/settlement
                            {:status :committed
                             :operation operation
                             :receipt receipt
                             :commit-value @commit-value*})))
         (catch Throwable error
           (let [{:keys [status]} (ygg/fork-disposition handle)]
             (if (= :open status)
               (if abort!
                 (if-let [abort-error (try
                                        (abort! receipt)
                                        nil
                                        (catch Throwable abort-error abort-error))]
                   (assoc transfer
                          :fork/descriptor (merge (ygg/fork-descriptor handle) ancestry)
                          :fork/settlement
                          {:status :abort-failed
                           :operation operation
                           :receipt receipt
                           :error error
                           :abort-error abort-error})
                   (throw error))
                 (throw error))
               (assoc transfer
                      :fork/descriptor (merge (ygg/fork-descriptor handle) ancestry)
                      :fork/settlement
                      {:status (if (contains? #{:merged :discarded} status)
                                 :commit-failed
                                 :incomplete)
                       :operation operation
                       :receipt receipt
                       :commit-value @commit-value*
                       :error error})))))))))

(defn retry-settlement-commit!
  "Retry only the durable commit of a terminal transferred leaf."
  [{:fork/keys [settlement] :as transfer} commit!]
  (when-not (= :commit-failed (:status settlement))
    (throw (ex-info "Fork settlement has no failed durable commit to retry"
                    {:type ::settlement-commit-not-retryable
                     :fork/status (:status settlement)})))
  (commit! (:receipt settlement) (:commit-value settlement))
  (assoc transfer :fork/settlement
         (-> settlement
             (assoc :status :committed)
             (dissoc :error))))

(defn retry-settlement-abort!
  "Retry compensation after a preflight failure left authority open.

   A successful retry removes the failed intent marker and returns the original
   open capability, which may then receive a fresh governed decision."
  [{:fork/keys [settlement] :as transfer} abort!]
  (when-not (= :abort-failed (:status settlement))
    (throw (ex-info "Fork settlement has no failed compensation to retry"
                    {:type ::settlement-abort-not-retryable
                     :fork/status (:status settlement)})))
  (abort! (:receipt settlement))
  (dissoc transfer :fork/settlement))

(defn- assert-transferred-node-settled!
  [{:fork/keys [handle room-id partitions partition-commit settlement] :as node}
   seen
   durable-required?]
  (assert-transferred-node-identity! node)
  (when (contains? seen (:token handle))
    (throw (ex-info "Transferred fork capability occurs more than once in its tree"
                    {:type ::duplicate-transferred-fork-capability
                     :fork/id room-id
                     :fork/settlement-id (get-in node [:fork/descriptor
                                                       :fork/settlement-id])})))
  (when (and partition-commit (not= :committed (:status partition-commit)))
    (throw (ex-info "Transferred fork partition descriptors are not durably committed"
                    {:type ::partition-commit-incomplete
                     :fork/id room-id
                     :fork/status (:status partition-commit)})))
  (let [seen (conj seen (:token handle))
        {:keys [status token] :as disposition} @(:authority handle)
        current? (= token (:token handle))
        durable-children? (or durable-required? (some? partition-commit))]
    (cond
      (and current? (contains? #{:merged :discarded} status))
      (if (and (or durable-required? (some? settlement))
               (not= :committed (:status settlement)))
        (throw (ex-info "Transferred fork leaf settlement is not durably committed"
                        {:type ::settlement-commit-incomplete
                         :fork/id room-id
                         :fork/status (:status settlement)}))
        seen)

      (and current? (= :partitioned status))
      (let [_ (when (and durable-required? (nil? partition-commit))
                (throw (ex-info "Nested fork partition descriptors are not durably committed"
                                {:type ::partition-commit-incomplete
                                 :fork/id room-id
                                 :fork/status :missing})))
            expected (set (:partitions disposition))
            actual (set (map #(get-in % [:fork/descriptor :fork/settlement-id])
                             partitions))]
        (when-not (= expected actual)
          (throw (ex-info "Transferred fork partition tree is incomplete"
                          {:type ::transferred-fork-partitions-incomplete
                           :fork/id room-id
                           :expected expected
                           :actual actual})))
        (reduce (fn [seen part]
                  (assert-transferred-node-settled! part seen durable-children?))
                seen
                partitions))

      :else
      (throw (ex-info "Transferred fork has not settled with this authority"
                      {:type ::transferred-fork-not-settled
                       :fork/id room-id
                       :fork/status status
                       :fork/current-authority? current?})))))

(defn release-transferred-fork!
  "Release a transferred child's structural parent claim after settlement.

   Accepts the capability tree returned by `transfer-fork!` and optionally
   `partition-transferred-fork!`. Every leaf must have settled with its exact
   affine authority and every partition node must list all children before the
   structural ancestry claim is released."
  [{:fork/keys [handle room-id] :as transfer}]
  (assert-transferred-node-settled! transfer #{} false)
  (binding [ec/*execution-context* (:parent-ctx handle)]
    (rreg/untrack-fork! room-id (:fork-id handle)))
  nil)

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

(defn- fork-home-ctx
  "The ctx where a fork's registry entry + control-plane events live — its
   PARENT ctx, matching `fork-room`'s registration (a `:ctx` fork's own
   child-ctx is invisible to the parent under CoW, so registry ops must run in
   the parent). For `:none` forks (no distinct parent ctx) this is the fork's
   own ctx."
  [fork]
  (room-home-ctx fork))

(defn discard
  "Discard a fork: deregister its participants, drop it from the
   registry, and if the fork's ctx was forked (`:isolation :ctx`), settle its
   canonical ForkHandle by discarding every branched Yggdrasil system.
   Participant processes and subscription pumps are cancelled in both
   isolation modes.

   Emits `:dvergr/fork-discarded` on the peer-bus. Idempotent — a second
   discard of the same fork is a no-op (the branched systems are deleted only
   once)."
  [fork]
  (assert-settlement-released! fork :discard)
  ;; Idempotence: once unregistered, the fork is a zombie — re-discarding would
  ;; double-delete the yggdrasil branch (which errors). Guard on registry.
  (when (binding [ec/*execution-context* (fork-home-ctx fork)] (rreg/lookup (:id fork)))
    (let [callbacks (drain-room-listeners! fork :discard)
          work-fence-token* (volatile! nil)]
      (try
        (try
          (vreset! work-fence-token* (sandbox-work/close-room-work! fork))
          (catch Throwable error
            (vreset! work-fence-token* (:fence (ex-data error)))
            (throw error)))
        (drain-room-runs! fork)                ; stop work before removing its substrate
        (seal-room-quiescence! fork :discard)
        (when-let [handle (fork-handle fork)]
          ;; P2: settle the substrate first, then drop deferred grants explicitly.
          ;; If grant cleanup fails, retrying `discard` replays the cached settlement
          ;; and retries only this idempotent integration step.
          (let [pending (binding [ec/*execution-context* (:ctx fork)]
                          (ec/get-state [:dvergr/pending-grants]))]
            (ygg/discard-fork! handle)
            (srooms/drop-fork-grants! pending)))
        (leave-all! fork)
        (binding [ec/*execution-context* (fork-home-ctx fork)]
          (rreg/untrack-fork! (:id fork) (some-> fork fork-handle :fork-id))
          (rreg/unregister! (:id fork))
          (peer-bus/post! {:type :dvergr/fork-discarded
                           :dvergr/origin (:id fork)}))
        (catch Throwable error
          (recover-room-quiescence! fork callbacks @work-fence-token* error)))))
  fork)

(defn discard-deferred
  "Consume the host-owned discard side of a deferred Run world.

   This is the narrow substrate capability used by evaluation cancellation and
   failed certification. Ordinary `discard`, merge, and transfer remain gated."
  ([fork]
   (discard-deferred fork (constantly true) (constantly nil)))
  ([fork claim!]
   (discard-deferred fork claim! (constantly nil)))
  ([fork claim! abort!]
   (locking (:meta fork)
     (let [meta @(:meta fork)
           live (binding [ec/*execution-context* (fork-home-ctx fork)]
                  (rreg/lookup (:id fork)))]
       (when-not (and (= fork live)
                      (= :deferred (:settlement-policy meta))
                      (not (:settlement-released? meta))
                      (nil? (:settlement-claim meta)))
         (throw (ex-info "Deferred discard authority is no longer current"
                         {:type ::stale-deferred-settlement
                          :fork/id (:id fork)
                          :run/id (:run-id meta)})))
       (when-not (claim!)
         (throw (ex-info "Deferred discard lost its settlement race"
                         {:type ::deferred-settlement-aborted
                          :fork/id (:id fork)})))
       (swap! (:meta fork) assoc :settlement-claim :discard)
       (try
         (binding [*deferred-settlement-authority* (:id fork)]
           (discard fork))
         (catch Throwable error
           ;; Abort while the same affine lock is still held. If its durable
           ;; compensation fails, retain a closed recovery claim rather than
           ;; opening a race or holding this lock through an unbounded retry.
           (let [abort-error (try (abort!) nil
                                  (catch Throwable abort-error abort-error))]
             (if abort-error
               (swap! (:meta fork) assoc :settlement-claim :discard-recovery)
               (swap! (:meta fork) dissoc :settlement-claim))
             (throw (if abort-error
                      (ex-info "Deferred discard requires durable abort recovery"
                               {:type ::deferred-discard-recovery-required
                                :fork/id (:id fork)
                                :abort-error (ex-message abort-error)}
                               error)
                      error)))))))
   fork))

(defn merge-room
  "Merge fork into parent.

   1. Append the fork's new log entries (those added after the fork
      point) to the parent's log.
   2. If the fork's ctx was forked (`:isolation :ctx`), settle its canonical
      ForkHandle by merging all branched Yggdrasil systems into the parent —
      datahike branches collapse, git branches fast-forward or three-way merge.
   3. Deregister the fork's participants.

   Concurrent sibling forks UNION (identity-keyed datahike merge); a genuine
   field clash (same entity+attr changed differently on both sides) is a 3-way
   CONFLICT that `merge-fork!` refuses by default. Pass `{:merge-opts
   {:force true}}` (the agent reconciler does, after resolving) to force past it.
   (doc/unified-fork-conversation.md, dvergr.rooms.forks/reconcile-merge!.)"
  ([parent fork] (merge-room parent fork {}))
  ([parent fork {:keys [merge-opts]}]
   (assert-settlement-released! fork :merge)
   (let [callbacks (drain-room-listeners! fork :merge)
         work-fence-token* (volatile! nil)]
     (try
       (try
         (vreset! work-fence-token* (sandbox-work/close-room-work! fork))
         (catch Throwable error
           (vreset! work-fence-token* (:fence (ex-data error)))
           (throw error)))
       (drain-room-runs! fork)                 ; stop work before merging its substrate
       (seal-room-quiescence! fork :merge)
   ;; (1) SUBSTRATE merge — branched yggdrasil systems (CRDTs, datahike, git) fold
   ;; back whenever the ctx was forked (`:isolation :ctx`), INDEPENDENT of any
   ;; conversation store. These are orthogonal: a room can carry shared CRDTs with
   ;; no message store (a subagent whose deliverable is CRDT edits + a summary), or
   ;; a message store with no extra systems. A `:store` fork's datahike *message*
   ;; branch also collapses here (bringing its messages into the parent's
   ;; conversation under the shared :chat/id); its deferred data-DB grants commit on
   ;; accept via :on-merge (store forks only). (doc/unified-fork-conversation.md)
       (when-let [handle (fork-handle fork)]
         (try
           (let [pending (when (:store fork)
                           (binding [ec/*execution-context* (:ctx fork)]
                             (ec/get-state [:dvergr/pending-grants])))]
             (ygg/merge-fork! handle (or merge-opts {}))
         ;; Grant integration is deliberately outside substrate settlement. A
         ;; failure leaves the handle truthfully :merged and a retry replays the
         ;; cached merge before retrying this idempotent commit.
             (when (:store fork)
               (srooms/commit-fork-grants! pending)))
           (catch Throwable e
             (tel/log! {:level :error :id :dvergr/merge-failed
                        :data {:fork (:id fork) :parent (:id parent) :error (str e)}}
                       "merge-room: affine yggdrasil settlement failed")
             (binding [ec/*execution-context* (fork-home-ctx fork)]
               (peer-bus/post! {:type :dvergr/merge-failed :dvergr/origin (:id fork)
                                :dvergr/parent (:id parent) :error (str e)}))
             (throw e))))
   ;; (2) CONVERSATION merge — for STORE-LESS forks (`:isolation :none`, or a `:ctx`
   ;; fork without a message store), absorb the fork's post-fork bus entries into the
   ;; parent's log (merge-as-history; no re-firing of live handlers, separate buses).
   ;; A `:store` fork's messages already arrived via the datahike collapse in (1).
       (when-not (:store fork)
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
       (leave-all! fork)
       (binding [ec/*execution-context* (fork-home-ctx fork)]
         (rreg/untrack-fork! (:id fork) (some-> fork fork-handle :fork-id))
         (rreg/unregister! (:id fork))
         (peer-bus/post! {:type            :dvergr/fork-merged
                          :dvergr/origin   (:id fork)
                          :dvergr/parent   (:id parent)}))
       parent
       (catch Throwable error
         (recover-room-quiescence! fork callbacks @work-fence-token* error))))))

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
  (let [diff      (when (fork-handle fork)
                    (geschichte/diff-since-fork (:ctx fork)))
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
