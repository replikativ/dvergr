(ns dvergr.runtime.bus
  "Opinionated pub/sub programming model over spindel.pubsub.

   A Bus is a routing substrate where messages are posted as a stream and
   subscribers tap by topic. Two routing dimensions are supported per Bus:

     [:to   <participant-id>]  — direct routing to a participant
     [:type <tag>]             — capability routing by message tag

   Both dimensions are pubs over one upstream mult, so each message
   reaches every matching subscription.

   LOG-FIRST FAN-OUT (durable-cursor model): `post!` (1) durably
   persists the message when the bus carries a `:durable-append!` hook
   (rooms with a store — durability BEFORE visibility), (2) appends it
   to the bus log — the fan-out source of truth, so log order == post
   order — and (3) rings a doorbell. A SUPERVISED pump delivers log
   entries into the mult and advances a cursor AFTER each handoff.
   Losing the pump loses promptness, never data: the supervisor
   restarts it and it resumes from the cursor, re-delivering at most
   the one in-flight entry (participants dedup by message :id, so the
   observed contract is effectively-once). This replaces the previous
   ephemeral-first pipeline, whose losses were total and whose pump
   was only recoverable by the out-of-band watchdog healing.

   The opinionated layer is `default-buffers` — a map from tag namespace
   (the `namespace` of `:type`) to a 0-arg buffer-builder. The defaults
   express the discourse programming model:

     :message    → fixed-buffer 64        (first-class content; generous backpressure)
     :directive  → fixed-buffer 16        (imperatives; serial; never lose)
     :escalation → fixed-buffer ##Inf-ish (must be answered or explicitly time out)
     :partial    → fixed-buffer 256       (LLM tokens / stream chunks are discrete
                                            data — losing one loses information.
                                            UI consumers wanting \"current
                                            accumulated state\" should override
                                            with sliding-buffer 1 themselves.)
     :tick       → sliding-buffer 1       (cadence; current pulse only — latest
                                            tick is the meaningful snapshot)
     :source     → sliding-buffer 8       (external readings; recent N tunable)
     :telemetry  → sliding-buffer 32      (observation events — turn-complete,
                                            tool-called, etc. UIs want recent
                                            activity, not full backlog. Loggers
                                            wanting every event override.)

   These are not law — `(subscribe! bus topic buf-override)` lets a caller
   pick any policy.

   Buses are spindel-execution-context-bound. Open the bus's ctx with
   `(binding [ec/*execution-context* (:ctx bus)] ...)` when posting from
   threads outside a spin."
  (:refer-clojure :exclude [await])
  (:require [is.simm.partial-cps.sequence :as aseq]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ectx]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.spin.sync :as sync]
            [org.replikativ.spindel.effects.await :refer [await]]
            [org.replikativ.spindel.pubsub.mult :as mult]
            [org.replikativ.spindel.pubsub.pub :as pub]
            [org.replikativ.spindel.pubsub.buffer :as buf]
            [org.replikativ.spindel.spin.supervisor :as supervisor]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; Default Buffer Policy
;; ============================================================================

(def ^:dynamic *default-buffers*
  "Map from tag namespace (the `namespace` of a message's `:type`) to a
   zero-arg fn that constructs the default buffer for that namespace.

   Functions, not buffer values, because each subscription needs its own
   buffer. Override per call via `(subscribe! bus topic buf)` or app-wide
   via `(alter-var-root #'*default-buffers* assoc ...)`."
  {"message"    #(buf/fixed-buffer 64)
   "directive"  #(buf/fixed-buffer 16)
   "escalation" #(buf/fixed-buffer Long/MAX_VALUE)
   "partial"    #(buf/fixed-buffer 256)
   "tick"       #(buf/sliding-buffer 1)
   "source"     #(buf/sliding-buffer 8)
   "telemetry"  #(buf/sliding-buffer 32)})

(defn- buf-for-topic
  "Resolve a buffer for `topic`.

   Topic forms:
     [:to   <id>]    → :message policy
     [:type <tag>]   → lookup (namespace tag); fall back to (name tag) for
                       unqualified keywords (e.g. :tick); else :message"
  [topic]
  (let [[dim value] topic
        ns-key      (cond
                      (and (= :type dim) (keyword? value))
                      (or (namespace value) (name value))

                      (= :to dim)
                      "message"

                      :else
                      "message")
        builder     (get *default-buffers* ns-key
                         (get *default-buffers* "message"))]
    (builder)))

;; ============================================================================
;; Bus
;; ============================================================================

(defrecord Bus
           [;; spindel execution context
            ctx
   ;; mailbox feeding the mult — written ONLY by the fan-out pump
            source-mbox
   ;; mult fanning out to the routing pubs + relay
            source-mult
   ;; pub keyed by :to (direct-to-participant routing)
            to-pub
   ;; pub keyed by :type (capability routing)
            type-pub
   ;; atom of {:entries [msg…] :cursor n} — the LOG-FIRST fan-out model:
   ;; post! appends here (upstream of delivery, so log order == post
   ;; order and a message is on record before any consumer sees it);
   ;; the pump delivers entries[cursor..] into the mult and advances.
   ;; One atom for both so seed/append-as-history compose race-free
   ;; with live posts (see append-log!).
            log
   ;; mailbox used as a data-free doorbell: post! rings it, the pump
   ;; parks on it when the log is drained
            hint-mbx
   ;; optional (fn [msg]) called by post! BEFORE the message becomes
   ;; visible — the durability-first hook (rooms pass a store append).
   ;; A throw here fails the post loudly; nothing is half-delivered.
            durable-append!
   ;; the supervised fan-out pump (spin) — kept for introspection
            pump])

(def ^:private history-key
  "Metadata key marking log entries absorbed as HISTORY (fork seeding /
   merge-as-history) — on record, never delivered to live handlers.
   Metadata, not an entry field, so log readers see unpolluted messages."
  ::history)

(defn- spawn-fanout-pump!
  "Spawn the supervised fan-out pump: delivers log entries[cursor..]
   into `source-mbox` (feeding the existing mult topology) and advances
   the cursor AFTER each handoff; parks on `hint-mbx` when drained.

   Crash-only by construction: the cursor lives in the log-state atom,
   so a pump that dies mid-stream is restarted by its supervisor and
   resumes where it left off. The restart re-delivers at most the one
   entry whose handoff was in flight (at-least-once); participants
   dedup by message :id (see discourse/participant-spin), making the
   observed contract effectively-once. Entries marked as history
   (fork seeding / merge absorption) advance the cursor without
   delivery.

   The cursor advance uses a max-guard so it composes with seed-log!'s
   cursor reset (which only ever moves it forward past history)."
  [ctx log-state hint-mbx source-mbox]
  (binding [ec/*execution-context* ctx]
    (let [pump-fn
          (fn []
            (spin
             (loop []
               (let [{:keys [entries cursor]} @log-state]
                 (if (< cursor (count entries))
                   (let [e (nth entries cursor)]
                     (when-not (history-key (meta e))
                       (sync/post! source-mbox e))
                     (swap! log-state update :cursor
                            (fn [c] (max c (inc cursor))))
                     (recur))
                   (do (await hint-mbx) ; doorbell; content ignored
                       (recur)))))))
          sup (supervisor/supervisor
               [{:id :fanout-pump :start pump-fn}]
               {:strategy :one-for-one
                :max-restarts 5
                :window-ms 60000
                :on-fatal (fn [e]
                            (tel/log! {:level :error :id ::pump-fatal
                                       :msg "bus fan-out pump exceeded its restart budget"
                                       :data {:error (str e)}}))})]
      (sync/spawn! sup)
      sup)))

(declare post!)

(defn- spawn-relay-drain!
  "Spawn a spin that taps `source-mult` and re-posts every message to
   `target-bus`, with `relay-tag` merged in. Used so a per-room bus can
   mirror its traffic up to a daemon-wide peer-bus.

   The relayed message gets two added fields by default:
     :dvergr/origin   — the room id (or whatever the caller set in
                         relay-tag's `:room`)
     :dvergr/scope    — `:room` for normal rooms, `:fork` for forks,
                         or whatever the caller set"
  [ctx source-mult target-bus relay-tag]
  (binding [ec/*execution-context* ctx]
    (let [relay-tap (mult/tap source-mult (buf/fixed-buffer 1024))]
      (sync/spawn!
       (spin
        (loop [s relay-tap]
          (when-let [r (await (aseq/anext s))]
            (let [[msg rest-s] r
                    ;; The relay-tag is a plain map. We don't overwrite
                    ;; keys the original message already has — the
                    ;; origin's view of itself wins.
                  tagged    (merge relay-tag msg)]
              ;; Through the PUBLIC post! — under the log-first model the
              ;; target's log is written at post time (there is no
              ;; delivery-side log tap anymore), so poking its
              ;; source-mbox directly would deliver without recording.
              (post! target-bus tagged)
              (recur rest-s)))))))))

(defn create-bus
  "Construct a Bus.

   LOG-FIRST fan-out (the durable-cursor model): `post!` appends to the
   bus log (and, for rooms with a store, durably persists FIRST via
   `:durable-append!`), then rings a doorbell; a supervised pump
   delivers log entries into the mult topology and advances a cursor.
   Losing the pump loses promptness, never data — the supervisor
   restarts it and it resumes from the cursor.

   Options:
     :ctx             — existing execution context; default: a fresh one
     :durable-append! — optional (fn [msg]); called by post! BEFORE the
                        message becomes visible anywhere. Rooms pass a
                        store append here (idempotent by :id at the
                        store layer). A throw fails the post loudly.
     :relay-to        — another Bus to mirror every message into
                        (typically the daemon-wide peer-bus). Messages
                        are re-posted verbatim with `:relay-tag` merged
                        in *underneath* (the original's fields win).
     :relay-tag       — extras to merge into each relayed message —
                        typically `{:dvergr/origin <room-id>
                        :dvergr/scope :room}` or `:fork`. Required when
                        `:relay-to` is set.
     :log?            — accepted for compatibility; the log is now the
                        fan-out source of truth and always kept."
  ([] (create-bus {}))
  ([{:keys [ctx durable-append! relay-to relay-tag log?] :as _opts}]
   (let [_ log? ;; vestigial — see docstring
         ctx (or ctx (ectx/create-execution-context))]
     (binding [ec/*execution-context* ctx]
       (let [source     (sync/create-mailbox ctx)
             hint-mbx   (sync/create-mailbox ctx)
             m          (mult/mult source)
             to-tap     (mult/tap m (buf/fixed-buffer 256))
             type-tap   (mult/tap m (buf/fixed-buffer 256))
             to-pub-v   (pub/pub to-tap :to)
             type-pub-v (pub/pub type-tap :type)
             log-state  (atom {:entries [] :cursor 0})]
         (when relay-to
           (spawn-relay-drain! ctx m relay-to (or relay-tag {})))
         (let [pump (spawn-fanout-pump! ctx log-state hint-mbx source)]
           (->Bus ctx source m to-pub-v type-pub-v
                  log-state hint-mbx durable-append! pump)))))))

;; ============================================================================
;; Posting
;; ============================================================================

(defn post!
  "Enqueue `msg` onto the bus. Safe from any thread. Returns nil.

   A well-formed message has at least `:to` (direct routing) or
   `:type` (capability routing) — typically both. The bus does not
   validate shape; conventions are an application concern.

   Every message is stamped with an `:id` (random-uuid) if it lacks one — done
   HERE, before the mult fans it out, so a message reaching a participant via
   several matching subscriptions carries the SAME id on each copy. That is what
   lets `dvergr.discourse/participant-spin` dedup and deliver each message once
   (a broadcast that also carries a subscribed `:type` matches both `[:to nil]`
   and `[:type …]`)."
  [bus msg]
  (let [msg' (cond-> msg
               (and (map? msg) (nil? (:id msg))) (assoc :id (random-uuid)))]
    ;; 1. Durability FIRST. For a room with a store this persists the
    ;;    message before it is visible anywhere — a store failure fails
    ;;    the post loudly and nothing is half-delivered (this inverts
    ;;    the old persistence-listener model, where a store failure
    ;;    silently lost durability while the live message flowed).
    (let [durability (when-let [append! (:durable-append! bus)]
                       (append! msg'))]
      (when (= :failed durability)
        (throw (ex-info "Durable bus append failed"
                        {:type :bus/durable-append-failed
                         :message-id (:id msg')})))
      ;; A duplicate immutable envelope was already made visible by the call
      ;; that won persistence. Suppress every repeated live effect as well as
      ;; the durable duplicate. Nil preserves compatibility with custom append
      ;; hooks that predate insertion-status returns.
      (when-not (= :duplicate durability)
        ;; 2. The log is the fan-out source of truth. Appending here (not
        ;;    in a delivery tap) means log order == post order, and a
        ;;    message is on record before any consumer runs.
        (swap! (:log bus) update :entries conj msg')
        ;; 3. Doorbell for the pump.
        (binding [ec/*execution-context* (:ctx bus)]
          (sync/post! (:hint-mbx bus) ::hint)))))
  nil)

(defn post-many!
  "Post a sequence of messages in order."
  [bus msgs]
  (doseq [m msgs] (post! bus m))
  nil)

;; ============================================================================
;; Subscribing
;; ============================================================================

(defrecord Subscription
           [bus topic aseq buffer])

;; A Bus's mult holds each Subscription's tap, and a Subscription holds its Bus —
;; so the default record printer recurses forever and StackOverflows when one is
;; auto-printed at the REPL (e.g. the result of `subscribe!`). Print compact,
;; acyclic summaries instead.
(defmethod print-method Bus [^Bus b ^java.io.Writer w]
  (let [{:keys [entries cursor]} @(:log b)]
    (.write w (str "#Bus{:log " (count entries) " :cursor " cursor "}"))))

(defmethod print-method Subscription [^Subscription s ^java.io.Writer w]
  (.write w (str "#Subscription{:topic " (pr-str (:topic s)) "}")))

(defn subscribe!
  "Subscribe to `topic`. Returns a `Subscription` whose `:aseq` is a
   `PAsyncSeq` of matching messages.

   Topic forms:
     [:to   <participant-id>]   — direct messages addressed to <id>
     [:type <tag>]              — messages whose :type equals <tag>

   Buffer defaults to `*default-buffers*` lookup by topic's tag namespace.
   Override via the 3-arg form."
  ([bus topic]
   (subscribe! bus topic (buf-for-topic topic)))
  ([bus topic buffer]
   (let [[dim value] topic
         aseq        (binding [ec/*execution-context* (:ctx bus)]
                       (case dim
                         :to   (pub/sub (:to-pub bus)   value buffer)
                         :type (pub/sub (:type-pub bus) value buffer)
                         (throw (ex-info "Unknown topic dimension"
                                         {:topic topic :supported #{:to :type}}))))]
     (tel/log! {:level :debug :id ::subscribe
                :data {:topic topic}})
     (->Subscription bus topic aseq buffer))))

(defn unsubscribe!
  "Remove `sub`'s subscription from its bus."
  [{:keys [bus topic aseq] :as _sub}]
  (let [[dim value] topic]
    (binding [ec/*execution-context* (:ctx bus)]
      (case dim
        :to   (pub/unsub (:to-pub bus)   value aseq)
        :type (pub/unsub (:type-pub bus) value aseq))))
  nil)

;; ============================================================================
;; Inspection
;; ============================================================================

(defn log
  "Return the bus's full message log (vector)."
  [bus]
  (:entries @(:log bus)))

(defn log-cursor
  "The fan-out cursor: entries below this index have been handed to the
   delivery topology (or absorbed as history). Introspection/tests."
  [bus]
  (:cursor @(:log bus)))

(defn clear-log!
  "Reset the bus's log to empty (cursor included)."
  [bus]
  (reset! (:log bus) {:entries [] :cursor 0})
  nil)

(defn seed-log!
  "Seed the bus's log with a prior history vector (e.g. a fork seeding the
   parent's log so log-based consumers see a continuous record). Replaces the
   current log; the cursor starts past the history so none of it is delivered
   to live handlers. This is the public op for the fork seam — callers should
   not touch the `:log` atom directly."
  [bus history]
  (let [h (vec history)]
    (reset! (:log bus) {:entries h :cursor (count h)}))
  nil)

(defn append-log!
  "Append entries to the bus's log without re-posting them (merge-as-history:
   the parent absorbs a fork's exchange into its record without re-firing live
   handlers). The carry for a branchless (`:isolation :none`/ephemeral) fork on
   merge — a `:ctx` fork merges its datahike branch natively instead.

   Entries are marked as history via METADATA (invisible to log readers);
   the pump advances past them without delivering. A single swap!, so this
   composes race-free with concurrent live post!s — no cursor arithmetic
   can skip a live entry."
  [bus entries]
  (swap! (:log bus) update :entries into
         (map (fn [e] (if (instance? clojure.lang.IObj e)
                        (vary-meta e assoc history-key true)
                        e))
              entries))
  ;; Ring the doorbell so a parked pump advances its cursor past the
  ;; absorbed history promptly (no delivery happens for marked entries).
  (binding [ec/*execution-context* (:ctx bus)]
    (sync/post! (:hint-mbx bus) ::hint))
  nil)
