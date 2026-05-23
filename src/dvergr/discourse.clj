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
            [org.replikativ.spindel.spin.sync :as sync]
            [org.replikativ.spindel.spin.combinators :as comb]
            [is.simm.partial-cps.sequence :refer [anext]]))

;; ============================================================================
;; Records
;; ============================================================================

(defrecord Message     [id from to content ts in-reply-to metadata])

(defrecord Participant
  ;; on-message :: (fn [participant envelope] -> Spin[ReplySpec | nil])
  ;;   envelope is either a Message (from inbox) or a synthetic event:
  ;;     {:type :tick}                          — periodic self-tick
  ;;     {:type :source :name kw :msg evt-data} — external source event
  ;; factory    :: (fn [new-ctx] -> Participant) — for cloning into a fork
  ;; process    :: the spindel spin driving this participant (set by `join`)
  ;; tick-ms    :: nil or interval in ms (`with-cadence`); fires {:type :tick}
  ;; sources    :: nil or [{:name kw :source PAsyncSeq}] (`with-sources`)
  [id inbox on-message factory process tick-ms sources])

(defrecord Room
  ;; participants : atom of {id → Participant or stub-map {:id :inbox}}
  ;; log          : atom of [Message] — append-only, ordered by delivery
  ;; ctx          : spindel ExecutionContext
  ;; forked-at-len: index into log at fork time (nil/0 for root rooms)
  [id participants log ctx forked-at-len])

(defn message
  "Construct a Message. Auto-fills id and ts."
  ([from to content] (message from to content nil nil))
  ([from to content in-reply-to] (message from to content in-reply-to nil))
  ([from to content in-reply-to metadata]
   (->Message (random-uuid) from to content (System/currentTimeMillis)
              in-reply-to metadata)))

;; ============================================================================
;; Delivery
;; ============================================================================

(defn- route-and-log!
  "Append msg to log; deliver to addressee's mailbox if registered."
  [room msg]
  (swap! (:log room) conj msg)
  (when-let [target (get @(:participants room) (:to msg))]
    (sync/post! (:inbox target) msg))
  msg)

(defn post!
  "Route a Message into the room. Safe to call from any thread."
  [room msg]
  (binding [ec/*execution-context* (:ctx room)]
    (route-and-log! room msg)))

(defn post-batch!
  "Route msgs into the room atomically (one log mutation, ordered delivery)."
  [room msgs]
  (binding [ec/*execution-context* (:ctx room)]
    (doseq [m msgs] (route-and-log! room m))
    msgs))

;; ============================================================================
;; Construction
;; ============================================================================

(defn room
  "Create a Room. With one arg, allocates a fresh ExecutionContext;
   with two, uses the provided ctx (useful for nested rooms)."
  ([id] (room id (sp/create-execution-context)))
  ([id ctx]
   (->Room id (atom {}) (atom []) ctx 0)))

(defn participant
  "Construct a Participant.

   :id          — keyword identifier (unique per room)
   :on-message  — (fn [p envelope] -> Spin[ReplySpec | nil]) where envelope is
                  a Message (inbox) or {:type :tick} / {:type :source :name K
                  :msg E} for driver-supplied events; ReplySpec is
                  {:to id :content str} or nil for no reply
   :factory     — (fn [new-ctx] -> Participant); enables fork-room cloning
   :ctx         — execution context (default: *execution-context*)

   Drivers (tick / sources) are added with `with-cadence` / `with-sources`."
  [{:keys [id on-message factory ctx]}]
  (let [ctx   (or ctx ec/*execution-context*)
        inbox (sync/create-mailbox ctx)]
    (->Participant id inbox on-message factory nil nil nil)))

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
  "Spawn one fire-and-forget spin per driver that pumps events into `inbox`
   as synthetic envelopes ({:type :tick} or {:type :source :name K :msg E}).

   Each driver has its OWN consumer loop — we deliberately do NOT race
   them against the inbox in the participant-spin, because consuming
   from stateful mailboxes inside `comb/race` is destructive: when
   multiple arms have queued data, the race winner cancels the losers
   AFTER their awaits have already taken from their queues, dropping
   those values. Per-driver pumps with a single shared inbox sidestep
   that whole class of bug."
  [p]
  (when-let [tick-ms (:tick-ms p)]
    (sp/spawn!
      (sp/spin
        (loop []
          (sp/await (comb/sleep tick-ms))
          (sync/post! (:inbox p) {:type :tick})
          (recur)))))
  (doseq [{:keys [name source]} (:sources p)]
    (sp/spawn!
      (sp/spin
        (loop []
          (when-let [[v _] (sp/await (anext source))]
            (sync/post! (:inbox p) {:type :source :name name :msg v})
            (recur)))))))

(defn- participant-spin
  "Continuous-time loop: await next inbox event → run on-message → emit
   reply if any → recur. The implementation of §5.1 + §5.5.

   The inbox is the single event channel: real Messages flow in from
   `route-and-log!`; driver envelopes ({:type :tick} / {:type :source ...})
   are posted by per-driver pumps spawned in `join`. on-message
   distinguishes them by checking `(instance? Message env)`."
  [p room]
  (sp/spin
    (loop []
      (let [env (sp/await (:inbox p))
            in-reply-to (when (instance? Message env) (:id env))
            reply-spec (sp/await ((:on-message p) p env))]
        (emit-reply! room p reply-spec in-reply-to))
      (recur))))

(defn join
  "Register participant in room, start its spin, and spawn any driver
   pumps (tick / sources). Returns the participant with :process set."
  [room p]
  (swap! (:participants room) assoc (:id p) p)
  (binding [ec/*execution-context* (:ctx room)]
    (start-driver-pumps! p)
    (let [proc (participant-spin p room)]
      (sp/spawn! proc)
      (let [p' (assoc p :process proc)]
        (swap! (:participants room) assoc (:id p) p')
        p'))))

(defn leave
  "Remove participant from room's routing table. The participant's spin
   continues running in the background (no per-spin cancel in spindel
   today); messages addressed to it after leaving are dropped."
  [room participant-id]
  (swap! (:participants room) dissoc participant-id)
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
   The asker is a transient stub (id + mailbox only) — no spin spawned."
  [room target-id msg-spec]
  (sp/spin
    (let [asker-id  (keyword (str "ask-" (random-uuid)))
          asker-mbx (binding [ec/*execution-context* (:ctx room)]
                      (sync/create-mailbox (:ctx room)))]
      (swap! (:participants room) assoc asker-id
             {:id asker-id :inbox asker-mbx})
      (post! room (message asker-id target-id (:content msg-spec) nil nil))
      (let [reply (sp/await asker-mbx)]
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
  "Create a sibling room with cloned participants. The execution context is
   SHARED with the parent — only the room atoms (participants, log) are
   isolated. Each participant with a :factory is re-created (with its
   current internal state captured) and joined as a fresh participant in
   the fork, with its own mailbox routed only by the fork's :participants.

   Sharing the ctx keeps awaits/deferreds/mailboxes coherent across the
   fork boundary, which is what allows simulate-reply and imagine-conversation
   to compose with the rest of the algebra. Substrate-level forking
   (datahike branches, git worktrees, SCI fork) is deferred to spindel's
   `:isolation :fork` extension (Open Q #11 of discourse-model.md)."
  [room]
  (let [new-id     (keyword (str (name (:id room))
                                 "-fork-" (subs (str (random-uuid)) 0 8)))
        parent-log @(:log room)
        new-room   (->Room new-id (atom {}) (atom parent-log) (:ctx room)
                           (count parent-log))]
    (doseq [[_id p] @(:participants room)]
      (when-let [fac (:factory p)]
        (join new-room (fac (:ctx room)))))
    new-room))

(defn discard
  "Discard a fork: deregister its participants. Their spins continue running
   on the shared context, but no further messages are routed to them — they
   block on their mailboxes indefinitely (harmless; cleaned up when the
   shared context eventually stops)."
  [fork]
  (reset! (:participants fork) {})
  fork)

(defn merge-room
  "Merge fork's NEW log entries (those added after the fork point) into
   parent's log, then deregister fork's participants. Assumes parent's log
   was not modified between fork and merge (the standard ToM/what-if
   pattern)."
  [parent fork]
  (let [fork-log  @(:log fork)
        forked-at (or (:forked-at-len fork) 0)
        new-entries (when (> (count fork-log) forked-at)
                      (subvec fork-log forked-at))]
    (when (seq new-entries)
      (swap! (:log parent) into new-entries)))
  (reset! (:participants fork) {})
  parent)

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
          log   @(:log fork)]
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
          log     @(:log fork)]
      (discard fork)
      {:outcome outcome :imagined-log log})))
