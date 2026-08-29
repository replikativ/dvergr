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
     (with :id :from :to :content :ts :in-reply-to :thread-root-id :metadata) OR a
     map with the same keys. :from and non-nil :to are canonical keyword actor
     ids; :in-reply-to is the stable immediate-parent UUID,
     :thread-root-id is the stable topical-root UUID, and :metadata uses dvergr's
     typed durable vocabulary (role/source, audience/mentions, attachment,
     provenance, notification, tool-use and reasoning fields). Implementations
     replay these envelope fields losslessly and must be first-write-wins
     idempotent on :id — re-stores are no-ops. Unknown durable metadata is an
     error: extend the typed schema rather than adding an opaque encoding.")

  (-message-thread-root [this room-id message-id]
    "Return one message's stable topical-root UUID inside `room-id`, or nil.
     This bounded lookup lets the authoritative Room derive and validate a
     reply's thread without trusting a client-supplied ancestor.")

  (-list-messages [this room-id {:keys [limit since thread-root-id]}]
    "Return messages in chronological order. :limit caps result size
     (default impl-specific); :since is an instant — only messages
     after that ts are returned; :thread-root-id restricts the result to one
     topical projection before the limit is applied.")

  (-store-run! [this room-id run]
    "Create or update a durable Run projection owned by `room-id`. Run identity,
     kind, room, actor, trigger, parent, and start timestamps are immutable by
     contract; lifecycle updates change status and terminal fields.")

  (-load-run [this room-id run-id]
    "Return one durable Run by UUID when it belongs to `room-id`, else nil.")

  (-list-runs [this room-id {:keys [limit status actor]}]
    "Return recent Runs, newest first. Optional :status and :actor filters are
     applied before :limit."))

;; =============================================================================
;; Helpers
;; =============================================================================

(def durable-run-statuses
  "Lifecycle states stored in a Room. `:cancelling` is deliberately live-only:
   durable cancellation is acknowledged by the terminal `:cancelled` state."
  #{:running :waiting :completed :failed :cancelled})

(def terminal-run-statuses #{:completed :failed :cancelled})

(def durable-run-keys
  #{:run/id :run/kind :run/room :run/actor :run/trigger :run/parent
    :run/status :run/created-at :run/started-at :run/updated-at :run/ended-at
    :run/reason :run/error
    :run/world :run/isolation :run/settlement-policy
    :run/settlement-status :run/settlement-reason
    :run/roster :run/agent-version :run/program-kind
    :run/interpreter-version :run/agent-def-hash :run/chat-id})

(def immutable-run-keys
  [:run/id :run/kind :run/room :run/actor :run/trigger :run/parent
   :run/created-at :run/started-at
   :run/world :run/isolation :run/settlement-policy
   :run/roster :run/agent-version :run/program-kind
   :run/interpreter-version :run/agent-def-hash :run/chat-id])

(defn validate-run!
  "Validate the minimal durable Run contract and return `run`."
  [run]
  (when-let [unknown (seq (remove durable-run-keys (keys run)))]
    (throw (ex-info "Unknown durable Run keys"
                    {:type :room-store/unknown-run-keys
                     :unknown (set unknown)
                     :allowed durable-run-keys})))
  (doseq [k [:run/id :run/trigger]]
    (when-not (uuid? (get run k))
      (throw (ex-info (str k " must be a UUID")
                      {:type :room-store/invalid-run :key k :run run}))))
  (when-not (keyword? (:run/room run))
    (throw (ex-info ":run/room must be a keyword"
                    {:type :room-store/invalid-run :key :run/room :run run})))
  (when-not (keyword? (:run/actor run))
    (throw (ex-info ":run/actor must be a keyword"
                    {:type :room-store/invalid-run :key :run/actor :run run})))
  (when-not (keyword? (:run/kind run))
    (throw (ex-info ":run/kind must be a keyword"
                    {:type :room-store/invalid-run :key :run/kind :run run})))
  (when-not (contains? durable-run-statuses (:run/status run))
    (throw (ex-info "Invalid durable run status"
                    {:type :room-store/invalid-run-status
                     :status (:run/status run) :run run})))
  (when (and (:run/parent run) (not (uuid? (:run/parent run))))
    (throw (ex-info ":run/parent must be a UUID"
                    {:type :room-store/invalid-run :key :run/parent :run run})))
  (when (and (:run/roster run) (not (keyword? (:run/roster run))))
    (throw (ex-info ":run/roster must be a keyword"
                    {:type :room-store/invalid-run :key :run/roster :run run})))
  (doseq [k [:run/agent-version :run/interpreter-version]
          :let [v (get run k)]
          :when (and (some? v) (not (and (integer? v) (pos? v))))]
    (throw (ex-info (str k " must be a positive integer")
                    {:type :room-store/invalid-run :key k :run run})))
  (when (and (:run/program-kind run) (not (keyword? (:run/program-kind run))))
    (throw (ex-info ":run/program-kind must be a keyword"
                    {:type :room-store/invalid-run :key :run/program-kind :run run})))
  (when (and (:run/agent-def-hash run) (not (uuid? (:run/agent-def-hash run))))
    (throw (ex-info ":run/agent-def-hash must be a UUID"
                    {:type :room-store/invalid-run :key :run/agent-def-hash :run run})))
  (when (and (:run/chat-id run) (not (uuid? (:run/chat-id run))))
    (throw (ex-info ":run/chat-id must be a UUID"
                    {:type :room-store/invalid-run :key :run/chat-id :run run})))
  (doseq [k [:run/world :run/isolation :run/settlement-policy
             :run/settlement-status :run/settlement-reason]
          :let [v (get run k)]
          :when (and (some? v) (not (keyword? v)))]
    (throw (ex-info (str k " must be a keyword")
                    {:type :room-store/invalid-run :key k :run run})))
  (doseq [k [:run/created-at :run/started-at :run/updated-at]
          :when (not (instance? java.util.Date (get run k)))]
    (throw (ex-info (str k " must be an instant")
                    {:type :room-store/invalid-run :key k :run run})))
  (when (and (contains? terminal-run-statuses (:run/status run))
             (not (instance? java.util.Date (:run/ended-at run))))
    (throw (ex-info "A terminal run requires :run/ended-at"
                    {:type :room-store/invalid-run :key :run/ended-at :run run})))
  run)

(defn validate-run-update!
  "Reject an update that changes the causal identity of an existing Run."
  [existing run]
  (when (and existing
             (not= (select-keys existing immutable-run-keys)
                   (select-keys run immutable-run-keys)))
    (throw (ex-info "Durable run identity fields are immutable"
                    {:type :room-store/immutable-run-update
                     :run-id (:run/id run)
                     :existing (select-keys existing immutable-run-keys)
                     :update (select-keys run immutable-run-keys)})))
  run)

(def durable-message-metadata-keys
  "The top-level metadata keys that every PRoomStore implementation accepts.
   New durable extensions must add typed storage in persistent implementations
   before being added here."
  #{:role :source-user :source-username :source-user-id
    :audience :mentions :attachment :provenance
    :tool-uses :reasoning :kind :from :source :schedule-id
    :notification/type :notification/agent :notification/task
    :notification/elapsed :run-id})

(def ^:private attachment-metadata-keys #{:blob-id :node-id :mime :name :size})
(def ^:private provenance-metadata-keys #{:mode :source})

(defn- reject-unknown-metadata! [kind allowed value]
  (let [unknown (seq (remove allowed (keys (or value {}))))]
    (when unknown
      (throw (ex-info (str "Unknown durable message " kind " keys")
                      {:type :room-store/unknown-message-metadata
                       :kind kind
                       :unknown (set unknown)
                       :allowed allowed})))))

(defn validate-message-metadata!
  "Validate the typed durable message metadata vocabulary and return `metadata`.
   Kept at the protocol boundary so ephemeral and persistent stores reject the
   same accidental, unmodelled extensions."
  [metadata]
  (when metadata
    (when-not (map? metadata)
      (throw (ex-info "Durable message metadata must be a map"
                      {:type :room-store/invalid-message-metadata
                       :value metadata})))
    (reject-unknown-metadata! :metadata durable-message-metadata-keys metadata)
    (when-let [attachment (:attachment metadata)]
      (when-not (map? attachment)
        (throw (ex-info "Message attachment metadata must be a map"
                        {:type :room-store/invalid-message-metadata
                         :attachment attachment})))
      (reject-unknown-metadata! :attachment attachment-metadata-keys attachment))
    (when-let [provenance (:provenance metadata)]
      (when-not (map? provenance)
        (throw (ex-info "Message provenance metadata must be a map"
                        {:type :room-store/invalid-message-metadata
                         :provenance provenance})))
      (reject-unknown-metadata! :provenance provenance-metadata-keys provenance)))
  (when (and (:run-id metadata) (not (uuid? (:run-id metadata))))
    (throw (ex-info "Message :run-id metadata must be a UUID"
                    {:type :room-store/invalid-message-metadata
                     :run-id (:run-id metadata)})))
  metadata)

(defn normalize-message-thread
  "Return `msg` with a typed durable `:thread-root-id`.

   Top-level messages self-root. Legacy replies without an explicit root treat
   their immediate parent as the root; live nested replies preserve the actual
   root through `dvergr.discourse/reply`, while out-of-order importers should
   supply it explicitly. A top-level message claiming another root is invalid:
   joining a thread requires an immediate causal parent."
  [msg]
  (let [id     (:id msg)
        parent (:in-reply-to msg)
        root   (or (:thread-root-id msg) parent id)]
    (when-not (uuid? root)
      (throw (ex-info "Durable message thread root must be a UUID"
                      {:type :room-store/invalid-thread-root
                       :message-id id :thread-root-id root})))
    (when (and (nil? parent) (not= id root))
      (throw (ex-info "A top-level message must be its own thread root"
                      {:type :room-store/invalid-thread-root
                       :message-id id :thread-root-id root})))
    (assoc msg :thread-root-id root)))

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
