(ns dvergr.agent.evaluation-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.episode :as episode]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.program :as program]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.discourse :as d]
            [dvergr.room.registry :as registry]
            [dvergr.room.store :as store]
            [dvergr.room.store.memory :as memory]
            [dvergr.rooms.forks :as forks]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.protocols :as rtp]
            [org.replikativ.spindel.spin.combinators :as comb]
            [org.replikativ.spindel.spin.core :as spin-core]
            [org.replikativ.spindel.yggdrasil :as ygg]))

(defn- test-room [id]
  (d/make-room {:id id :store (memory/make)}))

(defrecord RejectingAttemptStore [delegate]
  store/PRoomStore
  (-store-room! [_ room-id metadata]
    (store/-store-room! delegate room-id metadata))
  (-load-room [_ id] (store/-load-room delegate id))
  (-delete-room! [_ room-id] (store/-delete-room! delegate room-id))
  (-list-rooms [_] (store/-list-rooms delegate))
  (-store-message! [_ room-id message]
    (store/-store-message! delegate room-id message))
  (-message-thread-root [_ room-id message-id]
    (store/-message-thread-root delegate room-id message-id))
  (-list-messages [_ room-id opts]
    (store/-list-messages delegate room-id opts))
  (-store-run! [_ room-id value]
    (store/-store-run! delegate room-id value))
  (-load-run [_ room-id run-id]
    (store/-load-run delegate room-id run-id))
  (-list-runs [_ room-id opts]
    (store/-list-runs delegate room-id opts))

  store/PAttemptStore
  (-store-attempt! [_ _room-id _value] nil)
  (-load-attempt [_ room-id attempt-id]
    (store/-load-attempt delegate room-id attempt-id))
  (-list-attempts [_ room-id opts]
    (store/-list-attempts delegate room-id opts)))

(defn- wait-until [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline)
        (do (Thread/sleep 5) (recur))
        :else false))))

(deftest cleanup-groups-isolate-operation-failures
  (let [room (test-room :cleanup-groups)
        first-group (evaluation/cleanup-group)
        second-group (evaluation/cleanup-group)
        first-error (ex-info "first cleanup" {})
        second-error (ex-info "second cleanup" {})
        first-task (#'evaluation/start-task!
                    room first-group #(throw first-error))
        second-task (#'evaluation/start-task!
                     room second-group #(throw second-error))]
    (try
      @(:gate first-task)
      @(:gate second-task)
      (let [failure (try
                      (evaluation/await-cleanups-for! room first-group 1000)
                      nil
                      (catch clojure.lang.ExceptionInfo error error))]
        (is (= :dvergr.agent.evaluation/cleanup-incomplete
               (:type (ex-data failure))))
        (is (= [first-error]
               (mapv :error (:failures (ex-data failure))))))
      (is (true? (evaluation/await-cleanups-for! room first-group 1000))
          "the first group was consumed without touching the second")
      (let [failure (try
                      (evaluation/await-cleanups-for! room second-group 1000)
                      nil
                      (catch clojure.lang.ExceptionInfo error error))]
        (is (= [second-error]
               (mapv :error (:failures (ex-data failure))))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (evaluation/await-cleanups-for! room :not-a-uuid 1000)))
      (is (true? (evaluation/await-cleanups! room 1000)))
      (finally
        (d/close-room! room)))))

(defn- definition [id opts]
  (environment/make-environment
   (merge {:id id
           :task {:claim 42}
           :verifier {:id :test/exact :version 1 :basis "test:v1"}
           :limits {:timeout-ms 2000 :cancel-timeout-ms 1000}
           :world {:isolation :ctx :settlement :review}}
          opts)))

(def exact-evaluator
  (evaluation/make-evaluator
   {:id :test/exact
    :version 1
    :basis "test:v1"
    :observe (fn [{:keys [default]}] default)
    :verify (fn [environment evidence]
              (let [exact? (= (:environment/task environment)
                              (:result evidence))]
                {:checks {:exact? exact?}
                 :reward (if exact? 1.0 0.0)}))}))

(defn- discard-retained! [result]
  (when-let [fork (some-> result :run/result :run/world registry/lookup)]
    (d/discard fork)))

(deftest evaluation-is-a-lazy-composable-spin-over-ordinary-run-worlds
  (let [room (test-room :evaluation-parallel)
        team (roster/make-agent
              (roster/make-roster)
              {:id :candidate
               :program {:kind :echo}})
        env (definition :test/parallel {})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [a (evaluation/evaluate room team :candidate env exact-evaluator)
              b (evaluation/evaluate room team :candidate env exact-evaluator)]
          (is (empty? (run/active-runs (:id room)))
              "constructing evaluation Spins has no execution effect")
          (let [results @(apply comb/parallel [a b])]
            (try
              (is (= 2 (count results)))
              (is (= 2 (count (set (map :run/id results)))))
              (is (every? #(= 1.0 (get-in % [:attempt-receipt :attempt/reward]))
                          results))
              (is (every? #(= {:exact? true}
                              (get-in % [:attempt-receipt :attempt/checks]))
                          results))
              (is (every? #(= :review
                              (get-in % [:run/result :run/settlement-status]))
                          results))
              (is (every? #(= [:dvergr "echo" :echo false]
                              [(get-in % [:attempt-receipt :attempt/provider])
                               (get-in % [:attempt-receipt :attempt/model])
                               (get-in % [:attempt-receipt :attempt/metrics
                                          :program-kind])
                               (get-in % [:attempt-receipt :attempt/metrics
                                          :timed-out?])])
                          results))
              (is (every? #(= [:not-applicable 1 5]
                              [(get-in % [:attempt-receipt :attempt/metrics
                                          :model-resolution])
                               (get-in % [:attempt-receipt :attempt/metrics
                                          :agent-version])
                               (get-in % [:attempt-receipt :attempt/metrics
                                          :interpreter-version])])
                          results))
              (is (every? uuid?
                          (map #(get-in % [:attempt-receipt :attempt/metrics
                                           :agent-def-hash])
                               results)))
              (is (every? #(= (:attempt %)
                              (store/-load-attempt (:store room) (:id room)
                                                   (:run/id %)))
                          results)
                  "certification is durable before a retained world is exposed")
              (is (every? #(= (:attempt %)
                              (:episode/attempt
                               (episode/export room (:run/id %))))
                          results))
              (finally
                (doseq [result results] (discard-retained! result)))))))
      (finally
        (d/close-room! room)))))

(deftest host-evaluator-workers-can-compose-and-deref-spins
  (let [room (test-room :evaluation-worker-spin)
        team (roster/make-agent
              (roster/make-roster)
              {:id :candidate
               :program {:kind :echo}})
        env (definition :test/worker-spin {})
        evaluator
        (evaluation/make-evaluator
         {:id :test/exact
          :version 1
          :basis "test:v1"
          :observe (fn [{:keys [default]}]
                     @(sp/spin default))
          :verify (fn [environment evidence]
                    @(sp/spin
                      (let [exact? (= (:environment/task environment)
                                      (:result evidence))]
                        {:checks {:exact? exact?}
                         :reward (if exact? 1.0 0.0)})))})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [result @(evaluation/evaluate room team :candidate env evaluator)]
          (try
            (is (= {:exact? true}
                   (get-in result [:attempt-receipt :attempt/checks])))
            (is (= 1.0 (get-in result [:attempt-receipt :attempt/reward])))
            (finally
              (discard-retained! result)))))
      (finally
        (evaluation/await-cleanups! room 5000)
        (d/close-room! room)))))

(deftest evaluation-fails-closed-when-attempt-certification-is-not-durable
  (let [delegate (memory/make)
        room (d/make-room {:id :evaluation-attempt-durability
                           :store (->RejectingAttemptStore delegate)})
        team (roster/make-agent (roster/make-roster)
                                {:id :candidate :program {:kind :echo}})
        env (definition :test/attempt-durability {})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [error (try
                      @(evaluation/evaluate room team :candidate env
                                            exact-evaluator)
                      nil
                      (catch Throwable error error))
              run-id (:run/id (ex-data error))]
          (is error)
          (is (= ::evaluation/certification-failed
                 (:type (ex-data error))))
          (is (uuid? run-id))
          (is (nil? (store/-load-attempt delegate (:id room) run-id)))
          (is (= :discarded
                 (:run/settlement-status
                  (store/-load-run delegate (:id room) run-id))))
          (is (nil? (registry/lookup
                     (:run/world (store/-load-run delegate (:id room) run-id)))))))
      (finally
        (d/close-room! room)))))

(deftest evaluation-rejects-a-mismatched-host-capability-before-admission
  (let [room (test-room :evaluation-mismatch)
        team (roster/make-agent (roster/make-roster)
                                {:id :candidate :program {:kind :echo}})
        env (definition :test/mismatch {})
        wrong (evaluation/make-evaluator
               {:id :test/other :observe (constantly {})
                :verify (fn [_ _] {:checks {} :reward 0.0})})]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match"
                            (evaluation/evaluate room team :candidate env wrong)))
      (is (empty? (run/active-runs (:id room))))
      (finally
        (d/close-room! room)))))

(deftest evaluation-refuses-to-merge-before-trusted-scoring
  (let [room (test-room :evaluation-unsafe-settlement)
        team (roster/make-agent (roster/make-roster)
                                {:id :candidate :program {:kind :echo}})
        env (definition :test/unsafe
              {:world {:isolation :ctx :settlement :automatic}})]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"retain successful worlds"
                            (evaluation/evaluate
                             room team :candidate env exact-evaluator)))
      (is (empty? (run/active-runs (:id room))))
      (finally
        (d/close-room! room)))))

(deftest evaluation-requires-portable-evidence
  (let [room (test-room :evaluation-evidence)
        team (roster/make-agent (roster/make-roster)
                                {:id :candidate :program {:kind :echo}})
        env (definition :test/evidence {:world {:settlement :discard}})
        evaluator
        (evaluation/make-evaluator
         {:id :test/exact :version 1 :basis "test:v1"
          :observe (fn [_] {:live (atom 1)})
          :verify (fn [_ _] {:checks {} :reward 0.0})})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [error (try
                      @(evaluation/evaluate room team :candidate env evaluator)
                      nil
                      (catch clojure.lang.ExceptionInfo error error))]
          (is (= :dvergr.agent.evaluation/certification-failed
                 (:type (ex-data error))))
          (is (re-find #"portable map" (ex-message (ex-cause error))))
          (is (nil? (registry/lookup (:run/world (ex-data error)))))))
      (is (empty? (run/active-runs (:id room))))
      (finally
        (d/close-room! room)))))

(deftest deferred-world-cannot-settle-before-scoring
  (let [room (test-room :evaluation-deferred-gate)
        team (roster/make-agent (roster/make-roster)
                                {:id :candidate :program {:kind :echo}})
        env (definition :test/deferred {})
        premature (atom nil)
        evaluator
        (evaluation/make-evaluator
         {:id :test/exact :version 1 :basis "test:v1"
          :observe (fn [{:keys [result default]}]
                     (let [fork (registry/lookup (:run/world result))]
                       (reset! premature {:merge (forks/merge! fork)
                                          :discard (forks/discard! fork)
                                          :direct-merge-blocked?
                                          (try
                                            (d/merge-room room fork)
                                            false
                                            (catch clojure.lang.ExceptionInfo _
                                              true))
                                          :direct-discard-blocked?
                                          (try
                                            (d/discard fork)
                                            false
                                            (catch clojure.lang.ExceptionInfo _
                                              true))
                                          :deferred? (forks/deferred? fork)}))
                     default)
          :verify (fn [_ _] {:checks {:verified? true} :reward 1.0})})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [result @(evaluation/evaluate room team :candidate env evaluator)
              fork (registry/lookup (get-in result [:run/result :run/world]))]
          (is (false? (get-in @premature [:merge :ok?])))
          (is (false? (get-in @premature [:discard :ok?])))
          (is (true? (:direct-merge-blocked? @premature)))
          (is (true? (:direct-discard-blocked? @premature)))
          (is (true? (:deferred? @premature)))
          (is (= :review (get-in result [:run/result :run/settlement-status])))
          (is (false? (forks/deferred? fork)))
          (is (= :review (:run/settlement-status
                          (program/observe room (:run/handle result)))))
          (d/discard fork)))
      (finally
        (d/close-room! room)))))

(deftest cancellation-during-host-scoring-cannot-certify-or-release
  (let [room (test-room :evaluation-cancel-scoring)
        team (roster/make-agent (roster/make-roster)
                                {:id :candidate :program {:kind :echo}})
        env (definition :test/cancel-scoring {})
        entered (promise)
        release (promise)
        observed (atom nil)
        evaluator
        (evaluation/make-evaluator
         {:id :test/exact :version 1 :basis "test:v1"
          :observe (fn [{:keys [default run-id result]}]
                     (reset! observed {:run/id run-id
                                       :run/world (:run/world result)})
                     (deliver entered true)
                     @release
                     default)
          :verify (fn [_ _] {:checks {:verified? true} :reward 1.0})})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [attempt (evaluation/evaluate room team :candidate env evaluator)]
          (sp/spawn! attempt)
          (is (= true (deref entered 5000 ::timeout)))
          (spin-core/cancel-spin! attempt)
          (deliver release true)
          (is (wait-until
               #(let [{:run/keys [id world]} @observed
                      durable (when id (run/run room id))]
                  (and (= :discarded (:run/settlement-status durable))
                       (= :evaluation-cancelled
                          (:run/settlement-reason durable))
                       (nil? (registry/lookup world))))
               5000))
          (is (not= :review
                    (:run/settlement-status
                     (run/run room (:run/id @observed)))))
          (is (nil? (store/-load-attempt (:store room) (:id room)
                                         (:run/id @observed)))
              "cancellation before certification leaves no durable Attempt")))
      (finally
        (d/close-room! room)))))

(deftest cancellation-at-the-settlement-gate-wins-before-release
  (let [room (test-room :evaluation-cancel-release)
        team (roster/make-agent (roster/make-roster)
                                {:id :candidate :program {:kind :echo}})
        env (definition :test/cancel-release {})
        entered (promise)
        release (promise)
        observed (atom nil)
        original-release forks/release-deferred!
        evaluator
        (evaluation/make-evaluator
         {:id :test/exact :version 1 :basis "test:v1"
          :observe (fn [{:keys [default run-id result]}]
                     (reset! observed {:run/id run-id
                                       :run/world (:run/world result)})
                     default)
          :verify (fn [_ _] {:checks {:verified? true} :reward 1.0})})]
    (try
      (with-redefs [forks/release-deferred!
                    (fn [fork reason claim!]
                      (deliver entered true)
                      @release
                      (original-release fork reason claim!))]
        (binding [ec/*execution-context* (:ctx room)]
          (let [attempt (evaluation/evaluate room team :candidate env evaluator)]
            (sp/spawn! attempt)
            (is (= true (deref entered 5000 ::timeout)))
            (spin-core/cancel-spin! attempt)
            (deliver release true)
            (is (wait-until
                 #(let [{:run/keys [id world]} @observed
                        durable (when id (run/run room id))]
                    (and (= :discarded (:run/settlement-status durable))
                         (= :evaluation-cancelled
                            (:run/settlement-reason durable))
                         (nil? (registry/lookup world))))
                 5000))
            (is (nil? (store/-load-attempt (:store room) (:id room)
                                           (:run/id @observed)))
                "cancellation at the affine claim gate wins before persistence"))))
      (finally
        (d/close-room! room)))))

(deftest exact-world-setup-prepares-only-the-candidate-fork
  (let [room (test-room :evaluation-world-setup)
        team (roster/make-agent (roster/make-roster)
                                {:id :candidate :program {:kind :echo}})
        setup-ref {:setup/id :test/fixture :setup/version 1
                   :setup/basis "fixture:v1"}
        env (definition :test/setup
              {:world {:isolation :ctx :settlement :discard
                       :setup setup-ref}})
        setup
        (evaluation/make-world-setup
         {:id :test/fixture :version 1 :basis "fixture:v1"
          :prepare
          (fn [{work-room :room run-id :run/id}]
            ;; Exercise the ordinary ambient Spindel state API, not only an
            ;; explicit Room operation. This state must land in the candidate
            ;; fork and disappear with its discard, never touch the parent.
            (ec/swap-state! [:test :world-setup] (constantly run-id))
            (d/post! work-room
                     (d/message :fixture :_fixture {:run/id run-id}))
            {:fixture/run-id run-id
             :ambient-is-work-context?
             (identical? (ec/current-execution-context) (:ctx work-room))
             :ambient-spin-detached? (nil? ec/*spin-id*)
             :work-state (ec/get-state [:test :world-setup])
             :parent-state (rtp/get-state (:ctx room)
                                          [:test :world-setup])})})
        evaluator
        (evaluation/make-evaluator
         {:id :test/exact :version 1 :basis "test:v1"
          :observe
          (fn [{control-room :room work-room :world/room
                setup-evidence :setup/evidence default :default}]
            (let [fixture? #(= :fixture (:from %))]
              (assoc default
                     :setup-evidence setup-evidence
                     :work-fixtures (count (filter fixture?
                                                   (d/messages work-room)))
                     :control-fixtures (count (filter fixture?
                                                      (d/messages control-room))))))
          :verify
          (fn [_ evidence]
            (let [exact? (= {:claim 42} (:result evidence))
                  isolated? (= [1 0] [(:work-fixtures evidence)
                                      (:control-fixtures evidence)])
                  same-run? (= (get-in evidence [:trace :runs 0 :run/id])
                               (get-in evidence [:setup-evidence
                                                 :fixture/run-id]))
                  ambient-isolated?
                  (let [{:keys [fixture/run-id ambient-is-work-context?
                                ambient-spin-detached? work-state parent-state]}
                        (:setup-evidence evidence)]
                    (and ambient-is-work-context?
                         ambient-spin-detached?
                         (= run-id work-state)
                         (nil? parent-state)))]
              {:checks {:exact? exact? :isolated? isolated?
                        :same-run? same-run?
                        :ambient-isolated? ambient-isolated?}
               :reward (if (and exact? isolated? same-run? ambient-isolated?)
                         1.0
                         0.0)}))})]
    (try
      (is (= setup-ref (evaluation/world-setup-ref setup)))
      (binding [ec/*execution-context* (:ctx room)]
        (let [result @(evaluation/evaluate room team :candidate env evaluator
                                           {:world-setup setup})]
          (is (= {:exact? true :isolated? true :same-run? true
                  :ambient-isolated? true}
                 (get-in result [:attempt-receipt :attempt/checks])))
          (is (= :discarded
                 (get-in result [:run/result :run/settlement-status])))
          (is (nil? (registry/lookup
                     (get-in result [:run/result :run/world]))))))
      (is (empty? (filter #(= :fixture (:from %)) (d/messages room))))
      (is (nil? (rtp/get-state (:ctx room) [:test :world-setup])))
      (is (empty? (run/active-runs (:id room))))
      (finally
        (d/close-room! room)))))

(deftest world-setup-mismatch-and-failure-are-closed-before-candidate-work
  (let [room (test-room :evaluation-world-setup-failure)
        team (roster/make-agent (roster/make-roster)
                                {:id :candidate :program {:kind :echo}})
        setup-ref {:setup/id :test/fixture :setup/version 1}
        env (definition :test/setup-failure
              {:world {:isolation :ctx :settlement :discard
                       :setup setup-ref}})
        wrong (evaluation/make-world-setup
               {:id :test/wrong :prepare (constantly nil)})
        failing (evaluation/make-world-setup
                 {:id :test/fixture
                  :prepare
                  (fn [{work-room :room}]
                    (d/post! work-room (d/message :fixture :_fixture :partial))
                    (throw (ex-info "deliberate setup failure" {})))})]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires an exact"
                            (evaluation/evaluate room team :candidate env
                                                 exact-evaluator)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match"
                            (evaluation/evaluate room team :candidate env
                                                 exact-evaluator
                                                 {:world-setup wrong})))
      (is (empty? (run/runs room)))
      (binding [ec/*execution-context* (:ctx room)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"deliberate setup failure"
                              @(evaluation/evaluate room team :candidate env
                                                    exact-evaluator
                                                    {:world-setup failing}))))
      (let [[failed] (run/runs room)]
        (is (= :failed (:run/status failed)))
        (is (= :world-setup-failed (:run/reason failed)))
        (is (= :discarded (:run/settlement-status failed)))
        (is (some #(= (:run/trigger failed) (:id %)) (d/messages room))
            "the failed Run retains a real causal trigger")
        (binding [ec/*execution-context* (:ctx room)]
          (is (nil? (registry/lookup (:run/world failed))))))
      (is (empty? (filter #(= :fixture (:from %)) (d/messages room))))
      (is (empty? (run/active-runs (:id room))))
      (finally
        (d/close-room! room)))))

(deftest world-setup-timeout-is-supervised-and-never-scored
  (let [room (test-room :evaluation-world-setup-timeout)
        team (roster/make-agent (roster/make-roster)
                                {:id :candidate :program {:kind :echo}})
        env (definition :test/setup-timeout
              {:limits {:timeout-ms 20 :cancel-timeout-ms 2000}
               :world {:isolation :ctx :settlement :discard
                       :setup {:setup/id :test/slow :setup/version 1}}})
        entered (promise)
        interrupted (promise)
        setup (evaluation/make-world-setup
               {:id :test/slow
                :prepare
                (fn [_]
                  (deliver entered true)
                  (try
                    (Thread/sleep 10000)
                    (catch InterruptedException error
                      (deliver interrupted true)
                      (throw error))))})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [started (System/currentTimeMillis)
              error (try
                      @(evaluation/evaluate room team :candidate env
                                            exact-evaluator
                                            {:world-setup setup})
                      nil
                      (catch clojure.lang.ExceptionInfo error error))]
          (is (= true (deref entered 1000 ::timeout)))
          (is (= true (deref interrupted 1000 ::timeout)))
          (is (= ::evaluation/world-setup-failed
                 (:type (ex-data error))))
          (is (< (- (System/currentTimeMillis) started) 2000))))
      (let [[cancelled] (run/runs room)]
        (is (= :cancelled (:run/status cancelled)))
        (is (= :world-setup-cancelled (:run/reason cancelled)))
        (is (= :discarded (:run/settlement-status cancelled)))
        (is (some #(= (:run/trigger cancelled) (:id %)) (d/messages room))))
      (is (empty? (filter #(= :candidate (:from %)) (d/messages room))))
      (is (empty? (episode/attempts room)))
      (is (empty? (run/active-runs (:id room))))
      (finally
        (d/close-room! room)))))

(deftest world-setup-failure-retains-recovery-when-discard-fails
  (let [room (test-room :evaluation-world-setup-discard-failure)
        team (roster/make-agent (roster/make-roster)
                                {:id :candidate :program {:kind :echo}})
        env (definition :test/setup-discard-failure
              {:world {:isolation :ctx :settlement :discard
                       :setup {:setup/id :test/failing :setup/version 1}}})
        setup (evaluation/make-world-setup
               {:id :test/failing
                :prepare #(throw (ex-info "broken fixture" %))})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (with-redefs [d/discard-deferred
                      (fn [& _] (throw (ex-info "discard unavailable" {})))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"broken fixture"
                                @(evaluation/evaluate room team :candidate env
                                                      exact-evaluator
                                                      {:world-setup setup})))))
      (let [[failed] (run/runs room)
            retained (binding [ec/*execution-context* (:ctx room)]
                       (registry/lookup (:run/world failed)))]
        (is (= :failed (:run/status failed)))
        (is (= :world-setup-failed (:run/reason failed)))
        (is (= :review (:run/settlement-status failed)))
        (is (= :settlement-failed (:run/settlement-reason failed)))
        (is (some? retained) "the failed world remains available for recovery")
        (is (empty? (episode/attempts room)))
        (is (empty? (run/active-runs (:id room))))
        (when retained
          (binding [ec/*execution-context* (:ctx room)]
            (d/discard-deferred retained))))
      (finally
        (d/close-room! room)))))

(deftest malformed-verifier-result-discards-uncertified-world
  (let [room (test-room :evaluation-malformed-score)
        team (roster/make-agent (roster/make-roster)
                                {:id :candidate :program {:kind :echo}})
        env (definition :test/malformed-score {})
        evaluator
        (evaluation/make-evaluator
         {:id :test/exact :version 1 :basis "test:v1"
          :observe (fn [{:keys [default]}] default)
          :verify (fn [_ _] {:checks {:not :boolean} :reward ##NaN})})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [error (try
                      @(evaluation/evaluate room team :candidate env evaluator)
                      nil
                      (catch clojure.lang.ExceptionInfo error error))]
          (is (= :dvergr.agent.evaluation/certification-failed
                 (:type (ex-data error))))
          (is (uuid? (:run/id (ex-data error))))
          (is (nil? (registry/lookup (:run/world (ex-data error)))))
          (is (= :evaluation-certification-failed
                 (:run/settlement-reason
                  (run/run room (:run/id (ex-data error))))))
          (is (nil? (store/-load-attempt (:store room) (:id room)
                                         (:run/id (ex-data error)))))))
      (is (empty? (run/active-runs (:id room))))
      (finally
        (d/close-room! room)))))

(deftest discard-projection-failure-preserves-the-deferred-world
  (let [room (test-room :evaluation-discard-durability)
        team (roster/make-agent (roster/make-roster)
                                {:id :candidate :program {:kind :echo}})
        env (definition :test/discard-durability
              {:world {:isolation :ctx :settlement :discard}})
        world-id (atom nil)]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [error
              (with-redefs [run/update-durable-settlement!
                            (fn [& _]
                              (throw (ex-info "store unavailable"
                                              {:type ::store-unavailable})))]
                (try
                  @(evaluation/evaluate room team :candidate env exact-evaluator)
                  nil
                  (catch clojure.lang.ExceptionInfo error
                    (reset! world-id (:run/world (ex-data error)))
                    error)))
              durable (run/run room (:run/id (ex-data error)))
              fork (registry/lookup @world-id)]
          (is (= :dvergr.agent.evaluation/settlement-recovery-required
                 (:type (ex-data error))))
          (is (= :deferred (:run/settlement-status durable)))
          (is (some? fork))
          (is (forks/deferred? fork))
          (is (some? (store/-load-attempt (:store room) (:id room)
                                          (:run/id (ex-data error))))
              "certification remains truthful if later settlement projection fails")
          ;; Restore the real durability boundary before consuming the world.
          (is (:ok? (forks/discard-deferred!
                     fork :test-cleanup
                     (constantly true))))))
      (finally
        (d/close-room! room)))))

(deftest review-projection-failure-retains-certified-world-for-recovery
  (let [room (test-room :evaluation-review-durability)
        team (roster/make-agent (roster/make-roster)
                                {:id :candidate :program {:kind :echo}})
        env (definition :test/review-durability {})
        original-update run/update-durable-settlement!
        original-observe program/observe
        calls (atom [])
        observations (atom 0)
        world-id (atom nil)]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [error
              (with-redefs [run/update-durable-settlement!
                            (fn [parent id status & [reason]]
                              (swap! calls conj status)
                              (if (= :review status)
                                (throw (ex-info "store unavailable"
                                                {:type ::store-unavailable}))
                                (original-update parent id status reason)))
                            program/observe
                            (fn [parent handle]
                              (if (= 1 (swap! observations inc))
                                (original-observe parent handle)
                                (throw (ex-info "store remains unavailable"
                                                {:type ::store-unavailable}))))]
                (try
                  @(evaluation/evaluate room team :candidate env exact-evaluator)
                  nil
                  (catch clojure.lang.ExceptionInfo error
                    (reset! world-id (:run/world (ex-data error)))
                    error)))
              run-id (:run/id (ex-data error))
              durable (run/run room run-id)
              fork (registry/lookup @world-id)]
          (is (= :dvergr.agent.evaluation/settlement-recovery-required
                 (:type (ex-data error))))
          (is (= [:review] @calls)
              "failed settlement is not followed by destructive cleanup")
          (is (= 1 @observations)
              "recovery delivery does not depend on another store read")
          (is (= :deferred (:run/settlement-status durable)))
          (is (some? fork))
          (is (forks/deferred? fork))
          (is (some? (store/-load-attempt (:store room) (:id room) run-id)))
          ;; Restore the real durability boundary before consuming the world.
          (is (:ok? (forks/discard-deferred!
                     fork :test-cleanup
                     (constantly true))))))
      (finally
        (d/close-room! room)))))

(deftest physical-discard-failure-compensates-or-retains-a-recovery-claim
  (let [room (test-room :evaluation-discard-abort)
        team (roster/make-agent (roster/make-roster)
                                {:id :candidate :program {:kind :echo}})
        original-update run/update-durable-settlement!]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [handle (program/hire! room team :candidate
                                    {:task :work :settlement :deferred})
              result @handle
              run-id (program/run-id handle)
              fork (registry/lookup (:run/world result))]
          (with-redefs [ygg/discard-fork!
                        (fn [& _] (throw (ex-info "substrate failed" {})))]
            (is (false? (:ok? (forks/discard-deferred!
                               fork :test-discard))))
            (is (= [:deferred :discard-failed true]
                   ((juxt :run/settlement-status :run/settlement-reason
                          (constantly (forks/deferred? fork)))
                    (run/run room run-id)))))
          (with-redefs [ygg/discard-fork!
                        (fn [& _] (throw (ex-info "substrate failed" {})))
                        run/update-durable-settlement!
                        (fn [parent id status & [reason]]
                          (if (= :deferred status)
                            (throw (ex-info "abort store unavailable" {}))
                            (original-update parent id status reason)))]
            (is (false? (:ok? (forks/discard-deferred!
                               fork :test-discard))))
            (is (= :discarded
                   (:run/settlement-status (run/run room run-id))))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"not awaiting deferred settlement"
                                  (forks/release-deferred!
                                   fork :must-remain-fenced))))
          (is (:ok? (forks/retry-deferred-discard-abort! fork)))
          (is (= :deferred
                 (:run/settlement-status (run/run room run-id))))
          (is (:ok? (forks/discard-deferred! fork :test-cleanup)))))
      (finally
        (d/close-room! room)))))

(deftest evaluation-timeout-cancels-and-quiesces-before-certification
  (let [room (test-room :evaluation-timeout)
        team (roster/make-agent
              (roster/make-roster)
              {:id :slow
               :program {:kind :scripted :delay-ms 1000 :reply :late}})
        env (definition :test/timeout
              {:task :late
               :limits {:timeout-ms 10 :cancel-timeout-ms 2000}})
        timeout-evaluator
        (evaluation/make-evaluator
         {:id :test/exact :version 1 :basis "test:v1"
          :observe (fn [{:keys [default durable]}]
                     (assoc default :durable-status (:run/status durable)))
          :verify (fn [_ evidence]
                    (let [cancelled? (= :cancelled (:durable-status evidence))]
                      {:checks {:cancelled? cancelled?}
                       :reward (if cancelled? 1.0 0.0)}))})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [result @(evaluation/evaluate room team :slow env timeout-evaluator)
              receipt (:attempt-receipt result)]
          (is (= :cancelled (:attempt/status receipt)))
          (is (true? (get-in receipt [:attempt/metrics :timed-out?])))
          (is (= {:cancelled? true} (:attempt/checks receipt)))
          (is (= :discarded (get-in result [:run/result :run/settlement-status])))
          (is (empty? (run/active-runs (:id room))))))
      (finally
        (d/close-room! room)))))
