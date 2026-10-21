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
            [dvergr.substrate.repository-profile :as profile]
            [dvergr.substrate.store-ids :as store-ids]
            [geschichte.git.command :as command]
            [geschichte.git.http :as git-http]
            [geschichte.git.local :as git-local]
            [geschichte.repo :as repo]
            [geschichte.query :as query]
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

(defn legacy-store-id
  "The id versions before store id files derived for the repository store at
   `path`: from its scope, the directory holding it."
  [path]
  (store-ids/path-derived "dvergr-geschichte:" (.getParent (io/file path))))

(def repository-options
  "A Geschichte repository's Datahike options, without the store: shared by a
   file repository (`repository-config`) and an in-memory one."
  {;; 128, not 256 — measured knee on a room-shaped store; see
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
   :keep-history? false})

(defn repository-config
  "Portable Datahike configuration for one persistent Geschichte repository."
  [scope]
  (let [;; Keep the store below the repository scope. Besides leaving room for
        ;; future repository metadata, this lets callers hand us an existing
        ;; empty scope directory (the old native-worktree API commonly did).
        path (.getCanonicalPath (io/file scope "datahike"))]
    (assoc repository-options
           :store {:backend :file
                   :path path
                   ;; the id recorded beside the store, not one derived from
                   ;; the path: a moved home keeps its repositories
                   :id (store-ids/store-id path legacy-store-id)})))

(defn- fallback-workspace! [conn source error]
  (tel/log! {:level :warn :id :workspace/seed-clone-failed
             :data {:source source :error (ex-message error)}}
            "Geschichte sandbox seed import failed — creating an empty workspace")
  (repo/write! conn "user.clj" (.getBytes "(ns user)\n" "UTF-8"))
  (repo/stage-all! conn)
  (repo/commit! conn {:message "workspace: empty (stdlib source unreachable)"
                      :author "dvergr <agent@dvergr.local>"}))

(defn- import-seed! [conn source remote branch]
  (if-let [local-source (git-local/source-file (io/file ".") source)]
    (git-local/import! conn local-source
                       (cond-> {:remote remote :clone? true} branch (assoc :branch branch)))
    (git-http/clone! conn remote source)))

(def ^:private recovery-token (Object.))

(defn- retained-initialization-error [cleanup error & [initialization-error]]
  ;; Recovery is an opaque JVM field, NOT closure-valued ex-data. Normal
  ;; Throwable/ex-data wire/log projections contain only the safe descriptor.
  (let [retained (proxy [clojure.lang.ExceptionInfo clojure.lang.IDeref]
                        ["Repository initialization resources need recovery"
                         {:type ::initialization-recovery-required :phase ((:phase cleanup))} (or initialization-error error)]
                   (deref [] cleanup))]
    (when (and initialization-error (not (identical? initialization-error error)))
      (.addSuppressed retained error))
    retained))

(defn recover-initialization!
  "HOST ONLY: retry a failed unpublished initializer's retained cleanup. The
   original error holds the exact live resources privately; it is not a wire
   DTO. Abort is retryable and must be idempotent. Release/delete uncertainty
   requires explicit reconciliation, never a blind repeat or another writer."
  [error]
  (let [{:keys [token retry!] :as cleanup} (when (instance? clojure.lang.IDeref error) @error)]
    (when-not (and (identical? recovery-token token) (fn? retry!))
      (throw (ex-info "No retained initialization recovery" {:type ::invalid-recovery})))
    (try (retry!)
         (catch Throwable next-error (throw (retained-initialization-error cleanup next-error))))))

(defn- initialization-cleanup [cfg conn context hooks]
  ;; Native-resource ownership, scoped to ONE exclusive initialization call;
  ;; not an app-state cache or fork-shared registry. The exception retains this
  ;; closure if abort cannot positively establish that its work has joined.
  (let [phase (atom :owned)
        retry! (fn []
                 (locking phase
                   (when (= :owned @phase)
                     (reset! phase :aborting)
                     (try ((:abort-create! hooks) conn context)
                          (catch Throwable error (reset! phase :owned) (throw error)))
                     (reset! phase :joined))
                   (when (= :joined @phase)
                     (reset! phase :releasing)
                     (d/release conn)
                     (reset! phase :released))
                   (when (= :released @phase)
                     (reset! phase :deleting)
                     (sdh/delete-database! cfg)
                     (when (d/database-exists? cfg)
                       (throw (ex-info "Initialization database deletion was incomplete"
                                       {:type ::cleanup-delete-incomplete})))
                     (reset! phase :cleaned))
                   (when-not (= :cleaned @phase)
                     (throw (ex-info "Initialization cleanup needs reconciliation before retry"
                                     {:type ::cleanup-uncertain :phase @phase})))
                   true))]
    {:token recovery-token :retry! retry! :phase #(deref phase)
     :release! (fn []
                 (locking phase
                   (reset! phase :releasing)
                   (d/release conn)
                   (reset! phase :initialized-released)))}))

(defn- initialize-profile! [cfg scope profile hooks]
  (let [conn (sdh/connect cfg)
        context {:profile profile :scope scope :config cfg}
        cleanup (initialization-cleanup cfg conn context hooks)
        result (try ((:initialize! hooks) conn context) nil
                    (catch Throwable error error))]
    (try
      (if result ((:retry! cleanup)) ((:release! cleanup)))
      (catch Throwable cleanup-error
        (throw (retained-initialization-error cleanup cleanup-error result))))
    (when result (throw result))))

(defn ensure-repository!
  "Create and seed a persistent Geschichte repository at `scope`. Existing
  repositories are left untouched. Returns the Datahike config.

  `:source` (default the sandbox stdlib) is a local checkout or a Git URL, with
  `:branch` for a local one. When the stdlib seed cannot be imported the
  repository starts empty; with `:fallback? false` (a user's repository) the
  failure is an error and nothing is created.

  Trusted hosts may instead request :repository-profile from their explicit
  :repository-profiles map. Its initializer receives a pristine connection;
  no ordinary init/import is appended. All hooks are synchronous and must join
  their own work before returning. Abort must be idempotent; uncertain abort
  retains its connection through recover-initialization!. Lifecycle hooks must
  not call recovery reentrantly. prepare-open! is restricted to synchronous
  validation/governance registration; it must not start asynchronous work.
  Creation requires
  an exclusively owned fresh scope (e.g. a new room's random UUID), not an
  exists?/create pair used as a cross-process ownership lock. Existing stores
  are never reinitialized."
  ([scope] (ensure-repository! scope {}))
  ([scope {:keys [source branch fallback? remote repository-profile repository-profiles] :or {fallback? true}}]
   (let [cfg (repository-config scope)
         hooks (profile/resolve! repository-profiles repository-profile)]
     (when (and hooks (or source branch remote))
       (throw (ex-info "A profiled repository cannot also request a sandbox clone"
                       {:type ::conflicting-initialization})))
     (when-not (d/database-exists? cfg)
       (when-let [parent (.getParentFile (io/file scope))]
         (.mkdirs parent))
       (d/create-database cfg)
       (if hooks
         (initialize-profile! cfg scope repository-profile hooks)
         (let [conn (sdh/connect cfg)]
           (try
             (repo/init! conn {:name "dvergr workspace"})
             (let [source (or source (sandbox-repo))]
               (try
                 (import-seed! conn source (or remote (if fallback? "upstream" "origin")) branch)
                 (catch Throwable error
                   (if fallback?
                     (fallback-workspace! conn source error)
                     (do (d/release conn)
                         (sdh/delete-database! cfg)
                         (throw (ex-info (str "Could not import " source ": " (ex-message error))
                                         {:type ::import-failed :source source}
                                         error)))))))
             (finally (try (d/release conn) (catch Throwable _ nil)))))))
     cfg)))

(defn create-system
  "Open a persistent repository as a Geschichte Yggdrasil system. A trusted
   profile's prepare-open! must succeed before the adapter can be published."
  [& {:keys [scope system-name source repository-profile repository-profiles]}]
  (let [scope (or scope (paths/workspace-store))
        hooks (profile/resolve! repository-profiles repository-profile)
        cfg (ensure-repository! scope (cond-> {:repository-profile repository-profile
                                               :repository-profiles repository-profiles}
                                        source (assoc :source source)))
        conn (sdh/connect cfg)]
    (try
      (when hooks ((:prepare-open! hooks) conn {:profile repository-profile :scope scope :config cfg}))
      (gy/create conn {:system-name system-name})
      (catch Throwable error
        ;; This opening attempt does not own persisted data or store-global
        ;; governance. Never delete/ungovern a pre-existing/shared repository.
        (try
          (d/release conn)
          (catch Throwable release-error
            (throw (retained-initialization-error
                    {:token recovery-token :phase (constantly :releasing)
                     :retry! (fn []
                               ;; Retain the exact handle for host diagnosis;
                               ;; an uncertain release cannot be blindly retried.
                               (throw (ex-info "Repository opening release needs reconciliation"
                                               {:type ::cleanup-uncertain :phase :releasing
                                                :store (get-in @conn [:config :store :id])})))}
                    release-error error))))
        (throw error)))))

(defn delete-repository! [scope]
  (let [cfg (repository-config scope)]
    (when (d/database-exists? cfg)
      (sdh/delete-database! cfg))))

(def ^:private selection-path [:dvergr :workspace :selected-system])

(defn- selection-state []
  (let [state (ec/get-state (pop selection-path))]
    (when-not (or (nil? state) (map? state))
      (throw (ex-info "Workspace selection state is malformed"
                      {:type ::invalid-workspace-selection})))
    state))

(defn workspace-selected?
  "Whether this context has an explicit selection marker. Presence matters:
   a corrupt nil/invalid selected id must never enable legacy fallback."
  []
  (contains? (selection-state) (peek selection-path)))

(defn selected-system-id
  "The explicit logical workspace selection in the bound execution context,
   or nil when no host selection was made. Portable data only: forks inherit
   the identity, not a parent connection or filesystem handle."
  []
  (get (selection-state) (peek selection-path)))

(defn- selected-system! [id]
  (when-not (and (string? id) (<= 1 (count id) 4096) (not (str/blank? id)))
    (throw (ex-info "Workspace selection requires an exact logical system id"
                    {:type ::invalid-workspace-selection})))
  (let [matches (filterv #(= id (str (p/system-id %)))
                         (vals (ygg/registered-systems)))]
    (when-not (and (= 1 (count matches))
                   (= :geschichte (p/system-type (first matches))))
      (throw (ex-info "Selected Geschichte workspace is missing or ambiguous"
                      {:type ::selected-workspace-unavailable :system/id id})))
    (first matches)))

(defn select-workspace!
  "HOST API: select one registered Geschichte system by its exact string id
   in the bound Spindel context. This is resolution, NOT authorization. It
   neither changes registry ownership nor returns a connection/capability.
   The selection is fork-local computation state and follows async work that
   binds that context. Missing selections never fall back to another repo.
   Validation is a snapshot, not a lease against concurrent unregistration."
  [id]
  (selected-system! id)
  (ec/swap-state! selection-path (constantly id)))

(defn current-system
  "The selected Geschichte system in the bound Spindel context. An explicit
   host selection must resolve exactly, including after a fork; unavailable
   selections throw. Only unselected legacy callers use the room-prefix/first
   registered fallback."
  []
  (let [selection (selection-state)]
    (if (contains? selection (peek selection-path))
      (selected-system! (get selection (peek selection-path)))
      (let [systems (->> (ygg/registered-systems)
                         vals
                         (filter #(= :geschichte (p/system-type %))))]
        (or (first (filter #(some-> (p/system-id %) str
                                    (str/starts-with? "room-repo-"))
                           systems))
            (first systems))))))

;; Small named accessors keep dvergr.system.rooms independent of Geschichte's
;; record fields while it resolves a specific attached repository by system id.
(def gy-connection gy/connection)
(def gy-workspace-id gy/workspace-id)

(defn- workspace-descriptor
  ([system] (workspace-descriptor system (gy/connection system)))
  ([system conn]
   (when-not (instance? clojure.lang.IDeref conn)
     (throw (ex-info "Geschichte workspace has no active connection"
                     {:type ::workspace-unavailable})))
   (let [db @conn
         config (:config db)
         branch (get config :branch :db)
         physical {:repository (:geschichte.repo/id (query/repository db))
                   :store (get-in config [:store :id])
                   :backend (get-in config [:store :backend])
                   :branch branch :ref (query/current-ref db)}]
     (when-not (every? some? (vals physical))
       (throw (ex-info "Geschichte workspace is not initialized"
                       {:type ::workspace-unavailable})))
     {:system system
      :conn conn
      :id [(:store physical) branch]
      ::physical-target physical
      :repository {:conn conn :config config :workspace-branch branch}})))

(defn workspace-for-system!
  "HOST API: resolve this exact registered Geschichte SystemIdentity object in
   the bound context, independently of ambient selection. Its logical id and
   connection must be unique; aliases cannot select another system's policy.
   A parent/facade/replacement object is not the registered capability.
   The descriptor freezes repository/store/branch/ref identity, not HEAD.
   Resolution is a snapshot, NOT authorization or a registration lifetime lease."
  [system]
  (when-not (and (satisfies? p/SystemIdentity system)
                 (string? (p/system-id system))
                 (not (str/blank? (p/system-id system)))
                 (= :geschichte (p/system-type system)))
    (throw (ex-info "Expected a registered Geschichte SystemIdentity object"
                    {:type ::invalid-workspace-system})))
  (let [id (p/system-id system)
        registered (vec (vals (ygg/registered-systems)))
        matches (filterv #(and (satisfies? p/SystemIdentity %)
                               (= id (p/system-id %)))
                         registered)]
    (when-not (and (= 1 (count matches)) (identical? system (first matches)))
      (throw (ex-info "Geschichte workspace is missing, replaced or ambiguous"
                      {:type ::workspace-unavailable :system/id id})))
    (let [conn (gy/connection system)]
      (when-not (identical? conn (gy/connection (first matches)))
        (throw (ex-info "Geschichte workspace connection changed"
                        {:type ::workspace-changed :system/id id})))
      (when-not (= 1 (count (filter #(and (satisfies? p/SystemIdentity %)
                                          (identical? conn
                                                      (if (= :geschichte (p/system-type %))
                                                        (gy/connection %)
                                                        (:conn %))))
                                    registered)))
        (throw (ex-info "Geschichte connection has ambiguous registered system ownership"
                        {:type ::workspace-unavailable :system/id id})))
      (workspace-descriptor system conn))))

(defn assert-workspace-current!
  "HOST API: revalidate a retained descriptor's exact registered system,
   connection, canonical filesystem configuration and frozen physical target.
   Returns the ORIGINAL descriptor. Does not change selection or admit writes;
   callers retain their existing effect/permission gates and physical lock."
  [workspace]
  (when-not (and (map? workspace) (map? (:repository workspace)))
    (throw (ex-info "Expected a host workspace descriptor"
                    {:type ::invalid-workspace-descriptor})))
  (let [current (workspace-for-system! (:system workspace))
        conn (:conn current)]
    (when-not (and (identical? conn (:conn workspace))
                   (identical? conn (get-in workspace [:repository :conn]))
                   (= (:id current) (:id workspace))
                   (= (::physical-target current) (::physical-target workspace))
                   (= (dissoc (:repository current) :conn)
                      (dissoc (:repository workspace) :conn)))
      (throw (ex-info "Retained Geschichte workspace target changed"
                      {:type ::workspace-changed})))
    workspace))

(defn current-workspace
  "Virtual workspace descriptor for the bound context, or nil."
  []
  (some-> (current-system) workspace-descriptor))

(defn optional-workspace
  "Compatibility boundary for legacy callers that have no workspace. Resolve
   the current virtual workspace, or nil after an unselected resolution error.
   An explicit marker before OR after a failed lookup makes the error fatal,
   including a corrupt nil/invalid selection: never switch those callers to a
   native or other-repository fallback."
  []
  (when (ec/execution-context-bound?)
    (let [selected? (workspace-selected?)]
      (try
        (current-workspace)
        (catch Throwable e
          (when (or selected? (workspace-selected?)) (throw e))
          nil)))))

(defn filesystem
  "Create a Muschel MountFS rooted in a workspace descriptor. The returned
  filesystem is fully virtual and supports nested Geschichte worktree mounts."
  ([] (some-> (current-workspace) filesystem))
  ([{:keys [repository] :as workspace}]
   (when workspace
     (mgeschichte/make-root repository))))

(defonce ^:private workspace-locks (java.util.WeakHashMap.))

(defn workspace-lock
  "The monitor that serializes dvergr's Git operations on the repository of
   `conn` (`execute-git`, and the add-then-commit sequences built on it), so a
   commit is made of the index its caller staged. Not the connection itself:
   Datahike may synchronize on that."
  [conn]
  (locking workspace-locks
    (or (.get ^java.util.WeakHashMap workspace-locks conn)
        (let [lock (Object.)]
          (.put ^java.util.WeakHashMap workspace-locks conn lock)
          lock))))

(defn execute-git
  "Execute Git-compatible argv against a virtual workspace, under its
   `workspace-lock`."
  ([argv] (execute-git (current-workspace) argv))
  ([workspace argv]
   (if-let [{:keys [conn]} workspace]
     (locking (workspace-lock conn)
       (command/execute {:conn conn :root "/" :config (atom {})
                         :repo-relative #(let [path (str %)]
                                           (if (contains? #{"." "/"} path)
                                             ""
                                             (str/replace path #"^/+" "")))}
                        argv))
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
  (let [{fork-system :system explicit? :explicit?}
        (binding [ec/*execution-context* fork-ctx]
          {:system (current-system) :explicit? (workspace-selected?)})
        parent-system
        (when fork-system
          (if-let [parent (:parent-ctx fork-ctx)]
            (binding [ec/*execution-context* parent]
              (let [id (str (p/system-id fork-system))
                    matches (filterv #(= id (str (p/system-id %)))
                                     (vals (ygg/registered-systems)))]
                ;; A legacy child-only workspace has an empty baseline. An
                ;; explicitly selected workspace must have its exact parent
                ;; counterpart, never the parent's differently selected repo.
                (when (or explicit? (seq matches)) (selected-system! id))))
            (when explicit?
              (throw (ex-info "Selected workspace diff requires its parent context"
                              {:type ::selected-workspace-unavailable})))))]
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
