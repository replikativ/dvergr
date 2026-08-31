(ns dvergr.agent.room-context-test
  "Tests for the per-[room,agent] working ChatContext (design D): caching,
   the bus fold (append others, skip self), id-dedup, and the consistency
   contract (the in-memory signal matches the durable room store)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [dvergr.agent.room-context :as rc]
            [dvergr.discourse :as d]
            [dvergr.room.store.memory :as mem]
            [dvergr.room.store.datahike :as store-dh]
            [dvergr.chat.context :as cctx]
            [dvergr.chat.schema :as schema]
            [datahike.api :as dh]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]))

(defn- ledger-count [conn]
  (or (dh/q '[:find (count ?e) . :where [?e :ledger/id _]] @conn) 0))

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

(deftest forked-working-context-owns-child-persistence
  (let [parent-ctx (ctx/create-execution-context)
        child-ctx* (atom nil)
        parent-conn (schema/create-chat-db!
                     {:store {:backend :memory :id (random-uuid)}})
        child-conn (schema/create-chat-db!
                    {:store {:backend :memory :id (random-uuid)}})
        parent (d/make-room {:id :rc-db-parent
                             :ctx parent-ctx
                             :store (store-dh/make parent-conn)})]
    (try
      (let [working (rc/ensure-ctx! parent :var {:budget-dollars 1.0})]
        ;; Fork only after the parent component exists: this is the ordering
        ;; protected by fork-room's room-meta lifecycle fence.
        (let [child-ctx (ctx/fork-context parent-ctx :mode :frozen)
              _ (reset! child-ctx* child-ctx)
              child (d/make-room {:id :rc-db-child
                                  :ctx child-ctx
                                  :store (store-dh/make child-conn)})]
        ;; A real Yggdrasil branch already contains this inherited chat row.
        ;; The independent test connection reproduces that basis explicitly.
          (dh/transact child-conn
                       [(schema/create-chat-entity
                         {:id (:chat-id working) :title (:title working)
                          :budget 1000000})])
          (let [projected (rc/fork-ctx! parent child :var)]
            (is (identical? child-conn (:db-conn projected)))
            (is (not (identical? parent-conn (:db-conn projected))))
            (cctx/account-usage! projected :input-tokens 1
                                 :model "claude-sonnet-4-5")
            (is (= 0 (ledger-count parent-conn)))
            (is (= 1 (ledger-count child-conn))))))
      (finally
        (rc/drop-ctx! :rc-db-child :var)
        (rc/drop-ctx! :rc-db-parent :var)
        (dh/release child-conn)
        (dh/release parent-conn)
        (when-let [child-ctx @child-ctx*]
          (ctx/close-context! child-ctx))
        (ctx/close-context! parent-ctx)))))

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
