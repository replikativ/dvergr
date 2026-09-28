(ns dvergr.benchmarks.automationbench.experiment
  "AutomationBench experiments as certified Dvergr experiments
   (`dvergr.agent.experiment.runner`).

     (run! {:dir \".dvergr/benchmarks/automationbench-smoke\"
            :domains [\"sales\" \"finance\"] :sample 3
            :candidates [{:id :luna :model \"codex-subscription-luna\"}]})

   The public benchmark is 600 tasks (100 per domain; `simple`, 200 more, is
   a tool-use baseline outside the score). `:sample` takes that many per
   domain, the same ones for a given `:seed`.

   Splits: upstream has none, so `:split :dev` is a fixed third of each
   domain's tasks (by the digest of `domain/id`) and `:split :eval` the other
   two thirds. Tune on `:dev` (`tune!`), report on `:eval`; no task is in
   both."
  (:refer-clojure :exclude [run!])
  (:require [dvergr.agent.experiment.runner :as runner]
            [dvergr.agent.experiment.select :as select]
            [dvergr.benchmarks.pyjson :as pj]
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

(defn split-of
  "`:dev` or `:eval` for a task (`{\"domain\" \"id\"}`): a third of the tasks
   are `:dev`, fixed by the digest of `domain/id`."
  [{:strs [domain id]}]
  (if (< (Long/parseLong (subs (pj/sha256-hex (str domain "/" id)) 0 8) 16) (quot 0x100000000 3))
    :dev
    :eval))

(defn select-tasks
  "The tasks an experiment runs, in upstream order: `:task-ids`
   (`[domain id]` pairs), or `:sample` per domain of `:domains`."
  [sidecar {:keys [domains task-ids sample seed split] :or {seed 20260928}}]
  (let [domains (or domains (seq (distinct (map first task-ids))) public-domains)
        all (cond->> (sc/tasks sidecar domains)
              split (filterv #(= split (split-of %))))]
    (if task-ids
      (filterv #((set task-ids) [(get % "domain") (get % "id")]) all)
      (into [] (mapcat (fn [domain]
                         (sample-of (filterv #(= domain (get % "domain")) all) sample seed)))
            domains))))

(defn run!
  "Run (or resume) an AutomationBench experiment; see
   `dvergr.agent.experiment.runner/run!` for the directory, resume and Claude
   Code options (`:preflight`, `:allowance`). `:split` `:dev`/`:eval`
   restricts the tasks. `:toolset` \"api\" (upstream's default) or
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

(defn tune!
  "Choose among `variants` (candidate specs) of `baseline` (a candidate spec)
   on the development split: run all of them on `:sample` dev tasks per
   domain (default 2) with `:repetitions` (default 2), then
   `experiment.select/choose`: the cheapest variants whose mean reward is
   within `:margin` of the baseline's. Returns `{:choice :shortlist :specs
   :selection :run}`; `:specs` are the shortlisted candidate specs, to confirm
   against the baseline on `:split :eval`."
  [{:keys [baseline variants sample repetitions margin] :or {sample 2 repetitions 2 margin 0.05}
    :as opts}]
  (let [specs (into [baseline] variants)
        r (run! (merge (dissoc opts :baseline :variants :margin)
                       {:split :dev :sample sample :repetitions repetitions :candidates specs}))
        selection (when-let [entries (seq (get-in r [:scorecard :scorecard/entries]))]
                    (select/choose entries (:id baseline) (mapv :id variants) {:margin margin}))]
    {:choice (:choice selection)
     :shortlist (:shortlist selection)
     :specs (filterv #(contains? (set (:shortlist selection)) (:id %)) specs)
     :selection selection
     :run (dissoc r :scorecard)}))
