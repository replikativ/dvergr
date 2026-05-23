(ns dvergr.discourse-test
  "Tests for dvergr.discourse — the substrate, algebra, fork, patterns,
   and theory-of-mind primitives. No LLM calls; all participants are
   scripted/echo with optional latency/chaos wrappers."
  (:require [clojure.test :refer [deftest is testing]]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.sync :as sync]
            [org.replikativ.spindel.spin.combinators :as comb]
            [dvergr.discourse :as d]))

;; ============================================================================
;; Test fixtures — wrappers for chaos / latency testing.
;; These live in test/ rather than src/ because they're test scaffolding,
;; not production substrate. Re-introduce in src/ if production use cases
;; surface.
;; ============================================================================

(defn with-latency
  "Decorate participant: on-message awaits `base-ms` + uniform jitter before
   delegating. Non-blocking — uses spindel `comb/sleep`."
  [p {:keys [base-ms jitter-ms] :as opts :or {jitter-ms 0}}]
  (let [orig-handler (:on-message p)
        orig-factory (:factory p)]
    (-> p
        (assoc :on-message
               (fn [pp msg]
                 (sp/spin
                   (let [delay (+ base-ms (long (rand jitter-ms)))]
                     (sp/await (comb/sleep delay))
                     (sp/await (orig-handler pp msg))))))
        (assoc :factory
               (when orig-factory
                 (fn [new-ctx] (with-latency (orig-factory new-ctx) opts)))))))

(defn flaky
  "Decorate participant: with probability `error-rate` reply with
   '[ERROR: simulated]'; with probability `timeout-rate` sleep 30s before
   replying '[TIMEOUT]'; otherwise delegate."
  [p {:keys [error-rate timeout-rate] :as opts
      :or {error-rate 0.1 timeout-rate 0.0}}]
  (let [orig-handler (:on-message p)
        orig-factory (:factory p)]
    (-> p
        (assoc :on-message
               (fn [pp msg]
                 (sp/spin
                   (let [r (rand)]
                     (cond
                       (< r error-rate)
                       {:to (:from msg) :content "[ERROR: simulated]"}

                       (< r (+ error-rate timeout-rate))
                       (do (sp/await (comb/sleep 30000))
                           {:to (:from msg) :content "[TIMEOUT]"})

                       :else
                       (sp/await (orig-handler pp msg)))))))
        (assoc :factory
               (when orig-factory
                 (fn [new-ctx] (flaky (orig-factory new-ctx) opts)))))))

;; ============================================================================
;; Test helpers
;; ============================================================================

(defn- await-spin
  "Spawn `spin-fn` (Room → Spin) on `room`'s context, deliver result to a
   promise, wait up to wait-ms. Returns the delivered value or ::timeout."
  ([room spin-fn] (await-spin room spin-fn 2000))
  ([room spin-fn wait-ms]
   (let [p (promise)]
     (binding [ec/*execution-context* (:ctx room)]
       (sp/spawn!
         (sp/spin (deliver p (sp/await (spin-fn room))))))
     (deref p wait-ms ::timeout))))

(defn- error? [m] (re-find #"^\[ERROR" (:content m)))

;; ============================================================================
;; Substrate — Step 1
;; ============================================================================

(deftest single-echo-bot-cycle
  (let [r (d/room :t)]
    (binding [ec/*execution-context* (:ctx r)]
      (d/join r (d/echo :bot)))
    (d/post! r (d/message :driver :bot "hello"))
    (Thread/sleep 200)
    (let [log @(:log r)]
      (is (= 2 (count log)) "echo bot bounces once")
      (is (= "hello"        (:content (first log))))
      (is (= "echo: hello"  (:content (second log))))
      (is (= :driver (:from (first log))))
      (is (= :bot (:from (second log)))))))

(deftest scripted-replies-to-sender
  (let [r (d/room :t)]
    (binding [ec/*execution-context* (:ctx r)]
      (d/join r (d/scripted :s ["one" "two" "three"])))
    ;; Ask three times; each gets the next scripted content.
    (let [m1 (await-spin r #(d/ask % :s {:content "a"}))
          m2 (await-spin r #(d/ask % :s {:content "b"}))
          m3 (await-spin r #(d/ask % :s {:content "c"}))]
      (is (= "one"   (:content m1)))
      (is (= "two"   (:content m2)))
      (is (= "three" (:content m3))))))

;; ============================================================================
;; Algebra — Step 2
;; ============================================================================

(deftest ask-roundtrip
  (let [r (d/room :t)]
    (binding [ec/*execution-context* (:ctx r)]
      (d/join r (d/echo :bot)))
    (let [reply (await-spin r #(d/ask % :bot {:content "ping"}))]
      (is (= "echo: ping" (:content reply)))
      (is (= :bot (:from reply))))))

(deftest ask-does-not-leak-stub
  (let [r (d/room :t)]
    (binding [ec/*execution-context* (:ctx r)]
      (d/join r (d/echo :bot)))
    (await-spin r #(d/ask % :bot {:content "ping"}))
    (Thread/sleep 100)
    (is (= #{:bot} (set (keys @(:participants r))))
        "asker stub removed after reply")))

(deftest fan-out-collects-all-replies
  (let [r (d/room :t)]
    (binding [ec/*execution-context* (:ctx r)]
      (d/join r (d/echo :a))
      (d/join r (d/echo :b))
      (d/join r (d/echo :c)))
    (let [replies (await-spin r #(d/fan-out % [:a :b :c] {:content "hi"}))]
      (is (= 3 (count replies)))
      (is (= #{:a :b :c} (set (map :from replies))))
      (is (every? #(= "echo: hi" (:content %)) replies)))))

(deftest race-returns-fastest
  (let [r (d/room :t)]
    (binding [ec/*execution-context* (:ctx r)]
      (d/join r (with-latency (d/echo :fast)    {:base-ms 30}))
      (d/join r (with-latency (d/echo :slow)    {:base-ms 200}))
      (d/join r (with-latency (d/echo :slowest) {:base-ms 400})))
    (let [winner (await-spin r #(d/race % [:fast :slow :slowest] {:content "go"}))]
      (is (= :fast (:from winner))))))

(deftest quorum-returns-first-n
  (let [r (d/room :t)]
    (binding [ec/*execution-context* (:ctx r)]
      (d/join r (with-latency (d/echo :fast)    {:base-ms 30}))
      (d/join r (with-latency (d/echo :medium)  {:base-ms 100}))
      (d/join r (with-latency (d/echo :slowest) {:base-ms 500})))
    (let [first-two (await-spin r
                      #(d/quorum % [:fast :medium :slowest] {:content "vote"} 2))]
      (is (= 2 (count first-two)))
      (is (= #{:fast :medium} (set (map :from first-two)))))))

(deftest pipeline-threads-content
  (let [r (d/room :t)]
    (binding [ec/*execution-context* (:ctx r)]
      (d/join r (d/echo :a))
      (d/join r (d/echo :b))
      (d/join r (d/echo :c)))
    (let [final (await-spin r #(d/pipeline % [:a :b :c] {:content "x"}))]
      (is (= :c (:from final)))
      (is (= "echo: echo: echo: x" (:content final))))))

(deftest flaky-injects-errors
  (let [r (d/room :t)]
    (binding [ec/*execution-context* (:ctx r)]
      (d/join r (flaky (d/echo :bot) {:error-rate 0.5})))
    (let [n 30
          replies (await-spin r
                    (fn [room]
                      (sp/spin
                        (sp/await
                          (apply comb/parallel
                                 (for [i (range n)]
                                   (d/ask room :bot
                                          {:content (str "m-" i)})))))))]
      (is (= n (count replies)))
      (let [errs (count (filter error? replies))]
        ;; Binomial(30, 0.5) — ~15 ± 5, allow wide band for CI stability
        (is (<= 5 errs 25) (str "expected ~15 errors, got " errs))))))

;; ============================================================================
;; Fork — Step 3
;; ============================================================================

(deftest fork-isolation
  (testing "fork-room produces an independent room; parent unaffected"
    (let [parent (d/room :parent)]
      (binding [ec/*execution-context* (:ctx parent)]
        (d/join parent (d/scripted :s ["one" "two" "three"])))
      (let [fork (d/fork-room parent)]
        ;; Ask the fork's :s; consumes the fork's first reply.
        (let [r1 (await-spin fork #(d/ask % :s {:content "a"}))]
          (is (= "one" (:content r1))))
        ;; Parent's :s should be untouched: still has all three replies.
        (let [r2 (await-spin parent #(d/ask % :s {:content "a"}))]
          (is (= "one" (:content r2))))
        (d/discard fork)))))

(deftest merge-room-flows-new-entries
  (let [parent (d/room :parent)]
    (binding [ec/*execution-context* (:ctx parent)]
      (d/join parent (d/scripted :s ["hi"])))
    (let [fork (d/fork-room parent)
          _    (await-spin fork #(d/ask % :s {:content "go"}))
          before-merge (count @(:log parent))]
      (is (= 0 before-merge) "parent log untouched before merge")
      (d/merge-room parent fork)
      (is (= 2 (count @(:log parent)))
          "fork's new entries (ask + reply) flowed into parent"))))

(deftest discard-stops-context
  (let [parent (d/room :parent)
        fork   (d/fork-room parent)]
    (d/discard fork)
    (is (true? true) "discard returned without error")))

;; ============================================================================
;; Patterns — Step 4
;; ============================================================================

(deftest iterative-refinement-converges
  (let [r (d/room :t)]
    (binding [ec/*execution-context* (:ctx r)]
      (d/join r (d/scripted :coder    ["draft-1" "draft-2" "draft-3"]))
      (d/join r (d/scripted :reviewer ["needs work" "needs more" "lgtm"])))
    (let [result (await-spin r
                   #(d/iterative-refinement % :coder :reviewer
                      {:content "build login"}
                      {:accept? (fn [m] (re-find #"(?i)lgtm" (:content m)))
                       :max-iter 5}))]
      (is (= :accepted (:result result)))
      (is (= 2 (:iterations result)))
      (is (= "draft-3" (:content (:draft result))))
      (is (= "lgtm"    (:content (:review result)))))))

(deftest debate-rounds-history
  (let [r (d/room :t)]
    (binding [ec/*execution-context* (:ctx r)]
      (d/join r (d/scripted :pro [ "in-favor-R1"  "in-favor-R2"]))
      (d/join r (d/scripted :con [ "against-R1"   "against-R2"]))
      (d/join r (d/scripted :n   [ "unclear-R1"   "unclear-R2"])))
    (let [history (await-spin r
                    #(d/debate % [:pro :con :n]
                               {:rounds 2 :initial-content "AI regulation"}))]
      (is (= 2 (count history)))
      (is (= 3 (count (first history))))
      (is (= #{:pro :con :n} (set (map :from (first history)))))
      (is (every? #(re-find #"R1$" (:content %)) (first history)))
      (is (every? #(re-find #"R2$" (:content %)) (second history))))))

(deftest moderate-pick-fn-drives-turn-taking
  (let [r (d/room :t)]
    (binding [ec/*execution-context* (:ctx r)]
      (d/join r (d/scripted :a ["A1" "A2"]))
      (d/join r (d/scripted :b ["B1" "B2"]))
      (d/join r (d/scripted :c ["C1" "C2"])))
    (let [pick   (fn [history]
                   (let [order [:a :b :c]
                         i (dec (count history))]
                     (when (< i 6) (nth order (mod i 3)))))
          history (await-spin r
                    #(d/moderate % {:content "?"}
                                 {:pick-fn pick :max-rounds 10}))]
      (is (= 7 (count history)) "1 initial + 6 picked")
      (is (= [nil :a :b :c :a :b :c] (mapv :from history)))
      (is (= ["?" "A1" "B1" "C1" "A2" "B2" "C2"]
             (mapv :content history))))))

(deftest align-on-converges-when-all-accept
  (let [r (d/room :t)]
    (binding [ec/*execution-context* (:ctx r)]
      (d/join r (d/scripted :mediator ["v2" "v3" "v4-final"]))
      (d/join r (d/scripted :p1 ["needs work" "better" "agree"]))
      (d/join r (d/scripted :p2 ["unclear"    "better" "agree"])))
    (let [result (await-spin r
                   #(d/align-on % :mediator [:p1 :p2] "initial"
                                {:accept? (fn [m] (re-find #"agree"
                                                           (:content m)))
                                 :max-rounds 5}))]
      (is (= :converged (:result result)))
      (is (= 2 (:rounds result)))
      (is (every? #(re-find #"agree" (:content %))
                  (:final-critiques result))))))

;; ============================================================================
;; Theory of Mind — the killer primitive
;; ============================================================================

(deftest simulate-reply-leaves-parent-untouched
  (testing "Three ToM probes don't consume parent's scripted state"
    (let [r (d/room :t)]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (d/scripted :alice ["first" "second" "third"])))
      ;; Simulate three times — each fork sees alice's :first reply.
      (dotimes [_ 3]
        (let [imagined (await-spin r
                         #(d/simulate-reply % :alice {:content "what if?"}))]
          (is (= "first" (:content imagined))
              "every simulation sees alice's CURRENT first reply")))
      ;; Parent log unchanged through all three simulations.
      (is (zero? (count @(:log r)))
          "no entries written to parent room")
      ;; Now actually ask alice in the parent: still has 'first' available.
      (let [real-reply (await-spin r #(d/ask % :alice {:content "real"}))]
        (is (= "first" (:content real-reply))
            "alice's script remained intact through ToM probes")))))

(deftest imagine-conversation-captures-fork-log
  (let [r (d/room :t)]
    (binding [ec/*execution-context* (:ctx r)]
      (d/join r (d/scripted :alice ["A1" "A2"])))
    (let [{:keys [outcome imagined-log]}
          (await-spin r
            #(d/imagine-conversation %
               (fn [fork-room]
                 (sp/spin
                   (sp/await (d/ask fork-room :alice {:content "go"}))))))]
      (is (= "A1" (:content outcome)))
      (is (= 2 (count imagined-log)) "ask + reply in fork log")
      (is (zero? (count @(:log r))) "parent log untouched"))))

;; ============================================================================
;; Algebraic laws (selected; property-based variants are future work)
;; ============================================================================

(deftest pipeline-singleton-is-ask
  (let [r (d/room :t)]
    (binding [ec/*execution-context* (:ctx r)]
      (d/join r (d/echo :bot)))
    (let [via-pipeline (await-spin r #(d/pipeline % [:bot] {:content "hi"}))
          via-ask      (await-spin r #(d/ask      % :bot   {:content "hi"}))]
      (is (= (:content via-pipeline) (:content via-ask))))))

(deftest fan-out-singleton-wraps-ask
  (let [r (d/room :t)]
    (binding [ec/*execution-context* (:ctx r)]
      (d/join r (d/echo :bot)))
    (let [via-fanout (await-spin r #(d/fan-out % [:bot] {:content "hi"}))
          via-ask    (await-spin r #(d/ask     % :bot   {:content "hi"}))]
      (is (= 1 (count via-fanout)))
      (is (= (:content (first via-fanout)) (:content via-ask))))))
