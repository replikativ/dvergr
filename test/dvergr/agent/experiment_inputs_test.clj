(ns dvergr.agent.experiment-inputs-test
  "Resume keeps a cell only while its candidate would be presented with the
   same inputs: the assembled system prompt, the tools offered, the resolved
   model (`program/run-inputs`)."
  (:require [clojure.java.shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.agent.environment :as environment-def]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.program :as program]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.catalog.report :as report]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.discourse :as d]
            [dvergr.model.chat :as model-chat]
            [dvergr.model.providers :as providers]
            [dvergr.model.registry :as registry]
            [dvergr.room.store.memory :as memory]
            [dvergr.sandbox]
            [dvergr.sandbox.extensions :as extensions]
            [dvergr.substrate.git]
            [dvergr.substrate.paths :as paths]
            [dvergr.tools]
            [org.replikativ.spindel.engine.core :as ec]))

(def ^:private evaluator
  (evaluation/make-evaluator
   {:id :test/inputs-exact :version 1 :basis "experiment-inputs-test:v1"
    :observe (fn [{:keys [default]}] default)
    :verify (fn [definition evidence]
              (let [ok? (= (:environment/task definition) (:result evidence))]
                {:checks {:exact? ok?} :reward (if ok? 1.0 0.0)}))}))

(def ^:private evaluators {(:ref evaluator) evaluator})

(defn- environment [n]
  (environment-def/make-environment
   {:id (keyword "inputs-test" (str "task-" n))
    :task (str "answer-" n)
    :verifier {:id :test/inputs-exact :version 1 :basis "experiment-inputs-test:v1"}
    :limits {:timeout-ms 60000 :cancel-timeout-ms 10000}
    :world {:isolation :ctx :settlement :discard}}))

(def ^:private model-alias "inputs-test-model")

(defn- fixture
  "Two LLM candidates (one with the sandbox tool, so its system prompt has
   the sandbox pointer) on three tasks."
  []
  (let [team (-> (roster/make-roster {:id :inputs-test/team})
                 (roster/make-agent {:id :sandboxed :tools #{:clojure_eval}
                                     :model-policy {:provider :test :model model-alias}
                                     :program {:kind :llm :max-model-steps 2 :budget-dollars 1.0
                                               :auto-compact? false}})
                 (roster/make-agent {:id :plain :tools #{}
                                     :model-policy {:provider :test :model "inputs-test-plain"}
                                     :program {:kind :llm :max-model-steps 2 :budget-dollars 1.0
                                               :auto-compact? false}}))]
    {:team team
     :definition (experiment/make-experiment
                  {:id :inputs-test/experiment
                   :dataset (experiment/make-dataset
                             {:id :inputs-test/tasks
                              :environments (mapv environment (range 3))})
                   :candidates [(roster/agent team :sandboxed) (roster/agent team :plain)]})}))

(defn- task-of [messages]
  (some (fn [m] (let [c (or (:message/content m) (:content m))]
                  (when (and (string? c) (str/starts-with? c "answer-")) c)))
        messages))

(defmacro ^:private with-model
  "Run `body` with a model that answers every task exactly, counting its
   calls in `calls`."
  [calls & body]
  `(with-redefs [providers/ensure-initialized! (constantly nil)
                 chat-agent/messages->api-format (fn [messages# _# _#] messages#)
                 model-chat/chat (fn [messages# _#]
                                   (swap! ~calls inc)
                                   {:content (or (task-of messages#) "no task")
                                    :tool-calls nil
                                    :usage {:input-tokens 10 :output-tokens 5}
                                    :stop-reason :end-turn})]
     ~@body))

(defn- run! [room team definition & [opts]]
  (binding [ec/*execution-context* (:ctx room)]
    @(experiment/run room team definition evaluators
                     (merge {:resume? true :complete-only? true} opts))))

(defn- with-room [id f]
  (let [room (d/make-room {:id id :store (memory/make)})
        alias-before (get @registry/aliases model-alias)]
    (try
      (registry/register-alias! model-alias "inputs-test-model-a")
      (f room)
      (finally
        (if alias-before
          (registry/register-alias! model-alias alias-before)
          (swap! registry/aliases dissoc model-alias))
        (evaluation/await-cleanups! room 5000)
        (d/close-room! room)))))

(defn- inputs-ids [attempts candidate]
  (into #{} (comp (filter #(= candidate (get-in % [:attempt/receipt :attempt/metrics :experiment-candidate])))
                  (map experiment/inputs-id))
        attempts))

(deftest the-run-records-the-inputs-the-runner-predicts
  (with-room :inputs-recorded
    (fn [room]
      (let [{:keys [team definition]} (fixture)
            calls (atom 0)]
        (with-model calls
          (let [{:keys [attempts scorecard stale-cells]} (run! room team definition)
                predicted (binding [ec/*execution-context* (:ctx room)]
                            (program/inputs-id (program/run-inputs (roster/agent team :sandboxed))))]
            (is (= 6 @calls))
            (is (zero? stale-cells))
            (is (= 6 (count (:scorecard/entries scorecard))))
            (is (uuid? predicted))
            (is (= #{predicted} (inputs-ids attempts :sandboxed))
                "every Run recorded the inputs id computed before it, without a model call")
            (is (every? #(uuid? (get-in % [:attempt/receipt :attempt/metrics :prompt-id])) attempts))
            (is (not= (inputs-ids attempts :sandboxed) (inputs-ids attempts :plain))
                "the candidates' tools and models differ, so do their inputs")))))))

(deftest unchanged-inputs-keep-every-cell
  (with-room :inputs-unchanged
    (fn [room]
      (let [{:keys [team definition]} (fixture)
            calls (atom 0)]
        (with-model calls
          (let [first-pass (run! room team definition)
                _ (reset! calls 0)
                second-pass (run! room team definition)]
            (is (zero? @calls) "no model call, no token")
            (is (zero? (:stale-cells second-pass)))
            (is (= 6 (count (run/runs room {:limit 50}))) "no Run started")
            (is (= (:scorecard/content-id (:scorecard first-pass))
                   (:scorecard/content-id (:scorecard second-pass))))))))))

(deftest a-changed-prompt-re-runs-its-cells-and-the-report-counts-them
  (with-room :inputs-prompt-changed
    (fn [room]
      (let [{:keys [team definition]} (fixture)
            calls (atom 0)
            pointer @#'dvergr.sandbox/sandbox-prompt-pointer]
        (with-model calls
          (let [first-pass (run! room team definition)
                old-ids (set (map :attempt/id (:attempts first-pass)))]
            (is (= 6 @calls))
            ;; the sandbox prompt's text changes (as #295 changed it), the
            ;; AgentDefs and so the ExperimentDef do not
            (with-redefs [dvergr.sandbox/sandbox-prompt-pointer (str pointer "\n\nOne more example.")]
              (testing "a run stopped after one re-run cell"
                (reset! calls 0)
                (let [admitted (atom 0)
                      partial (run! room team definition
                                    {:admit (fn [_] (when (> (swap! admitted inc) 1) {:reason :test-stop}))})
                      r (binding [ec/*execution-context* (:ctx room)]
                          (report/room-report room {:experiment (:experiment/content-id definition)}))]
                  (is (= 3 (:stale-cells partial)) "only the sandboxed candidate's cells are stale")
                  (is (= 1 @calls))
                  (is (:incomplete (:scorecard partial)))
                  (is (false? (:complete r))
                      "the Scorecard of the old prompt is superseded: the report does not show it")
                  (is (= {:rerun 1 :awaiting 2 :superseded 3} (:inputs r)))
                  (is (= {:rerun 1 :awaiting 2 :superseded 3}
                         (:inputs (binding [ec/*execution-context* (:ctx room)] (report/room-report room {}))))
                      "a report that names no experiment finds the room's only one")
                  (is (str/includes? (:markdown r) "1 cell ran again because"))
                  (is (str/includes? (:markdown r) "2 cells made with earlier inputs await a re-run"))
                  (let [p (binding [ec/*execution-context* (:ctx room)] (experiment/progress room))
                        cs (into {} (map (juxt :candidate/id identity))
                                 (:candidates (first (:experiments p))))]
                    (is (= 1 (get-in cs [:sandboxed :verdicts])) "progress counts the current cells only")
                    (is (= 3 (get-in cs [:plain :verdicts])))
                    (is (= {:rerun 1 :awaiting 2 :superseded 3} (:inputs (first (:experiments p))))))))
              (testing "the resume runs the rest"
                (reset! calls 0)
                (let [second-pass (run! room team definition)
                      entries (:scorecard/entries (:scorecard second-pass))
                      r (binding [ec/*execution-context* (:ctx room)]
                          (report/room-report room {:experiment (:experiment/content-id definition)}))]
                  (is (= 2 (:stale-cells second-pass)))
                  (is (= 2 @calls))
                  (is (= 6 (count entries)))
                  (is (= 3 (count (filter #(contains? old-ids (:attempt/id %)) entries)))
                      "the plain candidate's cells were kept")
                  (is (not-any? #(and (= :sandboxed (:candidate/id %)) (contains? old-ids (:attempt/id %))) entries)
                      "the Scorecard has none of the sandboxed candidate's old cells")
                  (is (= 9 (count (run/runs room {:limit 50}))) "the old Attempts' Runs stay in the store")
                  (is (:complete r))
                  (is (= (str (:scorecard/content-id (:scorecard second-pass))) (:scorecard r)))
                  (is (= {:rerun 3 :awaiting 0 :superseded 3} (:inputs r)))
                  (is (str/includes? (:markdown r) "3 cells ran again because")))))))))))

(deftest a-changed-model-alias-re-runs-its-cells
  (with-room :inputs-alias-changed
    (fn [room]
      (let [{:keys [team definition]} (fixture)
            calls (atom 0)]
        (with-model calls
          (run! room team definition)
          (registry/register-alias! model-alias "inputs-test-model-b")
          (reset! calls 0)
          (let [{:keys [stale-cells scorecard]} (run! room team definition)]
            (is (= 3 stale-cells) "the alias names another model now")
            (is (= 3 @calls))
            (is (= 6 (count (:scorecard/entries scorecard))))))))))

(deftest a-changed-sandbox-extension-re-runs-its-cells
  ;; the AgentDef names the extension; what it resolves to (its version and
  ;; basis, e.g. a library's version) is an input of the Run
  (let [ext :inputs-test/extension
        register! (fn [basis] (extensions/register! ext {:version 1 :basis basis
                                                         :install (fn [_ _] nil)}))]
    (with-room :inputs-extension-changed
      (fn [room]
        (register! {:lib "1.0"})
        (let [{:keys [team definition]} (fixture)
              team (roster/make-agent team {:id :extended :tools #{:clojure_eval} :sandbox #{ext}
                                            :model-policy {:provider :test :model "inputs-test-plain"}
                                            :program {:kind :llm :max-model-steps 2 :budget-dollars 1.0
                                                      :auto-compact? false}})
              definition (experiment/make-experiment
                          {:id :inputs-test/extended
                           :dataset (:experiment/dataset definition)
                           :candidates [(roster/agent team :extended) (roster/agent team :plain)]})
              calls (atom 0)]
          (with-model calls
            (let [first-pass (run! room team definition)
                  predicted (binding [ec/*execution-context* (:ctx room)]
                              (program/run-inputs (roster/agent team :extended)))]
              (is (zero? (:stale-cells first-pass)))
              (is (= [{:extension/id ext :extension/version 1 :extension/basis {:lib "1.0"}}]
                     (:sandbox predicted)))
              (is (= #{(program/inputs-id predicted)} (inputs-ids (:attempts first-pass) :extended))
                  "the Run records the inputs the prediction computes, from the same registry"))
            (testing "unchanged extension refs keep every cell"
              (reset! calls 0)
              (let [{:keys [stale-cells]} (run! room team definition)]
                (is (zero? stale-cells))
                (is (zero? @calls))))
            (testing "a changed extension ref makes its candidate's kept cells stale"
              (register! {:lib "2.0"})
              (reset! calls 0)
              (let [{:keys [stale-cells scorecard]} (run! room team definition)]
                (is (= 3 stale-cells) "only the candidate whose extension changed")
                (is (= 3 @calls))
                (is (= 6 (count (:scorecard/entries scorecard))))))))))))

(deftest attempts-without-an-inputs-id-are-stale
  (testing "an LLM candidate's Attempts from before inputs ids run again"
    (with-room :inputs-legacy
      (fn [room]
        (let [{:keys [team definition]} (fixture)
              calls (atom 0)
              update! @#'program/update-llm-metrics!]
          (with-model calls
            ;; a Run that records no inputs id, as before
            (with-redefs [program/update-llm-metrics!
                          (fn [supervisor f & args]
                            (update! supervisor (fn [m] (some-> (apply f m args) (dissoc :inputs-id)))))]
              (let [{:keys [attempts]} (run! room team definition)]
                (is (every? nil? (map experiment/inputs-id attempts)))))
            (reset! calls 0)
            (let [{:keys [stale-cells scorecard]} (run! room team definition)]
              (is (= 6 stale-cells))
              (is (= 6 @calls))
              (is (every? #(experiment/inputs-id %) (:attempts (run! room team definition)))))))))
    (testing "a deterministic program resolves nothing: its cells are kept"
      (with-room :inputs-legacy-echo
        (fn [room]
          (let [team (-> (roster/make-roster {:id :inputs-test/echo})
                         (roster/make-agent {:id :echo :program {:kind :echo}}))
                definition (experiment/make-experiment
                            {:id :inputs-test/echo
                             :dataset (experiment/make-dataset
                                       {:id :inputs-test/echo-tasks
                                        :environments (mapv environment (range 2))})
                             :candidates [(roster/agent team :echo)]})]
            (run! room team definition)
            (let [again (run! room team definition)]
              (is (zero? (:stale-cells again)))
              (is (= 2 (count (run/runs room {:limit 10}))) "no cell ran again"))))))))

(deftest a-protocol-s-inputs-enter-the-inputs-id
  (let [agent (-> (roster/make-roster {:id :inputs-test/protocol})
                  (roster/make-agent {:id :p :model-policy {:provider :test :model "inputs-test-plain"}
                                      :program {:kind :llm}})
                  (roster/agent :p))
        protocol (fn [prompt] (evaluation/make-protocol
                               {:id :inputs-test/protocol :version 1 :run (constantly {})
                                :inputs (fn [_agent] {:system-prompt prompt})}))
        id (fn [p] (program/inputs-id (program/run-inputs agent p)))]
    (is (= (id (protocol "a")) (id (protocol "a"))))
    (is (not= (id (protocol "a")) (id (protocol "b"))))
    (is (not= (id (protocol "a")) (id nil)) "a protocol Run is not the LLM program")))

(deftest a-run-with-other-inputs-than-predicted-enters-no-scorecard
  (with-room :inputs-unpredicted
    (fn [room]
      (let [{:keys [team definition]} (fixture)
            calls (atom 0)
            pointer @#'dvergr.sandbox/sandbox-prompt-pointer]
        (with-model calls
          (try
            (binding [ec/*execution-context* (:ctx room)]
              ;; not a resume: the prediction holds every cell all the same
              (let [spin (experiment/run room team definition evaluators {:complete-only? true})
                    ;; predicted; now the prompt the Runs assemble differs
                    _ (alter-var-root #'dvergr.sandbox/sandbox-prompt-pointer (constantly (str pointer " (changed)")))
                    {:keys [scorecard results]} @spin]
                (is (= 6 @calls))
                (is (= 3 (count (filter :unpredicted? results))) "the sandboxed candidate's cells")
                (is (= {:cells 3 :unpredicted 3} (select-keys (:incomplete scorecard) [:cells :unpredicted]))
                    "no Scorecard takes a cell made with other inputs than its cell's")))
            (finally
              (alter-var-root #'dvergr.sandbox/sandbox-prompt-pointer (constantly pointer)))))))))

(deftest an-attempt-without-inputs-says-nothing-about-the-current-ones
  (let [attempt (fn [id inputs t status]
                  {:attempt/id id :attempt/certified-at t
                   :attempt/environment {:environment/content-id :e :environment/world {}}
                   :attempt/receipt {:attempt/status status
                                     :attempt/environment {:environment/content-id :e}
                                     :attempt/metrics (cond-> {:experiment-candidate :c :experiment-repetition 0}
                                                        inputs (assoc :inputs-id inputs))}})
        current (fn [xs] (set (map :attempt/id (experiment/current-attempts xs))))]
    (is (= #{1} (current [(attempt 2 nil 20 :failed) (attempt 1 :a 10 :completed)]))
        "a Run that faulted before it assembled its inputs does not make them nil")
    (is (= #{2} (current [(attempt 2 :b 20 :completed) (attempt 1 :a 10 :completed)])))
    (is (= #{2} (current [(attempt 2 :b 20 :completed) (attempt 1 nil 10 :completed)]))
        "Attempts from before inputs ids are superseded once one has them")
    (is (= #{1 2} (current [(attempt 2 nil 20 :completed) (attempt 1 nil 10 :completed)]))
        "a deterministic candidate's (or an old experiment's) all count")
    (is (= #{1} (current [(assoc-in (attempt 2 nil 20 :completed) [:attempt/receipt :attempt/metrics :inputs-drifted?] true)
                          (attempt 1 nil 10 :completed)]))
        "an Attempt whose inputs drifted counts for nothing")))

(deftest a-run-calls-the-model-its-inputs-id-names
  (with-room :inputs-pinned
    (fn [room]
      (let [{:keys [team definition]} (fixture)
            models (atom [])]
        (with-redefs [providers/ensure-initialized! (constantly nil)
                      chat-agent/messages->api-format (fn [messages _ _] messages)
                      model-chat/chat (fn [messages opts]
                                        (swap! models conj (:model opts))
                                        {:content (or (task-of messages) "no task") :tool-calls nil
                                         :usage {:input-tokens 1 :output-tokens 1} :stop-reason :end-turn})]
          (run! room team definition)
          (is (= #{"inputs-test-model-a" "inputs-test-plain"} (set @models))
              "the alias is resolved once, when the Run assembles its inputs"))))))

(deftest a-run-whose-model-entry-changes-records-no-inputs
  (with-room :inputs-entry-changed
    (fn [room]
      (let [{:keys [team definition]} (fixture)
            id "inputs-test-model-a"
            before (get @registry/registry id)]
        (try
          (swap! registry/registry assoc id {:id id :provider :test :api-type :test :max-output 100 :quirks {}})
          (with-redefs [providers/ensure-initialized! (constantly nil)
                        chat-agent/messages->api-format (fn [messages _ _] messages)
                        model-chat/chat (fn [messages opts]
                                          ;; a registry refresh while the Run calls its model
                                          (when (= id (:model opts))
                                            (swap! registry/registry update id update :max-output inc))
                                          {:content (or (task-of messages) "no task") :tool-calls nil
                                           :usage {:input-tokens 1 :output-tokens 1} :stop-reason :end-turn})]
            (let [{:keys [attempts scorecard]} (run! room team definition)]
              (is (= #{nil} (inputs-ids attempts :sandboxed)) "its calls were not all shaped alike")
              (is (every? experiment/inputs-drifted?
                          (filter #(= :sandboxed (get-in % [:attempt/receipt :attempt/metrics :experiment-candidate]))
                                  attempts)))
              (is (= {:cells 3 :unpredicted 3} (select-keys (:incomplete scorecard) [:cells :unpredicted])))
              (let [p (binding [ec/*execution-context* (:ctx room)] (experiment/progress room))
                    cs (set (map :candidate/id (:candidates (first (:experiments p)))))]
                (is (= #{:plain} cs) "the store's views score none of its cells"))))
          (finally
            (if before (swap! registry/registry assoc id before) (swap! registry/registry dissoc id))))))))

(deftest the-compaction-model-is-an-input
  (let [agent (-> (roster/make-roster {:id :inputs-test/compacting})
                  (roster/make-agent {:id :c :model-policy {:provider :test :model "inputs-test-plain"}
                                      :program {:kind :llm}})
                  (roster/agent :c))
        before (get @registry/defaults :compaction-model)
        id #(with-redefs [providers/ensure-initialized! (constantly nil)]
              (program/inputs-id (program/run-inputs agent)))]
    (try
      (swap! registry/defaults assoc :compaction-model "inputs-test-compactor-a")
      (let [a (id)]
        (swap! registry/defaults assoc :compaction-model "inputs-test-compactor-b")
        (is (not= a (id)) "another default compaction model is other inputs"))
      (finally (swap! registry/defaults assoc :compaction-model before)))))

(defn- seed-repo
  "A local git repository to seed workspaces from, its AGENTS.md saying
   `marker`."
  [marker]
  (let [dir (str (System/getProperty "java.io.tmpdir") "/dvergr-inputs-seed-" (random-uuid))
        sh (fn [& args] (let [r (apply clojure.java.shell/sh (concat args [:dir dir]))]
                          (when-not (zero? (:exit r)) (throw (ex-info "git failed" r)))))]
    (.mkdirs (java.io.File. dir))
    (spit (str dir "/AGENTS.md") (str "# Workspace\n\n" marker "\n"))
    (sh "git" "init" "-q" "-b" "main")
    (sh "git" "add" "-A")
    (sh "git" "-c" "user.email=t@dvergr" "-c" "user.name=t" "commit" "-q" "-m" "seed")
    dir))

(deftest the-prediction-reads-the-workspace-the-run-seeds
  ;; A room without a repository of its own reads the shared
  ;; .dvergr/workspace, which the first sandbox seeds. The prediction runs
  ;; before any sandbox exists: it must read the seeded workspace too, or a
  ;; fresh home's first run disagrees with every Run it predicts.
  (let [prev (paths/home)
        home (str (System/getProperty "java.io.tmpdir") "/dvergr-inputs-home-" (random-uuid))
        marker (str "inputs-seed-marker-" (random-uuid))
        seed (seed-repo marker)
        prompts (atom [])
        instructions program/llm-instructions]
    (try
      (paths/set-home! home)
      (is (not (.exists (java.io.File. (str (paths/workspace-dir) "/.git")))) "a fresh home: not seeded yet")
      (with-redefs [dvergr.substrate.git/sandbox-repo (constantly seed)
                    program/llm-instructions (fn [agent tool-map]
                                               (let [p (instructions agent tool-map)]
                                                 (when (contains? tool-map "clojure_eval") (swap! prompts conj p))
                                                 p))]
        (with-room :inputs-fresh-workspace
          (fn [room]
            (let [{:keys [team definition]} (fixture)
                  calls (atom 0)]
              (with-model calls
                (let [{:keys [results scorecard]} (run! room team definition)]
                  (is (str/includes? (first @prompts) marker)
                      "the prediction, made before any sandbox, read the seeded AGENTS.md")
                  (is (= 4 (count @prompts)) "the prediction and the sandboxed candidate's three Runs")
                  (is (apply = @prompts))
                  (is (not-any? :unpredicted? results))
                  (is (= 6 (count (:scorecard/entries scorecard))))))))))
      (finally (paths/set-home! prev)))))

(deftest a-tool-a-sandbox-registers-resolves-before-any-sandbox
  ;; `llm_call` and the mail tools register themselves when their namespaces
  ;; load, which building a sandbox does: resolving a name loads them first.
  ;; A cold process is simulated: the tool is not registered until its
  ;; namespace is (re)required.
  (let [_ (require 'dvergr.tools.llm-call)
        saved (get @dvergr.tools/registry "llm_call")
        required (atom [])
        require! dvergr.substrate.load/require!]
    (try
      (is (some? saved))
      (swap! dvergr.tools/registry dissoc "llm_call")
      (with-redefs [dvergr.substrate.load/require!
                    (fn [& args]
                      (swap! required into (filter symbol? args))
                      (when (some #{'dvergr.tools.llm-call} args)
                        (swap! dvergr.tools/registry assoc "llm_call" saved))
                      (apply require! args))]
        (is (= {"llm_call" saved} (dvergr.tools/normalize-tools #{:llm_call})))
        (is (= #{'dvergr.tools.llm-call 'dvergr.intake.mail} (set @required))))
      (finally (swap! dvergr.tools/registry assoc "llm_call" saved)))))

(deftest a-cold-model-registry-is-read-only-once-loaded
  ;; a caller that arrives while models.edn loads waits for the whole load
  (let [loaded? @#'registry/models-loaded?
        before @loaded?
        started (promise)
        release (promise)
        load! @#'registry/load-models-resource!]
    (try
      (reset! loaded? false)
      (with-redefs [registry/load-models-resource! (fn [] (deliver started true) @release (load!))]
        (let [first-caller (future (registry/ensure-models-loaded!) :done)
              _ (is (deref started 5000 nil) "the first caller is loading")
              second-thread (promise)
              second-caller (future (deliver second-thread (Thread/currentThread))
                                    (registry/ensure-models-loaded!) @loaded?)
              ^Thread t (deref second-thread 5000 nil)
              ;; until it has returned or blocks on the load
              blocked? (loop [n 0]
                         (cond (realized? second-caller) false
                               (= Thread$State/BLOCKED (.getState t)) true
                               (< n 500) (do (Thread/sleep 10) (recur (inc n)))
                               :else false))]
          (is blocked? "the second caller waits for the load")
          (is (not (realized? second-caller)))
          (deliver release true)
          (is (= :done (deref first-caller 5000 nil)))
          (is (true? (deref second-caller 5000 nil)) "and returns once it is complete")))
      (finally (reset! loaded? (or before @loaded?))))))
