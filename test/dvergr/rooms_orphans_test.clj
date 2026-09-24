(ns dvergr.rooms-orphans-test
  "A Run the daemon left running when it stopped is failed when the daemon
   next opens its room: nothing owns it any more, and it would otherwise read
   as running forever in progress, the Run tree and dashboards."
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.run :as run]
            [dvergr.ops :as ops]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.room.store :as store]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as sdb]
            [org.replikativ.spindel.engine.core :as ec]))

(deftest a-restart-fails-the-runs-the-previous-daemon-left-running
  (let [prev-home (paths/home)
        run-id (random-uuid)]
    (paths/set-home! (str (System/getProperty "java.io.tmpdir") "/dvergr-orphans-test-" (random-uuid)))
    (sdb/reset-conn!)
    (try
      (let [d1 (daemon/start! {:agents {}})]
        (try
          (ops/invoke d1 :room/create {:title "orphans" :slug "orphans"})
          (let [room (ops/resolve-room d1 "orphans")
                at (java.util.Date.)]
            (binding [ec/*execution-context* (:ctx room)]
              (is (store/-store-run! (:store room) (store/conversation-id room)
                                     {:run/id run-id :run/kind :agent-turn :run/room (:id room)
                                      :run/actor :worker :run/trigger (random-uuid)
                                      :run/status :running :run/created-at at
                                      :run/started-at at :run/updated-at at}))))
          (finally (daemon/stop! d1))))
      (sdb/reset-conn!)
      (let [d2 (daemon/start! {:agents {}})]
        (try
          (let [room (ops/resolve-room d2 "orphans")
                stored (binding [ec/*execution-context* (:ctx room)]
                         (run/run room run-id))]
            (is (= :failed (:run/status stored)))
            (is (= run/orphaned-reason (:run/reason stored))))
          (finally (daemon/stop! d2))))
      (finally
        (sdb/reset-conn!)
        (paths/set-home! prev-home)))))
