(ns dvergr.agent.room-context-test
  "Tests for the per-[room,agent] working ChatContext (design D): caching,
   the bus fold (append others, skip self), id-dedup, and the consistency
   contract (the in-memory signal matches the durable room store)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [dvergr.agent.room-context :as rc]
            [dvergr.discourse :as d]
            [dvergr.room.store.memory :as mem]
            [dvergr.chat.context :as cctx]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]))

(defn- roles+contents [chat-ctx]
  (binding [ec/*execution-context* (:spindel-ctx chat-ctx)]
    (mapv (juxt :message/role :message/content) (cctx/get-messages chat-ctx))))

(defn- non-system-contents [chat-ctx]
  (->> (roles+contents chat-ctx)
       (remove #(= :system (first %)))
       (map second)))

(deftest ensure-ctx-caches-and-seeds-from-store
  (let [c (ctx/create-execution-context)]
    (binding [ec/*execution-context* c]
      (let [room (d/make-room {:id :rc-seed :ctx c :store (mem/make)})]
        (d/post! room (d/message :alice :var "earlier message" nil {:role :user}))
        (Thread/sleep 150)
        (let [cc1 (rc/ensure-ctx! room :var {:budget-dollars 1.0})
              cc2 (rc/ensure-ctx! room :var {:budget-dollars 1.0})]
          (try
            (is (identical? cc1 cc2) "second call returns the cached ctx (no rebuild)")
            (is (some #(str/includes? % "earlier message") (non-system-contents cc1))
                "seeded the conversation from the room store")
            (finally (rc/drop-ctx! :rc-seed :var))))))))

(deftest fold-appends-others-skips-self
  (let [c (ctx/create-execution-context)]
    (binding [ec/*execution-context* c]
      (let [room (d/make-room {:id :rc-fold :ctx c :store (mem/make)})
            cc   (rc/ensure-ctx! room :var {:budget-dollars 1.0})]
        (try
          (Thread/sleep 50)
          (d/post! room (d/message :alice :var "hi var" nil {:role :user}))
          (d/post! room (d/message :var :alice "var reply"))   ; self → fold skips
          (d/post! room (d/message :bob nil "hello room"))      ; other → fold appends
          (Thread/sleep 350)
          (let [contents (non-system-contents cc)
                has? (fn [s] (some #(str/includes? % s) contents))]
            (is (has? "hi var")     "user message folded in (author·time decorated)")
            (is (has? "hello room") "another agent's message folded in")
            (is (not (has? "var reply"))
                "the agent's own message is skipped by the fold (the turn loop adds it)"))
          (finally (rc/drop-ctx! :rc-fold :var)))))))

(deftest append-inbound-dedups-by-id
  (let [c (ctx/create-execution-context)]
    (binding [ec/*execution-context* c]
      (let [room (d/make-room {:id :rc-dedup :ctx c :store (mem/make)})
            cc   (rc/ensure-ctx! room :var {:budget-dollars 1.0})
            id   (random-uuid)]
        (try
          (Thread/sleep 50)
          (is (true? (rc/append-inbound! :rc-dedup :var id :user "once" nil nil)) "first append")
          (is (nil? (rc/append-inbound! :rc-dedup :var id :user "again" nil nil)) "same id → no-op")
          (is (= 1 (count (filter #(= "once" %) (non-system-contents cc))))
              "appended exactly once despite two calls with the same id")
          (finally (rc/drop-ctx! :rc-dedup :var)))))))

(deftest consistency-signal-matches-store
  (testing "the in-memory fold and the durable room store are two projections
            of the same bus log — their conversational content matches"
    (let [c (ctx/create-execution-context)]
      (binding [ec/*execution-context* c]
        (let [room (d/make-room {:id :rc-cons :ctx c :store (mem/make)})
              cc   (rc/ensure-ctx! room :var {:budget-dollars 1.0})]
          (try
            (Thread/sleep 50)
            (doseq [[from txt] [[:alice "q1"] [:bob "q2"] [:alice "q3"] [:carol "q4"]]]
              (d/post! room (d/message from :var txt nil {:role :user})))
            (Thread/sleep 400)
            (let [signal (set (non-system-contents cc))
                  store  (set (map :content (d/messages room {:limit 50})))]
              ;; The signal is the store's content DECORATED with [author · time];
              ;; every stored message must appear (as a substring) in the signal.
              (is (every? (fn [s] (some #(str/includes? % s) signal)) store)
                  "signal (fold) is the author·time-decorated projection of the store"))
            (finally (rc/drop-ctx! :rc-cons :var))))))))
