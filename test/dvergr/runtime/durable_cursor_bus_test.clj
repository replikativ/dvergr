(ns dvergr.runtime.durable-cursor-bus-test
  "Regression tests for the durable-cursor bus (log-first fan-out):
   durability-before-visibility, cursor-resume-on-restart (at-least-once,
   deduped by :id at participants), history absorption never delivered,
   and post-order preservation."
  (:require [clojure.test :refer [deftest is testing]]
            [is.simm.partial-cps.sequence :as aseq]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.spin.sync :as sync]
            [org.replikativ.spindel.effects.await :refer [await]]
            [dvergr.runtime.bus :as bus]
            [dvergr.runtime.bus-test :refer [drain-into! wait-until]]))

(deftest durability-before-visibility
  (testing "the durable-append! hook runs synchronously inside post!,
          BEFORE the message is on the log or delivered anywhere; a
          throwing hook fails the post and nothing is half-delivered"
    (let [appended (atom [])
          b (bus/create-bus {:durable-append! (fn [m] (swap! appended conj (:id m)))})
          got (atom [])
          sub (bus/subscribe! b [:to :x])]
      (drain-into! b sub got :content)
      (bus/post! b {:to :x :content "one"})
      ;; Synchronous: by the time post! returns, durability happened —
      ;; regardless of whether any delivery has run yet.
      (is (= 1 (count @appended)))
      (is (wait-until #(= ["one"] @got) 3000))
      ;; Failure inversion: a store failure fails the post loudly and the
      ;; message never becomes visible.
      (let [b2 (bus/create-bus {:durable-append!
                                (fn [_] (throw (ex-info "store down" {})))})
            got2 (atom [])
            sub2 (bus/subscribe! b2 [:to :x])]
        (drain-into! b2 sub2 got2 :content)
        (is (thrown-with-msg? Exception #"store down"
                              (bus/post! b2 {:to :x :content "lost?"})))
        (is (empty? (bus/log b2)) "nothing on the log")
        (Thread/sleep 150)
        (is (empty? @got2) "nothing delivered")))))

(deftest durable-duplicate-is-not-visible-twice
  (testing "first-write-wins covers the live log and subscribers"
    (let [seen (atom #{})
          append! (fn [message]
                    (locking seen
                      (if (contains? @seen (:id message))
                        :duplicate
                        (do (swap! seen conj (:id message)) :inserted))))
          b (bus/create-bus {:durable-append! append!})
          got (atom [])
          sub (bus/subscribe! b [:to :x])
          id (random-uuid)
          message {:id id :to :x :content "canonical"}]
      (drain-into! b sub got :content)
      (bus/post! b message)
      (bus/post! b message)
      (is (wait-until #(= ["canonical"] @got) 3000))
      (Thread/sleep 100)
      (is (= ["canonical"] @got)
          "projectors and external relays see only the winning post")
      (is (= [message] (bus/log b))))))

(deftest cursor-resume-redelivers-in-flight-at-least-once
  (testing "rewinding the cursor by one (simulating a pump crash after
          handoff but before advance) re-delivers exactly that entry —
          the at-least-once window; participants dedup by :id"
    (let [b (bus/create-bus)
          got (atom [])
          sub (bus/subscribe! b [:to :x])]
      (drain-into! b sub got :content)
      (bus/post! b {:to :x :content "a"})
      (bus/post! b {:to :x :content "b"})
      (is (wait-until #(= ["a" "b"] @got) 3000))
      (is (= 2 (bus/log-cursor b)))
      ;; Simulate the crash window: cursor points before the last
      ;; delivered entry; the (supervised, restarted) pump loop re-reads
      ;; from the cursor. We reuse the live pump by ringing the doorbell.
      (swap! (:log b) update :cursor dec)
      (binding [ec/*execution-context* (:ctx b)]
        (sync/post! (:hint-mbx b) :hint))
      (is (wait-until #(= ["a" "b" "b"] @got) 3000)
          "the in-flight entry is re-delivered (at-least-once), never lost")
      (is (= 2 (bus/log-cursor b)) "cursor re-advanced"))))

(deftest history-absorption-never-delivered
  (testing "seed-log! and append-log! put entries on the record without
          firing live handlers; live posts still deliver"
    (let [b (bus/create-bus)
          got (atom [])
          sub (bus/subscribe! b [:to :x])]
      (drain-into! b sub got :content)
      (bus/seed-log! b [{:to :x :content "seeded-1"}
                        {:to :x :content "seeded-2"}])
      (bus/append-log! b [{:to :x :content "merged-1"}])
      (bus/post! b {:to :x :content "live"})
      (is (wait-until #(= ["live"] @got) 3000)
          "only the live post reached the subscriber")
      (Thread/sleep 150)
      (is (= ["live"] @got) "…and nothing else trickled in")
      (is (= ["seeded-1" "seeded-2" "merged-1" "live"]
             (mapv :content (bus/log b)))
          "the log shows the full record, history included")
      (is (= 4 (bus/log-cursor b)) "cursor advanced past history"))))

(deftest post-order-preserved
  (testing "log order == post order == delivery order (the log is the
          serialization point, upstream of delivery)"
    (let [b (bus/create-bus)
          got (atom [])
          sub (bus/subscribe! b [:to :x])
          n 50]
      (drain-into! b sub got :content)
      (dotimes [i n]
        (bus/post! b {:to :x :content i}))
      (is (wait-until #(= n (count @got)) 5000))
      (is (= (vec (range n)) @got) "delivered in post order")
      (is (= (vec (range n)) (mapv :content (bus/log b))) "logged in post order"))))
