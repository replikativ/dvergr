(ns dvergr.resource.projection
  "Pure spend attribution over an immutable classified resource-ledger view.
   Funding follows grants, stops at Run wallets, and never follows refunds.
   Non-Run pooling is rejected rather than assigned an invented cost split."
  (:require [clojure.set :as set]))

(defn- plus [a b]
  (into {} (remove (comp zero? val)) (merge-with + a b)))

(defn- reach [edges starts stop?]
  (loop [seen #{} frontier (set starts)]
    (if (empty? frontier)
      seen
      (let [seen' (into seen frontier)
            next (into #{} (comp (remove stop?) (mapcat edges) (remove seen')) frontier)]
        (recur seen' next)))))

(defn- origins [accounts transfers]
  (let [seeds (into {}
                    (keep (fn [[id {:keys [purpose kind]}]]
                            (cond
                              (= :run purpose) [id #{id}]
                              (or (= :room purpose) (#{:source :sink} kind)) [id #{::outside}])))
                    accounts)
        seeds (reduce (fn [out {:keys [kind destination]}]
                        (if (and (= :mint kind)
                                 (not= :run (:purpose (accounts destination))))
                          (update out destination (fnil conj #{}) ::outside)
                          out)) seeds transfers)]
    (loop [known seeds]
      (let [next (reduce (fn [out {:keys [kind source destination]}]
                           (if (and (= :grant kind)
                                    (not (#{:run :room} (:purpose (accounts destination)))))
                             (update out destination (fnil set/union #{}) (get out source #{}))
                             out)) known transfers)]
        (if (= next known) known (recur next))))))

(defn spend-by-run
  "Conservative spend: actual sink consumption plus outstanding reservation
   and hold balances, excluding available world/escrow balances. Supports the
   Dvergr funding topology; ambiguous pools, unclassified wallets and unrelated
   refunds into non-Run wallets are explicit errors, never silent undercounts."
  [{:keys [accounts transfers]} run-ids]
  (doseq [id run-ids
          :let [account (accounts id)]
          :when (and account (not= :run (:purpose account)))]
    (throw (ex-info "A Run wallet lacks its immutable Run classification"
                    {:type ::unclassified-wallet :wallet id :purpose (:purpose account)})))
  (let [grants (filter #(= :grant (:kind %)) transfers)
        children (reduce (fn [m {:keys [source destination]}]
                           (update m source (fnil conj #{}) destination)) {} grants)
        parents (reduce (fn [m {:keys [source destination]}]
                          (update m destination (fnil conj #{}) source)) {} grants)
        roots (set (filter #(= :run (:purpose (accounts %))) run-ids))
        ;; A child Run is another attribution boundary, not its parent's spend.
        relevant (into roots
                       (mapcat (fn [root]
                                 (disj (reach children #{root}
                                              #(and (not= root %) (= :run (:purpose (accounts %)))))
                                       root))) roots)
        relevant (set relevant)
        relevant (set (filter #(or (roots %) (not= :run (:purpose (accounts %)))) relevant))
        ancestors (reach parents relevant #(= :run (:purpose (accounts %))))
        funding-origins (origins accounts transfers)]
    (doseq [id (set/union relevant ancestors)
            :let [{:keys [kind purpose run-id]} (accounts id)]]
      (when (and (= :wallet kind)
                 (or (not (#{:room :run :world :escrow :reservation :hold} purpose))
                     (and (= :run purpose) (not= id run-id))))
        (throw (ex-info "Resource spend needs classified wallet provenance"
                        {:type ::unclassified-wallet :wallet id :purpose purpose}))))
    (doseq [{:keys [source destination]} grants
            :when (and (relevant source) (= :room (:purpose (accounts destination))))]
      (throw (ex-info "A grant into a Room wallet is not a supported funding edge"
                      {:type ::unsupported-funding :source source :destination destination})))
    (doseq [{:keys [kind source destination]} transfers
            :when (and (= :return kind) (or (relevant source) (relevant destination))
                       (not= :run (:purpose (accounts destination)))
                       (not (contains? (reach parents (get parents source) (constantly false)) destination)))]
      (throw (ex-info "A refund must return along its funding ancestry"
                      {:type ::unsupported-refund :source source :destination destination})))
    (doseq [id relevant
            :let [owners (get funding-origins id #{})]
            :when (or (not= 1 (count owners)) (contains? owners ::outside))]
      (throw (ex-info "A resource wallet has ambiguous funding provenance"
                      {:type ::ambiguous-funding :wallet id :origins owners})))
    (let [attribute (fn [out id resources]
                      (if (and (relevant id) (seq resources))
                        (update out (first (funding-origins id)) (fnil plus {}) resources)
                        out))
          consumed (reduce (fn [out {:keys [kind source resources]}]
                             (if (= :consume kind) (attribute out source resources) out))
                           {} transfers)]
      (reduce (fn [out id]
                (let [{:keys [purpose balance]} (accounts id)]
                  (if (#{:reservation :hold} purpose) (attribute out id balance) out)))
              consumed relevant))))
