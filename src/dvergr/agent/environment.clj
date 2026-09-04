(ns dvergr.agent.environment
  "Portable, content-addressed definitions for verified agent environments.

   An EnvironmentDef describes what may be attempted and names trusted verifier
   code. It never embeds the verifier function, a live world, or a Run handle.
   The content ID identifies this exact definition; individual attempts retain
   fresh Run identities."
  (:require [dvergr.agent.roster :as roster]
            [hasch.core :as hasch]))

(def ^:private allowed-keys
  #{:id :version :task :verifier :limits :world :metadata})

(def ^:private definition-keys
  #{:environment/id :environment/version :environment/task
    :environment/verifier :environment/limits :environment/world
    :environment/metadata :environment/content-id})

(def ^:private required-definition-keys
  #{:environment/id :environment/version :environment/task
    :environment/verifier :environment/limits :environment/world
    :environment/content-id})

(defn- invalid! [message type data]
  (throw (ex-info message (assoc data :type type))))

(defn- positive-version! [label version]
  (when-not (and (integer? version) (pos? version))
    (invalid! (str label " must be a positive integer")
              ::invalid-version
              {:label label :version version}))
  version)

(defn- verifier-ref! [verifier]
  (when-not (map? verifier)
    (invalid! "Environment :verifier must be a reference map"
              ::invalid-verifier {:verifier verifier}))
  (when-let [unknown (seq (remove #{:id :version :basis} (keys verifier)))]
    (invalid! "Environment :verifier contains unknown keys"
              ::unknown-verifier-keys {:unknown (set unknown)}))
  (let [{:keys [id version basis]} verifier]
    (when-not (keyword? id)
      (invalid! "Verifier :id must be a keyword"
                ::invalid-verifier-id {:id id}))
    (positive-version! "Verifier :version" (or version 1))
    (when-not (roster/data-value? basis)
      (invalid! "Verifier :basis must contain only portable data"
                ::non-portable-verifier-basis {:basis basis}))
    (cond-> {:verifier/id id
             :verifier/version (or version 1)}
      basis (assoc :verifier/basis basis))))

(def ^:private setup-ref-keys #{:setup/id :setup/version :setup/basis})

(defn- setup-ref! [setup]
  (when-not (map? setup)
    (invalid! "Environment world :setup must be an exact reference map"
              ::invalid-setup {:setup setup}))
  (when-let [unknown (seq (remove setup-ref-keys (keys setup)))]
    (invalid! "Environment world :setup contains unknown keys"
              ::unknown-setup-keys {:unknown (set unknown)}))
  (when-not (keyword? (:setup/id setup))
    (invalid! "World setup :setup/id must be a keyword"
              ::invalid-setup-id {:id (:setup/id setup)}))
  (positive-version! "World setup :setup/version" (:setup/version setup))
  (when-not (roster/data-value? (:setup/basis setup))
    (invalid! "World setup :setup/basis must contain only portable data"
              ::non-portable-setup-basis {:basis (:setup/basis setup)}))
  (cond-> {:setup/id (:setup/id setup)
           :setup/version (:setup/version setup)}
    (some? (:setup/basis setup))
    (assoc :setup/basis (:setup/basis setup))))

(defn make-environment
  "Construct a portable, content-addressed EnvironmentDef.

   Required options are keyword `:id`, portable `:task`, and `:verifier` as
   `{:id keyword :version positive-int}`. Optional `:basis` identifies immutable
   verifier source. `:limits`, `:world`, and `:metadata` are portable policy
   data interpreted by the runner."
  [{:keys [id version task verifier limits world metadata]
    :or {version 1 limits {} world {}}
    :as opts}]
  (when-let [unknown (seq (remove allowed-keys (keys opts)))]
    (invalid! "Environment contains unknown keys"
              ::unknown-environment-keys
              {:unknown (set unknown) :allowed allowed-keys}))
  (when-not (keyword? id)
    (invalid! "Environment :id must be a keyword"
              ::invalid-environment-id {:id id}))
  (positive-version! "Environment :version" version)
  (when-not (contains? opts :task)
    (invalid! "Environment requires :task"
              ::missing-task {:environment/id id}))
  (doseq [[label value] [[:task task] [:limits limits] [:world world]
                         [:metadata metadata]]]
    (when-not (roster/data-value? value)
      (invalid! (str "Environment " label " must contain only portable data")
                ::non-portable-data {:label label :value value})))
  (when-not (map? limits)
    (invalid! "Environment :limits must be a map"
              ::invalid-limits {:limits limits}))
  (when-not (map? world)
    (invalid! "Environment :world must be a map"
              ::invalid-world {:world world}))
  (let [world (if (contains? world :setup)
                (assoc world :setup (setup-ref! (:setup world)))
                world)]
    (when (and metadata (not (map? metadata)))
      (invalid! "Environment :metadata must be a map"
                ::invalid-metadata {:metadata metadata}))
    (let [definition (cond-> {:environment/id id
                              :environment/version version
                              :environment/task task
                              :environment/verifier (verifier-ref! verifier)
                              :environment/limits limits
                              :environment/world world}
                       metadata (assoc :environment/metadata metadata))]
      (assoc definition
             :environment/content-id
             (hasch/uuid [:dvergr/environment-definition definition])))))

(defn validate-environment
  "Validate that `environment` is canonical portable data and that its content
   ID still names its exact current value. Returns the unchanged definition."
  [environment]
  (when-not (map? environment)
    (invalid! "EnvironmentDef must be a map"
              ::invalid-definition {:value environment}))
  (when-let [unknown (seq (remove definition-keys (keys environment)))]
    (invalid! "EnvironmentDef contains unknown canonical keys"
              ::unknown-definition-keys {:unknown (set unknown)}))
  (when-let [missing (seq (remove #(contains? environment %)
                                  required-definition-keys))]
    (invalid! "EnvironmentDef is missing canonical keys"
              ::missing-definition-keys {:missing (set missing)}))
  (when-not (roster/data-value? environment)
    (invalid! "EnvironmentDef must contain only portable data"
              ::non-portable-data {:value environment}))
  (let [claimed (:environment/content-id environment)
        content (dissoc environment :environment/content-id)
        actual (hasch/uuid [:dvergr/environment-definition content])]
    (when-not (= claimed actual)
      (invalid! "EnvironmentDef content ID does not match its content"
                ::content-id-mismatch {:claimed claimed :actual actual})))
  (let [verifier (:environment/verifier environment)
        spec (cond-> {:id (:environment/id environment)
                      :version (:environment/version environment)
                      :task (:environment/task environment)
                      :verifier (cond-> {:id (:verifier/id verifier)
                                         :version (:verifier/version verifier)}
                                  (contains? verifier :verifier/basis)
                                  (assoc :basis (:verifier/basis verifier)))
                      :limits (:environment/limits environment)
                      :world (:environment/world environment)}
               (contains? environment :environment/metadata)
               (assoc :metadata (:environment/metadata environment)))]
    (when-not (= environment (make-environment spec))
      (invalid! "EnvironmentDef is not in canonical form"
                ::non-canonical-definition {:value environment})))
  environment)

(defn environment-ref
  "Stable reference to one exact EnvironmentDef."
  [environment]
  (select-keys (validate-environment environment)
               [:environment/id :environment/version :environment/content-id]))

(def ^:private attempt-option-keys
  #{:run-id :provider :model :status :started-at :elapsed-ms :metrics
    :checks :reward :result :trace :resources})

(def ^:private attempt-receipt-keys
  #{:attempt/id :attempt/run-id :attempt/environment :attempt/provider
    :attempt/model :attempt/status :attempt/started-at :attempt/elapsed-ms
    :attempt/metrics :attempt/checks :attempt/reward :attempt/result
    :attempt/trace :attempt/resources :attempt/content-id})

(def ^:private required-attempt-receipt-keys
  #{:attempt/id :attempt/run-id :attempt/environment :attempt/provider
    :attempt/model :attempt/status :attempt/started-at :attempt/elapsed-ms
    :attempt/metrics :attempt/checks :attempt/reward :attempt/content-id})

(def ^:private environment-ref-keys
  #{:environment/id :environment/version :environment/content-id})

(defn- environment-ref! [ref]
  (when-not (and (map? ref) (= environment-ref-keys (set (keys ref))))
    (invalid! "Attempt receipt :environment must be an exact EnvironmentRef"
              ::invalid-attempt-environment {:environment ref}))
  (when-not (keyword? (:environment/id ref))
    (invalid! "EnvironmentRef :environment/id must be a keyword"
              ::invalid-attempt-environment {:environment ref}))
  (positive-version! "EnvironmentRef :environment/version"
                     (:environment/version ref))
  (when-not (uuid? (:environment/content-id ref))
    (invalid! "EnvironmentRef :environment/content-id must be a UUID"
              ::invalid-attempt-environment {:environment ref}))
  ref)

(defn- finite-number? [value]
  (and (number? value)
       (try
         (Double/isFinite (double value))
         (catch Throwable _ false))))

(defn- attempt-fields!
  [{:keys [run-id environment provider model status started-at elapsed-ms metrics
           checks reward result trace resources]}]
  (when-not (uuid? run-id)
    (invalid! "Attempt receipt :run-id must be a UUID"
              ::invalid-attempt-run {:run-id run-id}))
  (environment-ref! environment)
  (when-not (keyword? provider)
    (invalid! "Attempt receipt :provider must be a keyword"
              ::invalid-attempt-provider {:provider provider}))
  (when-not (string? model)
    (invalid! "Attempt receipt :model must be a string"
              ::invalid-attempt-model {:model model}))
  (when-not (keyword? status)
    (invalid! "Attempt receipt :status must be a keyword"
              ::invalid-attempt-status {:status status}))
  (when-not (and (integer? started-at) (not (neg? started-at)))
    (invalid! "Attempt receipt :started-at must be epoch milliseconds"
              ::invalid-attempt-start {:started-at started-at}))
  (when-not (and (integer? elapsed-ms) (not (neg? elapsed-ms)))
    (invalid! "Attempt receipt :elapsed-ms must be a non-negative integer"
              ::invalid-attempt-elapsed {:elapsed-ms elapsed-ms}))
  (when-not (map? metrics)
    (invalid! "Attempt receipt :metrics must be a map"
              ::invalid-attempt-metrics {:metrics metrics}))
  (when-not (and (map? checks) (every? keyword? (keys checks))
                 (every? boolean? (vals checks)))
    (invalid! "Attempt receipt :checks must map keywords to booleans"
              ::invalid-attempt-checks {:checks checks}))
  (when-not (finite-number? reward)
    (invalid! "Attempt receipt :reward must be a finite number"
              ::invalid-attempt-reward {:reward reward}))
  (doseq [[label value] [[:metrics metrics] [:result result] [:trace trace]
                         [:resources resources]]]
    (when-not (roster/data-value? value)
      (invalid! (str "Attempt receipt " label " must contain only portable data")
                ::non-portable-attempt-data {:label label :value value})))
  true)

(defn make-attempt-receipt
  "Create an immutable verified-attempt receipt for one unique root Run.

   This constructor is intentionally host-only: SCI may author EnvironmentDefs,
   but the trusted runner supplies checks and reward after observing durable
   effects. `started-at` is epoch milliseconds; metrics/resources/trace remain
   portable data so the receipt can live in Datahike or Geschichte."
  [environment {:keys [run-id provider model status started-at elapsed-ms metrics
                       checks reward result trace resources]
                :as opts}]
  (validate-environment environment)
  (when-let [unknown (seq (remove attempt-option-keys (keys opts)))]
    (invalid! "Attempt receipt contains unknown keys"
              ::unknown-attempt-keys {:unknown (set unknown)}))
  (let [environment-ref (environment-ref environment)
        metrics (or metrics {})
        _ (attempt-fields! {:run-id run-id :environment environment-ref
                            :provider provider :model model :status status
                            :started-at started-at :elapsed-ms elapsed-ms
                            :metrics metrics :checks checks :reward reward
                            :result result :trace trace :resources resources})
        receipt (cond-> {:attempt/id run-id
                         :attempt/run-id run-id
                         :attempt/environment environment-ref
                         :attempt/provider provider
                         :attempt/model model
                         :attempt/status status
                         :attempt/started-at started-at
                         :attempt/elapsed-ms elapsed-ms
                         :attempt/metrics metrics
                         :attempt/checks checks
                         :attempt/reward reward}
                  (contains? opts :result) (assoc :attempt/result result)
                  (contains? opts :trace) (assoc :attempt/trace trace)
                  (contains? opts :resources) (assoc :attempt/resources resources))]
    (assoc receipt :attempt/content-id
           (hasch/uuid [:dvergr/environment-attempt receipt]))))

(defn validate-attempt-receipt
  "Reject a malformed or stale attempt receipt before persistence/projection.
   Content addressing detects mutation; authorization of the writer remains the
   durable store's responsibility, not a property of an unsigned value."
  [receipt]
  (when-not (map? receipt)
    (invalid! "Attempt receipt must be a map"
              ::invalid-attempt-receipt {:value receipt}))
  (when-let [unknown (seq (remove attempt-receipt-keys (keys receipt)))]
    (invalid! "Attempt receipt contains unknown canonical keys"
              ::unknown-attempt-receipt-keys {:unknown (set unknown)}))
  (when-let [missing (seq (remove #(contains? receipt %)
                                  required-attempt-receipt-keys))]
    (invalid! "Attempt receipt is missing canonical keys"
              ::missing-attempt-receipt-keys {:missing (set missing)}))
  (when-not (roster/data-value? receipt)
    (invalid! "Attempt receipt must contain only portable data"
              ::non-portable-attempt-data {:value receipt}))
  (when-not (= (:attempt/id receipt) (:attempt/run-id receipt))
    (invalid! "Attempt identity must equal its root Run identity"
              ::attempt-run-mismatch
              {:attempt/id (:attempt/id receipt)
               :attempt/run-id (:attempt/run-id receipt)}))
  (attempt-fields! {:run-id (:attempt/run-id receipt)
                    :environment (:attempt/environment receipt)
                    :provider (:attempt/provider receipt)
                    :model (:attempt/model receipt)
                    :status (:attempt/status receipt)
                    :started-at (:attempt/started-at receipt)
                    :elapsed-ms (:attempt/elapsed-ms receipt)
                    :metrics (:attempt/metrics receipt)
                    :checks (:attempt/checks receipt)
                    :reward (:attempt/reward receipt)
                    :result (:attempt/result receipt)
                    :trace (:attempt/trace receipt)
                    :resources (:attempt/resources receipt)})
  (let [claimed (:attempt/content-id receipt)
        actual (hasch/uuid [:dvergr/environment-attempt
                            (dissoc receipt :attempt/content-id)])]
    (when-not (= claimed actual)
      (invalid! "Attempt receipt content ID does not match its content"
                ::attempt-content-id-mismatch
                {:claimed claimed :actual actual})))
  receipt)
