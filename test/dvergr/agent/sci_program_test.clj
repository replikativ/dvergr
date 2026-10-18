(ns dvergr.agent.sci-program-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.authority :as authority]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.program :as program]
            [dvergr.agent.program-test :as fixtures]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.agent.sci-program :as source]
            [dvergr.agent.world :as world]
            [dvergr.catalog.workspace :as workspace]
            [dvergr.discourse :as discourse]
            [dvergr.resource :as resource]
            [dvergr.room.registry :as registry]
            [dvergr.room.store.memory :as memory]
            [dvergr.sandbox :as sandbox]
            [org.replikativ.spindel.engine.core :as ec]))

(defn definition [body]
  {:kind :sci :source (str "(ns example.program)\n" body)
   :entrypoint 'example.program/main :args [] :timeout-ms 10000})

(defn execute [body & [args]]
  (source/execute! (sandbox/create-base-ctx)
                   (cond-> (definition body) args (assoc :args args)) :task (fn [])))

(defn team [program]
  (roster/make-agent (roster/make-roster)
                     {:id :coordinator :tools #{} :sandbox #{} :program program}))

(def policy {:tools #{} :sandboxes #{#{}} :providers #{}
             :program-kinds #{:sci :echo :scripted} :max-attempts 2 :max-parallelism 1})

(deftest source-contract-is-portable-bounded-and-closed
  (let [good (definition "(defn main [task & args] [task args])")]
    (is (= good (source/validate! good)))
    (doseq [bad [(assoc good :entrypoint 'main) (dissoc good :source)
                 (assoc good :source " ") (assoc good :timeout-ms 0)
                 (assoc good :timeout-ms 600001) (assoc good :unknown true)
                 (assoc good :args [(atom 1)])
                 (assoc good :args [(apply str (repeat 262144 "x"))])
                 (assoc good :args [(nth (iterate vector 0) 70)])
                 (assoc good :source (apply str (repeat 33000 "🦋")))]]
      (is (thrown? clojure.lang.ExceptionInfo (source/validate! bad))))))

(deftest direct-evaluation-keeps-actual-data-and-bounds-lazy-values
  (is (= [:task [1 2]] (:value (execute "(defn main [task & args] [task (vec args)])" [1 2]))))
  (is (= [1 2 3] (:value (execute "(defn main [_] (map inc [0 1 2]))"))))
  (doseq [body ["(defn main [_] (fn [] :opaque))"
                "(defn main [_] (atom 1))"
                "(defn main [_] (repeat :unbounded))"]]
    (is (thrown? Exception (execute body))))
  (is (nil? (:value (execute "(defn main [_] nil)"))))
  (is (false? (:value (execute "(defn main [_] false)")))))

(deftest diagnostics-are-bounded-and-the-limit-cannot-be-swallowed
  (is (= "hello\n" (:stdout (execute "(defn main [_] (println \"hello\") :ok)"))))
  (is (thrown? Exception
               (execute "(defn main [_] (try (dotimes [_ 20000] (print \"x\")) (catch Throwable _ :caught)))"))))

(deftest map-byte-accounting-includes-encoded-separators
  (is (= 15 (count (pr-str {1 1 2 2 3 3}))))
  (with-redefs [source/max-value-bytes 14]
    (is (thrown? clojure.lang.ExceptionInfo (source/portable-value! {1 1 2 2 3 3})))))

(deftest portable-data-does-not-retain-a-mutable-number-handle
  (is (thrown? clojure.lang.ExceptionInfo
               (source/portable-value! (java.util.concurrent.atomic.AtomicLong. 1))))
  (is (= [1 1.5 1/2 1M 1N] (source/portable-value! [1 1.5 1/2 1M 1N]))))

(deftest input-data-is-never-reparsed-as-executable-source
  (let [evil (symbol "x) (throw (ex-info \"injected\" {})) (quote x")
        task {:symbol evil :keyword (keyword "x) (throw :injected)")}
        code (assoc (definition "(defn main [task arg] [task arg])") :args [task])
        result (source/execute! (sandbox/create-base-ctx) code task (fn []))]
    (is (= [task task] (:value result))))
  (is (thrown? clojure.lang.ExceptionInfo
               (source/execute! (sandbox/create-base-ctx)
                                (definition "(defn main [task] task)")
                                (vec (repeat 10001 :large)) (fn [])))))

(deftest old-restrictive-policies-do-not-admit-a-new-interpreter
  (let [agent (roster/agent (team (definition "(defn main [task] task)")) :coordinator)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (authority/check-agent! agent {:delegation-policy (dissoc policy :program-kinds)})))
    (is (= agent (authority/check-agent! agent {:delegation-policy policy})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (authority/validate-policy! (assoc policy :program-kinds #{:native}))))))

(deftest source-entrypoint-args-and-policy-change-input-identity
  (let [agent (roster/agent (team (definition "(defn main [task] task)")) :coordinator)
        input #(program/inputs-id (program/run-inputs % nil {:delegation-policy policy}))]
    (is (uuid? (input agent)))
    (doseq [changed [(assoc-in agent [:agent/program :source] "(ns example.program)(defn main [_] 2)")
                     (assoc-in agent [:agent/program :entrypoint] 'example.program/other)
                     (assoc-in agent [:agent/program :args] [1])
                     (assoc-in agent [:agent/program :timeout-ms] 9000)]]
      (is (not= (input agent) (input changed))))
    (is (not= (input agent)
              (program/inputs-id
               (program/run-inputs agent nil {:delegation-policy (assoc policy :program-kinds #{:sci})}))))))

(deftest a-real-sci-run-needs-no-model-and-records-its-inputs
  (let [room (discourse/make-room {:id :sci-value-test :store (memory/make)})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (registry/register! room)
        (doseq [value [nil false :dvergr.agent.program/sci-timeout {:answer 42}]]
          (let [result @(program/hire! room
                                       (team (definition "(defn main [task] task)"))
                                       :coordinator {:task value :settlement :discard})]
            (is (= :completed (:run/status result)))
            (is (= value (:run/value result)))
            (is (uuid? (get-in result [:run/metrics :inputs-id]))))))
      (finally (discourse/close-room! room)))))

(deftest sci-programs-hire-sci-children-under-the-same-scope
  (let [room (discourse/make-room {:id :sci-recursive-test :store (memory/make)})
        child (definition "(defn main [task n] {:child task :n n})")
        parent (definition
                 (str "(require '[dvergr.agent :as agent])\n"
                      "(defn main [task] (let [team (agent/make-agent (agent/roster) '"
                      (pr-str {:id :child :program (assoc child :args [7]) :tools #{} :sandbox #{}})
                      ") handle (agent/hire! team :child {:task task :role :worker :settlement :discard})"
                      " result @(agent/result-spin handle)] (:run/value result)))"))]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (registry/register! room)
        (let [h (program/hire-in! room room (team parent) :coordinator {:task :work :settlement :discard}
                                  {:delegation-policy policy})
              result @h
              children (filter :run/parent (run/runs room))]
          (is (= :completed (:run/status result)) (pr-str (select-keys result [:run/error :run/reason])))
          (is (= {:child :work :n 7} (:run/value result)))
          (is (= 1 (count children)))
          (is (= :worker (:run/role (first children))))
          (is (= :discarded (:run/settlement-status (first children))))
          (is (contains? (:run/caused-by (run/run room (program/run-id h))) (:run/id (first children))))))
      (finally (discourse/close-room! room)))))

(deftest program-kind-refusal-precedes-world-or-wallet-admission
  (let [room (discourse/make-room {:id :sci-refusal-test :store (memory/make)})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (with-redefs [world/open! (fn [& _] (throw (ex-info "unexpected world admission" {})))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds"
                                (program/hire-in! room room
                                                  (team (definition "(defn main [task] task)"))
                                                  :coordinator {:task nil}
                                                  {:delegation-policy (dissoc policy :program-kinds)}))))
        (is (empty? (run/runs room))))
      (finally (discourse/close-room! room)))))

(deftest a-funded-sci-driver-owns-a-child-experiment-and-returns-the-grant
  (let [[room conn] (#'fixtures/resource-test-room :sci-child-experiment-test)
        resources {"microUSD" 100M "model-dispatch" 2M}
        scorer (evaluation/make-evaluator
                {:id :sci/completion :version 1
                 :observe (fn [{:keys [default]}] default)
                 :verify (fn [_ _] {:checks {:completed? true} :reward 1.0})})
        code (definition
               "(require '[dvergr.agent :as agent])
                (defn main [task]
                  (let [team (agent/make-agent (agent/roster) {:id :echo :tools #{} :sandbox #{} :program {:kind :echo}})
                        env (agent/environment {:id :sci/env :task task :verifier {:id :sci/completion :version 1}
                                               :world {:isolation :ctx :settlement :discard
                                                       :resources {\"microUSD\" 20M \"model-dispatch\" 1M}}})
                        exp (agent/experiment {:id :sci/experiment :dataset (agent/dataset {:id :sci/data :environments [env]})
                                               :candidates [(agent/lookup team :echo)] :repetitions 1})
                        result @(agent/run-experiment! team exp {:resources {\"microUSD\" 20M \"model-dispatch\" 1M}})]
                    {:status (:status result) :entries (count (get-in result [:scorecard :scorecard/entries])) :job (:job result)}))")]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (registry/register! room)
        (resource/install-unit! room {:symbol "model-dispatch" :name "Dispatches" :precision 0})
        (resource/mint! room {:id (random-uuid) :resources resources})
        (let [h (program/hire-in! room room (team code) :coordinator
                                  {:task :work :resources resources :settlement :discard}
                                  {:delegation-policy policy
                                   :experiment-capabilities {:evaluators {(evaluation/evaluator-ref scorer) scorer}
                                                             :world-setups {}}})
              result @h]
          (is (= :completed (:run/status result)) (pr-str (select-keys result [:run/error])))
          (is (= :completed (get-in result [:run/value :status])))
          (is (= 1 (get-in result [:run/value :entries])))
          (is (= resources (resource/balance room)))
          (is (every? #(empty? (resource/run-wallet-balance room (:run/id %))) (run/runs room)))))
      (finally (#'fixtures/close-resource-test-room! room conn)))))

(deftest source-and-files-are-private-to-each-work-world
  (let [room (discourse/make-room {:id :sci-private-world-test :store (memory/make)})
        code (definition "(def private-counter (atom 0)) (defn main [task] (spit \"/out/value.edn\" (pr-str task)) {:count (swap! private-counter inc) :file (slurp \"/out/value.edn\")})")]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (registry/register! room)
        (workspace/ensure-workspace! room)
        (workspace/seed! room {"/out/value.edn" "trunk"})
        (doseq [task [:one :two]]
          (let [result @(program/hire! room (team code) :coordinator {:task task :settlement :discard})]
            (is (= :completed (:run/status result)))
            (is (= {:count 1 :file (pr-str task)} (:run/value result)))))
        (is (= "trunk" (get (workspace/read-tree room "/out") "/out/value.edn"))))
      (finally (discourse/close-room! room)))))

(deftest a-caught-timeout-cannot-be-published-as-success
  (let [room (discourse/make-room {:id :sci-timeout-test :store (memory/make)})
        code (assoc (definition "(defn main [_] (try (loop [] (recur)) (catch Throwable _ :caught)))")
                    :timeout-ms 500)]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (registry/register! room)
        (let [result @(program/hire! room (team code) :coordinator {:task nil :settlement :discard})]
          (is (= :failed (:run/status result)))
          (is (not= :caught (:run/value result)))))
      (finally (discourse/close-room! room)))))

(deftest fatal-source-failure-cancels-its-owned-child-before-settlement
  (let [room (discourse/make-room {:id :sci-failed-parent-test :store (memory/make)})
        code (definition
               "(require '[dvergr.agent :as agent])
                (defn main [_]
                  (let [team (agent/make-agent (agent/roster)
                               {:id :slow :tools #{} :sandbox #{} :program {:kind :echo :delay-ms 600000}})]
                    (agent/hire! team :slow {:task :pending :settlement :discard})
                    (throw (ex-info \"source failed\" {}))))")]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (registry/register! room)
        (let [h (program/hire-in! room room (team code) :coordinator {:task nil :settlement :discard}
                                  {:delegation-policy policy})
              result (deref (future @h) 25000 {:run/status :did-not-quiesce})
              child (first (filter :run/parent (run/runs room)))]
          (is (= :failed (:run/status result)))
          (is (= :cancelled (:run/status child)))
          (is (= :discarded (:run/settlement-status child)))))
      (finally (discourse/close-room! room)))))

(deftest cancellation-retains-the-wallet-until-native-work-acknowledges
  (let [[room conn] (#'fixtures/resource-test-room :sci-physical-cancel-test)
        entered (promise) release (promise)
        resources {"microUSD" 100M "model-dispatch" 2M}]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (registry/register! room)
        (resource/install-unit! room {:symbol "model-dispatch" :name "Dispatches" :precision 0})
        (resource/mint! room {:id (random-uuid) :resources resources})
        (with-redefs [source/execute!
                      (fn [_ _ _ check!]
                        (deliver entered true)
                        (loop []
                          (when-not (try @release (catch InterruptedException _ false)) (recur)))
                        (check!)
                        {:value :late})]
          (let [h (program/hire! room (team (definition "(defn main [_] :unused)"))
                                 :coordinator {:task nil :resources resources :settlement :discard})
                result (future @h)]
            (is (= true (deref entered 10000 :not-entered)))
            (program/cancel! room h)
            (is (not (realized? result)))
            (is (every? zero? (vals (resource/balance room))))
            (is (= resources (resource/run-wallet-balance room (program/run-id h))))
            (deliver release true)
            (is (= :cancelled (:run/status (deref result 25000 {}))))
            (is (= resources (resource/balance room))))))
      (finally
        (deliver release true)
        (#'fixtures/close-resource-test-room! room conn)))))
