(ns dvergr.benchmarks.bfcl.experiment
  "BFCL experiments as certified Dvergr experiments
   (`dvergr.benchmarks.runner`).

     (run! {:dir \".dvergr/benchmarks/bfcl-smoke\"
            :categories [\"simple_python\" \"parallel\" \"irrelevance\"]
            :sample 5
            :candidates [{:id :sonnet :model \"claude-code-sonnet\"}]})

   A full pass over the eleven categories is 3491 model calls per candidate.
   `:sample` takes that many tasks per category, the same ones for a given
   `:seed`, so a small slice is a stable thing to iterate on."
  (:refer-clojure :exclude [run!])
  (:require [dvergr.benchmarks.bfcl.core :as bfcl]
            [dvergr.benchmarks.bfcl.provider :as provider]
            [dvergr.benchmarks.runner :as runner]
            [hasch.core :as hasch])
  (:import [java.util ArrayList Collections Random]))

(defn- sample-of [tasks n seed]
  (if (or (nil? n) (>= n (count tasks)))
    (vec tasks)
    (let [shuffled (ArrayList. ^java.util.Collection (vec tasks))]
      (Collections/shuffle shuffled (Random. (long seed)))
      ;; upstream order within the sample
      (let [chosen (set (map :id (take n shuffled)))]
        (filterv #(chosen (:id %)) tasks)))))

(defn select-tasks
  "The tasks an experiment runs, in upstream order: `:task-ids`, or `:sample`
   per category of `:categories`."
  [{:keys [categories task-ids sample seed root] :or {seed 20260921}}]
  (let [categories (or categories (vec (sort (keys bfcl/categories))))]
    (into []
          (mapcat (fn [category]
                    (let [tasks (bfcl/load-category category (when root {:root root}))]
                      (if task-ids
                        (filterv #((set task-ids) (:id %)) tasks)
                        (sample-of tasks sample seed)))))
          categories)))

(defn run!
  "Run (or resume) a BFCL experiment; see `dvergr.benchmarks.runner/run!` for
   the directory, resume and Claude Code options. `:agent-generate`
   `(fn [task]) -> generate fn` replaces the candidates' models."
  [{:keys [candidates agent-generate timeout-ms] :or {timeout-ms (* 5 60 1000)} :as opts}]
  (let [selected (select-tasks opts)
        tasks (into {} (map (juxt :id identity)) selected)
        caps (provider/capabilities tasks {:agent-generate agent-generate})]
    (runner/run!
     (merge
      (select-keys opts [:dir :repetitions :parallelism :experiment-id :claude-cli :claude-env
                         :host-context-note :usage-pause-threshold :usage-retries])
      {:benchmark :bfcl
       :capabilities caps
       :environments (mapv #(provider/environment-def % caps {:timeout-ms timeout-ms}) selected)
       :team (provider/candidate-roster candidates)
       :models candidates
       :dataset {:id (keyword "bfcl" (str "tasks-" (hasch/uuid (mapv :id selected))))
                 :metadata {:upstream bfcl/upstream
                            :categories (vec (distinct (map :category selected)))
                            :tasks (count selected)}}
       :metadata {:sample (:sample opts) :seed (:seed opts)}}))))
