(ns dvergr.agent.experiment
  "Portable experiment definitions and pure scorecard projections.

   A DatasetDef contains exact EnvironmentDefs. An ExperimentDef pairs every
   environment with exact AgentDef identities and a repetition count. Running
   an experiment merely composes ordinary evaluation Spins; Runs, worlds,
   certified Attempts, and settlement retain their existing authority."
  (:require [dvergr.agent.attempt :as attempt]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.roster :as roster]
            [hasch.core :as hasch]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.spin.combinators :as comb]))

(defn- invalid! [message type data]
  (throw (ex-info message (assoc data :type type))))

(defn- positive-int! [label value]
  (when-not (and (integer? value) (pos? value))
    (invalid! (str label " must be a positive integer")
              ::invalid-positive-integer {:label label :value value}))
  value)

(defn- portable-map! [label value]
  (when-not (and (map? value) (roster/data-value? value))
    (invalid! (str label " must be a portable map")
              ::invalid-portable-map {:label label :value value}))
  value)

(def ^:private dataset-spec-keys #{:id :version :environments :metadata})
(def ^:private dataset-keys
  #{:dataset/id :dataset/version :dataset/environments :dataset/metadata
    :dataset/content-id})
(def ^:private required-dataset-keys
  (disj dataset-keys :dataset/metadata))

(defn make-dataset
  "Construct a portable, content-addressed DatasetDef from a non-empty vector
   of exact EnvironmentDefs. Environment content identities must be unique."
  [{:keys [id version environments metadata]
    :or {version 1}
    :as opts}]
  (when-let [unknown (seq (remove dataset-spec-keys (keys opts)))]
    (invalid! "Dataset contains unknown keys" ::unknown-dataset-keys
              {:unknown (set unknown) :allowed dataset-spec-keys}))
  (when-not (keyword? id)
    (invalid! "Dataset :id must be a keyword" ::invalid-dataset-id {:id id}))
  (positive-int! "Dataset :version" version)
  (when-not (and (vector? environments) (seq environments))
    (invalid! "Dataset :environments must be a non-empty vector"
              ::invalid-environments {:environments environments}))
  (doseq [definition environments]
    (environment/validate-environment definition))
  (let [ids (mapv :environment/content-id environments)]
    (when-not (= (count ids) (count (set ids)))
      (invalid! "Dataset contains duplicate EnvironmentDefs"
                ::duplicate-environments {:content-ids ids})))
  (when (some? metadata) (portable-map! "Dataset :metadata" metadata))
  (let [definition (cond-> {:dataset/id id
                            :dataset/version version
                            :dataset/environments environments}
                     (some? metadata) (assoc :dataset/metadata metadata))]
    (assoc definition :dataset/content-id
           (hasch/uuid [:dvergr/dataset-definition definition]))))

(defn validate-dataset
  "Validate canonical DatasetDef data and its claimed content identity."
  [dataset]
  (when-not (map? dataset)
    (invalid! "DatasetDef must be a map" ::invalid-dataset {:value dataset}))
  (when-let [unknown (seq (remove dataset-keys (keys dataset)))]
    (invalid! "DatasetDef contains unknown canonical keys"
              ::unknown-dataset-keys {:unknown (set unknown)}))
  (when-let [missing (seq (remove #(contains? dataset %)
                                  required-dataset-keys))]
    (invalid! "DatasetDef is missing canonical keys"
              ::missing-dataset-keys {:missing (set missing)}))
  (let [spec (cond-> {:id (:dataset/id dataset)
                      :version (:dataset/version dataset)
                      :environments (:dataset/environments dataset)}
               (contains? dataset :dataset/metadata)
               (assoc :metadata (:dataset/metadata dataset)))]
    (when-not (= dataset (make-dataset spec))
      (invalid! "DatasetDef is not canonical or its content ID is stale"
                ::non-canonical-dataset {:dataset/id (:dataset/id dataset)})))
  dataset)

(defn dataset-ref
  "Return the stable logical/version/content reference for a DatasetDef."
  [dataset]
  (select-keys (validate-dataset dataset)
               [:dataset/id :dataset/version :dataset/content-id]))

(defn- candidate [agent]
  (when-not (and (map? agent)
                 (keyword? (:agent/id agent))
                 (integer? (:agent/version agent))
                 (pos? (:agent/version agent))
                 (roster/data-value? agent))
    (invalid! "Experiment candidates must be portable AgentDefs"
              ::invalid-candidate {:candidate agent}))
  {:candidate/id (:agent/id agent)
   :candidate/agent (roster/agent-ref agent)
   :candidate/agent-content-id (hasch/uuid agent)})

(def ^:private experiment-spec-keys
  #{:id :version :dataset :candidates :repetitions :metadata})
(def ^:private experiment-keys
  #{:experiment/id :experiment/version :experiment/dataset
    :experiment/candidates :experiment/repetitions :experiment/metadata
    :experiment/content-id})
(def ^:private required-experiment-keys
  (disj experiment-keys :experiment/metadata))

(defn make-experiment
  "Construct a content-addressed full-factorial ExperimentDef.

   `:candidates` is a non-empty vector of AgentDefs. Each stored candidate binds
   its lightweight AgentRef to the hash of the complete definition. Every
   candidate is evaluated in every DatasetDef environment `:repetitions` times.
   Concurrency and admission ceilings belong to host execution policy, not to
   this agent-authorable definition."
  [{:keys [id version dataset candidates repetitions metadata]
    :or {version 1 repetitions 1}
    :as opts}]
  (when-let [unknown (seq (remove experiment-spec-keys (keys opts)))]
    (invalid! "Experiment contains unknown keys" ::unknown-experiment-keys
              {:unknown (set unknown) :allowed experiment-spec-keys}))
  (when-not (keyword? id)
    (invalid! "Experiment :id must be a keyword"
              ::invalid-experiment-id {:id id}))
  (positive-int! "Experiment :version" version)
  (validate-dataset dataset)
  (when-not (and (vector? candidates) (seq candidates))
    (invalid! "Experiment :candidates must be a non-empty vector"
              ::invalid-candidates {:candidates candidates}))
  (let [candidates (mapv candidate candidates)
        ids (mapv :candidate/id candidates)]
    (when-not (= (count ids) (count (set ids)))
      (invalid! "Experiment candidate ids must be unique"
                ::duplicate-candidates {:candidate-ids ids}))
    (positive-int! "Experiment :repetitions" repetitions)
    (when (some? metadata) (portable-map! "Experiment :metadata" metadata))
    (let [definition
          (cond-> {:experiment/id id
                   :experiment/version version
                   :experiment/dataset dataset
                   :experiment/candidates candidates
                   :experiment/repetitions repetitions}
            (some? metadata) (assoc :experiment/metadata metadata))]
      (assoc definition :experiment/content-id
             (hasch/uuid [:dvergr/experiment-definition definition])))))

(defn validate-experiment
  "Validate canonical ExperimentDef data and all embedded definitions."
  [experiment]
  (when-not (map? experiment)
    (invalid! "ExperimentDef must be a map"
              ::invalid-experiment {:value experiment}))
  (when-let [unknown (seq (remove experiment-keys (keys experiment)))]
    (invalid! "ExperimentDef contains unknown canonical keys"
              ::unknown-experiment-keys {:unknown (set unknown)}))
  (when-let [missing (seq (remove #(contains? experiment %)
                                  required-experiment-keys))]
    (invalid! "ExperimentDef is missing canonical keys"
              ::missing-experiment-keys {:missing (set missing)}))
  (validate-dataset (:experiment/dataset experiment))
  (let [candidates (:experiment/candidates experiment)]
    (when-not (and (vector? candidates) (seq candidates))
      (invalid! "Experiment candidates must be a non-empty vector"
                ::invalid-candidates {:candidates candidates}))
    (doseq [c candidates]
      (when-not (and (= #{:candidate/id :candidate/agent
                          :candidate/agent-content-id}
                        (set (keys c)))
                     (keyword? (:candidate/id c))
                     (= (:candidate/id c) (get-in c [:candidate/agent :agent/id]))
                     (uuid? (:candidate/agent-content-id c))
                     (= #{:agent/id :agent/version}
                        (set (keys (:candidate/agent c))))
                     (integer? (get-in c [:candidate/agent :agent/version]))
                     (pos? (get-in c [:candidate/agent :agent/version])))
        (invalid! "Experiment contains an invalid canonical candidate"
                  ::invalid-candidate {:candidate c})))
    (when-not (= (count candidates) (count (set (map :candidate/id candidates))))
      (invalid! "Experiment candidate ids must be unique"
                ::duplicate-candidates
                {:candidate-ids (mapv :candidate/id candidates)})))
  (positive-int! "Experiment :version" (:experiment/version experiment))
  (positive-int! "Experiment :repetitions" (:experiment/repetitions experiment))
  (when (contains? experiment :experiment/metadata)
    (portable-map! "Experiment :metadata" (:experiment/metadata experiment)))
  (when-not (and (keyword? (:experiment/id experiment))
                 (roster/data-value? experiment))
    (invalid! "ExperimentDef must contain portable data"
              ::non-portable-experiment {:experiment experiment}))
  (let [claimed (:experiment/content-id experiment)
        actual (hasch/uuid [:dvergr/experiment-definition
                            (dissoc experiment :experiment/content-id)])]
    (when-not (= claimed actual)
      (invalid! "ExperimentDef content ID does not match its content"
                ::experiment-content-id-mismatch
                {:claimed claimed :actual actual})))
  experiment)

(defn experiment-ref
  "Return the stable logical/version/content reference for an ExperimentDef."
  [experiment]
  (select-keys (validate-experiment experiment)
               [:experiment/id :experiment/version :experiment/content-id]))

(defn- exact-agent! [team candidate]
  (let [ref (:candidate/agent candidate)
        agent (or (roster/agent team ref)
                  (invalid! "Experiment candidate is absent from the Roster"
                            ::unknown-candidate {:candidate candidate}))
        actual (hasch/uuid agent)]
    (when-not (= (:candidate/agent-content-id candidate) actual)
      (invalid! "Roster AgentDef does not match the ExperimentDef candidate"
                ::candidate-content-mismatch
                {:candidate/id (:candidate/id candidate)
                 :expected (:candidate/agent-content-id candidate)
                 :actual actual}))
    agent))

(defn- evaluator! [evaluators definition]
  (let [ref (:environment/verifier definition)
        evaluator (get evaluators ref)]
    (or evaluator
        (invalid! "Experiment has no exact host Evaluator for an environment"
                  ::missing-evaluator {:verifier ref
                                       :environment
                                       (environment/environment-ref definition)}))))

(defn- world-setup! [world-setups definition]
  (when-let [ref (get-in definition [:environment/world :setup])]
    (or (get world-setups ref)
        (invalid! "Experiment has no exact host WorldSetup for an environment"
                  ::missing-world-setup
                  {:setup ref
                   :environment (environment/environment-ref definition)}))))

(defn- cell-evaluation-opts [world-setups opts definition]
  (if-let [setup (world-setup! world-setups definition)]
    (assoc opts :world-setup setup)
    opts))

(defn- jobs [experiment]
  (for [candidate (:experiment/candidates experiment)
        definition (get-in experiment
                           [:experiment/dataset :dataset/environments])
        repetition (range (:experiment/repetitions experiment))]
    {:candidate candidate
     :environment definition
     :repetition repetition}))

(defn- result-spin [room team evaluators world-setups opts job]
  (let [definition (:environment job)
        candidate (:candidate job)
        evaluation-spin
        (evaluation/evaluate room team (:candidate/agent candidate)
                             definition (evaluator! evaluators definition)
                             (cell-evaluation-opts world-setups opts definition))]
    (sp/spin
     (let [result (sp/await evaluation-spin)]
       (assoc result :experiment/job
              {:candidate/id (:candidate/id candidate)
               :candidate/agent (:candidate/agent candidate)
               :candidate/agent-content-id
               (:candidate/agent-content-id candidate)
               :environment (environment/environment-ref definition)
               :repetition (:repetition job)})))))

(defn- run-batches [spins parallelism]
  (if (seq spins)
    (let [batch (vec (take parallelism spins))
          rest-spins (drop parallelism spins)]
      (sp/spin
       (let [head (if (= 1 (count batch))
                    [(sp/await (first batch))]
                    (sp/await (apply comb/parallel batch)))
             tail (sp/await (run-batches rest-spins parallelism))]
         (into head tail))))
    (sp/spin [])))

(defn- passed? [receipt]
  (every? true? (vals (:attempt/checks receipt))))

(defn- score-entry [{:keys [experiment/job attempt]}]
  (attempt/validate-attempt attempt)
  (let [receipt (:attempt/receipt attempt)]
    (when-not (= [(:candidate/agent-content-id job) (:environment job)]
                 [(:attempt/agent-def-hash attempt)
                  (:attempt/environment receipt)])
      (invalid! "Certified Attempt does not match its experiment cell"
                ::attempt-cell-mismatch {:job job :attempt/id (:attempt/id attempt)}))
    {:candidate/id (:candidate/id job)
     :candidate/agent (:candidate/agent job)
     :candidate/agent-content-id (:candidate/agent-content-id job)
     :environment (:environment job)
     :repetition (:repetition job)
     :attempt/id (:attempt/id attempt)
     :attempt/content-id (:attempt/content-id attempt)
     :reward (:attempt/reward receipt)
     :passed? (passed? receipt)}))

(defn- summarize [entries]
  (->> entries
       (group-by :candidate/id)
       (map (fn [[candidate-id xs]]
              (let [rewards (map :reward xs)
                    n (count xs)]
                {:candidate/id candidate-id
                 :attempt-count n
                 :passed-count (count (filter :passed? xs))
                 :reward-sum (reduce + 0 rewards)
                 :reward-mean (double (/ (reduce + 0 rewards) n))})))
       (sort-by (comp str :candidate/id))
       vec))

(defn- entry-sort-key [entry]
  [(str (:candidate/id entry))
   (str (get-in entry [:environment :environment/id]))
   (get-in entry [:environment :environment/version])
   (str (get-in entry [:environment :environment/content-id]))
   (:repetition entry)])

(declare validate-scorecard)

(defn make-scorecard
  "Create a content-addressed Scorecard from successful evaluation results.
   Every result must contain the certified Attempt produced for its exact cell."
  [experiment results]
  (validate-experiment experiment)
  (let [entries (->> results (map score-entry)
                     (sort-by entry-sort-key)
                     vec)
        expected (* (count (:experiment/candidates experiment))
                    (count (get-in experiment
                                   [:experiment/dataset :dataset/environments]))
                    (:experiment/repetitions experiment))]
    (when-not (= expected (count entries))
      (invalid! "Scorecard requires exactly one Attempt per experiment cell"
                ::incomplete-scorecard {:expected expected
                                        :actual (count entries)}))
    (when-not (= (count entries)
                 (count (set (map (juxt :candidate/agent-content-id
                                        (comp :environment/content-id
                                              :environment)
                                        :repetition)
                                  entries))))
      (invalid! "Scorecard contains duplicate experiment cells"
                ::duplicate-scorecard-cells {}))
    (when-not (= (count entries) (count (set (map :attempt/id entries))))
      (invalid! "Scorecard repetitions must name distinct Attempts"
                ::duplicate-scorecard-attempts {}))
    (let [scorecard {:scorecard/experiment experiment
                     :scorecard/entries entries
                     :scorecard/summary (summarize entries)}]
      (validate-scorecard
       (assoc scorecard :scorecard/content-id
              (hasch/uuid [:dvergr/experiment-scorecard scorecard]))))))

(defn validate-scorecard
  "Validate a canonical Scorecard, its experiment-cell matrix, deterministic
   aggregates, and claimed content identity."
  [scorecard]
  (when-not (and (map? scorecard)
                 (= #{:scorecard/experiment :scorecard/entries
                      :scorecard/summary :scorecard/content-id}
                    (set (keys scorecard)))
                 (roster/data-value? scorecard))
    (invalid! "Scorecard must be canonical portable data"
              ::invalid-scorecard {:scorecard scorecard}))
  (validate-experiment (:scorecard/experiment scorecard))
  (let [experiment (:scorecard/experiment scorecard)
        entries (:scorecard/entries scorecard)
        candidates (into {} (map (juxt :candidate/id identity)
                                 (:experiment/candidates experiment)))
        environments (into {}
                           (map (juxt :environment/content-id identity)
                                (get-in experiment
                                        [:experiment/dataset
                                         :dataset/environments])))
        expected (* (count candidates) (count environments)
                    (:experiment/repetitions experiment))]
    (when-not (and (vector? entries) (= expected (count entries)))
      (invalid! "Scorecard has an incomplete entry set"
                ::incomplete-scorecard {:expected expected
                                        :actual (count entries)}))
    (when-not (= entries (vec (sort-by entry-sort-key entries)))
      (invalid! "Scorecard entries are not in canonical cell order"
                ::non-canonical-scorecard-order {}))
    (doseq [entry entries]
      (let [candidate (get candidates (:candidate/id entry))
            environment-ref (:environment entry)
            definition (get environments (:environment/content-id
                                          environment-ref))]
        (when-not (and
                   (= #{:candidate/id :candidate/agent
                        :candidate/agent-content-id :environment :repetition
                        :attempt/id :attempt/content-id :reward :passed?}
                      (set (keys entry)))
                   (= (select-keys candidate
                                   [:candidate/id :candidate/agent
                                    :candidate/agent-content-id])
                      (select-keys entry
                                   [:candidate/id :candidate/agent
                                    :candidate/agent-content-id]))
                   definition
                   (= environment-ref (environment/environment-ref definition))
                   (integer? (:repetition entry))
                   (<= 0 (:repetition entry))
                   (< (:repetition entry)
                      (:experiment/repetitions experiment))
                   (uuid? (:attempt/id entry))
                   (uuid? (:attempt/content-id entry))
                   (number? (:reward entry))
                   (Double/isFinite (double (:reward entry)))
                   (boolean? (:passed? entry)))
          (invalid! "Scorecard contains an invalid experiment-cell entry"
                    ::invalid-scorecard-entry {:entry entry}))))
    (when-not (= (count entries)
                 (count (set (map (juxt :candidate/agent-content-id
                                        (comp :environment/content-id
                                              :environment)
                                        :repetition)
                                  entries))))
      (invalid! "Scorecard contains duplicate experiment cells"
                ::duplicate-scorecard-cells {}))
    (when-not (= (count entries) (count (set (map :attempt/id entries))))
      (invalid! "Scorecard repetitions must name distinct Attempts"
                ::duplicate-scorecard-attempts {}))
    (when-not (= (:scorecard/summary scorecard) (summarize entries))
      (invalid! "Scorecard summary does not match its entries"
                ::scorecard-summary-mismatch {})))
  (let [claimed (:scorecard/content-id scorecard)
        actual (hasch/uuid [:dvergr/experiment-scorecard
                            (dissoc scorecard :scorecard/content-id)])]
    (when-not (= claimed actual)
      (invalid! "Scorecard content ID does not match its content"
                ::scorecard-content-id-mismatch
                {:claimed claimed :actual actual})))
  scorecard)

(defn run
  "Return a lazy Spin for one complete ExperimentDef.

   `evaluators` is a host-owned map from exact verifier refs to Evaluator
   capabilities. All candidates, evaluators, environment policies, and host
   admission ceilings are preflighted before the Spin can admit a Run. Jobs and
   evaluation Spins are realized one bounded batch at a time. Options include
   ordinary evaluation `:from`/`:parent-run` plus host-owned `:parallelism`,
   `:max-parallelism`, `:max-attempts`, and an exact `:world-setups` capability
   map for environments that name setup references. Experiment batches
   initially require discard settlement; retained partial experiments need
   durable execution identity and recovery first."
  ([room team experiment evaluators]
   (run room team experiment evaluators {}))
  ([room team experiment evaluators
    {:keys [parallelism max-parallelism max-attempts world-setups]
     :or {parallelism 1 max-parallelism 16 max-attempts 256 world-setups {}}
     :as opts}]
   (validate-experiment experiment)
   (when-not (map? evaluators)
     (invalid! "Experiment evaluators must be a host capability map"
               ::invalid-evaluators {:evaluators evaluators}))
   (when-not (map? world-setups)
     (invalid! "Experiment WorldSetups must be a host capability map"
               ::invalid-world-setups {:world-setups world-setups}))
   (doseq [candidate (:experiment/candidates experiment)]
     (exact-agent! team candidate))
   (doseq [definition (get-in experiment
                              [:experiment/dataset :dataset/environments])]
     (evaluator! evaluators definition)
     (world-setup! world-setups definition)
     (when-not (= :discard (get-in definition
                                   [:environment/world :settlement]))
       (invalid! "Experiment batches currently require :discard settlement"
                 ::retained-experiment-unsupported
                 {:environment (environment/environment-ref definition)
                  :settlement (get-in definition
                                      [:environment/world :settlement])})))
   (when-let [unknown (seq (remove #{:from :parent-run :parallelism
                                     :max-parallelism :max-attempts
                                     :world-setups}
                                   (keys opts)))]
     (invalid! "Experiment contains unknown run options"
               ::unknown-run-options {:unknown (set unknown)}))
   (doseq [[label value] [["Experiment :parallelism" parallelism]
                          ["Experiment :max-parallelism" max-parallelism]
                          ["Experiment :max-attempts" max-attempts]]]
     (positive-int! label value))
   (when (> parallelism max-parallelism)
     (invalid! "Experiment parallelism exceeds the host admission ceiling"
               ::parallelism-exceeds-ceiling
               {:parallelism parallelism :max-parallelism max-parallelism}))
   (let [attempt-count (* (count (:experiment/candidates experiment))
                          (count (get-in experiment
                                         [:experiment/dataset
                                          :dataset/environments]))
                          (:experiment/repetitions experiment))
         _ (when (> attempt-count max-attempts)
             (invalid! "Experiment size exceeds the host admission ceiling"
                       ::attempts-exceed-ceiling
                       {:attempt-count attempt-count
                        :max-attempts max-attempts}))
         base-evaluation-opts (select-keys opts [:from :parent-run])
         ;; Validate every distinct candidate/environment pairing once before
         ;; execution. Repetitions are constructed lazily per bounded batch.
         _ (doseq [candidate (:experiment/candidates experiment)
                   definition (get-in experiment
                                      [:experiment/dataset
                                       :dataset/environments])]
             (evaluation/evaluate
              room team (:candidate/agent candidate)
              definition (evaluator! evaluators definition)
              (cell-evaluation-opts world-setups base-evaluation-opts
                                    definition)))
         spins (map #(result-spin room team evaluators world-setups
                                  base-evaluation-opts %)
                    (jobs experiment))]
     (sp/spin
      (let [results (sp/await (run-batches spins parallelism))]
        {:experiment experiment
         :execution {:parallelism parallelism
                     :attempt-count attempt-count}
         :results results
         :attempts (mapv :attempt results)
         :scorecard (make-scorecard experiment results)})))))
