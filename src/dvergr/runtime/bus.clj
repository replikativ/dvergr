(ns dvergr.runtime.bus
  "Opinionated pub/sub programming model over spindel.pubsub.

   A Bus is a routing substrate where messages are posted as a stream and
   subscribers tap by topic. Two routing dimensions are supported per Bus:

     [:to   <participant-id>]  — direct routing to a participant
     [:type <tag>]             — capability routing by message tag

   Both dimensions are pubs over the same source mailbox via an upstream
   mult, so each message reaches every matching subscription.

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
   ;; mailbox: every post! lands here; PAsyncSeq source for the mult
            source-mbox
   ;; mult fanning out to the routing pubs + log
            source-mult
   ;; pub keyed by :to (direct-to-participant routing)
            to-pub
   ;; pub keyed by :type (capability routing)
            type-pub
   ;; atom vector — history, updated by a tap on source-mult
            log])

(defn- spawn-log-drain!
  "Spawn a spin that consumes every message from the source-mult and
   appends it to the log atom."
  [ctx source-mult log-atom]
  (binding [ec/*execution-context* ctx]
    (let [log-tap (mult/tap source-mult (buf/fixed-buffer 1024))]
      (sync/spawn!
       (spin
        (loop [s log-tap]
          (when-let [r (await (aseq/anext s))]
            (let [[msg rest-s] r]
              (swap! log-atom conj msg)
              (recur rest-s)))))))))

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
              (binding [ec/*execution-context* (:ctx target-bus)]
                (sync/post! (:source-mbox target-bus) tagged))
              (recur rest-s)))))))))

(defn create-bus
  "Construct a Bus.

   Options:
     :ctx        — existing execution context; default: a fresh one
     :log?       — keep a vector history of every message (default true)
     :relay-to   — another Bus to mirror every message into (typically
                    the daemon-wide peer-bus). Messages are re-posted
                    verbatim with `:relay-tag` merged in *underneath*
                    (so the original message's own fields win).
     :relay-tag  — extras to merge into each relayed message — typically
                    `{:dvergr/origin <room-id> :dvergr/scope :room}`
                    or `:fork`. Required when `:relay-to` is set."
  ([] (create-bus {}))
  ([{:keys [ctx log? relay-to relay-tag] :or {log? true}}]
   (let [ctx (or ctx (ectx/create-execution-context))]
     (binding [ec/*execution-context* ctx]
       (let [source     (sync/create-mailbox ctx)
             m          (mult/mult source)
             to-tap     (mult/tap m (buf/fixed-buffer 256))
             type-tap   (mult/tap m (buf/fixed-buffer 256))
             to-pub-v   (pub/pub to-tap :to)
             type-pub-v (pub/pub type-tap :type)
             log-atom   (atom [])]
         (when log?
           (spawn-log-drain! ctx m log-atom))
         (when relay-to
           (spawn-relay-drain! ctx m relay-to (or relay-tag {})))
         (->Bus ctx source m to-pub-v type-pub-v log-atom))))))

;; ============================================================================
;; Posting
;; ============================================================================

(defn post!
  "Enqueue `msg` onto the bus. Safe from any thread. Returns nil.

   A well-formed message has at least `:to` (direct routing) or
   `:type` (capability routing) — typically both. The bus does not
   validate shape; conventions are an application concern."
  [bus msg]
  (binding [ec/*execution-context* (:ctx bus)]
    (sync/post! (:source-mbox bus) msg))
  nil)

(defn post-many!
  "Post a sequence of messages in order under one ctx binding."
  [bus msgs]
  (binding [ec/*execution-context* (:ctx bus)]
    (doseq [m msgs] (sync/post! (:source-mbox bus) m)))
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
  (.write w (str "#Bus{:log " (count @(:log b)) "}")))

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
  @(:log bus))

(defn clear-log!
  "Reset the bus's log to empty."
  [bus]
  (reset! (:log bus) [])
  nil)

(defn seed-log!
  "Seed the bus's log with a prior history vector (e.g. a fork seeding the
   parent's log so log-based consumers see a continuous record). Replaces the
   current log. This is the public op for the fork seam — callers should not
   touch the `:log` atom directly."
  [bus history]
  (reset! (:log bus) (vec history))
  nil)

(defn append-log!
  "Append entries to the bus's log without re-posting them (merge-as-history:
   the parent absorbs a fork's exchange into its record without re-firing live
   handlers). The carry for a branchless (`:isolation :none`/ephemeral) fork on
   merge — a `:ctx` fork merges its datahike branch natively instead."
  [bus entries]
  (swap! (:log bus) into entries)
  nil)
