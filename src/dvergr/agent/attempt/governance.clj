(ns dvergr.agent.attempt.governance
  "Mandatory Datahike writer predicate for certified evaluation projections.

   This composes with an existing store predicate (notably Kontor) and validates
   fully-resolved datoms. It prevents raw transactions, imports, and remote
   writers from mutating certification after the trusted transaction function
   creates it."
  (:require [datahike.api :as d]))

(def ^:private attempt-required
  #{:attempt/id :attempt/chat :attempt/run :attempt/content-id
    :attempt/payload-blob :attempt/payload-codec :attempt/environment-id
    :attempt/environment-version :attempt/environment-content-id
    :attempt/verifier-id :attempt/verifier-version :attempt/provider
    :attempt/model :attempt/status :attempt/started-at :attempt/elapsed-ms
    :attempt/certified-at :attempt/reward :attempt/agent-def-hash
    :attempt/program-kind :attempt/interpreter-version
    :attempt/evidence-content-id :attempt/evidence-runs
    :attempt/settlement-intent :attempt/checks})

(def ^:private scorecard-required
  #{:scorecard/id :scorecard/chat :scorecard/payload-blob
    :scorecard/payload-codec :scorecard/experiment-id
    :scorecard/experiment-version :scorecard/experiment-content-id
    :scorecard/dataset-id :scorecard/dataset-version
    :scorecard/dataset-content-id :scorecard/stored-at
    :scorecard/attempts :scorecard/summaries})

(def ^:private summary-required
  #{:scorecard.summary/id :scorecard.summary/candidate-id
    :scorecard.summary/candidate-content-id
    :scorecard.summary/attempt-count :scorecard.summary/passed-count
    :scorecard.summary/reward-sum :scorecard.summary/reward-mean})

(defn- protected-ident? [ident]
  (and (keyword? ident)
       (contains? #{"attempt" "attempt.check"
                    "scorecard" "scorecard.summary"}
                  (namespace ident))))

(defn- datom-ident [db datom]
  (let [attr (nth datom 1)]
    (if (keyword? attr)
      attr
      (:db/ident (d/entity db attr)))))

(defn- touched [report]
  (let [db (:db-after report)]
    (filter #(protected-ident? (datom-ident db %)) (:tx-data report))))

(def ^:private entity-pattern
  '[*
    {:attempt/chat [:db/id :chat/id]}
    {:attempt/run [* {:run/chat [:db/id :chat/id]}]}
    {:attempt/evidence-runs [* {:run/chat [:db/id :chat/id]}]}
    {:attempt/evidence-messages [* {:message/chat [:db/id :chat/id]}]}
    {:attempt/checks [*]}
    {:scorecard/chat [:db/id :chat/id]}
    {:scorecard/attempts [* {:attempt/chat [:db/id :chat/id]}]}
    {:scorecard/summaries [*]}])

(defn- entity-map [db eid]
  (d/pull db entity-pattern eid))

(defn- attempt? [entity] (uuid? (:attempt/id entity)))
(defn- check? [entity] (uuid? (:attempt.check/id entity)))
(defn- scorecard? [entity] (uuid? (:scorecard/id entity)))
(defn- summary? [entity] (uuid? (:scorecard.summary/id entity)))

(defn- same-chat? [attempt run]
  (= (:db/id (:attempt/chat attempt)) (:db/id (:run/chat run))))

(defn- same-scorecard-chat? [scorecard attempt]
  (= (:db/id (:scorecard/chat scorecard))
     (:db/id (:attempt/chat attempt))))

(defn- validate-new-attempt! [db entity]
  (let [missing (remove #(contains? entity %) attempt-required)
        run (:attempt/run entity)]
    (when (seq missing)
      (throw (ex-info "Certified Attempt row is incomplete"
                      {:type ::incomplete-attempt :missing (set missing)})))
    (when-not (= (:attempt/id entity) (:run/id run))
      (throw (ex-info "Certified Attempt identity differs from its Run"
                      {:type ::attempt-run-mismatch
                       :attempt/id (:attempt/id entity)
                       :run/id (:run/id run)})))
    (when-not (and (contains? #{:completed :failed :cancelled}
                              (:run/status run))
                   (= (:attempt/status entity) (:run/status run))
                   (same-chat? entity run))
      (throw (ex-info "Certified Attempt requires a same-Room terminal Run"
                      {:type ::invalid-attempt-run
                       :attempt/id (:attempt/id entity)})))
    (doseq [[attempt-key run-key]
            [[:attempt/agent-def-hash :run/agent-def-hash]
             [:attempt/program-kind :run/program-kind]
             [:attempt/interpreter-version :run/interpreter-version]]]
      (when-not (= (get entity attempt-key) (get run run-key))
        (throw (ex-info "Certified Attempt provenance differs from its Run"
                        {:type ::attempt-run-provenance-mismatch
                         :attempt/key attempt-key}))))
    (doseq [evidence-run (:attempt/evidence-runs entity)]
      (when-not (and evidence-run (same-chat? entity evidence-run))
        (throw (ex-info "Certified Attempt has cross-Room Run evidence"
                        {:type ::invalid-evidence-run
                         :attempt/id (:attempt/id entity)}))))
    (doseq [message (:attempt/evidence-messages entity)]
      (when-not (= (:db/id (:attempt/chat entity))
                   (:db/id (:message/chat message)))
        (throw (ex-info "Certified Attempt has cross-Room message evidence"
                        {:type ::invalid-evidence-message
                         :attempt/id (:attempt/id entity)}))))
    (doseq [check (:attempt/checks entity)]
      (when-not (and (keyword? (:attempt.check/key check))
                     (boolean? (:attempt.check/passed? check)))
        (throw (ex-info "Certified Attempt has malformed checks"
                        {:type ::invalid-check
                         :attempt/id (:attempt/id entity)}))))
    db))

(defn- validate-new-scorecard! [db entity]
  (let [missing (remove #(contains? entity %) scorecard-required)]
    (when (seq missing)
      (throw (ex-info "Certified Scorecard row is incomplete"
                      {:type ::incomplete-scorecard :missing (set missing)})))
    (when-not (seq (:scorecard/attempts entity))
      (throw (ex-info "Certified Scorecard requires Attempts"
                      {:type ::scorecard-without-attempts
                       :scorecard/id (:scorecard/id entity)})))
    (doseq [attempt (:scorecard/attempts entity)]
      (when-not (and (attempt? attempt)
                     (same-scorecard-chat? entity attempt))
        (throw (ex-info "Certified Scorecard has a missing or cross-Room Attempt"
                        {:type ::invalid-scorecard-attempt
                         :scorecard/id (:scorecard/id entity)}))))
    (doseq [summary (:scorecard/summaries entity)]
      (let [missing-summary (remove #(contains? summary %) summary-required)]
        (when (seq missing-summary)
          (throw (ex-info "Certified Scorecard summary is incomplete"
                          {:type ::incomplete-scorecard-summary
                           :scorecard/id (:scorecard/id entity)
                           :missing (set missing-summary)})))
        (when-not (and (keyword? (:scorecard.summary/candidate-id summary))
                       (uuid? (:scorecard.summary/candidate-content-id summary))
                       (pos-int? (:scorecard.summary/attempt-count summary))
                       (nat-int? (:scorecard.summary/passed-count summary))
                       (<= (:scorecard.summary/passed-count summary)
                           (:scorecard.summary/attempt-count summary))
                       (Double/isFinite
                        (double (:scorecard.summary/reward-sum summary)))
                       (Double/isFinite
                        (double (:scorecard.summary/reward-mean summary))))
          (throw (ex-info "Certified Scorecard summary is malformed"
                          {:type ::invalid-scorecard-summary
                           :scorecard/id (:scorecard/id entity)})))))
    db))

(defonce ^:private authorized-writes (atom {}))

(defn- store-id [conn-or-db]
  (get-in @conn-or-db [:config :store :id]))

(defn with-authorized-write
  "Invoke `f` with transaction metadata carrying a one-use trusted writer
   capability for `attempt-id`. The token is meaningful only while this call is
   active and is never available through the SCI or store API."
  [conn attempt-id f]
  (let [token (random-uuid)
        key [(store-id conn) token]]
    (swap! authorized-writes assoc key {:kind :attempt :id attempt-id})
    (try
      (f {:evaluation.writer/token token})
      (finally
        (swap! authorized-writes dissoc key)))))

(defn- authorized-attempt-id [report]
  (when-let [token (get-in report [:tx-meta :evaluation.writer/token])]
    (let [authorization
          (get @authorized-writes
               [(get-in report [:db-after :config :store :id]) token])]
      (when (= :attempt (:kind authorization)) (:id authorization)))))

(defn with-authorized-scorecard-write
  "Invoke `f` with a one-use capability for one certified Scorecard identity."
  [conn scorecard-id f]
  (let [token (random-uuid)
        key [(store-id conn) token]]
    (swap! authorized-writes assoc key {:kind :scorecard :id scorecard-id})
    (try
      (f {:evaluation.writer/token token})
      (finally
        (swap! authorized-writes dissoc key)))))

(defn- authorized-scorecard-id [report]
  (when-let [token (get-in report [:tx-meta :evaluation.writer/token])]
    (let [authorization
          (get @authorized-writes
               [(get-in report [:db-after :config :store :id]) token])]
      (when (= :scorecard (:kind authorization)) (:id authorization)))))

(defn validate-report
  "Reject unauthorized creation and mutation of certified evaluation facts."
  [{:keys [db-before db-after tx-data] :as report}]
  (let [changed (vec (touched report))
        eids (into #{} (map #(nth % 0)) changed)
        authorized-id (authorized-attempt-id report)
        authorized-scorecard-id (authorized-scorecard-id report)]
    (doseq [eid eids]
      (let [before (entity-map db-before eid)
            after (entity-map db-after eid)
            before-attempt? (attempt? before)
            after-attempt? (attempt? after)
            before-check? (check? before)
            after-check? (check? after)
            before-scorecard? (scorecard? before)
            after-scorecard? (scorecard? after)
            before-summary? (summary? before)
            after-summary? (summary? after)
            entity-datoms (filter #(= eid (nth % 0)) changed)]
        (cond
          (and (not before-attempt?) after-attempt?)
          (do
            (when-not (= authorized-id (:attempt/id after))
              (throw (ex-info "Certified Attempts require the trusted writer"
                              {:type ::unauthorized-attempt-create
                               :attempt/id (:attempt/id after)})))
            (validate-new-attempt! db-after after))

          (and before-attempt? after-attempt?)
          (when (seq entity-datoms)
            (throw (ex-info "Certified Attempt rows are immutable"
                            {:type ::attempt-mutation
                             :attempt/id (:attempt/id before)})))

          (and before-attempt? (not after-attempt?))
          (when (:chat/id
                 (d/pull db-after [:chat/id]
                         (get-in before [:attempt/chat :db/id])))
            (throw (ex-info "Certified Attempt deletion requires Room deletion"
                            {:type ::attempt-deletion
                             :attempt/id (:attempt/id before)})))

          (and (not before-check?) after-check?)
          (when-not authorized-id
            (throw (ex-info "Attempt checks require the trusted writer"
                            {:type ::unauthorized-check-create})))

          (and before-check? after-check?)
          (when (seq entity-datoms)
            (throw (ex-info "Attempt checks are immutable"
                            {:type ::check-mutation
                             :check/id (:attempt.check/id before)})))

          (and before-check? (not after-check?))
          (when-let [owner
                     (d/q '[:find ?a . :in $ ?check
                            :where [?a :attempt/checks ?check]]
                          db-before eid)]
            (when (attempt? (entity-map db-after owner))
              (throw (ex-info "Attempt check deletion requires Attempt deletion"
                              {:type ::check-deletion
                               :check/id (:attempt.check/id before)}))))

          (and (not before-scorecard?) after-scorecard?)
          (do
            (when-not (= authorized-scorecard-id (:scorecard/id after))
              (throw (ex-info "Certified Scorecards require the trusted writer"
                              {:type ::unauthorized-scorecard-create
                               :scorecard/id (:scorecard/id after)})))
            (validate-new-scorecard! db-after after))

          (and before-scorecard? after-scorecard?)
          (when (seq entity-datoms)
            (throw (ex-info "Certified Scorecard rows are immutable"
                            {:type ::scorecard-mutation
                             :scorecard/id (:scorecard/id before)})))

          (and before-scorecard? (not after-scorecard?))
          (when (:chat/id
                 (d/pull db-after [:chat/id]
                         (get-in before [:scorecard/chat :db/id])))
            (throw (ex-info "Certified Scorecard deletion requires Room deletion"
                            {:type ::scorecard-deletion
                             :scorecard/id (:scorecard/id before)})))

          (and (not before-summary?) after-summary?)
          (when-not authorized-scorecard-id
            (throw (ex-info "Scorecard summaries require the trusted writer"
                            {:type ::unauthorized-scorecard-summary-create})))

          (and before-summary? after-summary?)
          (when (seq entity-datoms)
            (throw (ex-info "Scorecard summaries are immutable"
                            {:type ::scorecard-summary-mutation
                             :summary/id (:scorecard.summary/id before)})))

          (and before-summary? (not after-summary?))
          (when-let [owner
                     (d/q '[:find ?s . :in $ ?summary
                            :where [?s :scorecard/summaries ?summary]]
                          db-before eid)]
            (when (scorecard? (entity-map db-after owner))
              (throw
               (ex-info "Scorecard summary deletion requires Scorecard deletion"
                        {:type ::scorecard-summary-deletion
                         :summary/id (:scorecard.summary/id before)})))))))
    ;; A newly-created check must be owned by an Attempt component edge.
    (doseq [eid eids
            :let [before (entity-map db-before eid)
                  after (entity-map db-after eid)]
            :when (and (not (check? before)) (check? after))]
      (when-not (d/q '[:find ?a . :in $ ?check
                       :where [?a :attempt/checks ?check]] db-after eid)
        (throw (ex-info "Attempt check is not owned by a certified Attempt"
                        {:type ::orphan-check :check/id (:attempt.check/id after)}))))
    ;; A newly-created summary must be a component of the authorized Scorecard.
    (doseq [eid eids
            :let [before (entity-map db-before eid)
                  after (entity-map db-after eid)]
            :when (and (not (summary? before)) (summary? after))]
      (let [owner (d/q '[:find ?s . :in $ ?summary
                         :where [?s :scorecard/summaries ?summary]]
                       db-after eid)
            owner-scorecard (when owner (entity-map db-after owner))]
        (when-not (and (scorecard? owner-scorecard)
                       (= authorized-scorecard-id
                          (:scorecard/id owner-scorecard)))
          (throw
           (ex-info "Scorecard summary is not owned by its certified Scorecard"
                    {:type ::orphan-scorecard-summary
                     :summary/id (:scorecard.summary/id after)})))))
    report))

(defonce ^:private installed (atom {}))

(defn govern!
  "Compose certified Attempt/Scorecard validation with the store predicate."
  [conn]
  (let [store-id (get-in @conn [:config :store :id])
        tx-pred-for (requiring-resolve 'datahike.tx-preds/tx-pred-for)
        register! (requiring-resolve 'datahike.tx-preds/register-tx-pred!)
        current (tx-pred-for store-id)
        entry (get @installed store-id)
        {:keys [base composed]} entry]
    (cond
      (and entry (= current composed)) nil

      ;; An idempotent subsystem install may re-register the same base
      ;; predicate. Restore the existing composite identity rather than nesting
      ;; or allocating a new closure.
      (and entry composed (= current base))
      (register! store-id composed)

      :else
      (let [composed' (fn [report]
                        (when current (current report))
                        (validate-report report))]
        (swap! installed assoc store-id {:base current :composed composed'})
        (register! store-id composed')))
    store-id))
