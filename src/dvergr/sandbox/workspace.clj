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
            [dvergr.substrate.paths :as paths]
            [muschel.fs :as mfs]))

(def ^:dynamic *workspace-dir*
  "Explicit override for the directory the sandbox loads code from (mainly for
   tests). When nil, the workspace is resolved from the bound execution context's
   git worktree — so it's automatically the current room/fork's branch of
   `.dvergr/workspace`."
  nil)

(defn- current-virtual-workspace
  "Virtual Geschichte workspace registered in the bound execution context."
  []
  (try ((requiring-resolve 'dvergr.substrate.geschichte/current-workspace))
       (catch Throwable _ nil)))

(defn workspace-root
  "The primary workspace root. Geschichte workspaces return a virtual root
  descriptor; explicit test overrides and the transitional fallback remain
  physical paths."
  []
  (or (some-> *workspace-dir* io/file)
      (when-let [workspace (current-virtual-workspace)]
        {:id (:id workspace)
         :root "/"
         :fs ((requiring-resolve 'dvergr.substrate.geschichte/filesystem)
              workspace)})
      (io/file (paths/workspace-dir))))

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

(defn virtual-root? [root]
  (and (map? root) (satisfies? mfs/FS (:fs root))))

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
  (if (virtual-root? root)
    (some (fn [rel]
            (let [path (str (str/replace (:root root) #"/$" "") "/" rel)]
              (when (= :file (:type (mfs/stat (:fs root) path)))
                {:file (str "geschichte:" (:id root) ":" path)
                 :source (mfs/read-file (:fs root) path)})))
          (ns->rel-paths lib))
    (let [root (io/file root)]
      (some (fn [rel]
              (let [f (io/file root rel)]
                (when (and (.isFile f) (under-root? root f))
                  {:file (.getCanonicalPath f) :source (slurp f)})))
            (ns->rel-paths lib)))))

(def ^:private source-subdirs
  "Per workspace root, the source-root candidates the load-fn searches, in
   order. `\"\"` is the worktree root itself (a file written directly there);
   `\"src\"` is the conventional Clojure source root (what `deps.edn` projects —
   and agents following the convention — use). Without `\"src\"`, an agent that
   wrote `src/my/ns.clj` (the natural layout) could not `(require 'my.ns)` and
   was forced to inline its code into the caller — defeating the
   write-a-namespace-then-require pipeline pattern the prompt teaches."
  ["" "src"])

(defn- resolve-in-root
  "Resolve `lib` under `root`, trying each source-subdir (root, root/src)."
  [root lib]
  (some (fn [sub]
          (resolve-source
           (cond
             (str/blank? sub) root
             (virtual-root? root) (update root :root #(str % "/" sub))
             :else (io/file root sub))
           lib))
        source-subdirs))

(defn workspace-guide
  "The workspace's own `AGENTS.md` — its self-description and stdlib map
   (the intake catalog, the copy-a-source pattern, the conventions),
   read from the ctx-bound worktree root, path-clamped, or nil. Meant to
   be spliced into the agent's system prompt so it always sees its
   workspace's guidance — the AGENTS.md/CLAUDE.md convention. Kept small
   by design (the file is the summary; INTAKES.md et al. are read on
   demand). `max-chars` caps a runaway edit (default 8k)."
  ([] (workspace-guide (workspace-root) 8192))
  ([root] (workspace-guide root 8192))
  ([root max-chars]
   (try
     (let [s (if (virtual-root? root)
               (mfs/read-file (:fs root)
                              (str (str/replace (:root root) #"/$" "")
                                   "/AGENTS.md"))
               (let [root (io/file root)
                     f (io/file root "AGENTS.md")]
                 (when (and (.isFile f) (under-root? root f))
                   (slurp f))))]
       (when s (if (> (count s) max-chars) (subs s 0 max-chars) s)))
     (catch Throwable _ nil))))

(defn load-fn
  "An SCI `:load-fn`. SCI calls this for `(require …)`/`(load …)`; we return the
   source for `lib` from the current workspace (or nil → SCI throws not-found).
   Re-reads on every call, so `:reload` works for free. Each workspace root is
   searched both at its top level and under `src/` (see `source-subdirs`)."
  [{:keys [namespace]}]
  ;; Try the room's own + attached repos first (when bound), then always fall
  ;; back to the base workspace (current worktree / shared `.dvergr/workspace`),
  ;; so a room's agents see their own code but shared library code still resolves.
  (or (some #(resolve-in-root % namespace) *workspace-roots*)
      (resolve-in-root (workspace-root) namespace)))
