(ns dvergr.substrate.git
  "Git worktree integration for session isolation.

   Each session gets its own git worktree with an isolated working directory.
   Sessions can be nested via branch-from-branch pattern."
  (:require [yggdrasil.adapters.git :as git-adapter]
            [yggdrasil.protocols :as p]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [dvergr.substrate.paths :as paths]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.telemere :as tel])
  (:import))

;; ---------------------------------------------------------------------------
;; Git System Management
;; ---------------------------------------------------------------------------

(declare ensure-workspace-repo!)

(defn create-git-system
  "Create a GitSystem for a repository.

   Options:
   - :repo-path - Path to git repository (default: the `.dvergr/workspace` agent
                  code repo, auto-initialised — NOT dvergr's own source tree).
                  Pass a user project path to point a room at that instead.
   - :worktrees-dir - Where to create worktrees (default: .dvergr/worktrees)
   - :system-name - Yggdrasil system id (default: derived from repo-path). Pass a
                    unique name so multiple per-room repos coexist in one composite."
  [& {:keys [repo-path worktrees-dir system-name]
      :or {repo-path (paths/workspace-dir)}}]
  ;; The default workspace repo is auto-initialised; explicit project paths used as-is.
  (when (= repo-path (paths/workspace-dir)) (ensure-workspace-repo!))
  (let [repo-file (io/file repo-path)
        _ (when-not (.exists repo-file)
            (throw (ex-info "Repository path does not exist"
                            {:repo-path repo-path})))

        ;; Check if it's a git repo
        git-dir (io/file repo-path ".git")
        _ (when-not (.exists git-dir)
            (throw (ex-info "Not a git repository (no .git directory)"
                            {:repo-path repo-path})))

        ;; Resolve to absolute path
        abs-repo-path (.getCanonicalPath repo-file)
        ;; PER-REPO worktrees dir. A composite can hold MORE than one git system
        ;; (e.g. the default `.dvergr/workspace` repo + a room's own per-room
        ;; repo). On a fork, every system branches to the SAME name
        ;; (`main-fork-<id>`), so a SHARED worktrees-dir makes them collide on
        ;; `<worktrees-dir>/<branch>` — the 2nd `git worktree add` fails
        ;; "already exists". Keying the dir by system-name / repo basename gives
        ;; each repo its own worktree namespace.
        wt-key (-> (or system-name (.getName repo-file))
                   (str/replace #"[^A-Za-z0-9._-]" "_"))
        abs-worktrees-dir (cond
                            (nil? worktrees-dir)            (str (paths/worktrees-dir) "/" wt-key)
                            (str/starts-with? worktrees-dir "/") worktrees-dir
                            :else                           (str abs-repo-path "/" worktrees-dir))]

    ;; Create worktrees directory if it doesn't exist
    (.mkdirs (io/file abs-worktrees-dir))

    (git-adapter/create abs-repo-path
                        (cond-> {:worktrees-dir abs-worktrees-dir}
                          system-name (assoc :system-name system-name)))))

(def sandbox-repo-url
  "Published git source for the sandbox stdlib workspace."
  "https://github.com/replikativ/dvergr-sandbox")

(defn default-sandbox-repo
  "Default git source for the sandbox stdlib workspace. Prefers the `../dvergr-sandbox`
   sibling when it's a checkout (local development — local edits flow straight into new
   room repos), otherwise the published github URL. Override via config `:sandbox-repo`
   or env `DVERGR_SANDBOX_REPO`."
  []
  (let [sibling (io/file ".." "dvergr-sandbox" ".git")]
    (if (.exists sibling) "../dvergr-sandbox" sandbox-repo-url)))

(defn- sandbox-repo []
  (or (System/getenv "DVERGR_SANDBOX_REPO")
      (try (:sandbox-repo ((requiring-resolve 'dvergr.substrate.config/config)))
           (catch Throwable _ nil))
      (default-sandbox-repo)))

(defn ensure-repo!
  "Ensure `root` is a git repo seeded with the dvergr sandbox stdlib by CLONING it
   from `(sandbox-repo)` — a local path in dev, the github URL in production (git
   clones both). The clone's remote is renamed `origin`→`upstream` so the room owns
   its own history and can `git pull upstream` for stdlib updates. Idempotent — a
   no-op once `.git` exists. If the source is unreachable, falls back to an EMPTY
   repo (NOT a second copy of the stdlib) and warns. Returns `root`. Used for the
   default workspace and each room's own code repo."
  [root]
  (.mkdirs (io/file root))
  (when-not (.exists (io/file root ".git"))
    (let [src (sandbox-repo)
          res (clojure.java.shell/sh "git" "clone" "--quiet" (str src) (str root))]
      (if (zero? (:exit res))
        (clojure.java.shell/sh "git" "-C" (str root) "remote" "rename" "origin" "upstream")
        (let [sh (fn [& args] (apply clojure.java.shell/sh "git" "-C" (str root) args))]
          (tel/log! {:level :warn :id :workspace/seed-clone-failed
                     :data {:source src :err (:err res)}}
                    "Sandbox stdlib clone failed — creating an empty workspace repo")
          (sh "init" "-q" "-b" "main")
          (spit (io/file root "user.clj") "(ns user)\n")
          (sh "add" "-A")
          (sh "-c" "user.email=agent@dvergr" "-c" "user.name=dvergr"
              "commit" "-q" "-m" "workspace: empty (stdlib source unreachable)")))))
  root)

(defn ensure-workspace-repo!
  "Ensure the default agent code workspace (`.dvergr/workspace`) is initialised.
   Distinct from any user project the agent might edit; keeping it under
   `.dvergr/` means the sandbox never forks dvergr's own source tree."
  []
  (ensure-repo! (paths/workspace-dir)))

(defn safe-workspace-root
  "A sandbox-SAFE workspace path — the ensured `.dvergr/workspace` (a clone of the
   sandbox stdlib, isolated under `.dvergr/`). This is the ONLY permitted fallback
   when no room/worktree git system is in scope. It must NEVER be the host JVM cwd
   (the dvergr source tree): an agent's shell/fs/git/require must not be able to
   read or mutate dvergr's own checkout. Idempotent (clone is a no-op once present)."
  []
  (let [w (paths/workspace-dir)]
    (ensure-repo! w)
    (str w)))

;; ---------------------------------------------------------------------------
;; Session Worktree Management
;; ---------------------------------------------------------------------------

(defn session-branch-name
  "Generate branch name for a session."
  [session-id]
  (keyword (str "session-" session-id)))

(defn create-session-worktree
  "Create a git worktree for a session.

   Options:
   - :git-system - GitSystem instance
   - :session-id - Unique session identifier
   - :from-branch - Branch to fork from (default: :main)

   Returns map with :branch, :worktree-path"
  [{:keys [git-system session-id from-branch]
    :or {from-branch :main}}]
  (let [branch (session-branch-name session-id)
        repo-path (:repo-path git-system)

        ;; Get commit SHA of from-branch
        from-branch-name (if (keyword? from-branch)
                           (name from-branch)
                           from-branch)
        from-sha (try
                   (let [result (clojure.java.shell/sh "git" "-C" repo-path "rev-parse" from-branch-name)]
                     (when (zero? (:exit result))
                       (str/trim (:out result))))
                   (catch Exception _ nil))

        ;; Create branch and worktree
        _ (if from-sha
            (p/branch! git-system branch from-sha)
            (p/branch! git-system branch))

        ;; Get worktree path
        worktrees-dir (:worktrees-dir git-system)
        wt-path (str worktrees-dir "/session-" session-id)]

    {:branch branch
     :worktree-path wt-path
     :parent-branch from-branch}))

(defn cleanup-session-worktree
  "Remove a session's worktree and branch.

   WARNING: This deletes uncommitted work. Use merge-session-to-parent first
   if you want to preserve changes."
  [{:keys [git-system session-id]}]
  (let [branch (session-branch-name session-id)]
    (try
      (p/delete-branch! git-system branch)
      {:success true}
      (catch Exception e
        {:success false
         :error (.getMessage e)}))))

;; ---------------------------------------------------------------------------
;; Merging and Finalization
;; ---------------------------------------------------------------------------

(defn merge-session-to-parent
  "Merge a session's branch back to its parent branch.

   Options:
   - :git-system - GitSystem instance
   - :session-id - Session to merge
   - :parent-branch - Target branch (or :main if not specified)
   - :message - Commit message (auto-generated if not provided)

   Returns {:success true/false :commit-sha ...} or {:success false :error ...}"
  [{:keys [git-system session-id parent-branch message]}]
  (let [branch (session-branch-name session-id)
        target (or parent-branch :main)
        msg (or message (str "Merge session-" session-id))]
    (try
      ;; Check for conflicts before attempting merge
      (let [conflicts (p/conflicts git-system target branch)]
        (if (seq conflicts)
          {:success false
           :error "Merge conflicts detected"
           :conflicts conflicts}

          ;; No conflicts, proceed with merge
          (do
            (p/checkout git-system target)
            (p/merge! git-system branch {:message msg})
            (let [commit-sha (p/snapshot-id git-system)]
              {:success true
               :commit-sha commit-sha
               :message msg}))))
      (catch Exception e
        {:success false
         :error (.getMessage e)
         :exception (class e)}))))

(defn show-session-diff
  "Show diff between session branch and its parent.

   Returns string with diff output."
  [{:keys [git-system session-id parent-branch]}]
  (let [branch (session-branch-name session-id)
        target (or parent-branch :main)]
    (try
      (p/diff git-system target branch)
      (catch Exception e
        (str "Error getting diff: " (.getMessage e))))))

;; ---------------------------------------------------------------------------
;; Nested Session Support
;; ---------------------------------------------------------------------------

(defn create-nested-session-worktree
  "Create a nested session as a child of parent session.

   This creates a new branch from the parent session's branch,
   enabling hierarchical agent workflows.

   Options:
   - :git-system - GitSystem instance
   - :session-id - Unique session identifier for new session
   - :parent-session-id - Parent session ID

   Returns map with :branch, :worktree-path, :parent-branch"
  [{:keys [git-system session-id parent-session-id]}]
  (let [parent-branch (session-branch-name parent-session-id)]
    (create-session-worktree
     {:git-system git-system
      :session-id session-id
      :from-branch parent-branch})))

(defn merge-nested-session
  "Merge a nested session back to its parent session.

   This is for hierarchical agent workflows where inner agents
   merge their work back to outer agent's branch."
  [{:keys [git-system session-id parent-session-id]}]
  (let [parent-branch (session-branch-name parent-session-id)]
    (merge-session-to-parent
     {:git-system git-system
      :session-id session-id
      :parent-branch parent-branch
      :message (str "Merge nested session-" session-id
                    " into session-" parent-session-id)})))

;; ---------------------------------------------------------------------------
;; Worktree Path Access
;; ---------------------------------------------------------------------------

(defn worktree-path
  "Get the filesystem path for a GitSystem's current branch.

   Delegates to the Addressable protocol's working-path method.

   Args:
     git-sys - GitSystem instance (from yggdrasil or create-git-system)

   Returns: Absolute path string to the working directory

   Example:
     (def ygit (ygg/register! (create-git-system)))
     (spit (str (worktree-path @ygit) \"/new-file.clj\") \"...\")"
  [git-sys]
  (p/working-path git-sys))

(defn current-git-system
  "Find the registered git system in the **current** execution context
   (must be bound via `binding [ec/*execution-context* …]`).

   When the context has been forked, the git system stored under the
   same external-ref id will be the forked one — pointing at the
   child's worktree — so its `working-path` returns the per-fork
   workspace.

   When a ctx holds more than one git system (e.g. a room composite that inherited
   a workspace git from its parent, or a multi-repo room), prefer the room's OWN
   repo — its system-id is `room-repo-<dir>` (see system/rooms/repo-system-name).
   Resolving by name is fork-correct because `checkout` preserves system-name.
   Falls back to the sole/first git system (e.g. the default `.dvergr/workspace`).

   Returns the git system, or nil if no git system is registered."
  []
  (let [gits (->> (ygg/registered-systems)
                  vals
                  (filter #(= :git (p/system-type %))))]
    (or (first (filter #(some-> (p/system-id %) str (str/starts-with? "room-repo-")) gits))
        (first gits))))

(defn current-worktree-path
  "Working-tree filesystem path of the registered git system in the
   current execution context. nil if no git system is registered."
  []
  (some-> (current-git-system) p/working-path))

(defn- git-in
  "Run `git <args>` inside `dir`, return {:exit :out}. Best-effort —
   never throws on git errors, the caller decides what to do."
  [dir & args]
  (let [pb (-> (ProcessBuilder. ^java.util.List
                (into ["git"] args))
               (.directory (io/file dir))
               (.redirectErrorStream true))
        proc (.start pb)
        out (slurp (.getInputStream proc))
        exit (.waitFor proc)]
    {:exit exit :out out}))

(defn diff-since-fork
  "Compute the diff that a fork-context's branch has accumulated over
   its parent branch. Used by `dvergr.discourse/propose-merge!` to
   build a payload the human (or another agent) can scan before
   approving a merge.

   `fork-ctx` is the spindel execution context returned by
   `ctx/fork-context` (i.e. the `:child-ctx` of a yggdrasil
   ForkHandle, or `(:ctx fork-room)` after `fork-room :isolation
   :ctx`).

   Returns a map:
     {:branch         \"main-fork-…\"           ; fork's branch
      :parent-branch  \"main\"                  ; parent's branch
      :worktree-path  \"/tmp/.../worktrees/…\"  ; the fork's path
      :commits        [{:sha … :subject …} …]   ; fork-only commits
      :stat           \"…lines + / - per file…\" ; diff --stat output
      :files          [\"side.txt\" \"x.clj\" …] ; changed file paths
      :empty?         false}                    ; true when no diff"
  [fork-ctx]
  (let [in-ctx (fn [c] (binding [ec/*execution-context* c]
                         (current-git-system)))
        fork-sys (in-ctx fork-ctx)]
    (when fork-sys
      (let [fork-branch   (name (p/current-branch fork-sys))
            worktree      (p/working-path fork-sys)
            parent-ctx    (:parent-ctx fork-ctx)
            parent-sys    (when parent-ctx (in-ctx parent-ctx))
            parent-wt     (some-> parent-sys p/working-path)
            ;; The fork worktree branches off the parent worktree's ACTUAL HEAD,
            ;; NOT the git adapter's nominal branch — which is a fixed "main" and
            ;; ignores the real checkout (e.g. our feature branch). Diffing against
            ;; "main" therefore shows the WHOLE feature branch's divergence (every
            ;; file we've changed), not the fork's own work. Resolve the real
            ;; merge-base from the parent worktree's HEAD instead.
            parent-head   (when parent-wt
                            (let [r (git-in parent-wt "rev-parse" "HEAD")]
                              (when (zero? (:exit r)) (str/trim (:out r)))))
            parent-branch (or (when parent-wt
                                (let [r (git-in parent-wt "rev-parse" "--abbrev-ref" "HEAD")]
                                  (when (and (zero? (:exit r)) (not (str/blank? (:out r))))
                                    (str/trim (:out r)))))
                              (some-> parent-sys p/current-branch name)
                              "main")
            base          (or (when parent-head
                                (let [r (git-in worktree "merge-base" parent-head fork-branch)]
                                  (when (zero? (:exit r)) (str/trim (:out r)))))
                              parent-branch)
            range         (str base ".." fork-branch)
            commits-r     (git-in worktree "log" "--format=%h%x09%s" range)
            commits       (when (zero? (:exit commits-r))
                            (for [line (str/split-lines (:out commits-r))
                                  :when (not (str/blank? line))
                                  :let [[sha subject] (str/split line #"\t" 2)]]
                              {:sha sha :subject subject}))
            stat-r        (git-in worktree "diff" "--stat" range)
            files-r       (git-in worktree "diff" "--name-only" range)
            files         (when (zero? (:exit files-r))
                            (->> (str/split-lines (:out files-r))
                                 (remove str/blank?)
                                 vec))]
        {:branch        fork-branch
         :parent-branch parent-branch
         :worktree-path worktree
         :commits       (vec commits)
         :stat          (when (zero? (:exit stat-r)) (:out stat-r))
         :files         files
         :empty?        (empty? files)}))))

;; ---------------------------------------------------------------------------
;; Utilities
;; ---------------------------------------------------------------------------

(defn get-session-status
  "Get git status for a session's worktree.

   Returns map with :branch, :has-changes?, :commit-sha, :status-text"
  [{:keys [git-system session-id]}]
  (let [branch (session-branch-name session-id)
        wt-path (str (:worktrees-dir git-system) "/session-" session-id)]

    (try
      (let [result (clojure.java.shell/sh "git" "-C" wt-path "status" "--porcelain")
            status-text (:out result)
            has-changes? (not (str/blank? status-text))

            ;; Get current commit
            commit-result (clojure.java.shell/sh "git" "-C" wt-path "rev-parse" "HEAD")
            commit-sha (str/trim (:out commit-result))]

        {:branch branch
         :worktree-path wt-path
         :has-changes? has-changes?
         :commit-sha commit-sha
         :status-text status-text})

      (catch Exception e
        {:error (.getMessage e)}))))

(defn list-session-commits
  "List commits in a session's branch.

   Options:
   - :git-system - GitSystem instance
   - :session-id - Session ID
   - :limit - Max number of commits (default: 10)

   Returns vector of commit info maps"
  [{:keys [git-system session-id limit]
    :or {limit 10}}]
  (let [branch (session-branch-name session-id)]
    (try
      (p/checkout git-system branch)
      (let [commits (p/history git-system {:limit limit})]
        (mapv #(p/snapshot-meta git-system %) commits))
      (catch Exception e
        {:error (.getMessage e)}))))

(comment
  ;; Example usage

  ;; 1. Create git system for current repo
  (def git-sys (create-git-system))

  ;; 2. Create session worktree
  (def session (create-session-worktree
                {:git-system git-sys
                 :session-id "123"
                 :from-branch :main}))
  ;; => {:branch :session-123
  ;;     :worktree-path "/path/to/repo/.worktrees/session-123"
  ;;     :parent-branch :main}

  ;; 3. Agent works in worktree...
  ;; (shell commands execute in :worktree-path)

  ;; 4. Check status
  (get-session-status {:git-system git-sys :session-id "123"})

  ;; 5. Show diff before merging
  (show-session-diff {:git-system git-sys
                      :session-id "123"
                      :parent-branch :main})

  ;; 6. Merge back to main
  (merge-session-to-parent {:git-system git-sys
                            :session-id "123"
                            :parent-branch :main})

  ;; 7. Cleanup
  (cleanup-session-worktree {:git-system git-sys :session-id "123"})

;; Nested sessions example:

  ;; Outer agent
  (def outer (create-session-worktree
              {:git-system git-sys
               :session-id "outer-123"}))

  ;; Inner agent (nested)
  (def inner (create-nested-session-worktree
              {:git-system git-sys
               :session-id "inner-456"
               :parent-session-id "outer-123"}))

  ;; Inner finishes, merges to outer
  (merge-nested-session {:git-system git-sys
                         :session-id "inner-456"
                         :parent-session-id "outer-123"})

  ;; Outer finishes, merges to main
  (merge-session-to-parent {:git-system git-sys
                            :session-id "outer-123"
                            :parent-branch :main}))
