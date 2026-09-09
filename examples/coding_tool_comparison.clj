(ns coding-tool-comparison
  "A four-attempt, counterbalanced RSS smoke comparison; not a model ranking.
   Load this file at the host REPL. The caller owns the initialized Room."
  (:require [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.roster :as roster]
            [dvergr.benchmarks.rss :as rss]
            [org.replikativ.spindel.core :as sp]))

(defn plan [model-policy]
  (let [base {:model-policy model-policy
              :prompt "Work on saved files using the tools available to you; use the REPL for evaluation and tests. Once the task is complete, report the result."
              :program {:kind :llm :auto-compact? false :max-model-steps 32}}
        team (-> (roster/make-roster)
                 (roster/make-agent (assoc base :id :repl :tools #{:clojure_eval}))
                 (roster/make-agent (assoc base :id :editing
                                           :tools #{:clojure_eval :clojure_edit :write_file})))
        dataset (experiment/make-dataset {:id :coding/rss-tools
                                          :environments [(rss/definition)]})
        block (fn [id order]
                (experiment/make-experiment
                 {:id id :dataset dataset :repetitions 1
                  :candidates (mapv #(roster/agent team %) order)
                  :metadata {:purpose :smoke-comparison :candidate-order order
                             :token-cap :not-supported :request-fuse 32}}))]
    {:team team
     :blocks [(block :coding/rss-tools-ab [:repl :editing])
              (block :coding/rss-tools-ba [:editing :repl])]}))

(defn run
  "Compose two ordinary experiments serially: REPL, editing, editing, REPL.
   Each Run has the unchanged RSS 180-second deadline. Token usage is measured,
   not capped by this API. No retry, successful-only filtering, or world merge.
   Pass an evaluation cleanup-group and join it at the host operation boundary."
  [room {:keys [team blocks]} cleanup-group]
  (sp/spin
   (loop [remaining blocks results []]
     (if-let [block (first remaining)]
       (let [result (sp/await
                     (experiment/run
                      room team block {rss/verifier-ref (rss/evaluator)}
                      {:parallelism 1 :max-parallelism 1 :max-attempts 2
                       :cleanup-group cleanup-group
                       :world-setups {rss/setup-ref (rss/world-setup)}}))]
         (recur (next remaining) (conj results result)))
       results))))
