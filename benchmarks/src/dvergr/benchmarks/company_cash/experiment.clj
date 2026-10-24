(ns dvergr.benchmarks.company-cash.experiment
  "Native artifact-model experiments over room workflows. The ordinary exported
   bundle retains its v1 forecast/decision checker; this explicit runner adds
   executable-model scoring. Simmis needs a separately admitted host evaluator
   before offering the same artifact evaluation through its MCP."
  (:refer-clojure :exclude [run!])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.experiment.runner :as runner]
            [dvergr.benchmarks.company-cash.model :as model]
            [dvergr.benchmarks.company-cash.generate :as generate]
            [dvergr.catalog.room :as catalog]
            [hasch.core :as hasch]))

(def model-task
  (str "\n\nReusable model evaluation: write /out/model/receivables.clj as one self-contained Clojure namespace. "
       "It must define fit(observations, seed, budget), returning portable EDN maps/vectors, and "
       "simulate(fitted, actions, seed, n), returning a vector of exactly n maps with "
       ":final-cash, :final-value and :ruin?. Read /inputs/observations.edn for the public "
       ":history, :notes and :company contract. fit receives that whole map; simulate receives an "
       "invoice-id-to-action map. Use explicit seeds; java.util.Random with an explicit seed is available. "
       "The evaluator runs this file alone, with Math and safe core but no filesystem, "
       "network or namespace load path. Do not rely on other source files. "
       "The model must handle previously unseen observation maps and actions, including all invoices factored. "
       "fit receives {:max-samples 256}. By default each evaluation has 10 seconds and a cooperative "
       "4 GiB cumulative allocation limit across fitting and repeated simulations; keep the data "
       "your model holds small (thousands of values, not millions). "
       "Fit and simulate must reproduce results for identical inputs and seeds. "
       "The frozen code is also refitted on previously unseen businesses with new histories, notes and customer names. "
       "The model is evaluated under the fixed no-action policy on independent hidden future draws. "
       "Its executable validity and forecast skill are measured separately from /out/answer.edn; "
       "an answer without an executable model scores zero. The world is synthetic, with no claim of market fidelity."))

(defn- with-model-contract [b]
  (let [definition (-> (:definition b)
                       (update :task str model-task)
                       (assoc :outputs ["/out/answer.edn" "/out/model/receivables.clj"]))]
    (catalog/bundle (:name b) (assoc (:files b) "workflow.edn" (pr-str definition)))))

(defn- evaluator [b opts]
  (let [base (catalog/evaluator b opts)
        options (merge model/policy (:model-options opts))
        _ (when-not (and (integer? (:probe-count options)) (<= 1 (:probe-count options) 4))
            (throw (ex-info "Transfer evaluation needs 1 to 4 independent business probes" {})))
        probes (into {} (for [[index id] (map-indexed vector (keys (:cases b)))]
                          [id (delay (mapv (fn [probe]
                                             (let [probe-id (str id "-transfer-" probe)]
                                               (generate/generate-case probe-id
                                                                       (generate/varied-spec :held-out (+ index probe) (+ (:probe-seed options) (* index 1000))))))
                                           (range (:probe-count options))))]))
        source-id (str (hasch/uuid
                        (mapv #(slurp (io/resource (str "dvergr/benchmarks/company_cash/" % ".clj")))
                              ["kernel" "scoring" "model" "generate" "experiment"])))]
    (evaluation/make-evaluator
     {:id :company-cash/executable-model :version 1
      :basis {:bundle (str (:id b)) :policy options :source source-id}
      :capture (:capture base)
      :observe (fn [{:keys [environment result timed-out?] :as event}]
                 (let [evidence ((:observe base) event)
                       runnable? (or (= :completed (:run/status result))
                                     (and timed-out? (get-in b [:definition :score-on-timeout])))
                       observations (some-> (catalog/case-of b environment)
                                            (get-in [:fixtures "/inputs/observations.edn"]) edn/read-string)]
                   (assoc evidence :model-verdict
                          (if runnable?
                            (model/score-panels (:files evidence) (catalog/gold-of b environment) observations options
                                                @(get probes (get-in environment [:environment/metadata :case])))
                            {:checks {:model-executable? false :completed? false} :reward 0.0}))))
      :verify (fn [_ evidence] (:model-verdict evidence))})))

(defn experiment-plan
  "A plan for the existing experiment runner. Select a declared split before
   optionally taking :cases N. All model settings and evaluator policy enter
   experiment/input identities. This performs no model dispatch."
  [bundle {:keys [split cases] :or {split :development} :as opts}]
  (let [b (with-model-contract bundle)
        splits (some-> (get-in b [:files "benchmark.edn"]) edn/read-string :splits)
        ids (or (get splits split)
                (throw (ex-info "Unknown company-cash split" {:split split :known (keys splits)})))
        selected (if cases (vec (take cases ids)) ids)
        _ (when (empty? selected) (throw (ex-info "No company-cash cases selected" {:split split})))
        plan (catalog/experiment-plan b (assoc opts :cases selected))
        setup (get-in plan [:capabilities :world-setup])
        ev (evaluator b opts)]
    (assoc plan :benchmark :company-cash-model
           :capabilities {:world-setup setup :evaluator ev}
           :environments (mapv #(catalog/environment b setup ev opts %) selected)
           :metadata {:split split :model-policy (merge model/policy (:model-options opts))})))

(defn run!
  "Run an artifact-model experiment in its own JVM/home. :bundle-dir is an
   exported company-cash bundle; :models names fixed candidate settings."
  [{:keys [bundle-dir] :as opts}]
  (let [bundle (catalog/read-dir bundle-dir)
        calibration (catalog/calibrate bundle)]
    (when-not (:ok? calibration)
      (throw (ex-info "Company-cash checker calibration failed" {:problems (:problems calibration)})))
    (runner/run!
     (merge (select-keys opts [:dir :daemon :repetitions :parallelism :experiment-id
                               :fault-retries :preflight :allowance :claude-cli :claude-env])
            (experiment-plan bundle opts)))))
