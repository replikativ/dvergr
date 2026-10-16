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
   they may read, write their own forked world, spend and run jailed
   processes, but not reach the network, send, schedule or write beyond their
   world, since discarding a member cannot undo those (doc/design/
   speculation.md, Law 7).

   Every member's Run records the search durably (`dvergr.agent.run/
   record-search!`): first the intended decision (`:search/status :pending`),
   then, after its world is settled, the outcome (`:settled`, or `:failed`
   with the error). A search is therefore auditable after a restart, and
   `select!` again with the same `:search-id` settles only the members not yet
   settled. Settling a world after a restart is not possible: its live handle
   is process-local (doc/design/runs-as-worlds.md, Decisions 6)."
  (:require [dvergr.agent.run :as run]
            [dvergr.agent.workflow :as workflow]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store :as store]
            [dvergr.rooms.forks :as forks]
            [org.replikativ.spindel.engine.core :as ec]))

(def policies #{:automatic :review :keep-all})

(def speculative-effects
  "The effect filter of a search's member worlds (`dvergr.effects`): local
   reads and writes in the member's own forked world, charged spend, and
   jailed processes. Network access, egress, schedules, lifecycle operations
   and global writes are refused until they are classified finer (an HTTP GET
   is reversible, a POST is not)."
  {:admit #{:read :write :spend :process}})

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

(defn- preflight!
  "Refuse members this search does not own, before anything is settled: each
   a durable Run of `room`, its world live, forked from `room`, and held
   behind the deferred gate, every member once; a member to merge needs its
   review token. A member a previous attempt of this search settled already
   is exempt."
  [room members decisions search-id]
  (let [run-ids (map run-id-of members)
        worlds (map :world members)]
    (when-not (and (every? some? run-ids) (apply distinct? run-ids)
                   (every? some? worlds) (apply distinct? worlds))
      (throw (ex-info "Search members must be distinct Runs with their worlds"
                      {:type ::members})))
    (doseq [[member decision] (map vector members decisions)
            :let [run-id (run-id-of member)
                  durable (durable-run room run-id)
                  prior (run/search-record durable)]
            :when (not (and (= search-id (:search/id prior)) (= :settled (:search/status prior))))]
      (when-not durable
        (throw (ex-info "A search member is not a durable Run of this room"
                        {:type ::foreign-member :run/id run-id :room (:id room)})))
      (let [fork (rreg/lookup (:world member))]
        (when-not (and fork (= (:id room) (:parent-id fork)) (forks/deferred? fork))
          (throw (ex-info "A search member's world is not held by the search in this room"
                          {:type ::not-held :run/id run-id :world (:world member)})))
        (when (and (= :merge decision) (nil? (get-in member [:review :state])))
          (throw (ex-info "A member to merge needs its review token"
                          {:type ::no-review-token :run/id run-id})))))))

(defn- settle!
  "Settle a held member's world by `decision`. Returns `{:decision actual
   :ok? bool :error message}`: a merge that fails leaves the world in review."
  [{:keys [world review]} decision]
  (let [fork (rreg/lookup world)
        attempt (fn [f] (try (f) (catch Throwable t {:ok? false :error (.getMessage t)})))]
    (case decision
      :discard (let [r (attempt #(forks/discard-deferred! fork :search-unselected))]
                 {:decision :discard :ok? (boolean (:ok? r)) :error (:error r)})
      :review (let [r (attempt #(forks/release-deferred! fork :search-retained))]
                {:decision :review :ok? (boolean (:ok? r)) :error (:error r)})
      :merge (let [released (attempt #(forks/release-deferred! fork :search-selected))]
               (if-not (:ok? released)
                 {:decision :merge :ok? false :error (:error released)}
                 (let [merged (attempt #(forks/merge! fork :expect-state (:state review)))]
                   (if (:ok? merged)
                     {:decision :merge :ok? true}
                     ;; released, not merged: it waits in review for a person
                     {:decision :review :ok? true :error (:error merged)})))))))

(defn select!
  "Join the `members` of one search (the rows of `workflow/attempt!` with
   `:hold? true`) in `room`: weigh them, decide, record the intended
   decisions, settle each world, and record each outcome. Call at a boundary,
   not inside a Spin.

   Options: `:policy` (`:automatic`, default), `:weigh` (member -> log
   weight, default `reward-weight`), `:retain` (default 0), `:search-id`
   (to settle again what a previous attempt left pending). Returns
   `{:search/id :search/policy :search/selected run-id-or-nil :search/members
   [{:run/id :world :search/weight :search/decision :search/status
   :search/error}]}`."
  [room members {:keys [policy weigh search-id] :or {policy :automatic} :as opts}]
  (validate-options! opts)
  (let [weigh (or weigh reward-weight)
        weights (mapv #(double (weigh %)) members)
        decisions (decide weights (assoc opts :policy policy))
        search-id (or search-id (random-uuid))
        base (fn [i] {:search/id search-id :search/policy policy
                      :search/members (count members)
                      :search/weight (nth weights i)})]
    (binding [ec/*execution-context* (:ctx room)]
      (preflight! room members decisions search-id)
      (let [prior (mapv #(run/search-record (durable-run room (run-id-of %))) members)
            settled? (fn [i] (let [p (nth prior i)]
                               (and (= search-id (:search/id p)) (= :settled (:search/status p)))))
            todo (remove settled? (range (count members)))]
        ;; 1. intended decisions, durable before any world is settled
        (doseq [i todo]
          (run/record-search! room (run-id-of (nth members i))
                              (assoc (base i) :search/decision (nth decisions i)
                                     :search/status :pending)))
        ;; 2. settle, the merge first; 3. record each outcome
        (doseq [i (sort-by #(if (= :merge (nth decisions %)) 0 1) todo)]
          (let [{:keys [decision ok? error]} (settle! (nth members i) (nth decisions i))]
            (run/record-search! room (run-id-of (nth members i))
                                (cond-> (assoc (base i) :search/decision decision
                                               :search/status (if ok? :settled :failed))
                                  error (assoc :search/error error)))))
        (let [rows (mapv (fn [i member]
                           (-> (run/search-record (durable-run room (run-id-of member)))
                               (assoc :run/id (run-id-of member) :world (:world member))))
                         (range) members)]
          {:search/id search-id
           :search/policy policy
           :search/selected (some #(when (and (= :merge (:search/decision %))
                                              (= :settled (:search/status %)))
                                     (:run/id %))
                                  rows)
           :search/members rows})))))

(defn search!
  "Attempt `task` in `room` as a search: `workflow/attempt!` (its options:
   `:task`, `:attempts`, `:models`, `:evaluator`, `:parallelism`,
   `:parent-run`, `:effects`, default `speculative-effects`) with its members
   held for the search, then `select!` (`:policy`, `:weigh`, `:retain`).
   Options are checked before anything runs. Call at a boundary, not inside a
   Spin."
  [room opts]
  (let [search-opts (select-keys opts [:policy :weigh :retain :search-id])]
    (validate-options! search-opts)
    (select! room
             (workflow/attempt! room (-> opts
                                         (dissoc :policy :weigh :retain :search-id)
                                         (update :effects #(or % speculative-effects))
                                         (assoc :hold? true)))
             search-opts)))
