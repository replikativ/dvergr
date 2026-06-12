(ns dvergr.scheduler.core-test
  "Tests for the room-scoped schedule CRUD (RF5). Schedules live in the room's
   own datahike store; firing is covered by dvergr.rooms.scheduler-test."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as dh]
            [dvergr.chat.schema :as cschema]
            [dvergr.scheduler.schema :as sched-schema]
            [dvergr.scheduler.core :as scheduler]
            [dvergr.rooms.scheduler :as rsched]
            [dvergr.rooms.messages :as rmsg]
            [dvergr.discourse :as d]
            [dvergr.room.store.datahike :as store-dh]
            [dvergr.tools :as tools]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ectx]))

(def ^:dynamic *room* nil)
(def ^:dynamic *cleanup* nil)

(use-fixtures :each
  (fn [f]
    (let [ctx  (ectx/create-execution-context)]
      (binding [ec/*execution-context* ctx]
        (let [cfg  {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write}
              _    (dh/create-database cfg)
              conn (dh/connect cfg)]
          (dh/transact conn cschema/full-schema)
          (dh/transact conn sched-schema/schema)
          (dh/transact conn [(merge (cschema/create-chat-entity {:id (random-uuid) :title "sched-core"})
                                    {:room/slug "sched-core" :room/type :internal})])
          (let [room (d/make-room {:id :sched-core :slug "sched-core" :ctx ctx
                                   :store (store-dh/make conn)})]
            (binding [*room* room]
              (try (f)
                   (finally
                     (rsched/stop-room-scheduler! :sched-core)
                     (rmsg/drop-room! :sched-core)
                     (dh/release conn) (dh/delete-database cfg)
                     (ectx/stop-context! ctx))))))))))

(deftest create-and-list
  (testing "create returns a uuid, list shows the active schedule"
    (let [id (scheduler/create-schedule! *room*
                                         {:agent-id :test-agent :task "Do something"
                                          :interval-ms 60000 :description "Test schedule"})]
      (is (uuid? id))
      (let [schedules (scheduler/list-schedules *room*)]
        (is (= 1 (count schedules)))
        (let [s (first schedules)]
          (is (= :test-agent (:agent-id s)))
          (is (= "Do something" (:task s)))
          (is (= :interval (:kind s)))
          (is (= 60000 (:interval-ms s)))
          (is (= "Test schedule" (:description s)))
          (is (some? (:next-fire s)) "next-fire materialized on create"))))))

(deftest recurring-decomposed
  (testing "a cron schedule stores decomposed transparent attrs"
    (scheduler/create-schedule! *room*
                                {:agent-id :a :task "daily" :schedule {:every :day :at "09:00"}})
    (let [s (first (scheduler/list-schedules *room*))]
      (is (= :recurring (:kind s)))
      (is (= :day (:every s)))
      (is (= "09:00" (:at s))))))

(deftest cancel-deactivates
  (testing "cancel flips active? false → drops from the active list"
    (let [id (scheduler/create-schedule! *room*
                                         {:agent-id :a :task "x" :interval-ms 60000})]
      (is (= 1 (count (scheduler/list-schedules *room*))))
      (is (= :cancelled (scheduler/cancel-schedule! *room* id)))
      (is (= 0 (count (scheduler/list-schedules *room*)))))))

(deftest cancel-nonexistent
  (is (nil? (scheduler/cancel-schedule! *room* (random-uuid)))))

(deftest list-all-fans-out
  (testing "list-all-schedules aggregates across rooms, tagged with :room"
    (scheduler/create-schedule! *room* {:agent-id :a :task "t1" :interval-ms 60000})
    (let [all (scheduler/list-all-schedules)]
      (is (some #(= "sched-core" (:room %)) all)))))

(deftest validation
  (testing "invalid interval / non-keyword agent / missing task throw"
    (is (thrown? AssertionError
                 (scheduler/create-schedule! *room* {:agent-id :t :task "bad" :interval-ms -1})))
    (is (thrown? AssertionError
                 (scheduler/create-schedule! *room* {:agent-id "nope" :task "bad" :interval-ms 60000})))
    (is (thrown? AssertionError
                 (scheduler/create-schedule! *room* {:agent-id :t :task nil :interval-ms 60000})))))

(deftest tools-registered
  (testing "schedule_* tools are registered"
    (require 'dvergr.scheduler.tools :reload)
    (is (some? (tools/get-tool "schedule_create")))
    (is (some? (tools/get-tool "schedule_list")))
    (is (some? (tools/get-tool "schedule_cancel")))))
