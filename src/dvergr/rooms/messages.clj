(ns dvergr.rooms.messages
  "Room-level reactive message view: ONE spindel signal per room holding the
   room's transcript (render shape), seeded once from the store and kept current
   by a fold over the room bus.

   This is the shared projection every frontend (TUI, web, simmis) `track`s —
   no per-client bus subscription, no per-render store re-read, no race. It is
   distinct from the per-[room,agent] working ctxs in `dvergr.agent.room-context`
   (those carry an agent's POV roles + private tool scratch); this is the room's
   conversation as everyone sees it (global roles, including tool-activity rows).

   Same consistency story as everything else here: the bus is the per-room
   totally-ordered log; the store (disk) and this signal (memory) are two folds
   of it, consistent by construction, deduped by message id."
  (:require [dvergr.discourse :as d]
            [dvergr.runtime.bus :as bus]
            [dvergr.runtime.ctx :as rctx]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store :as store]
            [org.replikativ.spindel.signal :as sig]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.engine.core :as ec]
            [is.simm.partial-cps.sequence :refer [anext]]))

;; room-id → {:signal SignalRef :seen java.util.Set :sub Subscription}
(defonce ^:private room-signals (atom {}))

;; --- diagnostics: a ring buffer of fold lifecycle events, inspectable from
;; the REPL via `(dvergr.rooms.messages/recent-debug)`. Tells us, for a given
;; room, whether the fold spin SEEDED, RECEIVED live messages, hit an APPEND
;; error, or EXITED (died) — i.e. whether the bug is "spin dies" or "never
;; receives". Cheap; capped at 300 entries.
(defonce debug-events (atom []))

(defn- dbg! [room-id ev data]
  (swap! debug-events
         (fn [v] (conj (if (> (count v) 300) (subvec v 1) v)
                       [(System/currentTimeMillis) room-id ev data]))))

(defn recent-debug
  "Recent fold events, optionally filtered by room-id."
  ([] @debug-events)
  ([room-id] (filterv #(= room-id (nth % 1)) @debug-events)))

;; The room message signal is Tier-1 shared state (dvergr.runtime.ctx): a UI
;; projection read by every frontend in the ROOT ctx. It is created at
;; `(rctx/root-ctx (:ctx room))` so a fork's own ctx (a child of root) doesn't
;; hide it — spindel signal reads fall through child→parent, never parent→child.

(defn- msg->view
  "Normalize a message to the render shape the views consume. `d/messages`
   already returns this shape (the store normalizes); a raw bus Message carries
   :role/:tool-uses in :metadata — lift them so live + seeded rows match.
   Role uses the SAME `store/infer-role` the persisted store uses, so a live
   agent reply (keyword :from, no explicit role) is `:assistant` just like its
   reloaded-from-disk twin — no live/restart drift."
  [m]
  {:id        (:id m)
   :from      (:from m)
   :content   (:content m)
   :ts        (:ts m)
   :role      (store/infer-role m)
   :tool-uses (or (:tool-uses m) (:tool-uses (:metadata m)))
   :reasoning (or (:reasoning m) (:reasoning (:metadata m)) (:message/reasoning m))})

(defn messages-signal
  "Get-or-create the room's message signal — a reactive vector of the room's
   transcript (render shape). Created lazily on first use, one per room.
   Subscribe-before-seed + id-dedup closes the seed/live race."
  [room]
  (or (:signal (get @room-signals (:id room)))
      ;; Create the signal + fold in the ROOT ctx (not (:ctx room)) so a fork's
      ;; signal is visible to the root-ctx TUI/web readers. The fold still
      ;; subscribes to THIS room's own bus, so a fork's signal folds the fork's
      ;; messages — only its home ctx moves to the root.
      (binding [ec/*execution-context* (rctx/root-ctx (:ctx room))]
        (let [signal (sig/signal [])
              seen   (java.util.Collections/synchronizedSet (java.util.HashSet.))
              append (fn [m]
                       (when (.add ^java.util.Set seen (:id m))
                         (swap! signal conj (msg->view m))))]
          ;; Register before the fold/seed so both find the entry.
          (swap! room-signals assoc (:id room) {:signal signal :seen seen :sub nil})
          ;; Subscribe first (catch live messages during seeding)…
          (let [sub (bus/subscribe! (:bus room) [:type :user/message])]
            (swap! room-signals assoc-in [(:id room) :sub] sub)
            (dbg! (:id room) :spawn nil)
            (sp/spawn!
             (spin
                ;; Error-ISOLATED, like the persistence listener: a throwing
                ;; message must NOT kill the subscription (the old loop had no
                ;; guard — one bad append → dead signal forever). The outer
                ;; catch logs if the await/anext chain itself dies.
              (try
                (loop [s (:aseq sub)]
                  (when-let [r (sp/await (anext s))]
                    (let [[m rest-s] r]
                      (dbg! (:id room) :recv (:id m))
                      (try (append m)
                           (catch Throwable t
                             (dbg! (:id room) :append-error (.getMessage t))))
                      (recur rest-s))))
                (dbg! (:id room) :exit :seq-ended)
                (catch Throwable t
                  (dbg! (:id room) :exit-error (str (.getMessage t))))))))
          ;; …then seed history. A room's conversation is ONE query now:
          ;; `d/messages` reads the room's (for a fork, branched) store under the
          ;; conversation :chat/id, so a fork already returns inherited (pre-fork)
          ;; + its own messages in order. For a fork we drop a `:divider?` marker
          ;; after the inherited prefix (the messages the parent also has) so every
          ;; frontend can render the fork boundary. (doc/unified-fork-conversation.md)
          (let [fork?  (some-> room :meta deref :forked-from)
                parent (when (and fork? (:parent-id room)) (rreg/lookup (:parent-id room)))
                pids   (when parent (set (map :id (d/messages parent {:limit 200}))))
                own    (d/messages room {:limit 200})
                ;; inherited = the leading messages the parent also has (pre-fork
                ;; prefix); the fork's own messages follow. Drop the divider AFTER
                ;; the inherited prefix even if there are no own messages yet (the
                ;; signal is seeded eagerly at fork registration), so live fork
                ;; messages append below it.
                inherited-n (if pids (count (take-while #(pids (:id %)) own)) 0)]
            (doseq [m (take inherited-n own)] (append m))
            (when (and parent (pos? inherited-n))
              (swap! signal conj {:id       (str "::fork-divider/" (:id room))
                                  :divider? true
                                  :content  (str "forked from #" (:slug parent))}))
            (doseq [m (drop inherited-n own)] (append m))
            (dbg! (:id room) :seed (count own)))
          signal))))

(defn refresh!
  "Re-seed a room's message signal from the store — e.g. after a merge collapses
   a fork's branch into the parent, bringing new messages into the conversation.
   Resets the signal value + dedup set to the current `d/messages`, keeping the
   SAME SignalRef so existing trackers (TUI/web mirrors) re-render in place."
  [room]
  (when-let [{:keys [signal seen]} (get @room-signals (:id room))]
    (binding [ec/*execution-context* (rctx/root-ctx (:ctx room))]
      (let [views (mapv msg->view (d/messages room {:limit 200}))]
        (.clear ^java.util.Set seen)
        (doseq [v views] (.add ^java.util.Set seen (:id v)))
        (reset! signal views))))
  nil)

(defn drop-room!
  "Tear down a room's message signal (room delete / fork discard)."
  [room-id]
  (when-let [{:keys [sub]} (get @room-signals room-id)]
    (when sub (try (bus/unsubscribe! sub) (catch Throwable _ nil)))
    (swap! room-signals dissoc room-id))
  nil)

(defn clear-all!
  "Drop every room signal (daemon stop — the cache survives same-process
   restart, so a fresh start must rebuild rather than reuse stale folds)."
  []
  (doseq [rid (keys @room-signals)] (drop-room! rid))
  nil)

;; One hook covers every room teardown path (delete, fork discard).
(rreg/add-unregister-hook! ::drop-room drop-room!)

;; Establish the fold EARLY — at room registration, while the room is quiet —
;; instead of lazily at first display. Subscribing under concurrent message flow
;; loses a spindel mult/pub late-subscriber race (the subscription spin stays
;; alive but never receives; see the :spawn/:seed-without-:recv debug trace).
;; Creating it at registration (alongside the persistence listener, before any
;; traffic) sidesteps that race. Idempotent: messages-signal is get-or-create.
(rreg/add-register-hook! ::eager-signal
                         (fn [room]
                           (try (messages-signal room) (catch Throwable _ nil))))
