(ns dvergr.agent.experiment-visibility-test
  "What an experiment says while and after it runs: a model's own failure is
   scored (a verdict), a failure of the path to the model is a fault to
   re-run, one cell's error stays that cell's, and every finished cell is in
   the progress file."
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

(defn- run-with [model-fn & {:keys [repetitions dir] :or {repetitions 2}}]
  (let [prev (paths/home)
        dir (or dir (str (System/getProperty "java.io.tmpdir") "/dvergr-visibility-" (random-uuid)))]
    (try
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/messages->api-format (fn [messages _ _] messages)
                    model-chat/chat model-fn]
        (assoc (wiki/experiment! {:dir dir :version 2 :models ["claude-haiku-4-5"]
                                  :repetitions repetitions :timeout-ms 60000})
               :dir dir))
      (finally
        (paths/set-home! prev)
        (sdb/reset-conn!)))))

(defn- silent-model [_ _]
  {:content "" :tool-calls nil :usage {:input-tokens 500 :output-tokens 0} :stop-reason :end-turn})

(defn- broken-provider [_ _]
  (throw (ex-info "Max retries exceeded" {:status 503})))

(deftest a-model-that-answers-nothing-is-scored-not-retried
  (let [{:keys [scorecard dir]} (run-with silent-model)
        [summary] (:scorecard/summary scorecard)
        progress (runner/progress dir)]
    (is (nil? (:incomplete scorecard)) "a verdict, so the Scorecard is complete")
    (is (= 2 (:attempt-count summary)))
    (is (= 0.0 (:reward-mean summary)))
    (is (pos? (get-in summary [:spend :microdollars])) "its bill is counted")
    (testing "the progress file says why"
      (is (= 2 (count (:cells progress))))
      (is (every? #(= :model (get-in % [:failure :kind])) (:cells progress)))
      (is (every? :verdict? (:cells progress)))
      (is (= 2 (get-in progress [:by-candidate 0 :verdicts]))))))

(deftest a-provider-failure-is-a-fault-and-resume-re-runs-it
  (let [dir (str (System/getProperty "java.io.tmpdir") "/dvergr-visibility-" (random-uuid))
        first-run (run-with broken-provider :dir dir :repetitions 1)]
    (is (= 1 (get-in first-run [:scorecard :incomplete :cells])))
    (is (= 1 (get-in first-run [:scorecard :incomplete :faults])))
    (let [[cell] (:cells (runner/progress dir))]
      (is (= :infrastructure (get-in cell [:failure :kind])))
      (is (re-find #"Max retries" (get-in cell [:failure :cause])))
      (is (false? (:verdict? cell))))
    (testing "resumed with a working model, the faulted cell runs again and completes"
      (let [second-run (run-with silent-model :dir dir :repetitions 1)]
        (is (nil? (get-in second-run [:scorecard :incomplete])))
        (is (= 2 (count (:cells (runner/progress dir)))) "both runs are in the progress file")))))

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
      (let [{:keys [scorecard dir]} (run-with silent-model :repetitions 2)
            cells (:cells (runner/progress dir))]
        (is (= 1 (count (get-in scorecard [:incomplete :errors]))))
        (is (re-find #"certification failed" (get-in scorecard [:incomplete :errors 0 :error])))
        (is (= 2 (count cells)) "the other cell ran to its verdict")
        (is (= #{:error :failed} (set (map :status cells))))))))

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
        [cell] (:cells (runner/progress dir))]
    (is (nil? (:incomplete scorecard)) (pr-str (:incomplete scorecard)))
    (is (= 0.0 (get-in scorecard [:scorecard/summary 0 :reward-mean])))
    (is (= {:kind :model :cause "budget exhausted"} (select-keys (:failure cell) [:kind :cause])))
    (is (:verdict? cell))))
