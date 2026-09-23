(ns dvergr.agent.experiment-visibility-test
  "What an experiment says while and after it runs: a model's own failure is
   scored (a verdict), a failure of the path to the model is a fault to
   re-run, one cell's error stays that cell's, and progress is derived from
   the experiment Room's store (its certified Attempts), not a side file."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.experiment.runner :as runner]
            [dvergr.catalog.wiki :as wiki]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.model.chat :as model-chat]
            [dvergr.model.providers :as providers]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as sdb]
            [org.replikativ.spindel.core :as sp]))

(defn- run-with [model-fn & {:keys [repetitions dir timeout-ms] :or {repetitions 2 timeout-ms 60000}}]
  (let [prev (paths/home)
        dir (or dir (str (System/getProperty "java.io.tmpdir") "/dvergr-visibility-" (random-uuid)))]
    (try
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/messages->api-format (fn [messages _ _] messages)
                    model-chat/chat model-fn]
        (assoc (wiki/experiment! {:dir dir :version 2 :models ["claude-haiku-4-5"]
                                  :repetitions repetitions :timeout-ms timeout-ms})
               :dir dir))
      (finally
        (paths/set-home! prev)
        (sdb/reset-conn!)))))

(defn- cand
  "The one candidate's progress of the one experiment in `dir`."
  [dir]
  (get-in (runner/progress dir) [:experiments 0 :candidates 0]))

(defn- silent-model [_ _]
  {:content "" :tool-calls nil :usage {:input-tokens 500 :output-tokens 0} :stop-reason :end-turn})

(defn- broken-provider [_ _]
  (throw (ex-info "Max retries exceeded" {:status 503})))

(deftest a-model-that-answers-nothing-is-scored-not-retried
  (let [{:keys [scorecard dir]} (run-with silent-model)
        [summary] (:scorecard/summary scorecard)
        p (cand dir)]
    (is (nil? (:incomplete scorecard)) "a verdict, so the Scorecard is complete")
    (is (= 2 (:attempt-count summary)))
    (is (= 0.0 (:reward-mean summary)))
    (is (pos? (get-in summary [:spend :microdollars])) "its bill is counted")
    (testing "the store says why"
      (is (= 2 (:done p) (:verdicts p)))
      (is (zero? (:faults p)))
      (is (= {"empty response after a corrective retry" 2} (:failures p)))
      (is (pos? (:microdollars p))))))

(deftest a-provider-failure-is-a-fault-and-resume-re-runs-it
  (let [dir (str (System/getProperty "java.io.tmpdir") "/dvergr-visibility-" (random-uuid))
        first-run (run-with broken-provider :dir dir :repetitions 1)]
    (is (= 1 (get-in first-run [:scorecard :incomplete :cells])))
    (is (= 1 (get-in first-run [:scorecard :incomplete :faults])))
    (let [p (cand dir)]
      (is (= 1 (:faults p)))
      (is (zero? (:verdicts p)))
      (is (re-find #"Max retries" (ffirst (:failures p)))))
    (testing "resumed with a working model, the faulted cell runs again and completes"
      (let [second-run (run-with silent-model :dir dir :repetitions 1)
            p (cand dir)]
        (is (nil? (get-in second-run [:scorecard :incomplete])))
        (is (= 2 (:done p)) "the fault and the verdict are both in the store")
        (is (= 1 (:verdicts p) (:faults p)))))))

(deftest one-cell-error-does-not-end-the-experiment
  (let [real @#'evaluation/evaluate
        calls (atom 0)]
    (with-redefs [evaluation/evaluate
                  (fn [& args]
                    ;; The preflight evaluates each pairing once; fail the
                    ;; second cell's evaluation at run time.
                    (if (= 3 (swap! calls inc))
                      (sp/spin (throw (ex-info "Evaluation certification failed" {})))
                      (apply real args)))]
      (let [{:keys [scorecard dir]} (run-with silent-model :repetitions 2)]
        (is (= 1 (count (get-in scorecard [:incomplete :errors]))))
        (is (re-find #"certification failed" (get-in scorecard [:incomplete :errors 0 :error])))
        (is (= 1 (:verdicts (cand dir))) "the other cell ran to its verdict")))))

(defn- expensive-model
  "Keeps working, and each step costs more than an attempt's budget."
  [_ _]
  {:content ""
   :tool-calls [{:id (str "g" (random-uuid)) :name "read_file" :input {:path "/docs/charter-1953.md"}}]
   :usage {:input-tokens 2000000 :output-tokens 1000}
   :stop-reason :tool-use})

(deftest running-out-of-budget-is-scored-not-left-waiting
  ;; An interactive Run that exhausts its budget waits for more; an evaluation
  ;; has no one to extend it, and a waiting Run cannot be certified (Kimi K3
  ;; on wiki/v2 aborted the experiment this way).
  (let [{:keys [scorecard dir]} (run-with expensive-model :repetitions 1)
        p (cand dir)]
    (is (nil? (:incomplete scorecard)) (pr-str (:incomplete scorecard)))
    (is (= 0.0 (get-in scorecard [:scorecard/summary 0 :reward-mean])))
    (is (= {"budget exhausted" 1} (:failures p)))
    (is (= 1 (:verdicts p)))))

(defn- truncated-model
  "Spends its whole output limit thinking: no content, no tool call."
  [_ _]
  {:content "" :tool-calls nil :usage {:input-tokens 500 :output-tokens 8192} :stop-reason :length})

(deftest a-reply-cut-off-at-the-output-limit-is-named-as-such
  ;; GLM 5.3 on wiki/v2: every "empty response" was a reply cut off at the
  ;; 8192-token default, which the harness mistook for an empty answer.
  (let [{:keys [dir]} (run-with truncated-model :repetitions 1)]
    (is (= {"output cut off at the output-token limit" 1} (:failures (cand dir))))))

(defn- stalling-model [_ _]
  (Thread/sleep 8000)
  {:content "late" :tool-calls nil :usage {:input-tokens 10 :output-tokens 1} :stop-reason :end-turn})

(deftest a-timeout-counts-against-the-candidate-where-the-environment-says-so
  ;; wiki/v2 declares {:on-timeout :verdict}: not finishing in time is the
  ;; candidate's failure, scored 0, not a fault that resume would re-run.
  (let [{:keys [scorecard dir]} (run-with stalling-model :repetitions 1 :timeout-ms 2000)
        p (cand dir)]
    (is (nil? (:incomplete scorecard)) (pr-str (:incomplete scorecard)))
    (is (= 0.0 (get-in scorecard [:scorecard/summary 0 :reward-mean])))
    (is (= {"timed out after 2000 ms" 1} (:failures p)))
    (is (= 1 (:verdicts p)))))
