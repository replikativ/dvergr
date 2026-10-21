(ns dvergr.system.rooms
  "Rooms as projects — provisioning + resolvers over the `dvergr.system.db`
   registry.

   A room's own KB (datahike) and repo (a Geschichte workspace) are registered as **yggdrasil
   systems** in the room's execution context composite, so they fork + merge
   with the room (as its messages store does) and resolve **fork-aware** via
   `ygg/system`. The registry (`dvergr.system.db`) records *which* systems a room
   owns/attaches and with what permission; this namespace turns that into the
   concrete, ctx-resolved stores the sandbox/tools use:

   - `room-kb-conn`     — a room's OWN writable KB conn (for `knowledge_add`).
   - `room-kb-conns`    — all KBs a room may read (own + attached).
   - `room-load-roots`  — ordered repo roots for the SCI load-fn.
   - `room-mount-spec`  — muschel composite-fs mount data.
   - `room-database-by-name` — any attached database by name, with whether the
                          sandbox may write it (`grant-permits?`; the host's
                          vocabulary through `set-grant-permits!`).

   Resolvers require a bound execution context (always true in a turn; tests bind
   one). No manual conn caching — yggdrasil owns resolution."
  (:require [datahike.api :as d]
            [clojure.java.io :as io]
            [dvergr.system.db :as sdb]
            [dvergr.system.room-preparation :as preparation]
            [dvergr.system.room-intent :as room-intent]
            [dvergr.kb.schema :as kbs]
            [dvergr.chat.schema :as cschema]
            [dvergr.resource :as resource]
            [dvergr.room.store :as room-store]
            [dvergr.search.secondary :as search-secondary]
            [dvergr.scheduler.schema :as sched-schema]
            [dvergr.runtime.ctx :as rctx]
            [dvergr.substrate.geschichte :as geschichte]
            [dvergr.substrate.repository-profile :as repository-profile]
            [geschichte.workspace :as gworkspace]
            [dvergr.substrate.paths :as paths]
            [dvergr.substrate.store-ids :as store-ids]
            [dvergr.substrate.datahike :as sdh]
            [dvergr.substrate.kontor-book :as kontor-book]
            [yggdrasil.adapters.datahike :as dh-adapter]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.impl.simple :as simple]
            [taoensso.telemere :as tel]
            [yggdrasil.gc :as ygg-gc]
            [yggdrasil.protocols :as ygg-protocols]))

;; room-id → the room's OWN execution context (RF5 S2). A `fork-context` of the
;; daemon root whose composite holds ONLY this room's systems (msgs/kb/repo), so
;; forking the room (`fork-context (:ctx room)`) branches just those — not the
;; whole daemon. A Tier-3 daemon-global atom (it must NOT itself fork). The room
;; RUNS on this ctx (bus/turn/composite = ISOLATION); its identity in the
;; registry is Tier-1 shared (root). See `dvergr.runtime.ctx`.
(defonce ^:private room-ctxs (atom {}))
(defonce ^:private room-hydration-errors (atom {}))
;; Native HOST bootstrap coordination, not forkable semantic state. Engine/
;; child callers are refused before acquiring this process-local monitor.
(defonce ^:private profiled-creation-lock (Object.))

(defn room-ctx-for
  "The room's own execution context (created by provision/hydrate), or nil.
   A known hydration failure is not allowed to fall back to the daemon root."
  [room-id]
  (if-let [error (get @room-hydration-errors room-id)]
    (throw (ex-info "Room is unavailable because hydration failed"
                    {:type ::room-hydration-failed :room/id room-id}
                    error))
    (get @room-ctxs room-id)))

(declare clear-room-ctxs!)

(defn- retained-preparation? [error]
  (or (preparation/recovery-required? error) (geschichte/initialization-recovery-required? error)))

(defn retain-room-registration-failure!
  "INTERNAL HOST failure sink, not a retry or authorization API. Preserve the
   exact context and original constructor error in the existing ownership gate.
   A live constructor may already have started pumps before it throws."
  [room-id ctx error]
  (let [held (preparation/retain-registration-failure room-id ctx error)
        [before _] (swap-vals! room-hydration-errors assoc room-id held)
        previous (get before room-id)]
    ;; Never drop a predecessor owner if another lifecycle operation was also
    ;; uncertain. No compensating teardown or attempt to reinterpret its cause.
    (when (and previous (not (identical? previous error))) (.addSuppressed held previous))
    held))

(defn- assert-no-retained-preparation! []
  (when (some retained-preparation? (vals @room-hydration-errors))
    (throw (ex-info "Room bootstrap ownership must be reconciled before reset or release"
                    {:type ::pending-room-preparation}))))

(defn recover-room-preparation!
  "HOST ONLY: finish positively safe cleanup of one withheld Room attempt.
   Success permits a fresh hydration; uncertain dispatch/publication still
   requires explicit reconciliation and cannot be bypassed by clearing state."
  [room-id]
  (let [error (get @room-hydration-errors room-id)]
    (if (geschichte/initialization-recovery-required? error)
      (geschichte/recover-initialization! error)
      (preparation/recover! error))
    (swap! room-hydration-errors dissoc room-id)
    true))

(defn release-room-stores!
  "Release the Datahike connection of every store registered in a room's
   context, all of its leases, and drop the room contexts: the daemon owns
   them and is stopping. Datahike caches connections by store id, so a
   connection left open would be handed out again to a same-process restart.
   Best-effort per store; call once everything that writes has stopped."
  []
  (assert-no-retained-preparation!)
  (doseq [[_ ctx] @room-ctxs]
    (binding [ec/*execution-context* ctx]
      (doseq [[_ system] (try (ygg/registered-systems) (catch Throwable _ {}))
              :let [conn (:conn system)]
              :when conn]
        (try (d/release conn true) (catch Throwable _ nil)))))
  (clear-room-ctxs!))

(defn clear-room-ctxs!
  "Drop all per-room execution contexts. Call on daemon stop! so a same-process
   restart doesn't reuse stale forked ctxs pointing at the previous daemon root."
  []
  (assert-no-retained-preparation!)
  (reset! room-ctxs {})
  (reset! room-hydration-errors {}))

(defn- scope-path [scope] (str (io/file (paths/systems-dir) scope)))

(defn legacy-store-id
  "The id versions before store id files derived from a room store's `path`."
  [path]
  (store-ids/path-derived-default-charset path))

(defn store-id
  "Konserve store id for a room store `path`: the id recorded beside the store
   (`dvergr.substrate.store-ids`), so it is stable across restarts and survives
   moving the home. Not derivable from the path: a store created by an older
   version has a path-derived id, a new one a random id.

   Public because it is the konserve-sync SCOPE: a consumer that collapses its
   own per-room database onto the room store (simmis binds
   `:room/content-db-scope` to it) must name the same store the writer uses, and
   must not re-derive the formula on its own."
  [path]
  (store-ids/store-id path legacy-store-id))

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
    (try (sdh/connect cfg)
         (catch clojure.lang.ExceptionInfo e
           (if (= :config-does-not-match-stored-db (:type (ex-data e)))
             (sdh/connect (assoc cfg :schema-flexibility :read))
             (throw e))))))

(defn- book-system-name
  "Yggdrasil system id for a room's kontor book at `path`."
  [path] (str "room-book-" (.getName (io/file path))))

(defn kb-system-name   "Yggdrasil system id for a KB store path."  [path]
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

(defn- ensure-msgs-resources!
  "Install/re-register the conserved-resource kernel for one messages store."
  [conn]
  (when-let [[slug chat-id]
             (d/q '[:find [?slug ?chat-id]
                    :where
                    [?room :room/slug ?slug]
                    [?room :chat/id ?chat-id]]
                  @conn)]
    (resource/install-connection! conn (room-store/slug->room-id slug) chat-id))
  conn)

(defn- seed-msgs-store!
  "Create the per-room messages store (if absent), install the chat schema, and
   seed its single `:chat/*`+`:room/*` row so the DatahikeStore can scope messages
   by `:room/slug`→`:chat/id`. Idempotent."
  [path slug name]
  ;; RF5: schedules are a per-room project artifact — the (transparent)
  ;; :schedule/* schema rides along as :extra-schema so the room's reactive
  ;; scheduler (dvergr.rooms.scheduler) can store + fire them from this store.
  (-> (sdh/provision! {:cfg (msgs-cfg path)
                       :extra-schema sched-schema/schema
                       :seed-tx [(merge (cschema/create-chat-entity
                                         {:id (msgs-chat-id slug) :title (or name slug)})
                                        {:room/slug slug :room/type :internal})]
                       :register? false})
      ensure-msgs-resources!)
  ;; Declare the messages fulltext (scriptum) secondary index once, after the
  ;; chat schema is installed. It's schema data in the store, so it forks with
  ;; the room; datahike maintains it on every message transact. Best-effort.
  (search-secondary/declare-message-fulltext! (sdh/connect (msgs-cfg path)) path))

(defn register-room-systems!
  "Register a room's messages store + KB (DatahikeSystems) + repo (GitSystem) as
   yggdrasil systems in the CURRENT execution context's composite, so they
   fork/merge with it and resolve fork-aware via `ygg/system`. Requires a bound
   execution context. Used by `provision-room!`."
  [msgs-path kb-path repo-path]
  ;; Schema was installed at creation (seed-msgs-store! / provision-room!) —
  ;; registration is a boot concern, so :schema? false keeps it connect+register.
  (-> (sdh/provision! {:cfg (msgs-cfg msgs-path) :schema? false
                       :system-name (msgs-system-name msgs-path)})
      ensure-msgs-resources!)
  (sdh/provision! {:cfg (kb-cfg kb-path) :schema? false
                   :system-name (kb-system-name kb-path)})
  (ygg/register! (geschichte/create-system :scope repo-path
                                           :system-name (repo-system-name repo-path))))

(defn- register-system-into-current!
  "Register one registry system (`{:system/type :system/scope}`) into the bound
   ctx's composite. Optional systems remain best-effort; a messages-store
   resource-governance failure is fatal because publishing that Room would
   silently remove its mandatory accounting boundary."
  [{:system/keys [type scope]}]
  (try
    (case type
      :msgs (let [conn (sdh/provision! {:cfg (msgs-cfg scope)
                                        :schema? false
                                        :register? false})]
              (try
                ;; Establish mandatory accounting governance before publishing
                ;; this connection as a fork-visible Yggdrasil system. A failed
                ;; install therefore cannot leave a partially registered Room
                ;; context behind.
                (ensure-msgs-resources! conn)
                (sdh/provision! {:conn conn :schema? false
                                 :system-name (msgs-system-name scope)})
                (catch Throwable error
                  ;; Governance is store-global and idempotent, not owned by
                  ;; this connection attempt. It may protect another live Room
                  ;; sharing the store, so release only this connection.
                  (d/release conn)
                  (throw error))))
      :kb   (sdh/provision! {:cfg (kb-cfg scope) :schema? false
                             :system-name (kb-system-name scope)})
      :repo (ygg/register! (geschichte/create-system :scope scope
                                                     :system-name (repo-system-name scope)))
      ;; agent-created data DBs (see create-room-db!) — re-register so they survive
      ;; restart, same as the room's own KB/msgs. Custom connect (schema-flexibility
      ;; fallback), so pass the conn; never impose dvergr schema on agent stores.
      ;; the room's business book settles by replay (kontor ADR-172), so it is
      ;; registered with its settlement hooks, never as a plain datom store
      :book (kontor-book/register-book!
             (sdh/provision! {:cfg (store-cfg scope) :schema? false :register? false})
             (book-system-name scope))
      :data (sdh/provision! {:conn (connect-data-store scope) :schema? false
                             :system-name (str "room-data-" (.getName (io/file scope)))})
      nil)
    (catch Throwable error
      (when (= :msgs type)
        (throw (ex-info "Messages resource kernel failed to hydrate"
                        {:type ::resource-hydration-failed
                         :system/type type
                         :system/scope scope}
                        error)))
      nil)))

(defn- fork-room-ctx
  "A new room's own execution context: a fork of the daemon root that sees NONE
   of the root's systems. A raw `fork-context` would fork every system registered
   on the root, and an embedder may register there (simmis registers each room's
   store as a `kb:` system for its own branching), so every room, and every Run
   world forked from it, would branch other rooms' stores. The room registers its
   own systems into this context afterwards."
  []
  (binding [ec/*execution-context* (rctx/current-root)]
    (:child-ctx (ygg/fork! {:systems :none :purpose :room}))))

(defn- needs-hydration? [room-id]
  (when-let [error (get @room-hydration-errors room-id)]
    (when (retained-preparation? error)
      (throw (ex-info "Reconcile retained room bootstrap ownership before retrying hydration"
                      {:type ::pending-room-preparation :room/id room-id} error))))
  (if-let [existing (get @room-ctxs room-id)]
    (do
      ;; A transport-only restart keeps the room runtime alive. Replacing its
      ;; context would strand the existing registry Room/bus on the old fork.
      ;; A different daemon root, however, must explicitly stop/release its
      ;; predecessor; silently reusing or replacing those handles is unsafe.
      (when-not (identical? (rctx/current-root) (rctx/root-ctx existing))
        (throw (ex-info "Release the previous daemon's room stores before hydration"
                        {:type ::stale-room-context :room/id room-id})))
      (boolean (get @room-hydration-errors room-id)))
    true))

(defn canonical-messages-identity?
  "Pure bounded identity check for a prepared messages snapshot. Exactly one
   room marker must own its expected chat ID. Other agent working ChatContexts
   legitimately have chat IDs in this same store, but no room marker."
  [snapshot slug]
  (let [expected (msgs-chat-id slug)
        identities (into [] (comp (map :e) (distinct) (take 2))
                         (concat (take 2 (d/datoms snapshot :aevt :room/slug))
                                 (take 2 (d/datoms snapshot :avet :chat/id expected))))]
    (= [{:room/slug slug :chat/id expected}]
       (mapv #(d/pull snapshot [:room/slug :chat/id] %) identities))))

(defn- mandatory-system-spec
  [{:system/keys [type scope repository-profile]} repository-profiles slug]
  (case type
    :msgs {:id (msgs-system-name scope)
           :acquire! #(sdh/connect (msgs-cfg scope))
           :prepare! (fn [conn]
                       (let [snapshot @conn]
                         (when-not (canonical-messages-identity? snapshot slug)
                           (throw (ex-info "Mandatory messages store has a missing, foreign or ambiguous room/chat identity"
                                           {:type ::missing-message-identity :scope scope}))))
                       (ensure-msgs-resources! conn)
                       (dh-adapter/create conn {:system-name (msgs-system-name scope)}))}
    :repo (let [cfg (geschichte/repository-config scope)]
            {:id (repo-system-name scope)
             :acquire! (fn []
                         (when-not (d/database-exists? cfg)
                           (throw (ex-info "Profiled repository is missing; hydration cannot initialize it"
                                           {:type ::missing-profiled-repository :scope scope})))
                         (sdh/connect cfg))
             :prepare! #(geschichte/prepare-system! % {:scope scope :system-name (repo-system-name scope)
                                                       :repository-profile repository-profile
                                                       :repository-profiles repository-profiles})})))

(defn- hydrate-profiled-room! [id room-ctx systems repository-profiles slug]
  (let [;; Attachment permissions produce several rows for one registry system.
        ;; Collapse registry identity, NOT acquired connection identity/leases.
        systems (vals (reduce (fn [by-id system]
                                (let [id (:system/id system)]
                                  (when (and (contains? by-id id) (not= system (get by-id id)))
                                    (throw (ex-info "Conflicting registry rows for one system"
                                                    {:type ::conflicting-system-rows :system/id id})))
                                  (assoc by-id id system))) {} systems))
        mandatory? #(or (= :msgs (:system/type %)) (contains? % :system/repository-profile))
        mandatory (vec (sort-by #(if (= :msgs (:system/type %)) 0 1) (filter mandatory? systems)))
        optional (remove mandatory? systems)]
    (when-not (= 1 (count (filter #(= :msgs (:system/type %)) mandatory)))
      (throw (ex-info "A profiled Room requires exactly one mandatory messages system"
                      {:type ::missing-mandatory-messages :room/id id})))
    (preparation/with-prepared!
      room-ctx (mapv #(mandatory-system-spec % repository-profiles slug) mandatory)
      (fn []
        (swap! room-ctxs assoc id room-ctx)
        (swap! room-hydration-errors dissoc id)))
    ;; Existing optional best-effort adapters are not part of mandatory
    ;; readiness. Keep their native I/O outside the publication callback.
    (doseq [system optional] (register-system-into-current! system))))

(defn hydrate-rooms!
  "Recreate each room's OWN execution context (RF5 S2) on daemon boot and
   register that room's systems INTO it — so per-room msgs/KB/repo survive a
   restart with the same per-room-composite isolation `provision-room!` set up.
   The `room-ctxs` map + composites are in-memory, so this runs at boot after the
   system DB is up. Requires a bound ctx (its `current-root` is the fork parent).
   Repeating hydration on the SAME daemon root preserves existing room
   contexts/handles (e.g. an embedder's web-only restart). Failed/missing rooms
   may retry. A new daemon root requires release-room-stores! first; hydration
   refuses to silently replace its predecessor's live contexts. Best-effort
   per newly hydrated room."
  ([] (hydrate-rooms! {}))
  ([{:keys [repository-profiles]}]
   (doseq [{:room/keys [id slug] :as room-intent} (sdb/all-rooms)
           :when (needs-hydration? id)]
     (let [room-ctx (fork-room-ctx)]
       (try
         (when (and (or (contains? room-intent :room/provisioning-state)
                        (contains? room-intent :room/provisioning-version))
                    (not (and (= 1 (:room/provisioning-version room-intent))
                              (= :ready (:room/provisioning-state room-intent)))))
           (throw (ex-info "Room creation intent is not ready for ordinary hydration"
                           {:type ::unready-room-intent :room/id id})))
         (binding [ec/*execution-context* room-ctx]
          ;; The mandatory messages/accounting system is always first. If it
          ;; cannot be governed, the fresh fork has not registered any Room
          ;; systems and can be safely withheld without a separate fork-close
          ;; lifecycle (Spindel forks share their root lifecycle).
           (let [systems (mapv :system (sdb/systems-for-room id))]
             (if (some #(contains? % :system/repository-profile) systems)
               (hydrate-profiled-room! id room-ctx systems repository-profiles slug)
               (do
                 (doseq [system (sort-by #(if (= :msgs (:system/type %)) 0 1) systems)]
                   (register-system-into-current! system))
                 (swap! room-ctxs assoc id room-ctx)
                 (swap! room-hydration-errors dissoc id)))))
         (catch Throwable e
           (swap! room-hydration-errors assoc id e)
          ;; Fork contexts share the root lifecycle and are not independently
          ;; closeable. Mandatory messages governance is established before its
          ;; system is registered, so this fresh unpublished context has no
          ;; accounting system/capability to retain when that step fails.
           ((requiring-resolve 'taoensso.telemere/log!)
            {:level :error :id :rooms/hydrate-failed
             :data {:room id :error (.getMessage e)}}
            "Room hydration failed — context was withheld")))))))

(defn provision-profiled-room!
  "HOST ONLY explicit creation, not a transport/authorization API. Options are
   the closed room-intent/create! input; trusted policies are a separate host
   argument. Requires a bound root context and a non-engine host thread.

   Records complete pending control-store intent first, prepares all owned
   native adapters, CASes readiness, then publishes the context. Failure keeps
   intent/data and the original recovery owner under its known room ID. An
   existing slug is NEVER success or automatic retry. Fulltext is deliberately
   not started here: async builds need separately owned lifecycle handling."
  [options trusted-profiles]
  (when (or (simple/on-drain-thread?) (not (ec/execution-context-bound?))
            (not (identical? (ec/current-execution-context) (rctx/current-root))))
    (throw (ex-info "Profiled Room creation requires a bound host root thread"
                    {:type ::requires-host-root})))
  (when-not (keyword? (:repository-profile options))
    (throw (ex-info "Explicit repository profile is required" {:type ::invalid-repository-profile})))
  ;; Validate the host policy BEFORE creating even control-store intent.
  (repository-profile/resolve! trusted-profiles (:repository-profile options))
  (locking profiled-creation-lock
    (let [{:keys [room systems basis]} (room-intent/create! options)
          id (:room/id room) slug (:room/slug room)
          by-type (into {} (map (juxt :system/type identity)) systems)
          repo-path (get-in by-type [:repo :system/scope])
          msgs-path (get-in by-type [:msgs :system/scope])
          kb-path (get-in by-type [:kb :system/scope])
          profile (:repository-profile options)]
      (try
        ;; Initialization has its OWN reviewed opaque recovery owner. Its
        ;; failure must also stay under this already durable Room identity.
        (geschichte/ensure-repository! repo-path {:repository-profile profile :repository-profiles trusted-profiles})
        (let [ctx (fork-room-ctx)
              acquire-new! (fn [cfg]
                             (when (d/database-exists? cfg)
                               (throw (ex-info "New Room store scope is already populated"
                                               {:type ::creation-scope-populated})))
                             (sdh/connect! cfg))
              specs [{:id (msgs-system-name msgs-path)
                      :acquire! #(acquire-new! (msgs-cfg msgs-path))
                      :prepare! (fn [conn]
                                  (sdh/provision! {:conn conn :register? false :extra-schema sched-schema/schema
                                                   :seed-tx [(merge (cschema/create-chat-entity
                                                                     {:id (msgs-chat-id slug) :title (:room/name room)})
                                                                    {:room/slug slug :room/type :internal})]})
                                  (ensure-msgs-resources! conn)
                                  (dh-adapter/create conn {:system-name (msgs-system-name msgs-path)}))}
                     (mandatory-system-spec (get by-type :repo) trusted-profiles slug)
                     {:id (kb-system-name kb-path)
                      :acquire! #(acquire-new! (kb-cfg kb-path))
                      :prepare! (fn [conn]
                                  (sdh/provision! {:conn conn :register? false :schema? false
                                                   :extra-schema (kbs/knowledge-datahike-schema)})
                                  (dh-adapter/create conn {:system-name (kb-system-name kb-path)}))}]]
          (preparation/with-prepared!
            ctx specs
            (fn []
              (room-intent/mark-ready! basis)
              (swap! room-ctxs assoc id ctx)
              (swap! room-hydration-errors dissoc id)
              {:room-id id :room-ctx ctx
               :repo-id (get-in by-type [:repo :system/id]) :repo-path repo-path
               :msgs-id (get-in by-type [:msgs :system/id]) :msgs-path msgs-path
               :kb-id (get-in by-type [:kb :system/id]) :kb-path kb-path})))
        (catch Throwable error
          (swap! room-hydration-errors assoc id error)
          (throw error))))))

(defn provision-room!
  "Create a room/project with its OWN execution context (RF5 S2) holding its own
   messages store + KB + repo as yggdrasil systems, recorded in the system DB and
   attached `:owner`. The room runs on this ctx (its bus/turn/composite), so a
   later `fork-context (:ctx room)` branches ONLY these systems. Idempotent on
   `slug`. Requires a bound ctx (whose `current-root` is the fork parent).
   Returns `{:room-id … :room-ctx …}` (`:existing? true` if already provisioned).

   `:repo-source` (a local checkout or Git URL, with `:repo-branch`) makes the
   room's repository a clone of it rather than of the sandbox stdlib.

   RF5 S4: the registry fields (`type`/`telegram-chat-id`/`agent-ids`/`parent-slug`)
   are stored on the system-db room row — the sole registry. On an already-provisioned
   room they're upserted (so re-create refreshes participants/parent)."
  [{:keys [slug name owner-id type telegram-chat-id agent-ids parent-slug repo-source repo-branch] :as opts}]
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
            ;; A room imported from a repository (`:repo-source`) starts as a
            ;; clone of it instead of the sandbox stdlib, and fails loudly.
            _         (geschichte/ensure-repository!
                       repo-path
                       (if repo-source
                         (cond-> {:source repo-source :fallback? false}
                           repo-branch (assoc :branch repo-branch))
                         {}))
            ;; KB store carries ONLY the knowledge schema (not the chat schema).
            _         (sdh/provision! {:cfg (kb-cfg kb-path) :schema? false
                                       :extra-schema (kbs/knowledge-datahike-schema)
                                       :register? false})
            ;; Declare the KB fulltext (scriptum) secondary index over entity
            ;; title/summary/contexts — forks with the KB store, maintained on
            ;; every knowledge_add. Best-effort.
            _         (search-secondary/declare-kb-fulltext! (sdh/connect (kb-cfg kb-path)) kb-path)
            _         (seed-msgs-store! msgs-path slug name)
            repo-id   (sdb/register-system! {:type :repo :name (str slug "-repo")
                                             :scope repo-path :owner-id owner-id})
            kb-id     (sdb/register-system! {:type :kb :name (str slug "-kb")
                                             :scope kb-path :owner-id owner-id})
            msgs-id   (sdb/register-system! {:type :msgs :name (str slug "-msgs")
                                             :scope msgs-path :owner-id owner-id})
            room-id   (sdb/create-room! (merge {:slug slug :name name :owner-id owner-id
                                                :db-scope (str (random-uuid))
                                                :default-repository-id repo-id} reg))
          ;; The room's OWN ctx — a fork of the daemon root. Register its systems
          ;; INTO it so its composite holds only them (scoped forks).
            room-ctx  (fork-room-ctx)]
        (binding [ec/*execution-context* room-ctx]
          (register-room-systems! msgs-path kb-path repo-path))
        (swap! room-ctxs assoc room-id room-ctx)
        (swap! room-hydration-errors dissoc room-id)
        (sdb/attach! room-id repo-id :owner)
        (sdb/attach! room-id kb-id   :owner)
        (sdb/attach! room-id msgs-id :owner)
        {:room-id room-id :kb-id kb-id :repo-id repo-id :msgs-id msgs-id :room-ctx room-ctx
         :repo-path repo-path :kb-path kb-path :msgs-path msgs-path}))))

;; ---------------------------------------------------------------------------
;; Resolvers — registry grants → fork-aware ygg systems, owner-first ordering
;; ---------------------------------------------------------------------------

(def ^:private perm-rank {:owner 0 :read-write 1 :read 2})

;; What a grant's permission lets the room do with the system. dvergr's own
;; vocabulary is :owner, :read-write and :read; a host that records grants in
;; the same system DB may use more words (simmis adds :merge), so the host
;; answers for its vocabulary through `set-grant-permits!`.

(def default-write-permissions
  "The permissions that let a room write a system, when no host hook is set."
  #{:owner :read-write})

(defonce ^:private grant-permits-hook (atom nil))

(defn set-grant-permits!
  "Host hook: `f` is `(fn [permission action] -> boolean)`, asked whether a
   grant with `permission` (the `:grant/permission` keyword) lets the room do
   `action` to the attached system. dvergr asks `:write` (transact a database
   from the sandbox, the default KB `knowledge_add` targets, GC of the
   system's storage). The hook answers for every permission, dvergr's own
   included; an unknown one should answer false. nil restores the default,
   `default-write-permissions`."
  [f]
  (reset! grant-permits-hook f))

(defonce ^:private trunk-write-policy-hook (atom nil))

(defn set-trunk-write-policy!
  "Host hook: `f` is `(fn [room-id system-id] -> :fork-only | nil)`.
   Both ids are registry ids. :fork-only makes trunk handles read-only;
   forks still write and merge, subject to grants. nil removes the policy."
  [f]
  (reset! trunk-write-policy-hook f))

(defn trunk-write-refusal
  "The refusal for writing registry system `system-id` in the current world,
   or nil. An isolated Room marked by `forks/fork?` is unaffected."
  [room-id system-id]
  (when-let [policy @trunk-write-policy-hook]
    (when (and (= :fork-only (policy room-id system-id))
               (not (and (ec/execution-context-bound?)
                         (some (fn [room]
                                 (and ((requiring-resolve 'dvergr.rooms.forks/fork?) room)
                                      (not (identical? (:ctx room)
                                                       (:ctx ((requiring-resolve 'dvergr.room.registry/lookup)
                                                              (:parent-id room)))))
                                      (rctx/descendant-context?
                                       (ec/current-execution-context) (:ctx room))))
                               ((requiring-resolve 'dvergr.room.registry/list-rooms))))))
      "this room's workflow writes through forks")))

(defn effect-write-refusal
  "The trunk policy refusal at an effect gate. File effects target repos;
   database effects target databases. Opaque tools and native/process code
   may write any attached system. Merge and conversation posting stay available."
  [room-id {:keys [effect resource]}]
  (when (and room-id @trunk-write-policy-hook)
    (let [types (cond
                  (contains? #{:fs/write :fs/delete :fs/mkdir :fs/move :fs/copy
                               :git/add :git/commit :room/write} effect) #{:repo}
                  (= :db/transact effect) #{:kb :data}
                  (contains? #{:tool/call :eval/native :process/run :room/gc
                               :db/create :db/delete} effect) #{:kb :data :repo :book}
                  :else #{})]
      (some (fn [{:keys [system]}]
              (when (and (contains? types (:system/type system))
                         (or (nil? (:system-id resource))
                             (= (:system-id resource) (:system/id system))))
                (trunk-write-refusal room-id (:system/id system))))
            (sdb/systems-for-room room-id)))))

(defn room-effect-write-refusal
  "The policy refusal for an effect acting on a Room. No policy means no
   registry access, preserving host calls on rooms without provisioned stores."
  [room effect]
  (when (and room @trunk-write-policy-hook)
    (effect-write-refusal (:room/id (sdb/room-by-slug
                                     (or (some-> room :meta deref :origin-slug) (:slug room))))
                          effect)))

(defn grant-permits?
  "Whether a grant with `permission` lets the room do `action` (`:write`) to
   the attached system: the host hook's answer (`set-grant-permits!`), else
   membership in `default-write-permissions`."
  [permission action]
  (boolean
   (if-let [f @grant-permits-hook]
     (f permission action)
     (case action
       :write (contains? default-write-permissions permission)
       false))))

(defn- resolve-type [room-id type]
  (->> (sdb/systems-for-room-of-type room-id type)
       (sort-by #(perm-rank (:permission %) 9))
       (mapv (fn [{:keys [system permission]}]
               {:slug (:system/name system)
                :path (:system/scope system)
                :system-id (:system/id system)
                :permission permission}))))

(defn room-repos
  "Ordered repos attached to a room (own/`:owner` first), each `{:slug :path :permission}`."
  [room-id]
  (resolve-type room-id :repo))

(defn default-workspace
  "Host-only canonical default workspace, never current-system's attachment
   fallback. Requires an explicit owned registry link and exactly one matching
   Geschichte runtime in the room's OWN context. Returns live handles; retain
   only registry-system/runtime-system and exact DB target metadata as evidence.
   Currently supports the provisioner's file-backed default repositories.

   The explicit registry-db arity uses only that immutable registry snapshot
   for ownership and physical-alias checks, including in a serialized writer.
   Runtime checks still inspect the live room context; this is not a lease."
  ([room-id]
   ;; Preserve invalid-input refusal before touching an ambient connection.
   (default-workspace (when (uuid? room-id) @(sdb/get-conn)) room-id))
  ([registry-db room-id]
   (when-not (uuid? room-id)
     (throw (ex-info "Exact room UUID required" {:type ::invalid-room})))
   (let [registered (sdb/default-repository registry-db room-id)
         ctx (room-ctx-for room-id)]
     (when-not registered
       (throw (ex-info "Room default repository is not configured or no longer owned"
                       {:type ::default-repository-unconfigured :room/id room-id})))
     (when-not ctx
       (throw (ex-info "Room default workspace is not hydrated"
                       {:type ::default-workspace-unavailable :room/id room-id})))
    ;; Raw locators are not physical identities: dot segments and symlinks may
    ;; give two registry entries the same directory. Use the SAME registry
    ;; snapshot as the ownership check, and refuse ambiguous physical owners.
     (let [canonical (.getCanonicalPath (io/file (:system/scope registered) "datahike"))
           owners (filterv
                   (fn [{:system/keys [type scope]}]
                     (and (= :repo type) (string? scope)
                          (= canonical (.getCanonicalPath (io/file scope "datahike")))))
                   (sdb/all-systems registry-db))]
       (when-not (and (= 1 (count owners))
                      (= (:system/id registered) (:system/id (first owners))))
         (throw (ex-info "Default repository physical ownership is ambiguous"
                         {:type ::default-workspace-mismatch :room/id room-id}))))
     (binding [ec/*execution-context* ctx]
       (let [expected (repo-system-name (:system/scope registered))
             matches (filterv #(= expected (str (ygg-protocols/system-id %)))
                              (vals (ygg/registered-systems)))]
         (when-not (and (= 1 (count matches))
                        (= :geschichte (ygg-protocols/system-type (first matches))))
           (throw (ex-info "Default repository runtime identity is missing or ambiguous"
                           {:type ::default-workspace-unavailable :room/id room-id})))
         (let [system (first matches) conn (geschichte/gy-connection system)
               config (when conn (:config @conn))
               path (get-in config [:store :path])
               expected-path (.getCanonicalPath (io/file (:system/scope registered) "datahike"))]
           (when-not (and (= :file (get-in config [:store :backend]))
                          (uuid? (get-in config [:store :id]))
                          (keyword? (:branch config)) (string? path)
                          (= expected-path (.getCanonicalPath (io/file path))))
             (throw (ex-info "Default workspace does not match its registered repository"
                             {:type ::default-workspace-mismatch :room/id room-id})))
           {:registry-system (:system/id registered) :runtime-system expected
            :system system :conn conn :id (geschichte/gy-workspace-id system)
            :repository {:conn conn :config config :workspace-branch (:branch config)}}))))))

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
  (filterv #(grant-permits? (:permission %) :write) (room-kbs room-id)))

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

(defn room-book-conn
  "Fork-aware conn to the room's kontor book, or nil when it has none."
  [room-id]
  (some->> (resolve-type room-id :book) first :path book-system-name ygg/system :conn))

(defn create-room-book!
  "Create the room's kontor business book, register it into the CURRENT ctx's
   composite as a book that settles by replay (`dvergr.substrate.kontor-book`)
   and record its system-db grant. Returns the fork-aware conn. Idempotent per
   room. Requires a bound ctx (the room's)."
  [room-id & {:keys [owner-id]}]
  (or (room-book-conn room-id)
      (let [path (scope-path (str (random-uuid)))
            conn (sdh/provision! {:cfg (store-cfg path) :schema? false :register? false})]
        (kontor-book/register-book! conn (book-system-name path))
        (if (ec/get-state [:dvergr/transient-fork?])
          (ec/swap-state! [:dvergr/pending-grants]
                          (fn [v] (conj (or v [])
                                        {:room-id room-id :name "book" :type :book
                                         :scope path :owner-id owner-id})))
          (sdb/attach! room-id
                       (sdb/register-system! {:type :book :name "book"
                                              :scope path :owner-id owner-id})
                       :owner))
        (room-book-conn room-id))))

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

(defonce ^:private storage-gc-homes
  ;; the homes (state roots) whose daemon enabled deleting GC
  (atom #{}))

(defn enable-storage-gc!
  "Allow storage GC that deletes (`gc-stores!`, `gc-room!` without
   `:dry-run?`). Off by default: datahike's `gc-storage` fails open (an
   incomplete mark, e.g. a branch walk dying on an Error, made its keep-set
   `#{:branches}`, and the sweep deleted every other key of a system DB:
   dev.simm.is, 2026-09-29). Turn it on only with a datahike that fails closed
   (datahike#1117) and a backup. The daemon turns it on for `:gc {:enabled?
   true}`, for its home only, and revokes it when it stops."
  [on?]
  (let [home (str (paths/home))]
    (swap! storage-gc-homes (if on? #(conj % home) #(disj % home)))
    (boolean on?)))

(defn storage-gc-enabled?
  "Whether deleting GC is enabled for the current home."
  []
  (contains? @storage-gc-homes (str (paths/home))))

(defn- refuse-gc-unless-enabled! [opts]
  (when-not (or (:dry-run? opts) (storage-gc-enabled?))
    (throw (ex-info (str "Storage GC is off: datahike's gc-storage can delete a whole store when its "
                         "mark is incomplete (datahike#1117). A dry run ({:dry-run? true}) is allowed; "
                         "enable deleting GC with the daemon's :gc {:enabled? true}.")
                    {:type ::storage-gc-disabled}))))

(defn- gc-report
  "A system DB GC result as a report: a dead or empty result is a failure,
   never `:reclaimed 0`."
  [r]
  (cond
    (= r ::timeout) {:error "timed out"}
    (nil? r) {:error "the GC returned nothing (it may have died)"}
    (number? r) {:reclaimed r}
    (counted? r) {:reclaimed (count r)}
    (seqable? r) {:reclaimed (count (seq r))}
    :else {:error (str "unexpected GC result " (pr-str r))}))

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
   (refuse-gc-unless-enabled! opts)
   (let [rb (or (:remove-before opts) (java.util.Date. 0))
         ;; system-db is a plain root datahike (the registry itself), not in any
         ;; ctx workspace — GC it directly. BOUNDARY deref with timeout (the api
         ;; returns datahike's async throwable-promise), mirroring the ygg adapter.
         ;; datahike's gc-storage has no dry run: a dry run leaves the system DB
         sysdb (if (:dry-run? opts)
                 {:dry-run true :skipped "the system DB's GC has no dry run"}
                 (try (gc-report (deref (d/gc-storage (sdb/get-conn) rb) 600000 ::timeout))
                      (catch Throwable e {:error (.getMessage e)})))
         _ (when (:error sysdb)
             (tel/log! {:level :warn :id :rooms/gc-failed :data {:system :system-db :error (:error sysdb)}}
                       "System DB storage GC failed"))
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
  (doseq [{:keys [room-id name scope owner-id type]} pending-grants]
    (try
      (sdb/attach! room-id
                   (sdb/register-system! {:type (or type :data) :name name :scope scope :owner-id owner-id})
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
    (try (sdh/delete-database! (data-cfg scope)) (catch Throwable _))))

(defn delete-room-db!
  "Remove an agent-created data DB `db-name` from the room: detach + retract the
   grant + drop it from the composite + delete the store. KB/msgs are NOT
   deletable here, and neither is a data DB the room holds on any grant but
   `:owner` (one attached from another room): deleting it would delete the
   owner's store. Returns true if a DB was removed."
  [room-id db-name]
  (when-let [{:keys [path]} (first (filter #(and (= (name db-name) (:slug %))
                                                 (= :owner (:permission %)))
                                           (room-data-dbs room-id)))]
    (try (ygg/unregister! (data-system-name path)) (catch Throwable _))
    (sdb/delete-system-by-scope! room-id path)
    (try (sdh/delete-database! (data-cfg path)) (catch Throwable _))
    true))

(defn- sandbox-writable?
  "Whether sandbox code may transact a database of `type` the room holds on a
   grant with `permission`. A messages store never: messages are posted
   through the posting API, which records who posted them."
  [room-id type permission system-id]
  (and (not= :msgs type) (grant-permits? permission :write)
       (nil? (trunk-write-refusal room-id system-id))))

(defn room-databases
  "Every datahike database attached to `room-id` as `{:name :type :permission
   :writable?}` — the room's own KB + messages store + any agent-created data
   DBs, and the KBs attached from other rooms. `:writable?` is whether sandbox
   code may transact it (`sandbox-writable?`). This is the
   `dvergr.room/databases` discovery surface."
  [room-id]
  (vec (for [[type dbs] [[:kb (room-kbs room-id)] [:msgs (room-msgs room-id)] [:data (room-data-dbs room-id)]]
             {:keys [slug permission system-id]} dbs]
         {:name slug :type type :permission permission
          :writable? (sandbox-writable? room-id type permission system-id)})))

(defn room-database-by-name
  "The datahike database attached to `room-id` named `db-name` — a data DB,
   KB or messages store, in that order — as `{:conn :type :permission
   :writable?}`, the conn fork-aware; nil if no match. `:writable?` is
   whether sandbox code may transact it (`room-databases`)."
  [room-id db-name]
  (let [nm (name db-name)]
    (some (fn [[type dbs system-name]]
            (some (fn [{:keys [slug path permission system-id]}]
                    (when (= nm slug)
                      (when-let [conn (:conn (ygg/system (system-name path)))]
                        {:conn conn :type type :permission permission
                         :writable? (sandbox-writable? room-id type permission system-id)
                         :write-refusal (trunk-write-refusal room-id system-id)})))
                  dbs))
          [[:data (room-data-dbs room-id) data-system-name]
           [:kb (room-kbs room-id) kb-system-name]
           [:msgs (room-msgs room-id) msgs-system-name]])))

(defn room-conn-by-name
  "Fork-aware conn to ANY datahike database attached to `room-id` resolved by its
   name — KB, messages store, or an agent-created data DB. nil if no match.
   Host code only: it ignores the grant's permission (`room-database-by-name`
   says what it allows)."
  [room-id db-name]
  (:conn (room-database-by-name room-id db-name)))

(defn- system-name
  "The Yggdrasil system id a registry system of `type` at `scope` registers
   under in a room's composite, or nil for a type that registers none."
  [type scope]
  (case type
    :kb (kb-system-name scope)
    :msgs (msgs-system-name scope)
    :repo (repo-system-name scope)
    :data (data-system-name scope)
    :book (book-system-name scope)
    nil))

(defn- registry-system-index [db]
  (let [systems (filter #(#{:kb :msgs :repo :data :book} (:system/type %))
                        (sdb/all-systems db))]
    (doseq [system systems]
      (when-not (and (string? (:system/scope system)) (seq (:system/scope system)))
        (throw (ex-info "Registry-backed system has no valid scope locator"
                        {:type ::invalid-system-scope :system/id (:system/id system)}))))
    (group-by #(system-name (:system/type %) (:system/scope %)) systems)))

(defn- unambiguous-system [index runtime-id]
  (let [matches (get index runtime-id)]
    (when (> (count matches) 1)
      (throw (ex-info "Runtime system has ambiguous registry identity"
                      {:type ::ambiguous-system :system runtime-id})))
    (first matches)))

(defn registry-system-for
  "Resolve an exact runtime system id through the canonical registry naming
   rules. Nil means unregistered; ambiguous identities refuse rather than
   selecting a grant by query order. The db arity is pure and lets a host
   authorize several proposal components against ONE registry snapshot."
  ([runtime-id] (registry-system-for @(sdb/get-conn) runtime-id))
  ([db runtime-id]
   (when runtime-id
     (unambiguous-system (registry-system-index db) runtime-id))))

(defn connection-system-id
  "The registry id of `conn` in the bound world, when a trunk policy is set.
   Tool transaction effects carry this so an unrelated protected KB does not
   refuse a write to this connection."
  [conn]
  (when (and conn @trunk-write-policy-hook (ec/execution-context-bound?))
    (some (fn [{:system/keys [id type scope]}]
            (when-let [sid (system-name type scope)]
              (when (identical? conn (let [system (ygg/system sid)]
                                       (if (= :repo type)
                                         (when system (geschichte/gy-connection system))
                                         (:conn system)))) id)))
          (sdb/all-systems))))

(defn system-write-check
  "A predicate over Yggdrasil system ids: whether the room `room-id` may
   write the system (transact it, collect its storage, merge a fork's changes
   into it). A system the registry knows (KB, messages store, repo, data DB,
   book) only on a grant of this room that permits `:write`; a detached one,
   still registered in the room's composite, not at all. A system the
   registry does not know (a mailbox) is the room's own. Read once: call it
   again after the grants change. The trunk policy is checked on each call
   of the predicate. Ambiguous runtime identities refuse; registry identities
   and room grants are read from one DB snapshot. The second arity disables
   only that policy for merge settlement, which still checks grants."
  ([room-id] (system-write-check room-id true))
  ([room-id trunk-policy?]
   (let [db @(sdb/get-conn)
         index (registry-system-index db)
         writable (into #{}
                        (keep (fn [{:keys [system permission]}]
                                (when (grant-permits? permission :write)
                                  (:system/id system))))
                        (when room-id (sdb/systems-for-room db room-id)))]
     (fn [sid]
       (let [system (unambiguous-system index sid)
             id (:system/id system)]
         (and (or (nil? system) (contains? writable id))
              (or (not trunk-policy?) (nil? (trunk-write-refusal room-id (or id sid))))))))))

(defn- delta-changed?
  "Whether a fork's merge-base delta of one system (`ygg/context-diff`)
   changes it: datoms added or retracted, files changed, intents to settle.
   A delta that is an error, or of a shape not known here, counts as a
   change."
  [delta]
  (cond
    (nil? delta) false
    (:error delta) true
    (contains? delta :intents) (boolean (seq (:intents delta)))
    (contains? delta :files) (boolean (seq (:files delta)))
    (contains? delta :summary) (let [{:keys [added-datoms removed-datoms]} (:summary delta)]
                                 (or (pos? (long (or added-datoms 0)))
                                     (pos? (long (or removed-datoms 0)))))
    :else true))

(defn assert-merge-permitted!
  "Throws when merging the `:ctx` fork whose context is `fork-ctx` into the
   room `room-id` would change a system the room may not write
   (`system-write-check`): a fork may have written a KB while the room's grant
   permitted it, and the grant was downgraded or detached since. Only a
   system the fork changed counts (`delta-changed?` on its merge-base delta,
   retractions included). Introducing a child-only protected system also
   requires a current write grant: shared-pair diffs omit that structural
   change. Missing deltas for mergeable systems fail closed. Shared,
   non-mergeable compatibility systems need no state merge. Nothing is
   diffed when every system in the fork may be written.
   The room is named by its `slug`: the durable room's, for a fork of a fork."
  [slug fork-ctx]
  (let [room-id (some-> slug sdb/room-by-slug :room/id)
        may-write? (system-write-check room-id false)
        systems (binding [ec/*execution-context* fork-ctx]
                  (ygg/registered-systems))
        blocked (into #{} (remove may-write?) (keys systems))]
    (when (seq blocked)
      (let [diff (ygg/context-diff fork-ctx)
            parent-systems (when-let [parent (:parent-ctx fork-ctx)]
                             (binding [ec/*execution-context* parent]
                               (ygg/registered-systems)))
            changed (filterv
                     (fn [sid]
                       (or (not (contains? parent-systems sid))
                           (and (nil? (get diff sid))
                                (or (contains? diff sid)
                                    (satisfies? ygg-protocols/Mergeable (get systems sid))))
                           (delta-changed? (get diff sid))))
                     (sort blocked))]
        (when (seq changed)
          (throw (ex-info (str "The fork changed databases this room may not write (its grant does "
                               "not permit writes, or they were detached): " (pr-str changed)
                               "; discard the fork")
                          {:type ::merge-not-permitted :room/id room-id :systems changed})))))))

(defn assert-fork-merge-permitted!
  "Fresh room-grant preflight for an adopted ForkHandle settlement leaf.

   The trusted adopter supplies the durable origin room's slug, NOT ancestry
   copied from an external descriptor. Call from its merge prepare! callback,
   under the handle's authority lock, before journaling the decision. Only the
   selected handle's authoritative scope/delta is checked, never its siblings.
   Only fixed partition leaves are accepted (partition a singleton too).
   Original handles can outgrow their creation metadata, and their shared
   delta does not describe every child-only system a merge could introduce.
   Missing/error deltas for blocked mergeable systems fail closed; unchanged
   blocked systems do not prevent a no-op merge. Discard and persistence-only
   commit retries need no write grant.

   This is a fresh preflight, not a transaction with an independent grant
   writer. It does not grant human decision authority or replace an adopter's
   own permission checks."
  [slug handle]
  (when-not (ygg/fork-handle? handle)
    (throw (ex-info "Merge permission check requires a ForkHandle"
                    {:type ::invalid-fork-handle})))
  (let [room-id (:room/id (sdb/room-by-slug slug))]
    (when-not room-id
      (throw (ex-info "Cannot resolve the adopted world's origin room"
                      {:type ::merge-not-permitted :room/slug slug})))
    (let [may-write? (system-write-check room-id false)
          descriptor (ygg/fork-descriptor handle)
          _ (when-not (:fork/partition-of descriptor)
              (throw (ex-info "Merge permission check requires a fixed partition leaf"
                              {:type ::merge-scope-not-fixed})))
          diff (try (ygg/fork-diff handle)
                    (catch Throwable error
                      (throw (ex-info "Cannot inspect the selected world's merge delta"
                                      {:type ::merge-not-permitted :room/id room-id} error))))
          described (into #{} (keep (fn [[sid metadata]]
                                      (when-not (= :shared (:kind metadata)) sid)))
                          (:fork/systems descriptor))
          systems (into described (keys diff))
          blocked (remove may-write? systems)
          changed (filterv #(or (nil? (get diff %)) (delta-changed? (get diff %)))
                           (sort-by pr-str blocked))]
      (when (seq changed)
        (throw (ex-info "The selected world changed systems its origin room may no longer write"
                        {:type ::merge-not-permitted :room/id room-id :systems changed}))))))

(defn- start-gc!
  "Start one system's GC: `gc-system!` answers a partial-cps computation,
   which runs only when it is called, and completes later (datahike's and the
   repository's sweeps are async-only). It is called here, with callbacks
   that log the outcome, so no caller is left holding a computation it could
   run later, after the room's grant changed."
  [sid cps]
  (cps (fn [report]
         (if (nil? report)
           (tel/log! {:level :warn :id :rooms/gc-failed :data {:system sid :error "the GC returned nothing"}}
                     "Room storage GC failed")
           (tel/log! {:level :info :id :rooms/gc-done :data {:system sid :report (pr-str report)}}
                     "Room storage GC finished")))
       (fn [error]
         (tel/log! {:level :warn :id :rooms/gc-failed :data {:system sid :error (str error)}}
                   "Room storage GC failed")))
  :started)

(defn gc-room!
  "Reclaim unreachable storage of the room's systems in the bound ctx (the
   room's, or a fork of it), as `ygg/gc!` does, but only of the systems the
   room may write (`system-write-check`): one attached on a grant that does
   not permit `:write` (a KB attached `:read` from another room), or no
   longer attached, is its owner's storage, and its GC (`:remove-before`
   collapses history) is the owner's to run. Each collection is started here
   and completes in the background (logged as `:rooms/gc-done` or
   `:rooms/gc-failed`). Returns {system-id -> :started}, or nil if no system
   may be collected."
  ([room-id] (gc-room! room-id {}))
  ([room-id opts]
   (refuse-gc-unless-enabled! opts)
   (let [may-write? (system-write-check room-id)
         reports (into {}
                       (keep (fn [[sid sys]]
                               (when (and (may-write? sid)
                                          (satisfies? ygg-protocols/GarbageCollectable sys))
                                 [sid (start-gc! sid (ygg-gc/gc-system! sys opts))])))
                       (ygg/registered-systems))]
     (when (seq reports) reports))))

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
                           (sdh/delete-database! (kb-cfg scope)))
                         (catch Throwable _ nil))
                    (delete-tree! scope))
          :msgs (do (ygg/unregister! (msgs-system-name scope))
                    (try (when (d/database-exists? (msgs-cfg scope))
                           (sdh/delete-database! (msgs-cfg scope)))
                         (catch Throwable _ nil))
                    (delete-tree! scope))
          nil)))
    (swap! room-ctxs dissoc room-id)
    (sdb/delete-room! room-id (mapv :system/id owned))
    {:deleted-room? true :owned-systems-removed (count owned)}))
