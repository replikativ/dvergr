(ns dvergr.drive.core
  "Drives — per-room file systems. A drive is the room's datahike fs.node TREE
   plus content in the CAS blob store (dvergr.drive.blobs); the tree references
   blobs by hash. The tree DB is a room-owned datahike system
   (`dvergr.system.rooms/create-room-db!`), so it forks / merges / discards WITH
   the room and its grant survives restart — no bespoke registry.

   API-first: consumers (the shell /drive mount via dvergr.drive.fs, the media
   fns, channel uploads) go through ls / put-file! / read-file / mkdir! / mv! /
   rm! — the fs.node schema is an implementation detail.

   Drives hold raw documents; the KB holds derived knowledge."
  (:require [datahike.api :as d]
            [dvergr.drive.blobs :as blobs]
            [dvergr.system.rooms :as srooms]
            [clojure.string :as str]
            [taoensso.telemere :as log]))

(def fs-node-schema
  [{:db/ident :fs.node/id     :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :fs.node/name   :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :fs.node/parent :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Parent dir node; absent ⇒ child of the root"}
   {:db/ident :fs.node/kind   :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one :db/doc ":file | :dir"}
   {:db/ident :fs.node/blob   :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Content hash into the CAS blob store — files only"}
   {:db/ident :fs.node/mime   :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :fs.node/size   :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :fs.node/mtime  :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :fs.node/source :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc ":upload :telegram :photo :voice :agent :mail …"}])

;; =============================================================================
;; Room drive connection (reuses dvergr's room-owned, fork-aware DB machinery)
;; =============================================================================

(def ^:private drive-db-name "drive")

(defn room-drive-conn
  "The room's drive tree conn, or nil if not yet provisioned."
  [room-id]
  (srooms/room-db-conn room-id drive-db-name))

(defn ensure-room-drive!
  "The room's drive tree conn, provisioning it on first need as a room-owned
   datahike system (forks/merges with the room; grant persisted). Requires the
   room's ctx bound (the caller runs in it)."
  [room-id & {:keys [owner-id]}]
  (or (room-drive-conn room-id)
      (srooms/create-room-db! room-id drive-db-name
                              :schema fs-node-schema :owner-id owner-id)))

;; =============================================================================
;; The tree API — the contract every consumer programs against
;; =============================================================================

(defn- node-eid [db node-id]
  (d/q '[:find ?e . :in $ ?id :where [?e :fs.node/id ?id]] db node-id))

(defn- child-by-name
  "Child of `parent-id` (nil ⇒ root level) named `name`, or nil."
  [db parent-id name]
  (if parent-id
    (d/q '[:find (pull ?c [*]) . :in $ ?pid ?n
           :where [?p :fs.node/id ?pid] [?c :fs.node/parent ?p] [?c :fs.node/name ?n]]
         db parent-id name)
    (d/q '[:find (pull ?c [*]) . :in $ ?n
           :where [?c :fs.node/name ?n] [(missing? $ ?c :fs.node/parent)]]
         db name)))

(defn ls
  "Children of `parent-id` (nil ⇒ root), sorted dirs-first by name."
  [conn & [parent-id]]
  (let [db @conn
        rows (if parent-id
               (d/q '[:find [(pull ?c [*]) ...] :in $ ?pid
                      :where [?p :fs.node/id ?pid] [?c :fs.node/parent ?p]]
                    db parent-id)
               (d/q '[:find [(pull ?c [*]) ...]
                      :where [?c :fs.node/id _] [(missing? $ ?c :fs.node/parent)]]
                    db))]
    (->> rows
         (mapv #(dissoc % :db/id))
         (sort-by (juxt #(if (= :dir (:fs.node/kind %)) 0 1)
                        #(str/lower-case (or (:fs.node/name %) ""))))
         vec)))

(defn mkdir!
  "Ensure a directory `name` under `parent-id` (nil ⇒ root). Idempotent; returns
   the dir node's id."
  [conn parent-id name]
  (if-let [existing (child-by-name @conn parent-id name)]
    (:fs.node/id existing)
    (let [id (random-uuid)]
      (d/transact conn [(cond-> {:fs.node/id id :fs.node/name name
                                 :fs.node/kind :dir :fs.node/mtime (java.util.Date.)}
                          parent-id (assoc :fs.node/parent [:fs.node/id parent-id]))])
      id)))

(defn ensure-path!
  "Ensure the directory path `segments` (e.g. [\"telegram\"]) exists; returns the
   deepest dir's node id (nil for empty path = root)."
  [conn segments]
  (reduce (fn [parent-id seg] (mkdir! conn parent-id seg)) nil segments))

(defn put-file!
  "Store `bytes` in the CAS and upsert a file node `name` under `parent-id`
   (nil ⇒ root). Same name ⇒ new version (node points at the new hash; datahike
   history keeps the old). Returns the node map."
  [conn parent-id name bytes & {:keys [mime source]
                                :or {mime "application/octet-stream" source :upload}}]
  (let [blob     (blobs/store! bytes mime)
        existing (child-by-name @conn parent-id name)
        id       (or (:fs.node/id existing) (random-uuid))
        node     (cond-> {:fs.node/id id :fs.node/name name :fs.node/kind :file
                          :fs.node/blob (:blob/id blob) :fs.node/mime mime
                          :fs.node/size (long (count bytes))
                          :fs.node/mtime (java.util.Date.) :fs.node/source source}
                   parent-id (assoc :fs.node/parent [:fs.node/id parent-id]))]
    (d/transact conn [node])
    (log/log! {:level :info :id ::file-put
               :data {:name name :size (count bytes) :blob (:blob/id blob)}})
    node))

(defn stat
  "Node map by id, or nil."
  [conn node-id]
  (when-let [e (d/q '[:find (pull ?e [*]) . :in $ ?id
                      :where [?e :fs.node/id ?id]] @conn node-id)]
    (dissoc e :db/id)))

(defn read-file
  "File bytes by node id (nil when absent or a dir)."
  [conn node-id]
  (when-let [hash (:fs.node/blob (stat conn node-id))]
    (blobs/get-bytes hash)))

(defn resolve-path
  "Node map for a /-joined `path` (e.g. \"telegram/report.pdf\"), or nil."
  [conn path]
  (let [segs (remove str/blank? (str/split (or path "") #"/"))]
    (loop [parent-id nil, [s & more] segs, node nil]
      (if-not s
        node
        (when-let [n (child-by-name @conn parent-id s)]
          (recur (:fs.node/id n) more (dissoc n :db/id)))))))

(defn mv!
  "Rename and/or reparent a node (no args but node-id ⇒ bump mtime only)."
  [conn node-id & {:keys [name parent-id]}]
  (when (stat conn node-id)
    (d/transact conn [(cond-> {:fs.node/id node-id :fs.node/mtime (java.util.Date.)}
                        name      (assoc :fs.node/name name)
                        parent-id (assoc :fs.node/parent [:fs.node/id parent-id]))])
    (stat conn node-id)))

(defn rm!
  "Retract a node (dirs must be empty). Blobs stay in the CAS (other versions may
   reference them; GC is a later concern)."
  [conn node-id]
  (let [db @conn]
    (when-let [eid (node-eid db node-id)]
      (when (seq (d/q '[:find ?c :in $ ?p :where [?c :fs.node/parent ?p]] db eid))
        (throw (ex-info "Directory not empty" {:node-id node-id})))
      (d/transact conn [[:db/retractEntity eid]])
      :removed)))

(defn tree
  "Whole tree as nested maps (children under :fs.node/children) — for a files
   panel. Founder-scale; paginate later."
  [conn & [parent-id]]
  (mapv (fn [n]
          (if (= :dir (:fs.node/kind n))
            (assoc n :fs.node/children (tree conn (:fs.node/id n)))
            n))
        (ls conn parent-id)))

;; =============================================================================
;; Convenience — store bytes into a room's drive at a /-path
;; =============================================================================

(defn store-in-room!
  "Ensure the room's drive, put `bytes` at directory `dir-segs` under `name`,
   returning the node. The one call channels use to land an upload. Requires the
   room's ctx bound."
  [room-id dir-segs name bytes & {:keys [mime source] :or {source :upload}}]
  (let [conn (ensure-room-drive! room-id)
        dir  (ensure-path! conn (vec dir-segs))]
    (put-file! conn dir name bytes :mime mime :source source)))
