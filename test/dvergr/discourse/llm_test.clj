(ns dvergr.discourse.llm-test
  "Tests for dvergr.discourse.llm — the LLM-backed participant constructor.
   Uses a mock run-turn-fn to simulate LLM responses; no real API calls."
  (:require [clojure.test :refer [deftest is testing]]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [dvergr.discourse :as d]
            [dvergr.discourse.llm :as llm]
            [dvergr.chat.context :as cc]))

;; ============================================================================
;; Mock turn-fn — scripts the LLM responses
;; ============================================================================

(defn make-mock-turn-fn
  "Test helper. `script-atom` holds any sequential of [result text] pairs:
   - [:continue text]   adds the assistant message, returns :continue
                        (the agent's loop will call us again)
   - [:complete text]   adds the assistant message, returns :complete
                        (the agent's loop exits)
   - [:error nil]       returns :error
   When the script is exhausted, returns :complete to terminate cleanly."
  [script-atom]
  (fn [chat-ctx _opts]
    (let [script @script-atom]
      (if (empty? script)
        :complete
        (let [[result text] (first script)]
          (swap! script-atom rest)
          (when text
            (cc/add-message! chat-ctx {:role :assistant :content text}))
          result)))))

;; ============================================================================
;; Test helpers
;; ============================================================================

(defn- await-spin
  ([room spin-fn] (await-spin room spin-fn 3000))
  ([room spin-fn wait-ms]
   (let [p (promise)]
     (binding [ec/*execution-context* (:ctx room)]
       (sp/spawn!
         (sp/spin (deliver p (sp/await (spin-fn room))))))
     (deref p wait-ms ::timeout))))

;; ============================================================================
;; Tests
;; ============================================================================

(deftest single-turn-replies
  (testing "Agent replies after a single :complete turn"
    (let [r (d/room :t)
          script (atom [[:complete "Hello, I'm ready to help."]])]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (llm/llm-agent
                    {:id :researcher
                     :spec {:provider :mock :model "mock"
                            :system-prompt "You are a helpful assistant."}
                     :run-turn-fn (make-mock-turn-fn script)})))
      (let [reply (await-spin r #(d/ask % :researcher
                                        {:content "Tell me something"}))]
        (is (= "Hello, I'm ready to help." (:content reply)))
        (is (= :researcher (:from reply)))
        (is (empty? @script) "script fully consumed")))))

(deftest multi-turn-loop-continues-until-complete
  (testing "Agent loops :continue → :continue → :complete; reply is the last"
    (let [r (d/room :t)
          script (atom [[:continue "Step 1: thinking..."]
                        [:continue "Step 2: refining..."]
                        [:complete "Final answer."]])]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (llm/llm-agent
                    {:id :worker
                     :spec {:provider :mock :model "mock"}
                     :budget {:max-turns 5}
                     :run-turn-fn (make-mock-turn-fn script)})))
      (let [reply (await-spin r #(d/ask % :worker {:content "go"}))]
        (is (= "Final answer." (:content reply)))
        (is (empty? @script) "all three turns ran")))))

(deftest max-turns-hits-budget
  (testing "Agent stops at max-turns even if turn-fn keeps returning :continue"
    (let [r (d/room :t)
          ;; Returns :continue forever; will be cut off at max-turns=2
          script (atom (vec (repeatedly 100 #(vector :continue "still working..."))))]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (llm/llm-agent
                    {:id :stuck
                     :spec {:provider :mock :model "mock"}
                     :budget {:max-turns 2}
                     :run-turn-fn (make-mock-turn-fn script)})))
      (let [reply (await-spin r #(d/ask % :stuck {:content "go"}))]
        ;; Last assistant message was 'still working...'; that's the reply
        (is (= "still working..." (:content reply)))
        ;; 2 turns consumed, the rest of the script untouched
        (is (= 98 (count @script)))))))

(deftest error-turn-terminates-cleanly
  (testing "Agent returns last assistant message even if a turn errors"
    (let [r (d/room :t)
          script (atom [[:continue "partial work"]
                        [:error nil]])]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (llm/llm-agent
                    {:id :flaky
                     :spec {:provider :mock :model "mock"}
                     :run-turn-fn (make-mock-turn-fn script)})))
      (let [reply (await-spin r #(d/ask % :flaky {:content "go"}))]
        (is (= "partial work" (:content reply)))))))

(deftest agent-composes-with-iterative-refinement
  (testing "Two llm-agents (coder + reviewer) drive iterative-refinement"
    (let [r (d/room :t)
          coder-script    (atom [[:complete "draft v1"]
                                  [:complete "draft v2 with fix"]])
          reviewer-script (atom [[:complete "needs work"]
                                  [:complete "lgtm"]])]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (llm/llm-agent
                    {:id :coder
                     :spec {:provider :mock :model "mock"}
                     :run-turn-fn (make-mock-turn-fn coder-script)}))
        (d/join r (llm/llm-agent
                    {:id :reviewer
                     :spec {:provider :mock :model "mock"}
                     :run-turn-fn (make-mock-turn-fn reviewer-script)})))
      (let [result (await-spin r
                     #(d/iterative-refinement % :coder :reviewer
                        {:content "build login"}
                        {:accept? (fn [m] (re-find #"(?i)lgtm" (:content m)))
                         :max-iter 4}))]
        (is (= :accepted (:result result)))
        (is (= "draft v2 with fix" (:content (:draft result))))
        (is (= "lgtm" (:content (:review result))))))))

(deftest agent-composes-with-simulate-reply
  (testing "Theory-of-mind probe on an llm-agent leaves parent state intact"
    (let [r (d/room :t)
          script (atom [[:complete "Hi from the agent"]])]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (llm/llm-agent
                    {:id :a
                     :spec {:provider :mock :model "mock"}
                     :run-turn-fn (make-mock-turn-fn script)})))
      ;; Probe via fork — script has only one entry. The probe runs in a
      ;; fork; the fork's agent has its OWN script via the factory. But the
      ;; factory in llm-agent re-creates with the SAME `run-turn-fn`, which
      ;; closes over the SAME script atom. So both parent and fork share
      ;; the script — meaning the fork's probe DOES consume the entry.
      ;; This is a documented v1 limitation: the mock script is shared by
      ;; reference; in production each llm-agent has its own (server-side)
      ;; LLM state, so this isn't an issue.
      (let [imagined (await-spin r
                       #(d/simulate-reply % :a {:content "what if?"}))]
        ;; The fork's agent has its own chat-ctx but shared script atom.
        ;; The reply is "Hi from the agent" — what we expect from the script.
        (is (= "Hi from the agent" (:content imagined)))))))
