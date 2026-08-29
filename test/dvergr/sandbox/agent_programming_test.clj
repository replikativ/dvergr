(ns dvergr.sandbox.agent-programming-test
  "Provider-free acceptance tests for the functional agent surface in room SCI."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.run :as run]
            [dvergr.discourse :as d]
            [dvergr.room.store.memory :as memory]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.ns.agent :as agent-ns]
            [dvergr.sandbox.ns.data :as data-ns]
            [dvergr.sandbox.ns.kb :as kb-ns]
            [dvergr.sandbox.ns.room :as room-ns]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- wait-until [pred timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (cond
        (pred) true
        (< (System/nanoTime) deadline) (do (Thread/sleep 5) (recur))
        :else false))))

(deftest immutable-rosters-launch-composable-room-runs-from-sci
  (let [room    (d/make-room {:id :sci-agent-programming
                              :store (memory/make)})
        sci-ctx (sandbox/fork-for-session (:ctx room))]
    (try
      ;; Keep this acceptance test focused on the new surface. Production calls
      ;; the same injector from setup-agent-namespaces!; doc-coverage exercises
      ;; that full wiring and catches any omission there.
      (agent-ns/add-programming-ns! sci-ctx (:id room) (:ctx room) nil)
      (data-ns/add-spindel-extras-ns! sci-ctx (:ctx room))
      (testing "progressive help contains an executable composition example"
        (let [guide (sandbox/ns-doc-md sci-ctx 'dvergr.agent)]
          (is (re-find #":scripted.*:reply" guide))
          (is (re-find #":delay-ms" guide))
          (is (re-find #"result-spin" guide))
          (is (re-find #"owned-result-spin" guide))
          (is (re-find #"comb/race" guide))
          (is (re-find #"await" guide)))
        (let [guide (sandbox/ns-doc-md sci-ctx 'spindel.comb)]
          (is (re-find #"cancel losing branches" guide))
          (is (re-find #"owned-result-spin" guide))))
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

(deftest room-sci-race-cancels-and-settles-its-owned-loser
  (let [room (d/make-room {:id :sci-agent-owned-race
                           :store (memory/make)})
        sci-ctx (sandbox/fork-for-session (:ctx room))]
    (try
      (agent-ns/add-programming-ns! sci-ctx (:id room) (:ctx room) nil)
      (data-ns/add-spindel-extras-ns! sci-ctx (:ctx room))
      (let [result
            (binding [ec/*execution-context* (:ctx room)]
              (sandbox/eval-code
               sci-ctx
               (str
                "(require '[dvergr.agent :as agent] "
                "         '[org.replikativ.spindel.spin.cps :refer [spin]] "
                "         '[org.replikativ.spindel.effects.await :refer [await]] "
                "         '[spindel.comb :as comb]) "
                "(let [team (-> (agent/roster) "
                "               (agent/make-agent {:id :fast :program {:kind :scripted :delay-ms 10 :reply :fast}}) "
                "               (agent/make-agent {:id :slow :program {:kind :scripted :delay-ms 5000 :reply :slow}})) "
                "      a (agent/hire! team :fast {:task :solve}) "
                "      b (agent/hire! team :slow {:task :solve})] "
                "  @(spin (-> (await (comb/race (agent/owned-result-spin a) "
                "                                (agent/owned-result-spin b))) "
                "             :run/value)))")))]
        (is (:success result) (pr-str (:error result)))
        (is (= :fast (:value result)))
        (is (wait-until #(empty? (run/active-runs (:id room))) 1000))
        (let [by-actor (into {} (map (juxt :run/actor identity)) (run/runs room))]
          (is (= :completed (get-in by-actor [:fast :run/status])))
          (is (= :cancelled (get-in by-actor [:slow :run/status])))))
      (finally
        (d/close-room! room)))))

(deftest roomless-sci-can-build-rosters-but-cannot-launch-effects
  (let [ctx     (context/create-execution-context)
        sci-ctx (sandbox/fork-for-session ctx)]
    (try
      (agent-ns/add-programming-ns! sci-ctx nil ctx nil)
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

(deftest delegation-ceiling-allows-pure-children-but-rejects-paid-children
  (let [room    (d/make-room {:id :sci-agent-delegation-ceiling
                              :store (memory/make)})
        sci-ctx (sandbox/fork-for-session (:ctx room))]
    (try
      (agent-ns/add-programming-ns!
       sci-ctx (:id room) (:ctx room)
       {:program-kinds #{:echo :scripted}})
      (let [prefix (str
                    "(require '[dvergr.agent :as agent] "
                    "         '[org.replikativ.spindel.spin.cps :refer [spin]] "
                    "         '[org.replikativ.spindel.effects.await :refer [await]]) "
                    "(def team (-> (agent/roster) "
                    "              (agent/make-agent {:id :pure :program {:kind :echo}}) "
                    "              (agent/make-agent {:id :paid :program {:kind :llm} "
                    "                                 :model-policy {:provider :test :model \"stub\"}}))) ")
            pure (binding [ec/*execution-context* (:ctx room)]
                   (sandbox/eval-code
                    sci-ctx
                    (str prefix
                         "@(spin (-> (await (agent/result-spin "
                         "                    (agent/hire! team :pure {:task :ok}))) "
                         "            :run/value))")))
            paid (binding [ec/*execution-context* (:ctx room)]
                   (sandbox/eval-code
                    sci-ctx
                    (str prefix
                         "(agent/hire! team :paid {:task :forbidden})")))]
        (is (= :ok (:value pure)))
        (is (false? (:success paid)))
        (is (re-find #"delegation ceiling" (get-in paid [:error :message])))
        (is (empty? (run/active-runs (:id room)))
            "rejected authority never admits a Run"))
      (finally
        (d/close-room! room)))))

(deftest ambient-parent-run-is-the-default-structural-parent
  (let [room      (d/make-room {:id :sci-agent-ambient-parent
                                :store (memory/make)})
        sci-ctx   (sandbox/fork-for-session (:ctx room))
        parent-id (random-uuid)]
    (try
      (agent-ns/add-programming-ns!
       sci-ctx (:id room) (:ctx room)
       {:program-kinds #{:echo :scripted}
        :parent-run parent-id})
      (let [result
            (binding [ec/*execution-context* (:ctx room)]
              (sandbox/eval-code
               sci-ctx
               (str
                "(require '[dvergr.agent :as agent] "
                "         '[org.replikativ.spindel.spin.cps :refer [spin]] "
                "         '[org.replikativ.spindel.effects.await :refer [await]]) "
                "(let [team (agent/make-agent (agent/roster) "
                "                             {:id :child :program {:kind :echo}}) "
                "      child (agent/hire! team :child {:task :work})] "
                "  @(spin (await (agent/result-spin child))) "
                "  (:run/parent (agent/observe child)))")))]
        (is (:success result) (pr-str (:error result)))
        (is (= parent-id (:value result))))
      (finally
        (d/close-room! room)))))

(deftest legacy-room-hire-is-not-an-alternate-delegation-path
  (let [room    (d/make-room {:id :sci-legacy-hire-ceiling
                              :store (memory/make)})
        sci-ctx (sandbox/fork-for-session (:ctx room))]
    (try
      (room-ns/add-room-ns!
       sci-ctx nil nil (:id room) (:ctx room)
       {:program-kinds #{:echo :scripted}})
      (let [result
            (binding [ec/*execution-context* (:ctx room)]
              (sandbox/eval-code
               sci-ctx
               "(require '[dvergr.room :as room]) (room/hire :sci-legacy-hire-ceiling {:goal :forbidden})"))]
        (is (false? (:success result)))
        (is (re-find #"(?:Could not|Unable to) resolve symbol:?.*room/hire"
                     (get-in result [:error :message])))
        (is (empty? (run/active-runs (:id room)))
            "the removed API cannot start an untracked execution"))
      (finally
        (d/close-room! room)))))

(deftest nested-sci-cannot-bypass-authority-through-cheap-llm-calls
  (let [ctx     (context/create-execution-context)
        sci-ctx (sandbox/fork-for-session ctx)]
    (try
      (kb-ns/add-llm-ns! sci-ctx {:provider-effects? false})
      (doseq [form ["(require '[llm]) (llm/call \"classify\" \"input\")"
                    "(require '[llm]) (llm/summarize \"input\")"]]
        (let [result (sandbox/eval-code sci-ctx form)]
          (is (false? (:success result)))
          (is (re-find #"provider effects.*delegation ceiling"
                       (get-in result [:error :message])))))
      (finally
        (context/stop-context! ctx)))))
