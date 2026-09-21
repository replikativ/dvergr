(ns dvergr.benchmarks.bfcl-provider-test
  "BFCL on the generic evaluation path, with scripted candidates (no model)."
  (:require [dvergr.test-support :as support]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.agent.episode :as attempts]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.run :as run]
            [dvergr.benchmarks.bfcl.core :as bfcl]
            [dvergr.benchmarks.bfcl.equivalence :as eq]
            [dvergr.benchmarks.bfcl.harness :as harness]
            [dvergr.benchmarks.bfcl.provider :as provider]
            [dvergr.discourse :as d]
            [dvergr.model.chat :as model-chat]
            [dvergr.room.store.memory :as memory]
            [org.replikativ.spindel.engine.core :as ec]))

(def ^:private checkout?
  (.exists (io/file bfcl/default-root (:path bfcl/upstream) "bfcl_eval" "data")))

(defn- as-tool-calls
  "Decoded calls `[{name args}]` as a model response's `:tool-calls`."
  [calls]
  (map-indexed (fn [i call]
                 (let [[tool-name args] (first call)]
                   {:id (str "call_" i) :name tool-name :arguments args}))
               calls))

(defn- scripted
  "A candidate that answers every task with `(answer task)`: decoded calls, or
   a string for a text answer."
  [answer]
  (fn [task]
    (fn [request]
      (let [a (answer task request)]
        (if (string? a)
          {:content a :tool-calls []}
          {:content "" :tool-calls (as-tool-calls a)})))))

(defn- team []
  (provider/candidate-roster [{:id :scripted :model "claude-code-sonnet"}]))

(defn- evaluate! [room tasks task-id answer]
  (let [caps (provider/capabilities tasks {:agent-generate (scripted answer)})
        env (provider/environment-def (get tasks task-id) caps {:timeout-ms 60000})]
    (binding [ec/*execution-context* (:ctx room)]
      @(evaluation/evaluate room (team) :scripted env (:evaluator caps)
                            {:world-setup (:world-setup caps)
                             :protocol (:protocol caps)}))))

(deftest a-task-through-evaluate
  (if-not checkout?
    (support/skip! "a-task-through-evaluate: no ../gorilla checkout")
    (let [tasks (provider/tasks ["parallel" "irrelevance"])
          room (d/make-room {:id :bfcl/provider-test :store (memory/make)})
          seen (atom nil)]
      (try
        (testing "the gold answer, as a model would emit it"
          (let [result (evaluate! room tasks "parallel_0"
                                  (fn [task request] (reset! seen request) (eq/gold-calls task)))
                receipt (:attempt-receipt result)
                attempt (first (attempts/attempts room {:limit 10}))]
            (is (= 1.0 (:attempt/reward receipt)))
            (is (= :completed (:attempt/status receipt)))
            (is (= :trusted (get-in receipt [:attempt/metrics :verifier-trust])))
            (is (true? (get-in receipt [:attempt/checks :valid])))
            (testing "the candidate got the question and the compiled tools, not the answers"
              (is (= :user (:role (first (:messages @seen)))))
              (is (= (count (:functions (get tasks "parallel_0"))) (count (:tools @seen))))
              (is (not (re-find #"ground" (pr-str @seen)))))
            (testing "the certified Attempt carries the recorded calls"
              (is (= (:attempt/id receipt) (:attempt/id attempt)))
              (is (= (eq/gold-calls (get tasks "parallel_0"))
                     (get-in attempt [:attempt/evidence :calls]))))
            (testing "the run's world was discarded"
              (is (= :discarded (:run/settlement-status (run/run room (:run/id result))))))))
        (testing "a wrong answer scores zero and says why"
          (let [receipt (:attempt-receipt
                         (evaluate! room tasks "parallel_0"
                                    (fn [task _] (vec (rest (eq/gold-calls task))))))]
            (is (= 0.0 (:attempt/reward receipt)))
            (is (false? (get-in receipt [:attempt/checks
                                         :bfcl.error/parallel_function_checker_no_order_wrong_count])))))
        (testing "irrelevance: a text answer passes, a call fails"
          (is (= 1.0 (:attempt/reward (:attempt-receipt
                                       (evaluate! room tasks "irrelevance_0"
                                                  (fn [_ _] "I cannot help with that using these functions."))))))
          (is (= 0.0 (:attempt/reward (:attempt-receipt
                                       (evaluate! room tasks "irrelevance_0"
                                                  (fn [_ request]
                                                    [{(get-in (first (:tools request)) ["function" "name"]) {}}])))))))
        (testing "no Run is left alive"
          (is (empty? (run/active-runs (:id room)))))
        (finally (d/close-room! room))))))

(deftest an-experiment-through-the-shared-runner
  (if-not checkout?
    (support/skip! "an-experiment-through-the-shared-runner: no ../gorilla checkout")
    (let [dir (str (java.nio.file.Files/createTempDirectory
                    "bfcl-exp" (make-array java.nio.file.attribute.FileAttribute 0)))
          calls (atom 0)
          opts {:dir dir
                :categories ["simple_python" "irrelevance"] :sample 3
                :candidates [{:id :scripted :model "claude-code-sonnet"}]
                :host-context-note nil
                ;; right on AST tasks, wrong on irrelevance (it always calls)
                :agent-generate (scripted (fn [task request]
                                            (swap! calls inc)
                                            (if (= :ast (:kind task))
                                              (eq/gold-calls task)
                                              [{(get-in (first (:tools request)) ["function" "name"]) {}}])))}
          run-it #((requiring-resolve 'dvergr.benchmarks.bfcl.experiment/run!) opts)
          first-pass (run-it)]
      (is (= 6 (:results first-pass)))
      (is (zero? (:failed-cells first-pass)))
      (is (= 6 @calls))
      (testing "the sample is stable, so a second pass resumes into it"
        (is (= 6 (:results (run-it))))
        (is (= 6 @calls) "no cell ran again")))))

;; -----------------------------------------------------------------------------
;; Dvergr's agent step as the candidate (the model is stubbed)

(def ^:private usage {:input-tokens 10 :output-tokens 5})

(deftest dvergr-agent-step-candidates
  (if-not checkout?
    (support/skip! "dvergr-agent-step-candidates: no ../gorilla checkout")
    (let [tasks (provider/tasks ["parallel"])
          task (get tasks "parallel_0")
          gold (eq/gold-calls task)
          requests (atom [])
          ;; the model's ONE response per action space: parallel JSON tool
          ;; calls, or one evaluation holding every call
          responses {:tools {:content "" :usage usage
                             :tool-calls (map-indexed (fn [i call]
                                                        (let [[n args] (first call)]
                                                          {:id (str "c" i) :name n :input args}))
                                                      gold)}
                     :repl {:content "" :usage usage
                            :tool-calls [{:id "e1" :name "clojure_eval"
                                          :input {:code (str "(do "
                                                             (apply str (map (fn [call]
                                                                               (let [[n args] (first call)]
                                                                                 (str "(bfcl/" n " " (pr-str args) ") ")))
                                                                             gold))
                                                             ")")}}]}}]
      (doseq [action-space [:tools :repl]]
        (testing (str action-space)
          (let [caps (provider/capabilities tasks {})
                env (provider/environment-def task caps {:timeout-ms 60000})
                team (provider/candidate-roster [{:id :dv :harness :dvergr :action-space action-space
                                                  :parallel-tool-calls true
                                                  :model "claude-code-sonnet"}])
                room (d/make-room {:id (keyword "bfcl" (str "dv-" (name action-space)))
                                   :store (memory/make)})
                steps (atom 0)]
            (try
              (with-redefs [model-chat/chat (fn [messages opts]
                                              (swap! steps inc)
                                              (swap! requests conj {:action-space action-space :opts opts
                                                                    :messages messages})
                                              (get responses action-space))]
                (binding [ec/*execution-context* (:ctx room)]
                  (let [result @(evaluation/evaluate room team :dv env (:evaluator caps)
                                                     {:world-setup (:world-setup caps)
                                                      :protocol (:protocol caps)})
                        receipt (:attempt-receipt result)
                        attempt (first (attempts/attempts room {:limit 10}))]
                    (is (= :completed (:attempt/status receipt)) (pr-str (:run/result result)))
                    (is (= 1.0 (:attempt/reward receipt)))
                    (is (= 1 @steps) "the step is never followed by a second one")
                    (is (= (set gold) (set (get-in attempt [:attempt/evidence :calls])))
                        "every call is recorded, also those made inside clojure_eval")
                    (is (empty? (run/active-runs (:id room)))))))
              (finally (d/close-room! room))))))
      (testing "the REPL candidate is given one tool, and the functions in its prompt"
        (let [{repl-request :opts :keys [messages]} (first (filter #(= :repl (:action-space %)) @requests))]
          (is (= ["clojure_eval"] (mapv :name (:tools repl-request))))
          (is (str/includes? (pr-str messages) (:neutral harness/repl-guidance))
              "the default guidance does not tell the model to always call")))
      (testing "a candidate's :parallel-tool-calls reaches the model call"
        (is (every? #(true? (get-in % [:opts :parallel-tool-calls])) @requests)))
      (testing "an unknown guidance is refused, not defaulted"
        (is (thrown? clojure.lang.ExceptionInfo
                     (provider/candidate-roster [{:id :x :harness :dvergr :action-space :repl
                                                  :repl-guidance :nope :model "claude-code-sonnet"}])))))))
