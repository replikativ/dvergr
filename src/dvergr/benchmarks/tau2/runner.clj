(ns dvergr.benchmarks.tau2.runner
  "Headless tau2 batch runs: tasks x trials, bounded parallelism, pass^k.

   Each episode is appended as one EDN line (full transcript, termination,
   grading, usage) as soon as it finishes, so an interrupted run keeps its
   completed episodes and `resume` skips them. The run header records the
   exact upstream revision, split, models, and grading configuration: a
   number without that identity is not comparable to anything.

     (def dom (t2/load-domain \"retail\"))
     (run! dom {:split \"train\" :trials 1 :parallelism 2
                :agent {:model \"claude-code-sonnet\"}
                :user {:model \"claude-code-sonnet\"}
                :judge {:model \"claude-code-sonnet\"}})"
  (:refer-clojure :exclude [run!])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.benchmarks.tau2.core :as t2]
            [dvergr.benchmarks.tau2.live :as live])
  (:import [java.util.concurrent Executors TimeUnit]))

(def default-dir ".dvergr/benchmarks/tau2")

(defn- successful? [reward] (<= (- 1 1e-6) (double reward) (+ 1 1e-6)))

(defn- comb [n k]
  (if (or (neg? k) (> k n))
    0
    (reduce (fn [acc i] (/ (* acc (- n i)) (inc i))) 1 (range k))))

(defn pass-hat-k
  "tau2/τ-bench pass^k: mean over tasks of C(successes, k) / C(trials, k)."
  [episodes k]
  (let [by-task (vals (group-by :task-id episodes))]
    (when (seq by-task)
      (/ (reduce + (map (fn [eps]
                          (let [n (count eps)
                                c (count (filter #(successful? (get-in % [:grade :reward])) eps))]
                            (if (< n k) 0 (/ (comb c k) (comb n k)))))
                        by-task))
         (double (count by-task))))))

(defn metrics [episodes]
  (let [trials (if (seq episodes)
                 (apply min (map count (vals (group-by :task-id episodes))))
                 0)]
    {:episodes (count episodes)
     :tasks (count (distinct (map :task-id episodes)))
     :avg-reward (when (seq episodes)
                   (/ (reduce + (map #(get-in % [:grade :reward] 0.0) episodes))
                      (double (count episodes))))
     :pass-hat-k (into (sorted-map) (for [k (range 1 (inc trials))]
                                      [k (pass-hat-k episodes k)]))
     :terminations (frequencies (map :termination episodes))
     :failed (count (filter :failure episodes))}))

(defn read-episodes [dir]
  (let [f (io/file dir "episodes.edn")]
    (if (.exists f)
      (with-open [r (io/reader f)]
        (mapv edn/read-string (remove str/blank? (line-seq r))))
      [])))

(defn- run-one [domain task trial {:keys [agent user judge] :as config}]
  (let [started (System/currentTimeMillis)]
    (try
      (let [episode (t2/run-episode domain task
                                    (merge (select-keys config [:max-steps :max-errors
                                                                :enforce-protocol?])
                                           {:agent agent :user user}))
            grade (t2/grade domain task episode {:judge judge})]
        {:task-id (get task "id")
         :trial trial
         :termination (:termination episode)
         :grade (dissoc grade :termination)
         :steps (:steps episode)
         :tool-errors (:errors episode)
         :messages (:messages episode)
         :usage (:usage episode)
         :duration-ms (- (System/currentTimeMillis) started)})
      (catch Throwable t
        ;; Infrastructure failures are recorded, never scored as model
        ;; reasoning failures (tau2 likewise filters them from metrics).
        {:task-id (get task "id")
         :trial trial
         :failure {:message (.getMessage t)
                   :class (.getName (class t))
                   :data (pr-str (ex-data t))}
         :duration-ms (- (System/currentTimeMillis) started)}))))

(defn run!
  "Run `trials` episodes for each task of `split` (or explicit `:task-ids`).
   Model specs are maps for `live/model-generate`; tests may pass generate
   functions directly as `:agent-fn`/`:user-fn`/`:judge-fn`. Returns the run
   header, metrics, and output directory. Pass `:dir` of an earlier run to
   resume it."
  [domain {:keys [split task-ids trials parallelism dir agent user judge
                  agent-fn user-fn judge-fn]
           ;; Leaderboard methodology: the full `base` split, >= 4 trials.
           :or {split "base" trials 4 parallelism 1}
           :as opts}]
  (let [run-id (str (java.time.LocalDateTime/now))
        dir (io/file (or dir (str default-dir "/" (str/replace run-id ":" "-"))))
        _ (.mkdirs dir)
        tasks (if task-ids
                (mapv #(get-in domain [:tasks %]) task-ids)
                (t2/split-tasks domain split))
        header {:benchmark :tau2
                :domain (:domain domain)
                :upstream t2/upstream
                :split (if task-ids :explicit split)
                :task-ids (mapv #(get % "id") tasks)
                :trials trials
                :agent agent :user user :judge judge
                :retrieval-config (:retrieval-config domain)
                :protocol (select-keys opts [:max-steps :max-errors :enforce-protocol?])
                :initial-db-hash (:initial-db-hash domain)}
        header-file (io/file dir "run.edn")
        _ (when-not (.exists header-file) (spit header-file (pr-str header)))
        done (set (map (juxt :task-id :trial)
                       (remove :failure (read-episodes dir))))
        config (merge opts
                      {:agent (or agent-fn (live/model-generate agent))
                       :user (or user-fn (live/model-generate user))
                       :judge (or judge-fn (when judge (live/model-generate judge)))})
        jobs (for [trial (range trials) task tasks
                   :when (not (done [(get task "id") trial]))]
               [task trial])
        out (io/file dir "episodes.edn")
        lock (Object.)
        pool (Executors/newFixedThreadPool (int parallelism))]
    (try
      (doseq [f (mapv (fn [[task trial]]
                        (.submit pool ^Callable
                                 (fn []
                                   (let [result (run-one domain task trial config)]
                                     (locking lock
                                       (spit out (str (pr-str result) "\n") :append true))
                                     result))))
                      jobs)]
        (.get ^java.util.concurrent.Future f))
      (finally
        (.shutdown pool)
        (.awaitTermination pool 1 TimeUnit/MINUTES)))
    (let [episodes (remove :failure (read-episodes dir))]
      {:dir (str dir)
       :header header
       :metrics (metrics episodes)})))
