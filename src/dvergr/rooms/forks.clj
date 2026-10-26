(ns dvergr.rooms.forks
  "Shared fork-management operations the TUI and web both call — describe a
   fork vs its parent, merge a fork back, or discard it. Thin wrappers over
   `dvergr.discourse` + `dvergr.substrate.geschichte` so the two surfaces behave
   identically. Call with the daemon execution context bound.

   Two kinds of fork (see `dvergr.discourse/fork-room`):
   - `:isolation :ctx` — its own forked context with real Datahike and
     Geschichte workspace branches; merging actually collapses those branches into the parent.
   - `:isolation :none` — shares the parent context; \"merge\" is just a
     conversation-log append, and there is no git history to browse."
  (:require [dvergr.room.registry :as rreg]
            [dvergr.agent.run :as agent-run]
            [dvergr.discourse :as d]
            [dvergr.substrate.geschichte :as git]
            [dvergr.system.rooms :as srooms]
            [dvergr.rooms.publication :as publication]
            [geschichte.repo :as repo]
            [clojure.string :as str]
            [hasch.core :as hasch]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [yggdrasil.protocols :as yp]))

(declare fork-diff)

(defn- run-record-room
  "The Room whose store keeps the Run a Run world belongs to. That is the
   world's parent, unless the Run was recorded elsewhere: a benchmark's cells
   fork its fixture room while their Runs are kept in the room it reports to."
  [fork parent]
  (or (some-> fork :meta deref :record-room-id rreg/lookup) parent))

(defn deferred?
  "True when a Run world is retained behind its host-owned settlement gate."
  [fork]
  (let [meta (some-> fork :meta deref)]
    (and (= :deferred (:settlement-policy meta))
         (not (:settlement-released? meta)))))

(defn release-deferred!
  "Release an existing deferred Run world for ordinary review/settlement.

   Durable projection is updated before the process-local gate opens, so a
   reviewer can never merge a world whose certification state is still
   `:deferred`. This does not merge, copy, or create another fork."
  ([fork reason]
   (release-deferred! fork reason (constantly true)))
  ([fork reason claim!]
   (d/with-room-lock fork
     (let [parent (rreg/lookup (:parent-id fork))
           live (rreg/lookup (:id fork))
           meta @(:meta fork)
           run-id (:run-id meta)]
       (when-not (and (= fork live)
                      (= :deferred (:settlement-policy meta))
                      (not (:settlement-released? meta))
                      (nil? (:settlement-claim meta)))
         (throw (ex-info "Fork is not awaiting deferred settlement"
                         {:type ::not-deferred :fork/id (:id fork)})))
       (when-not (and parent run-id)
         (throw (ex-info "Deferred Run world has no live settlement owner"
                         {:type ::missing-settlement-owner
                          :fork/id (:id fork)
                          :run/id run-id})))
       (when-not (claim!)
         (throw (ex-info "Deferred release lost its settlement race"
                         {:type ::deferred-settlement-aborted
                          :fork/id (:id fork)})))
       (agent-run/update-durable-settlement! (run-record-room fork parent) run-id :review reason)
       (swap! (:meta fork) assoc :settlement-released? true
              :settlement-claim :release)
       {:ok? true :status :review :run/id run-id :fork/id (:id fork)}))))

(defn discard-deferred!
  "Durability-first consumption of a deferred Run world's discard capability.

   The terminal projection is written by the claim callback while the fork's
   affine settlement lock is held, before substrate destruction. A projection
   failure therefore leaves the world live. If physical discard fails after
   preparation, compensate the still-live world back to `:deferred`."
  ([fork reason]
   (discard-deferred! fork reason (constantly true)))
  ([fork reason claim!]
   (let [parent (rreg/lookup (:parent-id fork))
         run-id (some-> fork :meta deref :run-id)
         prepared? (atom false)
         durable-claim!
         (fn []
           (when (claim!)
             (when (and parent run-id)
               (agent-run/update-durable-settlement! (run-record-room fork parent) run-id :discarded
                                                     reason))
             (reset! prepared? true)
             true))
         durable-abort!
         (fn []
           (when (and @prepared? parent run-id)
             (agent-run/update-durable-settlement!
              (run-record-room fork parent) run-id :deferred :discard-failed)))]
     (try
       (d/discard-deferred fork durable-claim! durable-abort!)
       {:ok? true}
       (catch Throwable error
         {:ok? false :error (ex-message error)})))))

(defn retry-deferred-discard-abort!
  "Restore a live world fenced by a failed durable discard compensation."
  [fork]
  (d/with-room-lock fork
    (let [meta @(:meta fork)
          parent (rreg/lookup (:parent-id fork))
          live (rreg/lookup (:id fork))
          run-id (:run-id meta)]
      (when-not (and (= fork live)
                     (= :deferred (:settlement-policy meta))
                     (= :discard-recovery (:settlement-claim meta))
                     parent run-id)
        (throw (ex-info "Fork has no current deferred discard recovery"
                        {:type ::no-discard-recovery
                         :fork/id (:id fork)})))
      (agent-run/update-durable-settlement! (run-record-room fork parent) run-id :deferred
                                            :discard-failed)
      (swap! (:meta fork) dissoc :settlement-claim)
      {:ok? true :status :deferred :run/id run-id :fork/id (:id fork)})))

(defn- require-settleable! [fork operation]
  (when (deferred? fork)
    (throw (ex-info "Deferred Run world must be released before settlement"
                    {:type ::settlement-deferred
                     :operation operation
                     :fork/id (:id fork)
                     :run/id (some-> fork :meta deref :run-id)}))))

(defn fork?
  "Is this Room a fork? The canonical marker is `:forked-from` in the room's
   meta — set by `fork-room` — the SAME thing `dvergr.rooms.tree` keys its
   `:fork` node kind on. (`:parent-id` alone is not enough: a DM room nests
   under a parent in the tree without being a fork.)"
  [room]
  (boolean (some-> room :meta deref :forked-from)))

(defn ctx-fork?
  "True when the fork holds its OWN forked execution context (`:isolation
   :ctx`) — i.e. it has real branches to merge. `:none` forks share the
   parent ctx, which may itself already be forked. The canonical ForkHandle,
   not the context's ancestry, distinguishes the two isolation modes."
  [fork]
  (boolean (d/fork-handle fork)))

(defn fork!
  "Fork `room` into an isolated `:ctx` branch — its own Geschichte workspace
   branch + Datahike branches — so the fork can later be merged (`reconcile-merge!`) or discarded
   (`discard!`). The canonical fork op both the TUI (`f`) and web (`/fork`) call.
   Returns the new fork Room. Call with the daemon execution context bound."
  [room]
  (d/fork-room room {:isolation :ctx}))

(defn detail
  "Fork-vs-parent summary for the management UI, or nil for a non-fork:
     {:slug :id :parent-id :parent-slug :isolation
      :commits :commit-list :diff-stat :files :msgs-since :mergeable?}
   Call with the daemon ctx bound."
  [fork]
  (when (fork? fork)
    (let [parent (rreg/lookup (:parent-id fork))
          gdiff  (when (ctx-fork? fork)
                   (try (git/diff-since-fork (:ctx fork)) (catch Throwable _ nil)))
          ;; Datahike side of the diff — what the KB / messages-&-schedules stores
          ;; gained, alongside the git diff. Shared so the TUI + web render BOTH the
          ;; git AND the database changes a `merge!` would collapse into the parent.
          db-changes (when (ctx-fork? fork)
                       (try (->> (fork-diff fork)
                                 (keep (fn [[sid delta]]
                                         (let [s (:summary delta)]
                                           (when (and s (pos? (long (or (:added-datoms s) 0))))
                                             {:system   (str sid)
                                              :store    (cond (clojure.string/includes? (str sid) "-kb-")   :knowledge
                                                              (clojure.string/includes? (str sid) "-msgs-") :messages
                                                              :else :other)
                                              :added    (:added-datoms s)
                                              :removed  (:removed-datoms s)
                                              :entities (:entities-touched s)}))))
                                 vec)
                            (catch Throwable _ nil)))
          flen   (or (:forked-at-len fork) 0)
          total  (try (count (d/log fork)) (catch Throwable _ 0))]
      {:slug        (:slug fork)
       :id          (:id fork)
       :parent-id   (:parent-id fork)
       :parent-slug (some-> parent :slug)
       :isolation   (if (ctx-fork? fork) :ctx :none)
       :commits     (count (:commits gdiff))
       :commit-list (vec (:commits gdiff))
       :diff-stat   (:stat gdiff)
       :files       (vec (:files gdiff))
       :db-changes  db-changes
       :msgs-since  (max 0 (- total flen))
       :mergeable?  (boolean (and parent (not (deferred? fork))))
       :settlement-deferred? (deferred? fork)})))

;; ---------------------------------------------------------------------------
;; Unified diff + tier classification — the substrate the agent merge-reviewer
;; and the TUI/web review flow both read. The per-system merge-base diff/conflicts
;; now live in the spindel bridge (`spindel.yggdrasil/context-diff` /
;; `context-conflicts`), which resolves each sub-system on snapshot-ids over
;; the forked context's registered systems. These are thin fork-shaped wrappers.
;; ---------------------------------------------------------------------------

(defn fork-diff
  "Per-system delta of a `:ctx` fork vs its parent — the unified diff a reviewer
   reads: {system-id → typed yggdrasil delta (GitDiff / DatahikeDiff / DiffError)}.
   nil for `:none` forks (no branched substrate)."
  [fork]
  (when (ctx-fork? fork)
    (ygg/context-diff (:ctx fork))))

(defn fork-conflicts
  "Per-system conflicts of a `:ctx` fork vs its parent, each tagged `:system`.
   Often empty (datahike conflict detection is conservative today)."
  [fork]
  (when (ctx-fork? fork)
    (ygg/context-conflicts (:ctx fork))))

(defn- delta-trivial?
  "Did the FORK contribute nothing meaningful in this system? Duck-typed over
   the yggdrasil diff records (GitDiff :files, DatahikeDiff :summary, DiffError).

   For datahike we key on `:added-datoms` (the fork's OWN additions), not
   `:entities-touched` — because diffing parent→fork in a live system also
   reports the parent's concurrent advance as `removed`, which is a
   merge-from-parent (staleness) concern, not the fork's work. The precise fix
   is to diff against the merge-base (common-ancestor) so only fork-local
   changes show; `:added-datoms` is the safe first cut until then."
  [delta]
  (cond
    (nil? delta)               true
    (contains? delta :intents) (empty? (:intents delta))    ; a book settling by replay
    (:error delta)             false                       ; DiffError
    (contains? delta :files)   (empty? (:files delta))     ; GitDiff
    (contains? delta :summary) (zero? (long (or (:added-datoms (:summary delta)) 0)))
    :else                      false))

(defn classify
  "Tier a fork's diff + conflicts — the SAME tiers simmis uses:
     :trivial    — nothing meaningfully touched, no conflicts (safe to auto-merge)
     :reviewable — real changes worth a look
     :conflict   — at least one conflicting system."
  [diff-map conflicts]
  (cond
    (seq conflicts)                          :conflict
    (every? delta-trivial? (vals diff-map))  :trivial
    :else                                    :reviewable))

(defn workspace-changes
  "Uncommitted paths in `room`'s workspace (git porcelain status lines), or
   nil when it has none or no workspace."
  [room]
  (binding [ec/*execution-context* (:ctx room)]
    (try
      (when (git/current-workspace)
        (let [{:keys [stdout exit]} (git/execute-git ["status" "--porcelain"])]
          (when (zero? exit)
            (not-empty (vec (remove str/blank? (str/split-lines (str stdout))))))))
      (catch Throwable _ nil))))

(defn commit-workspace!
  "Commit every change in `room`'s workspace with `message`. A merge adopts
   commits only: uncommitted files in a fork are lost on merge, and a dirty
   parent refuses one. Returns the changed paths, or nil when clean."
  [room message]
  (when-let [changes (workspace-changes room)]
    (binding [ec/*execution-context* (:ctx room)]
      (let [workspace (git/current-workspace)]
        ;; add and commit as one step: no other git operation stages between
        (locking (git/workspace-lock (:conn workspace))
          (doseq [argv [["add" "."] ["commit" "-m" message]]]
            (let [{:keys [exit stderr]} (git/execute-git workspace argv)]
              (when-not (zero? exit)
                (throw (ex-info (str "git " (first argv) " failed in " (:id room) ": " stderr)
                                {:type ::workspace-commit-failed :room (:id room) :argv argv}))))))))
    changes))

(defn- configured-author
  "The author a Git commit in this repository gets: its user.name and
   user.email, as geschichte's `git commit` reads them."
  [conn]
  (let [cfg (repo/configuration conn)
        n (get cfg "user.name") e (get cfg "user.email")]
    (cond (and n e) (str n " <" e ">") n n :else "unknown")))

(defn commit-paths-in-workspace!
  "HOST API: commit exactly the paths of `expected` (`{path content-id}`, nil
   for a removed path) in one retained, registered workspace descriptor, under
   the workspace lock. The paths are staged by name (`repo/stage!`, no
   pathspec parsing). The index must hold nothing else staged, and once the
   paths are staged they must hold the expected content: otherwise nothing is
   committed (a concurrent writer changed them) and it throws. Returns the
   commit id, or nil when the paths already match HEAD. With `:receipt? true`,
   returns {:commit id-or-nil :tree validated-index}; the tree is captured
   BEFORE publication, so obtaining a receipt requires no postcommit read.
   Optional :expected-modes is checked against that same staged index. The
   exact descriptor is revalidated; ambient selection is never consulted."
  ([room workspace expected message] (commit-paths-in-workspace! room workspace expected message {}))
  ([room workspace expected message {:keys [receipt? expected-modes publication required-subtree removed-paths subtree-basis stage-witness]}]
   (when (and (map? publication) (contains? publication :validate-basis!)
              (not (fn? (:validate-basis! publication))))
     (throw (ex-info "An explicit publication validator must be a host function"
                     {:type ::invalid-publication-validator})))
   (binding [ec/*execution-context* (:ctx room)]
     (let [{:keys [conn]} (git/assert-workspace-current! workspace)
           rel #(str/replace (str %) #"^/+" "")
           expected (update-keys expected rel)
           paths (vec (sort (keys expected)))]
       (locking (git/workspace-lock conn)
         (git/assert-workspace-current! workspace)
         (let [staged (:staged (repo/status conn))]
           (when (seq (remove (set paths) staged))
             (throw (ex-info (str "The workspace of " (name (:id room)) " has staged changes ("
                                  (str/join ", " (take 5 staged)) "); commit them first")
                             {:type ::staged-changes :room (:id room) :staged staged}))))
         (when (seq paths)
           (if required-subtree
             (do (when-not (and (:noop? (:plan subtree-basis)) stage-witness)
                   (throw (ex-info "Missing original clean subtree stage basis" {:type ::concurrent-change})))
                 (publication/stage-subtree! conn subtree-basis required-subtree
                                             (mapv #(str "/" %) paths) stage-witness))
             (repo/stage! conn paths))
           (let [index (repo/index-tree conn)
                 staged (:staged (repo/status conn))
                ;; the content, and a regular file: a link to the same text has the same id
                 link? #(= 0120000 (bit-and (long (or (:mode (get index %)) 0)) 0170000))
                 differs (concat (filter #(or (not= (get expected %) (some-> (get index %) :content str))
                                              (link? %)
                                              (and (contains? expected-modes (str "/" %))
                                                   (not= (get expected-modes (str "/" %))
                                                         (:mode (get index %)))))
                                         paths)
                                 (remove (set paths) staged))]
             (when (seq differs)
               (throw (ex-info (str "Changed while it was written: " (str/join ", " differs))
                               {:type ::concurrent-change :room (:id room) :paths (vec differs)})))
             (let [options {:message message :author (configured-author conn)}
                   published
                   (when publication
                     (let [{:keys [identity authorize! before-dispatch! validate-basis!]} publication
                           plan (cond-> (if (contains? publication :validate-basis!)
                                          (publication/prepare conn options (mapv #(str "/" %) paths) validate-basis!)
                                          (publication/prepare conn options (mapv #(str "/" %) paths)))
                                  required-subtree (assoc :required-subtree required-subtree))
                           _ (when-not (= index (:index plan))
                               (throw (ex-info "Staged files changed during publication preparation"
                                               {:type ::concurrent-change :room (:id room)})))
                           candidate (publication/receipt
                                      plan
                                      (select-keys identity [:publication/id :publication/intent
                                                             :publication/actor :publication/proposal
                                                             :publication/manifest :publication/attempt])
                                      (into {} (map (fn [p] [(str "/" p) (get index p)])) paths))]
                       (when-not (= identity (select-keys candidate (keys identity)))
                         (throw (ex-info "Publication target changed before dispatch"
                                         {:type ::publication-target-changed})))
                       ;; Host policy is current at the final boundary. Journal
                       ;; applying durably BEFORE the native operation can start.
                       (publication/authorize! publication)
                       (when-let [reason (srooms/room-effect-write-refusal
                                          room {:effect :room/write
                                                :resource {:system-id (srooms/connection-system-id conn)}})]
                         (throw (ex-info "Room policy changed before publication"
                                         {:type ::publication-policy-changed :reason reason})))
                       (git/assert-workspace-current! workspace)
                       (before-dispatch! candidate)
                       (publication/publish! conn plan candidate)))
                   conditional
                   (when (and required-subtree (nil? publication))
                     (let [plan (assoc (repo/prepare-commit conn options)
                                       :required-subtree required-subtree
                                       :subtree/removed (vec removed-paths))]
                       (when-not (every? #(and (contains? expected (rel %))
                                               (nil? (get expected (rel %)))) removed-paths)
                         (throw (ex-info "Invalid conditional cleanup paths" {:type ::concurrent-change})))
                       (when-not (= index (:index plan))
                         (throw (ex-info "Index changed before conditional publication" {:type ::concurrent-change})))
                       (when-let [reason (srooms/room-effect-write-refusal
                                          room {:effect :room/write
                                                :resource {:system-id (srooms/connection-system-id conn)}})]
                         (throw (ex-info "Room policy changed before conditional publication"
                                         {:type ::publication-policy-changed :reason reason})))
                       (git/assert-workspace-current! workspace)
                       (publication/commit-prepared! conn plan)))
                   commit (cond publication (some-> (:publication/commit published) str)
                                required-subtree (:commit conditional)
                                (seq staged)
                                (do (git/assert-workspace-current! workspace)
                                    (some-> (repo/commit! conn options) :geschichte.commit/id str)))]
               (if receipt?
                 (cond-> {:commit commit :tree index} published (assoc :publication published))
                 commit)))))))))

(defn commit-paths!
  "Commit exactly `expected` paths in the room's ambient workspace. Legacy
   resolution happens once; the explicit host implementation retains that same
   validated descriptor through staging and commit. See commit-paths-in-workspace!."
  ([room expected message] (commit-paths! room expected message {}))
  ([room expected message opts]
   (binding [ec/*execution-context* (:ctx room)]
     (let [workspace (or (git/current-workspace)
                         (throw (ex-info "Room has no workspace"
                                         {:type ::no-workspace :room (:id room)})))]
       (commit-paths-in-workspace! room workspace expected message opts)))))

(defn- refuse-dirty-parent! [parent]
  (when-let [dirty (workspace-changes parent)]
    (throw (ex-info (str "The parent " (name (:id parent)) " has uncommitted changes ("
                         (str/join ", " (take 5 dirty)) (when (< 5 (count dirty)) ", …")
                         "); commit or discard them before merging a fork into it")
                    {:type ::parent-dirty :parent (:id parent) :uncommitted dirty}))))

(defn- prepare-workspaces!
  "Make `fork` mergeable into `parent`: commit the fork's work (a merge adopts
   commits only), and refuse a parent with uncommitted changes here, before
   the merge fences the fork (geschichte refuses a merge into a dirty
   workspace, and that refusal left the fork fenced, unmergeable)."
  [parent fork]
  (refuse-dirty-parent! parent)
  (commit-workspace! fork (str "Work of fork " (name (:id fork)))))

(defn workspace-merge-guard
  "A `discourse/merge-room` guard for unpinned workspace settlement.
   Runs after the fork's work is drained: refuse a dirty parent, commit the
   quiescent fork, then recheck live merge permissions against those commits.
   Hold the parent's workspace lock through preparation and substrate merge.
   Pinned review-state guards remain separate: they must not alter their input."
  [parent fork]
  (fn [merge-substrate!]
    (let [workspace (binding [ec/*execution-context* (:ctx parent)] (git/current-workspace))
          settle! (fn []
                    (prepare-workspaces! parent fork)
                    (when (d/fork-handle fork)
                      (srooms/assert-merge-permitted!
                       (or (some-> fork :meta deref :origin-slug) (:slug parent)) (:ctx fork)))
                    (merge-substrate!))]
      (if workspace
        (locking (git/workspace-lock (:conn workspace)) (settle!))
        (settle!)))))

(defn uncommitted
  "What `room`'s workspace holds that is not committed, exactly: one entry per
   changed path from the repository's structured status (`repo/status-entries`;
   no parsing of status lines), with the path's worktree and index entries
   (content id and mode: a symbolic link by its target, an executable bit
   too). Sorted by path; nil when clean or without a workspace."
  [room]
  (binding [ec/*execution-context* (:ctx room)]
    (when-let [{:keys [conn]} (git/current-workspace)]
      (let [entries (repo/status-entries conn)]
        (when (seq entries)
          (let [work (repo/worktree conn)
                index (repo/index-tree conn)]
            (vec (for [{:keys [path] :as e} (sort-by :path entries)]
                   {:path path :status e :worktree (get work path) :index (get index path)}))))))))

(defn state-token
  "A content hash of `fork`'s state: its mergeable systems' snapshot ids
   (workspace commit, datahike commit, …) and what its uncommitted files hold
   (a merge commits them). It changes whenever the fork does, so a merge can
   be pinned to the state that was reviewed (the `room_merge` op's
   `expect-state`, `dvergr.ops` `:room/merge`; `merge!` checks it inside the
   merge). nil for non-`:ctx` forks."
  [fork]
  (when (ctx-fork? fork)
    (let [dirty (uncommitted fork)]
      (binding [ec/*execution-context* (:ctx fork)]
        (str (hasch/uuid
              (cond-> (into (sorted-map)
                            (for [[sid sys] (ygg/registered-systems)
                                  :when (satisfies? yp/Mergeable sys)]
                              [(str sid) (str (yp/snapshot-id sys))]))
                dirty (assoc "workspace/uncommitted" dirty))))))))

(defn review
  "Everything a reviewer (agent or human) needs to decide a merge:
   {:tier :trivial|:reviewable|:conflict :diff {system-id → delta} :conflicts [...]
    :uncommitted [paths] :state token}. Uncommitted workspace files are not in
   the diff yet (`merge!` commits them first), so they make a fork reviewable.
   nil for non-`:ctx` forks."
  [fork]
  (when (ctx-fork? fork)
    (let [state (state-token fork)
          diff (fork-diff fork)
          conf (fork-conflicts fork)
          uncommitted (workspace-changes fork)
          tier (classify diff conf)]
      {:tier (if (and (= :trivial tier) (seq uncommitted)) :reviewable tier)
       :diff diff :conflicts conf :uncommitted (vec uncommitted) :state state})))

(defn- pinned
  "A `discourse/merge-room` guard: inside the merge (the fork's work closed
   and drained), under the fork's workspace lock (no write between the check
   and the merge), the fork must still be in `expect-state`; then its
   uncommitted files are committed and the substrate merged."
  [fork expect-state]
  (fn [merge-substrate!]
    (binding [ec/*execution-context* (:ctx fork)]
      (let [check-and-merge
            (fn []
              (let [now (state-token fork)]
                (when (not= expect-state now)
                  (throw (ex-info (str "Fork " (name (:id fork)) " changed since it was reviewed"
                                       " (reviewed " expect-state ", now " now "); review it again")
                                  {:type ::fork-changed :expected expect-state :actual now}))))
              (commit-workspace! fork (str "Work of fork " (name (:id fork))))
              (merge-substrate!))]
        (if-let [ws (git/current-workspace)]
          (locking (git/workspace-lock (:conn ws)) (check-and-merge))
          (check-and-merge))))))

(defn merge!
  "Merge a fork into its parent (`discourse/merge-room`). With
   `:expect-state` (a `state-token`), only if the fork is still in that state,
   checked inside the merge. Returns {:ok? true :parent-slug ...} or {:ok?
   false :error ...}."
  [fork & {:keys [expect-state]}]
  (try
    (require-settleable! fork :merge)
    (if-let [parent (rreg/lookup (:parent-id fork))]
      (let [run-id (some-> fork :meta deref :run-id)]
        (if expect-state
          (do (refuse-dirty-parent! parent)
              (d/merge-room parent fork {:guard (pinned fork expect-state)}))
          (d/merge-room parent fork {:guard (workspace-merge-guard parent fork)}))
        (when run-id
          (agent-run/update-durable-settlement! (run-record-room fork parent) run-id :merged :review-approved))
        {:ok? true :parent-slug (:slug parent) :parent-id (:id parent)})
      {:ok? false :error "fork has no live parent in the registry"})
    (catch Throwable t {:ok? false :error (.getMessage t)})))

;; ---------------------------------------------------------------------------
;; Agent conflict reconciler — when two branches changed the SAME field
;; differently (a genuine 3-way conflict the identity-keyed merge can't union),
;; a one-shot agent picks the winning value per field; we force past the merge
;; guard, then override the chosen fields in the parent.
;; ---------------------------------------------------------------------------

(def ^:private reconcile-instruction
  (str "You are reconciling a merge between two branches of an agent workspace "
       "that changed the SAME fields to DIFFERENT values. For EACH numbered "
       "conflict choose which value the merged result keeps: OURS (the parent's "
       "value) or THEIRS (the fork's value). Prefer the more correct / complete / "
       "recent value; when truly equivalent prefer THEIRS (the fork did the "
       "focused work). Reply one line per conflict, exactly: `N: OURS` or `N: THEIRS`."))

(defn- reconcile-prompt [conflicts]
  (str "Resolve these " (count conflicts) " field conflict(s):\n\n"
       (str/join "\n\n"
                 (map-indexed
                  (fn [i c]
                    (str (inc i) ". [" (:system c) "] " (pr-str (:entity c)) "  " (:attr c)
                         "\n   base:   " (pr-str (:base c))
                         "\n   OURS:   " (pr-str (:ours c))
                         "\n   THEIRS: " (pr-str (:theirs c))))
                  conflicts))))

(defn- parse-resolutions
  "Attach :resolved to each conflict by scanning the reply for `N: OURS|THEIRS`.
   Default THEIRS (the fork's focused change) when a line is missing/unparseable."
  [text conflicts]
  (let [t (str text)]
    (vec (map-indexed
          (fn [i c]
            (let [m    (re-find (re-pattern (str "(?im)^\\s*" (inc i) "\\s*[:.)\\-]\\s*(OURS|THEIRS)")) t)
                  pick (or (some-> m second str/upper-case) "THEIRS")]
              (assoc c :pick pick :resolved (if (= pick "OURS") (:ours c) (:theirs c)))))
          conflicts))))

(defn- apply-resolutions!
  "Override conflicted fields in the parent with the agent's chosen values.
   THEIRS picks already won the force-merge, so only non-THEIRS need a write."
  [fork resolutions]
  (let [pctx (or (some-> fork :ctx :parent-ctx) (:ctx fork))
        tx   (requiring-resolve 'datahike.api/transact)]
    (binding [ec/*execution-context* pctx]
      (doseq [{:keys [system entity attr resolved theirs]} resolutions
              :when (not= resolved theirs)]
        (when-let [conn (:conn (ygg/system system))]
          (try (tx conn [[:db/add entity attr resolved]])
               (catch Throwable _ nil)))))))

(defn reconcile-merge!
  "Merge a fork, reconciling genuine 3-way field conflicts with a one-shot agent.
   No conflicts → a clean identity-keyed union (`merge!`). Conflicts → the agent
   picks OURS/THEIRS per field; we force past the conflict guard, then override
   the chosen fields in the parent. Returns
   {:ok? true :reconciled N :resolutions [...] :rationale …} or {:ok? false :error}.
   `:expect-state` pins it as in `merge!`."
  [fork & {:keys [expect-state]}]
  (let [conflicts (vec (fork-conflicts fork))
        ;; a book settling by replay conflicts on CLAIMS (a bank line, an
        ;; invoice both sides booked): no field to pick a side of, and forcing
        ;; would book it twice. A decision about which intents to drop.
        claims (filterv #(= :parent (:with %)) conflicts)]
    (cond
      (seq claims)
      {:ok? false
       :error (str "The fork's book claims what the room claimed meanwhile ("
                   (count claims) " conflict(s)); decide which of the fork's entries to drop — "
                   "they cannot be reconciled field by field")
       :book-conflicts claims}

      (empty? conflicts)
      (merge! fork :expect-state expect-state)

      :else
      (if-let [parent (rreg/lookup (:parent-id fork))]
        (try
          (if expect-state (refuse-dirty-parent! parent) (prepare-workspaces! parent fork))
          (let [call        (requiring-resolve 'dvergr.tools.llm-call/cheap-llm-call)
                resp        (call reconcile-instruction (reconcile-prompt conflicts) {})
                resolutions (parse-resolutions (:text resp) conflicts)]
            (d/merge-room parent fork (cond-> {:merge-opts {:force true}}
                                        expect-state (assoc :guard (pinned fork expect-state))))
            (apply-resolutions! fork resolutions)
            (try ((requiring-resolve 'dvergr.rooms.messages/refresh!) parent)
                 (catch Throwable _ nil))
            {:ok? true :reconciled (count resolutions) :resolutions resolutions
             :parent-slug (:slug parent) :rationale (:text resp)})
          (catch Throwable t {:ok? false :error (.getMessage t)}))
        {:ok? false :error "fork has no live parent in the registry"}))))

(defn discard!
  "Discard a fork (`discourse/discard`) — deletes its branches. Returns
   {:ok? true} or {:ok? false :error ...}. An explicit reason lets trusted
   policies distinguish rejection, evaluation policy, and failed certification."
  ([fork]
   (discard! fork :review-rejected))
  ([fork reason]
   (try
     (require-settleable! fork :discard)
     (let [parent (rreg/lookup (:parent-id fork))
           run-id (some-> fork :meta deref :run-id)]
       (let [{:keys [outcome settlement in-progress?]} (d/discard-outcome fork)]
         (cond
           ;; another settlement holds the fork, or a merge settled it: this
           ;; discard did nothing, and the Run's status is not a discard.
           ;; One that lost to a completed discard writes it below: the
           ;; winner may not have (a plain `d/discard`)
           (and (= :lost outcome) (or in-progress? (not= :discarded (:op settlement))))
           {:ok? false
            :error (str "fork " (:id fork) (if in-progress? " is being " " was ") (name (:op settlement)))}

           :else
           (do (when (and parent run-id)
                 (agent-run/update-durable-settlement! (run-record-room fork parent) run-id :discarded reason))
               {:ok? true}))))
     (catch Throwable t {:ok? false :error (.getMessage t)}))))

(defn adopt!
  "Promote a retained isolated fork into an external durable owner.

   `opts` is passed to `discourse/transfer-fork!` and must contain durable
   `:prepare!` and compensating `:abort!` callbacks. The returned ForkHandle is live
   process-local authority for the adopter. When this is a Run world, its
   durable projection is correlated as `:adopted`; a projection failure is
   reported without hiding the already-transferred capability.

   Adoption initially transfers the whole world. Use `partition-adoption!` to
   split its settlement authority before making per-system decisions; callers
   must never settle descriptor systems by bypassing those handles."
  [fork new-owner opts]
  (try
    (require-settleable! fork :adopt)
    (let [parent (rreg/lookup (:parent-id fork))
          run-id (some-> fork :meta deref :run-id)
          transfer (d/transfer-fork! fork new-owner opts)
          projection-error
          (when (and parent run-id)
            (try
              (agent-run/update-durable-settlement! (run-record-room fork parent) run-id :adopted
                                                    :governance-transfer)
              nil
              (catch Throwable error error)))]
      (cond-> (assoc transfer :ok? true)
        projection-error
        (assoc :warning "Fork transferred, but the Run settlement projection was not updated"
               :projection-error (ex-message projection-error))))
    (catch Throwable error
      {:ok? false :error (ex-message error)})))

(defn partition-adoption!
  "Split an adopted world's settlement authority into exhaustive system scopes.

   This is the Room-facing wrapper over `discourse/partition-transferred-fork!`.
   See that function for partition shape and optional durability callbacks."
  ([adoption partitions]
   (d/partition-transferred-fork! adoption partitions))
  ([adoption partitions lifecycle]
   (d/partition-transferred-fork! adoption partitions lifecycle)))

(defn settle-adoption!
  "Settle one adopted leaf with optional durable governance callbacks."
  ([adoption operation]
   (d/settle-transferred-fork! adoption operation))
  ([adoption operation lifecycle]
   (d/settle-transferred-fork! adoption operation lifecycle)))

(def retry-partition-commit!
  "Retry durable persistence of an adoption's exact partition descriptors."
  d/retry-partition-commit!)

(def retry-settlement-commit!
  "Retry durable terminal persistence without touching substrate authority."
  d/retry-settlement-commit!)

(def retry-settlement-abort!
  "Retry durable compensation after settlement preflight left authority open."
  d/retry-settlement-abort!)

(def release-adoption!
  "Release Dvergr ancestry after the whole governed capability tree is durable."
  d/release-transferred-fork!)
