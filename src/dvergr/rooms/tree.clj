(ns dvergr.rooms.tree
  "Signal-backed view of the room registry.

   With Option E in place, `dvergr.room.registry` is the single source
   of truth for which Rooms exist (persistent + forks). This namespace
   exposes that as a Spindel signal so the TUI render-spin and the web
   dashboard can subscribe.

   Two refresh paths feed the signal:

   1. Peer-bus subscription on fork lifecycle events
      (`:dvergr/fork-created`, `:fork-merged`, `:fork-discarded`).
      The fork-room/merge-room/discard fns already update the
      registry themselves; this subscription just bumps the signal
      so UI subscribers see the change immediately.

   2. A low-frequency poll (default 1000 ms) catches registry
      mutations that don't emit peer-bus events (room creates,
      agent joins/leaves).

   `start!` returns a controller {:stop! ...}; the signal is exposed
   via `(tree-signal)` so callers can `track` it from inside a
   render-spin."
  (:refer-clojure :exclude [await])
  (:require [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.signal :as sig]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.effects.await :refer [await]]
            [org.replikativ.spindel.spin.sync :as sync]
            [is.simm.partial-cps.sequence :as aseq]
            [dvergr.runtime.peer-bus :as peer-bus]
            [dvergr.actors :as actors]
            [dvergr.room.registry :as rreg]
            [taoensso.telemere :as tel]))

;; =============================================================================
;; Signal slot
;; =============================================================================

(def ^:private tree-signal-path
  [:dvergr/rooms-tree-signal])

(defn ^:no-doc reset-signal-slot!
  "Test helper — clear the cached tree signal so the next start! builds
   a fresh one. Don't call from production code."
  []
  (when-let [ctx (try (ec/current-execution-context) (catch Exception _ nil))]
    (binding [ec/*execution-context* ctx]
      (ec/swap-state! tree-signal-path (constantly nil)))))

(defn tree-signal
  "Return the rooms-tree SignalRef on the current execution context.
   nil if start! hasn't been called yet."
  []
  (ec/get-state tree-signal-path))

(defn current-tree
  "Convenience: deref the tree signal (requires ctx bound). nil if no
   tree has been built yet."
  []
  (when-let [s (tree-signal)] @s))

;; =============================================================================
;; Tree projection from the registry
;; =============================================================================

(defn- normalize-room
  "Project a discourse Room into a UI-friendly node map. The full Room
   carries an atom, bus, etc. — strip to the data the renderers need."
  [room]
  (let [m @(:meta room)]
    (cond-> {:kind       (if (:forked-from m) :fork :room)
             :id         (:id room)
             :slug       (:slug room)
             :title      (:title room)
             :parent-id  (:parent-id room)
             :agent-ids  (set (:agent-ids m))
             :forked-from (:forked-from m)
             :children   []}
      (:type m)              (assoc :type (:type m))
      (:telegram-chat-id m)  (assoc :telegram-chat-id (:telegram-chat-id m))
      (:chat-id m)           (assoc :chat-id (:chat-id m)))))

(defn- attach-children
  "Given a flat seq of node maps, return the roots with each node's
   :children populated by recursive resolution. Stable order by slug."
  [nodes]
  (let [by-parent (group-by :parent-id nodes)
        sort-fn   (fn [coll] (vec (sort-by :slug coll)))
        build     (fn build [parent-id]
                    (sort-fn
                     (mapv (fn [node]
                             (assoc node :children (build (:id node))))
                           (get by-parent parent-id []))))]
    (build nil)))

(defn- compute-tree
  "Read the room registry + agent registry, return a fresh tree value.
   Pure — no signal write.

   The internal discourse ROOT room (stashed at [:dvergr/discourse-root]) is
   system plumbing — it holds the :_system reply-drain and is the base every
   agent joins for dispatch, not a place you'd chat. Filter it out so the tree
   shows real rooms (Boardroom, DMs, project rooms, forks), never the root."
  []
  (let [root-id (some-> (ec/get-state [:dvergr/discourse-root]) :id)
        nodes  (into []
                     (comp (remove #(= root-id (:id %)))
                           (map normalize-room))
                     (rreg/list-rooms))
        roots  (attach-children nodes)
        agents (actors/online-actors)]
    {:roots    roots
     :agents   agents
     :built-at (java.util.Date.)}))

(defn- refresh!
  "Recompute the tree and swap into the signal. Suppresses no-op
   writes (= against current snapshot) so subscribers don't see
   redundant ticks."
  [signal]
  (let [next-value (compute-tree)
        prev       (try @signal (catch Throwable _ nil))]
    (when (not= (dissoc next-value :built-at)
                (dissoc prev :built-at))
      (reset! signal next-value))))

;; =============================================================================
;; Peer-bus subscription
;; =============================================================================

(defn- fork-event?
  [msg]
  (contains? #{:dvergr/fork-created
               :dvergr/fork-merged
               :dvergr/fork-discarded}
             (:type msg)))

(defn- spawn-bus-consumer!
  "Drain peer-bus fork events and bump the signal on each. Registry
   updates happen inside fork-room/merge-room/discard themselves —
   here we just react."
  [ctx signal]
  (binding [ec/*execution-context* ctx]
    (let [drain (fn [{:keys [aseq]}]
                  (sync/spawn!
                   (spin
                    (loop [s aseq]
                      (when-let [r (await (aseq/anext s))]
                        (let [[msg rest-s] r]
                          (when (fork-event? msg)
                            (refresh! signal))
                          (recur rest-s)))))))]
      (doseq [t [:dvergr/fork-created :dvergr/fork-merged :dvergr/fork-discarded]]
        (drain (peer-bus/subscribe! [:type t]))))))

;; =============================================================================
;; Periodic refresh
;; =============================================================================

(defn- start-poller!
  "Side thread that re-runs refresh! every poll-ms. Catches non-fork
   registry mutations (room creates, agent joins) that don't emit
   peer-bus events."
  [ctx signal running-atom poll-ms]
  (doto (Thread.
         ^Runnable
         (fn []
           (binding [ec/*execution-context* ctx]
             (try
               (while @running-atom
                 (try
                   (refresh! signal)
                   (catch Throwable t
                     (tel/log! {:id    :rooms-tree/poll-failed
                                :data  {:error (.getMessage t)}
                                :level :warn})))
                 (Thread/sleep (long poll-ms)))
               (catch InterruptedException _ nil))))
         "dvergr-rooms-tree-poller")
    (.setDaemon true)
    (.start)))

;; =============================================================================
;; Public API
;; =============================================================================

(defn start!
  "Boot the rooms-tree signal on `ctx`. Idempotent — re-running
   returns a controller wrapping the existing signal without
   re-spawning subscriptions.

   Optional :poll-ms — registry polling cadence (default 1000)."
  [{:keys [ctx poll-ms] :or {poll-ms 1000}}]
  (binding [ec/*execution-context* ctx]
    (if-let [existing (tree-signal)]
      {:running     (atom true)
       :stop!       (fn [] nil)
       :tree-signal existing
       :already?    true}
      (let [signal  (sig/->SignalRef ::tree {:roots [] :agents []
                                             :built-at (java.util.Date.)})
            _       (sig/ensure-signal-initialized! signal)
            _       (ec/swap-state! tree-signal-path (constantly signal))
            running (atom true)]
        (refresh! signal)
        (spawn-bus-consumer! ctx signal)
        (start-poller! ctx signal running poll-ms)
        {:running     running
         :stop!       (fn []
                        (reset! running false)
                        (binding [ec/*execution-context* ctx]
                          (ec/swap-state! tree-signal-path (constantly nil))))
         :tree-signal signal}))))

(defn force-refresh!
  "Force a synchronous refresh from the registry. Useful at the REPL
   after manual operations that don't emit peer-bus events."
  [{:keys [tree-signal] :as _controller}]
  (refresh! tree-signal))
