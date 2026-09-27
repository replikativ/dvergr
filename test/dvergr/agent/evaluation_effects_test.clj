(ns dvergr.agent.evaluation-effects-test
  "An environment's `:world :effects` configures the Attempt's isolated world:
   everything that runs there performs under those handlers (doc/effects.md)."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.program :as program]
            [dvergr.agent.roster :as roster]
            [dvergr.discourse :as d]
            [dvergr.effects :as effects]
            [dvergr.room.store.memory :as memory]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.sync :as sync]))

(def ^:private team
  (roster/make-agent (roster/make-roster) {:id :candidate :program {:kind :echo}}))

(def ^:private evaluator
  (evaluation/make-evaluator
   {:id :test/effects
    :observe (fn [{:keys [default result]}]
               (assoc default :completed? (= :completed (:run/status result))))
    :verify (fn [_ evidence]
              {:checks {:completed? (:completed? evidence)}
               :reward (if (:completed? evidence) 1.0 0.0)})}))

(defn- run! [room definition]
  (let [done (promise)]
    (binding [ec/*execution-context* (:ctx room)]
      (sync/spawn! (evaluation/evaluate room team :candidate definition evaluator)
                   {:on-success #(deliver done {:ok %}) :on-error #(deliver done {:error %})}))
    (let [r (deref done 15000 ::timeout)]
      (when (= ::timeout r) (throw (ex-info "Test timed out" {})))
      (if-let [e (:error r)] (throw e) (:ok r)))))

(deftest the-environment-configures-the-attempts-world
  (let [room (d/make-room {:id :evaluation-effects :store (memory/make)})
        seen (atom nil)
        original @#'program/execute-program
        definition (environment/make-environment
                    {:id :test/effects :task :work
                     :verifier {:id :test/effects :version 1}
                     :world {:isolation :ctx :settlement :discard
                             :effects {:faults {:seed 1 :rate 1.0 :only #{:fs/write}}}}
                     :limits {:timeout-ms 5000 :cancel-timeout-ms 5000}})]
    (with-redefs-fn
      {#'program/execute-program
       (fn [& args]
         (sp/spin
          (let [world (:ctx (second args))
                specs (effects/world-handlers world)
                ;; what a sandbox in this world would perform under
                boundary (effects/boundary-resolver nil nil {:world #(effects/world-handlers world)})]
            (reset! seen {:specs specs
                          :write (try (effects/perform! boundary {:effect :fs/write :resource {:path "a"}}
                                                        (constantly "a"))
                                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
                          :read (effects/perform! boundary {:effect :fs/read :resource {:path "a"}}
                                                  (constantly "content"))}))
          (sp/await (apply original args))))}
      (fn []
        (let [result (run! room definition)]
          (testing "the Attempt ran in a world with the environment's handlers"
            (is (= [:faults] (mapv first (:specs @seen))))
            (is (= :effect/fault (:write @seen)) "a write in scope faults")
            (is (= "content" (:read @seen)) "an effect out of scope reaches the world"))
          (testing "the fault state is released with the Attempt"
            (let [id (:id (second (first (:specs @seen))))]
              (is (thrown-with-msg? Exception #"No effect handler state"
                                    (effects/perform! (constantly {:handlers (effects/handlers [[:faults {:id id}]])})
                                                      {:effect :fs/write :resource {:path "a"}}
                                                      (constantly nil))))))
          (is (= :completed (get-in result [:attempt-receipt :attempt/status])))))))
  (testing "an unsupported configuration is refused up front"
    (let [room (d/make-room {:id :evaluation-effects-bad :store (memory/make)})]
      (is (thrown-with-msg? Exception #"not a supported handler configuration"
                            (run! room (environment/make-environment
                                        {:id :test/effects :task :work
                                         :verifier {:id :test/effects :version 1}
                                         :world {:isolation :ctx :settlement :discard
                                                 :effects {:faults {:rate 2}}}
                                         :limits {:timeout-ms 5000 :cancel-timeout-ms 5000}})))))))
