(ns dvergr.drive.blobs
  "Content-addressed blob storage: a konserve store keyed by the SHA-256 of the
   content. Bytes-by-hash — the drive tree (dvergr.drive.core) references blobs
   by hash; metadata (name, mime, size) lives with the tree node. Global, not
   per-room: CAS dedup is content-based, so identical bytes uploaded to different
   rooms are stored once.

   Backend is konserve-config-driven via the generic `connect-store` lifecycle,
   so the store is selected by its `:backend` and any konserve backend works by
   config alone. Default: a filestore under `.dvergr/blobs`. Override with
   `set-store-config!` (the daemon sets it from `config.local.edn :blob-store`)
   — e.g. `{:backend :s3 :bucket \"…\" :region \"…\"}` or a `:tiered` store.

   First consumers: Telegram voice notes / documents, web uploads."
  (:require [konserve.store :as kstore]
            [konserve.core :as k]
            [dvergr.substrate.paths :as paths]
            [taoensso.telemere :as log])
  (:import [java.security MessageDigest]))

(defonce ^:private store-config (atom nil))

(defn set-store-config!
  "Set the blob store's konserve config (a `{:backend …}` map) — overrides the
   filestore default. Must be called before first blob access."
  [config]
  (reset! store-config config))

(defn- resolve-config []
  (or @store-config
      (let [path (paths/dir "blobs")]
        ;; konserve requires a UUID :id (stable store identity across restarts /
        ;; backends). Derive it deterministically from the path.
        {:backend :file
         :path    path
         :id      (java.util.UUID/nameUUIDFromBytes (.getBytes (str "dvergr-blobs:" path) "UTF-8"))
         :opts    {:sync? true}})))

(defn- connect-or-create!
  "Connect to the store, creating it first if it doesn't exist yet (the generic
   konserve `connect-store` only connects — `:file` throws when absent)."
  [config]
  (let [opts {:sync? true}]
    (if (kstore/store-exists? config opts)
      (kstore/connect-store config opts)
      (kstore/create-store config opts))))

(defonce ^:private store
  (delay (connect-or-create! (resolve-config))))

(defn sha256-hex [^bytes bs]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") bs)]
    (apply str (map #(format "%02x" %) d))))

(defn store!
  "Store bytes content-addressed. Returns {:blob/id sha :blob/mime m
   :blob/size n}. Idempotent (same content → same id, single copy)."
  [^bytes bytes mime]
  (let [id (sha256-hex bytes)]
    (k/bassoc @store id bytes {:sync? true})
    (log/log! {:level :debug :id ::blob-stored
               :data {:blob id :size (count bytes) :mime mime}})
    {:blob/id id :blob/mime mime :blob/size (count bytes)}))

(defn get-bytes
  "Fetch blob bytes by id, nil when absent."
  [id]
  (try
    (k/bget @store id
            (fn [{:keys [input-stream]}]
              (.readAllBytes ^java.io.InputStream input-stream))
            {:sync? true})
    (catch Exception _ nil)))
