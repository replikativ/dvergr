(ns dvergr.agent.budget
  "Budgets as a tree (doc/design/runs-as-worlds.md): a parent declares how its
   grant splits over the roles of its children, and spend sums by role over
   any subtree of Runs.

   A Run's own spend is read from the ledger, not from metrics, and follows
   the ledger's transfers, not `:run/parent`: what its wallet was granted and
   got back from the Runs it funded, less what it granted to Runs (children,
   or the successor that resumes it), less what it holds or returned. That
   counts every other way its wallet was drawn on: model spend, dispatch
   reservations and their holds (`dvergr.resource`), any consumption. All
   ledger reads come from one snapshot of it, so a transfer is never seen
   half done."
  (:require [dvergr.agent.run :as run]
            [dvergr.resource :as resource]
            [dvergr.room.store :as store]
            [kontor.resource :as kontor]))

(defn- ledger
  "Readers of one snapshot of `room`'s ledger: `:receipt` and `:balance`. A
   store over a Datahike connection is read at one database value; another
   store is read as it is."
  [room]
  (let [st (:store room)]
    (if-let [conn (:conn st)]
      (let [db @conn
            snapshot (reify clojure.lang.IDeref (deref [_] db))]
        {:receipt #(kontor/receipt snapshot %)
         :balance #(kontor/balance snapshot %)})
      {:receipt #(store/-resource-receipt st %)
       :balance #(store/-resource-balance st %)})))

(defn- holding
  "What `wallet` holds; a wallet never opened (a Run granted nothing) holds
   nothing. Any other failure to read it propagates."
  [{:keys [balance]} wallet]
  (try (balance (kontor/account-ref wallet))
       (catch clojure.lang.ExceptionInfo e
         (if (= ::kontor/account-not-found (:type (ex-data e)))
           {}
           (throw e)))))

(defn- plus [& vectors]
  (into {} (remove (comp zero? val)) (apply merge-with + {} vectors)))

(defn- minus [a b]
  (plus a (update-vals b -)))

(defn- all-runs
  "Every Run of `room`, read after the ledger snapshot: a Run admitted since
   has no transfers in it and adds nothing."
  [room]
  (run/runs room {:limit Integer/MAX_VALUE}))

(defn- own-spends
  "{run-id resource-vector}: what each Run of `runs` consumed itself, from
   the transfers in `book`."
  [book runs]
  (let [{:keys [receipt]} book
        edges (for [{id :run/id} runs]
                {:id id
                 :wallet (resource/run-wallet-id id)
                 :in (receipt (resource/allocation-id id))
                 :out (receipt (resource/return-id id))})
        granted-from (apply merge-with plus {}
                            (keep (fn [{:keys [in]}] (when in {(:source in) (:resources in)})) edges))
        returned-to (apply merge-with plus {}
                           (keep (fn [{:keys [out]}] (when out {(:destination out) (:resources out)})) edges))]
    (into {}
          (map (fn [{:keys [id wallet in out]}]
                 [id (minus (plus (:resources in) (get returned-to wallet))
                            (plus (get granted-from wallet)
                                  (if out (:resources out) (holding book wallet))))]))
          edges)))

(defn- subtree [runs root-id]
  (let [by-parent (group-by :run/parent runs)]
    (loop [selected [] frontier (filter #(= root-id (:run/id %)) runs)]
      (if (empty? frontier)
        selected
        (recur (into selected frontier)
               (mapcat #(by-parent (:run/id %)) frontier))))))

(defn spend-of
  "What `run-id` itself consumed from its wallet (see the namespace doc)."
  [room run-id]
  (let [book (ledger room)
        runs (all-runs room)]
    (get (own-spends book runs) run-id {})))

(defn spend-by-role
  "The spend of every Run in the subtree under `run-id`, itself included,
   summed by the role each Run plays (`hire!`'s `:role`, `:run/role`); a Run
   without one is summed under `:unassigned`. Returns {role resource-vector}."
  [room run-id]
  (let [book (ledger room)
        runs (all-runs room)
        spends (own-spends book runs)]
    (->> (subtree runs run-id)
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
  (when (> (reduce + (vals shares)) 1)
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
