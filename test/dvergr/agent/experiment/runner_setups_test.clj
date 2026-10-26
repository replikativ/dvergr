(ns dvergr.agent.experiment.runner-setups-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.experiment.runner :as runner]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.discourse :as discourse]
            [dvergr.room.store.memory :as memory]
            [org.replikativ.spindel.blocking]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- setup [id prepare]
  (evaluation/make-world-setup {:id id :basis {:fixture id} :prepare prepare}))

(def evaluator
  (evaluation/make-evaluator
   {:id :runner-setups/exact
    :observe (fn [{:keys [result] evidence :setup/evidence}]
               {:result result :setup evidence})
    :verify (fn [definition evidence]
              (let [ok? (= (:environment/task definition) (:setup evidence))]
                {:checks {:own-setup? ok?} :reward (if ok? 1.0 0.0)}))}))

(defn- forwarded [capabilities calls]
  (with-redefs [experiment/run (fn [& args] (swap! calls conj args) :forwarded)]
    (runner/run-in {:ctx nil}
                   {:capabilities (assoc capabilities :evaluator evaluator)
                    :experiment {:experiment/candidates []
                                 :experiment/dataset {:dataset/environments []}
                                 :experiment/repetitions 1}})))

(deftest exact-setup-maps-forward-through-the-existing-engine
  (let [a (setup :fixture/a (constantly :a))
        b (setup :fixture/b (constantly :b))
        setups {(:ref a) a (:ref b) b} calls (atom [])]
    (is (= :forwarded (forwarded {:world-setups setups} calls)))
    (is (= setups (:world-setups (last (first @calls)))))
    (reset! calls [])
    (is (= :forwarded (forwarded {:world-setup a} calls)))
    (is (= {(:ref a) a} (:world-setups (last (first @calls)))))
    (doseq [capabilities [{} {:world-setups {}} {:world-setup nil}]]
      (reset! calls [])
      (is (= :forwarded (forwarded capabilities calls)))
      (is (= {} (:world-setups (last (first @calls))))))))

(deftest invalid-capabilities-refuse-before-experiment-or-prediction
  (let [a (setup :fixture/a (constantly :a))
        origin {"fixture" {:version 1 :kind :datahike :commit (random-uuid)
                           :source {:store (random-uuid) :repository (random-uuid)}}}
        calls (atom [])]
    (doseq [capabilities
            [{:world-setup nil :world-setups {}}
             {:world-setup a :world-setups nil}
             {:world-setup a :world-setups {(:ref a) a}}
             {:world-setups nil} {:world-setups []}
             {:world-setups {(:ref a) {:ref (:ref a) :prepare (constantly :a)}}}
             {:world-setups {(:ref a) (assoc a :prepare nil)}}
             {:world-setups {(:ref a) a {:setup/id :wrong :setup/version 1} a}}
             {:world-setups {{:setup/id :wrong :setup/version 1} a}}
             {:world-setups {(assoc (:ref a) :unknown :field) (assoc-in a [:ref :unknown] :field)}}
             {:world-setups {(assoc (:ref a) :setup/checkpoints origin)
                             (assoc a :ref (assoc (:ref a) :setup/checkpoints origin) :checkpoints {})}}
             {:world-setups {(:ref a) (assoc a :checkpoints origin)}}]]
      (is (thrown? clojure.lang.ExceptionInfo (forwarded capabilities calls))))
    (is (empty? @calls))))

(deftest distinct-environments-use-only-their-own-setup-and-resume
  (let [prepared (atom [])
        a (setup :fixture/a (fn [_] (swap! prepared conj :a) :a))
        b (setup :fixture/b (fn [_] (swap! prepared conj :b) :b))
        team (roster/make-agent (roster/make-roster {:id :runner-setups/team})
                                {:id :candidate :program {:kind :echo}})
        environments (mapv (fn [[id capability]]
                             (environment/make-environment
                              {:id (keyword "runner-setups" (name id)) :task id
                               :verifier {:id :runner-setups/exact :version 1}
                               :limits {:timeout-ms 2000 :cancel-timeout-ms 1000}
                               :world {:isolation :ctx :settlement :discard :setup (:ref capability)}}))
                           [[:a a] [:b b]])
        definition (experiment/make-experiment
                    {:id :runner-setups/paired
                     :dataset (experiment/make-dataset {:id :runner-setups/cases :environments environments})
                     :candidates [(roster/agent team :candidate)]})
        room (discourse/make-room {:id :runner-setups/integration :store (memory/make)})
        options {:team team :experiment definition
                 :capabilities {:evaluator evaluator :world-setups {(:ref a) a (:ref b) b}}}]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [first-run @(runner/run-in room options)
              resumed @(runner/run-in room options)]
          (is (= {:a 1 :b 1} (frequencies @prepared)))
          (is (= 2 (count (:attempts first-run))))
          (is (= 2 (count (:scorecard/entries (:scorecard first-run)))))
          (is (every? #(= 1.0 (:reward %)) (:scorecard/entries (:scorecard first-run))))
          (is (= (set (map :attempt/id (:attempts first-run)))
                 (set (map :attempt/id (:attempts resumed)))))
          (is (= 2 (count (run/runs room {:limit 10}))))))
      (finally
        (evaluation/await-cleanups! room 5000)
        (discourse/close-room! room)))))
