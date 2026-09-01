(ns dvergr.agent.attempt
  "Immutable durable records of trusted evaluation certification.

   A certified Attempt is the write model. An Episode is later derived by
   joining it with the owning Run and immutable Room evidence; it is not a
   second lifecycle or settlement authority."
  (:require [dvergr.agent.environment :as environment]
            [dvergr.agent.roster :as roster]
            [dvergr.room.store :as store]
            [hasch.core :as hasch]))

(def ^:private attempt-keys
  #{:attempt/id :attempt/run-id :attempt/environment :attempt/agent
    :attempt/receipt :attempt/evidence :attempt/evidence-run-ids
    :attempt/evidence-message-ids :attempt/agent-def-hash
    :attempt/evidence-content-id :attempt/settlement-intent
    :attempt/certified-at :attempt/content-id})

(def ^:private required-keys
  (disj attempt-keys :attempt/evidence-message-ids))

(defn- invalid! [message type data]
  (throw (ex-info message (assoc data :type type))))

(defn- trace-ids [evidence k]
  (->> (get-in evidence [:trace k])
       (keep #(when (map? %) (get % (case k :runs :run/id :messages :message/id))))
       (filter uuid?)
       set))

(defn make-attempt
  "Construct the exact immutable record for one certified Attempt.

   `settlement-intent` records the requested post-certification policy. Actual
   settlement remains on the Run projection and is never copied back here."
  [definition agent receipt evidence settlement-intent]
  (environment/validate-environment definition)
  (environment/validate-attempt-receipt receipt)
  (when-not (roster/data-value? agent)
    (invalid! "Attempt AgentDef must be portable data"
              ::invalid-agent {:agent agent}))
  (when-not (= (environment/environment-ref definition)
               (:attempt/environment receipt))
    (invalid! "Attempt receipt names a different EnvironmentDef"
              ::environment-mismatch
              {:expected (environment/environment-ref definition)
               :actual (:attempt/environment receipt)}))
  (when-not (and (map? evidence) (roster/data-value? evidence))
    (invalid! "Attempt evidence must be a portable map"
              ::invalid-evidence {:evidence evidence}))
  (when-not (contains? #{:review :discard} settlement-intent)
    (invalid! "Attempt settlement intent must be :review or :discard"
              ::invalid-settlement-intent
              {:settlement-intent settlement-intent}))
  (doseq [k [:result :trace :resources]]
    (when-not (= [(contains? evidence k) (get evidence k)]
                 [(contains? receipt (keyword "attempt" (name k)))
                  (get receipt (keyword "attempt" (name k)))])
      (invalid! "Attempt evidence differs from its trusted receipt"
                ::evidence-receipt-mismatch {:key k})))
  (let [run-id (:attempt/run-id receipt)
        agent-hash (hasch/uuid agent)
        metrics (:attempt/metrics receipt)
        claimed-agent-hash (get-in receipt [:attempt/metrics :agent-def-hash])
        _ (when-not (= agent-hash claimed-agent-hash)
            (invalid! "Attempt AgentDef differs from receipt provenance"
                      ::agent-mismatch
                      {:expected claimed-agent-hash :actual agent-hash}))
        _ (doseq [[metric expected]
                  [[:agent-version (:agent/version agent)]
                   [:program-kind (get-in agent [:agent/program :kind])]]]
            (when-not (= expected (get metrics metric))
              (invalid! "Attempt AgentDef differs from receipt provenance"
                        ::agent-mismatch
                        {:metric metric :expected expected
                         :actual (get metrics metric)})))
        _ (when-not (and (integer? (:interpreter-version metrics))
                         (pos? (:interpreter-version metrics)))
            (invalid! "Attempt receipt requires an interpreter version"
                      ::missing-interpreter-version {:metrics metrics}))
        run-ids (conj (trace-ids evidence :runs) run-id)
        message-ids (trace-ids evidence :messages)]
    (cond-> {:attempt/id run-id
             :attempt/run-id run-id
             :attempt/environment definition
             :attempt/agent agent
             :attempt/receipt receipt
             :attempt/evidence evidence
             :attempt/evidence-run-ids run-ids
             :attempt/agent-def-hash agent-hash
             :attempt/evidence-content-id
             (hasch/uuid [:dvergr/evaluation-evidence evidence])
             :attempt/settlement-intent settlement-intent
             :attempt/certified-at (+ (:attempt/started-at receipt)
                                      (:attempt/elapsed-ms receipt))
             ;; The trusted receipt is the immutable certification identity.
             :attempt/content-id (:attempt/content-id receipt)}
      (seq message-ids) (assoc :attempt/evidence-message-ids message-ids))))

(defn validate-attempt
  "Validate exact certified Attempt content and return it unchanged."
  [value]
  (when-not (map? value)
    (invalid! "Certified Attempt must be a map"
              ::invalid-attempt {:value value}))
  (when-let [unknown (seq (remove attempt-keys (keys value)))]
    (invalid! "Certified Attempt contains unknown keys"
              ::unknown-attempt-keys {:unknown (set unknown)}))
  (when-let [missing (seq (remove #(contains? value %) required-keys))]
    (invalid! "Certified Attempt is missing required keys"
              ::missing-attempt-keys {:missing (set missing)}))
  (when-not (= (:attempt/id value) (:attempt/run-id value))
    (invalid! "Attempt identity must equal its root Run identity"
              ::run-mismatch {:attempt value}))
  (when-not (and (uuid? (:attempt/id value))
                 (integer? (:attempt/certified-at value))
                 (not (neg? (:attempt/certified-at value))))
    (invalid! "Attempt requires UUID identity and epoch-millisecond certification time"
              ::invalid-identity {:attempt value}))
  (let [rebuilt (make-attempt (:attempt/environment value)
                              (:attempt/agent value)
                              (:attempt/receipt value)
                              (:attempt/evidence value)
                              (:attempt/settlement-intent value))]
    (when-not (= value rebuilt)
      (invalid! "Certified Attempt is not canonical"
                ::non-canonical-attempt
                {:attempt/id (:attempt/id value)})))
  value)

(defn persist!
  "Persist `value` through the Room's Attempt store.

   Rooms without a store intentionally produce ephemeral attempts. A configured
   store must support durable Attempts; silently returning an unaudited reward
   would violate the trusted evaluation boundary."
  [room value]
  (validate-attempt value)
  (if-let [room-store (:store room)]
    (if (satisfies? store/PAttemptStore room-store)
      (or (store/-store-attempt! room-store (:id room) value)
          (invalid! "Attempt certification was not durable"
                    ::not-durable {:attempt/id (:attempt/id value)}))
      (invalid! "Configured Room store does not support durable Attempts"
                ::unsupported-store {:store (class room-store)}))
    value))
