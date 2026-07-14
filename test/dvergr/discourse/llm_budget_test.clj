(ns dvergr.discourse.llm-budget-test
  "What happens when an agent runs out of money.

   There were NO budget tests, which is how the following shipped: the turn loop
   blocked on a CountDownLatch for `grace-ms` (60s) waiting for a manager
   directive — from inside the participant spin, i.e. on spindel's DRAIN thread,
   under the [:engine/draining?] CAS. Every room in a process shares one
   execution context, so ONE agent exhausting its budget froze the entire
   server's event loop for a full minute. Nothing ever called `directive!`, so
   the latch was never counted down: it was not an occasional stall, it was
   every time.

   And when the stopwatch expired, the loop 'soft-continued' into a wrap-up turn
   — an extra LLM call, charged AFTER the ceiling was hit, chosen by nobody.

   These tests pin the two properties that autopsy demands:
     1. exhaustion ENDS the turn — promptly, holding nothing;
     2. exhaustion spends NOTHING further — no wrap-up turn."
  (:require [clojure.test :refer [deftest is testing]]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [dvergr.discourse :as d]
            [dvergr.discourse.llm :as llm]
            [dvergr.chat.accounting :as acct]
            [dvergr.chat.context :as cc]))

(defn- spendthrift-turn-fn
  "A turn-fn that burns `per-turn` dollars and always wants ANOTHER turn.

   It debits the budget signal directly rather than going through
   `account-usage!`, whose cost comes from the model pricing table — a mock
   model prices at zero, and a test that cannot spend cannot test a ceiling.

   Counts its invocations: an agent that respects its ceiling must stop calling
   this the moment the money is gone. `seen-ctx` captures the chat-ctx so a test
   can play the human who raises the budget."
  [calls per-turn seen-ctx]
  (fn [chat-ctx _opts]
    (swap! calls inc)
    (reset! seen-ctx chat-ctx)
    (binding [ec/*execution-context* (:spindel-ctx chat-ctx)]
      (swap! (:budget-signal chat-ctx) update :used
             + (long (* per-turn acct/MICRODOLLARS-PER-DOLLAR))))
    (cc/add-message! chat-ctx {:role :assistant :content "still working…"})
    :continue))

(defn- ask-and-wait
  "Ask the agent and wait for the participant's turn loop to settle.
   Returns [reply elapsed-ms]. A reply of ::timeout means the loop is STUCK —
   which, before the fix, is exactly what a budget-exhausted agent did."
  [room agent-id wait-ms]
  (let [p     (promise)
        start (System/currentTimeMillis)]
    (binding [ec/*execution-context* (:ctx room)]
      (sp/spawn!
       (sp/spin (deliver p (sp/await (d/ask room agent-id {:content "go"}))))))
    (let [reply (deref p wait-ms ::timeout)]
      [reply (- (System/currentTimeMillis) start)])))

(deftest budget-exhaustion-ends-the-turn-promptly
  (testing "an agent out of budget STOPS — it does not park, and it does not
            hold the engine. Before the fix this blocked the drain thread for
            grace-ms (60s), freezing every room in the process."
    (let [r        (d/room :budget-t)
          calls    (atom 0)
          seen-ctx (atom nil)]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (llm/llm-agent
                   {:id :spender
                    :spec {:provider :mock :model "mock" :system-prompt "spend"}
                    ;; a ceiling it blows through on the first turn
                    :budget {:dollars 0.01}
                    :run-turn-fn (spendthrift-turn-fn calls 0.05 seen-ctx)})))
      (let [[_ elapsed] (ask-and-wait r :spender 10000)]
        (is (< elapsed 5000)
            (str "the turn loop must not hold anything while awaiting a human. "
                 "Took " elapsed "ms — a latch/stopwatch has crept back in."))))))

(deftest budget-exhaustion-spends-nothing-further
  (testing "no wrap-up turn: the ceiling is a ceiling. The old loop answered
            budget exhaustion with ANOTHER LLM call — unbudgeted, over the
            limit, and triggered by a timer rather than by a person."
    (let [r        (d/room :budget-t2)
          calls    (atom 0)
          seen-ctx (atom nil)]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (llm/llm-agent
                   {:id :spender
                    :spec {:provider :mock :model "mock" :system-prompt "spend"}
                    :budget {:dollars 0.01}
                    :run-turn-fn (spendthrift-turn-fn calls 0.05 seen-ctx)})))
      (ask-and-wait r :spender 10000)
      ;; ONE turn ran, blew the ceiling, and the loop ended. A second call would
      ;; be the wrap-up turn — money spent past a limit nobody agreed to lift.
      (is (= 1 @calls)
          (str "expected exactly 1 turn (the one that exhausted the budget); "
               "got " @calls ". A wrap-up turn is spending past the ceiling.")))))

(deftest budget-is-resumable-by-raising-it
  (testing "resumption is an ORDINARY message, not a parked continuation:
            raise the budget, speak again, and the agent carries on. This is the
            whole design — the durable room log is the continuation, which is the
            only kind spindel can have (it has no durable conts)."
    (let [r        (d/room :budget-t3)
          calls    (atom 0)
          seen-ctx (atom nil)]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (llm/llm-agent
                   {:id :spender
                    :spec {:provider :mock :model "mock" :system-prompt "spend"}
                    :budget {:dollars 0.01}
                    :run-turn-fn (spendthrift-turn-fn calls 0.05 seen-ctx)})))
      (ask-and-wait r :spender 10000)
      (is (= 1 @calls) "first ask: one turn, then the ceiling stops it")

      ;; the human raises the room's budget (simmis: room settings → raise-budget!)
      (let [chat-ctx @seen-ctx]
        (is (some? chat-ctx) "the agent ran, so we have its chat-ctx")
        (binding [ec/*execution-context* (:spindel-ctx chat-ctx)]
          (swap! (:budget-signal chat-ctx) update :total
                 + (long (* 1.0 acct/MICRODOLLARS-PER-DOLLAR)))))

      ;; …and speaks. No latch was waiting; the message itself is the resume.
      (ask-and-wait r :spender 10000)
      (is (>= @calls 2)
          "after the budget was raised, an ordinary message must resume work"))))
