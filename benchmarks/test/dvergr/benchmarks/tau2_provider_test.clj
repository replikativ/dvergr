(ns dvergr.benchmarks.tau2-provider-test
  "tau2 on the generic evaluation path: the grader behind the evaluator
   boundary, the conversation hosted by the Run in a forked world that is
   discarded after certification, experiments through `experiment/run` with
   resume. Scripted models replay the gold actions."
  (:require [dvergr.test-support :as support]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.agent.episode :as attempts]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.benchmarks.tau2.core :as t2]
            [dvergr.benchmarks.tau2.experiment :as tx]
            [dvergr.benchmarks.tau2.provider :as provider]
            [dvergr.discourse :as d]
            [dvergr.model.chat :as model-chat]
            [dvergr.room.registry :as registry]
            [dvergr.room.store.memory :as memory]
            [org.replikativ.spindel.engine.core :as ec]))

(def ^:private checkout?
  (.exists (io/file t2/default-root "data/tau2/domains/telecom/db.toml")))

(defn- without-nl [domain]
  (update domain :tasks
          (fn [tasks] (into (array-map)
                            (map (fn [[k t]] [k (update-in t ["evaluation_criteria" "reward_basis"]
                                                           #(vec (remove #{"NL_ASSERTION"} %)))]))
                            tasks))))

(defn- gold-pair
  "Scripted candidate and customer sharing one cursor over the task's gold
   actions: whichever side owns the next action performs it."
  []
  (let [cursors (atom {})
        cursor (fn [task] (or (get @cursors (get task "id"))
                              (get (swap! cursors assoc (get task "id") (atom 0)) (get task "id"))))
        actions #(vec (get-in % ["evaluation_criteria" "actions"]))
        user? #(= "user" (get % "requestor"))
        emit (fn [task]
               (let [c (cursor task)
                     {:strs [name arguments action_id]} (nth (actions task) @c)]
                 (swap! c inc)
                 {:tool-calls [{:id (or action_id (str "a" @c)) :name name :arguments arguments}]}))]
    {:agent-generate
     (fn [task]
       (fn [_]
         (let [a (get (actions task) @(cursor task))]
           (cond (nil? a) {:content (str "All done. "
                                         (str/join " " (get-in task ["evaluation_criteria" "communicate_info"]))
                                         " ###STOP###")}
                 (user? a) {:content "Please go ahead on your side."}
                 :else (emit task)))))
     :user-fn-for
     (fn [task]
       (fn [_]
         (let [a (get (actions task) @(cursor task))]
           (if (and a (user? a)) (emit task) {:content "Done on my side."}))))}))

(defn- team []
  (tx/candidate-roster [{:id :gold :harness :reference :model "claude-code-sonnet"}]))

(deftest retail-gold-through-evaluate
  (if-not checkout?
    (support/skip! "retail-gold-through-evaluate: no ../tau2-bench checkout")
    (let [dom (without-nl (t2/load-domain "retail"))
          task (get-in dom [:tasks "0"])
          {:keys [agent-generate user-fn-for]} (gold-pair)
          caps (provider/capabilities dom {:user-fn (user-fn-for task)
                                           :agent-generate agent-generate})
          env (provider/environment-def dom "0" caps {:timeout-ms 60000})
          room (d/make-room {:id :tau2/provider-retail :store (memory/make)})]
      (try
        (binding [ec/*execution-context* (:ctx room)]
          (let [result @(evaluation/evaluate room (team) :gold env (:evaluator caps)
                                             {:world-setup (:world-setup caps)
                                              :protocol (:protocol caps)})
                receipt (:attempt-receipt result)
                attempt (first (attempts/attempts room {:limit 10}))]
            (is (= 1.0 (:attempt/reward receipt)))
            (is (= :completed (:attempt/status receipt)))
            (is (= :trusted (get-in receipt [:attempt/metrics :verifier-trust])))
            (is (true? (get-in receipt [:attempt/checks :db])))
            (testing "the certified Attempt is durable and carries the graded log"
              (is (= (:attempt/id receipt) (:attempt/id attempt)))
              (is (seq (get-in attempt [:attempt/evidence :trajectory])))
              (is (= :agent-stop (get-in attempt [:attempt/evidence :result :termination]))))
            (testing "the episode ran in a forked world that was discarded"
              (is (= :discarded (:run/settlement-status (run/run room (:run/id result)))))
              (is (nil? (registry/lookup (:run/world result)))))
            (testing "no Run is left alive"
              (is (empty? (run/active-runs (:id room)))))))
        (finally (d/close-room! room))))))

(deftest telecom-experiment-resumes
  (if-not checkout?
    (support/skip! "telecom-experiment-resumes: no ../tau2-bench checkout")
    (let [dom (without-nl (t2/load-domain "telecom"))
          ids (->> (get-in dom [:splits "base"])
                   (filter #(some (fn [a] (= "user" (get a "requestor")))
                                  (get-in dom [:tasks % "evaluation_criteria" "actions"])))
                   (take 2) vec)
          {:keys [agent-generate user-fn-for]} (gold-pair)
          caps (provider/capabilities dom {:user-generate user-fn-for
                                           :agent-generate agent-generate})
          envs (mapv #(provider/environment-def dom % caps {:timeout-ms 60000}) ids)
          team (team)
          exp (experiment/make-experiment
               {:id :tau2/provider-telecom
                :dataset (experiment/make-dataset {:id :tau2.telecom/two :environments envs})
                :candidates (roster/agents team)
                :repetitions 1})
          run-exp (fn [room]
                    (binding [ec/*execution-context* (:ctx room)]
                      @(experiment/run room team exp
                                       {(evaluation/evaluator-ref (:evaluator caps)) (:evaluator caps)}
                                       {:world-setups {(evaluation/world-setup-ref (:world-setup caps)) (:world-setup caps)}
                                        :protocols {(evaluation/protocol-ref (:protocol caps)) (:protocol caps)}
                                        :resume? true})))
          room (d/make-room {:id :tau2/provider-telecom :store (memory/make)})]
      (try
        (let [first-run (run-exp room)]
          (is (= 2 (count (:attempts first-run))))
          (is (every? #(= 1.0 (get-in % [:attempt/receipt :attempt/reward])) (:attempts first-run)))
          (is (every? #(true? (get-in % [:attempt/receipt :attempt/checks :env-assertion]))
                      (:attempts first-run))
              "graded by the domain's environment assertions")
          (testing "a second run of the same ExperimentDef resumes: nothing is re-run"
            (let [second-run (run-exp room)]
              (is (every? :resumed? (:results second-run)))
              (is (= (set (map :attempt/id (:attempts first-run)))
                     (set (map :attempt/id (:attempts second-run)))))
              (is (= 2 (count (attempts/attempts room {:limit 100})))))))
        (finally (d/close-room! room))))))

(def ^:private usage {:input-tokens 1 :output-tokens 1})

(defn- scripted-chat [responses]
  (let [n (atom -1)]
    (fn [_ _] (nth responses (min (swap! n inc) (dec (count responses)))))))

(deftest dvergr-candidates-through-evaluate
  (if-not checkout?
    (support/skip! "dvergr-candidates-through-evaluate: no ../tau2-bench checkout")
    (let [dom (without-nl (t2/load-domain "retail"))
          gold (get-in dom [:tasks "0" "evaluation_criteria" "actions"])
          tool-responses (conj (mapv (fn [{:strs [name arguments action_id]}]
                                       {:content "" :usage usage
                                        :tool-calls [{:id action_id :name name :input arguments}]})
                                     gold)
                               {:content "Done. ###STOP###" :usage usage})
          code (str "(doseq [[n a] " (pr-str (mapv (fn [{:strs [name arguments]}] [name arguments]) gold))
                    "] ((ns-resolve 'tau2 (symbol n)) a))")
          repl-responses [{:content "" :usage usage
                           :tool-calls [{:id "e1" :name "clojure_eval" :input {:code code}}]}
                          {:content "Done. ###STOP###" :usage usage}]]
      (doseq [[action-space responses] [[:tools tool-responses] [:repl repl-responses]]]
        (testing (str action-space)
          (let [caps (provider/capabilities dom {:user-fn (constantly {:content "I need help with an exchange."})})
                env (provider/environment-def dom "0" caps {:timeout-ms 120000})
                team (tx/candidate-roster [{:id :dv :harness :dvergr :action-space action-space
                                            :model "claude-code-sonnet"}] dom)
                room (d/make-room {:id (keyword "tau2" (str "provider-dv-" (name action-space)))
                                   :store (memory/make)})]
            (try
              (with-redefs [model-chat/chat (scripted-chat responses)]
                (binding [ec/*execution-context* (:ctx room)]
                  (let [result @(evaluation/evaluate room team :dv env (:evaluator caps)
                                                     {:world-setup (:world-setup caps)
                                                      :protocol (:protocol caps)})
                        receipt (:attempt-receipt result)
                        attempt (first (attempts/attempts room {:limit 10}))]
                    (is (= :completed (:attempt/status receipt)) (pr-str (:run/result result)))
                    (is (= 1.0 (:attempt/reward receipt)))
                    (is (= (count gold)
                           (count (filter #(= :tool (:kind %))
                                          (get-in attempt [:attempt/evidence :trajectory]))))
                        "every tool call, including those made inside clojure_eval, is in the graded log")
                    (is (empty? (run/active-runs (:id room)))))))
              (finally (d/close-room! room)))))))))
