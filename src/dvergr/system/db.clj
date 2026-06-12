(ns dvergr.system.db
  "The **system database** — dvergr's registry + identity backbone.

   A single Datahike DB (distinct from per-room message DBs and per-system
   stores) holding the durable answer to \"who exists, what systems exist, and
   what's attached to which room with which permission\":

   - `:party/*`   — unified identity for humans and agents.
   - `:system/*`  — the registry of attachable yggdrasil systems (KBs, repos, …),
                    each carrying its own store *scope*.
   - `:room/*`    — rooms as top-level projects (own message-DB scope + owner).
   - `:grant/*`   — room↔system attachment edges, each with a permission.

   A room defaults to owning its own KB + repo (a `:grant` with `:owner`).
   Systems are attached/detached to *other* rooms at `:read` / `:read-write`
   on the fly — the durable record lives here; the live runtime attach/detach
   happens on the room's yggdrasil composite (spindel `register!` / `detach!`).
   Generalises simmis's `is.simm.model.system-db` from `:kb/*` to any `:system/*`."
  (:require [datahike.api :as d]
            [dvergr.chat.schema :as cschema]
            [dvergr.substrate.paths :as paths]))

;; ---------------------------------------------------------------------------
;; Schema
;; ---------------------------------------------------------------------------

(def schema
  [;; --- Parties: humans and agents share one identity ---
   {:db/ident :party/id           :db/valueType :db.type/uuid    :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :party/type         :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one} ; :human | :agent
   {:db/ident :party/handle       :db/valueType :db.type/string  :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :party/display-name :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :party/role         :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one} ; :admin | :user
   {:db/ident :party/created      :db/valueType :db.type/instant :db/cardinality :db.cardinality/one}
   ;; agent fields
   {:db/ident :party/owner        :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one} ; billing party
   {:db/ident :party/model        :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :party/provider     :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :party/system-prompt :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   ;; human auth fields (minimal; extend per simmis later)
   {:db/ident :party/email        :db/valueType :db.type/string  :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :party/password-hash :db/valueType :db.type/string :db/cardinality :db.cardinality/one}

   ;; --- Systems: attachable yggdrasil systems (KBs, repos, …) ---
   {:db/ident :system/id      :db/valueType :db.type/uuid    :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :system/type    :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one} ; :kb | :repo | …
   {:db/ident :system/name    :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :system/owner   :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one} ; party
   {:db/ident :system/scope   :db/valueType :db.type/string  :db/cardinality :db.cardinality/one} ; db-scope uuid-str | repo path
   {:db/ident :system/created :db/valueType :db.type/instant :db/cardinality :db.cardinality/one}

   ;; --- Rooms: top-level projects ---
   {:db/ident :room/id       :db/valueType :db.type/uuid    :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :room/slug     :db/valueType :db.type/string  :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :room/name     :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   {:db/ident :room/type     :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :room/owner    :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one} ; party
   {:db/ident :room/db-scope :db/valueType :db.type/string  :db/cardinality :db.cardinality/one} ; own message DB
   {:db/ident :room/parties  :db/valueType :db.type/ref     :db/cardinality :db.cardinality/many} ; members
   {:db/ident :room/created  :db/valueType :db.type/instant :db/cardinality :db.cardinality/one}
   ;; RF5 S4: the room REGISTRY lives here (system-db) — these are the fields the
   ;; in-memory Room + UI need that aren't otherwise derivable. `parent-slug` keeps
   ;; the hierarchy as a plain slug (no chat-id impedance); telegram-chat-id keys
   ;; the channel lookup; agent-ids the default participants.
   {:db/ident :room/telegram-chat-id :db/valueType :db.type/long    :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :room/agent-ids        :db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
   {:db/ident :room/parent-slug      :db/valueType :db.type/string  :db/cardinality :db.cardinality/one}

   ;; --- Grants: room↔system attachment with permission ---
   {:db/ident :grant/id         :db/valueType :db.type/uuid    :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :grant/room       :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   {:db/ident :grant/system     :db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   {:db/ident :grant/permission :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one} ; :owner | :read | :read-write
   {:db/ident :grant/created    :db/valueType :db.type/instant :db/cardinality :db.cardinality/one}])

;; --- Actors: the global cast (humans + agents) ---
;; RF5 S3: identity is global (an actor spans rooms), so `:actor/*` lives in
;; system-db, NOT a room store. We reuse the canonical `actor-schema` (single
;; source of truth) rather than redeclaring the 11 attrs. `:party/*` above is
;; kept for now (human-auth fields email/password-hash that `:actor/*` lacks);
;; S5 reconciles the two.
(def actor-schema cschema/actor-schema)

;; --- Pricing: the global model price list ---
;; RF5 S3: pricing is truly-global, read-mostly config (a model's price is the
;; same in every room), so it belongs in system-db. The live price source today
;; is the in-code model registry (`dvergr.model.registry/pricing-of`); this
;; durable `:pricing/*` family is the DB-backed override path (future use).
(def pricing-schema cschema/pricing-schema)

;; --- Tasks: the actor dispatch ledger ---
;; RF5 S3 (deviation from the per-room plan): a task is keyed by `:task/actor-id`
;; — it's the ASSIGNEE's inbox item (a human/external dispatched a skill), not a
;; room project artifact (the room-id is just provenance). Assignees are global
;; `:actor/*` rows here, so tasks live next to them: the inbox is one query (not
;; a fan-out), and `skills/dispatch!` reads the actor AND writes the task on ONE
;; conn (system-db) — resolving the S3a actor/task split. Low-volume, so no
;; meaningful write pressure on the thin glue.
(def task-schema cschema/task-dispatch-schema)

;; ---------------------------------------------------------------------------
;; Lifecycle — a plain (un-forked) Datahike at .dvergr/system-db
;; ---------------------------------------------------------------------------

(defn- cfg []
  (let [path (paths/system-db-dir)]
    {:store {:backend :file :path path
             :id (java.util.UUID/nameUUIDFromBytes (.getBytes ^String path))}
     :schema-flexibility :write}))

(defonce ^:private conn-atom (atom nil))

(defn get-conn
  "The system-DB connection, creating + migrating the store on first use."
  []
  (or @conn-atom
      (let [c (cfg)]
        (when-not (d/database-exists? c) (d/create-database c))
        (let [conn (d/connect c)]
          (d/transact conn (vec (concat schema actor-schema pricing-schema task-schema)))
          (reset! conn-atom conn)))))

(defn reset-conn!
  "Drop the cached system-DB connection so the next `get-conn` reconnects at the
   *current* `paths/system-db-dir`. For tests that re-root
   `dvergr.substrate.paths` to an isolated temp dir (call after
   `paths/set-home!`), so they neither read nor pollute the real `.dvergr`."
  []
  (reset! conn-atom nil))

(defn- now [] (java.util.Date.))

;; ---------------------------------------------------------------------------
;; Parties
;; ---------------------------------------------------------------------------

(defn create-party!
  "Upsert a party (human or agent). `m` carries `:party/*` keys; `:party/handle`
   is the natural key. Returns the party's uuid."
  [{:keys [party/handle party/type] :as m}]
  (let [conn (get-conn)
        id   (or (:party/id m) (java.util.UUID/randomUUID))
        ent  (merge {:party/id id :party/type (or type :human)
                     :party/created (now)} m {:party/id id})]
    (d/transact conn [ent])
    id))

(defn party-by-handle [handle]
  (d/q '[:find (pull ?e [*]) . :in $ ?h :where [?e :party/handle ?h]] @(get-conn) handle))

;; ---------------------------------------------------------------------------
;; Systems registry
;; ---------------------------------------------------------------------------

(defn register-system!
  "Register an attachable system (a KB, repo, …). `type` is `:kb`/`:repo`,
   `scope` is its store locator (a db-scope uuid string or a repo path),
   `owner-id` the owning party uuid. Returns the system uuid."
  [{:keys [type name scope owner-id]}]
  (let [conn (get-conn)
        id   (java.util.UUID/randomUUID)
        owner (when owner-id [:party/id owner-id])]
    (d/transact conn [(cond-> {:system/id id :system/type type :system/name name
                               :system/scope scope :system/created (now)}
                        owner (assoc :system/owner owner))])
    id))

(defn system-by-id [id]
  (d/q '[:find (pull ?e [*]) . :in $ ?id :where [?e :system/id ?id]] @(get-conn) id))

(defn all-systems
  "Every registered system `{:system/type :system/scope …}` — used to re-register
   them as yggdrasil systems on daemon boot (the composite is in-memory)."
  []
  (d/q '[:find [(pull ?e [:system/id :system/type :system/scope :system/name]) ...]
         :where [?e :system/id]] @(get-conn)))

;; ---------------------------------------------------------------------------
;; Rooms (projects)
;; ---------------------------------------------------------------------------

(defn create-room!
  "Create-or-update a room/project. `slug` is the natural key (upsert), so this
   is idempotent on re-provision. `db-scope` is its own message-DB locator;
   `owner-id` the owning party. RF5 S4: the registry fields (`type`,
   `telegram-chat-id`, `agent-ids`, `parent-slug`) live here too — the UI reads
   them straight off system-db, no chat-db. Returns the room uuid."
  [{:keys [slug name type db-scope owner-id telegram-chat-id agent-ids parent-slug]}]
  (let [conn  (get-conn)
        ;; Upsert by slug: reuse the existing room-id if present so re-provision
        ;; (hydrate) doesn't mint a duplicate identity.
        id    (or (d/q '[:find ?id . :in $ ?s :where [?e :room/slug ?s] [?e :room/id ?id]]
                       @conn slug)
                  (java.util.UUID/randomUUID))
        owner (when owner-id [:party/id owner-id])]
    (d/transact conn [(cond-> {:room/id id :room/slug slug :room/name (or name slug)
                               :room/type (or type :project) :room/created (now)}
                        db-scope         (assoc :room/db-scope db-scope)
                        owner            (assoc :room/owner owner)
                        telegram-chat-id (assoc :room/telegram-chat-id (long telegram-chat-id))
                        (seq agent-ids)  (assoc :room/agent-ids (set agent-ids))
                        parent-slug      (assoc :room/parent-slug parent-slug))])
    id))

;; The full registry projection — everything the in-memory Room/UI needs.
(def ^:private room-pull
  [:room/id :room/slug :room/name :room/type :room/db-scope
   :room/telegram-chat-id :room/agent-ids :room/parent-slug])

(defn room-by-slug [slug]
  (d/q '[:find (pull ?e [*]) . :in $ ?s :where [?e :room/slug ?s]] @(get-conn) slug))

(defn room-by-telegram-id
  "Registry row for a Telegram chat-id, or nil."
  [telegram-chat-id]
  (d/q '[:find (pull ?e [*]) . :in $ ?t :where [?e :room/telegram-chat-id ?t]]
       @(get-conn) (long telegram-chat-id)))

(defn add-room-agent!
  "Add `agent-id` (keyword) to a room's `:room/agent-ids` set (by slug)."
  [slug agent-id]
  (d/transact (get-conn) [{:room/slug slug :room/agent-ids agent-id}]))

(defn remove-room-agent!
  "Remove `agent-id` from a room's `:room/agent-ids` set (by slug)."
  [slug agent-id]
  (when-let [eid (d/q '[:find ?e . :in $ ?s :where [?e :room/slug ?s]] @(get-conn) slug)]
    (d/transact (get-conn) [[:db/retract eid :room/agent-ids agent-id]])))

(defn set-room-parent!
  "Set a room's parent (by slugs)."
  [child-slug parent-slug]
  (d/transact (get-conn) [{:room/slug child-slug :room/parent-slug parent-slug}]))

(defn all-rooms
  "Every registered room, fully projected (`room-pull`) — the authoritative
   registry the daemon hydrates from (RF5 S4) and recreates per-room ctxs for."
  []
  (d/q '[:find [(pull ?e ?pull) ...] :in $ ?pull
         :where [?e :room/id]] @(get-conn) room-pull))

;; ---------------------------------------------------------------------------
;; Grants — attach / detach a system to a room with a permission
;; ---------------------------------------------------------------------------

(defn attach!
  "Attach `system-id` to `room-id` with `permission` (:owner/:read/:read-write).
   Idempotent on the [room system] pair (re-attaching updates the permission).
   Returns the grant uuid."
  [room-id system-id permission]
  (let [conn     (get-conn)
        existing (d/q '[:find ?g . :in $ ?r ?s
                        :where [?g :grant/room ?re] [?re :room/id ?r]
                        [?g :grant/system ?se] [?se :system/id ?s]]
                      @conn room-id system-id)
        id       (java.util.UUID/randomUUID)]
    (if existing
      (do (d/transact conn [{:db/id existing :grant/permission permission}])
          (d/q '[:find ?gid . :in $ ?g :where [?g :grant/id ?gid]] @conn existing))
      (do (d/transact conn [{:grant/id id :grant/room [:room/id room-id]
                             :grant/system [:system/id system-id]
                             :grant/permission permission :grant/created (now)}])
          id))))

(defn detach!
  "Remove the attachment of `system-id` from `room-id`. Returns true if a grant
   was removed."
  [room-id system-id]
  (let [conn (get-conn)
        g    (d/q '[:find ?g . :in $ ?r ?s
                    :where [?g :grant/room ?re] [?re :room/id ?r]
                    [?g :grant/system ?se] [?se :system/id ?s]]
                  @conn room-id system-id)]
    (when g (d/transact conn [[:db/retractEntity g]]) true)))

(defn delete-system-by-scope!
  "Detach the system whose `:system/scope` is `scope` from `room-id` and retract its
   system entity. Used to remove an agent-created room data DB. Returns true if a
   system was removed."
  [room-id scope]
  (let [conn (get-conn)
        sid  (d/q '[:find ?id . :in $ ?sc :where [?e :system/scope ?sc] [?e :system/id ?id]]
                  @conn scope)]
    (when sid
      (detach! room-id sid)
      (d/transact conn [[:db/retractEntity [:system/id sid]]])
      true)))

(defn systems-for-room
  "The systems attached to a room, each as `{:system … :permission …}`.
   This is the resolver the sandbox/KB tools consult to know which stores a room
   may read/write."
  [room-id]
  (->> (d/q '[:find (pull ?se [*]) ?perm
              :in $ ?r
              :where [?g :grant/room ?re] [?re :room/id ?r]
              [?g :grant/system ?se] [?g :grant/permission ?perm]]
            @(get-conn) room-id)
       (mapv (fn [[sys perm]] {:system sys :permission perm}))))

(defn systems-for-room-of-type
  "Attached systems of a given `:system/type` (e.g. :kb or :repo) for a room."
  [room-id type]
  (filterv #(= type (get-in % [:system :system/type])) (systems-for-room room-id)))

(defn delete-room!
  "Retract a room, all its grants, and the given owned system entities (by uuid)
   from the registry. Attached (non-owned) systems are left intact; only the
   grants linking them to this room are removed. Returns the count retracted."
  [room-id owned-system-ids]
  (let [conn       (get-conn)
        room-eid   (d/q '[:find ?e . :in $ ?id :where [?e :room/id ?id]] @conn room-id)
        grant-eids (d/q '[:find [?g ...] :in $ ?id
                          :where [?g :grant/room ?re] [?re :room/id ?id]] @conn room-id)
        sys-eids   (when (seq owned-system-ids)
                     (d/q '[:find [?s ...] :in $ [?sid ...]
                            :where [?s :system/id ?sid]] @conn owned-system-ids))
        eids       (concat (when room-eid [room-eid]) grant-eids sys-eids)]
    (when (seq eids)
      (d/transact conn (mapv (fn [e] [:db/retractEntity e]) eids)))
    (count eids)))
