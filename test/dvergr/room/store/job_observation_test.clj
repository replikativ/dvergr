(ns dvergr.room.store.job-observation-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.room.store :as store]
            [dvergr.room.store.memory :as memory]
            [dvergr.room.store.datahike :as datahike]))

(defn- row [id room now]
  {:run/id id :run/kind :agent-task :run/room room :run/actor :test
   :run/trigger (random-uuid) :run/status :running
   :run/created-at now :run/started-at now :run/updated-at now})

(defn- exercise [st]
  (let [room :job-receipt-contract parent (random-uuid) now (java.util.Date. 1000)
        id #uuid "00000000-0000-0000-0000-000000000001"
        base (assoc (row id room now) :run/kind :experiment :run/parent parent
                    :run/job-offer (random-uuid) :run/job-experiment (random-uuid))
        core (assoc base :run/job-core-cleanup-ack (store/job-cleanup-ack-id id :core)
                    :run/family-return-ack (store/family-return-ack-id id))
        terminal (assoc core :run/status :completed :run/ended-at now
                        :run/job-result-state :available :run/job-scorecard (random-uuid))
        put! #(store/-store-run! st room %)]
    (store/-store-room! st room {:slug (name room)})
    (put! (row parent room now))
    (put! base)
    (is (thrown? clojure.lang.ExceptionInfo (put! (assoc base :run/job-offer (random-uuid)))))
    (is (thrown? clojure.lang.ExceptionInfo (put! (assoc base :run/job-result-state :incomplete))))
    (put! core)
    (doseq [bad [(assoc terminal :run/status :failed)
                 (assoc terminal :run/job-result-state :unavailable)
                 (assoc (dissoc terminal :run/job-scorecard) :run/status :failed :run/job-result-state :incomplete)
                 (dissoc terminal :run/job-scorecard)]]
      (is (thrown? clojure.lang.ExceptionInfo (put! bad))))
    (put! terminal)
    (is (= terminal (store/-load-run st room id)))
    (is (= terminal (put! terminal)) "same receipt retry is idempotent")
    (is (thrown? clojure.lang.ExceptionInfo (put! (assoc terminal :run/job-scorecard (random-uuid)))))
    (is (thrown? clojure.lang.ExceptionInfo (put! (dissoc terminal :run/job-scorecard :run/job-result-state))))
    (let [second-id #uuid "ffffffff-ffff-ffff-ffff-fffffffffff2" grandchild (random-uuid)]
      (put! (assoc (row second-id room now) :run/parent parent))
      (put! (assoc (row grandchild room (java.util.Date. 1002)) :run/parent second-id))
      (is (= [second-id] (mapv :run/id (store/-list-runs st room {:parent-run-id parent :limit 1}))))
      (is (= [id] (mapv :run/id (store/-list-runs st room {:parent-run-id parent :offset 1 :limit 1}))))
      (is (empty? (store/-list-runs st room {:parent-run-id parent :offset 2 :limit 1})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (store/-list-runs st room {:parent-run-id parent :root-run-id parent}))))
    {:room room :terminal terminal}))

(deftest typed-job-receipt-and-direct-child-pagination-in-memory
  (exercise (memory/make)))

(deftest typed-job-receipt-survives-file-store-reopen
  (let [base (.toPath (doto (java.io.File. "target/test-tmp") .mkdirs))
        path (java.nio.file.Files/createTempDirectory base "job-receipt-" (make-array java.nio.file.attribute.FileAttribute 0))
        cfg {:store {:backend :file :path (str (.resolve path "store")) :id (random-uuid)}
             :keep-history? false :schema-flexibility :write :value-caps :default}]
    (try
      (d/create-database cfg)
      (let [conn (d/connect cfg)
            {:keys [room terminal]} (try (exercise (datahike/make conn)) (finally (d/release conn)))
            reopened (d/connect cfg)]
        (try
          (is (= terminal (store/-load-run (datahike/make reopened) room (:run/id terminal))))
          (finally (d/release reopened))))
      (finally (d/delete-database cfg) (java.nio.file.Files/deleteIfExists path)))))
