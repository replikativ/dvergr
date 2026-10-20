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
  (:import [java.nio.charset StandardCharsets CodingErrorAction]
           [java.io InputStream PushbackReader StringReader]))

(defprotocol PArtifactStore
  (-put-value! [this value]
    "Store one EDN value in an unmanaged store. Managed stores require publish-value!.")
  (-get-value [this ref]
    "Load the value named by `ref`, or nil when the content is unavailable."))

(defn- encode [value]
  (.getBytes (pr-str value) StandardCharsets/UTF_8))

(defn- decode [bytes]
  (edn/read-string (String. ^bytes bytes StandardCharsets/UTF_8)))

(defn prepare-value
  "Encode a value with the SAME codec and content identity used by publication.
   The bounded arity refuses rather than truncating. Prepared bytes are host-only."
  ([value] (prepare-value value Long/MAX_VALUE))
  ([value max-bytes]
   (let [bytes (encode value)]
     (when (> (alength ^bytes bytes) max-bytes)
       (throw (ex-info "Artifact exceeds encoded byte limit" {:type ::too-large})))
     {:value value :ref (blob/blob-id bytes) :bytes bytes
      :size (long (alength ^bytes bytes)) :codec :edn-v1})))

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
          {:keys [bytes ref]} (prepare-value value)]
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

(defn- check-edn-depth! [^String text]
  ;; Prepared conversation values exclude character literals and unreadable
  ;; objects. Bound delimiter depth BEFORE invoking the recursive EDN reader.
  (loop [i 0 depth 0 quoted? false escaped? false]
    (when (< i (.length text))
      (let [c (.charAt text i)]
        (cond
          escaped? (recur (inc i) depth quoted? false)
          (and quoted? (= c \\)) (recur (inc i) depth true true)
          (= c \") (recur (inc i) depth (not quoted?) false)
          quoted? (recur (inc i) depth true false)
          (or (= c \\) (= c \;))
          (throw (ex-info "Unsupported bounded artifact syntax" {:type ::invalid-value}))
          (#{\( \[ \{} c)
          (let [next-depth (inc depth)]
            (when (> next-depth 64)
              (throw (ex-info "Artifact nesting exceeds limit" {:type ::too-deep})))
            (recur (inc i) next-depth false false))
          (#{\) \] \}} c) (recur (inc i) (dec depth) false false)
          :else (recur (inc i) depth false false))))))

(defn get-bounded-value
  "Read one managed UUID artifact with an ACTUAL byte and parser-depth bound.
   No legacy/unbounded fallback. Returns nil when absent; verifies content ID."
  [store ref max-bytes]
  (when-not (and (uuid? ref) (integer? max-bytes) (pos? max-bytes)
                 (< max-bytes Integer/MAX_VALUE))
    (throw (ex-info "Invalid bounded artifact request" {:type ::invalid-request})))
  (when-not (instance? DatahikeArtifactStore store)
    (throw (ex-info "Store has no bounded managed artifact reader" {:type ::unsupported-store})))
  (k/bget (:store @(:conn store)) ref
          (fn [{:keys [input-stream]}]
            (when input-stream
              (let [bytes (if (bytes? input-stream)
                            (do (when (> (alength ^bytes input-stream) max-bytes)
                                  (throw (ex-info "Artifact exceeds read limit" {:type ::too-large})))
                                input-stream)
                            (.readNBytes ^InputStream input-stream (int (inc max-bytes))))]
                (when (> (alength ^bytes bytes) max-bytes)
                  (throw (ex-info "Artifact exceeds read limit" {:type ::too-large})))
                (when-not (= ref (blob/blob-id bytes))
                  (throw (ex-info "Artifact identity mismatch" {:type ::identity-mismatch})))
                (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                                (.onMalformedInput CodingErrorAction/REPORT)
                                (.onUnmappableCharacter CodingErrorAction/REPORT))
                      text (str (.decode decoder (java.nio.ByteBuffer/wrap bytes)))]
                  (check-edn-depth! text)
                  (with-open [reader (PushbackReader. (StringReader. text))]
                    (let [value (edn/read {:eof ::eof} reader)]
                      (when (or (= ::eof value) (not= ::eof (edn/read {:eof ::eof} reader)))
                        (throw (ex-info "Artifact must contain one EDN value" {:type ::invalid-value})))
                      {:value value :ref ref :size (long (alength ^bytes bytes))
                       :codec :edn-v1}))))))
          {:sync? true}))

(defn publish-value!
  "Publish a payload reference, guarding managed bytes until publish returns.
   The callback must finish its transaction here, not return a future/Spin.
   Failure leaves an unreferenced object eligible for normal collection."
  [store value publish]
  (if (satisfies? PArtifactPublication store)
    (-publish-value! store value publish)
    (publish (-put-value! store value))))
