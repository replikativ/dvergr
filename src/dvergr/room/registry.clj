(ns dvergr.room.registry
  "Slug → Room registry. The single source of truth for which Rooms
   exist in a daemon: persistent (Datahike-backed) and ephemeral
   (forks) alike.

   The registry is **Tier-1 shared state** (`dvergr.runtime.ctx`): it lives at
   `[:dvergr/rooms]` on the ROOT ctx and every read/write goes through the
   `shared-*` ops, so it's authoritative + visible to every frontend regardless
   of which room/fork ctx is bound when a room is (un)registered. (A room runs on
   its own ctx for ISOLATION state — its yggdrasil composite, proposals — but its
   identity in the registry is shared.)

   The TUI tree-of-rooms reads from here instead of running Datahike
   queries + a separate fork-map. Peer-bus subscriptions remain the
   source of truth for fork lifecycle events (so the registry can
   react to forks created elsewhere in the daemon)."
  (:require [dvergr.runtime.ctx :as rctx]))

(def ^:private registry-path [:dvergr/rooms])

;; Callbacks run (with the room-id) AFTER a room is unregistered. Lets
;; dependents (e.g. dvergr.agent.room-context) tear down per-room resources
;; without the registry depending on them — every teardown path (room delete,
;; fork discard) funnels through `unregister!`, so one hook covers them all.
(defonce ^:private unregister-hooks (atom {}))

(defn add-unregister-hook!
  "Register `f` (1-arg, takes room-id) to run after any room is unregistered.
   Keyed by `id` so re-registration (ns reload) replaces rather than dupes."
  [id f]
  (swap! unregister-hooks assoc id f)
  nil)

;; Callbacks run (with the Room) AFTER a room is registered — and the ctx is
;; already bound to the registering ctx. Lets dependents (e.g.
;; dvergr.rooms.messages) establish per-room resources EARLY, while the room is
;; quiet, rather than lazily under concurrent load (which loses a spindel
;; mult/pub late-subscriber race). One hook covers every creation path
;; (create-room!, ensure-agent-room!, hydration, forks).
(defonce ^:private register-hooks (atom {}))

(defn add-register-hook!
  "Register `f` (1-arg, takes the Room) to run after any room is registered.
   Keyed by `id` so ns-reload replaces rather than dupes."
  [id f]
  (swap! register-hooks assoc id f)
  nil)

(defn register!
  "Add or replace a Room in the registry, then run register hooks. Returns the
   Room."
  [room]
  (rctx/shared-swap-state! registry-path (fn [m] (assoc (or m {}) (:id room) room)))
  (doseq [f (vals @register-hooks)]
    (try (f room) (catch Throwable _ nil)))
  room)

(defn unregister!
  "Remove a Room from the registry by id, then run unregister hooks."
  [room-id]
  (rctx/shared-swap-state! registry-path (fn [m] (dissoc (or m {}) room-id)))
  (doseq [f (vals @unregister-hooks)]
    (try (f room-id) (catch Throwable _ nil)))
  nil)

(defn lookup
  "Find a Room by id (keyword) or slug (string). nil if not present."
  [id-or-slug]
  (let [m (rctx/shared-get-state registry-path)]
    (cond
      (keyword? id-or-slug) (get m id-or-slug)
      (string? id-or-slug)  (some (fn [[_ r]] (when (= (:slug r) id-or-slug) r)) m)
      :else                 nil)))

(defn list-rooms
  "All rooms in the registry. Optional :where filter as a predicate.

   Examples:
     (list-rooms)
     (list-rooms :where #(nil? (:parent-id %)))     ;; roots only
     (list-rooms :where #(some? (:store %)))        ;; persistent only"
  [& {:keys [where]}]
  (let [m (rctx/shared-get-state registry-path)
        rs (vals (or m {}))]
    (vec (if where (filter where rs) rs))))

(defn children
  "Rooms whose :parent-id matches the given id. Order undefined."
  [parent-id]
  (list-rooms :where #(= parent-id (:parent-id %))))

(defn roots
  "Rooms with no parent (top-level)."
  []
  (list-rooms :where #(nil? (:parent-id %))))

(defn snapshot
  "Return the entire registry map as-is (for UI rendering, debugging)."
  []
  (or (rctx/shared-get-state registry-path) {}))
