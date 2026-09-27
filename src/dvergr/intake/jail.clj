(ns dvergr.intake.jail
  "Real programs in the room shell, jailed: a command on the jail's list
   (python3, node, pytest, …) runs under bubblewrap (muschel's
   `SandboxedHost`) with the network unshared, only /usr and /etc visible
   read-only, and cgroup limits, over a disk mirror of the room's worktree.

   The worktree stays the truth. It is virtual (a Geschichte repository, forked
   with the room), and bubblewrap needs a real directory, so each spawn syncs
   the worktree into the mirror first (changed files only), runs, then syncs
   what the program changed back (written, created, deleted). A fork's shell
   has its own host and so its own mirror.

   Enabled per daemon: `:shell {:jail {:commands [\"python3\" \"pytest\"]
   :mem-max \"2G\" :tasks-max 256 :cpu-quota \"200%\"}}` in the config. A
   jailed command is still inside the shell's `:process/run` effect; with no
   network it cannot reach past the sandbox's HTTP boundary."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [muschel.fs :as mfs]
            [muschel.host :as host]
            [muschel.host.sandboxed :as sandboxed])
  (:import [java.security MessageDigest]))

(def mount-at
  "Where the worktree appears inside the jail."
  "/home/agent")

(defn- sha [^bytes bs]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest md bs)))))

(defn- virtual-files
  "`{path bytes}` of every file in the virtual worktree `fs`, paths absolute
   (`/src/a.py`)."
  [fs]
  (letfn [(walk [dir acc]
            (reduce (fn [acc {:keys [name type]}]
                      (let [p (str (if (= "/" dir) "" dir) "/" name)]
                        (case type
                          :dir (walk p acc)
                          :file (assoc acc p (mfs/read-bytes fs p))
                          acc)))
                    acc (or (mfs/list-dir fs dir) [])))]
    (walk "/" {})))

(defn- disk-files
  "`{path bytes}` of every file under `root`, paths absolute from it."
  [^java.io.File root]
  (let [base (.getCanonicalPath root)]
    (into {} (for [^java.io.File f (file-seq root) :when (.isFile f)]
               [(subs (.getCanonicalPath f) (count base))
                (java.nio.file.Files/readAllBytes (.toPath f))]))))

(defn- ensure-virtual-dirs! [fs path]
  (doseq [d (butlast (reductions #(str %1 "/" %2) "" (remove empty? (str/split path #"/"))))]
    (when-not (mfs/exists? fs d) (mfs/mkdir fs d))))

(defn- write-virtual! [fs path ^bytes bs]
  (ensure-virtual-dirs! fs path)
  (with-open [^java.io.OutputStream out (mfs/-open-sink fs path false)]
    (.write out bs)))

(defn sync-in!
  "Make `root` hold the worktree `fs`: write files whose content differs,
   delete files the worktree no longer has. Returns the digests now on disk,
   the base the sync back compares against."
  [fs ^java.io.File root]
  (let [want (virtual-files fs)
        have (disk-files root)]
    (doseq [[p bs] want
            :when (not= (some-> (get have p) sha) (sha bs))]
      (let [f (io/file root (subs p 1))]
        (io/make-parents f)
        (java.nio.file.Files/write (.toPath f) ^bytes bs (make-array java.nio.file.OpenOption 0))))
    (doseq [p (keys have) :when (not (contains? want p))]
      (.delete (io/file root (subs p 1))))
    (update-vals want sha)))

(defn sync-out!
  "Bring what a program changed under `root` back into the worktree `fs`:
   files written or created since `base` (the digests `sync-in!` left) and
   files deleted. Returns `{:written [paths] :deleted [paths]}`."
  [fs ^java.io.File root base]
  (let [now (disk-files root)
        written (sort (for [[p bs] now :when (not= (get base p) (sha bs))] p))
        deleted (sort (remove #(contains? now %) (keys base)))]
    (doseq [p written] (write-virtual! fs p (get now p)))
    (doseq [p deleted] (mfs/delete fs p))
    {:written (vec written) :deleted (vec deleted)}))

(defn host
  "A host for the builtin host's fallback: every spawn syncs the worktree `fs`
   into `mirror` (a directory), runs under bubblewrap there with `opts`
   (`:mem-max`, `:cpu-quota`, `:tasks-max`), and syncs changes back when the
   program exits. Everything else is `wrapped`'s."
  [wrapped fs ^java.io.File mirror opts]
  (let [work (io/file mirror "home" "agent")
        _ (.mkdirs work)
        jailed (sandboxed/make (merge {:wrapped wrapped
                                       :bind-root (.getCanonicalPath mirror)
                                       :mounts [[mount-at "home/agent"]]
                                       :net :off}
                                      (select-keys opts [:mem-max :cpu-quota :tasks-max])))
        lock (Object.)]
    (reify host/Host
      (-write-string!    [_ sink s]  (host/-write-string! wrapped sink s))
      (-read-all-string  [_ source]  (host/-read-all-string wrapped source))
      (-close!           [_ io]      (host/-close! wrapped io))
      (-string-sink      [_]         (host/-string-sink wrapped))
      (-sink->string     [_ sink]    (host/-sink->string wrapped sink))
      (-string-source    [_ s]       (host/-string-source wrapped s))
      (-open-file-sink   [_ p ap?]   (host/-open-file-sink wrapped p ap?))
      (-open-file-source [_ p]       (host/-open-file-source wrapped p))
      (-file-info        [_ p]       (host/-file-info wrapped p))
      (-read-file        [_ p]       (host/-read-file wrapped p))
      (-make-pipe        [_]         (host/-make-pipe wrapped))
      (-async            [_ thunk]   (host/-async wrapped thunk))
      (-await            [_ h]       (host/-await wrapped h))
      (-spawn [_ {:keys [dir] :as spawn-opts}]
        ;; one program at a time over a mirror: syncs must not interleave
        (locking lock
          (let [base (sync-in! fs work)
                {:keys [wait] :as proc}
                (host/-spawn jailed (-> spawn-opts
                                        (dissoc :fs)
                                        (assoc :dir (str mount-at (if (or (nil? dir) (= "/" dir)) "" dir)))))
                exit (wait)]
            (sync-out! fs work base)
            (assoc proc :wait (constantly exit))))))))
