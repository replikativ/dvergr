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
  (:import [java.nio.file Files StandardCopyOption FileAlreadyExistsException]))

(defn id-file
  "The file holding the id of the store at `path`."
  ^java.io.File [path]
  (io/file (str path ".id")))

(defn- read-id [^java.io.File f]
  (when (.exists f)
    (or (parse-uuid (str/trim (slurp f)))
        (throw (ex-info (str "Not a store id: " f) {:type ::malformed-id :file (str f)})))))

(defn- write-new!
  "Write `id` to `f` unless it exists; the id that is there afterwards."
  [^java.io.File f id]
  (io/make-parents f)
  (let [tmp (io/file (str f ".tmp-" (random-uuid)))]
    (spit tmp (str id "\n"))
    (try (Files/move (.toPath tmp) (.toPath f) (into-array [StandardCopyOption/ATOMIC_MOVE]))
         id
         (catch FileAlreadyExistsException _ (read-id f))
         (finally (.delete tmp)))))

(defn store-exists?
  "Whether a store has been created at `path`: a non-empty directory."
  [path]
  (let [f (io/file path)]
    (boolean (and (.isDirectory f) (seq (.list f))))))

(defonce ^:private ids (atom {}))

(defn store-id
  "The id of the file store at `path`: the one recorded beside it, else, for a
   store an older version created, `(legacy path)`, else a fresh one. Recorded
   before the store exists, so every config built for one store names one id."
  [path legacy]
  (let [path (str path)
        ;; only while its file is there: a store deleted with its directory
        ;; (not through `forget!`) gets a new id when it is created again
        cached #(when (.exists (id-file path)) (get @ids path))]
    (or (cached)
        (locking ids
          (or (cached)
              (let [f (id-file path)
                    id (or (read-id f)
                           (write-new! f (if (store-exists? path) (legacy path) (random-uuid))))]
                (swap! ids assoc path id)
                id))))))

(defn record!
  "Record `id` for the store at `path` when it has none (an older store whose
   id is known from elsewhere). The id recorded afterwards."
  [path id]
  (let [path (str path)]
    (locking ids
      (let [id (or (read-id (id-file path)) (write-new! (id-file path) id))]
        (swap! ids assoc path id)
        id))))

(defn forget!
  "Remove the id of the store at `path`, after the store was deleted, so a store
   created again there gets its own."
  [path]
  (let [path (str path)]
    (locking ids
      (.delete (id-file path))
      (swap! ids dissoc path)
      nil)))

(defn forget-all!
  "Drop the in-memory ids (tests: a store's id file is read again)."
  []
  (reset! ids {})
  nil)

(defn path-derived
  "The id an older version derived from `prefix` and `path`."
  [prefix path]
  (java.util.UUID/nameUUIDFromBytes (.getBytes (str prefix path) "UTF-8")))
