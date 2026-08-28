(ns dvergr.sandbox.agent-programming-test
  "Provider-free acceptance tests for the functional agent surface in room SCI."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.run :as run]
            [dvergr.discourse :as d]
            [dvergr.room.store.memory :as memory]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.ns.agent :as agent-ns]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]))

(deftest immutable-rosters-launch-composable-room-runs-from-sci
  (let [room    (d/make-room {:id :sci-agent-programming
                              :store (memory/make)})
        sci-ctx (sandbox/fork-for-session (:ctx room))]
    (try
      ;; Keep this acceptance test focused on the new surface. Production calls
      ;; the same injector from setup-agent-namespaces!; doc-coverage exercises
      ;; that full wiring and catches any omission there.
      (agent-ns/add-programming-ns! sci-ctx (:id room) (:ctx room))
      (let [result
            (binding [ec/*execution-context* (:ctx room)]
              (sandbox/eval-code
               sci-ctx
               (str
                "(require '[dvergr.agent :as agent] "
                "         '[org.replikativ.spindel.spin.cps :refer [spin]] "
                "         '[org.replikativ.spindel.effects.await :refer [await]]) "
                "(let [empty-team (agent/roster {:id :investigation}) "
                "      team (-> empty-team "
                "               (agent/make-agent "
                "                {:id :analyst :skills #{:research} "
                "                 :program {:kind :scripted :reply \"evidence\"}}) "
                "               (agent/make-agent "
                "                {:id :reviewer :skills #{:review} "
                "                 :program {:kind :echo}})) "
                "      analyst (agent/hire! team :analyst {:task \"inspect\"}) "
                "      reviewer (agent/hire! team :reviewer {:task {:claim 42}}) "
                "      values @(spin [(-> (await (agent/result-spin analyst)) :run/value) "
                "                     (-> (await (agent/result-spin reviewer)) :run/value)])] "
                "  {:empty-count (count (agent/list empty-team)) "
                "   :team-count (count (agent/list team)) "
                "   :values values "
                "   :analyst-run (agent/run-id analyst) "
                "   :analyst-status (:run/status (agent/observe analyst))})")))]
        (testing "roster construction remains a value transformation"
          (is (:success result) (pr-str (:error result)))
          (is (= 0 (get-in result [:value :empty-count])))
          (is (= 2 (get-in result [:value :team-count]))))
        (testing "RunHandles compose through Spindel await in SCI"
          (is (= ["evidence" {:claim 42}] (get-in result [:value :values])))
          (is (uuid? (get-in result [:value :analyst-run])))
          (is (= :completed (get-in result [:value :analyst-status])))
          (is (empty? (run/active-runs (:id room))))))
      (finally
        (d/close-room! room)))))

(deftest roomless-sci-can-build-rosters-but-cannot-launch-effects
  (let [ctx     (context/create-execution-context)
        sci-ctx (sandbox/fork-for-session ctx)]
    (try
      (agent-ns/add-programming-ns! sci-ctx nil ctx)
      (let [pure (sandbox/eval-code
                  sci-ctx
                  (str "(require '[dvergr.agent :as agent]) "
                       "(-> (agent/roster) "
                       "    (agent/make-agent {:id :worker "
                       "                       :program {:kind :echo}}) "
                       "    agent/list count)"))
            effect (sandbox/eval-code
                    sci-ctx
                    (str "(require '[dvergr.agent :as agent]) "
                         "(agent/hire! (agent/make-agent "
                         "               (agent/roster) "
                         "               {:id :worker :program {:kind :echo}}) "
                         "             :worker {:task :work})"))]
        (is (= 1 (:value pure)))
        (is (false? (:success effect)))
        (is (re-find #"room-scoped" (get-in effect [:error :message]))))
      (finally
        (context/stop-context! ctx)))))
