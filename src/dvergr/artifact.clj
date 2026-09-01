(ns dvergr.artifact
  "Content-addressed storage for exact portable values.

   Durable domain projections keep typed, queryable attributes in Datahike and
   place open-ended immutable values behind these references. The default
   implementation reuses Dvergr's blob CAS; tests and ephemeral stores may use
   the in-memory implementation."
  (:require [clojure.edn :as edn]
            [dvergr.drive.blobs :as blobs])
  (:import [java.nio.charset StandardCharsets]))

(defprotocol PArtifactStore
  (-put-value! [this value]
    "Store one EDN value and return its immutable string content reference.")
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

(defrecord MemoryArtifactStore [values]
  PArtifactStore
  (-put-value! [_ value]
    (let [bytes (encode value)
          ref (blobs/sha256-hex bytes)]
      (swap! values #(if (contains? % ref) % (assoc % ref value)))
      ref))
  (-get-value [_ ref]
    (get @values ref)))

(defn memory-store [] (->MemoryArtifactStore (atom {})))

(defn put-value! [store value]
  (-put-value! store value))

(defn get-value [store ref]
  (-get-value store ref))
