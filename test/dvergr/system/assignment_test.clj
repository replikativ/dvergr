(ns dvergr.system.assignment-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [dvergr.actors :as actors]
            [dvergr.rooms :as rooms]
            [dvergr.system.db :as sdb]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]))

(def ^:dynamic *conn* nil)

(defn- memory-system-db [f]
  (let [config {:store {:backend :memory :id (random-uuid)}
                :schema-flexibility :write
                :keep-history? false}]
    (d/create-database config)
    (let [conn (d/connect config)
          execution-context (context/create-execution-context)]
      (d/transact conn (vec (concat sdb/schema sdb/actor-schema
                                    sdb/assignment-schema)))
      (binding [*conn* conn
                ec/*execution-context* execution-context]
        (with-redefs [sdb/get-conn (constantly conn)]
          (try
            (f)
            (finally
              (d/release conn)
              (d/delete-database config))))))))

(use-fixtures :each memory-system-db)

(deftest legacy-membership-is-a-compatible-assignment
  (sdb/create-room! {:slug "old-room" :name "Old room"
                     :agent-ids #{:not-yet-running}})
  (is (= [{:assignment/actor-id :not-yet-running
           :assignment/role :specialist
           :assignment/response-policy :always
           :assignment/legacy? true}]
         (mapv #(select-keys % [:assignment/actor-id :assignment/role
                                :assignment/response-policy :assignment/legacy?])
               (sdb/room-assignments "old-room")))))

(deftest assignment-roundtrip-and-partial-update
  (actors/spawn-agent! *conn* {:id :researcher :name "Researcher"})
  (sdb/create-room! {:slug "product" :name "Product"})
  (let [created (sdb/assign-room-actor!
                 "product" :researcher
                 {:role :lead
                  :response-policy :mention
                  :config {:budget-dollars 4 :focus :customers}})
        updated (sdb/assign-room-actor!
                 "product" :researcher {:response-policy :manual})]
    (testing "the canonical relation carries role, policy, actor and config"
      (is (uuid? (:assignment/id created)))
      (is (= :researcher (:assignment/actor-id created)))
      (is (= "Researcher" (:assignment/actor-name created)))
      (is (= :lead (:assignment/role created)))
      (is (= :mention (:assignment/response-policy created)))
      (is (= {:budget-dollars 4 :focus :customers}
             (:assignment/config created))))
    (testing "an omitted role/config survives a policy-only update"
      (is (= (:assignment/id created) (:assignment/id updated)))
      (is (= (:assignment/created-at created) (:assignment/created-at updated)))
      (is (= :lead (:assignment/role updated)))
      (is (= :manual (:assignment/response-policy updated)))
      (is (= (:assignment/config created) (:assignment/config updated))))
    (testing "legacy membership is maintained but not duplicated on reads"
      (is (= #{:researcher}
             (set (:room/agent-ids (sdb/room-by-slug "product")))))
      (is (= [updated] (sdb/room-assignments "product"))))))

(deftest assignment-input-is-validated
  (actors/spawn-agent! *conn* {:id :reviewer})
  (sdb/create-room! {:slug "review" :name "Review"})
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown room"
                        (sdb/assign-room-actor! "missing" :reviewer {})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown actor"
                        (sdb/assign-room-actor! "review" :missing {})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"assignment role"
                        (sdb/assign-room-actor! "review" :reviewer
                                                {:role :wizard})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"response policy"
                        (sdb/assign-room-actor! "review" :reviewer
                                                {:response-policy :sometimes})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"config must be"
                        (sdb/assign-room-actor! "review" :reviewer
                                                {:config [:not :a :map]})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"decode to a map"
                        (sdb/assign-room-actor! "review" :reviewer
                                                {:config "[:still :not-a-map]"}))))

(deftest unassign-retracts-both-representations
  (actors/spawn-agent! *conn* {:id :observer})
  (sdb/create-room! {:slug "ops" :name "Ops"})
  (sdb/assign-room-actor! "ops" :observer
                          {:role :observer :response-policy :manual})
  (is (true? (sdb/unassign-room-actor! "ops" :observer)))
  (is (empty? (sdb/room-assignments "ops")))
  (is (empty? (:room/agent-ids (sdb/room-by-slug "ops"))))
  (is (false? (sdb/unassign-room-actor! "ops" :observer))))

(deftest join-agent-materializes-when-identity-is-available
  (sdb/create-room! {:slug "dispatch" :name "Dispatch"})
  (testing "boot ordering remains compatible before an actor row exists"
    (let [result (rooms/join-agent! "dispatch" :late-agent)]
      (is (not (:discourse-joined? result)))
      (is (true? (get-in result [:assignment :assignment/legacy?])))))
  (testing "joining again materializes the assignment with explicit policy"
    (actors/spawn-agent! *conn* {:id :late-agent :name "Late"})
    (let [result (rooms/join-agent! "dispatch" :late-agent
                                    {:role :reviewer
                                     :response-policy :mention})]
      (is (= :reviewer (get-in result [:assignment :assignment/role])))
      (is (= :mention
             (get-in result [:assignment :assignment/response-policy])))
      (is (nil? (get-in result [:assignment :assignment/legacy?]))))))
