(ns dvergr.benchmarks.spreadsheetbench.experiment
  "SpreadsheetBench experiments (`dvergr.agent.experiment.runner`), over the
   tasks the oracle certifies (`oracle-file`, written by
   `spreadsheetbench.oracle/report`):

     (run! {:dir \"~/.cache/dvergr-bench/sb-dev\" :split :dev :sample 20 :repetitions 2
            :candidates [{:id :luna :model \"codex-subscription-luna\"}]})

   `:search` runs every episode as an SMC search over episodes instead
   (`spreadsheetbench.smc/run` options, e.g. {:particles 4 :twist :oracle};
   `:judge-model` a model spec for the `:judge` potential, e.g.
   {:model \"codex-subscription-sol-6.1\" :effort :medium}. `:export` a file
   that collects the searches' trajectories as finetune-rstr training records
   (`smc/training-records`), one EDN record per line."
  (:refer-clojure :exclude [run!])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [dvergr.agent.experiment.runner :as runner]
            [dvergr.benchmarks.live :as live]
            [dvergr.benchmarks.pyjson :as pj]
            [dvergr.benchmarks.spreadsheetbench.core :as sb]
            [dvergr.benchmarks.spreadsheetbench.provider :as provider]
            [dvergr.benchmarks.spreadsheetbench.smc :as smc]
            [hasch.core :as hasch])
  (:import [java.util ArrayList Collections Random]))

(defn oracle-file []
  (str (System/getProperty "user.home") "/.cache/dvergr-bench/spreadsheetbench/oracle-verified-400.edn"))

(defn certified-ids
  "The ids the oracle certified, or nil without an oracle run."
  []
  (let [f (io/file (oracle-file))]
    (when (.exists f)
      (set (keep #(when (#{:certified :certified-gold} (:status %)) (:id %))
                 (:results (edn/read-string {:default tagged-literal} (slurp f))))))))

(defn split-of
  "`:dev` (a third) or `:eval`, fixed by the digest of the task id."
  [{:keys [id]}]
  (if (< (Long/parseLong (subs (pj/sha256-hex (str "spreadsheetbench/" id)) 0 8) 16) (quot 0x100000000 3)) :dev :eval))

(defn select-tasks
  [{:keys [split sample seed ids] :or {seed 20260930}}]
  (let [certified (or (certified-ids) (throw (ex-info "Run the oracle first (spreadsheetbench.oracle/report)" {})))
        ts (cond->> (filter #(certified (:id %)) (sb/tasks))
             split (filter #(= split (split-of %)))
             ids (filter #((set ids) (:id %))))]
    (if (or (nil? sample) (>= sample (count ts)))
      (vec ts)
      (let [l (ArrayList. ^java.util.Collection (vec ts))]
        (Collections/shuffle l (Random. (long seed)))
        (vec (sort-by :id (take sample l)))))))

(defn- export-records!
  "Append `records` to the EDN-lines file `path`, one per line."
  [path records]
  (locking export-records!
    (with-open [w (io/writer (io/file path) :append true)]
      (doseq [r records] (.write w (pr-str r)) (.write w "\n")))))

(defn- search-episode
  "The protocol's episode as an SMC search (`smc/run`) with `search`'s
  options."
  [search export]
  (let [judge (some-> (:judge-model search) live/model-generate)]
    (fn [task {:keys [generate max-turns cancelled?]}]
      (smc/run task (cond-> (assoc search :generate generate :max-turns max-turns :cancelled? cancelled?)
                      judge (assoc :judge judge)
                      export (assoc :on-trajectories
                                    #(export-records! export (smc/training-records task %))))))))

(defn run!
  [{:keys [candidates agent-generate max-turns search export] :or {max-turns 30} :as opts}]
  (let [selected (select-tasks opts)
        caps (provider/capabilities selected
                                    (cond-> {:agent-generate agent-generate}
                                      search (assoc :run-episode (search-episode search export))))]
    (runner/run!
     (merge
      (select-keys opts [:dir :repetitions :parallelism :experiment-id :preflight :allowance
                         :usage-pause-threshold :usage-retries])
      {:benchmark :spreadsheetbench
       :capabilities caps
       :environments (mapv #(provider/environment-def % caps {:max-turns max-turns}) selected)
       :team (provider/candidate-roster candidates)
       :models candidates
       :dataset {:id (keyword "spreadsheetbench" (str "tasks-" (hasch/uuid (mapv :id selected))))
                 :metadata {:upstream "SpreadsheetBench verified_400" :tasks (count selected)}}
       :metadata {:sample (:sample opts) :seed (:seed opts) :split (:split opts)
                  :search (dissoc search :generate)}}))))
