(ns dvergr.sandbox.ns.datahike
  "A faithful mirror of the datahike API for the agent sandbox — mounted in SCI as
   both `datahike.api` and the short alias `d`. Ordinary datahike code the agent
   already knows works verbatim:

     (d/q '[:find ?t :where [?e :entity/title ?t]] @dvergr.room/*kb*)
     (d/transact dvergr.room/*kb* [{:entity/title \"Note\"}])
     (d/create-database {:store {:backend :file :id \"notes\"} :schema-flexibility :write})
     (d/connect          {:store {:backend :file :id \"notes\"}})

   DATA ops (q/pull/transact/…) are the REAL datahike fns, resolved from
   `datahike.api`, on whatever conn/db the agent hands them.

   The LIFECYCLE fns keep datahike's real signatures (a config map) but are
   GUARDED: we OWN the storage. The config's `:store :id` (or the basename of its
   `:path`) is the database's logical NAME; we ignore/relocate the path under the
   room's systems dir — so the agent literally cannot point datahike at system-db
   or anywhere outside its room. A `:file` database is registered into the room's
   yggdrasil composite + a system-db grant, so it forks/merges/discards with the
   room and survives restart; a `:mem`/`:memory` database is a real ephemeral
   datahike (scratch, gone at turn end), not composite-registered.

   (Discovery + by-name access to the room's MANAGED databases — KB, messages,
   created data DBs — is dvergr-specific and lives in `dvergr.room`, not here.)"
  (:require [datahike.api :as d]
            [dvergr.system.rooms :as srooms]
            [dvergr.runtime.ctx :as runtime-ctx]
            [clojure.string :as str]
            [org.replikativ.spindel.engine.core :as ec]
            [sci.fork :as sci-fork]
            [sci.core :as sci]))

(deftype WorldConnection [resolver]
  clojure.lang.IDeref
  (deref [_]
    @(resolver))

  sci-fork/Forkable
  (fork-value [this]
    ;; The handle is immutable. Its resolver consults the dynamically selected
    ;; Spindel world, so sharing the handle selects a different Ygg connection
    ;; after a world fork rather than sharing the connection itself.
    this))

(defn world-connection
  "Create an ambient Datahike connection handle. Deref returns the selected
   connection's DB; mirrored Datahike functions unwrap it to the connection."
  [resolve-conn]
  (WorldConnection. resolve-conn))

(defn resolve-connection
  "Resolve a WorldConnection, otherwise return `value` unchanged."
  [value]
  (if (instance? WorldConnection value)
    (or ((.-resolver ^WorldConnection value))
        (throw (ex-info "Ambient Datahike connection is unavailable in this world"
                        {:type ::connection-unavailable})))
    value))

;; The safe DATA surface of datahike.api — resolved from the real namespace so the
;; mirror never drifts. Lifecycle (connect/create/delete/exists?) is GUARDED below.
(def ^:private data-ops
  '[q pull pull-many entity entity-db datoms seek-datoms
    schema reverse-schema metrics db history as-of since filter
    transact transact! with])

(defn- certified-evaluation-attr? [x]
  (and (keyword? x)
       (contains? #{"attempt" "attempt.check"
                    "scorecard" "scorecard.summary"}
                  (namespace x))))

(defn- certified-evaluation-entity? [conn eid]
  (try
    (let [entity (d/entity @conn eid)]
      (boolean (or (:attempt/id entity) (:attempt.check/id entity)
                   (:scorecard/id entity) (:scorecard.summary/id entity))))
    (catch Throwable _ false)))

(defn- assert-no-certified-evaluation-write! [conn tx-data]
  (doseq [form tx-data]
    (let [protected?
          (cond
            (map? form)
            (or (some certified-evaluation-attr? (keys form))
                (certified-evaluation-entity? conn (:db/id form)))

            (vector? form)
            (let [[op eid attr] form]
              (or (= :db.fn/call op)
                  (certified-evaluation-attr? attr)
                  (and (vector? eid)
                       (certified-evaluation-attr? (first eid)))
                  (and (#{:db/retractEntity :db.fn/retractEntity} op)
                       (certified-evaluation-entity? conn eid))))

            :else false)]
      (when protected?
        (throw
         (ex-info "Certified evaluation projections are host-owned and read-only in SCI"
                  {:type ::protected-evaluation-write :tx-form form})))))
  tx-data)

(defn- cfg-name
  "The logical database name from a datahike config: `:store :id`, else the
   basename of `:store :path`. We own placement, so this is the only handle."
  [cfg]
  (or (some-> (get-in cfg [:store :id]) str (str/replace #"^:" "") not-empty)
      (some-> (get-in cfg [:store :path]) str (str/split #"/") last not-empty)
      (throw (ex-info "datahike config needs a :store :id (the database name)" {:cfg cfg}))))

(defn- mem? [cfg] (boolean (#{:mem :memory} (get-in cfg [:store :backend]))))

(defn- entry-conn [entry]
  (if (map? entry) (:connection entry) entry))

(defn- world-memory-config
  "Translate an agent-visible logical memory config into a process-global
   Datahike config namespaced by the selected interpreter world."
  [cfg binding-resolver]
  (let [binding (when binding-resolver (binding-resolver))
        logical-name (cfg-name cfg)
        world-key (str (:capability-id binding) "|"
                       (:room-runtime-id binding) "|"
                       (:room-incarnation binding) "|"
                       logical-name)
        physical-id (if binding-resolver
                      (java.util.UUID/nameUUIDFromBytes
                       (.getBytes world-key java.nio.charset.StandardCharsets/UTF_8))
                      (random-uuid))]
    (-> cfg
        (assoc-in [:store :backend] :memory)
        (assoc-in [:store :id] physical-id))))

(defn dispose-ephemeral-databases!
  "Release and delete every process-global scratch database owned by the
   currently selected interpreter world. Returns cleanup errors, if any."
  [binding-resolver binding-swap!]
  (when (and binding-resolver binding-swap!)
    (let [entries (or (:ephemeral-databases (binding-resolver)) {})
          errors (reduce-kv
                  (fn [errors name entry]
                    (let [conn (entry-conn entry)
                          cfg (:config entry)]
                      (try
                        (when conn (d/release conn))
                        (when (and cfg (d/database-exists? cfg))
                          (d/delete-database cfg))
                        errors
                        (catch Throwable error
                          (conj errors {:name name :error error})))))
                  [] entries)]
      (binding-swap! assoc :ephemeral-databases {})
      errors)))

(defn add-datahike-ns!
  "Mount the faithful datahike API under `datahike.api` and `d`, with lifecycle fns
   guarded to room `room-id` (resolved fork-aware under `ctx`)."
  [sci-ctx room-id ctx & [binding-resolver binding-swap!]]
  (let [data      (into {} (keep (fn [sym]
                                   (when-let [v (ns-resolve 'datahike.api sym)]
                                     (let [f @v]
                                       [sym (fn [& args]
                                              (let [resolved (mapv resolve-connection args)]
                                                (try
                                                  (apply f resolved)
                                                  (catch Throwable error
                                                    (throw
                                                     (ex-info
                                                      "Sandbox Datahike operation failed"
                                                      {:operation sym
                                                       :argument-types
                                                       (mapv #(some-> % class str) resolved)}
                                                      error))))))])))
                                 data-ops))
        data      (assoc data
                         'transact
                         (fn [conn tx-data]
                           (let [conn (resolve-connection conn)]
                             (d/transact conn
                                         (assert-no-certified-evaluation-write!
                                          conn tx-data))))
                         'transact!
                         (fn [conn tx-data]
                           (let [conn (resolve-connection conn)]
                             (d/transact! conn
                                          (assert-no-certified-evaluation-write!
                                           conn tx-data)))))
        ;; per-sandbox ephemeral (:mem) databases, keyed by logical name
        ephemeral (atom {})
        ephemeral-map #(if binding-resolver
                         (or (:ephemeral-databases (binding-resolver)) {})
                         @ephemeral)
        swap-ephemeral! (fn [f & args]
                          (if binding-swap!
                            (binding-swap!
                             #(update % :ephemeral-databases
                                      (fn [m]
                                        (apply f (or m {}) args))))
                            (apply swap! ephemeral f args)))
        selected-ctx #(runtime-ctx/selected-context ctx)
        current-room-id #(or (when binding-resolver
                               (:room-id (binding-resolver)))
                             room-id)
        in-ctx    (fn [f]
                    (binding [ec/*execution-context* (selected-ctx)] (f)))
        managed-connection
        (fn [nm]
          (world-connection
           #(binding [ec/*execution-context* (selected-ctx)]
              (or (srooms/room-conn-by-name (current-room-id) nm)
                  (throw (ex-info (str "No database named " (pr-str nm)
                                       " in this room")
                                  {:name nm}))))))
        no-room   (fn [op] (throw (ex-info (str op " needs a room — this sandbox isn't attached to one "
                                                "(only :mem databases are available here).") {})))
        guard
        {'create-database
         (fn [cfg]
           (in-ctx
            (fn [] (let [nm (cfg-name cfg)
                         room-id (current-room-id)]
                     (cond
                       (mem? cfg) (if (contains? (ephemeral-map) nm)
                                    cfg
                                    (let [c (world-memory-config cfg binding-resolver)]
                                      (when (d/database-exists? c)
                                        (throw (ex-info
                                                "Scratch database exists without a live world owner"
                                                {:name nm :physical-config c})))
                                      (d/create-database c)
                                      (swap-ephemeral! assoc nm
                                                       {:connection (d/connect c)
                                                        :config c})
                                      cfg))
                       room-id    (do (srooms/create-room-db! room-id nm
                                                             ;; honour datahike's own default (:write) — don't
                                                             ;; silently downgrade to :read. Explicit :read in cfg
                                                             ;; still wins (schema-free append-only scratch).
                                                              :schema-flexibility (get cfg :schema-flexibility :write))
                                      cfg)
                       :else      (no-room "create-database"))))))
         'connect
         (fn [cfg]
           (in-ctx
            (fn [] (let [nm (cfg-name cfg)]
                     (or (some-> (get (ephemeral-map) nm) entry-conn)
                         (when (current-room-id) (managed-connection nm))
                         (throw (ex-info (str "No database named " (pr-str nm)
                                              " in this room — create it with create-database, or see "
                                              "(dvergr.room/databases).")
                                         {:name nm})))))))
         'database-exists?
         (fn [cfg]
           (in-ctx
            (fn [] (let [nm (cfg-name cfg)]
                     (boolean (or (get (ephemeral-map) nm)
                                  (when-let [room-id (current-room-id)]
                                    (srooms/room-conn-by-name room-id nm))))))))
         'delete-database
         (fn [cfg]
           (in-ctx
            (fn [] (let [nm (cfg-name cfg)]
                     (if (contains? (ephemeral-map) nm)
                       (let [{:keys [connection config] :as entry}
                             (get (ephemeral-map) nm)]
                         (when-let [conn (entry-conn entry)]
                           (d/release conn))
                         (when (and config (d/database-exists? config))
                           (d/delete-database config))
                         (swap-ephemeral! dissoc nm)
                         true)
                       (boolean (when-let [room-id (current-room-id)]
                                  (srooms/delete-room-db! room-id nm))))))))}
        m (merge data guard)]
    ;; The real datahike.api name (the model aliases it `:as d` itself, as everyone does).
    (sci/add-namespace! sci-ctx 'datahike.api m)))
