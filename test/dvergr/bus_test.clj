(ns dvergr.bus-test
  "Tests for dvergr.bus — the pub/sub substrate underneath discourse.
   No LLM, no participants — exercises just the routing topology."
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.partial-cps.sequence :as aseq]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.effects.await :refer [await]]
            [org.replikativ.spindel.pubsub.buffer :as buf]
            [dvergr.bus :as bus]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn drain-into!
  "Spawn a spin that drains `(:aseq sub)` into `acc` (atom of vector),
   extracting items with `pick`. Returns nil; control returns to caller."
  [bus sub acc pick]
  (binding [ec/*execution-context* (:ctx bus)]
    (sp/spawn!
      (spin
        (loop [s (:aseq sub)]
          (when-let [r (await (aseq/anext s))]
            (let [[m rest-s] r]
              (swap! acc conj (pick m))
              (recur rest-s))))))))

(defn wait-until
  "Busy-wait until `pred` is true or timeout-ms elapses. Returns true if
   pred became true, false on timeout. Coarse-grained — for tests only."
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred)                                       true
        (> (System/currentTimeMillis) deadline)      false
        :else (do (Thread/sleep 10) (recur))))))

;; ============================================================================
;; Routing
;; ============================================================================

(deftest test-routing-by-to
  (testing "direct :to routing delivers only to addressee"
    (let [b      (bus/create-bus)
          alice  (atom [])
          bob    (atom [])
          s-a    (bus/subscribe! b [:to :alice])
          s-b    (bus/subscribe! b [:to :bob])]
      (drain-into! b s-a alice :n)
      (drain-into! b s-b bob   :n)
      (bus/post! b {:to :alice :type :user/message :n 1})
      (bus/post! b {:to :bob   :type :user/message :n 2})
      (bus/post! b {:to :alice :type :user/message :n 3})
      (is (wait-until #(= 2 (count @alice)) 500))
      (is (wait-until #(= 1 (count @bob))   500))
      (is (= [1 3] @alice))
      (is (= [2]   @bob)))))

(deftest test-routing-by-type
  (testing ":type routing delivers to subscribers regardless of recipient"
    (let [b           (bus/create-bus)
          directives  (atom [])
          escalations (atom [])
          s-d (bus/subscribe! b [:type :directive/wrap-up])
          s-e (bus/subscribe! b [:type :escalation/budget])]
      (drain-into! b s-d directives :to)
      (drain-into! b s-e escalations :to)
      (bus/post! b {:to :alice  :type :directive/wrap-up})
      (bus/post! b {:to :bob    :type :escalation/budget})
      (bus/post! b {:to :carol  :type :directive/wrap-up})
      (bus/post! b {:to :dave   :type :user/message})
      (is (wait-until #(= 2 (count @directives)) 500))
      (is (wait-until #(= 1 (count @escalations)) 500))
      (is (= [:alice :carol] @directives))
      (is (= [:bob]          @escalations)))))

(deftest test-both-dimensions
  (testing "a single message reaches both a :to and a :type subscriber"
    (let [b   (bus/create-bus)
          to-recv (atom [])
          ty-recv (atom [])
          s-to (bus/subscribe! b [:to :alice])
          s-ty (bus/subscribe! b [:type :directive/raise-budget])]
      (drain-into! b s-to to-recv :tag)
      (drain-into! b s-ty ty-recv :tag)
      (bus/post! b {:to :alice
                    :type :directive/raise-budget
                    :tag :A})
      (is (wait-until #(and (= 1 (count @to-recv))
                            (= 1 (count @ty-recv))) 500))
      (is (= [:A] @to-recv))
      (is (= [:A] @ty-recv)))))

;; ============================================================================
;; Multiple subscribers to the same topic
;; ============================================================================

(deftest test-broadcast-within-topic
  (testing "multiple subscribers to the same topic each receive every message"
    (let [b   (bus/create-bus)
          a   (atom [])
          c   (atom [])
          s-a (bus/subscribe! b [:type :user/message])
          s-c (bus/subscribe! b [:type :user/message])]
      (drain-into! b s-a a :n)
      (drain-into! b s-c c :n)
      (bus/post! b {:type :user/message :to :a :n 1})
      (bus/post! b {:type :user/message :to :b :n 2})
      (bus/post! b {:type :user/message :to :c :n 3})
      (is (wait-until #(= 3 (count @a)) 500))
      (is (wait-until #(= 3 (count @c)) 500))
      (is (= [1 2 3] @a))
      (is (= [1 2 3] @c)))))

;; ============================================================================
;; Buffer policy
;; ============================================================================

(deftest test-default-buffers
  (testing "subscribe! resolves the right buffer policy per namespace"
    (let [b (bus/create-bus)
          sub-name #(-> (bus/subscribe! b %) :buffer class .getSimpleName)]
      (is (= "FixedBuffer"   (sub-name [:to :alice])))
      (is (= "FixedBuffer"   (sub-name [:type :user/message])))
      (is (= "FixedBuffer"   (sub-name [:type :directive/wrap-up])))
      (is (= "FixedBuffer"   (sub-name [:type :escalation/budget])))
      (is (= "SlidingBuffer" (sub-name [:type :partial/token])))
      (is (= "SlidingBuffer" (sub-name [:type :tick])))
      (is (= "SlidingBuffer" (sub-name [:type :source/sensor]))))))

(deftest test-buffer-override
  (testing "3-arg subscribe! lets caller override the policy"
    (let [b (bus/create-bus)
          s (bus/subscribe! b [:type :user/message] (buf/sliding-buffer 1))]
      (is (= "SlidingBuffer" (-> s :buffer class .getSimpleName))))))

;; ============================================================================
;; FIFO order through the bus
;; ============================================================================

(deftest test-fifo-through-bus
  (testing "burst-posted messages arrive in :n order through the bus"
    (let [b   (bus/create-bus)
          got (atom [])
          n   20
          sub (bus/subscribe! b [:to :alice])]
      (drain-into! b sub got :n)
      (dotimes [i n]
        (bus/post! b {:to :alice :type :user/message :n i}))
      (is (wait-until #(= n (count @got)) 1000))
      (is (= (vec (range n)) @got)))))

;; ============================================================================
;; Unsubscribe
;; ============================================================================

(deftest test-unsubscribe
  (testing "after unsubscribe, no new messages arrive on that subscription"
    (let [b   (bus/create-bus)
          got (atom [])
          sub (bus/subscribe! b [:to :alice])]
      (drain-into! b sub got :n)
      (bus/post! b {:to :alice :type :user/message :n 1})
      (bus/post! b {:to :alice :type :user/message :n 2})
      (is (wait-until #(= 2 (count @got)) 500))
      (bus/unsubscribe! sub)
      (bus/post! b {:to :alice :type :user/message :n 3})
      (bus/post! b {:to :alice :type :user/message :n 4})
      ;; Give time for any errant deliveries.
      (Thread/sleep 200)
      (is (= [1 2] @got)))))

;; ============================================================================
;; Log
;; ============================================================================

(deftest test-log-captures-every-message
  (testing "every posted message lands in (bus/log bus)"
    (let [b (bus/create-bus)]
      (bus/post! b {:to :a :type :user/message :n 1})
      (bus/post! b {:to :b :type :directive/wrap-up})
      (bus/post! b {:to :c :type :user/message :n 3})
      (is (wait-until #(= 3 (count (bus/log b))) 500))
      (is (= [1 nil 3] (mapv :n (bus/log b)))))))
