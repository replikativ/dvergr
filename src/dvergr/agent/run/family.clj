(ns dvergr.agent.run.family
  "Pure child-admission transitions for the existing native Run owner.
   No registry, worker, ledger, or forkable state is created here.")

(defn empty-family [] {:version 1 :sealed? false :leases {}})

(defn valid? [value]
  (and (map? value) (= 1 (:version value)) (boolean? (:sealed? value)) (map? (:leases value))))

(defn- conflict! [token reason]
  (throw (ex-info "Child admission no longer owns this family transition"
                  {:type ::conflict :lease token :reason reason})))

(defn lease [family token]
  (when-not (valid? family) (conflict! token :unknown-coverage))
  (or (get-in family [:leases token]) (conflict! token :missing)))

(defn reserve [family token]
  (when-not (valid? family) (conflict! token :unknown-coverage))
  (when (:sealed? family) (conflict! token :sealed))
  (when (contains? (:leases family) token) (conflict! token :duplicate))
  (assoc-in family [:leases token]
            {:phase :reserved :callback-exited? false :child-returned? false
             :preparation-safe? true :publication-acknowledged? true :cancelled? false}))

(defn seal [family]
  (when-not (valid? family) (conflict! nil :unknown-coverage))
  (assoc family :sealed? true))

(defn claim [family token child-id]
  (when-not (and (uuid? child-id) (= :reserved (:phase (lease family token)))
                 (not (:callback-exited? (lease family token))))
    (conflict! token :consumed))
  ;; A reservation remains owned when the parent seals in the meantime.
  (update-in family [:leases token] assoc :phase :preparing :child-id child-id))

(defn preparation-entered [family token]
  (when-not (= :preparing (:phase (lease family token))) (conflict! token :phase))
  (assoc-in family [:leases token :preparation-safe?] false))

(defn preparation-cleaned [family token]
  (when-not (= :preparing (:phase (lease family token))) (conflict! token :phase))
  (assoc-in family [:leases token :preparation-safe?] true))

(defn starting [family token child-id]
  (let [current (lease family token)]
    (when-not (and (= :preparing (:phase current)) (= child-id (:child-id current)))
      (conflict! token :child))
    (assoc-in family [:leases token :phase] :starting)))

(defn admission-fenced [family token child-id]
  (let [current (lease family token)]
    (when-not (and (= :starting (:phase current)) (= child-id (:child-id current)))
      (conflict! token :child))
    ;; A failed, unexecuted durable identity now rejects late initial writes.
    ;; World disposal remains an independent required acknowledgement.
    (update-in family [:leases token] assoc :phase :preparing :admission-fenced? true)))

(defn admitted [family token child-id]
  (let [current (lease family token)]
    (when-not (and (= :starting (:phase current)) (= child-id (:child-id current)))
      (conflict! token :child))
    (update-in family [:leases token] assoc :phase :admitted :preparation-safe? true)))

(defn callback-exited [family token]
  (lease family token)
  (assoc-in family [:leases token :callback-exited?] true))

(defn child-returned [family token child-id]
  (let [current (lease family token)]
    (when-not (and (= :admitted (:phase current)) (= child-id (:child-id current)))
      (conflict! token :child))
    (assoc-in family [:leases token :child-returned?] true)))

(defn require-publication [family token]
  (when (:callback-exited? (lease family token)) (conflict! token :callback-exited))
  (assoc-in family [:leases token :publication-acknowledged?] false))

(defn publication-acknowledged [family token]
  (lease family token)
  (assoc-in family [:leases token :publication-acknowledged?] true))

(defn cancel [family]
  (-> family seal
      (update :leases #(into {} (map (fn [[token value]] [token (assoc value :cancelled? true)])) %))))

(defn releasable? [value]
  (and (not (:recovery-running? value)) (:callback-exited? value) (:publication-acknowledged? value)
       (case (:phase value)
         :reserved true
         :preparing (:preparation-safe? value)
         :admitted (:child-returned? value)
         false)))

(defn releasing [family token]
  (when-not (releasable? (lease family token)) (conflict! token :not-returned))
  (assoc-in family [:leases token :phase] :releasing))

(defn released [family token]
  (when-not (= :releasing (:phase (lease family token))) (conflict! token :phase))
  (update family :leases dissoc token))

(defn quiescent? [family]
  (and (valid? family) (:sealed? family) (empty? (:leases family))))
