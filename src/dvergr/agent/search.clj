(ns dvergr.agent.search
  "Alternative composition (doc/design/runs-as-worlds.md): one task attempted
   by several Runs, each in its own world, weighed, and joined by a policy.

   Overlays (parallel work that is all meant to land) organize concurrent
   tasks; a search chooses among alternatives of one task, of which at most a
   few land. Its members are Run worlds from `dvergr.agent.workflow`, each a
   certified Attempt, held behind the deferred settlement gate until the
   search has decided, so no one settles a member before the search does.

   A member's weight is a log weight, as foerster's `observe` and `factor`
   accumulate it: by default the log of its Attempt's reward (a verifier's 0
   makes it impossible), or any `:weigh` function of the member, such as a
   judge's log score. The policy joins the members:

   - `:automatic` merges the heaviest possible member into the room and
     discards the rest;
   - `:review` hands the heaviest possible member to a person and discards
     the rest;
   - `:keep-all` hands every member to a person.

   `:retain k` hands the k next-heaviest possible members to a person as well
   (for inspection) instead of discarding them. A world handed to review
   leaves the search: a person settles it, through the room's merge and
   discard.

   Members run under a speculative effect filter (`speculative-effects`):
   they may read, write their own forked world, spend (model calls) and make
   requests that change nothing, but not post to Rooms, send, schedule, run
   processes or write beyond their world, since discarding a member cannot
   undo those (doc/design/speculation.md, Law 7).

   Every member's Run records the search durably (`dvergr.agent.run/
   record-search!`): first the intended decision (`:search/status :pending`),
   then, after its world is settled, the outcome (`:settled`, or `:failed`
   with the error). A search is therefore auditable after a restart, and
   `select!` again with the same `:search-id` settles only the members not yet
   settled. Settling a world after a restart is not possible: its live handle
   is process-local (doc/design/runs-as-worlds.md, Decisions 6)."
  (:require [clojure.set :as set]
            [dvergr.agent.run :as run]
            [dvergr.agent.workflow :as workflow]
            [dvergr.discourse :as d]
            [dvergr.effects :as effects]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store :as store]
            [dvergr.rooms.forks :as forks]
            [org.replikativ.spindel.engine.core :as ec]))

(def policies #{:automatic :review :keep-all})

(def speculative-effects
  "The effect filter of a search's member worlds (`dvergr.effects`): reads,
   writes in the member's own forked substrates, charged spend (model calls),
   and network requests that change nothing (`:speculative` refuses writes to
   Rooms, undeclared tools, mail syncs and HTTP other than GET, HEAD and
   OPTIONS). Egress, processes, schedules, lifecycle operations and global
   writes are refused: processes because only the jail's listed commands run
   without a network, so a shell could reach a remote."
  {:admit #{:read :write :spend :network} :speculative true})

(defn reward-weight
  "The default weight of a member: the log of its Attempt's reward, read as
   the probability that it did the task. A reward of 0 is impossible (-Inf)."
  [{:keys [attempt]}]
  (let [r (get-in attempt [:attempt/receipt :attempt/reward])]
    (if (and (number? r) (pos? r))
      (Math/log (double r))
      Double/NEGATIVE_INFINITY)))

(defn- possible? [w] (and (number? w) (> w Double/NEGATIVE_INFINITY) (not (Double/isNaN w))))

(defn validate-options!
  "Refuse search options before anything runs or is settled."
  [{:keys [policy retain weigh] :or {policy :automatic retain 0}}]
  (when-not (policies policy)
    (throw (ex-info (str "Unknown search policy " policy) {:type ::policy :policy policy})))
  (when-not (and (integer? retain) (<= 0 retain))
    (throw (ex-info ":retain is a non-negative integer" {:type ::retain :retain retain})))
  (when-not (or (nil? weigh) (ifn? weigh))
    (throw (ex-info ":weigh is a function of a member" {:type ::weigh}))))

(defn decide
  "The decision on each member's world, in member order, from their log
   `weights`: `:merge`, `:review` or `:discard` (see the namespace). Ties go
   to the earlier member. Pure."
  [weights {:keys [policy retain] :or {policy :automatic retain 0} :as opts}]
  (validate-options! opts)
  (let [ranked (->> (map-indexed vector weights)
                    (filter (comp possible? second))
                    (sort-by (fn [[i w]] [(- w) i]))
                    (map first))
        [best & next-best] ranked
        kept (set (take retain next-best))]
    (mapv (fn [i]
            (cond
              (= policy :keep-all) :review
              (= i best) (if (= policy :automatic) :merge :review)
              (kept i) :review
              :else :discard))
          (range (count weights)))))

(defn- run-id-of [member]
  (get-in member [:attempt :attempt/receipt :attempt/run-id]))

(defn- durable-run [room run-id]
  (store/-load-run (:store room) (store/conversation-id room) run-id))

(defn speculative
  "`effects` narrowed to at most `speculative-effects`: a caller may restrict a
   search's members further, never widen them (Law 7)."
  [effects]
  (let [admit (:admit speculative-effects)]
    (assoc (or effects {})
           :admit (if-let [a (:admit effects)] (set/intersection (set a) admit) admit)
           :speculative true)))

(defn- speculative-world?
  "Does the world `fork` run under the speculative filter?"
  [fork]
  (let [specs (effects/world-handlers (:ctx fork))
        admit (some (fn [[k a]] (when (= :admit k) a)) specs)]
    (boolean (and (some #(= [:speculative] %) specs)
                  admit (set/subset? admit (:admit speculative-effects))))))

(defn- preflight!
  "Refuse members this search does not hold, before anything is settled: each
   a terminal durable Run of `room` whose world is the member's world, live,
   forked from `room`, recording its Run here, held behind the deferred gate
   and running under the speculative filter; every member once; a member to
   merge needs its review token."
  [room members decisions todo]
  (let [run-ids (map run-id-of members)
        worlds (map :world members)]
    (when-not (and (every? some? run-ids) (apply distinct? run-ids)
                   (every? some? worlds) (apply distinct? worlds))
      (throw (ex-info "Search members must be distinct Runs with their worlds"
                      {:type ::members}))))
  (doseq [i todo
          :let [member (nth members i)
                run-id (run-id-of member)
                durable (durable-run room run-id)
                fork (rreg/lookup (:world member))
                meta (some-> fork :meta deref)
                refuse (fn [why] (throw (ex-info (str "Search member " run-id ": " why)
                                                 {:type ::not-held :run/id run-id :world (:world member)})))]]
    (cond
      (nil? durable) (refuse "not a durable Run of this room")
      (not (contains? store/terminal-run-statuses (:run/status durable))) (refuse "its Run has not ended")
      (not= (:world member) (:run/world durable)) (refuse "the world is not its Run's world")
      (nil? fork) (refuse "the world is gone")
      (not= (:id room) (:parent-id fork)) (refuse "the world is not forked from this room")
      (not= run-id (:run-id meta)) (refuse "the world belongs to another Run")
      (not (contains? #{nil (:id room)} (:record-room-id meta))) (refuse "its Run is recorded in another room")
      (not (forks/deferred? fork)) (refuse "the world is not held by the search in this room")
      (not (speculative-world? fork)) (refuse "the world does not run under the speculative effect filter")
      (and (= :merge (nth decisions i)) (nil? (get-in member [:review :state])))
      (refuse "a member to merge needs its review token"))))

(defonce ^:private merged-worlds
  ;; worlds whose substrate this process saw merged into their parent: the
  ;; evidence a retry reconciles from when the writes after the merge failed
  (atom #{}))

(defn- confirmed-merged?
  "Has `fork`'s substrate been merged into its parent, by this search or
   anyone: its settlement recorded as merged, or a merge claim that has ended
   with its substrate settled. A claim still in progress is not evidence: it
   may abort."
  [fork]
  (boolean
   (or (= :merged (:op (d/settlement fork)))
       (let [claim (:dvergr.discourse/settlement-claim @(:meta fork))]
         (and (= :merged (get-in claim [:settlement :op])) (false? (:active? claim)))))))

(defn- project-merged!
  "Record durably that `member`'s world merged, when its substrate merged but
   the Run's settlement was not written: true once its Run reads `:merged`."
  [room member]
  (let [run-id (run-id-of member)]
    (try (when-not (= :merged (:run/settlement-status (durable-run room run-id)))
           (run/update-durable-settlement! room run-id :merged :search-selected))
         (catch Throwable _ nil))
    (= :merged (:run/settlement-status (durable-run room run-id)))))

(defn- settle!
  "Settle a held member's world by `decision`. Returns `{:outcome :merged |
   :review | :discarded | nil, :status :settled | :failed | :incomplete,
   :error message}`. A merge refused before the world was merged leaves it in
   review; one whose substrate merged but whose cleanup or durable projection
   failed is `:merged` and `:incomplete` (the parent has its changes)."
  [room member decision]
  (let [fork (rreg/lookup (:world member))
        attempt (fn [f] (try (f) (catch Throwable t {:ok? false :error (.getMessage t)})))]
    (case decision
      :discard (let [r (attempt #(forks/discard-deferred! fork :search-unselected))]
                 (if (:ok? r)
                   {:outcome :discarded :status :settled}
                   {:status :failed :error (:error r)}))
      :review (let [r (attempt #(forks/release-deferred! fork :search-retained))]
                (if (:ok? r)
                  {:outcome :review :status :settled}
                  {:status :failed :error (:error r)}))
      :merge (let [released (attempt #(forks/release-deferred! fork :search-selected))]
               (if-not (:ok? released)
                 {:status :failed :error (:error released)}
                 (let [merged (attempt #(forks/merge! fork :expect-state (get-in member [:review :state])))
                       ;; the evidence first, before any store access can fail
                       substrate-merged? (confirmed-merged? fork)
                       _ (when substrate-merged? (swap! merged-worlds conj (:world member)))
                       durable (try (:run/settlement-status (durable-run room (run-id-of member)))
                                    (catch Throwable _ nil))]
                   (cond
                     (and (:ok? merged) (= :merged durable) (nil? (rreg/lookup (:world member))))
                     {:outcome :merged :status :settled}
                     ;; the substrate merged, its cleanup or projection did
                     ;; not: project the settlement again, never merge again
                     substrate-merged?
                     (do (swap! merged-worlds conj (:world member))
                         (if (and (project-merged! room member) (nil? (rreg/lookup (:world member))))
                           {:outcome :merged :status :settled :error (:error merged)}
                           ;; its cleanup did not finish (the world is still
                           ;; registered): kept incomplete for the host
                           {:outcome :merged :status :incomplete :error (:error merged)}))
                     ;; another caller discarded it between release and merge
                     (= :discarded (:op (d/settlement fork)))
                     {:outcome :discarded :status :settled :error (:error merged)}
                     ;; refused before merging, still there and unclaimed: it
                     ;; waits in review for a person
                     (and (rreg/lookup (:world member))
                          (not (:active? (:dvergr.discourse/settlement-claim @(:meta fork)))))
                     {:outcome :review :status :settled :error (:error merged)}
                     ;; another settlement is in progress: reconciled on retry
                     :else {:status :pending :error (:error merged)})))))))

(defn- reconciled
  "The outcome of a member whose world was settled but not recorded (a failed
   write, a crash), or whose merge was incomplete: read from its Run's
   durable settlement and its world; an incomplete merge (its recorded
   outcome is the evidence that its substrate merged) has its settlement
   projected again. nil while it is still held or incomplete."
  [room member prior]
  (let [status (:run/settlement-status (durable-run room (run-id-of member)))
        fork (rreg/lookup (:world member))]
    (cond
      (and (= :merged status) (nil? fork)) :merged
      ;; its substrate merged (recorded, or seen by this process), and its
      ;; settlement and cleanup have finished since
      (or (and (= :merged (:search/outcome prior)) (= :incomplete (:search/status prior)))
          (contains? @merged-worlds (:world member)))
      (when (and (project-merged! room member) (nil? fork)) :merged)
      (= :discarded status) :discarded
      (and (= :review status) fork (not (forks/deferred? fork))) :review
      :else nil)))

(defonce ^:private search-locks (atom {}))

(defonce ^:private search-members
  ;; search id -> the Runs of its members, in this process: a search id names
  ;; one set of members, so another set under it is refused
  (atom {}))

(defn- search-lock [search-id]
  (or (get @search-locks search-id)
      (get (swap! search-locks #(if (contains? % search-id) % (assoc % search-id (Object.)))) search-id)))

(defn select!
  "Join the `members` of one search (the rows of `workflow/attempt!` with
   `:hold? true` under `speculative` effects) in `room`: weigh them, decide,
   claim every member with the decisions, settle each world, and record each
   outcome. Call at a boundary, not inside a Spin.

   What a search decides is written once: every member's record carries the
   manifest of all members' weights and decisions, and a later record of the
   same search changes only the outcome. A Run is a member of one search,
   claimed for it all or none (`dvergr.agent.run/claim-search!`). Selection
   is serialized per search in this process. Again with the same `:search-id`
   (a crash, a failed write), `select!` takes the decisions from the
   manifest, claims what was not claimed, reconciles what was settled but not
   recorded, and settles the rest.

   Options: `:policy` (`:automatic`, default), `:weigh` (member -> log
   weight, default `reward-weight`), `:retain` (default 0), `:search-id`.
   Returns `{:search/id :search/policy :search/selected run-id-or-nil
   :search/members [{:run/id :world :search/weight :search/decision
   :search/outcome :search/status :search/error}]}`."
  [room members {:keys [policy weigh search-id] :or {policy :automatic} :as opts}]
  (validate-options! opts)
  (let [search-id (or search-id (random-uuid))]
    (locking (search-lock search-id)
      (binding [ec/*execution-context* (:ctx room)]
        (let [run-ids (mapv run-id-of members)
              _ (let [known (get (swap! search-members update search-id #(or % (set run-ids))) search-id)]
                  (when (not= known (set run-ids))
                    (throw (ex-info "A search id names one set of members"
                                    {:type ::incompatible-retry :search/id search-id}))))
              ;; a member's world is its Run's, checked before anything is
              ;; written (reconciliation reads evidence by world)
              _ (doseq [[m rid] (map vector members run-ids)
                        :let [durable (durable-run room rid)]]
                  (when-not (and durable (= (:world m) (:run/world durable)))
                    (throw (ex-info (str "Search member " rid ": the world is not its Run's world")
                                    {:type ::not-held :run/id rid :world (:world m)}))))
              records #(mapv (fn [m] (some-> (durable-run room (run-id-of m)) run/search-record)) members)
              prior (records)
              manifest (some #(when (= search-id (:search/id %)) (:search/manifest %)) prior)
              _ (when (and manifest
                           (or (not= (set (map str run-ids)) (set (keys manifest)))
                               (not-every? #(or (nil? %) (and (= search-id (:search/id %))
                                                              (= policy (:search/policy %))))
                                           prior)))
                  (throw (ex-info "A retried search must have the same members and policy"
                                  {:type ::incompatible-retry :search/id search-id})))
              ;; a retry keeps what was decided: a judge asked again may differ
              weights (if manifest
                        (mapv #(first (get manifest (str %))) run-ids)
                        (mapv #(double ((or weigh reward-weight) %)) members))
              decisions (if manifest
                          (mapv #(second (get manifest (str %))) run-ids)
                          (decide weights (assoc opts :policy policy)))
              manifest (or manifest (zipmap (map str run-ids) (map vector weights decisions)))
              intent (fn [i] {:search/id search-id :search/policy policy
                              :search/members (count members) :search/manifest manifest
                              :search/weight (nth weights i) :search/decision (nth decisions i)})
              record! (fn [i outcome]
                        (run/record-search! room (nth run-ids i)
                                            (merge (intent i) (select-keys outcome [:search/outcome :search/status :search/error]))))]
          ;; members settled but not recorded, or incomplete merges completed since
          (doseq [i (range (count members))
                  :let [p (nth prior i)]
                  :when (or (and (= search-id (:search/id p)) (#{:pending :incomplete} (:search/status p)))
                            (and (nil? p) (contains? @merged-worlds (:world (nth members i)))))
                  :let [outcome (reconciled room (nth members i) p)]
                  :when outcome]
            (record! i {:search/outcome outcome :search/status :settled}))
          (let [now (records)
                _ (doseq [[i r] (map-indexed vector now)
                          :when (and r (not= search-id (:search/id r)))]
                    (throw (ex-info (str "Search member " (nth run-ids i) " is a member of another search")
                                    {:type ::claimed-by-another-search :run/id (nth run-ids i)
                                     :claimed-by (:search/id r)})))
                done? #(and (= search-id (:search/id (nth now %)))
                            (#{:settled :incomplete} (:search/status (nth now %))))
                todo (vec (remove done? (range (count members))))]
            (preflight! room members decisions todo)
            ;; 1. claim the members, all or none, with the decisions
            (run/claim-search! room (into {} (map (fn [i] [(nth run-ids i) (assoc (intent i) :search/status :pending)])) todo))
            ;; 2. settle, the merge first; 3. record each outcome
            (doseq [i (sort-by #(if (= :merge (nth decisions %)) 0 1) todo)]
              (let [{:keys [outcome status error]} (settle! room (nth members i) (nth decisions i))]
                (record! i (cond-> {:search/status status}
                             outcome (assoc :search/outcome outcome)
                             error (assoc :search/error error))))))
          (let [rows (mapv (fn [member]
                             (-> (run/search-record (durable-run room (run-id-of member)))
                                 (dissoc :search/manifest)
                                 (assoc :run/id (run-id-of member) :world (:world member))))
                           members)]
            {:search/id search-id
             :search/policy policy
             :search/selected (some #(when (= :merged (:search/outcome %)) (:run/id %)) rows)
             :search/members rows}))))))

(defn search!
  "Attempt `task` in `room` as a search: `workflow/attempt!` (its options:
   `:task`, `:attempts`, `:models`, `:evaluator`, `:parallelism`,
   `:parent-run`, `:effects`, which can only narrow `speculative-effects`)
   with its members held for the search, then `select!` (`:policy`,
   `:weigh`, `:retain`). Options are checked before anything runs. Call at a
   boundary, not inside a Spin."
  [room opts]
  (let [search-opts (select-keys opts [:policy :weigh :retain :search-id])]
    (validate-options! search-opts)
    (select! room
             (workflow/attempt! room (-> opts
                                         (dissoc :policy :weigh :retain :search-id)
                                         (update :effects speculative)
                                         (assoc :hold? true)))
             search-opts)))
