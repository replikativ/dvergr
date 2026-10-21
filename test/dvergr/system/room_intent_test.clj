(ns dvergr.system.room-intent-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as db]
            [dvergr.system.room-intent :as intent]))

(defn- with-store [f]
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :keep-history? false}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (try
        (d/transact conn (into db/schema intent/schema))
        (with-redefs [db/get-conn (constantly conn) paths/systems-dir (constantly "/virtual/owned-systems")]
          (f conn))
        (finally (d/release conn) (d/delete-database cfg))))))

(deftest intent-atomically-records-the-complete-owned-default-without-readiness
  (with-store
    (fn [conn]
      (let [owner (random-uuid)
            _ (d/transact conn [{:party/id owner :party/type :human}])
            result (intent/create! {:slug "typed-intent" :owner-id owner :repository-profile :fixture/knowledge})
            room-id (get-in result [:room :room/id])
            attached (db/systems-for-room room-id)]
        (is (= :pending (get-in result [:room :room/provisioning-state])))
        (is (= 1 (get-in result [:room :room/provisioning-version])))
        (is (= #{:repo :msgs :kb} (set (map #(get-in % [:system :system/type]) attached))))
        (is (= 3 (count attached)))
        (is (every? #(= :owner (:permission %)) attached))
        (is (= (:system/id (first (:systems result))) (:system/id (db/default-repository room-id))))
        (is (= :fixture/knowledge (:system/repository-profile (db/system-by-id (:system/id (first (:systems result)))))))
        (is (every? #(= owner (get-in (d/pull @conn '[{:system/owner [:party/id]}] [:system/id (:system/id %)])
                                      [:system/owner :party/id])) (:systems result)))
        (let [before @conn]
          (is (thrown? clojure.lang.ExceptionInfo
                       (intent/create! {:slug "typed-intent" :repository-profile :other/profile})))
          (is (= before @conn)))))))

(deftest invalid-and-unknown-owner-intents-leave-no-partial-rows
  (with-store
    (fn [conn]
      (let [before @conn]
        (doseq [options [{:slug "invalid" :repository-profile :fixture/v1 :owner-id (random-uuid)}
                         {:slug "invalid" :repository-profile false}
                         {:slug "invalid" :repository-profile :fixture/v1 :repository-profiles {}}
                         {:slug "" :repository-profile :fixture/v1}]]
          (is (thrown? clojure.lang.ExceptionInfo (intent/create! options)))
          (is (= before @conn)))))))

(deftest concurrent-creators-cannot-upsert-or-leave-losing-systems
  (with-store
    (fn [conn]
      (let [start (promise)
            attempts (mapv (fn [_] (future @start
                                           (try (intent/create! {:slug "contended" :repository-profile :fixture/v1})
                                                (catch clojure.lang.ExceptionInfo _ :conflict)))) (range 2))]
        (deliver start true)
        (let [results (mapv #(deref % 10000 :timeout) attempts)]
          (is (= 1 (count (filter map? results))))
          (is (= 1 (count (filter #{:conflict} results))))
          (is (= 1 (d/q '[:find (count ?r) . :where [?r :room/id _]] @conn)))
          (is (= 3 (d/q '[:find (count ?s) . :where [?s :system/id _]] @conn)))
          (is (= 3 (d/q '[:find (count ?g) . :where [?g :grant/id _]] @conn))))))))

(deftest readiness-is-cas-guarded-and-an-exact-acknowledgement-retry-is-idempotent
  (with-store
    (fn [conn]
      (let [created (intent/create! {:slug "ready" :repository-profile :fixture/v1})
            expected (:basis created)]
        (is (= :pending (get-in expected [:room :room/provisioning-state])))
        (is (= :ready (:room/provisioning-state (intent/mark-ready! expected))))
        (is (= :ready (:room/provisioning-state (intent/mark-ready! expected))))
        (let [other (intent/create! {:slug "changed" :repository-profile :fixture/v1})
              grant (get-in other [:basis :attachments 0 :grant/id])]
          (d/transact conn [[:db/add [:grant/id grant] :grant/permission :read]])
          (is (thrown? clojure.lang.ExceptionInfo (intent/mark-ready! (:basis other))))
          (is (= :pending (:room/provisioning-state (db/room-by-slug "changed")))))))))

(deftest readiness-refuses-amplified-grants-before-pulling-their-payloads
  (with-store
    (fn [conn]
      (let [created (intent/create! {:slug "amplified" :repository-profile :fixture/v1})
            room-id (get-in created [:room :room/id])
            system-id (:system/id (first (:systems created)))]
        (d/transact conn (mapv (fn [_] {:grant/id (random-uuid) :grant/room [:room/id room-id]
                                        :grant/system [:system/id system-id] :grant/permission :read}) (range 30)))
        (with-redefs [d/pull (fn [& _] (throw (AssertionError. "Must refuse before pulling amplified rows")))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"creation intent refused"
                                (intent/basis @conn room-id))))
        (is (thrown? clojure.lang.ExceptionInfo (intent/mark-ready! (:basis created))))
        (is (= :pending (:room/provisioning-state (db/room-by-slug "amplified"))))))))
