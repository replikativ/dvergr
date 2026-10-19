(ns dvergr.agent.experiment-admission
  "Pure admission of an agent's child experiment. No wallet mutation, execution
   or new authority: the explicit aggregate grant must cover every cell."
  (:require [dvergr.agent.experiment :as experiment]
            [dvergr.agent.authority :as authority]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.evaluators :as evaluators]
            [dvergr.resource :as resource]
            [hasch.core :as hasch]))

(defn- invalid! [message data]
  (throw (ex-info message (assoc data :type ::refused))))

(defn- resource-vector! [resources]
  (when-not (and (map? resources)
                 (every? (fn [[coordinate amount]]
                           (and (or (keyword? coordinate) (string? coordinate))
                                (number? amount) (pos? amount)
                                (Double/isFinite (double amount)))) resources))
    (invalid! "Experiment resources must be a finite positive resource vector"
              {:resources resources}))
  resources)

(defn plan
  "Preflight exact candidates, attenuation, aggregate resources and offered
   capabilities before creating a Job. `authority` is host-owned, never SCI
   input. An explicit capability scope (including {}) replaces the catalog."
  [team definition {:keys [parallelism resources] :or {parallelism 1 resources {}} :as opts}
   {:keys [program-kinds funded-program-kinds funded-providers max-attempts max-parallelism]
    :or {max-attempts 256 max-parallelism 16
         funded-providers #{:openai :anthropic :fireworks :codex-subscription}} :as authority}]
  (when (contains? authority :delegation-policy)
    (authority/validate-policy! (:delegation-policy authority)))
  (when-let [unknown (seq (remove #{:parallelism :resources} (keys opts)))]
    (invalid! "Unknown run-experiment! options" {:unknown (set unknown)}))
  (experiment/validate-experiment definition)
  (resource-vector! resources)
  (when-not (and (pos-int? parallelism)
                 (<= parallelism (min max-parallelism (get-in authority [:delegation-policy :max-parallelism] max-parallelism))))
    (invalid! "Experiment parallelism exceeds the host ceiling" {:parallelism parallelism}))
  (let [candidates (:experiment/candidates definition)
        environments (get-in definition [:experiment/dataset :dataset/environments])
        repetitions (:experiment/repetitions definition)
        count-cells (* (count candidates) (count environments) repetitions)]
    (when (> count-cells (min max-attempts (get-in authority [:delegation-policy :max-attempts] max-attempts)))
      (invalid! "Experiment size exceeds the host ceiling" {:cells count-cells :max-attempts max-attempts}))
    (doseq [candidate candidates]
      (let [agent (or (roster/agent team (:candidate/agent candidate))
                      (invalid! "Experiment candidate is absent from roster" {:candidate candidate}))]
        (authority/check-agent! agent authority)
        (when-not (= (:candidate/agent-content-id candidate) (hasch/uuid agent))
          (invalid! "Experiment candidate content differs from roster" {:candidate candidate}))))
    (doseq [env environments
            :let [grant (resource-vector! (or (get-in env [:environment/world :resources]) {}))]]
      (when-not (= :discard (get-in env [:environment/world :settlement]))
        (invalid! "Child experiment cells require discard settlement" {}))
      (doseq [candidate candidates
              :let [agent (roster/agent team (:candidate/agent candidate))
                    kind (get-in agent [:agent/program :kind])]]
        (when (and (= :llm kind) (not (resource/funded-grant? grant)))
          (invalid! "LLM candidate exceeds the delegation ceiling without funded per-cell resources"
                    {:program-kind kind}))
        (when (and (= :llm kind)
                   (not (contains? funded-providers (get-in agent [:agent/model-policy :provider]))))
          (invalid! "Funded child experiments require an accounted native provider"
                    {:provider (get-in agent [:agent/model-policy :provider])}))
        (when (and program-kinds (not (contains? program-kinds kind))
                   (not (and (contains? funded-program-kinds kind)
                             (resource/funded-grant? grant))))
          (invalid! "A candidate exceeds this sandbox's delegation ceiling"
                    {:program-kind kind}))))
    (let [required (reduce (fn [total env]
                             (merge-with + total
                                         (update-vals (or (get-in env [:environment/world :resources]) {})
                                                      #(* % (count candidates) repetitions))))
                           {} environments)]
      (when-not (every? (fn [[coordinate amount]] (>= (get resources coordinate 0) amount)) required)
        (invalid! "Explicit experiment grant does not cover all cells"
                  {:required required :resources resources}))
      {:resources resources :required required :cells count-cells :parallelism parallelism
       :capabilities (if (contains? authority :experiment-capabilities)
                       (evaluators/capabilities-for definition (:experiment-capabilities authority))
                       (evaluators/capabilities-for definition))})))
