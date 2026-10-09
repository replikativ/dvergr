(ns dvergr.substrate.datahike
  "ONE idempotent helper to provision a dvergr-shaped datahike DB:
   create-if-missing → connect → install dvergr's schema → register as a
   forkable yggdrasil system on the bound execution context.

   This is the seam shared with product layers (simmis): every per-room /
   per-KB / system store is opened through the same call, so schema +
   branching stay uniform across all of them (see simmis
   doc/using-dvergr-databases.md §5, which proposed it). dvergr's own
   call sites (`dvergr.system.rooms`, `dvergr.system.mail`) route through
   it too — the helper IS the documented provisioning idiom, not a wrapper
   beside it."
  (:require [datahike.api :as d]
            [datahike.config :as dc]
            [datahike.store :as ds]
            [konserve.core :as k]
            [konserve.store :as ks]
            [yggdrasil.adapters.datahike :as dh-adapter]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [dvergr.chat.schema :as cschema]))

(def diff-buf-size
  "Content-only child diffs buffered into the ancestor, cutting stored-object
   growth. Create-time-fixed and irreversible, so it applies to NEW stores only.
   Lives here rather than next to a store config because EVERY dvergr store uses
   the same value and `system.rooms` <-> `substrate.geschichte` cannot require
   each other.

   128, not the 256 the datahike-saas-starter uses: measured on a room-shaped
   store over 2000 message transactions, 256 is past the knee — MORE disk than
   128 (78 vs 57 MB) and slower (p50 13.4 vs 10.9 ms, p99 31.7 vs 17.1 ms). The
   budget counts ENTRIES, not bytes, so datom-sized elements want a smaller
   buffer than a blob-shaped workload.

     diff-buf   disk     objects   p50      p99
     0          320 MB   35355     25.1 ms  40.2 ms
     32          57 MB   14210     10.8 ms  18.4 ms
     128         57 MB   12697     10.9 ms  17.1 ms   <- knee
     256         78 MB   12436     13.4 ms  31.7 ms"
  128)

(defn stored-store-id
  "The `:store :id` the Datahike database in `store-config` was created with,
   or nil when there is no database there. Reads the stored db record the way
   `datahike.writing/-database-exists?*` does; konserve does not check the id
   on connect, so a probe id opens any store."
  [store-config]
  (let [probe (cond-> store-config (nil? (:id store-config)) (assoc :id (random-uuid)))]
    (when (ks/store-exists? probe {:sync? true})
      (let [raw (ks/connect-store probe {:sync? true})
            store (ds/add-cache-and-handlers raw (dc/load-config {:store probe}))]
        (try (get-in (k/get store :db nil {:sync? true}) [:config :store :id])
             (finally (ks/release-store probe store {:sync? true})))))))

(defonce ^:private file-store-ids (atom {}))

(defn file-store-id
  "The konserve store id for the file store at `path`: the id its database was
   created with when there is one, else a fresh one that the store keeps once
   created. Never derived from the path, so a home moved or copied to another
   directory opens its stores (a path-derived id changes with the path and
   Datahike refuses the store). Stores created by older versions keep their
   path-derived ids: they are read, not recomputed.

   Cached per path, so every config built for one store names one id, also
   before the store exists. `forget-file-store-id!` after deleting a store."
  [path]
  (let [path (str path)]
    (or (get @file-store-ids path)
        (let [id (or (stored-store-id {:backend :file :path path}) (random-uuid))]
          (get (swap! file-store-ids #(if (contains? % path) % (assoc % path id))) path)))))

(defn forget-file-store-id!
  "Drop the cached id of the store at `path` (after deleting it)."
  [path]
  (swap! file-store-ids dissoc (str path))
  nil)

(defn delete-database!
  "Delete the database `cfg` names and forget its cached store id, so a store
   created again at the same path gets its own."
  [cfg]
  (try (d/delete-database cfg)
       (finally (when-let [path (get-in cfg [:store :path])]
                  (forget-file-store-id! path)))))

(defn connect!
  "Connect to `cfg`, creating the database first when it doesn't exist.
   Plain create+connect — no schema, no registration. Returns the conn."
  [cfg]
  (when-not (d/database-exists? cfg) (d/create-database cfg))
  (d/connect cfg))

(defn provision!
  "Provision a dvergr-shaped datahike DB. Idempotent — safe to call on every
   boot/open. Returns the conn.

   Opts (one of :conn / :cfg is required):
     :cfg          datahike config map — create-if-missing + connect
     :conn         an already-connected conn (e.g. briefkasten mail) — used as-is
     :schema?      install dvergr's full chat schema via `ensure-full-schema!`
                   (default true; guarded once-per-conn, cheap on re-call)
     :extra-schema extra schema tx (vector) transacted after the dvergr schema —
                   the product layer's OWN attributes (e.g. simmis categorical
                   attrs, the per-room scheduler schema). Re-transacting schema
                   is an idempotent upsert in datahike.
     :seed-tx      data tx (vector) transacted after schema — for seed rows
                   (e.g. a room's `:chat/*` row). Caller owns idempotency
                   (use upserting identity attrs).
     :system-name  yggdrasil system id (required when registering)
     :register?    register as a forkable yggdrasil DatahikeSystem on the BOUND
                   execution context (default true; requires `:system-name` and
                   `*execution-context*`). Re-registration replaces by id."
  [{:keys [cfg conn schema? extra-schema seed-tx system-name register?]
    :or   {schema? true register? true}}]
  {:pre [(or conn cfg)]}
  (let [conn (or conn (connect! cfg))]
    (when schema?
      (cschema/ensure-full-schema! conn))
    (when (seq extra-schema)
      (d/transact conn (vec extra-schema)))
    (when (seq seed-tx)
      (d/transact conn (vec seed-tx)))
    (when register?
      (assert system-name "provision!: :register? true requires :system-name")
      (ygg/register! (dh-adapter/create conn {:system-name system-name})))
    conn))
