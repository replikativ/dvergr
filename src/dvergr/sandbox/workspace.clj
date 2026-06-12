(ns dvergr.sandbox.workspace
  "The agent's code workspace — the SCI sandbox's load root.

   The sandbox loads `.clj` files from a single directory (a fork's git worktree
   of `.dvergr/workspace`), so an agent can write code with the file tools and
   `(require '[sources.hn] :reload)` it like a normal Clojure REPL. This is the
   `:load-fn` seam that resolves a namespace symbol → a workspace source file,
   **path-clamped** so it can never read outside the workspace root. The class
   allowlist (`dvergr.sandbox/base-classes`) remains the JVM-escape barrier —
   loading source here does not widen it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.substrate.paths :as paths]))

(def ^:dynamic *workspace-dir*
  "Explicit override for the directory the sandbox loads code from (mainly for
   tests). When nil, the workspace is resolved from the bound execution context's
   git worktree — so it's automatically the current room/fork's branch of
   `.dvergr/workspace`."
  nil)

(defn- current-worktree
  "Worktree path of the git system registered in the bound execution context, or
   nil. Resolved lazily to avoid a hard dep on the yggdrasil/git stack."
  []
  (try ((requiring-resolve 'dvergr.substrate.git/current-worktree-path))
       (catch Throwable _ nil)))

(defn workspace-root
  "The single primary workspace root: the explicit override, else the current
   ctx's (per-fork) worktree, else the shared `.dvergr/workspace`."
  ^java.io.File []
  (io/file (or *workspace-dir* (current-worktree) (paths/workspace-dir))))

(def ^:dynamic *workspace-roots*
  "Ordered override list of roots the load-fn searches — a room's own repo first,
   then its attached (read-only) repos, resolved from the system-DB registry.
   When nil, falls back to the single `workspace-root`."
  nil)

(defn workspace-roots
  "Ordered roots the sandbox resolves `require` against for the current eval."
  []
  (or (seq *workspace-roots*) [(workspace-root)]))

(defn under-root?
  "True iff canonical `f` is inside canonical `root` — blocks `..`/symlink escape."
  [^java.io.File root ^java.io.File f]
  (let [rp (.getCanonicalPath root)
        fp (.getCanonicalPath f)]
    (or (= fp rp)
        (str/starts-with? fp (str rp java.io.File/separator)))))

(defn- ns->rel-paths
  "Candidate workspace-relative file paths for a namespace symbol
   (mirrors Clojure's namespace→file munging): `my-app.core` → my_app/core.clj."
  [lib]
  (let [base (-> (name lib) namespace-munge (str/replace "." "/"))]
    [(str base ".clj") (str base ".cljc")]))

(defn resolve-source
  "Resolve namespace `lib` to `{:file :source}` under `root` (a File or path
   string), or nil if absent. Path-clamped — only files genuinely inside `root`
   are read."
  [root lib]
  (let [root (io/file root)]
    (some (fn [rel]
            (let [f (io/file root rel)]
              (when (and (.isFile f) (under-root? root f))
                {:file (.getCanonicalPath f) :source (slurp f)})))
          (ns->rel-paths lib))))

(defn load-fn
  "An SCI `:load-fn`. SCI calls this for `(require …)`/`(load …)`; we return the
   source for `lib` from the current workspace (or nil → SCI throws not-found).
   Re-reads on every call, so `:reload` works for free."
  [{:keys [namespace]}]
  ;; Try the room's own + attached repos first (when bound), then always fall
  ;; back to the base workspace (current worktree / shared `.dvergr/workspace`),
  ;; so a room's agents see their own code but shared library code still resolves.
  (or (some #(resolve-source % namespace) *workspace-roots*)
      (resolve-source (workspace-root) namespace)))
