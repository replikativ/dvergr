(ns dvergr.git
  "Git worktree integration for session isolation.

   Each session gets its own git worktree with an isolated working directory.
   Sessions can be nested via branch-from-branch pattern."
  (:require [yggdrasil.adapters.git :as git-adapter]
            [yggdrasil.protocols :as p]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Git System Management
;; ---------------------------------------------------------------------------

(defn create-git-system
  "Create a GitSystem for a repository.

   Options:
   - :repo-path - Path to git repository (default: current directory)
   - :worktrees-dir - Where to create worktrees (default: .worktrees)"
  [& {:keys [repo-path worktrees-dir]
      :or {repo-path (System/getProperty "user.dir")
           worktrees-dir ".worktrees"}}]
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
        abs-worktrees-dir (if (str/starts-with? worktrees-dir "/")
                           worktrees-dir
                           (str abs-repo-path "/" worktrees-dir))]

    ;; Create worktrees directory if it doesn't exist
    (.mkdirs (io/file abs-worktrees-dir))

    (git-adapter/create abs-repo-path
                        {:worktrees-dir abs-worktrees-dir})))

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

   Returns the git system, or nil if no git system is registered."
  []
  (some->> (ygg/registered-systems)
           vals
           (filter #(= :git (p/system-type %)))
           first))

(defn current-worktree-path
  "Working-tree filesystem path of the registered git system in the
   current execution context. nil if no git system is registered."
  []
  (some-> (current-git-system) p/working-path))

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
