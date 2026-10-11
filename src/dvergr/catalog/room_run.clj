(ns dvergr.catalog.room-run
  "Benchmark a workflow bundle from a local directory, the way an MCP client
   does it:

     clojure -M -m dvergr.catalog.room-run path/to/competitors \\
       --models claude-haiku-4-5,codex-subscription-luna --repetitions 2 --home runs/competitors

   The directory is a bundle (workflow.edn, checker.clj, gold.edn, fixtures/),
   e.g. an export from `catalog_export` unpacked. `--home` is a dvergr home
   (default workflow-runs/<bundle name>): this process opens it with a
   headless daemon, creates the room <bundle name> there (once), writes the
   bundle into its workspace as workflows/<bundle name>/ (`room_write`), and
   runs `catalog_benchmark` into that room as the experiment <bundle name>.
   Running again resumes it: cells with a verdict are kept, faulted ones run
   again. The Scorecard is printed (billed and list-price cost) and the
   report (`experiment_report`) printed to stdout, or written to `--report
   FILE`; the exit code is 1 when cells did not finish. Nothing is written
   outside the home's stores."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.experiment.runner :as runner]
            [dvergr.catalog.room :as room-wf]
            [dvergr.ops :as ops]
            [dvergr.rooms.files :as room-files]
            [org.replikativ.spindel.engine.core :as ec]))

(def ^:private options
  [[nil "--models MODELS" "Comma-separated model ids or aliases" :parse-fn #(str/split % #",")]
   [nil "--repetitions N" "Attempts per model" :default 1 :parse-fn #(Integer/parseInt %)]
   [nil "--budget-dollars USD" "Budget per attempt" :parse-fn #(Double/parseDouble %)]
   [nil "--timeout-ms MS" "Per attempt" :parse-fn #(Integer/parseInt %)]
   [nil "--cases N" "A dataset's first N cases of a fixed shuffle (default all)" :parse-fn #(Integer/parseInt %)]
   [nil "--home DIR" "The dvergr home the experiment is kept in (default workflow-runs/<bundle name>)"]
   [nil "--report FILE" "Write the report to FILE instead of printing it"]
   [nil "--check" "Only check and calibrate the bundle (in its room)"]
   ["-h" "--help"]])

;; ---------------------------------------------------------------------------
;; The bundle into the room, through room_files / room_write
;; ---------------------------------------------------------------------------

(defn- room-tree
  "Every file path under `dir` of `room`'s workspace (relative to the
   workspace root, no leading slash), through `room_files`."
  [daemon room dir]
  (loop [todo [dir] acc []]
    (if-let [[d & more] (seq todo)]
      (let [entries (loop [offset 0 acc []]
                      (let [{:keys [entries next-offset]}
                            (try (ops/invoke daemon :room/files {:room room :path d :offset offset})
                                 (catch clojure.lang.ExceptionInfo e
                                   (if (= :dvergr.rooms.files/not-found (:type (ex-data e))) {} (throw e))))
                            acc (into acc entries)]
                        (if next-offset (recur next-offset acc) acc)))]
        (recur (into (vec more) (keep #(when (= "dir" (name (:kind %))) (:path %)) entries))
               (into acc (keep #(when (= "file" (name (:kind %))) (:path %)) entries))))
      (mapv #(str/replace % #"^/+" "") acc))))

(defn- batches
  "`files` (`{path content}`) in batches one `room_write` takes."
  [files]
  (let [size #(count (.getBytes (str (get (val %) "text" "")) "UTF-8"))]
    (reduce (fn [bs e]
              (let [cur (peek bs)]
                (if (and (seq cur)
                         (or (<= room-files/max-batch-files (count cur))
                             (< (* 3/4 room-files/max-batch-bytes) (+ (size e) (reduce + 0 (map size cur))))))
                  (conj bs [e])
                  (conj (pop bs) (conj cur e)))))
            [[]] (sort-by key files))))

(defn sync-bundle!
  "Make workflows/<name>/ of `room` exactly `files` (`{relative-path text}`):
   written, and what is no longer in the bundle removed, through the gated
   `room_write` (a commit per batch; what already is as given commits
   nothing). Returns the commits."
  [daemon room name files]
  (let [dir (str "workflows/" name)
        wanted (into {} (map (fn [[p t]] [(str dir "/" p) {"text" t}])) files)
        stale (remove #(contains? wanted %) (room-tree daemon room dir))
        b (room-wf/bundle name files)
        write! (fn [batch]
                 (when (seq batch)
                   (:commit (ops/invoke daemon :room/write
                                        {:room room :files (into {} batch)
                                         :message (str "Workflow bundle " name " " (subs (str (:id b)) 0 8))}))))]
    ;; removals first, on their own: a file the bundle turned into a
    ;; directory (fixtures/docs → fixtures/docs/q.txt) is gone before its
    ;; descendants are written
    (into [] (keep write!)
          (concat (batches (zipmap stale (repeat nil))) (batches wanted)))))

;; ---------------------------------------------------------------------------
;; The benchmark, through catalog_benchmark / job_status / experiment_report
;; ---------------------------------------------------------------------------

(defn- wait-for
  "The job's status once it is over and settled (its fixture room archived):
   the home closes after this."
  [daemon job]
  (loop []
    (let [st (ops/invoke daemon :job/status {:job (:id job) :wait-ms 20000})]
      (if (or (= "running" (:status st)) (false? (:settled st))) (recur) st))))

(defn- cell-count
  "How many cells the experiment of `b` on `models` has."
  [b {:keys [models repetitions cases]}]
  (* (count models) (or repetitions 1)
     (let [n (count (:cases b))]
       (cond (zero? n) 1
             (number? cases) (min n cases)
             ;; case ids, as `room-wf/experiment-plan` selects them
             (seq cases) (count (filter (set cases) (keys (:cases b))))
             :else n))))

(defn- verdicts [daemon room experiment-id]
  (->> (:experiments (ops/invoke daemon :experiment/progress {:room room}))
       (filter #(= experiment-id (str (:experiment/content-id %))))
       (mapcat :candidates) (map :verdicts) (reduce + 0)))

(defn benchmark!
  "Benchmark bundle `b` (with its `:files`, e.g. from `room-wf/read-dir`) in
   the home `home`, through the ops an MCP client calls: the room <name>
   (created once), the bundle written into its workspace, `catalog_benchmark`
   into that room as the experiment <name> (so running again resumes it),
   once more when cells faulted (`:fault-retries`, default 1), and the report
   from `experiment_report`. `:parallelism`, `:params` and `:prompt` go to
   `catalog_benchmark`; `:judge-fn` and `:tier` cannot cross an op and are
   refused. With `:check?`, only `catalog_calibrate`.
   Returns `{:home :room :job :experiment :scorecard (the Scorecard) :failed-cells
   :report (Markdown)}`, or `{:home :room :calibration}`."
  [{:keys [name files] :as b} {:keys [home models repetitions budget-dollars timeout-ms cases check? fault-retries
                                      parallelism params prompt]
                               :or {fault-retries 1} :as opts}]
  (when-not (re-matches #"[a-z0-9][a-z0-9-]*" (str name))
    (throw (ex-info (str "A bundle's name (its directory's) is lowercase letters, digits and dashes: " name)
                    {:type ::invalid-name :name name})))
  (when-not files
    (throw (ex-info "The bundle has no :files (read it with read-dir or bundle)" {:type ::no-files})))
  ;; what crosses the op is data: a function, or trust granted by the
  ;; caller, cannot
  (when-let [unsupported (seq (filter #(contains? opts %) [:judge-fn :tier]))]
    (throw (ex-info (str "Not through catalog_benchmark: " (vec unsupported)
                         " (promote the bundle for its tier; a judge is the bundle's :judge model)")
                    {:type ::unsupported-options :options (vec unsupported)})))
  (runner/with-home home
    (fn [daemon]
      (when-not (ops/resolve-room daemon name)
        (ops/invoke daemon :room/create {:title (str "Workflow " (get-in b [:definition :title] name)) :slug name}))
      (sync-bundle! daemon name name files)
      ;; what the room now holds is exactly the bundle given: the benchmark
      ;; reads it back from there
      (let [{:keys [ok bundle problems]} (ops/invoke daemon :catalog/check {:room name :name name})]
        (when-not (and ok (= (str (:id b)) bundle))
          (throw (ex-info (str "The room's workflows/" name "/ is not the bundle given"
                               (when (seq problems) (str ": " (str/join "; " problems))))
                          {:type ::bundle-mismatch :expected (str (:id b)) :found bundle :problems problems}))))
      (if check?
        {:home (str home) :room name
         :calibration (ops/invoke daemon :catalog/calibrate {:room name :name name})}
        (let [args (cond-> {:workflow (str name "/" name) :models (vec models) :room name :experiment name
                            :repetitions (or repetitions 1)}
                     budget-dollars (assoc :budget-dollars (double budget-dollars))
                     timeout-ms (assoc :timeout-ms timeout-ms)
                     (number? cases) (assoc :cases cases)
                     (and cases (not (number? cases))) (assoc :case-ids (vec cases))
                     parallelism (assoc :parallelism parallelism)
                     params (assoc :params params)
                     prompt (assoc :prompt prompt))
              report-of #(ops/invoke daemon :experiment/report
                                     {:room name :title (get-in b [:definition :title]) :experiment %})
              [done exp-id report]
              (loop [n 0]
                (let [{exp-id :experiment :as job} (ops/invoke daemon :catalog/benchmark args)
                      st (wait-for daemon job)
                      report (report-of exp-id)]
                  ;; cells that faulted (a lost connection, a provider
                  ;; failure) run once more: the resume keeps the rest
                  ;; not when admission refused cells (an allowance): each
                  ;; call would grant a fresh share
                  (if (and (= "completed" (:status st)) (not= "refused" (:reason st))
                           (not (:complete report)) (< n fault-retries))
                    (recur (inc n))
                    [st exp-id report])))
              r (ops/resolve-room daemon name)
              ;; detached evaluation cleanup, before the home closes
              _ (try (evaluation/await-cleanups! r) (catch Throwable _ nil))
              scorecard (when-let [id (:scorecard report)]
                          (binding [ec/*execution-context* (:ctx r)]
                            (experiment/scorecard r (parse-uuid id))))]
          {:home (str home) :room name :job done :experiment exp-id
           :scorecard scorecard
           :failed-cells (if scorecard 0 (- (cell-count b opts) (verdicts daemon name exp-id)))
           :report (:markdown report)})))))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args options)
        [dir] arguments]
    (cond
      (or (:help options) (not dir) (seq errors) (and (not (:check options)) (empty? (:models options))))
      (do (run! println errors) (println "Usage: room-run <bundle-dir> --models m1,m2 [options]") (println summary)
          (System/exit (if (:help options) 0 1)))

      :else
      (let [b (room-wf/read-dir dir)
            home (or (:home options) (str "workflow-runs/" (:name b)))
            _ (println "Bundle" (:name b) (str (:id b)) "in home" home)
            {:keys [calibration scorecard failed-cells report job]}
            (benchmark! b (cond-> {:home home :models (:models options) :repetitions (:repetitions options)
                                   :check? (:check options)}
                            (:budget-dollars options) (assoc :budget-dollars (:budget-dollars options))
                            (:timeout-ms options) (assoc :timeout-ms (:timeout-ms options))
                            (:cases options) (assoc :cases (:cases options))))]
        (if (:check options)
          (do (prn (select-keys calibration [:ok? :problems]))
              (shutdown-agents)
              (System/exit (if (:ok? calibration) 0 1)))
          (do
            (doseq [s (:scorecard/summary scorecard)]
              (println (format "%-40s reward %.3f  passed %d/%d  billed $%.4f  list price $%.4f"
                               (name (:candidate/id s)) (double (or (:reward-mean s) 0))
                               (:passed-count s 0) (:attempt-count s 0)
                               (/ (double (get-in s [:spend :microdollars] 0)) 1e6)
                               (/ (double (get-in s [:spend :notional-microdollars]
                                                  (get-in s [:spend :microdollars] 0))) 1e6))))
            (when (not= "completed" (:status job)) (println "The benchmark job" (:status job) (str (:error job))))
            (when (pos? (or failed-cells 0)) (println failed-cells "cells did not finish"))
            ;; the report a pilot hands over: the room's, read through the op
            (if-let [f (:report options)]
              (do (io/make-parents (io/file f))
                  (spit f report)
                  (println "Report:" f))
              (println (str "\n" report)))
            (shutdown-agents)
            ;; unfinished cells are a failed run: run again to resume them
            (System/exit (if (or (pos? (or failed-cells 0)) (not= "completed" (:status job))) 1 0))))))))
