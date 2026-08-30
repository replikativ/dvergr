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
(def ^:private fork-topology-path [:dvergr/fork-topology])
(defonce ^:private lifecycle-lock (Object.))
(defonce ^:private transitions (atom {}))

(defn- transition-key [room-id]
  [(:fork-id (rctx/current-root)) room-id])

(defn- reserve-transition! [room-id operation]
  (let [key (transition-key room-id)
        token (random-uuid)]
    (when (contains? @transitions key)
      (throw (ex-info "Room registry lifecycle is already in progress"
                      {:type ::lifecycle-in-progress
                       :room-id room-id
                       :operation operation})))
    (swap! transitions assoc key {:token token :operation operation})
    [key token]))

(defn- release-transition! [key token]
  (locking lifecycle-lock
    (when (= token (get-in @transitions [key :token]))
      (swap! transitions dissoc key)))
  nil)

;; Callbacks run (with the room-id) AFTER a room is unregistered. Lets
;; dependents (e.g. dvergr.agent.room-context) tear down per-room resources
;; without the registry depending on them — every teardown path (room delete,
;; fork discard) funnels through `unregister!`, so one hook covers them all.
(defonce ^:private unregister-hooks (atom {}))

;; Hooks that must complete BEFORE registry removal. Unlike observation-only
;; unregister hooks, failures propagate and keep the Room registered so teardown
;; can be retried safely.
(defonce ^:private pre-unregister-hooks (atom {}))

;; Registration fences run before replacing the registry entry. They are for
;; lifecycle owners whose identity must not be reset by an add-or-replace
;; refresh. Unlike observational register hooks, failures propagate and leave
;; the existing registry entry untouched.
(defonce ^:private pre-register-hooks (atom {}))

(defn add-pre-register-hook!
  "Register a Room admission fence. `f` receives the prospective Room before
   registry replacement; failures abort registration."
  [id f]
  (swap! pre-register-hooks assoc id f)
  nil)

(defn add-pre-unregister-hook!
  "Register a Room teardown fence. `f` receives the live Room before removal;
   failures abort unregister and remain visible to the caller."
  [id f]
  (swap! pre-unregister-hooks assoc id f)
  nil)

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
  (let [[key token] (locking lifecycle-lock
                      (reserve-transition! (:id room) :register))]
    (try
      ;; Lifecycle owners reserve/check outside the global monitor. The token
      ;; prevents unregister/replacement from interleaving while hooks run.
      (doseq [f (vals @pre-register-hooks)]
        (f room))
      (locking lifecycle-lock
        (when-not (= token (get-in @transitions [key :token]))
          (throw (ex-info "Room registration reservation was lost"
                          {:type ::lifecycle-reservation-lost
                           :room-id (:id room)})))
        (rctx/shared-swap-state! registry-path
                                 (fn [m] (assoc (or m {}) (:id room) room))))
      (doseq [f (vals @register-hooks)]
        (try (f room) (catch Throwable _ nil)))
      room
      (finally
        (release-transition! key token)))))

(defn unregister!
  "Remove a Room from the registry by id, then run unregister hooks."
  [room-id]
  (let [{:keys [room key token]}
        (locking lifecycle-lock
          (when-let [room (get (rctx/shared-get-state registry-path) room-id)]
            (let [[key token] (reserve-transition! room-id :unregister)]
              {:room room :key key :token token})))]
    (when room
      (try
        ;; Draining user/runtime work must never hold the daemon-global monitor.
        ;; Same-Room lifecycle contenders fail on the reservation; unrelated
        ;; registries continue normally.
        (doseq [f (vals @pre-unregister-hooks)]
          (f room))
        (locking lifecycle-lock
          (when-not (and (= token (get-in @transitions [key :token]))
                         (= (:incarnation room)
                            (:incarnation (get (rctx/shared-get-state registry-path)
                                               room-id))))
            (throw (ex-info "Room unregister reservation no longer owns the incarnation"
                            {:type ::lifecycle-reservation-lost
                             :room-id room-id})))
          (rctx/shared-swap-state! registry-path (fn [m] (dissoc (or m {}) room-id))))
        ;; Keep the reservation through cleanup so a new incarnation cannot be
        ;; registered before old process-local handles have been forgotten.
        (doseq [f (vals @unregister-hooks)]
          (try (f room-id) (catch Throwable _ nil)))
        (finally
          (release-transition! key token)))))
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

(defn track-fork!
  "Record structural ownership of an isolated child world independently of
   transient Room/UI registration. The link survives authority transfer and is
   removed only after the child world has actually settled."
  [child-id parent-id ygg-fork-id]
  (rctx/shared-swap-state!
   fork-topology-path
   (fn [topology]
     (assoc (or topology {}) child-id
            {:fork/id child-id
             :fork/parent-id parent-id
             :fork/ygg-id ygg-fork-id
             :fork/state :local})))
  nil)

(defn mark-fork-transferred!
  "Mark a tracked child as externally owned without releasing its parent link."
  [child-id owner]
  (rctx/shared-swap-state!
   fork-topology-path
   (fn [topology]
     (update (or topology {}) child-id
             (fn [entry]
               (assoc (or entry {:fork/id child-id})
                      :fork/state :transferred
                      :fork/owner owner)))))
  nil)

(defn untrack-fork!
  "Release a structural child link only when its Yggdrasil fork identity
   matches. Missing entries are idempotent; mismatches are rejected."
  [child-id expected-ygg-fork-id]
  (let [result (volatile! :missing)]
    (rctx/shared-swap-state!
     fork-topology-path
     (fn [topology]
       (let [topology (or topology {})
             entry (get topology child-id)]
         (cond
           (nil? entry) topology
           (= expected-ygg-fork-id (:fork/ygg-id entry))
           (do (vreset! result :released) (dissoc topology child-id))
           :else
           (do (vreset! result entry) topology)))))
    (when (map? @result)
      (throw (ex-info "Structural fork identity mismatch"
                      {:type ::fork-identity-mismatch
                       :fork/id child-id
                       :fork/expected-ygg-id (:fork/ygg-id @result)
                       :fork/actual-ygg-id expected-ygg-fork-id}))))
  nil)

(defn structural-children
  "Return open structural child-world projections for `parent-id`."
  [parent-id]
  (->> (vals (or (rctx/shared-get-state fork-topology-path) {}))
       (filter #(= parent-id (:fork/parent-id %)))
       vec))

(defn roots
  "Rooms with no parent (top-level)."
  []
  (list-rooms :where #(nil? (:parent-id %))))

(defn snapshot
  "Return the entire registry map as-is (for UI rendering, debugging)."
  []
  (or (rctx/shared-get-state registry-path) {}))
