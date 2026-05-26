(ns dvergr.peer-bus
  "Daemon-wide pub/sub bus — one per JVM, lives on the base execution
   context. Every per-room bus (and every fork bus) is created with
   `:relay-to <peer-bus>` so messages mirror up automatically, tagged
   with `:dvergr/origin <room-id>` + `:dvergr/scope :room | :fork`.

   Cross-cutting concerns subscribe to the peer-bus instead of each
   room individually:

     - TUI dashboard's `pending-reviews` badge
     - audit log / governance
     - oversight agents that watch many rooms

   Control-plane events that aren't tied to a specific room
   (`:dvergr/fork-created`, `:dvergr/merge-proposed`,
   `:dvergr/fork-merged`, `:dvergr/fork-discarded`) post **directly**
   here, never through a room's bus — so room conversation logs stay
   pure conversation.

   Matches the replikativ/kabel peer-bus idiom: a `:type`-keyed
   `bus-in`/`bus-out` pair carrying every event on the machine,
   subscribers filter."
  (:require [dvergr.bus :as bus]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ectx]))

;; ============================================================================
;; State location
;; ============================================================================

(def ^:private peer-bus-path
  "Slot on the base execution context where the peer-bus lives. A child
   ctx (forked) inherits this slot via CoW so the same peer-bus is
   reachable from anywhere in the daemon."
  [:dvergr/peer-bus])

;; ============================================================================
;; Construction + lookup
;; ============================================================================

(defn create!
  "Create a peer-bus on `base-ctx` and stash it at `[:dvergr/peer-bus]`.
   Returns the bus. Called by daemon init; idempotent — re-creating
   would orphan existing subscribers, so we no-op if one's already
   there."
  [base-ctx]
  (binding [ec/*execution-context* base-ctx]
    (or (ec/get-state peer-bus-path)
        (let [b (bus/create-bus {:ctx base-ctx})]
          (ec/swap-state! peer-bus-path (fn [x] (or x b)))
          (ec/get-state peer-bus-path)))))

(defn current
  "Return the peer-bus visible from the current execution context, or
   nil if none registered. Resolves through ctx fork inheritance: a
   forked ctx sees its parent's peer-bus."
  []
  (ec/get-state peer-bus-path))

;; ============================================================================
;; Post / subscribe
;; ============================================================================

(defn post!
  "Post a control-plane event to the peer-bus. `msg` should at minimum
   carry `:type` (so :type-pub subscribers can route it). Adds nothing
   else — callers attach `:dvergr/origin`, `:dvergr/scope`, etc.
   themselves as needed.

   No-op (logs a warning via the bus layer's own telemetry) when no
   peer-bus is registered, so library callers don't have to guard."
  [msg]
  (when-let [b (current)]
    (bus/post! b msg))
  nil)

(defn subscribe!
  "Subscribe to peer-bus events. Topic forms are the same as
   `dvergr.bus/subscribe!` — `[:to id]` or `[:type tag]`. Returns a
   `dvergr.bus.Subscription` (`:aseq` is a `PAsyncSeq` of events).

   Throws if no peer-bus is registered — there's no sensible
   fallback for a subscriber."
  ([topic]
   (if-let [b (current)]
     (bus/subscribe! b topic)
     (throw (ex-info "dvergr.peer-bus: no peer-bus registered in current ctx"
                     {:topic topic}))))
  ([topic buffer]
   (if-let [b (current)]
     (bus/subscribe! b topic buffer)
     (throw (ex-info "dvergr.peer-bus: no peer-bus registered in current ctx"
                     {:topic topic})))))

(defn log
  "Full peer-bus log (vector). nil if no peer-bus registered."
  []
  (some-> (current) bus/log))
