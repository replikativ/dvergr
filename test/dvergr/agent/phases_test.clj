(ns dvergr.agent.phases-test
  "Tests for phase-based agent execution engine.

   Unit tests: pure functions (narrow-tools, load-phase, run-self-check)
   Integration tests: run-turns, run-phase, run-workflow with injected turn fn"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.agent.phases :as phases]
            [dvergr.chat.context :as chat-ctx]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.engine.context :as ctx]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(def ^:dynamic *test-runtime* nil)

(defn with-spindel-runtime [f]
  (let [runtime (ctx/create-execution-context)]
    (binding [rtc/*execution-context* runtime
              *test-runtime* runtime]
      (f))))

(use-fixtures :each with-spindel-runtime)

(defn make-test-chat
  "Create a minimal chat context for testing."
  ([] (make-test-chat {}))
  ([{:keys [budget-dollars] :or {budget-dollars 1.00}}]
   (chat-ctx/create-chat-context {:title "test" :budget-dollars budget-dollars})))

;; ============================================================================
;; Phase Loading Tests
;; ============================================================================

(deftest load-phase-test
  (testing "Load existing phase from resources"
    (let [phase (phases/load-phase :explore)]
      (is (some? phase))
      (is (= :explore (:id phase)))
      (is (set? (:tools phase)))
      (is (contains? (:tools phase) :read_file))
      (is (string? (:system-prompt-suffix phase)))
      (is (some? (:completion-criteria phase)))))

  (testing "Load all built-in phases"
    (doseq [id [:explore :plan :implement :verify :research]]
      (let [phase (phases/load-phase id)]
        (is (some? phase) (str "Phase " id " should load"))
        (is (= id (:id phase))))))

  (testing "Returns nil for non-existent phase"
    (is (nil? (phases/load-phase :nonexistent)))))

(deftest load-phases-test
  (testing "Load multiple phases"
    (let [loaded (phases/load-phases [:explore :implement :verify])]
      (is (= 3 (count loaded)))
      (is (contains? loaded :explore))
      (is (contains? loaded :implement))
      (is (contains? loaded :verify))))

  (testing "Skips missing phases"
    (let [loaded (phases/load-phases [:explore :nonexistent :verify])]
      (is (= 2 (count loaded))))))

;; ============================================================================
;; Tool Narrowing Tests
;; ============================================================================

(deftest narrow-tools-test
  (let [all-tools {"read_file" {:name "read_file"}
                    "write_file" {:name "write_file"}
                    "edit_file" {:name "edit_file"}
                    "glob" {:name "glob"}
                    "grep" {:name "grep"}
                    "shell" {:name "shell"}}]

    (testing "Narrow to read-only tools"
      (let [narrowed (phases/narrow-tools all-tools #{:read_file :glob :grep})]
        (is (= 3 (count narrowed)))
        (is (contains? narrowed "read_file"))
        (is (contains? narrowed "glob"))
        (is (contains? narrowed "grep"))
        (is (not (contains? narrowed "write_file")))
        (is (not (contains? narrowed "shell")))))

    (testing "Nil tool-set returns all tools"
      (is (= all-tools (phases/narrow-tools all-tools nil))))

    (testing "Empty tool-set returns all tools"
      (is (= all-tools (phases/narrow-tools all-tools #{}))))

    (testing "Non-matching tools returns empty"
      (let [narrowed (phases/narrow-tools all-tools #{:nonexistent})]
        (is (= 0 (count narrowed)))))))

;; ============================================================================
;; Self-Check Tests
;; ============================================================================

(deftest run-self-check-test
  (testing "Passing check tool"
    (let [tools {"clj_kondo" {:name "clj_kondo"
                              :execute (fn [_ _] {:type :success
                                                   :metadata {:errors 0 :failed 0}})}}
          result (phases/run-self-check "clj_kondo" {} tools)]
      (is (:passed result))))

  (testing "Failing check tool"
    (let [tools {"clj_kondo" {:name "clj_kondo"
                              :execute (fn [_ _] {:type :error
                                                   :content "1 error"
                                                   :metadata {:errors 1}})}}
          result (phases/run-self-check "clj_kondo" {} tools)]
      (is (not (:passed result)))))

  (testing "Missing check tool passes by default"
    (let [result (phases/run-self-check "nonexistent" {} {})]
      (is (:passed result)))))

;; ============================================================================
;; Workflow Construction Tests
;; ============================================================================

(deftest make-workflow-test
  (testing "Create workflow from phase keywords"
    (let [wf (phases/make-workflow [:explore :implement :verify])]
      (is (= [:explore :implement :verify] (:phases wf)))
      (is (= 3 (count (:phase-defs wf))))
      (is (contains? (:phase-defs wf) :explore))
      (is (contains? (:phase-defs wf) :implement))
      (is (contains? (:phase-defs wf) :verify))))

  (testing "Workflow with unknown phases has fewer defs"
    (let [wf (phases/make-workflow [:explore :nonexistent :verify])]
      (is (= [:explore :nonexistent :verify] (:phases wf)))
      (is (= 2 (count (:phase-defs wf)))))))

;; ============================================================================
;; Phase Definition Integrity Tests
;; ============================================================================

(deftest phase-definitions-integrity-test
  (testing "Explore phase is read-only (no write tools)"
    (let [phase (phases/load-phase :explore)]
      (is (not (contains? (:tools phase) :write_file)))
      (is (not (contains? (:tools phase) :edit_file)))
      (is (not (contains? (:tools phase) :shell)))))

  (testing "Implement phase has write tools"
    (let [phase (phases/load-phase :implement)]
      (is (contains? (:tools phase) :write_file))
      (is (contains? (:tools phase) :edit_file))
      (is (contains? (:tools phase) :clojure_edit))))

  (testing "Verify phase has test/lint tools"
    (let [phase (phases/load-phase :verify)]
      (is (contains? (:tools phase) :run_tests))
      (is (contains? (:tools phase) :clj_kondo))))

  (testing "Plan phase has no write tools"
    (let [phase (phases/load-phase :plan)]
      (is (not (contains? (:tools phase) :write_file)))
      (is (not (contains? (:tools phase) :edit_file)))))

  (testing "All phases have required fields"
    (doseq [id [:explore :plan :implement :verify :research]]
      (let [phase (phases/load-phase id)]
        (is (keyword? (:id phase)) (str id " missing :id"))
        (is (set? (:tools phase)) (str id " missing :tools"))
        (is (string? (:system-prompt-suffix phase)) (str id " missing :system-prompt-suffix"))
        (is (some? (:completion-criteria phase)) (str id " missing :completion-criteria"))))))

;; ============================================================================
;; Integration: run-turns with injected turn fn
;; ============================================================================

(deftest run-turns-completes-test
  (testing "run-turns completes when LLM returns :complete"
    (let [chat (make-test-chat)
          turn-count (atom 0)]
      (chat-ctx/add-message! chat {:role :system :content "test"})
      (chat-ctx/add-message! chat {:role :user :content "do something"})
      (let [result (phases/run-turns chat {:provider :test :model "test"
                                           :tools {} :tool-ctx {}
                                           :run-turn-fn (fn [_ctx _opts]
                                                          (swap! turn-count inc)
                                                          (if (= 1 @turn-count) :continue :complete))})]
        (is (= :complete (:status result)))
        (is (= 2 (:turns result))))))

  (testing "run-turns respects abort? predicate"
    (let [chat (make-test-chat)
          aborted (atom false)]
      (chat-ctx/add-message! chat {:role :system :content "test"})
      (chat-ctx/add-message! chat {:role :user :content "do something"})
      (let [result (phases/run-turns chat {:provider :test :model "test"
                                           :tools {} :tool-ctx {}
                                           :run-turn-fn (fn [_ _]
                                                          (reset! aborted true)
                                                          :continue)
                                           :abort? (fn [] @aborted)})]
        (is (= :cancelled (:status result)))
        (is (= 1 (:turns result))))))

  (testing "run-turns respects budget"
    (let [chat (make-test-chat {:budget-dollars 0.00})] ;; zero budget
      (chat-ctx/add-message! chat {:role :system :content "test"})
      (chat-ctx/add-message! chat {:role :user :content "do something"})
      (let [result (phases/run-turns chat {:provider :test :model "test"
                                           :tools {} :tool-ctx {}
                                           :run-turn-fn (fn [_ _] :continue)})]
        (is (= :budget-exceeded (:status result)))
        (is (= 0 (:turns result))))))

  (testing "run-turns handles errors"
    (let [chat (make-test-chat)]
      (chat-ctx/add-message! chat {:role :system :content "test"})
      (chat-ctx/add-message! chat {:role :user :content "do something"})
      (let [result (phases/run-turns chat {:provider :test :model "test"
                                           :tools {} :tool-ctx {}
                                           :run-turn-fn (fn [_ _] :error)})]
        (is (= :error (:status result)))
        (is (= 1 (:turns result)))))))

(deftest run-turns-on-complete-test
  (testing "on-complete can request continuation"
    (let [chat (make-test-chat)
          complete-count (atom 0)]
      (chat-ctx/add-message! chat {:role :system :content "test"})
      (chat-ctx/add-message! chat {:role :user :content "do something"})
      (let [result (phases/run-turns chat {:provider :test :model "test"
                                           :tools {} :tool-ctx {}
                                           :run-turn-fn (fn [_ _] :complete)
                                           :on-complete (fn [_ctx _turn]
                                                          (swap! complete-count inc)
                                                          ;; Continue twice, then stop
                                                          (if (<= @complete-count 2)
                                                            :continue
                                                            nil))})]
        (is (= :complete (:status result)))
        (is (= 3 (:turns result)))
        (is (= 3 @complete-count))))))

;; ============================================================================
;; Integration: run-phase tool narrowing
;; ============================================================================

(deftest run-phase-tool-narrowing-test
  (testing "run-phase narrows tools to phase definition"
    (let [chat (make-test-chat)
          seen-tools (atom nil)]
      (chat-ctx/add-message! chat {:role :system :content "test"})
      (chat-ctx/add-message! chat {:role :user :content "explore the codebase"})
      (let [all-tools {"read_file" {:name "read_file"}
                        "write_file" {:name "write_file"}
                        "glob" {:name "glob"}
                        "grep" {:name "grep"}
                        "shell" {:name "shell"}}
            result (phases/run-phase chat
                                      {:provider :test :model "test"
                                       :tools all-tools :tool-ctx {}
                                       :run-turn-fn (fn [_ctx opts]
                                                      (reset! seen-tools (set (keys (:tools opts))))
                                                      :complete)}
                                      {:id :explore
                                       :tools #{:read_file :glob :grep}
                                       :system-prompt-suffix "Explore only."
                                       :completion-criteria :explicit})]
        (is (= :complete (:status result)))
        (is (= :explore (:phase-id result)))
        ;; Verify only explore tools were visible
        (is (= #{"read_file" "glob" "grep"} @seen-tools))
        (is (not (contains? @seen-tools "write_file")))
        (is (not (contains? @seen-tools "shell")))))))

(deftest run-phase-injects-prompt-test
  (testing "run-phase injects phase transition message"
    (let [chat (make-test-chat)]
      (chat-ctx/add-message! chat {:role :system :content "test"})
      (chat-ctx/add-message! chat {:role :user :content "task"})
      (phases/run-phase chat
                         {:provider :test :model "test" :tools {} :tool-ctx {}
                          :run-turn-fn (fn [_ _] :complete)}
                         {:id :plan
                          :tools #{}
                          :system-prompt-suffix "Write a plan."
                          :completion-criteria :explicit})
      (let [messages (chat-ctx/get-messages chat)
            phase-msg (->> messages
                           (filter #(= :user (:message/role %)))
                           (filter #(.contains (or (:message/content %) "") "## Phase:"))
                           first)]
        (is (some? phase-msg))
        (is (.contains (:message/content phase-msg) "plan"))
        (is (.contains (:message/content phase-msg) "Write a plan."))))))

(deftest run-phase-self-check-test
  (testing "self-check phase runs checks after completion"
    (let [chat (make-test-chat)
          check-called (atom false)]
      (chat-ctx/add-message! chat {:role :system :content "test"})
      (chat-ctx/add-message! chat {:role :user :content "verify"})
      (let [tools {"clj_kondo" {:name "clj_kondo"
                                 :execute (fn [_ _]
                                            (reset! check-called true)
                                            {:type :success
                                             :metadata {:errors 0 :failed 0}})}}
            result (phases/run-phase chat
                                      {:provider :test :model "test"
                                       :tools tools :tool-ctx {}
                                       :run-turn-fn (fn [_ _] :complete)}
                                      {:id :verify
                                       :tools #{:clj_kondo}
                                       :system-prompt-suffix "Verify."
                                       :completion-criteria :self-check
                                       :self-check {:test-tool "clj_kondo" :max-retries 3}})]
        (is @check-called)
        (is (= :complete (:status result)))
        (is (= :verify (:phase-id result)))))))

(deftest run-phase-artifact-extraction-test
  (testing "plan phase extracts artifact from last assistant message"
    (let [chat (make-test-chat)]
      (chat-ctx/add-message! chat {:role :system :content "test"})
      (chat-ctx/add-message! chat {:role :user :content "plan"})
      (let [result (phases/run-phase chat
                                      {:provider :test :model "test"
                                       :tools {} :tool-ctx {}
                                       :run-turn-fn (fn [ctx _]
                                                      ;; Simulate assistant producing a plan
                                                      (chat-ctx/add-message! ctx {:role :assistant
                                                                                   :content "Step 1: Read files\nStep 2: Write code"})
                                                      :complete)}
                                      {:id :plan
                                       :tools #{}
                                       :system-prompt-suffix "Plan."
                                       :completion-criteria :explicit
                                       :output-as :artifact})]
        (is (= :complete (:status result)))
        (is (some? (:artifact result)))
        (is (.contains (:artifact result) "Step 1"))))))

;; ============================================================================
;; Integration: :signal completion criteria
;; ============================================================================

(deftest run-phase-signal-completion-test
  (testing "signal completion adds complete_phase tool and stops when called"
    (let [chat (make-test-chat)
          turn-count (atom 0)
          seen-tools (atom nil)]
      (chat-ctx/add-message! chat {:role :system :content "test"})
      (chat-ctx/add-message! chat {:role :user :content "explore"})
      (let [result (phases/run-phase chat
                                      {:provider :test :model "test"
                                       :tools {"read_file" {:name "read_file"}
                                               "glob" {:name "glob"}}
                                       :tool-ctx {}
                                       :run-turn-fn (fn [_ctx opts]
                                                      (swap! turn-count inc)
                                                      (reset! seen-tools (set (keys (:tools opts))))
                                                      ;; On turn 3, call complete_phase
                                                      (when (= 3 @turn-count)
                                                        (let [tool (get (:tools opts) "complete_phase")]
                                                          ((:execute tool) {:summary "Done exploring"} {})))
                                                      ;; Always return :continue to simulate tool use
                                                      ;; The on-complete handler detects the atom
                                                      (if (>= @turn-count 3) :complete :continue))}
                                      {:id :explore
                                       :tools #{:read_file :glob}
                                       :system-prompt-suffix "Explore."
                                       :completion-criteria :signal})]
        ;; complete_phase tool was injected
        (is (contains? @seen-tools "complete_phase"))
        ;; Phase narrowed tools are still present
        (is (contains? @seen-tools "read_file"))
        (is (contains? @seen-tools "glob"))
        ;; Completed after 3 turns
        (is (= :complete (:status result)))
        (is (= 3 (:turns result)))
        (is (= :explore (:phase-id result))))))

  (testing "signal completion prompts agent if it stops without calling tool"
    (let [chat (make-test-chat)
          turn-count (atom 0)]
      (chat-ctx/add-message! chat {:role :system :content "test"})
      (chat-ctx/add-message! chat {:role :user :content "explore"})
      (let [result (phases/run-phase chat
                                      {:provider :test :model "test"
                                       :tools {} :tool-ctx {}
                                       :run-turn-fn (fn [ctx opts]
                                                      (swap! turn-count inc)
                                                      (cond
                                                        ;; Turn 1: complete without calling tool
                                                        (= 1 @turn-count) :complete
                                                        ;; Turn 2: after being prompted, call the tool
                                                        (= 2 @turn-count)
                                                        (do (let [tool (get (:tools opts) "complete_phase")]
                                                              ((:execute tool) {:summary "OK done"} {}))
                                                            :complete)
                                                        :else :complete))}
                                      {:id :test-signal
                                       :tools #{}
                                       :system-prompt-suffix "Test."
                                       :completion-criteria :signal})]
        ;; Should have continued after first :complete (no tool call)
        ;; and stopped after second :complete (tool called)
        (is (= :complete (:status result)))
        (is (= 2 (:turns result)))
        ;; Check that prompt message was injected
        (let [msgs (chat-ctx/get-messages chat)
              prompt-msg (->> msgs
                              (filter #(= :user (:message/role %)))
                              (filter #(.contains (or (:message/content %) "") "complete_phase"))
                              (filter #(not (.contains (or (:message/content %) "") "## Phase:"))))]
          (is (= 1 (count prompt-msg))))))))

;; ============================================================================
;; Integration: run-workflow
;; ============================================================================

(deftest run-workflow-sequential-test
  (testing "workflow runs phases in order"
    (let [chat (make-test-chat)
          phase-order (atom [])]
      (chat-ctx/add-message! chat {:role :system :content "test"})
      (chat-ctx/add-message! chat {:role :user :content "task"})
      (let [wf {:phases [:explore :implement]
                :phase-defs {:explore {:id :explore
                                       :tools #{}
                                       :system-prompt-suffix "Explore."
                                       :completion-criteria :explicit}
                             :implement {:id :implement
                                          :tools #{}
                                          :system-prompt-suffix "Implement."
                                          :completion-criteria :explicit}}}
            result (phases/run-workflow chat
                                        {:provider :test :model "test"
                                         :tools {} :tool-ctx {}
                                         :run-turn-fn (fn [ctx _]
                                                        (let [msgs (chat-ctx/get-messages ctx)
                                                              phase-msgs (->> msgs
                                                                              (filter #(= :user (:message/role %)))
                                                                              (filter #(.contains (or (:message/content %) "") "## Phase:")))]
                                                          (when-let [last-phase (last phase-msgs)]
                                                            (let [content (:message/content last-phase)]
                                                              (cond
                                                                (.contains content "explore") (swap! phase-order conj :explore)
                                                                (.contains content "implement") (swap! phase-order conj :implement)))))
                                                        :complete)}
                                        wf)]
        (is (= :complete (:status result)))
        (is (= 2 (:phases-completed result)))
        (is (= 2 (:total-turns result)))
        ;; Phases ran in order
        (is (= [:explore :implement] @phase-order))))))

(deftest run-workflow-stops-on-budget-test
  (testing "workflow stops when budget exhausted"
    (let [chat (make-test-chat {:budget-dollars 0.00})]
      (chat-ctx/add-message! chat {:role :system :content "test"})
      (chat-ctx/add-message! chat {:role :user :content "task"})
      (let [wf {:phases [:explore :implement :verify]
                :phase-defs {:explore {:id :explore
                                       :tools #{}
                                       :system-prompt-suffix "Explore."
                                       :completion-criteria :explicit}
                             :implement {:id :implement
                                          :tools #{}
                                          :system-prompt-suffix "Implement."
                                          :completion-criteria :explicit}
                             :verify {:id :verify
                                       :tools #{}
                                       :system-prompt-suffix "Verify."
                                       :completion-criteria :explicit}}}
            result (phases/run-workflow chat
                                        {:provider :test :model "test"
                                         :tools {} :tool-ctx {}
                                         :run-turn-fn (fn [_ _] :continue)}
                                        wf)]
        (is (= :budget-exceeded (:status result)))
        (is (= :explore (:failed-phase result)))))))

(deftest run-workflow-artifact-passing-test
  (testing "artifact from plan phase injected into implement phase"
    (let [chat (make-test-chat)
          implement-saw-artifact (atom false)]
      (chat-ctx/add-message! chat {:role :system :content "test"})
      (chat-ctx/add-message! chat {:role :user :content "task"})
      (let [wf {:phases [:plan :implement]
                :phase-defs {:plan {:id :plan
                                    :tools #{}
                                    :system-prompt-suffix "Plan."
                                    :completion-criteria :explicit
                                    :output-as :artifact}
                             :implement {:id :implement
                                          :tools #{}
                                          :system-prompt-suffix "Implement."
                                          :completion-criteria :explicit}}}
            result (phases/run-workflow chat
                                        {:provider :test :model "test"
                                         :tools {} :tool-ctx {}
                                         :run-turn-fn (fn [ctx _]
                                                        (let [msgs (chat-ctx/get-messages ctx)
                                                              content-strs (map :message/content msgs)
                                                              has-artifact (some #(and % (.contains % "Artifact from previous phase")) content-strs)
                                                              in-implement (some #(and % (.contains % "implement")) content-strs)]
                                                          ;; Check if implement phase sees the artifact
                                                          (when (and has-artifact in-implement)
                                                            (reset! implement-saw-artifact true))
                                                          ;; Plan phase produces an artifact
                                                          (when (some #(and % (.contains % "## Phase: plan")) content-strs)
                                                            (chat-ctx/add-message! ctx {:role :assistant
                                                                                         :content "The plan: do X then Y"})))
                                                        :complete)}
                                        wf)]
        (is (= :complete (:status result)))
        (is (contains? (:artifacts result) :plan))
        (is @implement-saw-artifact "implement phase should see artifact from plan")))))

;; ============================================================================
;; Integration: self-check-loop with feedback injection
;; ============================================================================

(deftest self-check-loop-retries-test
  (testing "self-check-loop retries on failure and injects feedback"
    (let [chat (make-test-chat)
          call-count (atom 0)]
      (chat-ctx/add-message! chat {:role :system :content "test"})
      (let [tools {"clj_kondo" {:name "clj_kondo"
                                 :execute (fn [_ _]
                                            (swap! call-count inc)
                                            ;; Fail first 2 times, pass on 3rd
                                            (if (< @call-count 3)
                                              {:type :error
                                               :content "error found"
                                               :metadata {:errors 1}}
                                              {:type :success
                                               :metadata {:errors 0}}))}}
            result (phases/self-check-loop chat {} tools
                                            {:test-tool "clj_kondo" :max-retries 5})]
        (is (= :passed result))
        (is (= 3 @call-count))
        ;; Should have injected 2 feedback messages
        (let [feedback-msgs (->> (chat-ctx/get-messages chat)
                                  (filter #(= :user (:message/role %)))
                                  (filter #(.contains (or (:message/content %) "") "Automatic Check Feedback")))]
          (is (= 2 (count feedback-msgs)))))))

  (testing "self-check-loop returns max-retries-exceeded"
    (let [chat (make-test-chat)]
      (chat-ctx/add-message! chat {:role :system :content "test"})
      (let [tools {"clj_kondo" {:name "clj_kondo"
                                 :execute (fn [_ _] {:type :error
                                                      :content "always fails"
                                                      :metadata {:errors 1}})}}
            result (phases/self-check-loop chat {} tools
                                            {:test-tool "clj_kondo" :max-retries 2})]
        (is (= :max-retries-exceeded result))))))
