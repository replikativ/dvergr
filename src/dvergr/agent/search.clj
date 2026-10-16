(ns dvergr.agent.search
  "Alternative composition (doc/design/runs-as-worlds.md): one task attempted
   by several Runs, each in its own world, weighed, and joined by a policy.

   Overlays (parallel work that is all meant to land) organize concurrent
   tasks; a search chooses among alternatives of one task, of which at most a
   few land. Its members are Run worlds from `dvergr.agent.workflow`, each a
   certified Attempt whose world is kept for review.

   A member's weight is a log weight, as foerster's `observe` and `factor`
   accumulate it: by default the log of its Attempt's reward (a verifier's 0
   makes it impossible), or any `:weigh` function of the member, such as a
   judge's log score. The policy joins the members:

   - `:automatic` merges the heaviest possible member into the room and
     discards the rest;
   - `:review` keeps the heaviest possible member for a person and discards
     the rest;
   - `:keep-all` keeps every member for a person.

   `:retain k` keeps the k next-heaviest possible members for inspection
   (their worlds stay in review) instead of discarding them. A world handed to
   review leaves the search: a person settles it, through the room's merge and
   discard. Every member's Run records the search, its weight and the
   decision on its world (`dvergr.agent.run/record-search!`), so a search is
   auditable after a restart; settling worlds after a restart is not
   (doc/design/runs-as-worlds.md, Decisions 6)."
  (:require [dvergr.agent.run :as run]
            [dvergr.agent.workflow :as workflow]
            [dvergr.room.registry :as rreg]
            [dvergr.rooms.forks :as forks]
            [org.replikativ.spindel.engine.core :as ec]))

(def policies #{:automatic :review :keep-all})

(defn reward-weight
  "The default weight of a member: the log of its Attempt's reward, read as
   the probability that it did the task. A reward of 0 is impossible (-Inf)."
  [{:keys [attempt]}]
  (let [r (get-in attempt [:attempt/receipt :attempt/reward])]
    (if (and (number? r) (pos? r))
      (Math/log (double r))
      Double/NEGATIVE_INFINITY)))

(defn- possible? [w] (and (number? w) (> w Double/NEGATIVE_INFINITY) (not (Double/isNaN w))))

(defn decide
  "The decision on each member's world, in member order, from their log
   `weights`: `:merge`, `:review` or `:discard` (see the namespace). Ties go
   to the earlier member. Pure."
  [weights {:keys [policy retain] :or {policy :automatic retain 0}}]
  (when-not (policies policy)
    (throw (ex-info (str "Unknown search policy " policy) {:type ::policy :policy policy})))
  (when-not (and (integer? retain) (<= 0 retain))
    (throw (ex-info ":retain is a non-negative integer" {:type ::retain :retain retain})))
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

(defn- settle! [{:keys [world review]} decision]
  (let [fork (some-> world rreg/lookup)]
    (case decision
      :merge (if fork
               (forks/merge! fork :expect-state (:state review))
               {:ok? false :error "the member's world is gone"})
      :discard (if fork (forks/discard! fork :search-unselected) {:ok? true})
      :review {:ok? true})))

(defn select!
  "Join the `members` of one search (the rows `workflow/attempt!` returns) in
   `room`: weigh them, decide, settle each world, and record the search on
   every member's Run. A merge that fails (the world changed since it was
   reviewed, a conflict) leaves the world in review. Call at a boundary, not
   inside a Spin.

   Options: `:policy` (`:automatic`, default), `:weigh` (member -> log
   weight, default `reward-weight`), `:retain` (default 0), `:search-id`.
   Returns `{:search/id :search/policy :search/selected run-id-or-nil
   :search/members [{:run/id :world :search/weight :search/decision
   :search/outcome}]}`."
  [room members {:keys [policy weigh search-id] :or {policy :automatic} :as opts}]
  (let [weigh (or weigh reward-weight)
        weights (mapv #(double (weigh %)) members)
        decisions (decide weights (assoc opts :policy policy))
        search-id (or search-id (random-uuid))
        ;; the merge first: should it fail, the member stays in review
        order (sort-by #(if (= :merge (nth decisions %)) 0 1) (range (count members)))
        settled (binding [ec/*execution-context* (:ctx room)]
                  (reduce (fn [acc i]
                            (let [member (nth members i)
                                  decision (nth decisions i)
                                  outcome (settle! member decision)
                                  decision (if (and (= :merge decision) (not (:ok? outcome)))
                                             :review
                                             decision)
                                  record {:search/id search-id
                                          :search/policy policy
                                          :search/members (count members)
                                          :search/weight (nth weights i)
                                          :search/decision decision}]
                              (when-let [run-id (run-id-of member)]
                                (run/record-search! room run-id record))
                              (assoc acc i (assoc record
                                                  :run/id (run-id-of member)
                                                  :world (:world member)
                                                  :search/outcome outcome))))
                          {} order))
        members' (mapv settled (range (count members)))]
    {:search/id search-id
     :search/policy policy
     :search/selected (some #(when (= :merge (:search/decision %)) (:run/id %)) members')
     :search/members members'}))

(defn search!
  "Attempt `task` in `room` as a search: `workflow/attempt!` (its options:
   `:task`, `:attempts`, `:models`, `:evaluator`, `:parallelism`,
   `:parent-run`), then `select!` (`:policy`, `:weigh`, `:retain`). Call at
   a boundary, not inside a Spin."
  [room opts]
  (select! room
           (workflow/attempt! room (dissoc opts :policy :weigh :retain :search-id))
           (select-keys opts [:policy :weigh :retain :search-id])))
