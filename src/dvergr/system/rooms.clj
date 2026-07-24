(ns dvergr.system.rooms
  "Rooms as projects — provisioning + resolvers over the `dvergr.system.db`
   registry.

   A room's own KB (datahike) and repo (git) are registered as **yggdrasil
   systems** in the execution context's composite, so they fork + merge with the
   room (the same mechanism the chat DB uses) and resolve **fork-aware** via
   `ygg/system`. The registry (`dvergr.system.db`) records *which* systems a room
   owns/attaches and with what permission; this namespace turns that into the
   concrete, ctx-resolved stores the sandbox/tools use:

   - `room-kb-conn`     — a room's OWN writable KB conn (for `knowledge_add`).
   - `room-kb-conns`    — all KBs a room may read (own + attached).
   - `room-load-roots`  — ordered repo worktrees for the SCI load-fn.
   - `room-mount-spec`  — muschel composite-fs mount data.

   Resolvers require a bound execution context (always true in a turn; tests bind
   one). No manual conn caching — yggdrasil owns resolution."
  (:require [datahike.api :as d]
            [clojure.java.io :as io]
            [dvergr.system.db :as sdb]
            [dvergr.kb.schema :as kbs]
            [dvergr.chat.schema :as cschema]
            [dvergr.search.secondary :as search-secondary]
            [dvergr.scheduler.schema :as sched-schema]
            [dvergr.runtime.ctx :as rctx]
            [dvergr.substrate.geschichte :as geschichte]
            [geschichte.workspace :as gworkspace]
            [dvergr.substrate.paths :as paths]
            [dvergr.substrate.datahike :as sdh]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as sctx]))

;; room-id → the room's OWN execution context (RF5 S2). A `fork-context` of the
;; daemon root whose composite holds ONLY this room's systems (msgs/kb/repo), so
;; forking the room (`fork-context (:ctx room)`) branches just those — not the
;; whole daemon. A Tier-3 daemon-global atom (it must NOT itself fork). The room
;; RUNS on this ctx (bus/turn/composite = ISOLATION); its identity in the
;; registry is Tier-1 shared (root). See `dvergr.runtime.ctx`.
(defonce ^:private room-ctxs (atom {}))

(defn room-ctx-for
  "The room's own execution context (created by provision/hydrate), or nil."
  [room-id]
  (get @room-ctxs room-id))

(defn clear-room-ctxs!
  "Drop all per-room execution contexts. Call on daemon stop! so a same-process
   restart doesn't reuse stale forked ctxs pointing at the previous daemon root."
  []
  (reset! room-ctxs {}))

(defn- scope-path [scope] (str (io/file (paths/systems-dir) scope)))

(defn store-id
  "Konserve store id for a room store `path`. Deterministic in the path, so the
   id is stable across restarts and derivable without touching the store.

   Public because it is the konserve-sync SCOPE: a consumer that collapses its
   own per-room database onto the room store (simmis binds
   `:room/content-db-scope` to it) must name the same store the writer uses, and
   must not re-derive the formula on its own."
  [path]
  (java.util.UUID/nameUUIDFromBytes (.getBytes ^String path)))

(defn- store-cfg
  "ONE config for every datom store a room owns — messages, KB, agent `:data`.
   Uniform on purpose: any of them may grow to hold any combination of a room's
   content, so none of them may be missing a create-time-fixed flag that a later
   use would need.

   `:crypto-hash? true` makes every index-node address a content hash and every
   commit-id a UUID-5 over the merkle roots, so `datahike.audit/verify-chain`
   can verify the store end to end. That is what makes a room's books — and its
   wiki, chat and code — tamper-evident under one mechanism.

   datahike REQUIRES `:commit-graph? true` alongside it (the audit chain verifies
   the persisted commit chain), and the commit graph is also what
   `datahike.versioning/branch-history` walks, so fork/merge ancestry comes from
   here. (There is no `:branch-history?` option in datahike — a config carrying
   one is silently ignoring it.)

   `:keep-history? true` is for the PRODUCT's history: wiki edits, chat, ledger
   rows. It is NOT needed for branching, contrary to what this comment used to
   claim — `d/branch!` needs commit records, not temporal indices.

   All of these are fixed at CREATION: `ensure-stored-config-consistency`
   consistency-checks the STORED config on connect, so an existing store can
   never adopt one. Changing anything here means re-creating rooms."
  [path]
  {:store {:backend :file :path path :id (store-id path)}
   :keep-history? true
   :crypto-hash? true
   :commit-graph? true
   :index-config {:diff-buf-size sdh/diff-buf-size}
   :schema-flexibility :write})

(def ^:private kb-cfg store-cfg)

(def ^:private msgs-cfg
  "The room's messages store. Was the only store carrying `:crypto-hash?` +
   `:commit-graph?`, back when it alone was expected to hold the kontor book;
   those flags are now in `store-cfg` for every datom store, so this is a plain
   alias kept for call-site readability."
  store-cfg)

(def ^:private data-cfg store-cfg)

(defn- connect-data-store
  "Connect an agent-created :data store, tolerant of its stored schema-flexibility.
   New stores are :write (datahike's default); an agent may opt a store into :read.
   We don't record which per store, and datahike requires the connect config to
   match the stored one — so try :write first and fall back to :read on the specific
   config-mismatch, loading each store with the flexibility it was created with.
   (`:allow-unsafe-config` is NOT used: it loads the store but forces the passed
   flexibility, silently turning a :read store into :write.)"
  [scope]
  (let [cfg (store-cfg scope)]
    (try (d/connect cfg)
         (catch clojure.lang.ExceptionInfo e
           (if (= :config-does-not-match-stored-db (:type (ex-data e)))
             (d/connect (assoc cfg :schema-flexibility :read))
             (throw e))))))

(defn- kb-system-name   "Yggdrasil system id for a KB store path."  [path]
  (str "room-kb-" (.getName (io/file path))))
(defn- repo-system-name "Yggdrasil system id for a repo path."      [path]
  (str "room-repo-" (.getName (io/file path))))
(defn- msgs-system-name "Yggdrasil system id for a messages store path." [path]
  (str "room-msgs-" (.getName (io/file path))))

(defn msgs-chat-id
  "Deterministic `:chat/id` for a room's own messages store, so the room↔chat
   row is stable across restarts (the store impl scopes messages by it)."
  [slug]
  (java.util.UUID/nameUUIDFromBytes (.getBytes (str "dvergr-room-msgs|" slug))))

(defn- seed-msgs-store!
  "Create the per-room messages store (if absent), install the chat schema, and
   seed its single `:chat/*`+`:room/*` row so the DatahikeStore can scope messages
   by `:room/slug`→`:chat/id`. Idempotent."
  [path slug name]
  ;; RF5: schedules are a per-room project artifact — the (transparent)
  ;; :schedule/* schema rides along as :extra-schema so the room's reactive
  ;; scheduler (dvergr.rooms.scheduler) can store + fire them from this store.
  (sdh/provision! {:cfg (msgs-cfg path)
                   :extra-schema sched-schema/schema
                   :seed-tx [(merge (cschema/create-chat-entity
                                     {:id (msgs-chat-id slug) :title (or name slug)})
                                    {:room/slug slug :room/type :internal})]
                   :register? false})
  ;; Declare the messages fulltext (scriptum) secondary index once, after the
  ;; chat schema is installed. It's schema data in the store, so it forks with
  ;; the room; datahike maintains it on every message transact. Best-effort.
  (search-secondary/declare-message-fulltext! (d/connect (msgs-cfg path)) path))

(defn register-room-systems!
  "Register a room's messages store + KB (DatahikeSystems) + repo (GitSystem) as
   yggdrasil systems in the CURRENT execution context's composite, so they
   fork/merge with it and resolve fork-aware via `ygg/system`. Requires a bound
   execution context. Used by `provision-room!`."
  [msgs-path kb-path repo-path]
  ;; Schema was installed at creation (seed-msgs-store! / provision-room!) —
  ;; registration is a boot concern, so :schema? false keeps it connect+register.
  (sdh/provision! {:cfg (msgs-cfg msgs-path) :schema? false
                   :system-name (msgs-system-name msgs-path)})
  (sdh/provision! {:cfg (kb-cfg kb-path) :schema? false
                   :system-name (kb-system-name kb-path)})
  (ygg/register! (geschichte/create-system :scope repo-path
                                           :system-name (repo-system-name repo-path))))

(defn- register-system-into-current!
  "Register one registry system (`{:system/type :system/scope}`) into the bound
   ctx's composite. Best-effort."
  [{:system/keys [type scope]}]
  (try
    (case type
      :msgs (sdh/provision! {:cfg (msgs-cfg scope) :schema? false
                             :system-name (msgs-system-name scope)})
      :kb   (sdh/provision! {:cfg (kb-cfg scope) :schema? false
                             :system-name (kb-system-name scope)})
      :repo (ygg/register! (geschichte/create-system :scope scope
                                                     :system-name (repo-system-name scope)))
      ;; agent-created data DBs (see create-room-db!) — re-register so they survive
      ;; restart, same as the room's own KB/msgs. Custom connect (schema-flexibility
      ;; fallback), so pass the conn; never impose dvergr schema on agent stores.
      :data (sdh/provision! {:conn (connect-data-store scope) :schema? false
                             :system-name (str "room-data-" (.getName (io/file scope)))})
      nil)
    (catch Throwable _ nil)))

(defn hydrate-rooms!
  "Recreate each room's OWN execution context (RF5 S2) on daemon boot and
   register that room's systems INTO it — so per-room msgs/KB/repo survive a
   restart with the same per-room-composite isolation `provision-room!` set up.
   The `room-ctxs` map + composites are in-memory, so this runs at boot after the
   system DB is up. Requires a bound ctx (its `current-root` is the fork parent).
   Best-effort per room."
  []
  (doseq [{:room/keys [id]} (sdb/all-rooms)]
    (try
      (let [room-ctx (sctx/fork-context (rctx/current-root))]
        (binding [ec/*execution-context* room-ctx]
          (doseq [{:keys [system]} (sdb/systems-for-room id)]
            (register-system-into-current! system)))
        (swap! room-ctxs assoc id room-ctx))
      ;; Never swallow silently: a room that fails to hydrate has NO ctx → callers
      ;; fall back to the daemon ROOT ctx and lose all per-room isolation. Log loudly.
      (catch Throwable e
        ((requiring-resolve 'taoensso.telemere/log!)
         {:level :error :id :rooms/hydrate-failed
          :data {:room id :error (.getMessage e)}}
         "Room hydration failed — room will lack per-room isolation until restart")))))

(defn provision-room!
  "Create a room/project with its OWN execution context (RF5 S2) holding its own
   messages store + KB + repo as yggdrasil systems, recorded in the system DB and
   attached `:owner`. The room runs on this ctx (its bus/turn/composite), so a
   later `fork-context (:ctx room)` branches ONLY these systems. Idempotent on
   `slug`. Requires a bound ctx (whose `current-root` is the fork parent).
   Returns `{:room-id … :room-ctx …}` (`:existing? true` if already provisioned).

   RF5 S4: the registry fields (`type`/`telegram-chat-id`/`agent-ids`/`parent-slug`)
   are stored on the system-db room row — the sole registry. On an already-provisioned
   room they're upserted (so re-create refreshes participants/parent)."
  [{:keys [slug name owner-id type telegram-chat-id agent-ids parent-slug] :as opts}]
  (let [reg (select-keys opts [:type :telegram-chat-id :agent-ids :parent-slug])]
    (if-let [existing (sdb/room-by-slug slug)]
      (do (when (seq (filter val reg))
            (sdb/create-room! (merge {:slug slug :name (or name (:room/name existing))
                                      :owner-id owner-id} reg)))
          {:room-id (:room/id existing) :existing? true
           :room-ctx (room-ctx-for (:room/id existing))})
      (let [repo-path (scope-path (str (random-uuid)))
            kb-path   (scope-path (str (random-uuid)))
            msgs-path (scope-path (str (random-uuid)))
            _         (geschichte/ensure-repository! repo-path)
            ;; KB store carries ONLY the knowledge schema (not the chat schema).
            _         (sdh/provision! {:cfg (kb-cfg kb-path) :schema? false
                                       :extra-schema (kbs/knowledge-datahike-schema)
                                       :register? false})
            ;; Declare the KB fulltext (scriptum) secondary index over entity
            ;; title/summary/contexts — forks with the KB store, maintained on
            ;; every knowledge_add. Best-effort.
            _         (search-secondary/declare-kb-fulltext! (d/connect (kb-cfg kb-path)) kb-path)
            _         (seed-msgs-store! msgs-path slug name)
            repo-id   (sdb/register-system! {:type :repo :name (str slug "-repo")
                                             :scope repo-path :owner-id owner-id})
            kb-id     (sdb/register-system! {:type :kb :name (str slug "-kb")
                                             :scope kb-path :owner-id owner-id})
            msgs-id   (sdb/register-system! {:type :msgs :name (str slug "-msgs")
                                             :scope msgs-path :owner-id owner-id})
            room-id   (sdb/create-room! (merge {:slug slug :name name :owner-id owner-id
                                                :db-scope (str (random-uuid))} reg))
          ;; The room's OWN ctx — a fork of the daemon root. Register its systems
          ;; INTO it so its composite holds only them (scoped forks).
            room-ctx  (sctx/fork-context (rctx/current-root))]
        (binding [ec/*execution-context* room-ctx]
          (register-room-systems! msgs-path kb-path repo-path))
        (swap! room-ctxs assoc room-id room-ctx)
        (sdb/attach! room-id repo-id :owner)
        (sdb/attach! room-id kb-id   :owner)
        (sdb/attach! room-id msgs-id :owner)
        {:room-id room-id :kb-id kb-id :repo-id repo-id :msgs-id msgs-id :room-ctx room-ctx
         :repo-path repo-path :kb-path kb-path :msgs-path msgs-path}))))

;; ---------------------------------------------------------------------------
;; Resolvers — registry grants → fork-aware ygg systems, owner-first ordering
;; ---------------------------------------------------------------------------

(def ^:private perm-rank {:owner 0 :read-write 1 :read 2})

(defn- resolve-type [room-id type]
  (->> (sdb/systems-for-room-of-type room-id type)
       (sort-by #(perm-rank (:permission %) 9))
       (mapv (fn [{:keys [system permission]}]
               {:slug (:system/name system)
                :path (:system/scope system)
                :permission permission}))))

(defn room-repos
  "Ordered repos attached to a room (own/`:owner` first), each `{:slug :path :permission}`."
  [room-id]
  (resolve-type room-id :repo))

(defn room-kbs
  "Ordered KBs attached to a room (own first), each `{:slug :path :permission}`."
  [room-id]
  (resolve-type room-id :kb))

(defn room-msgs
  "Ordered messages stores attached to a room (own first), each `{:slug :path :permission}`."
  [room-id]
  (resolve-type room-id :msgs))

(defn room-msgs-path
  "Filesystem path of a room's OWN (writable) messages store, or nil."
  [room-id]
  (some->> (room-msgs room-id)
           (filter #(#{:owner :read-write} (:permission %)))
           first :path))

(defn room-msgs-store-id
  "Konserve store id of a room's OWN messages store — the sync scope a consumer
   binds to when it collapses its per-room database onto the room store. nil if
   the room isn't provisioned."
  [room-id]
  (some-> (room-msgs-path room-id) store-id))

(defn room-msgs-conn
  "Fork-aware conn to a room's OWN messages store — where the room store writes
   conversation. nil if the room isn't provisioned / not registered in this ctx."
  [room-id]
  (some-> (room-msgs-path room-id) msgs-system-name ygg/system :conn))

(defn msgs-conn-for-slug
  "Fork-aware conn to the OWN messages store of the system-DB room with `slug`,
   or nil if it isn't provisioned. The conn a Room's `:store` wraps."
  [slug]
  (when-let [r (sdb/room-by-slug slug)]
    (room-msgs-conn (:room/id r))))

(defn writable-kbs
  "The room's writable KBs, `:owner` first — every one `knowledge_add` MAY
   target, not just the one it does by default."
  [room-id]
  (filterv #(#{:owner :read-write} (:permission %)) (room-kbs room-id)))

(defn room-kb-conn
  "Fork-aware conn to a room's writable KB — where `knowledge_add` writes.

   With no `slug`, the default is the highest-precedence writable KB, which is
   the room's own. That default is the reason a room's wiki could be invisible
   to its agent: a room whose knowledge actually lives in an ATTACHED KB still
   wrote into its own empty one, and nothing said so. Passing a slug names the
   target explicitly; `writable-kbs` lists what is nameable."
  ([room-id] (room-kb-conn room-id nil))
  ([room-id slug]
   (let [kbs (writable-kbs room-id)]
     (some-> (if slug
               (first (filter #(= slug (:slug %)) kbs))
               (first kbs))
             :path kb-system-name ygg/system :conn))))

(defn room-kb-conns
  "All KB conns a room may READ (own + attached), each `{:conn :permission :slug}` —
   `knowledge_search` unions these. Fork-aware via ygg."
  [room-id]
  (vec (keep (fn [{:keys [path permission slug]}]
               (when-let [sys (ygg/system (kb-system-name path))]
                 {:conn (:conn sys) :permission permission :slug slug}))
             (room-kbs room-id))))

;; ---------------------------------------------------------------------------
;; Agent-created room data DBs — a room OWNS its KB + msgs; the sandbox can also
;; create additional datahike databases (`:type :data`) that register into the
;; room's composite (so they fork/merge/discard with the room) + a system-db grant
;; (so they survive restart + hydrate). Resolved fork-aware by their logical name.
;; ---------------------------------------------------------------------------

(defn- data-system-name [path] (str "room-data-" (.getName (io/file path))))

(defn room-data-dbs
  "The room's OWN created data DBs, each `{:slug :path :permission}` (`:slug` is the
   logical name the agent created it with)."
  [room-id] (resolve-type room-id :data))

(defn room-db-conn
  "Fork-aware conn to the room's data DB named `db-name` (a `:type :data` system),
   or nil. (KB/msgs have their own resolvers; this is for agent-created DBs.)"
  [room-id db-name]
  (some->> (room-data-dbs room-id)
           (filter #(= (name db-name) (:slug %)))
           first :path data-system-name ygg/system :conn))

(defn create-room-db!
  "Create a room-OWNED datahike DB `db-name`, register it into the CURRENT ctx's
   composite (forks/merges/discards with the room) + record its system-db grant
   (survives restart). Returns the fork-aware conn. Idempotent on (room, name).
   `schema` (optional) is transacted once; `schema-flexibility` defaults to :write
   — datahike's own default (schema-on-write): attrs must be declared, which gives
   typed values, refs, and `:db.unique/identity` so the store MERGES soundly with
   the room on fork (identity-keyed, not blind-union). Pass :read explicitly for a
   schema-free append-only scratch db (no conflict detection — divergent forks
   duplicate rather than merge). Requires a bound ctx (the room's)."
  [room-id db-name & {:keys [schema schema-flexibility owner-id] :or {schema-flexibility :write}}]
  (or (room-db-conn room-id db-name)
      (let [path (scope-path (str (random-uuid)))
            cfg  (assoc (data-cfg path) :schema-flexibility schema-flexibility)]
        (let [conn (sdh/provision! {:cfg cfg :schema? false :extra-schema schema
                                    :system-name (data-system-name path)})]
          ;; The GLOBAL system-db grant must NOT be written from inside a transient
          ;; fork — discarding the fork would then leave a grant that hydrate-rooms!
          ;; resurrects (P2). In a transient fork, record the pending grant FORK-
          ;; LOCALLY (ctx-state, discarded with the fork for free); merge-room
          ;; replays it, discard drops it + deletes the store. (reconcile-fork-grants!)
          (if (ec/get-state [:dvergr/transient-fork?])
            (ec/swap-state! [:dvergr/pending-grants]
                            (fn [v] (conj (or v [])
                                          {:room-id room-id :name (name db-name)
                                           :scope path :owner-id owner-id})))
            (sdb/attach! room-id
                         (sdb/register-system! {:type :data :name (name db-name)
                                                :scope path :owner-id owner-id})
                         :owner))
          conn))))

(defn gc-orphan-fork-branches!
  "Boot-time GC for abandoned virtual Geschichte workspace branches. Room forks
  are in-memory and do not survive restart, so every non-canonical Datahike
  workspace branch is orphaned after hydration."
  []
  (reduce
   (fn [n {:room/keys [id]}]
     (binding [ec/*execution-context* (or (room-ctx-for id)
                                          (ec/current-execution-context))]
       (+ n
          (reduce
           (fn [m {:keys [path]}]
             (if-let [system (ygg/system (repo-system-name path))]
               (let [conn (geschichte/gy-connection system)
                     branches (gworkspace/list conn)]
                 (doseq [branch branches]
                   (try (gworkspace/remove! conn branch)
                        (catch Throwable _ nil)))
                 (+ m (count branches)))
               m))
           0 (room-repos id)))))
   0 (sdb/all-rooms)))

(defn gc-stores!
  "Reclaim unreachable storage across EVERY persistent store: the system-db plus
   each room's whole workspace (kb + msgs datahike, repo git, agent-created data).
   Datahike's index is immutable — every transaction writes new index-node blobs
   and leaves the superseded ones orphaned, freed ONLY by an explicit, offline GC
   (never automatic) — so without this the stores grow without bound (a young
   `.dvergr` is a few MB; a long-lived one reached tens of GB of orphaned `.ksv`
   blobs). Safe to run live: datahike keeps every branch head + its history, and
   git prunes only unreachable objects past its grace.
     `opts` — forwarded to each adapter: `:remove-before <java.util.Date>` collapses
       datahike snapshots before it (default epoch = keep ALL history); `:dry-run?`
       reports without deleting. (No retention window ⇒ orphan garbage only.)
   Returns `{:rooms n :system-db <report>}`. Best-effort, isolated per room."
  ([] (gc-stores! {}))
  ([opts]
   (let [rb (or (:remove-before opts) (java.util.Date. 0))
         ;; system-db is a plain root datahike (the registry itself), not in any
         ;; ctx workspace — GC it directly. BOUNDARY deref with timeout (the api
         ;; returns datahike's async throwable-promise), mirroring the ygg adapter.
         sysdb (try (let [r (deref (d/gc-storage (sdb/get-conn) rb) 600000 ::timeout)]
                      {:reclaimed (if (= r ::timeout) :timeout
                                      (cond (number? r) r (counted? r) (count r)
                                            (seqable? r) (count (seq r)) :else 0))})
                    (catch Throwable e {:error (.getMessage e)}))
         rooms (atom 0)]
     ;; Each room runs on its OWN ctx whose composite workspace holds all its
     ;; systems — one coordinated `ygg/gc!` sweeps kb+msgs+repo+data together.
     (doseq [{:room/keys [id]} (sdb/all-rooms)]
       (binding [ec/*execution-context* (or (room-ctx-for id) (ec/current-execution-context))]
         (try (ygg/gc! opts) (swap! rooms inc) (catch Throwable _ nil))))
     {:rooms @rooms :system-db sysdb})))

(defn commit-fork-grants!
  "Replay a discarded-or-merged fork's PENDING data-DB grants into the GLOBAL
   system-db. Called from the `:on-merge` callback (run under the PARENT ctx) with
   the fork's pending-grant list — so a fork's agent-created DBs become durable room
   systems only once the fork is accepted (P2). Idempotent per (room, scope)."
  [pending-grants]
  (doseq [{:keys [room-id name scope owner-id]} pending-grants]
    (try
      (sdb/attach! room-id
                   (sdb/register-system! {:type :data :name name :scope scope :owner-id owner-id})
                   :owner)
      (catch Throwable e
        ((requiring-resolve 'taoensso.telemere/log!)
         {:level :warn :id :rooms/fork-grant-commit-failed
          :data {:room room-id :scope scope :error (.getMessage e)}}
         "Failed to commit a fork's deferred data-DB grant on merge")))))

(defn drop-fork-grants!
  "Discard a rejected fork's PENDING data-DB grants: delete the on-disk stores the
   fork created (they were never granted, so nothing references them) and drop the
   pending list. Called from the `:on-discard` callback (P2) so a discarded fork's
   scratch DBs don't linger / resurrect."
  [pending-grants]
  (doseq [{:keys [scope]} pending-grants]
    (try (d/delete-database (data-cfg scope)) (catch Throwable _))))

(defn delete-room-db!
  "Remove an agent-created data DB `db-name` from the room: detach + retract the
   grant + drop it from the composite + delete the store. KB/msgs are NOT
   deletable here. Returns true if a DB was removed."
  [room-id db-name]
  (when-let [{:keys [path]} (first (filter #(= (name db-name) (:slug %)) (room-data-dbs room-id)))]
    (try (ygg/unregister! (data-system-name path)) (catch Throwable _))
    (sdb/delete-system-by-scope! room-id path)
    (try (d/delete-database (data-cfg path)) (catch Throwable _))
    true))

(defn room-databases
  "Every datahike database attached to `room-id` as `{:name :type :permission}` —
   the room's own KB + messages store + any agent-created data DBs. This is the
   `dvergr.room/databases` discovery surface."
  [room-id]
  (vec (concat (map (fn [k] {:name (:slug k) :type :kb   :permission (:permission k)}) (room-kbs room-id))
               (map (fn [m] {:name (:slug m) :type :msgs :permission (:permission m)}) (room-msgs room-id))
               (map (fn [d] {:name (:slug d) :type :data :permission (:permission d)}) (room-data-dbs room-id)))))

(defn room-conn-by-name
  "Fork-aware conn to ANY datahike database attached to `room-id` resolved by its
   name — KB, messages store, or an agent-created data DB. nil if no match."
  [room-id db-name]
  (let [nm (name db-name)]
    (or (room-db-conn room-id nm)
        (some (fn [{:keys [slug path]}] (when (= nm slug) (:conn (ygg/system (kb-system-name path)))))
              (room-kbs room-id))
        (some (fn [{:keys [slug path]}] (when (= nm slug) (:conn (ygg/system (msgs-system-name path)))))
              (room-msgs room-id)))))

(defn room-load-roots
  "Ordered virtual Geschichte roots for the SCI load-fn — own first, attached
  after. Each descriptor is fork-aware and contains a Muschel FS, not a host
  filesystem path."
  [room-id]
  (->> (room-repos room-id)
       (keep (fn [{:keys [path]}]
               (when-let [system (ygg/system (repo-system-name path))]
                 (let [conn (geschichte/gy-connection system)]
                   {:id (geschichte/gy-workspace-id system)
                    :root "/"
                    :fs (geschichte/filesystem
                         {:system system :conn conn
                          :id (geschichte/gy-workspace-id system)
                          :repository {:conn conn :config (:config @conn)}})}))))
       vec))

(defn roots-for-slug
  "Load-roots for the system-DB room with `slug`, or nil if it isn't provisioned."
  [slug]
  (when-let [r (sdb/room-by-slug slug)]
    (room-load-roots (:room/id r))))

(defn kb-conn-for-slug
  "Fork-aware conn to the OWN (writable) KB of the system-DB room with `slug`,
   or nil if it isn't provisioned. The `:kb-conn` the knowledge tools write to."
  [slug]
  (when-let [r (sdb/room-by-slug slug)]
    (room-kb-conn (:room/id r))))

(defn room-mount-spec
  "Virtual Geschichte mount data for attached room repositories."
  [room-id]
  (->> (room-repos room-id)
       (keep (fn [{:keys [slug path permission]}]
               (when-let [system (ygg/system (repo-system-name path))]
                 [(str "/" slug)
                  (geschichte/filesystem
                   (let [conn (geschichte/gy-connection system)]
                     {:system system :conn conn
                      :id (geschichte/gy-workspace-id system)
                      :repository {:conn conn :config (:config @conn)}}))
                  (= permission :read)])))
       vec))

(defn- delete-tree! [path]
  (let [f (io/file path)]
    (when (.exists f)
      (doseq [c (reverse (file-seq f))] (.delete c)))))

(defn delete-room!
  "Remove a room: unregister + delete its OWN KB+repo systems (ygg `unregister!`
   + store deletion), retract the room + grants + owned systems from the registry.
   Attached (non-owned) systems are only detached, never deleted. Returns
   `{:deleted-room? :owned-systems-removed}`."
  [room-id]
  (let [owned (->> (sdb/systems-for-room room-id)
                   (filter #(= :owner (:permission %)))
                   (map :system))]
    ;; The systems live in the room's OWN ctx (RF5 S2), so unregister there.
    (binding [ec/*execution-context* (or (room-ctx-for room-id)
                                         (ec/current-execution-context))]
      (doseq [{:system/keys [type scope]} owned]
        (case type
          :repo (do (ygg/unregister! (repo-system-name scope))
                    (geschichte/delete-repository! scope)
                    (delete-tree! scope))
          :kb   (do (ygg/unregister! (kb-system-name scope))
                    (try (when (d/database-exists? (kb-cfg scope))
                           (d/delete-database (kb-cfg scope)))
                         (catch Throwable _ nil))
                    (delete-tree! scope))
          :msgs (do (ygg/unregister! (msgs-system-name scope))
                    (try (when (d/database-exists? (msgs-cfg scope))
                           (d/delete-database (msgs-cfg scope)))
                         (catch Throwable _ nil))
                    (delete-tree! scope))
          nil)))
    (swap! room-ctxs dissoc room-id)
    (sdb/delete-room! room-id (mapv :system/id owned))
    {:deleted-room? true :owned-systems-removed (count owned)}))
