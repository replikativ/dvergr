(ns dvergr.substrate.paths
  "Single project-local root for dvergr's on-disk state: `.dvergr/`.

   Everything dvergr writes at runtime — the chat/KB/code Datahike store, git
   fork worktrees, the Lucene search index, intake transcripts,
   the log file — lives under one directory so it's obvious, easy to .gitignore,
   and trivial to reset (`rm -rf .dvergr`).

   Resolution priority for the root (see `home`):
     1. an explicit `(set-home! \"…\")` from Clojure
     2. the `DVERGR_HOME` env var
     3. `./.dvergr` (cwd-relative — this is PROJECT-based state, not per-user)

   The `.nrepl-port` file is deliberately NOT under here — editors and
   `clj-nrepl-eval --discover-ports` expect it at the project root."
  (:require [clojure.java.io :as io]))

(def ^:private home-atom (atom nil))

(defn set-home!
  "Override the state root from Clojure. Pass a path (String/File) or nil to
   fall back to the `DVERGR_HOME` env var / `./.dvergr` default."
  [path]
  (reset! home-atom (when path (str path))))

(defn home
  "The dvergr state root as an absolute path String. Priority:
   `set-home!` override → `DVERGR_HOME` env → `./.dvergr`. Ensures the
   directory exists."
  []
  (let [^java.io.File f (io/file (or @home-atom
                                     (System/getenv "DVERGR_HOME")
                                     ".dvergr"))]
    (.mkdirs f)
    (.getAbsolutePath f)))

(defn path
  "Absolute path String for `segments` under the state root. Ensures the parent
   directory exists (so callers can write a file or create a store there)."
  [& segments]
  (let [^java.io.File f (apply io/file (home) segments)]
    (some-> (.getParentFile f) (.mkdirs))
    (.getAbsolutePath f)))

(defn dir
  "Like `path`, but ensures the resulting directory itself exists (for stores
   that expect their target dir to be present)."
  [& segments]
  (let [p (apply path segments)]
    (.mkdirs (io/file p))
    p))

;; Named locations — the canonical layout under `.dvergr/`.
;;
;; IMPORTANT: a path that is *itself* a store directory (datahike's file store)
;; must use `path`, NOT `dir` — datahike/konserve create their own store dir and
;; throw "File store already exists" if it's pre-created. Only CONTAINER dirs
;; (whose real store/file is a child) may be pre-created with `dir`.
(defn db-dir           "Chat/KB/code Datahike file store (datahike makes it)." [] (path "db"))
(defn worktrees-dir    "Git worktrees container (git makes <branch> under it)." [] (dir "worktrees"))
(defn workspace-dir    "Agent code workspace — a dedicated git repo, the SCI sandbox's load root." [] (dir "workspace"))
(defn system-db-dir    "System DB store: registry of parties/systems/rooms/grants (datahike makes it)." [] (path "system-db"))
(defn systems-dir      "Per-system store container (each KB/repo scope gets a child under it)." [] (dir "systems"))
(defn transcripts-dir  "Intake transcript cache (files under it)." [] (dir "transcripts"))
(defn log-file         "Daemon/substrate log file."              [] (path "dvergr.log"))
