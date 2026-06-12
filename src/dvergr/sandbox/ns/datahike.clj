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
            [clojure.string :as str]
            [org.replikativ.spindel.engine.core :as ec]
            [sci.core :as sci]))

;; The safe DATA surface of datahike.api — resolved from the real namespace so the
;; mirror never drifts. Lifecycle (connect/create/delete/exists?) is GUARDED below.
(def ^:private data-ops
  '[q pull pull-many entity entity-db datoms seek-datoms
    schema reverse-schema metrics db history as-of since filter
    transact transact! load-entities with])

(defn- cfg-name
  "The logical database name from a datahike config: `:store :id`, else the
   basename of `:store :path`. We own placement, so this is the only handle."
  [cfg]
  (or (some-> (get-in cfg [:store :id]) name not-empty)
      (some-> (get-in cfg [:store :path]) str (str/split #"/") last not-empty)
      (throw (ex-info "datahike config needs a :store :id (the database name)" {:cfg cfg}))))

(defn- mem? [cfg] (boolean (#{:mem :memory} (get-in cfg [:store :backend]))))

(defn add-datahike-ns!
  "Mount the faithful datahike API under `datahike.api` and `d`, with lifecycle fns
   guarded to room `room-id` (resolved fork-aware under `ctx`)."
  [sci-ctx room-id ctx]
  (let [data      (into {} (keep (fn [sym]
                                   (when-let [v (ns-resolve 'datahike.api sym)] [sym @v]))
                                 data-ops))
        ;; per-sandbox ephemeral (:mem) databases, keyed by logical name
        ephemeral (atom {})
        in-ctx    (fn [f] (binding [ec/*execution-context* ctx] (f)))
        no-room   (fn [op] (throw (ex-info (str op " needs a room — this sandbox isn't attached to one "
                                                "(only :mem databases are available here).") {})))
        guard
        {'create-database
         (fn [cfg]
           (in-ctx
            (fn [] (let [nm (cfg-name cfg)]
                     (cond
                       (mem? cfg) (let [c (assoc-in cfg [:store :id] nm)]
                                    (when-not (d/database-exists? c) (d/create-database c))
                                    (swap! ephemeral assoc nm (d/connect c)) cfg)
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
                     (or (get @ephemeral nm)
                         (when room-id (srooms/room-conn-by-name room-id nm))
                         (throw (ex-info (str "No database named " (pr-str nm)
                                              " in this room — create it with create-database, or see "
                                              "(dvergr.room/databases).")
                                         {:name nm})))))))
         'database-exists?
         (fn [cfg]
           (in-ctx
            (fn [] (let [nm (cfg-name cfg)]
                     (boolean (or (get @ephemeral nm)
                                  (when room-id (srooms/room-conn-by-name room-id nm))))))))
         'delete-database
         (fn [cfg]
           (in-ctx
            (fn [] (let [nm (cfg-name cfg)]
                     (if (contains? @ephemeral nm)
                       (do (swap! ephemeral dissoc nm) true)
                       (boolean (when room-id (srooms/delete-room-db! room-id nm))))))))}
        m (merge data guard)]
    ;; The real datahike.api name (the model aliases it `:as d` itself, as everyone does).
    (sci/add-namespace! sci-ctx 'datahike.api m)))
