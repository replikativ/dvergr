(ns dvergr.agent.room-context-test
  "Tests for the per-[room,agent] working ChatContext (design D): caching,
   explicit attention admission, id-dedup, and durable reconstruction."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [dvergr.agent.room-context :as rc]
            [dvergr.agent.run :as run]
            [dvergr.discourse :as d]
            [dvergr.room.store.memory :as mem]
            [dvergr.room.store :as rstore]
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

(deftest room-observation-does-not-bypass-attention
  (let [c (ctx/create-execution-context)]
    (binding [ec/*execution-context* c]
      (let [room (d/make-room {:id :rc-no-eager-fold :ctx c :store (mem/make)})
            cc   (rc/ensure-ctx! room :var {:budget-dollars 1.0})]
        (try
          (d/post! room (d/message :alice :var "hi var" nil {:role :user}))
          (let [contents (non-system-contents cc)
                has? (fn [s] (some #(str/includes? % s) contents))]
            (is (not (has? "hi var"))
                "durable Room visibility alone does not admit provider input")
            (is (true? (rc/append-inbound! :rc-no-eager-fold :var
                                           (random-uuid) :user "admitted"
                                           "Alice" nil)))
            (is (some #(str/includes? % "admitted") (non-system-contents cc))))
          (finally (rc/drop-ctx! :rc-no-eager-fold :var)))))))

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

(deftest durable-attention-rebuilds-provider-projection
  (testing "Run triggers and :include decisions enter provider input while
            durable :remember facts remain outside it"
    (let [c (ctx/create-execution-context)]
      (binding [ec/*execution-context* c]
        (let [room (d/make-room {:id :rc-cons :ctx c :store (mem/make)})
              trigger (d/message :alice :var "trigger" nil {:role :user})
              remembered (d/message :bob :var "remember only" nil {:role :user})]
          (try
            (d/post! room trigger)
            (d/post! room remembered)
            (let [cc (rc/ensure-ctx! room :var {:budget-dollars 1.0})
                  run-ref (run/start! room :var trigger cc)]
              (run/finish! (:run/id run-ref) :completed)
              (rstore/-store-attention!
               (:store room) :rc-cons
               {:attention/id (random-uuid)
                :attention/participant :var
                :attention/message-id (:id remembered)
                :attention/memory :remember
                :attention/activation :none
                :attention/control :continue
                :attention/at :next-safe-boundary
                :attention/priority 0
                :attention/status :ready
                :attention/created-at (java.util.Date.)})
              (rc/drop-ctx! :rc-cons :var))
            (let [restored (rc/ensure-ctx! room :var {:budget-dollars 1.0})
                  contents (non-system-contents restored)]
              (is (some #(str/includes? % "trigger") contents))
              (is (not-any? #(str/includes? % "remember only") contents)))
            (finally (rc/drop-ctx! :rc-cons :var))))))))
