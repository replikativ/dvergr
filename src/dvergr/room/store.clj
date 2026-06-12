(ns dvergr.room.store
  "PRoomStore — pluggable durability for a Room.

   Every Room (`dvergr.discourse.Room`) optionally carries a
   `:store`. When non-nil, the Room spawns an internal listener at
   construction that mirrors every message-shaped event on its bus
   to the store via `-store-message!`. Consumers of Rooms never call
   the store directly — they post via `discourse/post!` and read via
   `room/messages` (which routes to the store when present, the bus
   log otherwise).

   The store also persists room *metadata* (title, parent, agent-ids,
   etc.) so the daemon can re-hydrate the registry on startup.

   Two impls ship:
     - dvergr.room.store.memory     — atom-backed; for tests and
                                       ephemeral rooms (e.g. forks)
     - dvergr.room.store.datahike   — wraps the existing
                                       `:chat/*` / `:message/*` /
                                       `:room/*` schema, same data
                                       as today's `dvergr.rooms`."
  (:require [clojure.string :as str]))

(defprotocol PRoomStore
  "Pluggable durability surface for Rooms. Implementations decide
   where data lives (Datahike, in-memory atom, KV store, etc.); the
   shape is the same.

   `room-id` is the Room's keyword id (its `:id` field). Implementations
   that need a UUID for their own storage layer compute one from the
   keyword (e.g. an alias lookup) — callers always pass the id."

  (-store-room! [this room-id metadata]
    "Persist room metadata. `metadata` is a map with at minimum
     :slug; may also carry :title :parent-id :agent-ids :telegram-chat-id
     :type :meta. Implementations are idempotent (re-storing updates).")

  (-load-room [this id-or-slug]
    "Look up a room by id (keyword) or slug (string). Returns the
     metadata map (same shape as -store-room! input) or nil.")

  (-delete-room! [this room-id]
    "Remove the room and all its messages from the store. Idempotent.")

  (-list-rooms [this]
    "Return every room metadata map known to the store, ordered by
     :updated-at descending (or any stable order). Used at daemon
     startup to re-hydrate the registry.")

  (-store-message! [this room-id message]
    "Persist a single Message. `message` is a discourse.Message record
     (with :id :from :to :content :ts :in-reply-to :metadata) OR a
     map with the same keys. Implementations must be idempotent on
     :id — re-stores are no-ops.")

  (-list-messages [this room-id {:keys [limit since]}]
    "Return messages in chronological order. :limit caps result size
     (default impl-specific); :since is an instant — only messages
     after that ts are returned."))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn message-shape?
  "True if `msg` looks like a Message (has the required keys). Used by
   the Room's internal persistence listener to filter bus events:
   user/agent message envelopes get persisted, ticks and source events
   do not."
  [msg]
  (and (map? msg)
       (contains? msg :id)
       (contains? msg :content)
       ;; The bus tags Messages with :type :user/message; filter to
       ;; message-shaped events only.
       (or (= :user/message (:type msg))
           (= :agent/reply  (:type msg))
           (and (not (contains? msg :type))
                (instance? java.util.UUID (:id msg))))))

(defn infer-role
  "Global conversation role for a message — the single source of truth shared
   by the persisted store and the live `:room-messages` signal so both folds
   of the bus agree. An explicit `:role` (on the message or its `:metadata`)
   wins; otherwise a keyword `:from` (an agent/system actor id) is `:assistant`
   and a user/external post is `:user`. User input carries an explicit
   `:role :user`, so it is never misread as an agent reply."
  [m]
  (or (:role m)
      (:role (:metadata m))
      (cond
        (= (:from m) :_system) :assistant
        (keyword? (:from m))   :assistant
        :else                  :user)))

(defn slug->room-id
  "Canonical mapping slug → Room id (a keyword). The id is the slug
   with `/` re-encoded to `_fork_` so forks of `boardroom` slugged as
   `boardroom/fork-abc12345` get a valid keyword id."
  [slug]
  (when slug
    (keyword (str/replace slug "/" "_fork_"))))

(defn room-id->slug
  "Inverse of slug->room-id."
  [room-id]
  (when room-id
    (str/replace (name room-id) "_fork_" "/")))
