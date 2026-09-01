(ns dvergr.agent.attempt.governance
  "Mandatory Datahike writer predicate for certified Attempt projections.

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

(defn- protected-ident? [ident]
  (and (keyword? ident)
       (contains? #{"attempt" "attempt.check"} (namespace ident))))

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
    {:attempt/checks [*]}])

(defn- entity-map [db eid]
  (d/pull db entity-pattern eid))

(defn- attempt? [entity] (uuid? (:attempt/id entity)))
(defn- check? [entity] (uuid? (:attempt.check/id entity)))

(defn- same-chat? [attempt run]
  (= (:db/id (:attempt/chat attempt)) (:db/id (:run/chat run))))

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
    (swap! authorized-writes assoc key attempt-id)
    (try
      (f {:attempt.writer/token token})
      (finally
        (swap! authorized-writes dissoc key)))))

(defn- authorized-attempt-id [report]
  (when-let [token (get-in report [:tx-meta :attempt.writer/token])]
    (get @authorized-writes
         [(get-in report [:db-after :config :store :id]) token])))

(defn validate-report
  "Reject unauthorized creation and every mutation of certified Attempt facts."
  [{:keys [db-before db-after tx-data] :as report}]
  (let [changed (vec (touched report))
        eids (into #{} (map #(nth % 0)) changed)
        merge? (seq (get-in db-after [:meta :datahike/merge-parents]))
        authorized-id (authorized-attempt-id report)]
    (doseq [eid eids]
      (let [before (entity-map db-before eid)
            after (entity-map db-after eid)
            before-attempt? (attempt? before)
            after-attempt? (attempt? after)
            before-check? (check? before)
            after-check? (check? after)
            entity-datoms (filter #(= eid (nth % 0)) changed)]
        (cond
          (and (not before-attempt?) after-attempt?)
          (do
            (when-not (or merge? (= authorized-id (:attempt/id after)))
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
          (when-not (or merge? authorized-id)
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
                               :check/id (:attempt.check/id before)})))))))
    ;; A newly-created check must be owned by an Attempt component edge.
    (doseq [eid eids
            :let [before (entity-map db-before eid)
                  after (entity-map db-after eid)]
            :when (and (not (check? before)) (check? after))]
      (when-not (d/q '[:find ?a . :in $ ?check
                       :where [?a :attempt/checks ?check]] db-after eid)
        (throw (ex-info "Attempt check is not owned by a certified Attempt"
                        {:type ::orphan-check :check/id (:attempt.check/id after)}))))
    report))

(defonce ^:private installed (atom {}))

(defn govern!
  "Compose Attempt validation with the store's current mandatory predicate."
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
