(ns dvergr.benchmarks.tau2-harness-test
  "Dvergr's agent loop as a tau2 candidate, with a scripted model: tool
   traffic reaches the benchmark world and the graded trajectory in both
   action spaces."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dvergr.benchmarks.tau2.core :as t2]
            [dvergr.benchmarks.tau2.harness :as harness]
            [dvergr.model.chat :as model-chat]))

(def ^:private checkout?
  (.exists (io/file t2/default-root "data/tau2/domains/retail/db.json")))

(defn- scripted [responses]
  (let [n (atom -1)]
    (fn [_messages _opts]
      (nth responses (min (swap! n inc) (dec (count responses)))))))

(def ^:private usage {:input-tokens 1 :output-tokens 1})

(def ^:private new-address
  {"user_id" "yusuf_rossi_9620" "address1" "1 Test Way" "address2" "" "city" "Austin"
   "state" "TX" "country" "USA" "zip" "78701"})

(defn- episode [dom task action-space responses]
  (with-redefs [model-chat/chat (scripted responses)]
    (let [{:keys [turn close]} (harness/make-agent-turn
                                dom {:model "claude-code-sonnet" :action-space action-space})]
      (try
        (t2/run-episode dom task {:agent-turn turn
                                  :user (constantly {:content "Please update my address."})})
        (finally (close))))))

(deftest harness-turns-act-on-the-benchmark-world
  (if-not checkout?
    (println "SKIP harness-turns-act-on-the-benchmark-world: no ../tau2-bench checkout")
    (let [dom (t2/load-domain "retail")
          task (-> (get-in dom [:tasks "0"])
                   (assoc-in ["evaluation_criteria" "actions"]
                             [{"name" "modify_user_address" "arguments" new-address}])
                   (assoc-in ["evaluation_criteria" "reward_basis"] ["DB"]))]
      (testing "JSON tools"
        (let [ep (episode dom task :tools
                          [{:content "" :usage usage
                            :tool-calls [{:id "t1" :name "modify_user_address" :input new-address}]}
                           {:content "Updated. ###STOP###" :usage usage}])]
          (is (= :agent-stop (:termination ep)))
          (is (= 1.0 (:reward (t2/grade dom task ep {}))))
          (is (some #(= "modify_user_address" (get-in % [:tool-calls 0 :name])) (:messages ep)))))
      (testing "REPL action space composes tool calls in Clojure"
        (let [code (str "(let [uid (tau2/find_user_id_by_email {\"email\" \"yusuf.rossi7301@example.com\"})] "
                        "(tau2/modify_user_address (assoc " (pr-str (dissoc new-address "user_id"))
                        " \"user_id\" uid)))")
              ep (episode dom task :repl
                          [{:content "" :usage usage
                            :tool-calls [{:id "t1" :name "clojure_eval" :input {:code code}}]}
                           {:content "Updated. ###STOP###" :usage usage}])]
          (is (= :agent-stop (:termination ep)))
          (is (= 1.0 (:reward (t2/grade dom task ep {}))))
          (is (= ["find_user_id_by_email" "modify_user_address"]
                 (keep #(get-in % [:tool-calls 0 :name]) (:messages ep)))
              "calls made inside clojure_eval are recorded as tau2 tool calls"))))))
