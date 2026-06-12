(ns dvergr.rooms.scheduler-test
  "Per-room reactive scheduler: the cron entity↔spec adapters, a deterministic
   fire-due! check, and the full reactive path (clock tick → scheduler spin →
   task posted into the room)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [dvergr.chat.schema :as cschema]
            [dvergr.scheduler.schema :as sched-schema]
            [dvergr.scheduler.cron :as cron]
            [dvergr.rooms.scheduler :as rsched]
            [dvergr.rooms.messages :as rmsg]
            [dvergr.runtime.clock :as clock]
            [dvergr.discourse :as d]
            [dvergr.room.store.datahike :as store-dh]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.engine.impl.simple :as engine]))

;; =============================================================================
;; cron entity ↔ spec adapters (pure)
;; =============================================================================

(deftest spec->attrs-and-back
  (let [from (java.util.Date. 0)]
    (testing ":interval"
      (let [a (cron/spec->attrs {:interval-ms 300000} from)]
        (is (= :interval (:schedule/kind a)))
        (is (= 300000 (:schedule/interval-ms a)))
        (is (= (java.util.Date. 300000) (:schedule/next-fire a)) "next-fire = from + interval")))
    (testing ":recurring decomposes time-of-day / weekday"
      (let [a (cron/spec->attrs {:every :week :on :monday :at "09:30"} from)]
        (is (= :recurring (:schedule/kind a)))
        (is (= :week (:schedule/every a)))
        (is (= 570 (:schedule/time-of-day a)) "9*60+30")
        (is (= :monday (:schedule/weekday a)))
        (is (= {:every :week :on :monday :at "09:30"} (cron/entity->spec a)) "roundtrips")))))

(deftest compute-next-fire-advances-and-retires
  (let [t0 (java.util.Date. 0)]
    (testing ":interval advances from `from`"
      (let [e {:schedule/kind :interval :schedule/interval-ms 60000}]
        (is (= (java.util.Date. 60000) (cron/compute-next-fire e t0)))))
    (testing ":once retires after it has fired (last-run set ⇒ nil)"
      (let [e {:schedule/kind :once :schedule/next-fire t0 :schedule/last-run t0}]
        (is (nil? (cron/compute-next-fire e t0)))))))

;; =============================================================================
;; firing (datahike-backed room store)
;; =============================================================================

(defn- datahike-room
  "A room backed by a fresh in-memory datahike store carrying the chat +
   schedule schema and a seeded room row. Returns [room conn cfg]."
  [ctx slug]
  (let [cfg  {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write}
        _    (dh/create-database cfg)
        conn (dh/connect cfg)]
    (dh/transact conn cschema/full-schema)
    (dh/transact conn sched-schema/schema)
    (dh/transact conn [(merge (cschema/create-chat-entity
                               {:id (random-uuid) :title slug})
                              {:room/slug slug :room/type :internal})])
    [(d/make-room {:id (keyword slug) :slug slug :ctx ctx :store (store-dh/make conn)})
     conn cfg]))

(defn- add-schedule! [conn m]
  (dh/transact conn [(merge {:schedule/id (random-uuid)
                             :schedule/active? true}
                            m)]))

(defn- the-schedule [conn]
  (dh/q '[:find (pull ?s [*]) . :where [?s :schedule/id _]] @conn))

(deftest fire-due!-posts-task-and-advances-row
  (let [c (ctx/create-execution-context)]
    (binding [ec/*execution-context* c]
      (let [[room conn cfg] (datahike-room c "sched-fire")]
        (try
          (add-schedule! conn {:schedule/agent-id :var :schedule/task "PING"
                               :schedule/kind :interval :schedule/interval-ms 600000
                               :schedule/next-fire (java.util.Date. 0)})  ; already due
          (engine/await-drain-complete! c :timeout-ms 100)
          (rsched/fire-due! room (java.util.Date.))
          (engine/await-drain-complete! c :timeout-ms 300)
          (let [msgs (mapv :content (d/messages room {:limit 50}))
                row  (the-schedule conn)]
            (is (some #{"PING"} msgs) "scheduled task posted into the room")
            (is (some? (:schedule/last-run row)) "last-run stamped")
            (is (> (.getTime ^java.util.Date (:schedule/next-fire row)) 0)
                "next-fire advanced off the epoch"))
          (finally
            (rsched/stop-room-scheduler! (:id room))
            (rmsg/drop-room! (:id room))
            (dh/release conn) (dh/delete-database cfg)))))))

(deftest not-yet-due-does-not-fire
  (let [c (ctx/create-execution-context)]
    (binding [ec/*execution-context* c]
      (let [[room conn cfg] (datahike-room c "sched-future")]
        (try
          (add-schedule! conn {:schedule/agent-id :var :schedule/task "LATER"
                               :schedule/kind :interval :schedule/interval-ms 600000
                               :schedule/next-fire (java.util.Date. (+ (System/currentTimeMillis) 3600000))})
          (engine/await-drain-complete! c :timeout-ms 100)
          (rsched/fire-due! room (java.util.Date.))
          (engine/await-drain-complete! c :timeout-ms 200)
          (is (not (some #{"LATER"} (mapv :content (d/messages room {:limit 50}))))
              "a not-yet-due schedule does not fire")
          (finally
            (rsched/stop-room-scheduler! (:id room))
            (rmsg/drop-room! (:id room))
            (dh/release conn) (dh/delete-database cfg)))))))

(deftest clock-tick-fires-due-schedule
  (testing "the reactive path: make-room auto-starts the scheduler spin; a clock
            advance re-runs it and fires the due schedule"
    (let [c (ctx/create-execution-context)]
      ;; fresh clock per test (defonce singleton would otherwise pin a prior ctx)
      (reset! @#'clock/clock-state {:signal nil :running? false})
      (binding [ec/*execution-context* c]
        (let [[room conn cfg] (datahike-room c "sched-tick")]
          (try
            (add-schedule! conn {:schedule/agent-id :var :schedule/task "TICKED"
                                 :schedule/kind :interval :schedule/interval-ms 600000
                                 :schedule/next-fire (java.util.Date. 0)})
            (engine/await-drain-complete! c :timeout-ms 150)
            ;; advance the clock an hour ⇒ signal changes ⇒ scheduler spin re-runs
            (clock/set-now! (java.util.Date. (+ (System/currentTimeMillis) 3600000)))
            (engine/await-drain-complete! c :timeout-ms 500)
            (is (some #{"TICKED"} (mapv :content (d/messages room {:limit 50})))
                "clock tick fired the due schedule into the room")
            (finally
              (rsched/stop-room-scheduler! (:id room))
              (rmsg/drop-room! (:id room))
              (dh/release conn) (dh/delete-database cfg))))))))
