(ns dvergr.artifact
  "Content-addressed storage for exact portable values.

   Durable domain projections keep typed, queryable attributes in Datahike and
   place exact immutable payloads behind GC-tracked UUID references in the
   database's own store. String references remain readable from the legacy CAS."
  (:require [clojure.edn :as edn]
            [datahike.blob :as blob]
            [datahike.gc-guard :as guard]
            [datahike.store :as ds]
            [konserve.core :as k]
            [dvergr.drive.blobs :as blobs])
  (:import [java.nio.charset StandardCharsets]))

(defprotocol PArtifactStore
  (-put-value! [this value]
    "Store one EDN value in an unmanaged store. Managed stores require publish-value!.")
  (-get-value [this ref]
    "Load the value named by `ref`, or nil when the content is unavailable."))

(defn- encode [value]
  (.getBytes (pr-str value) StandardCharsets/UTF_8))

(defn- decode [bytes]
  (edn/read-string (String. ^bytes bytes StandardCharsets/UTF_8)))

(defrecord BlobArtifactStore []
  PArtifactStore
  (-put-value! [_ value]
    (:blob/id (blobs/store! (encode value) "application/edn")))
  (-get-value [_ ref]
    (some-> (blobs/get-bytes ref) decode)))

(defn blob-store [] (->BlobArtifactStore))

(defprotocol PArtifactPublication
  (-publish-value! [this value publish]
    "Write immutable bytes and call publish with their reference while GC-protected.
     publish must synchronously finish the referencing transaction."))

(defrecord DatahikeArtifactStore [conn]
  PArtifactStore
  (-put-value! [_ _]
    (throw (ex-info "Datahike artifacts require publish-value!" {})))
  (-get-value [_ ref]
    (if (string? ref)
      ;; Historical string payloads remain in the legacy global blob store.
      (-get-value (blob-store) ref)
      (k/bget (:store @conn) ref
              (fn [{:keys [input-stream]}]
                (when input-stream
                  (decode (if (bytes? input-stream) input-stream
                              (.readAllBytes ^java.io.InputStream input-stream)))))
              {:sync? true})))
  PArtifactPublication
  (-publish-value! [_ value publish]
    (let [db @conn
          bytes (encode value)
          ref (blob/blob-id bytes)]
      (guard/with-unreferenced-writes (ds/canonical-store-id (:store db) (get-in db [:config :store]))
        ;; Rewrite even on dedup: refresh the timestamp under the guard so an
        ;; older unreferenced copy cannot race a concurrent sweep.
        (k/bassoc (:store db) ref bytes {:sync? true})
        (publish ref)))))

(defn datahike-store [conn] (->DatahikeArtifactStore conn))

(defrecord MemoryArtifactStore [values]
  PArtifactStore
  (-put-value! [_ value]
    (let [bytes (encode value)
          ref (blob/blob-id bytes)]
      (swap! values #(if (contains? % ref) % (assoc % ref value)))
      ref))
  (-get-value [_ ref]
    (get @values ref)))

(defn memory-store [] (->MemoryArtifactStore (atom {})))

(defn put-value! [store value]
  (-put-value! store value))

(defn get-value [store ref]
  (-get-value store ref))

(defn publish-value!
  "Publish a payload reference, guarding managed bytes until publish returns.
   The callback must finish its transaction here, not return a future/Spin.
   Failure leaves an unreferenced object eligible for normal collection."
  [store value publish]
  (if (satisfies? PArtifactPublication store)
    (-publish-value! store value publish)
    (publish (-put-value! store value))))
