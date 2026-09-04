(ns dvergr.agent.experiment-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.activity :as activity]
            [dvergr.agent.environment :as environment-def]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.observation :as observation]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.discourse :as d]
            [dvergr.model.chat :as model-chat]
            [dvergr.model.providers :as providers]
            [dvergr.room.registry :as registry]
            [dvergr.room.store :as store]
            [dvergr.room.store.memory :as memory]
            [dvergr.rooms.forks :as forks]
            [hasch.core :as hasch]
            [org.replikativ.spindel.engine.core :as ec])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- environment
  ([id task] (environment id 1 task))
  ([id version task]
   (environment-def/make-environment
    {:id id
     :version version
     :task task
     :verifier {:id :test/exact :version 1 :basis "experiment-test:v1"}
     :limits {:timeout-ms 2000 :cancel-timeout-ms 1000}
     :world {:isolation :ctx :settlement :discard}})))

(def exact-evaluator
  (evaluation/make-evaluator
   {:id :test/exact
    :version 1
    :basis "experiment-test:v1"
    :observe (fn [{:keys [default]}] default)
    :verify (fn [definition evidence]
              (let [ok? (= (:environment/task definition) (:result evidence))]
                {:checks {:exact? ok?}
                 :reward (if ok? 1.0 0.0)}))}))

(defn- fixture []
  (let [team (-> (roster/make-roster {:id :experiment/team})
                 (roster/make-agent {:id :alpha :program {:kind :echo}})
                 (roster/make-agent {:id :beta :program {:kind :echo}}))
        dataset (experiment/make-dataset
                 {:id :experiment/tasks
                  ;; Logical IDs may recur across exact environment versions.
                  :environments [(environment :task/versioned 1 {:value 1})
                                 (environment :task/versioned 2 {:value 2})]})]
    {:team team
     :dataset dataset
     :definition
     (experiment/make-experiment
      {:id :experiment/paired
       :dataset dataset
       :candidates [(roster/agent team :alpha) (roster/agent team :beta)]
       :repetitions 2})}))

(deftest definitions-are-canonical-portable-values
  (let [{:keys [team dataset definition]} (fixture)
        reordered (experiment/make-dataset
                   {:environments (:dataset/environments dataset)
                    :id :experiment/tasks})]
    (is (= dataset reordered))
    (is (= dataset (experiment/validate-dataset dataset)))
    (is (= definition (experiment/validate-experiment definition)))
    (is (uuid? (:dataset/content-id dataset)))
    (is (uuid? (:experiment/content-id definition)))
    (is (= #{:alpha :beta}
           (set (map :candidate/id (:experiment/candidates definition)))))
    (is (every? uuid?
                (map :candidate/agent-content-id
                     (:experiment/candidates definition))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"content ID"
                          (experiment/validate-dataset
                           (assoc dataset :dataset/version 2))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"content ID"
                          (experiment/validate-experiment
                           (assoc definition :experiment/repetitions 3))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"duplicate"
         (experiment/make-dataset
          {:id :duplicate
           :environments [(first (:dataset/environments dataset))
                          (first (:dataset/environments dataset))]})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"portable"
         (experiment/make-experiment
          {:id :non-portable :dataset dataset
           :candidates [(assoc (roster/agent team :alpha)
                               :agent/metadata {:live (atom 1)})]})))))

(deftest exact-candidates-and-evaluators-fail-before-run-admission
  (let [{:keys [team definition]} (fixture)
        room (d/make-room {:id :experiment-preflight :store (memory/make)})]
    (try
      (let [changed-team (roster/revise-agent team :alpha
                                              {:prompt "different definition"})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"version"
                              (experiment/run room changed-team definition
                                              {(:ref exact-evaluator)
                                               exact-evaluator})))
        (is (empty? (run/active-runs (:id room)))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no exact host Evaluator"
                            (experiment/run room team definition {})))
      (let [wrong (evaluation/make-evaluator
                   {:id :test/wrong
                    :observe (constantly {})
                    :verify (fn [_ _] {:checks {} :reward 0.0})})]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"does not match"
             (experiment/run room team definition
                             {(:ref exact-evaluator) wrong}))))
      (let [retained-env
            (environment-def/make-environment
             {:id :test/retained
              :task :retained
              :verifier {:id :test/exact :version 1
                         :basis "experiment-test:v1"}
              :limits {:timeout-ms 2000 :cancel-timeout-ms 1000}
              :world {:isolation :ctx :settlement :review}})
            retained-dataset (experiment/make-dataset
                              {:id :test/retained
                               :environments [retained-env]})
            retained-experiment
            (experiment/make-experiment
             {:id :test/retained
              :dataset retained-dataset
              :candidates [(roster/agent team :alpha)]})]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"require :discard"
             (experiment/run room team retained-experiment
                             {(:ref exact-evaluator) exact-evaluator}))))
      (let [oversized (experiment/make-experiment
                       {:id :test/oversized
                        :dataset (:experiment/dataset definition)
                        :candidates [(roster/agent team :alpha)]
                        :repetitions 1000000000})]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"host admission ceiling"
             (experiment/run room team oversized
                             {(:ref exact-evaluator) exact-evaluator}))))
      (let [setup-ref {:setup/id :test/experiment-fixture :setup/version 1}
            setup-env
            (environment-def/make-environment
             {:id :test/setup
              :task :setup
              :verifier {:id :test/exact :version 1
                         :basis "experiment-test:v1"}
              :limits {:timeout-ms 2000 :cancel-timeout-ms 1000}
              :world {:isolation :ctx :settlement :discard
                      :setup setup-ref}})
            setup-experiment
            (experiment/make-experiment
             {:id :test/setup
              :dataset (experiment/make-dataset
                        {:id :test/setup :environments [setup-env]})
              :candidates [(roster/agent team :alpha)]})]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"no exact host WorldSetup"
             (experiment/run room team setup-experiment
                             {(:ref exact-evaluator) exact-evaluator}))))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"parallelism exceeds"
           (experiment/run room team definition
                           {(:ref exact-evaluator) exact-evaluator}
                           {:parallelism 3 :max-parallelism 2})))
      (doseq [invalid-group [:not-a-uuid false]]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"Cleanup group must be a UUID"
             (experiment/run room team definition
                             {(:ref exact-evaluator) exact-evaluator}
                             {:cleanup-group invalid-group}))))
      (is (empty? (run/active-runs (:id room))))
      (finally
        (d/close-room! room)))))

(deftest experiment-applies-an-exact-world-setup-to-every-cell
  (let [{:keys [team]} (fixture)
        setup-ref {:setup/id :test/experiment-fixture :setup/version 1}
        setup-env
        (environment-def/make-environment
         {:id :test/setup-cells
          :task :setup-cells
          :verifier {:id :test/exact :version 1
                     :basis "experiment-test:v1"}
          :limits {:timeout-ms 2000 :cancel-timeout-ms 1000}
          :world {:isolation :ctx :settlement :discard :setup setup-ref}})
        definition
        (experiment/make-experiment
         {:id :test/setup-cells
          :dataset (experiment/make-dataset
                    {:id :test/setup-cells :environments [setup-env]})
          :candidates [(roster/agent team :alpha) (roster/agent team :beta)]
          :repetitions 2})
        prepared (atom [])
        setup (evaluation/make-world-setup
               {:id :test/experiment-fixture
                :prepare (fn [{run-id :run/id}]
                           (swap! prepared conj run-id)
                           {:prepared run-id})})
        room (d/make-room {:id :experiment-world-setups
                           :store (memory/make)})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [{:keys [attempts scorecard]}
              @(experiment/run room team definition
                               {(:ref exact-evaluator) exact-evaluator}
                               {:parallelism 2
                                :world-setups {setup-ref setup}})]
          (is (= 4 (count attempts)))
          (is (= 4 (count @prepared)))
          (is (= (set (map :attempt/run-id attempts)) (set @prepared)))
          (is (= 4 (count (:scorecard/entries scorecard))))
          (is (every? #(= :discarded (:run/settlement-status %))
                      (run/runs room {:limit 10})))))
      (finally
        (evaluation/await-cleanups! room 5000)
        (d/close-room! room)))))

(deftest finite-cell-rewards-cannot-overflow-scorecard-aggregates
  (let [team (-> (roster/make-roster {:id :experiment/overflow-team})
                 (roster/make-agent {:id :candidate :program {:kind :echo}}))
        definition
        (experiment/make-experiment
         {:id :experiment/overflow
          :dataset
          (experiment/make-dataset
           {:id :experiment/overflow
            :environments [(environment :experiment/overflow :ok)]})
          :candidates [(roster/agent team :candidate)]
          :repetitions 2})
        evaluator
        (evaluation/make-evaluator
         {:id :test/exact
          :version 1
          :basis "experiment-test:v1"
          :observe (fn [{:keys [default]}] default)
          :verify (fn [_ _]
                    {:checks {:finite-cell? true}
                     :reward Double/MAX_VALUE})})
        room (d/make-room {:id :experiment-overflow
                           :store (memory/make)})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"aggregates must be finite"
             @(experiment/run room team definition
                              {(:ref evaluator) evaluator}))))
      (is (= 2 (count (store/-list-attempts (:store room) (:id room) {})))
          "every finite cell Attempt remains certified for diagnosis")
      (is (empty? (store/-list-scorecards (:store room) (:id room) {}))
          "the constructor never exposes or persists an invalid Scorecard")
      (finally
        (evaluation/await-cleanups! room 5000)
        (d/close-room! room)))))

(deftest repeated-paired-experiment-composes-ordinary-certified-evaluations
  (let [{:keys [team definition]} (fixture)
        room (d/make-room {:id :experiment-run :store (memory/make)})
        evaluator-ref (:ref exact-evaluator)
        cleanup-group (evaluation/cleanup-group)]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [spin (experiment/run room team definition
                                   {evaluator-ref exact-evaluator}
                                   {:parallelism 2
                                    :cleanup-group cleanup-group})]
          (is (empty? (run/active-runs (:id room)))
              "constructing the Experiment Spin admits no Runs")
          (let [{:keys [results attempts scorecard execution]} @spin]
            (try
              (is (= 8 (count results) (count attempts)))
              (is (= {:parallelism 2
                      :attempt-count 8
                      :cleanup-group cleanup-group}
                     execution))
              (is (= 8 (count (set (map :attempt/id attempts)))))
              (is (= 8 (count (:scorecard/entries scorecard))))
              (is (= [{:candidate/id :alpha :attempt-count 4
                       :passed-count 4 :reward-sum 4.0 :reward-mean 1.0}
                      {:candidate/id :beta :attempt-count 4
                       :passed-count 4 :reward-sum 4.0 :reward-mean 1.0}]
                     (:scorecard/summary scorecard)))
              (is (= scorecard (experiment/validate-scorecard scorecard)))
              (is (= scorecard
                     (experiment/scorecard room
                                           (:scorecard/content-id scorecard))))
              (is (= [scorecard]
                     (experiment/scorecards
                      room {:experiment-content-id
                            (:experiment/content-id definition)
                            :dataset-content-id
                            (get-in definition [:experiment/dataset
                                                :dataset/content-id])
                            :candidate-id :alpha
                            :candidate-content-id
                            (get-in definition [:experiment/candidates 0
                                                :candidate/agent-content-id])})))
              (is (= scorecard (experiment/persist-scorecard! room scorecard))
                  "repeating the same immutable projection is idempotent")
              (testing "a new content hash cannot forge certified rewards"
                (let [forged (-> scorecard
                                 (assoc-in [:scorecard/entries 0 :reward] 99.0)
                                 (assoc-in [:scorecard/summary 0 :reward-sum]
                                           102.0)
                                 (assoc-in [:scorecard/summary 0 :reward-mean]
                                           25.5)
                                 (dissoc :scorecard/content-id))
                      forged
                      (assoc forged :scorecard/content-id
                             (hasch/uuid
                              [:dvergr/experiment-scorecard forged]))]
                  (is (= forged (experiment/validate-scorecard forged)))
                  (is (thrown-with-msg?
                       clojure.lang.ExceptionInfo #"certified Attempt"
                       (experiment/persist-scorecard! room forged)))))
              (is (every? #(= % (store/-load-attempt
                                 (:store room) (:id room) (:attempt/id %)))
                          attempts))
              (is (empty? (run/active-runs (:id room))))
              (testing "entry and aggregate tampering are rejected"
                (is (thrown-with-msg?
                     clojure.lang.ExceptionInfo #"summary"
                     (experiment/validate-scorecard
                      (assoc-in scorecard [:scorecard/summary 0 :reward-mean]
                                0.5))))
                (is (thrown-with-msg?
                     clojure.lang.ExceptionInfo #"content ID"
                     (experiment/validate-scorecard
                      (assoc scorecard :scorecard/content-id (random-uuid))))))
              (testing "one certified attempt cannot fill two repetitions"
                (let [same-cell-results
                      (-> results
                          vec
                          (assoc-in [1 :attempt] (:attempt (first results))))]
                  (is (thrown-with-msg?
                       clojure.lang.ExceptionInfo #"distinct Attempts"
                       (experiment/make-scorecard definition
                                                  same-cell-results)))))
              (testing "exact environment identity determines canonical order"
                (let [entries (:scorecard/entries scorecard)
                      ;; This order is canonical under the old
                      ;; [candidate logical-environment-id repetition] key:
                      ;; versions compare equal within each repetition. It is
                      ;; not canonical under exact environment identity.
                      alternate-entries
                      (into [(nth entries 2) (nth entries 0)
                             (nth entries 3) (nth entries 1)]
                            (drop 4 entries))
                      swapped (-> scorecard
                                  (assoc :scorecard/entries alternate-entries)
                                  (dissoc :scorecard/content-id))
                      swapped (assoc swapped :scorecard/content-id
                                     (hasch/uuid
                                      [:dvergr/experiment-scorecard swapped]))]
                  (is (thrown-with-msg?
                       clojure.lang.ExceptionInfo #"canonical cell order"
                       (experiment/validate-scorecard swapped)))))
              (finally
                (doseq [result results]
                  (when-let [fork (some-> result :run/result :run/world
                                          registry/lookup)]
                    (d/discard fork))))))))
      (finally
        (d/close-room! room)))))

(deftest later-cell-failure-leaves-no-retained-run-worlds
  (let [{:keys [team definition]} (fixture)
        room (d/make-room {:id :experiment-partial-failure
                           :store (memory/make)})
        failing-evaluator
        (evaluation/make-evaluator
         {:id :test/exact
          :version 1
          :basis "experiment-test:v1"
          :observe (fn [{:keys [default]}] default)
          :verify (fn [definition _]
                    (if (= 2 (:environment/version definition))
                      (throw (ex-info "deliberate verifier failure" {}))
                      {:checks {:first-cell? true} :reward 1.0}))})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"certification failed"
             @(experiment/run room team definition
                              {(:ref failing-evaluator) failing-evaluator}
                              {:parallelism 1})))
        (is (empty? (run/active-runs (:id room))))
        (let [runs (run/runs room {:limit 20})]
          (is (seq runs))
          (is (every? #(= :discarded (:run/settlement-status %)) runs))
          (is (every? #(nil? (registry/lookup (:run/world %))) runs))))
      (finally
        (d/close-room! room)))))

(deftest parallel-cell-failure-has-a-host-cleanup-barrier
  (let [team (-> (roster/make-roster {:id :experiment/cleanup-team})
                 (roster/make-agent {:id :alpha :program {:kind :echo}}))
        dataset (experiment/make-dataset
                 {:id :experiment/cleanup-tasks
                  :environments [(environment :cleanup/slow-a 1 :slow-a)
                                 (environment :cleanup/slow-b 1 :slow-b)
                                 (environment :cleanup/fail 1 :fail)]})
        definition (experiment/make-experiment
                    {:id :experiment/parallel-cleanup
                     :dataset dataset
                     :candidates [(roster/agent team :alpha)]})
        slow-started (CountDownLatch. 2)
        evaluator
        (evaluation/make-evaluator
         {:id :test/exact
          :version 1
          :basis "experiment-test:v1"
          :observe (fn [{:keys [default]}] default)
          :verify
          (fn [environment _]
            (if (contains? #{:slow-a :slow-b}
                           (:environment/task environment))
              (do
                (.countDown slow-started)
                (Thread/sleep 10000)
                {:checks {:unexpected? true} :reward 0.0})
              (do
                (when-not (.await slow-started 5 TimeUnit/SECONDS)
                  (throw (ex-info "slow verifiers did not start" {})))
                (throw (ex-info "deliberate parallel verifier failure" {})))))})
        room (d/make-room {:id :experiment-parallel-cleanup
                           :store (memory/make)})
        discard! forks/discard-deferred!
        discard-count (atom 0)
        failing-discard
        (fn failing-discard
          ([fork reason]
           (failing-discard fork reason (constantly true)))
          ([fork reason claim!]
           (if (= 2 (swap! discard-count inc))
             {:ok? false
              :fork/id (:id fork)
              :error :deliberate-discard-failure}
             (discard! fork reason claim!))))]
    (try
      (with-redefs [forks/discard-deferred! failing-discard]
        (binding [ec/*execution-context* (:ctx room)]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"certification failed"
               @(experiment/run room team definition
                                {(:ref evaluator) evaluator}
                                {:parallelism 3}))))
        (let [error (try
                      (evaluation/await-cleanups! room 5000)
                      nil
                      (catch clojure.lang.ExceptionInfo error error))]
          (is (= :dvergr.agent.evaluation/cleanup-incomplete
                 (:type (ex-data error))))
          (is (= 1 (count (:failures (ex-data error))))))
        (is (= 3 @discard-count)
            "the barrier joins every sibling despite one cleanup failure"))
      (is (empty? (run/active-runs (:id room))))
      (let [runs (run/runs room {:limit 10})]
        (is (= 3 (count runs)))
        (is (= 2 (count (filter #(= :discarded
                                    (:run/settlement-status %))
                                runs))))
        ;; Recover the one retained affine world before closing the fixture.
        (binding [ec/*execution-context* (:ctx room)]
          (doseq [run runs
                  :let [fork (registry/lookup (:run/world run))]
                  :when fork]
            (is (:ok? (discard! fork :test-recovery))))))
      (finally
        (evaluation/await-cleanups! room 5000)
        (d/close-room! room)))))

(def ^:private recursive-program-result
  {:answer 23 :particles 2 :verified true})

(def ^:private recursive-program-code
  (str
   "(require '[dvergr.agent :as agent] "
   "         '[org.replikativ.spindel.spin.cps :refer [spin]] "
   "         '[org.replikativ.spindel.effects.await :refer [await]] "
   "         '[spindel.comb :as comb]) "
   "(let [team (-> (agent/roster {:id :particle-eval}) "
   "               (agent/make-agent {:id :mod-five "
   "                 :program {:kind :scripted "
   "                           :reply [8 23 38 53 68 83 98]}}) "
   "               (agent/make-agent {:id :mod-seven "
   "                 :program {:kind :scripted "
   "                           :reply [2 9 16 23 30 37 44 51 58 65 72 79 86 93]}}) "
   "               (agent/make-agent {:id :verifier "
   "                 :program {:kind :scripted "
   "                           :reply {:moduli [[3 2] [5 3] [7 2]] "
   "                                   :upper-bound 100}}})) "
   "      a (agent/hire! team :mod-five {:task :candidates}) "
   "      b (agent/hire! team :mod-seven {:task :candidates}) "
   "      v (agent/hire! team :verifier {:task :constraints}) "
   "      [ra rb rv] @(spin (await (comb/parallel "
   "                                  (agent/result-spin a) "
   "                                  (agent/result-spin b) "
   "                                  (agent/result-spin v)))) "
   "      [xs ys spec] (mapv :run/value [ra rb rv]) "
   "      candidates (filter (set ys) xs) "
   "      valid? (fn [n] (and (pos? n) (< n (:upper-bound spec)) "
   "                            (every? (fn [[m r]] (= r (mod n m))) "
   "                                    (:moduli spec)))) "
   "      answers (vec (filter valid? candidates))] "
   "  {:answer (first answers) :particles 2 :verified (= [23] answers)})"))

(defn- read-one-edn [value]
  (when (string? value)
    (try
      (edn/read-string value)
      (catch Throwable _ nil))))

(deftest deterministic-model-certifies-the-complete-recursive-harness-path
  (let [verifier-ref {:verifier/id :test/recursive-harness
                      :verifier/version 1
                      :verifier/basis "recursive-harness-test:v1"}
        definition
        (environment-def/make-environment
         {:id :test/recursive-harness
          :task :author-and-run-particle-program
          :verifier {:id :test/recursive-harness :version 1
                     :basis "recursive-harness-test:v1"}
          :limits {:timeout-ms 10000 :cancel-timeout-ms 2000
                   :max-model-steps 2 :budget-dollars 1.0}
          :world {:isolation :ctx :settlement :discard}})
        team (-> (roster/make-roster {:id :test/recursive-candidates})
                 (roster/make-agent
                  {:id :simulated-model
                   :tools #{:clojure_eval}
                   :model-policy {:provider :test :model "deterministic"}
                   :program {:kind :llm :max-model-steps 2
                             :budget-dollars 1.0 :auto-compact? false}}))
        experiment-def
        (experiment/make-experiment
         {:id :test/recursive-harness
          :dataset
          (experiment/make-dataset
           {:id :test/recursive-harness
            :environments [definition]})
          :candidates [(roster/agent team :simulated-model)]})
        evaluator
        (evaluation/make-evaluator
         {:id :test/recursive-harness
          :version 1
          :basis "recursive-harness-test:v1"
          :observe
          (fn [{:keys [room run-id result durable]}]
            (let [snapshot (observation/snapshot
                            room run-id
                            {:run-limit 20 :message-limit 100
                             :content-limit 1000 :content-budget 32000
                             :detail-limit 100})]
              {:result (read-one-edn (:run/value result))
               :durable-status (:run/status durable)
               :root-run-id run-id
               :runs (:observation/runs snapshot)
               :activities (:observation/activities snapshot)}))
          :verify
          (fn [_ {:keys [result durable-status root-run-id runs activities]}]
            (let [root (some #(when (= root-run-id (:run/id %)) %) runs)
                  children (remove #(= root-run-id (:run/id %)) runs)
                  child-ids (into #{} (map :run/id) children)
                  root-evals
                  (filter #(and (= root-run-id (:activity/run-id %))
                                (= :tool (:activity/kind %))
                                (= :invoke (:activity/verb %))
                                (= "clojure_eval" (:activity/tool-name %))
                                (= :completed (:activity/status %)))
                          activities)
                  checks
                  {:exact-result? (= recursive-program-result result)
                   :durably-completed? (= :completed durable-status)
                   :four-run-tree? (= 4 (count runs))
                   :three-specialists? (= #{:mod-five :mod-seven :verifier}
                                          (set (map :run/actor children)))
                   :structural-parentage?
                   (every? #(= root-run-id (:run/parent %)) children)
                   :all-results-observed?
                   (= child-ids (set (:run/caused-by root)))
                   :specialists-completed?
                   (every? #(= :completed (:run/status %)) children)
                   :specialists-merged?
                   (every? #(= :merged (:run/settlement-status %)) children)
                   :distinct-worlds?
                   (= 4 (count (set (map :run/world runs))))
                   :one-real-clojure-eval? (= 1 (count root-evals))}]
              {:checks checks
               :reward (if (every? true? (vals checks)) 1.0 0.0)}))})
        room (d/make-room {:id :experiment-recursive-harness
                           :store (memory/make)})
        calls (atom 0)
        forwarded-tool-result (atom nil)]
    (try
      (with-redefs
       [providers/ensure-initialized! (constantly nil)
        chat-agent/messages->api-format (fn [messages _ _] messages)
        model-chat/chat
        (fn [messages _]
          (case (swap! calls inc)
            1 {:content ""
               :tool-calls [{:id "recursive-program"
                             :name "clojure_eval"
                             :input {:code recursive-program-code}}]
               :usage {:input-tokens 10 :output-tokens 20}
               :stop-reason :tool-use}
            2 (let [content
                    (some (fn [message]
                            (when (and (= :tool-result (:message/role message))
                                       (= "recursive-program"
                                          (:message/tool-use-id message)))
                              (:message/content message)))
                          messages)
                    _ (when-not (and (string? content)
                                     (str/starts-with? content "=> "))
                        (throw (ex-info "missing recursive tool result"
                                        {:messages messages})))
                    value (edn/read-string (subs content 3))]
                (reset! forwarded-tool-result value)
                {:content (pr-str value)
                 :tool-calls nil
                 :usage {:input-tokens 30 :output-tokens 10}
                 :stop-reason :end-turn})
            (throw (ex-info "deterministic model called too often"
                            {:calls @calls}))))]
        (binding [ec/*execution-context* (:ctx room)]
          (let [{:keys [attempts scorecard]}
                @(experiment/run room team experiment-def
                                 {verifier-ref evaluator}
                                 {:parallelism 1})
                attempt (first attempts)
                checks (get-in attempt [:attempt/receipt :attempt/checks])
                runs (run/runs room {:limit 20})]
            (is (= 2 @calls))
            (is (= recursive-program-result @forwarded-tool-result)
                "the simulated model forwards the actual tool result")
            (is (= 1 (count attempts)))
            (is (every? true? (vals checks)) (pr-str checks))
            (is (= 1.0 (get-in attempt [:attempt/receipt :attempt/reward])))
            (is (= scorecard
                   (experiment/scorecard room (:scorecard/content-id scorecard))))
            (is (= [{:candidate/id :simulated-model
                     :attempt-count 1 :passed-count 1
                     :reward-sum 1.0 :reward-mean 1.0}]
                   (:scorecard/summary scorecard)))
            (is (empty? (run/active-runs (:id room))))
            (is (every? #(nil? (registry/lookup (:run/world %))) runs)
                "all discarded/merged Run worlds release their registry handles"))))
      (finally
        (evaluation/await-cleanups! room 5000)
        (d/close-room! room)))))
