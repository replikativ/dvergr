(ns dvergr.rooms.forks
  "Shared fork-management operations the TUI and web both call — describe a
   fork vs its parent, merge a fork back, or discard it. Thin wrappers over
   `dvergr.discourse` + `dvergr.substrate.git` so the two surfaces behave
   identically. Call with the daemon execution context bound.

   Two kinds of fork (see `dvergr.discourse/fork-room`):
   - `:isolation :ctx` — its own forked context with real git/datahike
     branches; merging actually collapses those branches into the parent.
   - `:isolation :none` — shares the parent context; \"merge\" is just a
     conversation-log append, and there is no git history to browse."
  (:require [dvergr.room.registry :as rreg]
            [dvergr.discourse :as d]
            [dvergr.substrate.git :as git]
            [clojure.string :as str]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]))

(declare fork-diff)

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
   parent ctx."
  [fork]
  (boolean (some-> fork :ctx :parent-ctx)))

(defn fork!
  "Fork `room` into an isolated `:ctx` branch — its own git worktree + datahike
   branch — so the fork can later be merged (`reconcile-merge!`) or discarded
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
       :mergeable?  (boolean parent)})))

;; ---------------------------------------------------------------------------
;; Unified diff + tier classification — the substrate the agent merge-reviewer
;; and the TUI/web review flow both read. The per-system merge-base diff/conflicts
;; now live in the spindel bridge (`spindel.yggdrasil/workspace-diff` /
;; `workspace-conflicts`), which resolves each sub-system on snapshot-ids over
;; the one composite workspace. These are thin fork-shaped wrappers.
;; ---------------------------------------------------------------------------

(defn fork-diff
  "Per-system delta of a `:ctx` fork vs its parent — the unified diff a reviewer
   reads: {system-id → typed yggdrasil delta (GitDiff / DatahikeDiff / DiffError)}.
   nil for `:none` forks (no branched substrate)."
  [fork]
  (when (ctx-fork? fork)
    (ygg/workspace-diff (:ctx fork))))

(defn fork-conflicts
  "Per-system conflicts of a `:ctx` fork vs its parent, each tagged `:system`.
   Often empty (datahike conflict detection is conservative today)."
  [fork]
  (when (ctx-fork? fork)
    (ygg/workspace-conflicts (:ctx fork))))

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

(defn review
  "Everything a reviewer (agent or human) needs to decide a merge:
   {:tier :trivial|:reviewable|:conflict :diff {system-id → delta} :conflicts [...]}.
   nil for non-`:ctx` forks."
  [fork]
  (when (ctx-fork? fork)
    (let [diff (fork-diff fork)
          conf (fork-conflicts fork)]
      {:tier (classify diff conf) :diff diff :conflicts conf})))

(defn merge!
  "Merge a fork into its parent (`discourse/merge-room`). Returns
   {:ok? true :parent-slug ...} or {:ok? false :error ...}."
  [fork]
  (try
    (if-let [parent (rreg/lookup (:parent-id fork))]
      (do (d/merge-room parent fork)
          {:ok? true :parent-slug (:slug parent) :parent-id (:id parent)})
      {:ok? false :error "fork has no live parent in the registry"})
    (catch Throwable t {:ok? false :error (.getMessage t)})))

;; ---------------------------------------------------------------------------
;; Agent merge-reviewer — an agent (a one-shot LLM) reads the diff and decides.
;; Trivial forks auto-merge; reviewable/conflict go to the reviewer.
;; ---------------------------------------------------------------------------

(declare discard!)

(defn- fmt-delta [[sid delta]]
  (cond
    (:error delta)   (str "• " sid ": (diff error) " (:error delta))
    (contains? delta :files)
    (str "• " sid " [git]: " (count (:files delta)) " file(s) changed"
         (when (seq (:files delta))
           (str " — " (str/join ", " (map :path (take 25 (:files delta))))))
         (when-let [s (:stat delta)] (str "\n" (str/trim s))))
    (contains? delta :summary)
    (let [s (:summary delta)]
      (str "• " sid " [datahike]: +" (:added-datoms s) " / -" (:removed-datoms s)
           " datoms across " (:entities-touched s) " entit"
           (if (= 1 (:entities-touched s)) "y" "ies")
           (when (seq (:added delta))
             (str "\n  added (sample): "
                  (str/join " " (map pr-str (take 12 (:added delta))))))))
    :else (str "• " sid ": " (pr-str delta))))

(defn review-prompt
  "Human/LLM-readable summary of a fork's review for the merge decision."
  [fork {:keys [tier diff conflicts]}]
  (str "Fork: " (:slug fork) "  (tier: " (name tier) ")\n"
       "Parent: " (or (try (some-> (rreg/lookup (:parent-id fork)) :slug)
                           (catch Throwable _ nil))
                      (:parent-id fork)) "\n\n"
       "Changes vs parent (note: a live parent may have advanced — judge the\n"
       "fork's OWN additions, not the parent's concurrent activity):\n"
       (str/join "\n" (map fmt-delta diff))
       (when (seq conflicts)
         (str "\n\nCONFLICTS (" (count conflicts) "): "
              (str/join "; " (map (fn [c] (str (:system c) " " (pr-str (:entity c)) " " (:attr c)))
                                  (take 10 conflicts)))))))

(def ^:private review-instruction
  (str "You are a careful merge reviewer for an agent workspace. Given a fork's "
       "changes vs its parent, decide whether to MERGE the fork back, DISCARD it, "
       "or HOLD for a human. Merge only coherent, beneficial changes; discard junk "
       "or accidental forks; hold if genuinely unsure or there are conflicts you "
       "cannot resolve. Reply with exactly one line `DECISION: MERGE|DISCARD|HOLD` "
       "followed by a one-sentence rationale."))

(defn- parse-decision [text]
  (let [t (str/upper-case (str text))]
    (condp #(str/includes? %2 %1) t
      "DECISION: MERGE"   :merge
      "DECISION: DISCARD" :discard
      "DECISION: HOLD"    :hold
      ;; fall back to a looser scan
      (cond (str/includes? t "MERGE")   :merge
            (str/includes? t "DISCARD") :discard
            :else                       :hold))))

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
   {:ok? true :reconciled N :resolutions [...] :rationale …} or {:ok? false :error}."
  [fork]
  (let [conflicts (vec (fork-conflicts fork))]
    (if (empty? conflicts)
      (merge! fork)
      (if-let [parent (rreg/lookup (:parent-id fork))]
        (try
          (let [call        (requiring-resolve 'dvergr.tools.llm-call/cheap-llm-call)
                resp        (call reconcile-instruction (reconcile-prompt conflicts) {})
                resolutions (parse-resolutions (:text resp) conflicts)]
            (d/merge-room parent fork {:merge-opts {:force true}})
            (apply-resolutions! fork resolutions)
            (try ((requiring-resolve 'dvergr.rooms.messages/refresh!) parent)
                 (catch Throwable _ nil))
            {:ok? true :reconciled (count resolutions) :resolutions resolutions
             :parent-slug (:slug parent) :rationale (:text resp)})
          (catch Throwable t {:ok? false :error (.getMessage t)}))
        {:ok? false :error "fork has no live parent in the registry"}))))

(defn merge-review!
  "Review a `:ctx` fork and act on it:
     :trivial (and `auto-trivial?`) → auto-merge, no LLM.
     :conflict → the agent CONFLICT RECONCILER resolves each field then merges.
     :reviewable → a one-shot LLM reviewer reads the diff and decides
       MERGE (→ merge!) / DISCARD (→ discard!) / HOLD (leave it).
   Returns {:tier :decision :auto? :rationale :action}, or {:error …}."
  [fork & {:keys [auto-trivial?] :or {auto-trivial? true}}]
  (if-not (ctx-fork? fork)
    {:error "not a :ctx fork — nothing to review/merge"}
    (let [{:keys [tier] :as rev} (review fork)]
      (cond
        (and auto-trivial? (= :trivial tier) (empty? (:conflicts rev)))
        {:tier tier :decision :merge :auto? true :action (merge! fork)}

        (= :conflict tier)
        {:tier tier :decision :reconcile :auto? false :action (reconcile-merge! fork)}

        :else
        (let [call (requiring-resolve 'dvergr.tools.llm-call/cheap-llm-call)
              resp (try (call review-instruction (review-prompt fork rev) {})
                        (catch Throwable t {:error (.getMessage t)}))
              text (:text resp)
              decision (if (:error resp) :hold (parse-decision text))]
          (merge {:tier tier :decision decision :auto? false :rationale text}
                 (case decision
                   :merge   {:action (merge! fork)}
                   :discard {:action (discard! fork)}
                   {:action :held})))))))

(defn discard!
  "Discard a fork (`discourse/discard`) — deletes its branches. Returns
   {:ok? true} or {:ok? false :error ...}."
  [fork]
  (try (d/discard fork) {:ok? true}
       (catch Throwable t {:ok? false :error (.getMessage t)})))
