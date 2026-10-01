(ns dvergr.catalog.room-run
  "Benchmark a workflow bundle from a local directory, without a daemon:

     clojure -M -m dvergr.catalog.room-run path/to/competitors \\
       --models claude-haiku-4-5,codex-subscription-luna --repetitions 2 --out runs/competitors

   The directory is a bundle (workflow.edn, checker.clj, gold.edn, fixtures/),
   e.g. an export from `catalog_export` unpacked. The experiment's state (its
   rooms, Attempts, Scorecard) lives under `--out`; the Scorecard is printed."
  (:require [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [dvergr.catalog.room :as room-wf]))

(def ^:private options
  [[nil "--models MODELS" "Comma-separated model ids or aliases" :parse-fn #(str/split % #",")]
   [nil "--repetitions N" "Attempts per model" :default 1 :parse-fn #(Integer/parseInt %)]
   [nil "--budget-dollars USD" "Budget per attempt" :parse-fn #(Double/parseDouble %)]
   [nil "--timeout-ms MS" "Per attempt" :parse-fn #(Integer/parseInt %)]
   [nil "--cases N" "A dataset's first N cases of a fixed shuffle (default all)" :parse-fn #(Integer/parseInt %)]
   [nil "--out DIR" "Where the experiment's state is kept" :default "workflow-runs"]
   [nil "--check" "Only check and calibrate the bundle"]
   ["-h" "--help"]])

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args options)
        [dir] arguments]
    (cond
      (or (:help options) (not dir) (seq errors))
      (do (run! println errors) (println "Usage: room-run <bundle-dir> --models m1,m2 [options]") (println summary)
          (System/exit (if (:help options) 0 1)))

      :else
      (let [b (room-wf/read-dir dir)]
        (println "Bundle" (:name b) (str (:id b)))
        (if (:check options)
          (prn (select-keys (room-wf/calibrate b) [:ok? :problems]))
          (let [{:keys [scorecard failed-cells]}
                (room-wf/experiment! b (cond-> {:dir (str (:out options) "/" (:name b) "-" (subs (str (random-uuid)) 0 8))
                                                :models (:models options)
                                                :repetitions (:repetitions options)}
                                         (:budget-dollars options) (assoc :budget-dollars (:budget-dollars options))
                                         (:timeout-ms options) (assoc :timeout-ms (:timeout-ms options))
                                         (:cases options) (assoc :cases (:cases options))))]
            (doseq [s (:scorecard/summary scorecard)]
              (println (format "%-40s reward %.3f  passed %d/%d  $%.4f"
                               (name (:candidate/id s)) (double (or (:reward-mean s) 0))
                               (:passed-count s 0) (:attempt-count s 0)
                               (/ (double (get-in s [:spend :microdollars] 0)) 1e6))))
            (when (pos? (or failed-cells 0)) (println failed-cells "cells failed"))))
        (shutdown-agents)
        (System/exit 0)))))
