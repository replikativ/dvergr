(ns dvergr.drive.fs
  "muschel.fs/FS over a drive (dvergr.drive.core): the datahike fs.node tree +
   CAS blobs exposed as a plain filesystem.

   Mounted into the agent's shell host (muschel.fs.mount) at /drive, this makes
   the room's drive transparently reachable with the bash idioms agents already
   use — ls, cat, grep, echo >, mkdir, mv — through the EXISTING shell tool with
   permits and tracing intact. No bespoke fs_* LLM tools.

   Self-rooted: this FS's `/` is the drive root; the mount layer rebases sandbox
   paths. Writes commit through the drive API (put-file! → CAS + node upsert),
   so datahike history keeps every version; blobs stay in the CAS."
  (:require [dvergr.drive.core :as core]
            [muschel.fs :as mfs]
            [clojure.string :as str]))

(def ^:private ^:const read-cap-bytes (* 8 1024 1024))

(defn- canonicalize
  "Resolve `path` against `cwd`, collapse `.`/`..`. Nil on escape past /."
  [cwd path]
  (when (string? path)
    (let [combined   (if (str/starts-with? path "/") path (str cwd "/" path))
          normalized (mfs/normalize-segments (mfs/split-path combined))]
      (when normalized
        (let [p (mfs/join-path "" normalized)]
          (if (= "" p) "/" p))))))

(defn- drive-path
  "Canonical sandbox path → drive-relative path string (\"\" = root)."
  [p]
  (str/replace p #"^/" ""))

(defn- node-at [conn p]
  (if (= "/" p) ::root (core/resolve-path conn (drive-path p))))

(defn- parent-and-name [p]
  (let [segs (vec (remove str/blank? (str/split p #"/")))]
    [(pop segs) (peek segs)]))

(defn- dir-id-at
  "fs.node id of the DIR at path `p` (nil for root, ::none when the path is
   missing or a file)."
  [conn p]
  (if (= "/" p)
    nil
    (let [n (core/resolve-path conn (drive-path p))]
      (if (and n (= :dir (:fs.node/kind n)))
        (:fs.node/id n)
        ::none))))

(defn- node->entry [n]
  {:name (:fs.node/name n)
   :type (:fs.node/kind n)
   :size (or (:fs.node/size n) 0)
   :mtime-ms (some-> ^java.util.Date (:fs.node/mtime n) .getTime)})

(defrecord DriveFS [conn cwd-atom]
  mfs/FS
  (-resolve [_ path] (canonicalize @cwd-atom path))
  (-cwd [_] @cwd-atom)
  (-cd! [_ path]
    (when-let [p (canonicalize @cwd-atom path)]
      (let [d (dir-id-at conn p)]
        (when (not= ::none d) (reset! cwd-atom p) p))))

  (-exists? [_ path]
    (when-let [p (canonicalize @cwd-atom path)] (some? (node-at conn p))))

  (-stat [_ path]
    (when-let [p (canonicalize @cwd-atom path)]
      (if (= "/" p)
        {:type :dir :size 0 :mtime-ms 0 :perms nil}
        (when-let [n (node-at conn p)]
          (when (map? n) (assoc (node->entry n) :perms nil))))))

  (-list-dir [_ path]
    (when-let [p (canonicalize @cwd-atom path)]
      (let [d (dir-id-at conn p)]
        (when (not= ::none d) (mapv node->entry (core/ls conn d))))))

  (-read-file [this path]
    (when-let [^bytes bs (mfs/-read-bytes this path)] (String. bs "UTF-8")))

  (-read-bytes [_ path]
    (when-let [p (canonicalize @cwd-atom path)]
      (let [n (node-at conn p)]
        (when (and (map? n) (= :file (:fs.node/kind n)))
          (when-let [^bytes bs (core/read-file conn (:fs.node/id n))]
            (if (> (alength bs) read-cap-bytes)
              (java.util.Arrays/copyOf bs read-cap-bytes)
              bs))))))

  (-open-source [this path]
    (when-let [^bytes bs (mfs/-read-bytes this path)]
      (java.io.ByteArrayInputStream. bs)))

  (-open-sink [this path append?]
    (when-let [p (canonicalize @cwd-atom path)]
      (when (not= "/" p)
        (let [[dir-segs name] (parent-and-name p)
              parent-p (str "/" (str/join "/" dir-segs))
              parent-d (dir-id-at conn (if (= "/" parent-p) "/" parent-p))]
          (when (not= ::none parent-d)
            (let [existing (when append?
                             (let [n (node-at conn p)]
                               (when (and (map? n) (= :file (:fs.node/kind n)))
                                 (some-> ^bytes (core/read-file conn (:fs.node/id n))
                                         (String. "UTF-8")))))
                  acc (atom (or existing ""))
                  commit! (fn [^String s]
                            (core/put-file! conn parent-d name (.getBytes s "UTF-8")
                                            :mime "text/plain" :source :agent))]
              (commit! @acc)
              (add-watch acc ::drive-flush (fn [_ _ _ new] (commit! new)))
              {:acc acc ::path p ::drive-sink? true}))))))

  (-mkdir [_ path]
    (when-let [p (canonicalize @cwd-atom path)]
      (when (not= "/" p)
        (when (nil? (node-at conn p))
          (let [[dir-segs name] (parent-and-name p)
                parent-p (str "/" (str/join "/" dir-segs))
                parent-d (dir-id-at conn (if (= "/" parent-p) "/" parent-p))]
            (when (not= ::none parent-d) (core/mkdir! conn parent-d name) true))))))

  (-delete [_ path]
    (when-let [p (canonicalize @cwd-atom path)]
      (when-let [n (node-at conn p)]
        (when (map? n)
          (try (core/rm! conn (:fs.node/id n)) true (catch Exception _ nil))))))

  (-rename [_ from to]
    (let [pf (canonicalize @cwd-atom from)
          pt (canonicalize @cwd-atom to)]
      (when (and pf pt (not= "/" pf) (not= "/" pt))
        (let [n (node-at conn pf)]
          (when (and (map? n) (nil? (node-at conn pt)))
            (let [[dir-segs name] (parent-and-name pt)
                  parent-p (str "/" (str/join "/" dir-segs))
                  parent-d (dir-id-at conn (if (= "/" parent-p) "/" parent-p))]
              (when (not= ::none parent-d)
                (core/mv! conn (:fs.node/id n) :name name :parent-id parent-d)
                true)))))))

  (-touch [this path]
    (when-let [p (canonicalize @cwd-atom path)]
      (when (not= "/" p)
        (if-let [n (node-at conn p)]
          (when (map? n) (core/mv! conn (:fs.node/id n)) true)
          (when-let [sink (mfs/-open-sink this p false)]
            (remove-watch (:acc sink) ::drive-flush)
            true)))))

  (-chmod [_ _ _] nil)
  (-symlink [_ _ _] nil)
  (-chown [_ _ _ _] nil)
  (-sandbox-relativize [_ path] path)
  (-physical-path [_ _] nil))

(defn make
  "DriveFS over a connected drive tree conn (dvergr.drive.core/ensure-room-drive!)."
  [drive-conn]
  (->DriveFS drive-conn (atom "/")))
