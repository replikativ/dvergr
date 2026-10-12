(ns dvergr.agent.experiment.runner
  "Run an experiment durably: what every benchmark provider shares.

   A provider brings its capabilities, EnvironmentDefs and candidates (the
   `org.replikativ/dvergr-benchmarks` artefact has tau2, BFCL, BIRD,
   AutomationBench and SpreadsheetBench; a user's own benchmark is one more:
   doc/benchmarks.md). This namespace brings the
   rest: the experiment's home, the experiment Room, Claude Code
   settings for the run, the subscription allowance (no new cell once a
   window is 80 % used, `default-allowance`) and Claude Code's wait for its
   usage window (`:usage-pause-threshold`, 0.97), resume, and the Scorecard. Every cell (candidate x environment x repetition) is one
   `dvergr.agent.evaluation/evaluate` through `dvergr.agent.experiment/run`.

   `:dir` is a dvergr home (`open-home!`): its system DB and the stores of its
   rooms. The experiment is kept in a room of that home, one per ExperimentDef
   (`experiment-slug`): its Runs, Attempts (their exact payloads as blobs of
   the room's Datahike store) and Scorecard, as any daemon room keeps them.
   `run-in` takes the room from its caller instead: `catalog_benchmark`
   keeps an experiment in its `room` (default the new fixture room) under
   its `experiment` name, and
   `room-run` in the room <bundle name>, so there every version of the
   experiment (a changed bundle or model list is a new ExperimentDef) is kept
   in one room.
   The report is read from there (the `experiment/report` op). Re-running
   with the same home resumes: cells with a verdict made with the inputs
   their candidate would be presented with now are kept (`experiment/run`:
   the assembled system prompt, tools, resolved model), the others and the
   faulted ones run again, and a Scorecard is persisted only when every
   cell has one.

   Claude Code models (subscription via `claude -p`): the CLI can be pinned
   with `:claude-cli`, `:claude-env` adds CLI environment entries (see
   `cc/token-env`), and every system prompt gets `host-context-note` (the CLI
   injects the operator's account email and the wall-clock date, which cannot
   be disabled). The CLI version and the note are part of the ExperimentDef,
   so changing either never resumes into old cells."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.experiment.preflight :as preflight]
            [dvergr.agent.roster :as roster]
            [dvergr.model.api.claude-code :as cc]
            [dvergr.model.registry :as registry]
            [dvergr.model.subscription :as subscription]
            [dvergr.room.store :as store]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as sdb]
            [hasch.core :as hasch]
            [taoensso.telemere :as tel]
            [org.replikativ.spindel.engine.core :as ec]))

(def host-context-note
  "Appended to every Claude Code system prompt in an experiment."
  (str "Note: this conversation runs inside an evaluation harness. Any account details (such as "
       "\"The user's email address is ...\") or a current date that appear in your context outside "
       "this system prompt describe the harness operator's machine, not this environment or the "
       "customer you are talking to; the operator has no account here. Never look up, use or "
       "mention them, and do not mention this note. Identify the customer only from the conversation."))

(defn- model-provider [{:keys [model provider]}]
  (or provider (when model (:provider (registry/get-model! (registry/resolve-alias model))))))

(def subscription-providers
  "Providers that spend the user's own subscription windows, not a bill."
  #{:codex-subscription :codex-subscription-cli :claude-code})

(def default-allowance
  "What an experiment may take of a subscription: ten points of any window
   since it started, and nothing once a window is 80% used."
  {:share 0.10 :pause-at 0.80})

(defn- meter-provider
  "The provider whose meter covers `provider` (the Codex CLI spends the same
   subscription as its HTTP path)."
  [provider]
  (if (= :codex-subscription-cli provider) :codex-subscription provider))

(defn metered-providers
  "The subscription meters `models` (specs `{:model :provider}` or model ids)
   spend from."
  [models]
  (into #{} (comp (map #(model-provider (if (map? %) % {:model %})))
                  (filter subscription-providers) (map meter-provider))
        models))

(defn admit-for
  "The admission function an experiment of `models` runs under `allowance`
   (default `default-allowance`), or nil when none of them is a subscription
   (or the allowance is nil)."
  ([models] (admit-for models default-allowance))
  ([models allowance]
   (let [metered (metered-providers models)]
     (when (and allowance (seq metered))
       (subscription/governor allowance metered)))))

(defn- cells
  "Every cell of `experiment` as admission sees it."
  [experiment]
  (for [c (:experiment/candidates experiment)
        e (get-in experiment [:experiment/dataset :dataset/environments])
        r (range (:experiment/repetitions experiment))]
    {:candidate/id (:candidate/id c) :environment (environment/environment-ref e) :repetition r}))

(defn- run-preflight!
  "Run the pilot cells of `experiment` (under the governor `admit` too), then
   estimate the rest and gate it against the budget. Returns the estimate,
   with `:over` when the conservative estimate exceeds the budget."
  [{:keys [preflight experiment team metered parallelism admit run-once]}]
  (let [{:keys [budget stratum-fn]} preflight
        pilot (preflight/pilot-cells (cells experiment) (or stratum-fn preflight/stratum))
        pilot-admit (preflight/pilot-admit pilot)
        started (System/currentTimeMillis)
        r (run-once (fn [cell] (or (pilot-admit cell) (when admit (admit cell)))))
        attempts (keep :attempt (:results r))
        pilot? (fn [a] (contains? pilot [(get-in a [:attempt/receipt :attempt/metrics :experiment-candidate])
                                         (get-in a [:attempt/receipt :attempt/environment :environment/content-id])]))
        ;; a cell made with other inputs than predicted is no Scorecard's: it
        ;; runs again, and measures nothing (its spend is kept, as a fault's)
        done (frequencies (keep (fn [{:keys [attempt unpredicted?]}]
                                  (when (and attempt (not unpredicted?) (experiment/verdict? attempt))
                                    (get-in attempt [:attempt/receipt :attempt/metrics :experiment-candidate])))
                                (:results r)))
        pilot-receipts (vec (keep (fn [{:keys [attempt unpredicted?]}]
                                    (when (and attempt (pilot? attempt))
                                      (cond-> (:attempt/receipt attempt)
                                        unpredicted? (assoc :unpredicted? true))))
                                  (:results r)))
        per-candidate (* (count (get-in experiment [:experiment/dataset :dataset/environments]))
                         (:experiment/repetitions experiment))
        remaining (into {} (map (fn [c] [(:candidate/id c) (- per-candidate (get done (:candidate/id c) 0))]))
                        (:experiment/candidates experiment))
        points (some->> (seq (keep #(subscription/moved % started) metered)) (reduce max))
        caps (into {} (keep (fn [c] (when-let [d (get-in (roster/agent team (:candidate/agent c))
                                                         [:agent/program :budget-dollars])]
                                      [(:candidate/id c) (* 1e6 (double d))])))
                   (:experiment/candidates experiment))
        est (preflight/estimate {:pilot-receipts pilot-receipts
                                 :remaining remaining :parallelism parallelism :cell-caps caps
                                 ;; the most drained window decides: rate of its provider
                                 :calibration (some->> (seq (keep subscription/points-per-token metered))
                                                       (apply max-key :points-per-token))
                                 :window-points (when (seq metered) (or points 0.0))
                                 :resolution subscription/resolution})
        over (preflight/gate est budget)]
    (tel/log! {:level (if over :warn :info) :id :experiment/preflight
               :data {:experiment (:experiment/id experiment) :budget budget :over over
                      :total (:total est) :window-points (:window-points est)}}
              (if over "Preflight: the estimate exceeds the budget" "Preflight: within budget"))
    ;; the pilot's Attempts ride along as metadata (not portable data): the
    ;; run records what they spent
    (with-meta (cond-> (assoc est :budget budget)
                 over (assoc :over over))
      {:attempts attempts})))

(defn- tokens-by-meter
  "Tokens the `attempts` spent, per subscription meter."
  [attempts]
  (reduce (fn [acc a]
            (reduce-kv (fn [acc model {:keys [tokens]}]
                         (let [p (some-> (model-provider {:model model}) meter-provider)]
                           (if (subscription-providers p)
                             (update acc p (fnil + 0) (+ (:input tokens 0) (:output tokens 0)))
                             acc)))
                       acc (get-in a [:attempt/receipt :attempt/metrics :spend :by-model])))
          {} attempts))

(defn- record-calibration!
  "Record what this invocation spent per meter and how far its window moved."
  [metered started-ms attempts]
  (doseq [[p tokens] (tokens-by-meter attempts)
          :when (contains? metered p)]
    (try (subscription/record-run! p {:tokens tokens :cells (count attempts)
                                      :points (or (subscription/moved p started-ms) 0.0)})
         (catch Exception e
           (tel/log! {:level :warn :id ::calibration-not-recorded :error e}
                     "Could not record the subscription calibration")))))

(def ^:private identity-libraries
  "The libraries an experiment's execution and grading run on: their versions
   are part of its identity, so a resume never folds Attempts of one version
   into a Scorecard with another's (provider prompt and grader versions are
   already in it)."
  [["org.replikativ" "spindel"] ["org.replikativ" "datahike"] ["org.replikativ" "pg-datahike"]
   ["org.replikativ" "rechentafel"] ["org.replikativ" "kontor"] ["is.simm" "partial-cps"]
   ["org.babashka" "sci"]])

(defn library-versions
  "`{\"group/artifact\" version}` of `identity-libraries` found as jars on the
   classpath (their pom.properties); a library from a local checkout is absent."
  []
  (into (sorted-map)
        (for [[g a] identity-libraries
              :let [r (io/resource (str "META-INF/maven/" g "/" a "/pom.properties"))]
              :when r]
          [(str g "/" a) (with-open [in (io/input-stream r)]
                           (.getProperty (doto (java.util.Properties.) (.load in)) "version"))])))

(defn runtime-identity
  "What an experiment's identity takes from the host it runs on: the
   `library-versions`, and the Claude Code CLI's version when `models` (specs
   or ids) include a Claude Code model. Part of the ExperimentDef's metadata,
   so a resume never mixes Attempts of two versions."
  [models]
  (cond-> {:libraries (library-versions)}
    (some #{:claude-code} (map #(model-provider (if (map? %) % {:model %})) models))
    (assoc :claude-cli (cc/cli-version))))

(defn experiment-def
  "The ExperimentDef of a provider's pieces: `benchmark` (namespaces the ids),
   `id`, `environments`, the candidate `team`, `dataset` `{:id :metadata}`,
   `metadata`, `repetitions`."
  [{:keys [benchmark id environments team dataset metadata repetitions]
    :or {repetitions 1}}]
  (experiment/make-experiment
   {:id (keyword (name benchmark) (name id))
    :dataset (experiment/make-dataset
              {:id (:id dataset)
               :environments environments
               :metadata (:metadata dataset)})
    :candidates (roster/agents team)
    :repetitions repetitions
    :metadata (or metadata {})}))

(defn- log-cell [benchmark {:keys [attempt error] job :experiment/job}]
  (let [r (:attempt/receipt attempt)
        m (:attempt/metrics r)]
    (tel/log! {:id :experiment/cell
               :data (cond-> {:benchmark benchmark
                              :candidate (:candidate/id job)
                              :repetition (:repetition job)}
                       attempt (assoc :status (:attempt/status r) :reward (:attempt/reward r)
                                      :microdollars (get-in m [:spend :microdollars]))
                       (:failure m) (assoc :failure (:failure m))
                       error (assoc :error error))}
              "Experiment cell finished")))

(defn run-in
  "The experiment `experiment` (an ExperimentDef) of `team` in `room`, as a
   Spin: every cell an evaluation in a fork of `room`, certified into its
   store, resumable; `:parent-run` makes every cell's Run its child. With
   `:world-parent`, the cells fork that room instead while `room` keeps the
   records (a benchmark set's fixture room, reported in the caller's room).
   Evaluates in the context of the room the cells fork (`:world-parent`, else
   `room`), whoever calls; each cell's own fork context is chosen inside
   `experiment/run`. A daemon room's context is a fork of the daemon's, and an
   evaluation in the latter loses its Run's wakeups. `capabilities` is
   `{:world-setup :protocol :evaluator}` (setup and protocol when named)."
  [room {:keys [capabilities team experiment parallelism cleanup-group benchmark parent-run
                world-parent admit]
         :or {parallelism 1}}]
  (let [{:keys [world-setup protocol evaluator]} capabilities
        cells (* (count (:experiment/candidates experiment))
                 (count (get-in experiment [:experiment/dataset :dataset/environments]))
                 (:experiment/repetitions experiment))]
    (binding [ec/*execution-context* (:ctx (or world-parent room))]
      (experiment/run
       room team experiment
       {(evaluation/evaluator-ref evaluator) evaluator}
       {:world-setups (if world-setup
                        {(evaluation/world-setup-ref world-setup) world-setup}
                        {})
        :protocols (if protocol {(evaluation/protocol-ref protocol) protocol} {})
        :parallelism parallelism
        :max-parallelism (max 16 parallelism)
        :max-attempts (max 256 cells)
        :cleanup-group cleanup-group
        :resume? true
        :complete-only? true
        :on-result #(log-cell benchmark %)
        :parent-run parent-run
        :world-parent world-parent
        :admit admit}))))

;; ---------------------------------------------------------------------------
;; A home of its own: an experiment's directory is a dvergr home
;; ---------------------------------------------------------------------------

(defn old-layout?
  "Is `dir` an experiment directory of the layout before homes (`artifacts/`
   beside `store/`, `home/` inside)?"
  [dir]
  (let [has? #(.exists (io/file dir %))]
    (boolean (or (has? "artifacts") (and (has? "store") (has? "home"))))))

(defn- current-daemon [] @@(requiring-resolve 'dvergr.orchestration.daemon/current-daemon))

(defn- mcp-running? [] (:running ((requiring-resolve 'dvergr.mcp.server/status))))

(defn open-home!
  "Make `dir` the process's dvergr home and start a headless daemon on it:
   no Telegram, web, MCP listener, agents, schedules or storage GC (a Claude Code candidate's MCP
   calls get a loopback server on an ephemeral port, `dvergr.mcp.server/
   listening-port`). The home holds the system DB and the rooms' stores
   registered there; a moved home is adopted, a copied one refused
   (`dvergr.system.home/claim!`). Refuses a directory of the old experiment
   layout, and a process that already runs a daemon (run in its home with
   `run!`'s `:daemon`, or `run-in`). Returns the handle `close-home!` takes."
  [dir]
  (when-not dir
    (throw (ex-info "An experiment needs a home directory (:dir)" {:type ::no-home})))
  (when (old-layout? dir)
    (throw (ex-info (str dir " is an experiment directory of the old layout (artifacts/, store/, "
                         "home/), which this version neither reads nor resumes. Start a new home "
                         "elsewhere; the old directory stays as it is.")
                    {:type ::old-layout :dir (str dir)})))
  (when (current-daemon)
    (throw (ex-info (str "This process runs a daemon: an experiment runs in its home (run! with "
                         ":daemon, or run-in), not in a home of its own")
                    {:type ::daemon-running :dir (str dir)})))
  (let [prev @@#'paths/home-atom
        mcp-before? (mcp-running?)
        ;; the home's daemon configures Claude Code process-wide
        cc-before (cc/settings-snapshot)
        suspend! (requiring-resolve 'dvergr.rooms.scheduler/suspend!)
        back! (fn []
                (suspend! false)
                (sdb/reset-conn!)
                (paths/set-home! prev)
                (cc/restore-settings! cc-before))]
    (paths/set-home! (.getCanonicalPath (io/file dir)))
    (sdb/reset-conn!)
    ;; the home's rooms run no schedules: a benchmark (or reading a report)
    ;; must not fire what was deployed there
    (suspend! true)
    (try
      {:dir (str dir) :prev-home prev :mcp-before? mcp-before? :cc-before cc-before
       :daemon ((requiring-resolve 'dvergr.orchestration.daemon/start!) {:agents {} :headless? true :gc false})}
      (catch Throwable t
        ;; a start that failed halfway returned no daemon to stop: undo what
        ;; it began (the GC loop, the room contexts and their stores)
        (try ((requiring-resolve 'dvergr.orchestration.daemon/abort-start!)) (catch Throwable _ nil))
        (back!)
        (throw t)))))

(defn close-home!
  "Stop the daemon `open-home!` started (and the MCP server a candidate
   started) and give the process back the home and Claude Code settings it
   had."
  [{:keys [daemon prev-home mcp-before? cc-before]}]
  (try
    (when daemon ((requiring-resolve 'dvergr.orchestration.daemon/stop!) daemon))
    (finally
      (try (when (and (not mcp-before?) (mcp-running?))
             ((requiring-resolve 'dvergr.mcp.server/stop!)))
           (catch Throwable _ nil))
      ((requiring-resolve 'dvergr.rooms.scheduler/suspend!) false)
      (sdb/reset-conn!)
      (paths/set-home! prev-home)
      (when cc-before (cc/restore-settings! cc-before)))))

(defn with-home
  "`(f daemon)` with `dir` as the home (`open-home!`), closed whatever happens."
  [dir f]
  (let [h (open-home! dir)]
    (try (f (:daemon h))
         (finally (close-home! h)))))

(defn- op [daemon k args] ((requiring-resolve 'dvergr.ops/invoke) daemon k args))

(defn- live-room [daemon slug] ((requiring-resolve 'dvergr.ops/resolve-room) daemon slug))

(defn- slug-part [s]
  (-> (str s) str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"(^-+|-+$)" "")))

(defn experiment-slug
  "The slug of the room an ExperimentDef is kept in: its readable id (cut to
   40 characters) and the start of its content id. A change of provider
   prompt or grader version, candidates or library versions is a new
   ExperimentDef, so a new room: nothing resumes into another version's
   cells. What a Run resolves only when it starts (the assembled system
   prompt, the tools, the model an alias names) changes no room: its cells
   re-run in this one (`experiment/run`, inputs ids)."
  [experiment]
  (let [id (:experiment/id experiment)
        readable (slug-part (str (namespace id) "-" (name id)))
        readable (str/replace (subs readable 0 (min 40 (count readable))) #"-+$" "")]
    (str (if (str/blank? readable) "experiment" readable)
         "-" (subs (str (:experiment/content-id experiment)) 0 8))))

(defn experiment-room!
  "The room of the daemon's home `experiment` is kept in, created (through
   the `room/create` op) on first use; its title names the experiment."
  [daemon experiment title]
  (let [slug (experiment-slug experiment)]
    (or (live-room daemon slug)
        (do (op daemon :room/create {:title title :slug slug})
            (live-room daemon slug))
        (throw (ex-info (str "The experiment room " slug " could not be created") {:type ::no-room :slug slug})))))

(defn- experiment-rooms
  "The live rooms of the daemon's home other than its global room."
  [daemon]
  (let [global (or (:global-room-slug (:config daemon)) "boardroom")]
    (->> (binding [ec/*execution-context* (:execution-ctx daemon)] (sdb/all-rooms))
         (map :room/slug)
         (remove #{global})
         sort
         (keep #(live-room daemon %)))))

(defn progress
  "What the experiments kept in the home `dir` have done so far, derived from
   their rooms' stores (the `experiment/progress` op of each): see
   `dvergr.agent.experiment/progress`."
  [dir]
  (with-home dir
    (fn [daemon]
      (let [ps (mapv #(op daemon :experiment/progress {:room (:slug %)}) (experiment-rooms daemon))]
        {:rooms (mapv :slug (experiment-rooms daemon))
         :experiments (vec (mapcat :experiments ps))
         :running (vec (mapcat :running ps))
         :microdollars (reduce + 0 (keep :microdollars ps))}))))

(defn- only-room [daemon room]
  (or (some->> room (live-room daemon))
      (let [rs (experiment-rooms daemon)]
        (if (and (nil? room) (= 1 (count rs)))
          (first rs)
          (throw (ex-info (str "The home keeps " (count rs) " experiment rooms; name one"
                               (when room (str " (no room " room ")")) ": " (str/join ", " (map :slug rs)))
                          {:type ::which-room :rooms (mapv :slug rs)}))))))

(defn report
  "The report (`experiment/report` op) of an experiment kept in the home
   `dir`: of room `room` (a slug), else of its only experiment room."
  ([dir] (report dir nil))
  ([dir room]
   (with-home dir
     (fn [daemon] (op daemon :experiment/report {:room (:slug (only-room daemon room))})))))

(defn open-experiment
  "Open the home `dir` to read the experiment kept in room `room` (a slug;
   default its only experiment room) with the store functions:
   `{:home :room :store :room-id}`, `:room-id` the id its records are kept
   under. `close-experiment` closes it."
  ([dir] (open-experiment dir nil))
  ([dir room]
   (let [h (open-home! dir)]
     (try
       (let [r (only-room (:daemon h) room)]
         {:home h :room r :store (:store r) :room-id (store/conversation-id r)})
       (catch Throwable t (close-home! h) (throw t))))))

(defn close-experiment [{:keys [home]}] (close-home! home))

;; ---------------------------------------------------------------------------
;; run!
;; ---------------------------------------------------------------------------

(defn- run-on*
  "`run!` in `daemon`'s home; `run-on` restores Claude Code's settings."
  [daemon
   {:keys [dir benchmark capabilities environments team models dataset metadata
           repetitions parallelism experiment-id claude-cli claude-env
           usage-pause-threshold usage-retries fault-retries title]
    :or {repetitions 1 parallelism 1 usage-pause-threshold 0.97 usage-retries 3 fault-retries 1}
    :as opts}]
  (let [started-ms (System/currentTimeMillis)
        allowance (get opts :allowance default-allowance)
        metered (metered-providers models)
        before (select-keys (subscription/all-readings) metered)
        admit (admit-for models allowance)
        uses-cc? (boolean (some #{:claude-code} (map model-provider models)))
        note (let [n (get opts :host-context-note :auto)]
               (cond (= :auto n) (when uses-cc? host-context-note)
                     (string? n) n))
        _ (when uses-cc? (cc/configure! (cond-> {:system-note note}
                                          claude-cli (assoc :cli claude-cli)
                                          claude-env (assoc :env claude-env))))
        host (when uses-cc?
               {:claude-cli (or (cc/cli-version)
                                (throw (ex-info "Claude Code CLI not runnable"
                                                {:cli (:cli (cc/settings-snapshot))})))
                :host-context-note-id (some-> note hasch/uuid str)
                :claude-env-keys (vec (sort (keys claude-env)))})
        room-id (or experiment-id
                    (when dir (keyword (name benchmark) (.getName (io/file dir))))
                    (throw (ex-info "An experiment in a running daemon needs an :experiment-id"
                                    {:type ::no-experiment-id})))
        experiment-def (experiment-def {:benchmark benchmark :id room-id
                                        :environments environments :team team
                                        :dataset dataset :repetitions repetitions
                                        :metadata (cond-> (assoc (or metadata {}) :libraries (library-versions))
                                                    host (assoc :host host))})
        ;; the home's room for exactly this ExperimentDef: a Claude Code
        ;; candidate's MCP tool calls reach its forks through the daemon
        ;; the title is display only: the ExperimentDef's id (and so what
        ;; resumes) stays the one above
        title (or title (str (name benchmark) " — " (name room-id)))
        room (experiment-room! daemon experiment-def title)
        slug (:slug room)
        ;; an embedder that serves the experiment's rooms learns the room
        ;; they fork from
        _ (when-let [f (:on-room opts)] (f room))
        ;; Detached evaluation cleanup of this operation is joined before the
        ;; call returns.
        cleanup-group (evaluation/cleanup-group)
        ;; the cells made with other inputs when this call started (its
        ;; first run, the pilot's when there is one): they run again
        stale-cells (atom nil)
        run-once (fn run-once
                   ([] (run-once admit))
                   ([admit]
                   ;; Wait outside the Runs: inside one, the wait would count
                   ;; against the evaluation's own timeout.
                    (when uses-cc? (cc/await-usage-window! {:threshold usage-pause-threshold}))
                    (let [spin (run-in room {:capabilities capabilities :team team :admit admit
                                             :experiment experiment-def :parallelism parallelism
                                             :cleanup-group cleanup-group :benchmark benchmark})]
                      (let [r (binding [ec/*execution-context* (:ctx room)] @spin)]
                        (compare-and-set! stale-cells nil (:stale-cells r 0))
                        r))))]
    (try
      (let [estimate (when-let [pf (:preflight opts)]
                       (run-preflight! {:preflight pf :experiment experiment-def :team team :metered metered
                                        :parallelism parallelism :admit admit :run-once run-once}))]
        (if (:over estimate)
        ;; the pilot's cells are kept: running again with a larger budget
        ;; (or none) resumes from them
          (do (record-calibration! metered started-ms (:attempts (meta estimate)))
              {:dir dir :room slug :experiment-room room-id :experiment experiment-def
               :stale-cells (or @stale-cells 0)
               :preflight estimate :stopped :over-budget})
          (let [result (loop [n 0]
                         (let [r (run-once)]
                       ;; Cells a rejected subscription call failed are re-run
                       ;; (resume skips the completed ones) after the reset.
                           ;; So are cells that faulted or failed (a lost
                           ;; connection): a fresh run with one such cell had
                           ;; no Scorecard, its report nothing to show.
                           (if (let [inc (:incomplete (:scorecard r))]
                                 (and inc (not (:refused r))
                                      (or (and (< n fault-retries) (or (pos? (:faults inc 0)) (seq (:errors inc))))
                                          (and uses-cc? (< n usage-retries) (cc/usage-limited?)))))
                             (recur (inc n))
                             r)))
                failed-cells (get-in result [:scorecard :incomplete :cells] 0)
                ;; every Attempt this invocation ran, the pilot's included
                ran (filter #(>= (or (get-in % [:attempt/receipt :attempt/started-at]) 0) started-ms)
                            (keep :attempt (:results result)))
                _ (record-calibration! metered started-ms ran)]
            {:dir dir :room slug :experiment-room room-id :experiment experiment-def
             :results (count (:results result)) :failed-cells failed-cells
             ;; cells kept from an earlier run but made with other inputs
             ;; (prompt text, tools, resolved model): run again
             :stale-cells (or @stale-cells 0)
             ;; the receipts of the Attempts this invocation has (a report's
             ;; checks and times), by Attempt id
             :receipts (mapv (fn [a] (assoc (:attempt/receipt a) :attempt/id (:attempt/id a)))
                             (keep :attempt (:results result)))
             :refused (:refused result)
             :preflight estimate
             ;; a fresh process has no reading before its first call: then
             ;; the first one taken during the run
             :subscription (into {} (map (fn [p] [p {:before (or (get before p)
                                                                 (first (filter #(>= (:at-ms %) started-ms)
                                                                                (subscription/samples p))))
                                                     :after (subscription/reading p)}]))
                                 metered)
             :scorecard (:scorecard result)
             ;; the report is the room's, read through the op every binding
             ;; uses (experiment_report): nothing beside the stores
             :report (some->
                      (:markdown
                       (try (op daemon :experiment/report
                                (cond-> {:room slug :title title
                                         :experiment (str (:experiment/content-id experiment-def))}
                                  (:scorecard/content-id (:scorecard result))
                                  (assoc :scorecard (str (:scorecard/content-id (:scorecard result))))))
                            (catch Throwable e
                              (tel/log! {:level :warn :id :experiment/report-failed
                                         :data {:room slug :error (ex-message e)}})
                              nil)))
                      (str (let [n (get-in result [:scorecard :incomplete :unpredicted] 0)]
                             (when (pos? n)
                               (str "\n**Unpredicted inputs:** " n " cell" (when (not= 1 n) "s")
                                    " ran with other inputs than computed before they started (a world setup "
                                    "that rewrites the workspace's skills or AGENTS.md?), so no Scorecard "
                                    "takes them, and a resume runs them again.\n")))))})))
      (finally
        (try (evaluation/await-cleanups-for! room cleanup-group) (catch Throwable _ nil))))))

(defn- run-on
  "`run!` in `daemon`'s home, giving Claude Code's process-wide settings back
   however it ends, setup failures included."
  [daemon opts]
  (let [cc-before (cc/settings-snapshot)]
    (try (run-on* daemon opts)
         (finally (cc/restore-settings! cc-before)))))

(defn run!
  "Run (or resume) an experiment. Returns `{:dir :room :experiment-room
   :experiment :results :failed-cells :stale-cells :scorecard :receipts :refused
   :preflight :subscription :report}` (`:room` is the slug of the room that
   keeps it, `:results` the number of cell results, `:stale-cells` the cells
   an earlier run made with other inputs, which ran again, `:receipts` the Attempts' receipts,
   `:report` the `experiment/report` Markdown). A preflight stop returns
   `{:dir :room :experiment-room :experiment :preflight :stopped :over-budget}`.

     :dir           the experiment's home (`open-home!`): its system DB and
                    the stores of its rooms; the experiment is kept in a room
                    of its own there (`experiment-slug`), so running the same
                    ExperimentDef again resumes it
     :daemon        instead of `:dir`: run in this running daemon's home
                    (needs `:experiment-id`)
     :benchmark     keyword; namespaces the experiment and dataset ids
     :capabilities  `{:world-setup :protocol :evaluator}`; the setup and the
                    protocol only when the environments name them (an
                    ordinary agent program has no protocol)
     :environments  EnvironmentDefs, one per task
     :team          roster of candidate AgentDefs
     :models        every model spec `{:model :provider}` a cell may call
                    (candidates and paid roles), to know whether Claude Code
                    is involved
     :dataset       `{:id kw :metadata map}`
     :metadata      goes into the ExperimentDef
     :repetitions :parallelism :experiment-id
     :title         the report's (and a new experiment room's) title; display
                    only, not part of the ExperimentDef (default
                    `<benchmark> — <experiment id>`)
     :claude-cli :claude-env :host-context-note (`:auto`, a string, or nil)
     :usage-pause-threshold :usage-retries
     :fault-retries how often cells that faulted or errored are re-run within
                    this call (default 1; not when cells were `:refused`)
     :on-room       `(fn [room])`, called with the experiment Room
     :preflight     `{:budget {:dollars d :subscription share} :stratum-fn f}`:
                    run a pilot first (one cell per candidate per stratum,
                    `experiment.preflight`), estimate the rest, and stop with
                    the estimate (`:stopped :over-budget`) when its
                    conservative bound exceeds the budget, or a candidate
                    with cells left has no pilot Attempt with a verdict
                    (`:unpiloted`; a faulted pilot cell measures nothing);
                    the result's `:preflight` is the estimate
     :allowance     what the experiment may take of the subscriptions its
                    models use (`default-allowance`; nil: no bound): once
                    reached no new cell starts, the result carries
                    `:refused`, and running again resumes the rest

   The result's `:subscription` is `{provider {:before :after}}`, the meter
   readings around the run: with the tokens on the Scorecard, what a unit of
   work costs in window points.

   With `:dir`, `run!` is `open-home!` + the run + `close-home!`: the process's
   home is `dir` for the call's length (a dedicated JVM or REPL; a process
   with a daemon passes `:daemon`)."
  [{:keys [dir daemon] :as opts}]
  (if daemon
    (run-on daemon opts)
    (with-home dir #(run-on % opts))))
