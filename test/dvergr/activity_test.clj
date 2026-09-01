(ns dvergr.activity-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.activity :as activity]
            [dvergr.agent.environment :as environment]))

(deftest tool-activity-identity-is-canonical-and-stable
  (testing "the same typed source identity hashes to the same activity UUID"
    (let [run-id (random-uuid)
          source-id (random-uuid)
          uses [{:tool-use/id "call-1" :tool-use/name "clojure_eval"}]
          first-id (:activity/id (first (activity/tool-activities
                                         run-id source-id uses)))
          replay-id (:activity/id (first (activity/tool-activities
                                          run-id source-id uses)))
          other-id (:activity/id (first (activity/tool-activities
                                         run-id source-id
                                         [{:tool-use/id "call-2"
                                           :tool-use/name "clojure_eval"}])))]
      (is (uuid? first-id))
      (is (= first-id replay-id))
      (is (not= first-id other-id)))))

(deftest tool-traces-preserve-correlation-across-interleaved-runs
  (let [run-a (random-uuid)
        run-b (random-uuid)
        message-a (random-uuid)
        message-b (random-uuid)
        use-a {:tool-use/id "call-a"
               :tool-use/name "search"
               :tool-use/input {:query "alpha"}}
        use-b {:tool-use/id "call-b"
               :tool-use/name "search"
               :tool-use/input {:query "beta"}}
        messages
        [{:id message-a
          :metadata {:run-id run-a
                     :tool-uses [use-a]
                     :activities (activity/tool-activities
                                  run-a message-a [use-a])}}
         {:id message-b
          :metadata {:run-id run-b
                     :tool-uses [use-b]
                     :activities (activity/tool-activities
                                  run-b message-b [use-b])}}]
        trace (mapv activity/tool-trace-entry messages)]
    (is (= [[message-a run-a "call-a" run-a]
            [message-b run-b "call-b" run-b]]
           (mapv (fn [entry]
                   [(:message/id entry)
                    (:run/id entry)
                    (get-in entry [:tool-uses 0 :tool-use/id])
                    (get-in entry [:activities 0 :activity/run-id])])
                 trace)))
    (is (= [{:query "alpha"} {:query "beta"}]
           (mapv #(get-in % [:tool-uses 0 :tool-use/input]) trace)))
    (is (every? integer? (map #(get-in % [:activities 0 :activity/at]) trace)))
    (let [definition
          (environment/make-environment
           {:id :test/tool-trace
            :task "trace"
            :verifier {:id :test/trace :version 1}})
          receipt
          (environment/make-attempt-receipt
           definition
           {:run-id run-a :provider :test :model "stub" :status :completed
            :started-at 0 :elapsed-ms 1 :metrics {} :checks {:trace? true}
            :reward 1.0 :trace trace})]
      (is (= receipt (environment/validate-attempt-receipt receipt))))))
