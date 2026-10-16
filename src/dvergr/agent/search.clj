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

(defn- settle!
  "Settle a held member's world by `decision`. Returns `{:decision actual
   :ok? bool :error message}`. A merge refused before the world was merged
   leaves it in review; one that merged but failed afterwards (its Run's
   settlement record) still merged."
  [room {:keys [world review] :as member} decision]
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
                   (cond
                     (:ok? merged) {:decision :merge :ok? true}
                     ;; merged, then a later step failed: the world is gone
                     (or (nil? (rreg/lookup world))
                         (= :merged (:run/settlement-status (durable-run room (run-id-of member)))))
                     {:decision :merge :ok? true :error (:error merged)}
                     ;; refused before merging: it waits in review for a person
                     :else {:decision :review :ok? true :error (:error merged)})))))))

(defn- reconciled
  "A pending member's outcome when its world was settled but the outcome not
   recorded (a failed write, a crash): read from its Run's durable settlement
   and its world. nil while the member is still held."
  [room member]
  (let [status (:run/settlement-status (durable-run room (run-id-of member)))
        fork (rreg/lookup (:world member))]
    (cond
      (= :merged status) :merge
      (= :discarded status) :discard
      (and (= :review status) fork (not (forks/deferred? fork))) :review
      :else nil)))

(defn select!
  "Join the `members` of one search (the rows of `workflow/attempt!` with
   `:hold? true` under `speculative` effects) in `room`: weigh them, decide,
   claim every member with its intended decision, settle each world, and
   record each outcome. Call at a boundary, not inside a Spin.

   A Run is a member of one search: claiming it records the search's id, and
   another search's claim is refused (in Run order, so a losing search claims
   nothing). Again with the same `:search-id`, `select!` keeps the recorded
   weights and decisions, reconciles members whose world was settled but
   whose outcome was not recorded, and settles the rest.

   Options: `:policy` (`:automatic`, default), `:weigh` (member -> log
   weight, default `reward-weight`), `:retain` (default 0), `:search-id`.
   Returns `{:search/id :search/policy :search/selected run-id-or-nil
   :search/members [{:run/id :world :search/weight :search/decision
   :search/status :search/error}]}`."
  [room members {:keys [policy weigh search-id] :or {policy :automatic} :as opts}]
  (validate-options! opts)
  (binding [ec/*execution-context* (:ctx room)]
    (let [search-id (or search-id (random-uuid))
          prior (mapv #(some-> (durable-run room (run-id-of %)) run/search-record) members)
          retry? (some #(= search-id (:search/id %)) prior)
          _ (when (and retry? (not (every? #(and (= search-id (:search/id %))
                                                 (= policy (:search/policy %))
                                                 (= (count members) (:search/members %)))
                                           prior)))
              (throw (ex-info "A retried search must have the same members and policy"
                              {:type ::incompatible-retry :search/id search-id})))
          ;; a retry keeps what was decided: a judge asked again may differ
          weights (if retry? (mapv :search/weight prior) (mapv #(double ((or weigh reward-weight) %)) members))
          decisions (if retry? (mapv :search/decision prior) (decide weights (assoc opts :policy policy)))
          base (fn [i] {:search/id search-id :search/policy policy
                        :search/members (count members)
                        :search/weight (nth weights i)})
          record! (fn [i decision status & [error]]
                    (run/record-search! room (run-id-of (nth members i))
                                        (cond-> (assoc (base i) :search/decision decision
                                                       :search/status status)
                                          error (assoc :search/error error))))
          ;; members whose world was settled but whose outcome was not recorded
          _ (doseq [i (range (count members))
                    :when (= :pending (:search/status (nth prior i)))
                    :let [outcome (reconciled room (nth members i))]
                    :when outcome]
              (record! i outcome :settled))
          now (mapv #(some-> (durable-run room (run-id-of %)) run/search-record) members)
          todo (vec (remove #(= :settled (:search/status (nth now %))) (range (count members))))]
      (preflight! room members decisions todo)
      ;; 1. claim each member with its intended decision, durably, in Run order
      (doseq [i (sort-by #(str (run-id-of (nth members %))) todo)]
        (record! i (nth decisions i) :pending))
      ;; 2. settle, the merge first; 3. record each outcome
      (doseq [i (sort-by #(if (= :merge (nth decisions %)) 0 1) todo)]
        (let [{:keys [decision ok? error]} (settle! room (nth members i) (nth decisions i))]
          (record! i decision (if ok? :settled :failed) error)))
      (let [rows (mapv (fn [member]
                         (-> (run/search-record (durable-run room (run-id-of member)))
                             (assoc :run/id (run-id-of member) :world (:world member))))
                       members)]
        {:search/id search-id
         :search/policy policy
         :search/selected (some #(when (and (= :merge (:search/decision %))
                                            (= :settled (:search/status %)))
                                   (:run/id %))
                                rows)
         :search/members rows}))))

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
