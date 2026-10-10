(ns dvergr.substrate.store-ids
  "The id of each file store dvergr creates, kept beside the store.

   Konserve's `:id` is a logical id that the caller chooses and passes in: the
   store keeps no state about it (`konserve.store/validate-store-config`), and
   Datahike refuses a store whose recorded id differs from the one passed. So
   dvergr, as the caller, keeps each store's id in a file next to its
   directory, `<store>.id`, written before the store is created. The file moves
   with the store, so a home moved to another directory opens its stores; an
   id derived from the path, as older versions did, would change with it.

   A store an older version created has no id file. Its id is the one that
   version derived from the store's path, computed by the caller's `legacy`
   function and recorded on first use. That is right as long as the store has
   not moved since; after a move, `dvergr.system.db/rehome-scopes!` records the
   ids of registered stores from the paths the registry holds.

   A copy of a store carries its id file and is the same logical store (a
   replica), never an independent one: dvergr makes no copies of stores (an
   attempt's world is a copy-on-write fork), and `dvergr.system.home` refuses
   a copied home while its original exists."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.channels FileChannel]
           [java.nio.file Files FileAlreadyExistsException StandardOpenOption]))

(defn id-file
  "The file holding the id of the store at `path`."
  ^java.io.File [path]
  (io/file (str path ".id")))

(defn- read-entry
  "The id in `f` and whether it was derived from a path (not yet confirmed by
   the store), or nil when there is no file."
  [^java.io.File f]
  (when (.exists f)
    (let [[id tag] (str/split (str/trim (slurp f)) #"\s+")]
      (if-let [id (parse-uuid (str id))]
        {:id id :derived? (= "derived" tag)}
        (throw (ex-info (str "Not a store id: " f) {:type ::malformed-id :file (str f)}))))))

(defn- read-id [f] (:id (read-entry f)))

(defn- fsync! [^java.nio.file.Path p]
  (with-open [ch (FileChannel/open p (into-array [StandardOpenOption/READ]))]
    (.force ch true)))

(defonce ^:private durable (atom #{}))

(defn- durable!
  "Make `f`'s directory entry durable (once per file in this process): every
   id returned may be one a store is created with next."
  [^java.io.File f]
  (let [k (str f)]
    (when-not (@durable k)
      (fsync! (.toPath f))
      (fsync! (.toPath (.getParentFile (.getAbsoluteFile f))))
      (swap! durable conj k))))

(defn- write-new!
  "Publish `id` in `f` unless `f` exists, durably: the id that is there
   afterwards. Exclusive across processes (a hard link fails when the name is
   taken), and synced, file and directory, before it returns, so a store
   created after it never outlives its id in a crash. `derived?` marks an id
   derived from a path, which the store has not confirmed."
  ([f id] (write-new! f id false))
  ([^java.io.File f id derived?]
   (io/make-parents f)
   (let [target (.toPath f)
         tmp (.toPath (io/file (str f ".tmp-" (random-uuid))))]
     (try
       (spit (.toFile tmp) (str id (when derived? " derived") "\n"))
       (fsync! tmp)
       (try (Files/createLink target tmp)
            (catch FileAlreadyExistsException _ nil))
       (durable! f)
       (read-id f)
       (finally (Files/deleteIfExists tmp))))))

(defn store-exists?
  "Whether a store has been created at `path`: a non-empty directory."
  [path]
  (let [f (io/file path)]
    (boolean (and (.isDirectory f) (seq (.list f))))))

(defonce ^:private lock (Object.))

(defn store-id
  "The id of the file store at `path`: the one recorded beside it, else, for a
   store an older version created, `(legacy path)`, else a fresh one. Recorded
   before the store exists, so every config built for one store names one id.
   Read from the file each time: another process may have replaced the store
   and its id."
  [path legacy]
  (let [f (id-file path)]
    (or (when-let [id (read-id f)] (durable! f) id)
        (locking lock
          (or (when-let [id (read-id f)] (durable! f) id)
              (if (store-exists? path)
                (write-new! f (legacy (str path)) true)
                (write-new! f (random-uuid))))))))

(defn record!
  "Record `id` for the store at `path` when it has none (an older store whose
   id is known from elsewhere). The id recorded afterwards."
  [path id]
  (let [f (id-file path)]
    (locking lock
      (or (when-let [id (read-id f)] (durable! f) id)
          (write-new! f id true)))))

(defn forget!
  "Remove the id of the store at `path`, after the store was deleted, so a store
   created again there gets its own."
  [path]
  (locking lock
    (Files/deleteIfExists (.toPath (id-file path)))
    (swap! durable disj (str (id-file path)))
    nil))

(defn path-derived
  "The id an older version derived from `prefix` and `path`, in UTF-8."
  [prefix path]
  (java.util.UUID/nameUUIDFromBytes (.getBytes (str prefix path) "UTF-8")))

(defn path-derived-default-charset
  "The id an older version derived from `path` in the JVM's default charset (room
   stores and the system database did)."
  [path]
  (java.util.UUID/nameUUIDFromBytes (.getBytes (str path))))

(defn identity-mismatch-hint
  "`e`, or, when it is Datahike's store identity mismatch, an error that says
   what it means here: the store at `path` was created with another id than
   the one recorded beside it. For a store an older version created, that is a
   home moved before a version that records store ids first opened it."
  [path e]
  (if (= :store-identity-mismatch (:type (ex-data e)))
    (do
      ;; an id derived from a path that the store just refused is not its id:
      ;; drop it, so the home opens again at its original place
      (locking lock
        (when (:derived? (read-entry (id-file path)))
          (forget! path)))
      (ex-info (str "The store at " path " was created with a different id than " (id-file path)
                    " names. If this home was moved before a dvergr version that keeps store ids "
                    "first opened it, open it once at its original place, then move it.")
               {:type ::identity-mismatch :path (str path)}
               e))
    e))
