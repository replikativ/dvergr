(ns dvergr.substrate.geschichte
  "Geschichte-backed room workspaces.

  A workspace is a Datahike branch and a Muschel virtual filesystem, not a
  native checkout. The only native repository access in this namespace is the
  trusted bootstrap import from `../dvergr-sandbox`; production bootstrap uses
  Geschichte smart HTTP directly."
  (:require [clojure.java.io :as io]
            [dvergr.substrate.datahike :as sdh]
            [clojure.string :as str]
            [datahike.api :as d]
            [dvergr.substrate.paths :as paths]
            [geschichte.git.command :as command]
            [geschichte.git.http :as git-http]
            [geschichte.git.local :as git-local]
            [geschichte.repo :as repo]
            [muschel.fs.geschichte :as mgeschichte]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [taoensso.telemere :as tel]
            [yggdrasil.adapters.geschichte :as gy]
            [yggdrasil.protocols :as p]))

(def sandbox-repo-url
  "Published source for the sandbox standard library."
  "https://github.com/replikativ/dvergr-sandbox.git")

(defn default-sandbox-repo []
  (let [sibling (io/file ".." "dvergr-sandbox" ".git")]
    (if (.exists sibling) "../dvergr-sandbox" sandbox-repo-url)))

(defn sandbox-repo []
  (or (System/getenv "DVERGR_SANDBOX_REPO")
      (try (:sandbox-repo ((requiring-resolve 'dvergr.substrate.config/config)))
           (catch Throwable _ nil))
      (default-sandbox-repo)))

(defn repository-config
  "Portable Datahike configuration for one persistent Geschichte repository."
  [scope]
  (let [scope-path (.getCanonicalPath (io/file scope))
        ;; Keep the store below the repository scope. Besides leaving room for
        ;; future repository metadata, this lets callers hand us an existing
        ;; empty scope directory (the old native-worktree API commonly did).
        path (.getCanonicalPath (io/file scope "datahike"))]
    {:store {:backend :file
             :path path
             :id (java.util.UUID/nameUUIDFromBytes
                  (.getBytes (str "dvergr-geschichte:" scope-path) "UTF-8"))}
     ;; 128, not 256 — measured knee on a room-shaped store; see
     ;; `dvergr.substrate.datahike/diff-buf-size` for the table.
     :index-config {:diff-buf-size sdh/diff-buf-size}
     :schema-flexibility :write
     ;; `:crypto-hash?` matches every other room store, so ONE mechanism
     ;; (`datahike.audit/verify-chain`) verifies books, wiki, chat AND code.
     ;; Geschichte needs it: its own hashing covers CONTENT only —
     ;; `:geschichte.content/id` is a hash of the bytes, verified on read — while
     ;; `:geschichte.commit/id` is a random uuid and refs are ordinary datoms. So
     ;; repointing a path at other content, or rewriting commit parentage, is
     ;; invisible to geschichte and visible only to datahike's merkle.
     ;;
     ;; It costs `:fuse-index-roots?`, which datahike disables under crypto-hash
     ;; (measured: 296 -> 840 objects over 60 commits). Accepted because the
     ;; object count matters most for a full-store sync handshake, and the
     ;; roadmap is windowed partial loading rather than full handshakes.
     :crypto-hash? true
     :commit-graph? true
     ;; The ONE flag that differs from the datom stores, and the reason is that
     ;; Geschichte already implements history at a higher layer: its commit graph
     ;; IS the version history, so datahike's temporal index is redundant here.
     ;; Keeping it would be actively harmful — under `:keep-history? true` a
     ;; retracted store-ref survives in the temporal AEVT and keeps its blob
     ;; whitelisted forever, so deleted files and media could NEVER be reclaimed
     ;; (measured: 10 MB retracted -> 10 MB retained; with history off the same
     ;; test freed 10.57 MB -> 0.017 MB). Repos hold media; unbounded growth is
     ;; not a trade worth making for a redundant index.
     :keep-history? false}))

(defn- fallback-workspace! [conn source error]
  (tel/log! {:level :warn :id :workspace/seed-clone-failed
             :data {:source source :error (ex-message error)}}
            "Geschichte sandbox seed import failed — creating an empty workspace")
  (repo/write! conn "user.clj" (.getBytes "(ns user)\n" "UTF-8"))
  (repo/stage-all! conn)
  (repo/commit! conn {:message "workspace: empty (stdlib source unreachable)"
                      :author "dvergr <agent@dvergr.local>"}))

(defn- import-seed! [conn source]
  (if-let [local-source (git-local/source-file (io/file ".") source)]
    (git-local/import! conn local-source
                       {:remote "upstream" :clone? true})
    (git-http/clone! conn "upstream" source)))

(defn ensure-repository!
  "Create and seed a persistent Geschichte repository at `scope`. Existing
  repositories are left untouched. Returns the Datahike config."
  ([scope] (ensure-repository! scope {}))
  ([scope {:keys [source] :or {source (sandbox-repo)}}]
   (let [cfg (repository-config scope)]
     (when-not (d/database-exists? cfg)
       (when-let [parent (.getParentFile (io/file scope))]
         (.mkdirs parent))
       (d/create-database cfg)
       (let [conn (d/connect cfg)]
         (try
           (repo/init! conn {:name "dvergr workspace"})
           (try
             (import-seed! conn source)
             (catch Throwable error
               (fallback-workspace! conn source error)))
           (finally (d/release conn)))))
     cfg)))

(defn create-system
  "Open a persistent repository as a Geschichte Yggdrasil system."
  [& {:keys [scope system-name source]}]
  (let [scope (or scope (paths/workspace-store))
        cfg (ensure-repository! scope (cond-> {} source (assoc :source source)))]
    (gy/create (d/connect cfg) {:system-name system-name})))

(defn delete-repository! [scope]
  (let [cfg (repository-config scope)]
    (when (d/database-exists? cfg)
      (d/delete-database cfg))))

(defn current-system
  "The room-owned Geschichte system in the bound Spindel context."
  []
  (let [systems (->> (ygg/registered-systems)
                     vals
                     (filter #(= :geschichte (p/system-type %))))]
    (or (first (filter #(some-> (p/system-id %) str
                                (str/starts-with? "room-repo-"))
                       systems))
        (first systems))))

;; Small named accessors keep dvergr.system.rooms independent of Geschichte's
;; record fields while it resolves a specific attached repository by system id.
(def gy-connection gy/connection)
(def gy-workspace-id gy/workspace-id)

(defn current-workspace
  "Virtual workspace descriptor for the bound context, or nil."
  []
  (when-let [system (current-system)]
    (let [conn (gy/connection system)]
      {:system system
       :conn conn
       :id (gy/workspace-id system)
       :repository {:conn conn
                    :config (:config @conn)
                    :workspace-branch (get-in @conn [:config :branch])}})))

(defn filesystem
  "Create a Muschel MountFS rooted in a workspace descriptor. The returned
  filesystem is fully virtual and supports nested Geschichte worktree mounts."
  ([] (some-> (current-workspace) filesystem))
  ([{:keys [repository] :as workspace}]
   (when workspace
     (mgeschichte/make-root repository))))

(defn execute-git
  "Execute Git-compatible argv against a virtual workspace."
  ([argv] (execute-git (current-workspace) argv))
  ([workspace argv]
   (if-let [{:keys [conn]} workspace]
     (command/execute {:conn conn :root "/" :config (atom {})
                       :repo-relative #(let [path (str %)]
                                         (if (contains? #{"." "/"} path)
                                           ""
                                           (str/replace path #"^/+" "")))}
                      argv)
     {:stdout "" :stderr "fatal: not a Geschichte repository\n" :exit 128})))

(def review-excluded-prefixes
  "Worktree prefixes kept OUT of fork/merge review.

   These paths are versioned like everything else — this is not `.gitignore`,
   which means \"never tracked\". They are excluded from the REVIEW because a
   merge review is a judgement about code, and user media is neither reviewable
   as a diff nor mergeable as text: a room member uploading a voice note should
   not be able to put a binary conflict in front of whoever approves the fork.

   Consumer-side convention for now. The honest home for this is geschichte
   itself (repo-config-backed exclusion honoured by `repo/changes`, `status` and
   `git diff`), so that the CLI and the review agree — until then `git status`
   inside the sandbox will still show these paths."
  ["media/"])

(defn- reviewable?
  "Is this change part of the code review, or user data riding along?"
  [{:keys [path]}]
  (let [p (str/replace (str path) #"^/+" "")]
    (not-any? #(str/starts-with? p %) review-excluded-prefixes)))

(defn diff-since-fork
  "Review payload for a forked virtual workspace. Includes committed and dirty
  paths; unlike the old implementation it has no host worktree path.

  Excludes `review-excluded-prefixes` — media travels with the fork, it just
  does not show up as something to review."
  [fork-ctx]
  (let [in-ctx (fn [ctx]
                 (binding [ec/*execution-context* ctx] (current-system)))
        fork-system (in-ctx fork-ctx)
        parent-system (some-> fork-ctx :parent-ctx in-ctx)]
    (when fork-system
      (let [conn (gy/connection fork-system)
            parent-conn (some-> parent-system gy/connection)
            base (some-> parent-conn repo/head-commit :geschichte.commit/id)
            head (some-> conn repo/head-commit :geschichte.commit/id)
            changes (filterv reviewable?
                             (if base
                               (repo/changes conn base :worktree)
                               (repo/changes conn :empty :worktree)))
            files (mapv :path changes)
            commits (when (and base head (not= base head))
                      (->> (repo/log conn {:limit 100})
                           (take-while #(not= base (:geschichte.commit/id %)))
                           (mapv (fn [commit]
                                   {:sha (str (:geschichte.commit/id commit))
                                    :subject (:geschichte.commit/message commit)}))))
            ;; Review must include dirty worktree paths as well as commits. A
            ;; CLI `diff --stat` against HEAD drops that distinction after a
            ;; commit, so summarize the already-computed base→worktree changes.
            stat (when (seq changes)
                   (str (str/join "\n" (map #(str " " (:path %) " | changed") changes))
                        "\n " (count changes) " file"
                        (when (not= 1 (count changes)) "s") " changed\n"))]
        {:branch (name (p/current-branch fork-system))
         :parent-branch (some-> parent-system p/current-branch name)
         :workspace-id (gy/workspace-id fork-system)
         :worktree-path nil
         :commits (or commits [])
         :stat stat
         :files files
         :empty? (empty? files)}))))
