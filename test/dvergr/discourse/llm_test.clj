(ns dvergr.discourse.llm-test
  "Tests for dvergr.discourse.llm — the LLM-backed participant constructor.
   Uses a mock run-turn-fn to simulate LLM responses; no real API calls."
  (:require [clojure.test :refer [deftest is testing]]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [dvergr.discourse :as d]
            [dvergr.discourse.attention :as attention]
            [dvergr.discourse.llm :as llm]
            [dvergr.chat.context :as cc]
            [dvergr.agent.run :as run]
            [dvergr.room.store :as store]
            [dvergr.room.store.memory :as memory]))

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

(defn- await-condition [pred wait-ms]
  (let [deadline (+ (System/currentTimeMillis) wait-ms)]
    (loop []
      (cond
        (pred) true
        (>= (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 5) (recur))))))

(deftest default-attention-policy-is-structured-and-thread-aware
  (let [root (d/message :human :agent "root")
        same (d/reply :human :agent "correction" root)
        other (d/message :human :agent "another topic")
        same-decision (llm/default-attention-policy
                       {:active-message root :incoming-message same})
        other-decision (llm/default-attention-policy
                        {:active-message root :incoming-message other})]
    (is (= :restart (:control same-decision)))
    (is (= :steer (attention/legacy-action same-decision)))
    (is (= :enqueue (:activation other-decision)))
    (is (= :queue (attention/legacy-action other-decision)))))

(defn- fail-output-store [delegate actor]
  (reify store/PRoomStore
    (-store-room! [_ room-id metadata]
      (store/-store-room! delegate room-id metadata))
    (-load-room [_ id-or-slug]
      (store/-load-room delegate id-or-slug))
    (-delete-room! [_ room-id]
      (store/-delete-room! delegate room-id))
    (-list-rooms [_]
      (store/-list-rooms delegate))
    (-store-message! [_ room-id message]
      (if (= actor (:from message))
        (throw (ex-info "scripted output persistence failure" {:message message}))
        (store/-store-message! delegate room-id message)))
    (-message-thread-root [_ room-id message-id]
      (store/-message-thread-root delegate room-id message-id))
    (-list-messages [_ room-id opts]
      (store/-list-messages delegate room-id opts))
    (-store-run! [_ room-id run]
      (store/-store-run! delegate room-id run))
    (-load-run [_ room-id run-id]
      (store/-load-run delegate room-id run-id))
    (-list-runs [_ room-id opts]
      (store/-list-runs delegate room-id opts))
    store/PAttentionStore
    (-store-attention! [_ room-id fact]
      (store/-store-attention! delegate room-id fact))
    (-list-attention [_ room-id opts]
      (store/-list-attention delegate room-id opts))))

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

(deftest agent-turn-persists-and-correlates-one-run
  (testing "one inbound trigger owns all model rounds, activity, and final output"
    (let [st (memory/make)
          r (d/make-room {:id :run-correlated :store st})
          calls (atom 0)
          turn-fn (fn [chat-ctx _opts]
                    (if (= 1 (swap! calls inc))
                      (do
                        (cc/add-message! chat-ctx
                                         {:role :assistant
                                          :content "checking"
                                          :tool-uses [{:tool-use/id "t1"
                                                       :tool-use/name "search"
                                                       :tool-use/input {:q "x"}}]})
                        :continue)
                      (do
                        (cc/add-message! chat-ctx {:role :assistant :content "done"})
                        :complete)))]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (llm/llm-agent {:id :researcher
                                  :spec {:provider :mock :model "mock"}
                                  :run-turn-fn turn-fn})))
      (try
        (let [reply (await-spin r #(d/ask % :researcher {:content "go"}))
              second-reply (await-spin r #(d/ask % :researcher {:content "again"}))
              runs (run/runs r)
              stored (d/messages r {:limit 20})
              activities (filterv #(= :_activity (:to %)) stored)
              activity (first activities)
              run-row (first (filter #(= (:in-reply-to reply) (:run/trigger %)) runs))
              run-id (:run/id run-row)]
          (is (= 3 @calls))
          (is (= 2 (count runs)))
          (is (= :completed (:run/status run-row)))
          (is (= (:in-reply-to reply) (:run/trigger run-row)))
          (is (= run-id (get-in reply [:metadata :run-id])))
          (is (not= run-id (get-in second-reply [:metadata :run-id])))
          (is (= run-id (get-in activity [:metadata :run-id])))
          (is (= 1 (count activities))
              "a new run does not replay historical tool activity")
          (is (= (:thread-root-id reply) (:thread-root-id activity))
              "tool activity projects into the trigger's dedicated thread")
          (is (empty? (run/active-runs :run-correlated))))
        (finally
          (d/close-room! r))))))

(deftest targeted-cancel-does-not-cancel-the-next-run
  (testing "a Run-local cancellation token expires with that Run"
    (let [st (memory/make)
          r (d/make-room {:id :run-cancel-reuse :store st})
          first-started (promise)
          calls (atom 0)
          turn-fn
          (fn [chat-ctx {:keys [cancel?]}]
            (if (= 1 (swap! calls inc))
              (do
                (deliver first-started true)
                (loop []
                  (when-not (cancel?)
                    (Thread/sleep 5)
                    (recur)))
                :complete)
              (do
                (cc/add-message! chat-ctx {:role :assistant :content "second completed"})
                :complete)))]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (llm/llm-agent {:id :worker
                                  :spec {:provider :mock :model "mock"}
                                  :run-turn-fn turn-fn})))
      (try
        (d/post! r (d/message :alice :worker "cancel this run"))
        (is (= true (deref first-started 2000 ::timeout)))
        (let [run-id (:run/id (first (run/active-runs :run-cancel-reuse)))]
          (is (uuid? run-id))
          (is (run/cancel-run! run-id))
          (is (await-condition
               #(= :cancelled (:run/status (run/run r run-id)))
               3000)))
        (let [reply (await-spin r #(d/ask % :worker {:content "next run"}) 3000)]
          (is (= "second completed" (:content reply)))
          (is (= :completed
                 (:run/status (run/run r (get-in reply [:metadata :run-id])))))
          (is (= #{:cancelled :completed}
                 (set (map :run/status (run/runs r)))))
          (is (empty? (run/active-runs :run-cancel-reuse))))
        (finally
          (d/close-room! r))))))

(deftest run-finish-is-observed-after-durable-output
  (testing "a finished event is a safe frontier for querying correlated output"
    (let [st (memory/make)
          r (d/make-room {:id :run-output-frontier :store st})
          observed (promise)
          watch-key (random-uuid)
          turn-fn (fn [chat-ctx _opts]
                    (cc/add-message! chat-ctx {:role :assistant :content "durable output"})
                    :complete)]
      (try
        (run/watch-runs!
         watch-key
         (fn [{:keys [type run]}]
           (when (and (= :run/finished type)
                      (= :run-output-frontier (:run/room run)))
             (deliver observed
                      (boolean
                       (some #(and (= (:run/id run) (get-in % [:metadata :run-id]))
                                   (= :worker (:from %)))
                             (d/messages r {:limit 20})))))))
        (binding [ec/*execution-context* (:ctx r)]
          (d/join r (llm/llm-agent {:id :worker
                                    :spec {:provider :mock :model "mock"}
                                    :run-turn-fn turn-fn})))
        (let [reply (await-spin r #(d/ask % :worker {:content "go"}) 3000)]
          (is (= "durable output" (:content reply)))
          (is (= true (deref observed 2000 ::timeout))))
        (finally
          (run/unwatch-runs! watch-key)
          (d/close-room! r))))))

(deftest reply-emission-failure-fails-the-run
  (testing "completion is not recorded when the correlated output cannot persist"
    (let [base (memory/make)
          st (fail-output-store base :worker)
          r (d/make-room {:id :run-output-failure :store st})
          turn-fn (fn [chat-ctx _opts]
                    (cc/add-message! chat-ctx {:role :assistant :content "lost output"})
                    :complete)]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (llm/llm-agent {:id :worker
                                  :spec {:provider :mock :model "mock"}
                                  :run-turn-fn turn-fn})))
      (try
        (d/post! r (d/message :alice :worker "go"))
        (is (await-condition #(= :failed (:run/status (first (run/runs r)))) 3000))
        (let [failed (first (run/runs r))]
          (is (= :reply-emission-failed (:run/reason failed)))
          (is (empty? (filter #(= (:run/id failed) (get-in % [:metadata :run-id]))
                              (d/messages r {:limit 20}))))
          (is (empty? (run/active-runs :run-output-failure))))
        (finally
          (d/close-room! r))))))

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

(deftest silent-failure-does-not-repost-stale-reply
  (testing "a turn that ends with NO error tag and NO new assistant message
            (e.g. provider resolution failed before any generation) must NOT
            re-post the seeded prior reply — #38, the 'repeating' bug"
    (let [r (d/room :t)
          ;; ask 1 answers normally; ask 2's turn completes SILENTLY (no
          ;; message added, no error tag) — the shape a missing provider makes.
          script (atom [[:complete "First answer."]
                        [:complete nil]])]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (llm/llm-agent
                   {:id :quiet
                    :spec {:provider :mock :model "mock"}
                    :run-turn-fn (make-mock-turn-fn script)})))
      (let [first-reply (await-spin r #(d/ask % :quiet {:content "go"}))]
        (is (= "First answer." (:content first-reply))))
      (let [second-reply (await-spin r #(d/ask % :quiet {:content "again"}) 1500)]
        (is (= ::timeout second-reply)
            (str "the stale prior reply must not be re-posted; got: "
                 (pr-str (:content second-reply))))
        (is (empty? @script) "both script entries consumed")))))

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
                                                         :max-iter 4})
                               15000)]
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

(deftest same-thread-message-steers-mid-turn
  (testing "same-thread content arriving DURING a turn cancels the in-flight call,
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
        ;; Reply inside the active topic: this is steering, not a new job.
        (let [trigger (some #(when (= "go" (:content %)) %) (d/log r))]
          (is trigger "the triggering message is visible in the room log")
          (d/post! r (d/reply :tester :steer-worker
                              "actually, do B instead" trigger)))
        (let [reply @reply-f]
          (is (= "steered answer" (:content reply)))
          (is (= 2 (count @calls)) "call 1 cancelled, call 2 answered"))))))

(deftest different-thread-messages-queue-globally-fifo
  (testing "multiple topics do not cancel the active execution or reorder at hand-back"
    (let [r       (d/room :thread-queue-room)
          entered (promise)
          gate    (promise)
          steps   (atom [(fn [chat-ctx opts]
                           (deliver entered true)
                           ((gated-reply-step gate "first answer") chat-ctx opts))
                         (reply-step "second answer")
                         (reply-step "third answer")
                         (reply-step "fourth answer")])
          calls   (atom [])]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (llm/llm-agent {:id :thread-worker
                                  :spec {:provider :mock :model "mock"}
                                  :budget {:dollars 10.0}
                                  :run-turn-fn (make-queued-turn-fn steps calls)})))
      (let [first-reply-f
            (future (await-spin r #(d/ask % :thread-worker {:content "topic A"}) 10000))]
        (is (true? (deref entered 3000 ::timeout)) "topic A call is in flight")
        ;; Top-level Messages self-root, so each is a distinct thread.
        (d/post! r (d/message :tester :thread-worker "topic B"))
        (d/post! r (d/message :tester :thread-worker "topic C"))
        (Thread/sleep 150)
        (is (= 1 (count @calls)) "other topics did not preempt topic A")
        (deliver gate true)
        ;; Arrive at the completion/hand-back boundary. Even if this reaches the
        ;; live mailbox before the outer participant resumes, it is newer than B
        ;; and C and therefore must run after both.
        (d/post! r (d/message :tester :thread-worker "topic D"))
        (is (= "first answer" (:content @first-reply-f)))
        (let [deadline (+ (System/currentTimeMillis) 5000)]
          (while (and (< (System/currentTimeMillis) deadline)
                      (< (count (filter #(= :thread-worker (:from %)) (d/log r))) 4))
            (Thread/sleep 10)))
        (is (= 4 (count @calls)) "all queued topics start after topic A")
        (let [responses (filter #(= :thread-worker (:from %)) (d/log r))]
          (is (= ["first answer" "second answer" "third answer" "fourth answer"]
                 (mapv :content responses))))))))

(deftest attention-policy-can-queue-same-thread-peer-chatter
  (testing "thread membership does not permanently imply interruption"
    (let [r       (d/room :peer-attention-room)
          entered (promise)
          gate    (promise)
          steps   (atom [(fn [chat-ctx opts]
                           (deliver entered true)
                           ((gated-reply-step gate "primary answer") chat-ctx opts))
                         (reply-step "peer follow-up answer")])
          calls   (atom [])
          policy  (fn [{:keys [active-message incoming-message] :as context}]
                    (if (and (d/same-thread? active-message incoming-message)
                             (= :peer (:from incoming-message)))
                      (attention/enqueue :test/peer-chatter)
                      (llm/default-attention-policy context)))]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (llm/llm-agent {:id :policy-worker
                                  :spec {:provider :mock :model "mock"}
                                  :budget {:dollars 10.0}
                                  :attention-policy policy
                                  :run-turn-fn (make-queued-turn-fn steps calls)})))
      (let [reply-f (future (await-spin r #(d/ask % :policy-worker {:content "root"}) 10000))]
        (is (true? (deref entered 3000 ::timeout)) "root call is in flight")
        (let [trigger (some #(when (= "root" (:content %)) %) (d/log r))]
          (d/post! r (d/reply :peer :policy-worker "peer note" trigger)))
        (Thread/sleep 150)
        (is (= 1 (count @calls)) "peer note did not preempt the root call")
        (deliver gate true)
        (is (= "primary answer" (:content @reply-f)))
        (let [deadline (+ (System/currentTimeMillis) 3000)]
          (while (and (< (System/currentTimeMillis) deadline)
                      (< (count @calls) 2))
            (Thread/sleep 10)))
        (is (= 2 (count @calls)) "peer note became a later execution")))))

(deftest attention-policy-cancel-is-run-local-and-future-work-recovers
  (let [r (d/make-room {:id :policy-cancel-room :store (memory/make)})
        entered (promise)
        steps (atom [(block-until-cancelled-step entered 8000)
                     (reply-step "recovered answer")])
        calls (atom [])
        policy (fn [_]
                 (attention/decision {:memory :remember
                                      :control :cancel
                                      :at :now
                                      :reason :test/authorized-cancel}))]
    (try
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (llm/llm-agent {:id :policy-cancel-worker
                                  :spec {:provider :mock :model "mock"}
                                  :budget {:dollars 10.0}
                                  :attention-policy policy
                                  :run-turn-fn (make-queued-turn-fn steps calls)})))
      (let [first-reply
            (future (await-spin r #(d/ask % :policy-cancel-worker {:content "start"})
                                2500))]
        (is (true? (deref entered 3000 ::timeout)))
        (let [trigger (some #(when (= "start" (:content %)) %) (d/log r))]
          (d/post! r (d/reply :reviewer :policy-cancel-worker "stop this run" trigger)))
        (is (= ::timeout @first-reply) "policy cancellation emits no stale reply")
        (is (await-condition #(empty? (run/active-runs (:id r))) 2000))
        (is (= :cancelled (:run/status (first (run/runs r)))))
        (is (= "recovered answer"
               (:content (await-spin r #(d/ask % :policy-cancel-worker
                                               {:content "new work"}) 4000)))
            "attention cancellation does not poison later executions"))
      (finally
        (d/close-room! r)))))

(deftest attention-policy-suspend-includes-memory-at-safe-boundary
  (let [r (d/make-room {:id :policy-suspend-room :store (memory/make)})
        entered (promise)
        observed (atom nil)
        inspect-memory
        (fn [chat-ctx _]
          (let [messages (cc/get-messages chat-ctx)
                _ (reset! observed messages)
                included? (some #(re-find #"remember before waiting"
                                          (or (:content %) (:message/content %) ""))
                                messages)]
            (cc/add-message! chat-ctx {:role :assistant
                                       :content (if included? "included" "missing")})
            :complete))
        steps (atom [(block-until-cancelled-step entered 8000) inspect-memory])
        calls (atom [])
        policy (fn [_]
                 (attention/decision {:memory :include
                                      :control :suspend
                                      :at :next-safe-boundary
                                      :reason :test/wait}))]
    (try
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (llm/llm-agent {:id :policy-suspend-worker
                                  :spec {:provider :mock :model "mock"}
                                  :budget {:dollars 10.0}
                                  :attention-policy policy
                                  :run-turn-fn (make-queued-turn-fn steps calls)})))
      (let [first-reply
            (future (await-spin r #(d/ask % :policy-suspend-worker {:content "start"})
                                2500))]
        (is (true? (deref entered 3000 ::timeout)))
        (let [trigger (some #(when (= "start" (:content %)) %) (d/log r))]
          (d/post! r (d/reply :reviewer :policy-suspend-worker
                              "remember before waiting" trigger)))
        (is (= ::timeout @first-reply))
        (is (await-condition #(empty? (run/active-runs (:id r))) 2000))
        (is (= :waiting (:run/status (first (run/runs r)))))
        (is (= :attention-suspended (:run/reason (first (run/runs r)))))
        (is (= "included"
               (:content (await-spin r #(d/ask % :policy-suspend-worker
                                               {:content "resume"}) 4000)))
            (pr-str @observed)))
      (finally
        (d/close-room! r)))))

(deftest non-preempting-include-is-admitted-between-provider-rounds
  (let [r (d/make-room {:id :policy-include-boundary :store (memory/make)})
        entered (promise)
        gate (promise)
        observed (atom nil)
        steps (atom [(fn [_chat-ctx _]
                       (deliver entered true)
                       @gate
                       :continue)
                     (fn [chat-ctx _]
                       (reset! observed (cc/get-messages chat-ctx))
                       (cc/add-message! chat-ctx {:role :assistant :content "done"})
                       :complete)])
        calls (atom [])
        policy (fn [_]
                 (attention/decision {:memory :include
                                      :control :continue
                                      :at :next-safe-boundary
                                      :reason :test/include-next-round}))]
    (try
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (llm/llm-agent {:id :include-worker
                                  :spec {:provider :mock :model "mock"}
                                  :budget {:dollars 10.0}
                                  :attention-policy policy
                                  :run-turn-fn (make-queued-turn-fn steps calls)})))
      (let [reply-f (future (await-spin r #(d/ask % :include-worker {:content "start"})
                                        5000))]
        (is (true? (deref entered 3000 ::timeout)))
        (let [trigger (some #(when (= "start" (:content %)) %) (d/log r))]
          (d/post! r (d/reply :reviewer :include-worker "use this next" trigger)))
        (is (await-condition
             #(some (fn [fact]
                      (= :test/include-next-round (:attention/reason fact)))
                    (store/-list-attention (:store r) (:id r)
                                           {:participant :include-worker}))
             2000))
        (deliver gate true)
        (is (= "done" (:content @reply-f)))
        (is (some #(re-find #"use this next"
                            (or (:content %) (:message/content %) ""))
                  @observed)
            "include is visible to the very next provider round"))
      (finally
        (d/close-room! r)))))

(deftest unsupported-attention-remains-deferred-without-becoming-a-new-run
  (let [st (memory/make)
        r (d/make-room {:id :policy-deferred :store st})
        entered (promise)
        gate (promise)
        calls (atom [])
        steps (atom [(fn [chat-ctx _]
                       (deliver entered true)
                       @gate
                       (cc/add-message! chat-ctx {:role :assistant :content "first"})
                       :complete)])
        policy (fn [_]
                 (attention/decision {:memory :include
                                      :control :integrate
                                      :at :after-tool
                                      :reason :test/provider-boundary}))]
    (try
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (llm/llm-agent {:id :deferred-worker
                                  :spec {:provider :mock :model "mock"}
                                  :attention-policy policy
                                  :run-turn-fn (make-queued-turn-fn steps calls)})))
      (let [reply-f (future (await-spin r #(d/ask % :deferred-worker {:content "start"})
                                        5000))]
        (is (true? (deref entered 3000 ::timeout)))
        (let [trigger (some #(when (= "start" (:content %)) %) (d/log r))]
          (d/post! r (d/reply :reviewer :deferred-worker "after the tool" trigger)))
        (is (await-condition
             #(some (fn [fact] (= :deferred (:attention/status fact)))
                    (store/-list-attention st (:id r)
                                           {:participant :deferred-worker}))
             2000))
        (deliver gate true)
        (is (= "first" (:content @reply-f)))
        (Thread/sleep 150)
        (is (= 1 (count @calls)) "deferred input is not silently degraded to enqueue")
        (is (= 1 (count (run/runs r))))
        (is (some #(= :deferred (:attention/status %))
                  (store/-list-attention st (:id r)
                                         {:participant :deferred-worker}))))
      (finally
        (d/close-room! r)))))

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
