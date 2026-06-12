(ns dvergr.rooms.messages-test
  "Tests for the room-level reactive message signal: seed from store, live bus
   fold, id-dedup, and signal↔store consistency."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.rooms.messages :as rmsg]
            [dvergr.discourse :as d]
            [dvergr.room.store.memory :as mem]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.engine.impl.simple :as engine]))

(defn- contents [signal ctx]
  (binding [ec/*execution-context* ctx]
    (mapv :content @signal)))

(deftest seeds-from-store-and-caches
  (let [c (ctx/create-execution-context)]
    (binding [ec/*execution-context* c]
      (let [room (d/make-room {:id :rm-seed :ctx c :store (mem/make)})]
        (d/post! room (d/message :alice :var "earlier" nil {:role :user}))
        (engine/await-drain-complete! c :timeout-ms 150)
        (let [s1 (rmsg/messages-signal room)
              s2 (rmsg/messages-signal room)]
          (try
            (is (identical? s1 s2) "one signal per room (cached)")
            (is (some #(= "earlier" %) (contents s1 c)) "seeded from store")
            (finally (rmsg/drop-room! :rm-seed))))))))

(deftest folds-live-bus-messages-including-activity
  (let [c (ctx/create-execution-context)]
    (binding [ec/*execution-context* c]
      (let [room (d/make-room {:id :rm-fold :ctx c :store (mem/make)})
            sig  (rmsg/messages-signal room)]
        (try
          (engine/await-drain-complete! c :timeout-ms 50)
          (d/post! room (d/message :alice :var "user msg" nil {:role :user}))
          (d/post! room (d/message :var :alice "agent reply"))          ; included (room view = everyone)
          (d/post! room (d/message :var :_activity "🔧 grep" nil {:role :tool})) ; activity included
          (engine/await-drain-complete! c :timeout-ms 350)
          (let [cs (set (contents sig c))]
            (is (contains? cs "user msg"))
            (is (contains? cs "agent reply") "room signal includes agent replies (unlike per-agent ctx)")
            (is (contains? cs "🔧 grep")     "room signal includes tool-activity rows"))
          (finally (rmsg/drop-room! :rm-fold)))))))

(deftest dedups-by-id-and-matches-store
  (testing "the room signal and the store are two folds of the same bus —
            consistent, deduped by id"
    (let [c (ctx/create-execution-context)]
      (binding [ec/*execution-context* c]
        (let [room (d/make-room {:id :rm-cons :ctx c :store (mem/make)})
              sig  (rmsg/messages-signal room)]
          (try
            (engine/await-drain-complete! c :timeout-ms 50)
            (doseq [[from txt] [[:alice "a"] [:bob "b"] [:alice "c"]]]
              (d/post! room (d/message from :var txt nil {:role :user})))
            (engine/await-drain-complete! c :timeout-ms 350)
            (let [signal-ids (binding [ec/*execution-context* c] (mapv :id @sig))
                  store-ids  (mapv :id (d/messages room {:limit 50}))]
              (is (= (count signal-ids) (count (set signal-ids))) "no duplicate ids in the signal")
              (is (= (set (contents sig c)) (set (map :content (d/messages room {:limit 50}))))
                  "signal content == store content"))
            (finally (rmsg/drop-room! :rm-cons))))))))
