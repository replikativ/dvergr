(ns dvergr.rooms.facts-test
  "A room's change feed: every durable change bumps it, views re-read the
   store on it, and watchers hear of changes without being flooded."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [dvergr.chat.schema :as chat-schema]
            [dvergr.room.store :as store]
            [dvergr.room.store.datahike :as datahike-store]
            [dvergr.agent.run :as run]
            [dvergr.discourse :as d]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store.memory :as memory]
            [dvergr.rooms.facts :as facts]
            [dvergr.runtime.ctx :as rctx]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.incremental.interval :as iv]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.effects.track :refer [track]]
            [org.replikativ.spindel.effects.await :refer [await]]))

(defn- wait-until [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [] (cond (pred) true
                   (< (System/currentTimeMillis) deadline) (do (Thread/sleep 10) (recur))
                   :else false))))

(defmacro ^:private with-room [[sym id] & body]
  `(let [~sym (d/make-room {:id ~id :store (memory/make)})]
     (try
       (binding [ec/*execution-context* (:ctx ~sym)]
         (rreg/register! ~sym)
         ~@body)
       (finally
         (try (binding [ec/*execution-context* (:ctx ~sym)] (rreg/unregister! (:id ~sym)))
              (catch Throwable _# nil))
         (d/close-room! ~sym)))))

(defn- version [room]
  (binding [ec/*execution-context* (rctx/root-ctx (:ctx room))]
    (:version @(facts/facts-signal room))))

(deftest every-durable-change-bumps-the-feed
  (with-room [room :facts-bump]
    (let [v0 (version room)
          r (run/start! room :worker (random-uuid) nil)]
      (is (< v0 (version room)) "a stored Run is a change")
      (let [v1 (version room)]
        (run/finish! (:run/id r) :completed)
        (is (< v1 (version room)) "so is its finish")))))

(deftest a-view-re-reads-the-facts-on-every-change
  (with-room [room :facts-view]
    (let [seen (atom [])
          count-runs #(count (run/runs %))
          v (facts/view room count-runs)]
      (binding [ec/*execution-context* (rctx/root-ctx (:ctx room))]
        (sp/spawn! (spin (swap! seen conj (await v)))))
      (is (wait-until #(= [0] @seen) 2000) (pr-str @seen))
      (run/finish! (:run/id (run/start! room :worker (random-uuid) nil)) :completed)
      (is (wait-until #(= 1 (last @seen)) 2000)
          (str "the view saw the new Run: " (pr-str @seen))))))

(deftest a-watcher-hears-of-changes-at-a-bounded-rate
  (with-room [room :facts-watch]
    (let [calls (atom 0)]
      (facts/watch! room ::test #(swap! calls inc) :min-interval-ms 200)
      (Thread/sleep 50)
      (is (zero? @calls) "subscribing is not a change")
      (dotimes [_ 20]
        (run/finish! (:run/id (run/start! room :worker (random-uuid) nil)) :completed))
      (is (wait-until #(pos? @calls) 2000))
      (Thread/sleep 500)
      (let [n @calls]
        (is (<= 1 n 5) (str "40 changes were reported " n " times"))
        (testing "the last change is never lost, and unwatching stops it"
          (facts/unwatch! (:id room) ::test)
          (run/finish! (:run/id (run/start! room :worker (random-uuid) nil)) :completed)
          (Thread/sleep 400)
          (is (= n @calls)))))))

(deftest a-datahike-store-reports-every-transaction
  (let [cfg {:store {:backend :memory :id (random-uuid)} :keep-history? false
             :schema-flexibility :write}]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)
          st (datahike-store/make conn)
          calls (atom 0)]
      (try
        (chat-schema/ensure-full-schema! conn)
        (store/-listen! st ::test #(swap! calls inc))
        (dh/transact conn [{:tool-call/id (random-uuid) :tool-call/status :running}])
        (is (= 1 @calls))
        (store/-unlisten! st ::test)
        (dh/transact conn [{:tool-call/id (random-uuid) :tool-call/status :running}])
        (is (= 1 @calls) "unlistened")
        (finally (dh/release conn) (dh/delete-database cfg))))))
