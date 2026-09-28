(ns dvergr.benchmarks.automationbench.experiment
  "AutomationBench experiments as certified Dvergr experiments
   (`dvergr.agent.experiment.runner`).

     (run! {:dir \".dvergr/benchmarks/automationbench-smoke\"
            :domains [\"sales\" \"finance\"] :sample 3
            :candidates [{:id :luna :model \"codex-subscription-luna\"}]})

   The public benchmark is 600 tasks (100 per domain; `simple`, 200 more, is
   a tool-use baseline outside the score). `:sample` takes that many per
   domain, the same ones for a given `:seed`."
  (:refer-clojure :exclude [run!])
  (:require [dvergr.agent.experiment.runner :as runner]
            [dvergr.benchmarks.automationbench.provider :as provider]
            [dvergr.benchmarks.automationbench.sidecar :as sc]
            [hasch.core :as hasch])
  (:import [java.util ArrayList Collections Random]))

(def public-domains ["sales" "marketing" "operations" "support" "finance" "hr"])

(defn- sample-of [tasks n seed]
  (if (or (nil? n) (>= n (count tasks)))
    (vec tasks)
    (let [shuffled (ArrayList. ^java.util.Collection (vec tasks))]
      (Collections/shuffle shuffled (Random. (long seed)))
      (let [chosen (set (map #(get % "id") (take n shuffled)))]
        (filterv #(chosen (get % "id")) tasks)))))

(defn select-tasks
  "The tasks an experiment runs, in upstream order: `:task-ids`
   (`[domain id]` pairs), or `:sample` per domain of `:domains`."
  [sidecar {:keys [domains task-ids sample seed] :or {seed 20260928}}]
  (let [domains (or domains (distinct (map first task-ids)) public-domains)
        all (sc/tasks sidecar domains)]
    (if task-ids
      (filterv #((set task-ids) [(get % "domain") (get % "id")]) all)
      (into [] (mapcat (fn [domain]
                         (sample-of (filterv #(= domain (get % "domain")) all) sample seed)))
            domains))))

(defn run!
  "Run (or resume) an AutomationBench experiment; see
   `dvergr.agent.experiment.runner/run!` for the directory, resume and Claude
   Code options. `:toolset` \"api\" (upstream's default) or
   \"limited_zapier\"; `:max-turns` 50 as upstream."
  [{:keys [candidates agent-generate toolset max-turns timeout-ms]
    :or {toolset "api" max-turns 50 timeout-ms (* 20 60 1000)} :as opts}]
  (let [sidecar (sc/shared!)
        selected (select-tasks sidecar opts)
        caps (provider/capabilities {:sidecar sidecar :agent-generate agent-generate})]
    (runner/run!
     (merge
      (select-keys opts [:dir :repetitions :parallelism :experiment-id :claude-cli :claude-env
                         :host-context-note :usage-pause-threshold :usage-retries
                         :preflight :allowance])
      {:benchmark :automationbench
       :capabilities caps
       :environments (mapv #(provider/environment-def % caps {:toolset toolset :max-turns max-turns
                                                              :timeout-ms timeout-ms})
                           selected)
       :team (provider/candidate-roster candidates)
       :models candidates
       :dataset {:id (keyword "automationbench"
                              (str "tasks-" (hasch/uuid (mapv (juxt #(get % "domain") #(get % "id")
                                                                    #(get % "contract"))
                                                              selected))))
                 :metadata {:upstream sc/upstream :toolset toolset
                            :domains (vec (distinct (map #(get % "domain") selected)))
                            :tasks (count selected)}}
       :metadata {:sample (:sample opts) :seed (:seed opts) :max-turns max-turns}}))))
