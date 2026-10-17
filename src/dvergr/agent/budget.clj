(ns dvergr.agent.budget
  "Budgets as a tree (doc/design/runs-as-worlds.md): a parent declares how its
   grant splits over the roles of its children, and spend sums by role over
   any subtree of Runs.

   A Run's own spend is read from the ledger, not from metrics: what it was
   granted, less what it holds or returned, less what its children took and
   did not return. It counts every way a wallet is drawn on: model spend,
   dispatch reservations and their holds (`dvergr.resource`), and any other
   consumption."
  (:require [dvergr.agent.run :as run]
            [dvergr.resource :as resource]
            [dvergr.room.store :as store]))

(defn- receipt-resources [room transfer-id]
  (or (:resources (store/-resource-receipt (:store room) transfer-id)) {}))

(defn- minus [a b]
  (into {}
        (keep (fn [[k v]] (let [d (- v (get b k 0M))] (when-not (zero? d) [k d]))))
        (merge-with (fn [x _] x) a (zipmap (keys b) (repeat 0M)))))

(defn- sum [vectors]
  (apply merge-with + {} vectors))

(defn- granted [room run-id]
  (receipt-resources room (resource/allocation-id run-id)))

(defn- left
  "What `run-id` still holds, or what it returned when it ended."
  [room run-id]
  (let [returned (store/-resource-receipt (:store room) (resource/return-id run-id))]
    (if returned
      (:resources returned)
      (try (resource/run-balance room run-id)
           (catch clojure.lang.ExceptionInfo _ {})))))

(defn- taken
  "What a child took from its parent's wallet and has not returned."
  [room run-id]
  (minus (granted room run-id)
         (receipt-resources room (resource/return-id run-id))))

(defn- children [runs run-id]
  (filter #(= run-id (:run/parent %)) runs))

(defn spend-of
  "What `run-id` itself consumed from its wallet: its grant, less what it
   holds or returned, less what its children took and did not return."
  ([room run-id]
   (spend-of room (run/runs room {:root-run-id run-id}) run-id))
  ([room runs run-id]
   (minus (minus (granted room run-id) (left room run-id))
          (sum (map #(taken room (:run/id %)) (children runs run-id))))))

(defn spend-by-role
  "The spend of every Run in the subtree under `run-id`, itself included,
   summed by the role each Run plays (`hire!`'s `:role`, `:run/role`); a Run
   without one is summed under `:unassigned`. Returns {role resource-vector}."
  [room run-id]
  (let [runs (run/runs room {:root-run-id run-id})]
    (->> runs
         (map (fn [r] {(or (:run/role r) :unassigned) (spend-of room runs (:run/id r))}))
         (apply merge-with (fn [a b] (sum [a b])))
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
