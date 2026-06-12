(ns dvergr.rooms
  "Room CRUD and queries — unified with discourse Rooms.

   RF5 S4: the room REGISTRY (which rooms exist + their title/type/parent/
   telegram-chat-id/agent-ids) lives in the GLOBAL system-db (`dvergr.system.db`);
   each room's CONVERSATION lives in its OWN per-room messages store. There is no
   shared chat-db. `create-room!` provisions the room (system-db row + its own
   msgs/KB/repo systems on its own ctx) AND constructs the `dvergr.discourse.Room`
   with that per-room store, registering it for the TUI tree + sandbox + the rest.

   Usage:
     (hydrate-registry! ctx)             ; one-shot at daemon startup
     (create-room! {:title \"General\" :slug \"general\" :type :internal})
     (post-to-slug! \"general\" {:content \"hello\" :role :user})"
  (:require [datahike.api :as dh]
            [dvergr.chat.schema :as schema]
            [dvergr.substrate.config :as config]
            [dvergr.discourse :as d]
            [dvergr.room.store :as rstore]
            [dvergr.system.rooms :as srooms]
            [dvergr.system.db :as sdb]
            [dvergr.room.store.datahike :as datahike-store]
            [dvergr.room.registry :as rreg]
            [org.replikativ.spindel.engine.core :as ec]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; State
;; ============================================================================

(defn init!
  "Deprecated no-op (RF5 S4): the registry is system-db now; there is no shared
   chat-db conn to store. Retained until the daemon boot call site is cleaned up."
  [_datahike-conn]
  nil)

;; create-room! / join-agent! both call into discourse-join!, defined
;; further down. Declare it here so create-room! can resolve it at
;; compile time.
(declare discourse-join!)

(defn room-store
  "A DatahikeStore for `slug`'s OWN per-room messages store — wrapping the
   `room-msgs-*` yggdrasil conn (fork-aware via `ygg/system`), so the room's
   conversation forks/merges with it (RF5). nil if the room isn't provisioned
   with a `:msgs` system. Requires a bound execution context."
  [slug]
  (some-> (srooms/msgs-conn-for-slug slug) (datahike-store/make)))

;; ============================================================================
;; Room CRUD
;; ============================================================================

(defn create-room!
  "Create a new room: Datahike entity AND discourse.Room registration.

   The discourse Room is built with a DatahikeStore so every message
   posted via `discourse/post!` (or this ns's `post-message!`) is
   persisted to the same `:message/*` schema that's been the store
   format all along.

   Opts:
     :title            - Display title
     :slug             - URL-friendly identifier (unique)
     :type             - :telegram-mirror or :internal
     :telegram-chat-id - Telegram chat ID (for mirrors, long)
     :agent-ids        - Set of agent keywords participating
     :parent-id        - Parent room id (keyword) or slug (string) for
                          nesting. Defaults to the global room (boardroom)
                          unless the room being created IS the global room.
                          Pass false to opt out.
     :ctx              - Execution context for the discourse Room
                          (default: current bound ctx)

   Returns the room-id (slug keyword) of the created room. Callers who need the
   discourse Room can look it up via `(dvergr.room.registry/lookup room-id)`."
  [{:keys [title slug type telegram-chat-id agent-ids parent-id ctx]
    :or   {parent-id :default}}]
  (let [ctx (or ctx (try (ec/current-execution-context) (catch Throwable _ nil)))
        global-slug (or (:global-room-slug (config/config)) "boardroom")
        ;; Resolve parent-id:
        ;;   :default → global room (unless THIS room is the global room)
        ;;   false     → no parent (opt-out)
        ;;   string    → treat as slug
        ;;   keyword   → treat as room-id
        parent-kw (cond
                    (= parent-id :default)
                    (when (not= slug global-slug)
                      (rstore/slug->room-id global-slug))
                    (false? parent-id) nil
                    (string? parent-id) (rstore/slug->room-id parent-id)
                    (keyword? parent-id) parent-id
                    :else nil)
        parent-slug (some-> parent-kw rstore/room-id->slug)]
    ;; Provision the room as a project in the system DB — its own messages store +
    ;; KB + repo as yggdrasil systems on the room's OWN execution context (RF5 S2).
    ;; The system-db room row IS the registry (RF5 S4), carrying the registry
    ;; fields. Returns the room's ctx; forking it branches only the room's systems.
    (let [prov     (when ctx
                     (try (binding [ec/*execution-context* ctx]
                            (srooms/provision-room!
                             (cond-> {:slug slug :name (or title slug)
                                      :type (or type :internal)}
                               telegram-chat-id (assoc :telegram-chat-id telegram-chat-id)
                               (seq agent-ids)  (assoc :agent-ids (set agent-ids))
                               parent-slug      (assoc :parent-slug parent-slug))))
                          (catch Throwable _ nil)))
          room-ctx (or (:room-ctx prov) ctx)]
      (tel/log! {:id :rooms/created :data {:slug slug}} "Room created")
      ;; Build the discourse Room ON its own ctx, with its per-room store.
      ;; rreg/register! roots the registry write (Tier-1), so the room is visible
      ;; to the UI even though it runs on its own ctx.
      (when ctx
        (let [store (binding [ec/*execution-context* room-ctx] (room-store slug))]
          (when store
            (let [new-room (binding [ec/*execution-context* room-ctx]
                             (d/make-room {:id        (rstore/slug->room-id slug)
                                           :slug      slug
                                           :title     (or title slug)
                                           :parent-id parent-kw
                                           :ctx       room-ctx
                                           :store     store
                                           :meta      (cond-> {:type (or type :internal)
                                                               :chat-id (srooms/msgs-chat-id slug)
                                                               :agent-ids (set agent-ids)}
                                                        telegram-chat-id
                                                        (assoc :telegram-chat-id telegram-chat-id))}))]
              ;; Discourse-level join for every agent in :agent-ids. Only
              ;; effective for agents currently in the runtime registry —
              ;; missing ones get a warning but don't fail the create.
              (doseq [aid (seq agent-ids)]
                (binding [ec/*execution-context* room-ctx]
                  (discourse-join! new-room aid))))))))
    (rstore/slug->room-id slug)))

(defn delete-room!
  "Delete a room: retract its persisted `:chat/*`/`:message/*` entity from the
   store, then unregister it from the registry. The CRUD counterpart of
   `create-room!` — the single op both the TUI (`d`) and web (`/delete`) call.

   (Distinct from `dvergr.rooms.forks/discard!`, which tears down a FORK's git +
   datahike BRANCH; `delete-room!` removes a room's persisted conversation.)
   Returns {:ok? true} or {:ok? false :error …}."
  [room]
  (try
    (when room
      (when-let [store (:store room)]
        (rstore/-delete-room! store (:id room)))
      (rreg/unregister! (:id room)))
    {:ok? true}
    (catch Throwable t {:ok? false :error (.getMessage t)})))

(defn hydrate-registry!
  "For each room in the system-db registry (RF5 S4), build a discourse.Room
   backed by its OWN per-room messages store and register it. Idempotent —
   re-running just refreshes registry entries. Call once at daemon startup,
   AFTER `srooms/hydrate-rooms!` has rebuilt each room's ctx + systems."
  [ctx]
  (binding [ec/*execution-context* ctx]
    (let [rooms (sdb/all-rooms)]
      (doseq [{:room/keys [slug name type telegram-chat-id agent-ids parent-slug]} rooms]
        (let [id (rstore/slug->room-id slug)]
          (when-not (rreg/lookup id)
            ;; Ensure the room's systems (msgs/KB/repo) exist on its OWN ctx
            ;; (idempotent — returns the existing room-ctx for hydrated rooms).
            (let [prov     (try (srooms/provision-room! {:slug slug :name name})
                                (catch Throwable _ nil))
                  room-ctx (or (:room-ctx prov) ctx)]
              (binding [ec/*execution-context* room-ctx]
                (d/make-room {:id        id
                              :slug      slug
                              :title     (or name slug)
                              :parent-id (some-> parent-slug rstore/slug->room-id)
                              :ctx       room-ctx
                              :store     (room-store slug)
                              :meta      (cond-> {:chat-id (srooms/msgs-chat-id slug)}
                                           type             (assoc :type type)
                                           (seq agent-ids)  (assoc :agent-ids (set agent-ids))
                                           telegram-chat-id (assoc :telegram-chat-id telegram-chat-id))}))))))
      (tel/log! {:id :rooms/registry-hydrated :data {:count (count rooms)}}
                "Hydrated room registry"))))

(defn get-room-by-slug
  "Registry row for `slug` from system-db (RF5 S4), or nil."
  [slug]
  (sdb/room-by-slug slug))

(defn get-room-by-telegram-id
  "Registry row for a Telegram chat-id from system-db (RF5 S4), or nil."
  [telegram-chat-id]
  (sdb/room-by-telegram-id telegram-chat-id))

(defn list-rooms
  "All registry rows from system-db (RF5 S4), optionally filtered by `:type`."
  [& {:keys [type]}]
  (cond->> (sdb/all-rooms)
    type (filter #(= type (:room/type %)))))

;; ============================================================================
;; Messages
;; ============================================================================

(defn post-to-slug!
  "Post a message directly into room `slug`'s OWN messages store (RF5 S4) — the
   per-room conn + conversation chat-id are resolved here; requires a bound
   execution context. No-op (nil) if the room isn't provisioned.

   New code should prefer `(dvergr.discourse/post! room …)` (bus-routed, so the
   persistence listener writes it AND agents react). This direct path serves the
   system-notification callsites (calendar dispatcher, Telegram reply mirror)
   that only need the message in the room log.

   Opts: :content :role(:user) :source-user :source-username :source-user-id."
  [slug {:keys [content role source-user source-username source-user-id]}]
  (when-let [conn (srooms/msgs-conn-for-slug slug)]
    (let [cid (srooms/msgs-chat-id slug)
          msg (cond-> (schema/create-message-entity
                       {:chat-id cid :role (or role :user) :content content})
                source-user     (assoc :message/source-user source-user)
                source-username (assoc :message/source-username source-username)
                source-user-id  (assoc :message/source-user-id (long source-user-id)))]
      (dh/transact conn [msg {:db/id [:chat/id cid] :chat/updated-at (java.util.Date.)}]))))

;; ============================================================================
;; Agent membership — Datahike metadata + actual discourse join
;; ============================================================================

(defn- find-live-participant
  "Find a live Participant for `agent-id` in any registered room — used to
   clone it via its :factory into another room. Presence == room
   membership; nil if the agent isn't currently running anywhere."
  [agent-id]
  (some (fn [room] (get @(:participants room) agent-id))
        (rreg/list-rooms)))

(defn- discourse-join!
  "Find the running agent's Participant, clone it via :factory into the
   target Room's ctx, and join. No-op if the agent isn't running. Returns
   true if joined, false otherwise."
  [room agent-id]
  (when room
    (let [p   (find-live-participant agent-id)
          fac (some-> p :factory)]
      (cond
        (nil? p)
        (do (tel/log! {:level :warn :id :rooms/join-agent-not-running
                       :data  {:room-id (:id room) :agent-id agent-id}})
            false)

        (nil? fac)
        (do (tel/log! {:level :warn :id :rooms/join-agent-no-factory
                       :data  {:room-id (:id room) :agent-id agent-id}})
            false)

        ;; Already present — don't double-join
        (contains? @(:participants room) agent-id)
        true

        :else
        (binding [ec/*execution-context* (:ctx room)]
          (d/join room (fac (:ctx room)))
          true)))))

(defn- ->slug
  "Coerce a room ref (Room / room-id keyword / slug string) to its slug string."
  [room-ref]
  (cond
    (and (map? room-ref) (:slug room-ref)) (:slug room-ref)
    (keyword? room-ref) (rstore/room-id->slug room-ref)
    (string? room-ref)  room-ref))

(defn join-agent!
  "Join `agent-id` to `room-ref` (a Room, room-id keyword, or slug):
   - Persists the agent in the system-db registry (`:room/agent-ids`).
   - If the agent is running AND the discourse Room is registered, clones its
     Participant via `:factory` and joins it so it actually receives messages.

   Returns {:joined? true :discourse-joined? <bool>} for inspection."
  [room-ref agent-id]
  (let [slug (->slug room-ref)
        agent-id (if (keyword? agent-id) agent-id (keyword agent-id))]
    (when slug (sdb/add-room-agent! slug agent-id))
    (let [room (when slug (rreg/lookup (rstore/slug->room-id slug)))
          d-joined? (discourse-join! room agent-id)]
      {:joined? true :discourse-joined? d-joined? :agent-id agent-id
       :room (:id room)})))

(defn leave-agent!
  "Remove `agent-id` from `room-ref`'s registry membership (`:room/agent-ids`).
   (Discourse-level leave is not automatic — the Participant stays joined until
   the daemon restarts.)"
  [room-ref agent-id]
  (when-let [slug (->slug room-ref)]
    (sdb/remove-room-agent! slug (if (keyword? agent-id) agent-id (keyword agent-id)))))

(defn set-parent!
  "Set the parent of `child-ref` to `parent-ref` (Rooms, room-ids, or slugs) in
   the system-db registry."
  [child-ref parent-ref]
  (let [c (->slug child-ref) p (->slug parent-ref)]
    (when (and c p) (sdb/set-room-parent! c p))))
