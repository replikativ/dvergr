(ns dvergr.drive.blobs
  "Content-addressed blob storage: a konserve file store keyed by the SHA-256
   of the content. Bytes-by-hash — the drive tree (dvergr.drive.core) references
   blobs by hash; metadata (name, mime, size) lives with the tree node. Global,
   not per-room: CAS dedup is content-based, so identical bytes uploaded to
   different rooms are stored once.

   First consumers: Telegram voice notes / documents, web uploads. Under
   `.dvergr/blobs/`."
  (:require [konserve.filestore :refer [connect-fs-store]]
            [konserve.core :as k]
            [dvergr.substrate.paths :as paths]
            [taoensso.telemere :as log])
  (:import [java.security MessageDigest]))

(defonce ^:private store
  (delay (connect-fs-store (paths/dir "blobs") :opts {:sync? true})))

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
