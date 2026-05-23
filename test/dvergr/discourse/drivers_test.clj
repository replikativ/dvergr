(ns dvergr.discourse.drivers-test
  "Tests for §5.5 multi-channel drivers (with-cadence, with-sources).

   Drivers attach extra event channels alongside the inbox; on-message
   receives a Message for inbox events and {:type :tick} / {:type :source
   :name K :msg E} for driver events."
  (:require [clojure.test :refer [deftest is testing]]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.sync :as sync]
            [dvergr.discourse :as d]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- await-spin
  ([room spin-fn] (await-spin room spin-fn 3000))
  ([room spin-fn wait-ms]
   (let [p (promise)]
     (binding [ec/*execution-context* (:ctx room)]
       (sp/spawn!
         (sp/spin (deliver p (sp/await (spin-fn room))))))
     (deref p wait-ms ::timeout))))

(defn- counting-participant
  "Participant that increments `events-atom` for every envelope it sees.
   When the envelope is a Message, replies with a fixed string. For
   driver envelopes, returns nil (no reply)."
  [id events-atom]
  (d/participant
    {:id id
     :on-message
     (fn [_p env]
       (sp/spin
         (swap! events-atom conj env)
         (when (instance? dvergr.discourse.Message env)
           {:to (:from env) :content "ack"})))}))

;; ============================================================================
;; with-cadence
;; ============================================================================

(deftest tick-fires-without-inbox-traffic
  (testing "A participant with-cadence ticks even when its inbox is silent"
    (let [r      (d/room :t)
          events (atom [])]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (-> (counting-participant :a events)
                      (d/with-cadence 50))))
      (Thread/sleep 250)
      (let [tick-count (count (filter #(= :tick (:type %)) @events))]
        (is (<= 3 tick-count 8)
            (str "expected ~4 ticks in 250ms; got " tick-count))))))

(deftest tick-interleaves-with-inbox
  (testing "Inbox messages and ticks both reach on-message"
    (let [r      (d/room :t)
          events (atom [])]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (-> (counting-participant :a events)
                      (d/with-cadence 80))))
      ;; Post a message immediately and another 120ms in
      (d/post! r (d/message :driver :a "hi-1"))
      (Thread/sleep 120)
      (d/post! r (d/message :driver :a "hi-2"))
      (Thread/sleep 120)
      (let [msgs  (filter #(instance? dvergr.discourse.Message %) @events)
            ticks (filter #(= :tick (:type %)) @events)]
        (is (= 2 (count msgs)) "both inbox messages delivered")
        (is (pos? (count ticks))
            "ticks also fired during the 240ms window")))))

(deftest fork-does-not-propagate-tick
  (testing "fork-room of a ticking participant produces a non-ticking clone"
    (let [r        (d/room :t)
          parent-events (atom [])
          fork-events   (atom [])]
      ;; Build a participant whose factory recreates the same handler but
      ;; targeting a different events atom, so we can tell parent vs fork.
      (let [make (fn [id events-a]
                   (d/participant
                     {:id id
                      :on-message
                      (fn [_p env]
                        (sp/spin
                          (swap! events-a conj env)
                          (when (instance? dvergr.discourse.Message env)
                            {:to (:from env) :content "p"})))
                      :factory (fn [new-ctx]
                                 ;; fork's clone writes to a SEPARATE atom
                                 (d/participant
                                   {:id id
                                    :ctx new-ctx
                                    :on-message
                                    (fn [_p env]
                                      (sp/spin
                                        (swap! fork-events conj env)
                                        (when (instance? dvergr.discourse.Message env)
                                          {:to (:from env) :content "f"})))}))}))]
        (binding [ec/*execution-context* (:ctx r)]
          (d/join r (-> (make :s parent-events) (d/with-cadence 60))))
        (Thread/sleep 150)
        ;; Parent has been ticking
        (is (pos? (count (filter #(= :tick (:type %)) @parent-events)))
            "parent ticked")
        ;; Now fork; the fork clone should NOT tick (by design — drivers
        ;; don't propagate via factory).
        (let [fork (d/fork-room r)
              _    (Thread/sleep 150)]
          (is (zero? (count (filter #(= :tick (:type %)) @fork-events)))
              "fork did not receive ticks"))))))

;; ============================================================================
;; with-sources
;; ============================================================================

(deftest source-event-routes-to-on-message
  (testing "A mailbox-backed source delivers events as :source envelopes"
    (let [r      (d/room :t)
          events (atom [])
          src    (binding [ec/*execution-context* (:ctx r)]
                   (sync/mailbox))]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (-> (counting-participant :a events)
                      (d/with-sources [{:name :feed :source src}]))))
      ;; Push three events into the source mailbox
      (binding [ec/*execution-context* (:ctx r)]
        (sync/post! src {:source "test" :preview "alpha"})
        (sync/post! src {:source "test" :preview "beta"})
        (sync/post! src {:source "test" :preview "gamma"}))
      (Thread/sleep 200)
      (let [sources (filter #(= :source (:type %)) @events)]
        (is (= 3 (count sources)) "all three source events delivered")
        (is (every? #(= :feed (:name %)) sources)
            "all events carry source name")
        (is (= ["alpha" "beta" "gamma"]
               (map #(get-in % [:msg :preview]) sources)))))))

(deftest sources-and-inbox-coexist
  (testing "Mixed inbox + source traffic — both reach on-message in order"
    (let [r      (d/room :t)
          events (atom [])
          src    (binding [ec/*execution-context* (:ctx r)]
                   (sync/mailbox))]
      (binding [ec/*execution-context* (:ctx r)]
        (d/join r (-> (counting-participant :a events)
                      (d/with-sources [{:name :feed :source src}]))))
      (d/post! r (d/message :driver :a "inbox-msg"))
      (binding [ec/*execution-context* (:ctx r)]
        (sync/post! src {:from-feed "x"}))
      (Thread/sleep 200)
      (is (= 2 (count @events))
          (str "expected 1 inbox + 1 source event; got " (count @events)))
      (is (some #(instance? dvergr.discourse.Message %) @events))
      (is (some #(= :source (:type %)) @events)))))
