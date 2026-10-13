(ns dvergr.sandbox.ns.datahike
  "A faithful mirror of the datahike API for the agent sandbox — mounted in SCI as
   `datahike.api`. Ordinary datahike code the agent already knows works verbatim
   once it aliases the namespace, `(require '[datahike.api :as d])`:

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
  (:require [clojure.edn :as edn]
            [datahike.api :as d]
            [datahike.datom]
            [datahike.connector :as dc]
            [datahike.db.utils :as dbu]
            [datahike.impl.entity]
            [konserve.protocols :as kp]
            [datahike.query.resolve :as resolve]
            [dvergr.effects :as effects]
            [dvergr.system.rooms :as srooms]
            [dvergr.runtime.ctx :as runtime-ctx]
            [clojure.string :as str]
            [org.replikativ.spindel.engine.core :as ec]
            [sci.fork :as sci-fork]
            [sci.core :as sci]))

(deftype SandboxDb [db]
  ;; A database value as sandbox code holds it. The value itself carries its
  ;; store, writer and config, which no sandbox code may reach; the mirrored
  ;; Datahike fns take this back and use the value inside.
  Object
  (toString [_] (str "#sandbox/db " (pr-str {:max-tx (:max-tx db) :max-eid (:max-eid db)}))))

(defmethod print-method SandboxDb [x ^java.io.Writer w]
  (.write w (str x)))

(defn- db-value? [x]
  (and (some? x) (not (instance? SandboxDb x)) (dbu/db? x)))

(declare world-connection?)

(defn- leak!
  "A Datahike result that would hand sandbox code a host object."
  [x]
  (throw (ex-info "A Datahike result held a host object; sandbox code gets databases as handles only"
                  {:type ::host-object :class (str (class x))})))

(defn- plain?
  "A value that holds nothing to seal: the scalars query results are made of."
  [x]
  (or (nil? x) (string? x) (number? x) (keyword? x) (symbol? x) (boolean? x)
      (uuid? x) (char? x) (inst? x) (instance? datahike.datom.Datom x)))

(defn- seal-coll
  "`x` with `f` applied to its elements, or `x` itself when no element changed
   (an ordinary query result is scanned once and not copied)."
  [x f rebuild]
  (let [x (if (meta x) (with-meta x nil) x)] ; metadata is not part of a result
    (if (every? #(identical? % (f %)) x)
      x
      (rebuild x))))

(defn seal
  "What sandbox code gets for `x`, deeply: a database value as a SandboxDb,
   within maps, vectors, sets and sequences too (a transaction report's
   databases, a query tuple). A connection, store or other reference fails
   closed. Entities and handles are left as they are; an entity's database
   is reached only through the mirrored `entity-db`, which seals it. A
   collection that holds nothing to seal comes back as it is."
  [x]
  (cond
    (plain? x) x
    (instance? SandboxDb x) x
    (world-connection? x) x
    (instance? datahike.impl.entity.Entity x) x
    (dbu/db? x) (SandboxDb. x)
    (or (dc/connection? x)
        (instance? clojure.lang.IRef x)
        (instance? clojure.lang.IAtom x))
    (leak! x)
    (record? x) (seal (into {} x)) ; a transaction report, as a map
    (map? x) (let [x (if (meta x) (with-meta x nil) x)]
               (if (every? (fn [[k v]] (and (identical? k (seal k)) (identical? v (seal v)))) x)
                 x
                 (persistent! (reduce-kv (fn [m k v] (assoc! m (seal k) (seal v)))
                                         (transient (empty x)) x))))
    (vector? x) (seal-coll x seal #(mapv seal %))
    (set? x) (seal-coll x seal #(into (empty %) (map seal) %))
    (seq? x) (map seal x) ; lazily: `datoms` over a large database stays a stream
    (satisfies? kp/PEDNKeyValueStore x) (leak! x)
    :else x))

(defn unseal
  "The database value of a SandboxDb, else `x`."
  [x]
  (if (instance? SandboxDb x) (.-db ^SandboxDb x) x))

(deftype WorldConnection [resolver]
  clojure.lang.IDeref
  (deref [_]
    (seal @(resolver)))

  sci-fork/Forkable
  (fork-value [this]
    ;; The handle is immutable. Its resolver consults the dynamically selected
    ;; Spindel world, so sharing the handle selects a different Ygg connection
    ;; after a world fork rather than sharing the connection itself.
    this))

(defn- world-connection? [x] (instance? WorldConnection x))

(defn world-connection
  "Create an ambient Datahike connection handle. Deref returns the selected
   connection's DB, sealed; mirrored Datahike functions unwrap it to the
   connection."
  [resolve-conn]
  (WorldConnection. resolve-conn))

(defn resolve-connection
  "Resolve a WorldConnection to its connection and a SandboxDb to its
   database value, otherwise return `value` unchanged."
  [value]
  (cond
    (instance? WorldConnection value)
    (or ((.-resolver ^WorldConnection value))
        (throw (ex-info "Ambient Datahike connection is unavailable in this world"
                        {:type ::connection-unavailable})))

    (instance? SandboxDb value) (unseal value)
    :else value))

(defn db-of
  "The database value behind a WorldConnection, for host code."
  [conn]
  @(resolve-connection conn))

(defn- callable?
  "Code Datahike would call: a function, a var, a multimethod, any other
   invokable that is not data (keywords, symbols and collections are data;
   they invoke as lookups)."
  [x]
  (and (ifn? x) (not (keyword? x)) (not (symbol? x)) (not (coll? x))))

(defn- sealing-fn
  "`f` (sandbox code Datahike calls back: a query function, an aggregate, a
   filter predicate) seeing sealed values; anything else as it is."
  [f]
  (if (callable? f)
    (fn [& args] (apply f (map seal args)))
    f))

(defn- sealing-input
  "A query input with every callable in it, at any depth of a tuple,
   collection or relation input, seeing sealed values."
  [x]
  (cond
    (callable? x) (sealing-fn x)
    (map? x) (into (empty x) (map (fn [[k v]] [(sealing-input k) (sealing-input v)])) x)
    (vector? x) (mapv sealing-input x)
    (set? x) (into (empty x) (map sealing-input) x)
    (sequential? x) (map sealing-input x)
    :else x))

;; ---------------------------------------------------------------------------
;; Queries. A database is unsealed only where the query declares a source
;; (`$`, `$x` in :in, or the default source). A call, in the query or its
;; rules and in list or vector form, takes a source only as the first
;; argument of one of Datahike's own lookups. Otherwise [(identity $) ?db],
;; [(get $ :store) ?s], [(?f $) ?x], [[?f $] ?x] or a lookup's default
;; argument would bind the raw database, its store or a callback over it.

(def ^:private source-fns
  "Query functions that take a database source, as their first argument."
  '#{get-else get-some missing? pull})

(defn- source-sym? [x]
  (and (symbol? x) (str/starts-with? (name x) "$")))

(defn- query-form [query]
  (if (string? query) (edn/read-string query) query))

(defn- in-bindings
  "The :in of `query` (vector or map form); `[$]` when it declares none."
  [query]
  (let [in (if (map? query)
             (:in query)
             (->> query (drop-while #(not= :in %)) rest (take-while (complement keyword?))))]
    (if (seq in) (vec in) '[$])))

(defn- refuse-query! [why form]
  (throw (ex-info (str why ": " (pr-str form))
                  {:type ::source-in-function :clause (pr-str form)})))

(defn- check-bindings!
  "Every name an :in binds is a variable (`?x`), a source (`$`, `$x`), the
   rules (`%`) or `_`/`...`, so no input can stand in for a function name
   such as `pull`."
  [bindings]
  (doseq [x (tree-seq coll? seq bindings)
          :when (and (symbol? x)
                     (not (str/starts-with? (name x) "?"))
                     (not (source-sym? x))
                     (not (#{'% '_ '...} x)))]
    (refuse-query! "A query input binds a variable (?x), a source ($) or the rules (%)" x)))

(defn- check-source-use!
  "Throws when a call in `forms` (a query, its rules) takes a source other
   than as the first argument of an unqualified `source-fns` lookup. A form
   that starts with a source (a data pattern or rule call on that source)
   is not a call."
  [forms]
  (doseq [x (tree-seq coll? seq forms)
          :when (and (sequential? x) (symbol? (first x)) (not (source-sym? (first x))))]
    (let [[f & args] x
          lookup? (and (nil? (namespace f)) (contains? source-fns f))]
      (when (some source-sym? (if lookup? (rest args) args))
        (refuse-query! (str "A query function takes a database only as the first argument "
                            "of get-else, get-some, missing? or pull")
                       x)))))

(defn- query-args
  "A query's inputs as Datahike takes them: databases unsealed in source
   positions, callables seeing sealed values, bindings and rules checked."
  [query inputs]
  (let [query (query-form query)
        bindings (in-bindings query)
        rules (keep-indexed (fn [i b] (when (= '% b) (nth inputs i nil))) bindings)]
    (check-bindings! bindings)
    (check-source-use! (into [query] rules))
    (into []
          (map-indexed (fn [i v]
                         (if (source-sym? (get bindings i)) (unseal v) (sealing-input v))))
          inputs)))

(defn- tx-forms
  "The transaction forms of `tx` (a collection, or a map with :tx-data)."
  [tx]
  (if (map? tx) (:tx-data tx) tx))

(defn- host-args
  "The arguments of a mirrored Datahike call as the real fn takes them: the
   database (or connection) argument unsealed, query inputs as `query-args`
   has them, a filter predicate seeing sealed databases. Any other handle
   stays sealed, so Datahike never computes on a raw value there."
  [sym args]
  (case sym
    q (if (and (map? (first args)) (contains? (first args) :query))
        (let [{:keys [query] :as m} (first args)]
          (into [(update m :args #(query-args query (vec %)))] (rest args)))
        (into [(first args)] (query-args (first args) (vec (rest args)))))
    (let [[target & more] args
          more (vec more)]
      (into [(resolve-connection target)]
            (case sym
              filter (update more 0 sealing-fn)
              more)))))

(defn- sealed-result
  "A mirrored call's result as sandbox code gets it: sealed deeply, and a
   pending transaction sealed when it is dereferenced."
  [x]
  (if (and (instance? clojure.lang.IDeref x) (instance? clojure.lang.IPending x)
           (not (world-connection? x)))
    (reify
      clojure.lang.IDeref
      (deref [_] (seal @x))
      clojure.lang.IBlockingDeref
      (deref [_ ms timeout] (let [v (deref x ms ::timeout)]
                              (if (= ::timeout v) timeout (seal v))))
      clojure.lang.IPending
      (isRealized [_] (realized? x)))
    (seal x)))

(defn- sandbox-error
  "A fresh error for sandbox code: the message, the operation and its
   argument types, and no cause (a cause's ex-data can hold the raw values
   Datahike was given)."
  [sym resolved ^Throwable error]
  (ex-info (str "Sandbox Datahike operation " sym " failed: " (ex-message error))
           {:operation sym
            :argument-types (mapv #(some-> % class str) resolved)}))

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

(defn- certified-evaluation-entity? [db eid]
  (try
    (let [entity (d/entity db eid)]
      (boolean (or (:attempt/id entity) (:attempt.check/id entity)
                   (:scorecard/id entity) (:scorecard.summary/id entity))))
    (catch Throwable _ false)))

(defn- assert-no-certified-evaluation-write!
  "`tx-data` when sandbox code may transact it against `db`: no function
   calls (`:db.fn/call` would run sandbox code on the raw database) and no
   writes to certified evaluation projections."
  [db tx-data]
  (doseq [form tx-data]
    (when (or (and (sequential? form)
                   (let [op (first form)]
                     (or (= :db.fn/call op)
                         (not (and (keyword? op)
                                   (some-> (namespace op) (as-> n (or (= "db" n) (str/starts-with? n "db.")))))))))
              (and (map? form) (contains? form :db/fn)))
      (throw (ex-info (str "Transaction functions (:db.fn/call, :db/fn, an entity's ident as the "
                           "operation) are not available in the sandbox")
                      {:type ::tx-function})))
    (let [protected?
          (cond
            (map? form)
            (or (some certified-evaluation-attr? (keys form))
                (certified-evaluation-entity? db (:db/id form)))

            (sequential? form)
            (let [[op eid attr] form]
              (or (certified-evaluation-attr? attr)
                  (and (vector? eid)
                       (certified-evaluation-attr? (first eid)))
                  (and (#{:db/retractEntity :db.fn/retractEntity} op)
                       (certified-evaluation-entity? db eid))))

            :else false)]
      (when protected?
        (throw
         (ex-info "Certified evaluation projections are host-owned and read-only in SCI"
                  {:type ::protected-evaluation-write :tx-form (pr-str form)})))))
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

(defn untrusted
  "Run `f` resolving query/predicate function symbols as Datahike's server
   does (`safe-symbol-resolver`: pure clojure.core, clojure.string,
   read-only Datahike fns, registered fns). Embedded Datahike resolves them
   permissively (any var, reflection), so a sandboxed query clause such as
   [(slurp ?p) ?x] or [(clojure.java.shell/sh ...) ?x] would run on the host
   past SCI."
  [f]
  (binding [resolve/*symbol-resolver* resolve/safe-symbol-resolver] (f)))

(defn add-datahike-ns!
  "Mount the faithful datahike API under `datahike.api`, with lifecycle fns
   guarded to room `room-id` (resolved fork-aware under `ctx`)."
  [sci-ctx room-id ctx & [binding-resolver binding-swap! effects]]
  (let [data      (into {} (keep (fn [sym]
                                   (when-let [v (ns-resolve 'datahike.api sym)]
                                     (let [f @v]
                                       [sym (fn [& args]
                                              (let [resolved (host-args sym args)]
                                                (try
                                                  (sealed-result (untrusted #(apply f resolved)))
                                                  (catch Throwable error
                                                    (throw (sandbox-error sym resolved error))))))])))
                                 data-ops))
        ;; Writes: the forms are checked as Datahike reads them (a collection,
        ;; or a map's :tx-data), so the guard sees what is transacted.
        writing   (fn [sym f conn tx]
                    (let [target (resolve-connection conn)
                          checked (assert-no-certified-evaluation-write!
                                   (if (= 'with sym) target @target) (tx-forms tx))
                          tx (if (map? tx) (assoc tx :tx-data checked) checked)]
                      (try
                        (sealed-result (untrusted #(f target tx)))
                        (catch Throwable error
                          (throw (sandbox-error sym [target tx] error))))))
        data      (assoc data
                         'transact  (fn [conn tx] (writing 'transact d/transact conn tx))
                         'transact! (fn [conn tx] (writing 'transact! d/transact! conn tx))
                         'with      (fn [db tx] (writing 'with d/with db tx)))
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
                     (or (when (contains? (ephemeral-map) nm)
                           ;; a handle, like a room database: the connection
                           ;; itself stays with the host
                           (world-connection #(some-> (get (ephemeral-map) nm) entry-conn)))
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
        ;; writes and lifecycle are effects (dvergr.effects); queries read an
        ;; immutable value of a database and are not
        ;; a transaction's size, what a quota counts: its printed form
        tx-bytes (fn [[_ tx-data]] (count (pr-str tx-data)))
        fx (fn [kind resource-of f]
             (fn [& args]
               (effects/perform! effects (cond-> {:effect kind :resource (resource-of args)}
                                           (= :db/transact kind) (assoc :bytes (tx-bytes args)))
                                 #(apply f args))))
        tx-resource (fn [[_ tx]] {:datoms (count (tx-forms tx))})
        db-resource (fn [[cfg]] {:name (str (cfg-name cfg))})
        m (-> (merge data guard)
              (update 'transact #(fx :db/transact tx-resource %))
              (update 'transact! #(fx :db/transact tx-resource %))
              (update 'create-database #(fx :db/create db-resource %))
              (update 'delete-database #(fx :db/delete db-resource %)))]
    ;; The real datahike.api name (the model aliases it `:as d` itself, as everyone does).
    (sci/add-namespace! sci-ctx 'datahike.api m)))
