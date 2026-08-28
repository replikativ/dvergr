(ns dvergr.agent.prompt-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.agent.prompt :as prompt]))

(deftest participant-profile-preserves-the-established-prompt
  (let [base "You are the room analyst."
        opts {:tools #{:shell}
              :env-lookup (constantly nil)}]
    (is (= (str prompt/discourse-preamble "\n\n---\n\n" base)
           (prompt/assemble-system-prompt
            base {:tools []
                  :room-dir "/tmp/dvergr-prompt-test-no-workspace"
                  :env-lookup (constantly nil)}))
        "the tool-free default keeps its exact historical assembly")
    (is (= (prompt/assemble-system-prompt base opts)
           (prompt/assemble-system-prompt base (assoc opts :profile :participant)))
        "making the default profile explicit does not change prompt bytes")
    (let [assembled (prompt/assemble-system-prompt base opts)]
      (is (str/includes? assembled prompt/discourse-preamble))
      (is (str/includes? assembled "[SKIP]"))
      (is (str/includes? assembled "You are never capped")))))

(deftest workflow-profile-is-private-and-bounded
  (let [assembled (prompt/assemble-system-prompt
                   "Solve the delegated task."
                   {:profile :workflow
                    :tools #{:shell}
                    :env-lookup (constantly nil)})]
    (testing "private model-step execution is not taught room-participant discourse"
      (is (not (str/includes? assembled prompt/discourse-preamble)))
      (is (not (str/includes? assembled "[SKIP]")))
      (is (str/starts-with? assembled "Solve the delegated task.")))
    (testing "workflow authority is explicitly bounded"
      (is (str/includes? assembled "## Bounded authority"))
      (is (str/includes? assembled "parent Run"))
      (is (str/includes? assembled "model-step limit"))
      (is (not (str/includes? assembled "You are never capped"))))))
