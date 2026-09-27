(ns dvergr.benchmarks.automationbench-test
  "AutomationBench on the generic evaluation path, with scripted candidates
   (no model), over upstream's code in the sidecar. Skipped without the
   checkout (see `dvergr.benchmarks.automationbench.sidecar`)."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.run :as run]
            [dvergr.benchmarks.automationbench.provider :as provider]
            [dvergr.benchmarks.automationbench.sidecar :as sc]
            [dvergr.discourse :as d]
            [dvergr.room.store.memory :as memory]
            [dvergr.test-support :as support]
            [org.replikativ.spindel.engine.core :as ec]))

(def ^:private opportunity
  "https://yourinstance.salesforce.com/services/data/v61.0/sobjects/Opportunity/006001")

(defn- scripted
  "A candidate emitting `steps` (each a vector of `[tool-name arguments]`, or
   a string for the final reply), one per model step."
  [steps seen]
  (fn [_task]
    (let [left (atom steps)]
      (fn [request]
        (swap! seen conj request)
        (let [step (first @left)]
          (swap! left rest)
          (if (or (nil? step) (string? step))
            {:content (or step "Done.") :tool-calls []}
            {:content ""
             :tool-calls (map-indexed (fn [i [tool-name args]]
                                        {:id (str "call_" i) :name tool-name :arguments args})
                                      step)}))))))

(defn- evaluate! [room steps seen]
  (let [caps (provider/capabilities {:agent-generate (scripted steps seen)})
        task (some #(when (= ["simple" "3011"] [(get % "domain") (get % "id")]) %)
                   (sc/tasks (sc/shared!) ["simple"]))
        env (provider/environment-def task caps {:timeout-ms 60000})
        team (provider/candidate-roster [{:id :scripted :model "claude-code-sonnet"}])]
    (binding [ec/*execution-context* (:ctx room)]
      @(evaluation/evaluate room team :scripted env (:evaluator caps)
                            {:world-setup (:world-setup caps) :protocol (:protocol caps)}))))

(deftest the-stateless-service-makes-upstream-s-world
  (if-not (sc/available?)
    (support/skip! "the-stateless-service-makes-upstream-s-world: no AutomationBench checkout")
    (let [s (sc/shared!)
          start (sc/start s "sales" "501" {:at 1790000000000})]
      (testing "an initial world is made at an instant, so it can be made again"
        (is (= (get start "digest") (get (sc/start s "sales" "501" {:at 1790000000000}) "digest"))))
      (testing "calls through JSON, one at a time, end where one process on one world ends"
        (let [calls [["api_search" {"query" "salesforce opportunity query"}]
                     ["api_fetch" {"method" "GET"
                                   "url" "https://gmail.googleapis.com/gmail/v1/users/me/messages"}]
                     ["api_fetch" {"method" "POST"
                                   "url" "https://gmail.googleapis.com/gmail/v1/users/me/messages/send"
                                   "body" "{\"raw\": \"VG86IGEAZXhhbXBsZS5jb20NClN1YmplY3Q6IGhpDQoNCmhp\"}"}]]
              [world log] (reduce (fn [[world log] [tool args]]
                                    (let [r (sc/call s "sales" "501" {:world world :n (count log)
                                                                      :name tool :arguments args})]
                                      [(get r "world")
                                       (conj log {:n (count log) :name tool :arguments args
                                                  :at (get r "at") :digest (get r "digest")})]))
                                  [(get start "world") []] calls)
              replayed (sc/replay s "sales" "501" {:start-at 1790000000000 :calls log})]
          (is (= (:digest (peek log)) (get replayed "digest")))
          (is (= (get (sc/grade s "sales" "501" world) "partial_credit")
                 (get (sc/grade s "sales" "501" (get replayed "world")) "partial_credit"))))))))

(deftest a-task-through-evaluate
  (if-not (sc/available?)
    (support/skip! "a-task-through-evaluate: no AutomationBench checkout")
    (let [room (d/make-room {:id :automationbench/provider-test :store (memory/make)})
          seen (atom [])]
      (try
        (testing "a candidate that does the task passes, and its world replays"
          (let [result (evaluate! room [[["api_search" {"query" "salesforce opportunity update"}]]
                                        [["api_fetch" {"method" "PATCH" "url" opportunity
                                                       "body" "{\"StageName\": \"Closed Won\"}"}]]
                                        "Marked the NexGen Platform Deal as Closed Won."]
                                  seen)
                receipt (:attempt-receipt result)]
            (is (= 1.0 (:attempt/reward receipt)))
            (is (= {:completed true :stopped-by-itself true :world-replays true :passed true}
                   (:attempt/checks receipt)))
            (testing "the candidate got the task's prompt and upstream's api tools, no assertions"
              (let [request (first @seen)]
                (is (string? (:system request)))
                (is (= [:user] (mapv :role (:messages request))))
                (is (= #{"api_search" "api_fetch" "base64_encode"}
                       (set (map #(get-in % ["function" "name"]) (:tools request)))))
                (is (not (re-find #"salesforce_field_equals" (pr-str request))))))
            (testing "tool results are upstream's text"
              (is (re-find #"salesforce.sobjects.opportunity.update"
                           (:content (last (:messages (second @seen)))))))
            (is (= :discarded (:run/settlement-status (run/run room (:run/id result)))))))
        (testing "a candidate that does nothing scores zero and says why"
          (let [receipt (:attempt-receipt (evaluate! room ["I cannot do that."] (atom [])))]
            (is (= 0.0 (:attempt/reward receipt)))
            (is (false? (get-in receipt [:attempt/checks :passed])))
            (is (false? (get-in receipt [:attempt/checks :automationbench.failed/salesforce_field_equals])))))
        (testing "the wrong stage: the assertion fails, the world still replays"
          (let [receipt (:attempt-receipt
                         (evaluate! room [[["api_fetch" {"method" "PATCH" "url" opportunity
                                                         "body" "{\"StageName\": \"Closed Lost\"}"}]]]
                                    (atom [])))]
            (is (= 0.0 (:attempt/reward receipt)))
            (is (true? (get-in receipt [:attempt/checks :world-replays])))))
        (finally (d/close-room! room))))))
