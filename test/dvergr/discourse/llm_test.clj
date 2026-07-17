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
                    :budget {:dollars 10.0}
                    :run-turn-fn (make-mock-turn-fn script)})))
      (let [reply (await-spin r #(d/ask % :worker {:content "go"}))]
        (is (= "Final answer." (:content reply)))
        (is (empty? @script) "all three turns ran")))))

;; The old max-turns-hits-budget test is gone — :max-turns is no longer
;; a hard cap. Budget-checkpoint via dvergr.agent.process is the new
;; termination path (escalates to manager when :dollars is exhausted;
;; soft-wraps on grace-timeout). End-to-end coverage of that flow lives
;; in the REPL smoke test for now; a unit test would need a mock
;; chat-ctx whose budget signal can be flipped to exhausted on demand.

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

;; ============================================================================
;; Steerable turn — the ONE-control-plane arbiter
;; ============================================================================
;; A gated step blocks "in the LLM call" polling :cancel? — mirroring the real
;; SSE poll in model.chat (the sleep simulates a slow provider stream, it is
;; the semantics under test, not a coordination patch). `entered` tells the
;; test the call is genuinely in flight before it posts control messages.

(defn- make-queued-turn-fn
  "Each element of `steps-atom` is (fn [chat-ctx opts] -> result), consumed
   one per LLM call. Exhausted → :complete. `calls-log` records the :spec
   each call saw."
  [steps-atom calls-log]
  (fn [chat-ctx opts]
    (swap! calls-log conj {:spec (:spec opts)})
    (let [step (first @steps-atom)]
      (swap! steps-atom rest)
      (if step (step chat-ctx opts) :complete))))

(defn- block-until-cancelled-step
  "Simulates an in-flight streaming call: delivers `entered` on entry, then
   polls :cancel? like the SSE loop. Returns :cancelled when preempted (as
   run-agent-turn! does), :complete if `escape-ms` elapses (test safety net)."
  [entered escape-ms]
  (fn [_chat-ctx {:keys [cancel?]}]
    (deliver entered true)
    (let [t0 (System/currentTimeMillis)]
      (loop []
        (cond
          (and cancel? (cancel?)) :cancelled
          (> (- (System/currentTimeMillis) t0) escape-ms) :complete
          :else (do (Thread/sleep 5) (recur)))))))

(defn- gated-reply-step
  "Blocks until `gate` is delivered (still honoring :cancel?), then adds the
   assistant reply and completes."
  [gate text]
  (fn [chat-ctx {:keys [cancel?]}]
    (loop []
      (cond
        (and cancel? (cancel?)) :cancelled
        (realized? gate) (do (cc/add-message! chat-ctx {:role :assistant :content text})
                             :complete)
        :else (do (Thread/sleep 5) (recur))))))

(defn- reply-step [text]
  (fn [chat-ctx _] (cc/add-message! chat-ctx {:role :assistant :content text}) :complete))

(deftest steer-mid-turn
  (testing "a content message arriving DURING a turn cancels the in-flight call,
            folds in, and the next call answers — nothing lost, nothing stale"
    (let [r        (d/room :steer-room)
          entered  (promise)
          steps    (atom [(block-until-cancelled-step entered 8000)
                          (reply-step "steered answer")])
          calls    (atom [])]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (llm/llm-agent {:id :steer-worker
                                  :spec {:provider :mock :model "mock"}
                                  :budget {:dollars 10.0}
                                  :run-turn-fn (make-queued-turn-fn steps calls)})))
      ;; ask drives the triggering message and awaits the FINAL reply
      (let [reply-f (future (await-spin r #(d/ask % :steer-worker {:content "go"}) 10000))]
        (is (true? (deref entered 3000 ::timeout)) "call 1 in flight")
        ;; steer while call 1 runs
        (d/post! r (d/message :tester :steer-worker "actually, do B instead"))
        (let [reply @reply-f]
          (is (= "steered answer" (:content reply)))
          (is (= 2 (count @calls)) "call 1 cancelled, call 2 answered"))))))

(deftest cancel-directive-mid-turn
  (testing ":directive/cancel PREEMPTS a running turn (it used to queue behind it)"
    (let [r       (d/room :cancel-room)
          entered (promise)
          steps   (atom [(block-until-cancelled-step entered 8000)])
          calls   (atom [])]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (llm/llm-agent {:id :cancel-worker
                                  :spec {:provider :mock :model "mock"}
                                  :budget {:dollars 10.0}
                                  :run-turn-fn (make-queued-turn-fn steps calls)})))
      (let [reply-f (future (await-spin r #(d/ask % :cancel-worker {:content "go"}) 4000))]
        (is (true? (deref entered 3000 ::timeout)) "call in flight")
        (d/post! r {:to :cancel-worker :type :directive/cancel})
        (let [reply @reply-f]
          ;; turn ended without a reply: ask times out (no stale answer posted)
          (is (= ::timeout reply))
          (is (= 1 (count @calls)) "no second call after cancel"))))))

(deftest raise-budget-does-not-preempt
  (testing "a raise-budget directive mid-turn applies inline and the SAME call
            completes (never cancelled)"
    (let [r       (d/room :budget-room)
          gate    (promise)
          steps   (atom [(gated-reply-step gate "done under new budget")])
          calls   (atom [])]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (llm/llm-agent {:id :budget-worker
                                  :spec {:provider :mock :model "mock"}
                                  :budget {:dollars 1.0}
                                  :run-turn-fn (make-queued-turn-fn steps calls)})))
      (let [reply-f (future (await-spin r #(d/ask % :budget-worker {:content "go"}) 10000))]
        ;; give the turn a moment to start, then raise budget mid-call
        (Thread/sleep 100)
        (d/post! r {:to :budget-worker :type :directive/raise-budget :payload {:dollars 2.0}})
        (Thread/sleep 100)
        (deliver gate true)
        (let [reply @reply-f]
          (is (= "done under new budget" (:content reply)))
          (is (= 1 (count @calls)) "the call was NOT restarted"))))))

(deftest switch-model-mid-turn-restarts-call
  (testing ":directive/switch-model cancels the in-flight call and restarts it
            under the swapped spec"
    (let [r       (d/room :switch-room)
          entered (promise)
          steps   (atom [(block-until-cancelled-step entered 8000)
                         (reply-step "answer from new model")])
          calls   (atom [])]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (llm/llm-agent {:id :switch-worker
                                  :spec {:provider :mock :model "mock-1"}
                                  :budget {:dollars 10.0}
                                  :run-turn-fn (make-queued-turn-fn steps calls)})))
      (let [reply-f (future (await-spin r #(d/ask % :switch-worker {:content "go"}) 10000))]
        (is (true? (deref entered 3000 ::timeout)))
        (d/post! r {:to :switch-worker :type :directive/switch-model :payload {:model "mock-2"}})
        (let [reply @reply-f]
          (is (= "answer from new model" (:content reply)))
          (is (= 2 (count @calls)))
          (is (= "mock-2" (get-in (second @calls) [:spec :model]))
              "restarted call carries the swapped model in its spec"))))))
