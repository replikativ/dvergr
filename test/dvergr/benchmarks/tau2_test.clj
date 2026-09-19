(ns dvergr.benchmarks.tau2-test
  "Provider-free contracts for the tau2 transcription.

   Python-compatibility tests always run. Data-dependent tests need a pinned
   tau2-bench checkout at `../tau2-bench` (see `dvergr.benchmarks.tau2.core/
   upstream`) and are skipped with a note when it is absent. Their expected
   values are digests of upstream Python replays produced by
   `dev/benchmarks/tau2/oracle.py`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dvergr.benchmarks.tau2.core :as t2]
            [dvergr.benchmarks.tau2.equivalence :as eqv]
            [dvergr.benchmarks.tau2.pyjson :as pj]
            [dvergr.benchmarks.tau2.retail :as retail]
            [dvergr.benchmarks.tau2.runner :as runner]))

(deftest python-float-repr
  (doseq [[x expected] [[0.1 "0.1"] [242.1500000000001 "242.1500000000001"]
                        [100.0 "100.0"] [1e16 "1e+16"] [1.5e-5 "1.5e-05"]
                        [0.0001 "0.0001"] [123456789012345.6 "123456789012345.6"]
                        [-0.0 "-0.0"] [5e-324 "5e-324"] [1.7976931348623157e308
                                                          "1.7976931348623157e+308"]]]
    (is (= expected (pj/py-float-repr x)) (str x))))

(deftest python-round-and-json
  (is (= 2.67 (pj/py-round 2.675 2)) "exact binary value rounds down")
  (is (= 0.12 (pj/py-round 0.125 2)) "ties go to even")
  (is (= 0 (pj/py-round 0 2)) "integers pass through")
  (is (= "{\"b\": 1, \"a\": [1.5, null, true, \"\\u00e9\\n\"]}"
         (pj/dumps (array-map "b" 1 "a" [1.5 nil true "é\n"]))))
  (is (= "{\"a\": 2, \"b\": 1}" (pj/dumps {"b" 1 "a" 2} true))))

(deftest calculate-matches-python-eval
  (doseq [[expr expected] [["2 + 2" "4.0"] ["10 / 4" "2.5"] ["1 / 3" "0.33"]
                           ["0.1 + 0.2" "0.3"] ["2 ** 10" "1024.0"] ["7 // 2" "3.0"]
                           ["-3 + 1" "-2.0"] ["3 * (4 + 5)" "27.0"]
                           ["999999999999 * 999999999999" "9.99999999998e+23"]]]
    (is (= expected (retail/calculate-expression expr)) expr))
  (is (= {:content "Error: Invalid characters in expression" :error true}
         (select-keys (retail/respond {} "calculate" {"expression" "abc"})
                      [:content :error]))))

(deftest argument-errors-match-python-type-errors
  (is (= "Error: RetailTools.get_order_details() missing 1 required positional argument: 'order_id'"
         (:content (retail/respond {} "get_order_details" {}))))
  (is (= "Error: RetailTools.modify_user_address() missing 6 required positional arguments: 'address1', 'address2', 'city', 'state', 'country', and 'zip'"
         (:content (retail/respond {} "modify_user_address" {"user_id" "x"}))))
  (is (= "Error: Tool 'nope' not found." (:content (retail/respond {} "nope" {})))))

(deftest pass-hat-k-metric
  (let [eps (fn [task rewards]
              (map-indexed (fn [i r] {:task-id task :trial i :grade {:reward r}}) rewards))
        episodes (concat (eps "a" [1.0 1.0 0.0]) (eps "b" [1.0 1.0 1.0]))]
    (is (= (/ (+ 2/3 1) 2.0) (runner/pass-hat-k episodes 1)))
    (is (= (/ (+ 1/3 1) 2.0) (runner/pass-hat-k episodes 2)))
    (is (= 0.5 (runner/pass-hat-k episodes 3)))))

;; ---------------------------------------------------------------------------
;; Upstream data

(def ^:private checkout? (.exists (io/file t2/default-root "data/tau2/domains/retail/db.json")))

(def ^:private domain (delay (t2/load-domain "retail")))

(defn- oracle-fixture []
  (edn/read-string (slurp (io/resource "dvergr/benchmarks/tau2/retail_oracle_digests.edn"))))

(defn- gold-corpus [dom]
  (mapv (fn [task]
          {"id" (get task "id")
           "calls" (mapv #(select-keys % ["name" "arguments"])
                         (get-in task ["evaluation_criteria" "actions"]))})
        (vals (:tasks dom))))

(deftest retail-equivalence-with-upstream
  (if-not checkout?
    (println "SKIP retail-equivalence-with-upstream: no ../tau2-bench checkout")
    (let [dom @domain
          fixture (oracle-fixture)]
      (is (= (:revision t2/upstream) (get-in fixture [:upstream :revision])))
      (is (= "b25c9cb211f5efcaee5dd646054a73c4a9f43f4f5acc32c10713cd9f9ac20e9c"
             (:initial-db-hash dom)))
      (testing "gold action replays are byte-identical to Python"
        (is (= (:gold fixture)
               (eqv/replay-digests retail/respond retail/db-hash (:db dom)
                                   (gold-corpus dom)))))
      (testing "seeded fuzz replays (error paths, partial effects) are identical"
        (let [{:keys [seed n digests]} (:fuzz fixture)]
          (is (= digests
                 (eqv/replay-digests retail/respond retail/db-hash (:db dom)
                                     (eqv/retail-fuzz-corpus (:db dom) seed n)))))))))

(defn- gold-agent [task]
  (let [actions (get-in task ["evaluation_criteria" "actions"])]
    (fn [{:keys [messages]}]
      (let [done (count (filter :tool-calls messages))]
        (if (< done (count actions))
          (let [{:strs [name arguments action_id]} (nth actions done)]
            {:tool-calls [{:id action_id :name name :arguments arguments}]})
          {:content "Done. ###STOP###"})))))

(deftest gold-agents-certify-every-task
  (if-not checkout?
    (println "SKIP gold-agents-certify-every-task: no ../tau2-bench checkout")
    (let [dom @domain]
      (doseq [task (vals (:tasks dom))
              :let [task (update-in task ["evaluation_criteria" "reward_basis"]
                                    #(vec (remove #{"NL_ASSERTION"} %)))
                    episode (t2/run-episode dom task {:agent (gold-agent task)
                                                      :user (constantly {:content "Hi."})})]]
        (is (= :agent-stop (:termination episode)) (get task "id"))
        (is (= 1.0 (:reward (t2/grade dom task episode {}))) (get task "id"))))))

(deftest episode-protocol-terminations
  (if-not checkout?
    (println "SKIP episode-protocol-terminations: no ../tau2-bench checkout")
    (let [dom @domain
          task (get-in dom [:tasks "0"])
          run #(t2/run-episode dom task (merge {:user (constantly {:content "Hi."})} %))]
      (is (= :agent-error (:termination (run {:agent (constantly {:content "  "})}))))
      (is (= :user-stop (:termination
                         (run {:agent (constantly {:content "Hello"})
                               :user (constantly {:content "bye ###STOP###"})}))))
      (is (= :max-steps (:termination (run {:agent (constantly {:content "Hello"})
                                            :max-steps 6}))))
      (let [episode (run {:agent (constantly {:tool-calls [{:id "x" :name "get_order_details"
                                                            :arguments {:order_id "nope"}}]})})]
        (is (= :too-many-errors (:termination episode)))
        (is (= 0.0 (:reward (t2/grade dom task episode {})))))
      (testing "mixed text+tool messages route to the environment unless enforced"
        (let [mixed (constantly {:content "Checking." :tool-calls [{:id "x" :name "calculate"
                                                                     :arguments {"expression" "1+1"}}]})]
          (is (= :max-steps (:termination (run {:agent mixed :max-steps 10})))
              "accepted: the episode only ends at the step bound")
          (is (= :agent-error (:termination (run {:agent mixed :enforce-protocol? true})))))))))

(deftest nl-judge-prompt-matches-upstream
  ;; Captured from upstream `NLAssertionsEvaluator.evaluate_nl_assertions` via
  ;; `oracle.py judge-prompts` (Python list repr quoting, `None` content).
  (is (= "\n        conversation:\n        assistant: It's \"fine\"\nnew line\nuser: None\n        \n        expectedOutcomes:\n        [\"Agent's reply\", 'say \"hi\"', 'both \\' and \"', 'tab\\there', 'back\\\\slash']\n        "
         (t2/nl-judge-prompt [{:role :assistant :content "It's \"fine\"\nnew line"}
                              {:role :user :content nil}]
                             ["Agent's reply" "say \"hi\"" "both ' and \"" "tab\there"
                              "back\\slash"]))))

(deftest episode-usage-is-logged-per-model-call
  (if-not checkout?
    (println "SKIP episode-usage-is-logged-per-model-call: no ../tau2-bench checkout")
    (let [dom @domain
          n (atom 0)
          ep (t2/run-episode dom (get-in dom [:tasks "0"])
                             {:agent (fn [_] (if (< (swap! n inc) 3)
                                               {:content "hello" :usage {:input-tokens 10}}
                                               {:content "bye ###STOP###" :usage {:input-tokens 10}}))
                              :user (constantly {:content "hi" :usage {:input-tokens 5}})})]
      (is (= {:agent 3 :user 3} (frequencies (map :role (:usage ep)))))
      (is (= 45 (reduce + (map #(get-in % [:usage :input-tokens]) (:usage ep))))))))
