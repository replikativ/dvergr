(ns dvergr.agent.budget
  "Budgets as a tree (doc/design/runs-as-worlds.md): a parent declares how its
   grant splits over the roles of its children, and spend sums by role over
   any subtree of Runs.

   A Run's own spend follows funding on the ledger, not `:run/parent` or
   model metrics. It counts actual consumption, plus outstanding dispatch
   reservations and holds, including those inside its funded worlds. Available
   world and escrow balances are not spent. Each child Run starts a separate
   attribution boundary. All resource facts come from one immutable snapshot."
  (:require [dvergr.agent.run :as run]
            [dvergr.room.store :as store]
            [dvergr.resource.projection :as projection]))

(defn- ledger
  "One immutable view through the store boundary, never several live reads."
  [room]
  (let [st (:store room)]
    (when-not (satisfies? store/PResourceReadView st)
      (throw (ex-info "Resource store has no immutable attribution view"
                      {:type ::resource-read-view-unavailable :room-id (:id room)})))
    (store/-resource-read-view st)))

(defn- plus [& vectors]
  (into {} (remove (comp zero? val)) (apply merge-with + {} vectors)))

(defn- all-runs
  "Every Run of `room`, read after the ledger snapshot: a Run admitted since
   has no transfers in it and adds nothing."
  [room]
  (run/runs room {:limit Integer/MAX_VALUE}))

(defn- own-spends
  "Conservative own-spend for each Run, excluding available delegated funds."
  [book runs]
  (projection/spend-by-run book (map :run/id runs)))

(defn- subtree [runs root-id]
  (let [by-parent (group-by :run/parent runs)]
    (loop [selected [] frontier (filter #(= root-id (:run/id %)) runs)]
      (if (empty? frontier)
        selected
        (recur (into selected frontier)
               (mapcat #(by-parent (:run/id %)) frontier))))))

(defn spend-of
  "Own consumption plus outstanding reservations/holds (see namespace doc)."
  [room run-id]
  (let [book (ledger room)]
    (if (run/run room run-id)
      (get (projection/spend-by-run book [run-id]) run-id {})
      {})))

(defn spend-by-role
  "The spend of every Run in the subtree under `run-id`, itself included,
   summed by the role each Run plays (`hire!`'s `:role`, `:run/role`); a Run
   without one is summed under `:unassigned`. Returns {role resource-vector}."
  [room run-id]
  (let [book (ledger room)
        runs (subtree (all-runs room) run-id)
        spends (own-spends book runs)]
    (->> runs
         (map (fn [r] {(or (:run/role r) :unassigned) (get spends (:run/id r) {})}))
         (apply merge-with plus {})
         (into {} (remove (comp empty? val))))))

(defn split
  "Divide `resources` (a resource vector) among roles by `shares` ({role
   fraction}, each positive, together at most 1). Each amount is rounded
   down to a whole unit; what rounding and unshared fractions leave stays
   with the parent. A role whose share rounds to nothing in every coordinate
   is left out. Returns {role resource-vector}, each a grant for `hire!`."
  [resources shares]
  (when-not (and (map? shares) (seq shares)
                 (every? (fn [[role share]] (and (keyword? role) (number? share) (pos? share)))
                         shares))
    (throw (ex-info "split shares must map roles (keywords) to positive fractions"
                    {:type ::invalid-shares :shares shares})))
  (when (> (reduce + (map rationalize (vals shares))) 1)
    (throw (ex-info "split shares must sum to at most 1"
                    {:type ::shares-exceed-grant :shares shares})))
  (into {}
        (keep (fn [[role share]]
                (let [grant (into {}
                                  (keep (fn [[coordinate amount]]
                                          (let [x (* (rationalize amount) (rationalize share))
                                                ;; exact: a share of 1/3 has no finite decimal
                                                n (bigdec (if (ratio? x)
                                                            (quot (numerator x) (denominator x))
                                                            (bigint x)))]
                                            (when (pos? n) [coordinate n]))))
                                  resources)]
                  (when (seq grant) [role grant]))))
        shares))
