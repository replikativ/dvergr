(ns dvergr.orchestration.tasks-test
  "Tests for dvergr.orchestration.tasks — task ledger lifecycle."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as dh]
            [dvergr.chat.schema :as schema]
            [dvergr.orchestration.tasks :as tasks]))

(def ^:dynamic *conn* nil)

(defn- mem-db-fixture [f]
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :keep-history? false
             :schema-flexibility :write}]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)]
      (schema/install-schema! conn)
      (binding [*conn* conn]
        (try (f) (finally (dh/release conn) (dh/delete-database cfg)))))))

(use-fixtures :each mem-db-fixture)

(deftest create-and-lookup
  (testing "create-task! returns a task that lookup finds"
    (let [t (tasks/create-task! *conn*
                                {:actor-id :alice
                                 :room-id  :boardroom
                                 :skill    :legal-review
                                 :content  "review NDA"
                                 :from-actor :var})]
      (is (uuid? (:id t)))
      (is (= :alice (:actor-id t)))
      (is (= :pending (:status t)))
      (is (= :legal-review (:skill t)))
      (is (= "review NDA" (:content t)))
      (is (= :var (:from-actor t)))
      (is (instance? java.util.Date (:created-at t)))
      (is (= t (tasks/lookup *conn* (:id t)))))))

(deftest status-lifecycle
  (testing "accept! → complete! flow"
    (let [t (tasks/create-task! *conn*
                                {:actor-id :a :room-id :r :content "x"})]
      (tasks/accept! *conn* (:id t))
      (is (= :accepted (:status (tasks/lookup *conn* (:id t)))))
      (tasks/complete! *conn* (:id t) "done with result")
      (let [final (tasks/lookup *conn* (:id t))]
        (is (= :completed (:status final)))
        (is (= "done with result" (:result final)))
        (is (instance? java.util.Date (:completed-at final)))))))

(deftest ignore-sets-completed-at
  (let [t (tasks/create-task! *conn*
                              {:actor-id :a :room-id :r :content "x"})]
    (tasks/ignore! *conn* (:id t))
    (let [final (tasks/lookup *conn* (:id t))]
      (is (= :ignored (:status final)))
      (is (instance? java.util.Date (:completed-at final))))))

(deftest list-with-filters
  (tasks/create-task! *conn* {:actor-id :alice :room-id :r :content "x"})
  (tasks/create-task! *conn* {:actor-id :alice :room-id :r :content "y"})
  (tasks/create-task! *conn* {:actor-id :bob   :room-id :r :content "z"})
  (testing "filter by :actor-id"
    (is (= 2 (count (tasks/list-tasks *conn* :actor-id :alice))))
    (is (= 1 (count (tasks/list-tasks *conn* :actor-id :bob)))))
  (testing "filter by :status"
    (let [pending (tasks/list-tasks *conn* :status :pending)
          first-task-id (:id (first pending))]
      (is (= 3 (count pending)))
      (tasks/complete! *conn* first-task-id "ok")
      (is (= 2 (count (tasks/list-tasks *conn* :status :pending))))
      (is (= 1 (count (tasks/list-tasks *conn* :status :completed)))))))

(deftest list-orders-newest-first
  (let [t1 (tasks/create-task! *conn* {:actor-id :a :room-id :r :content "first"})
        _  (Thread/sleep 5) ;; ensure distinct timestamps
        t2 (tasks/create-task! *conn* {:actor-id :a :room-id :r :content "second"})]
    (is (= [(:id t2) (:id t1)] (mapv :id (tasks/list-tasks *conn*))))))

(deftest transition-on-missing-is-nil
  (is (nil? (tasks/accept!   *conn* (random-uuid))))
  (is (nil? (tasks/complete! *conn* (random-uuid) "x")))
  (is (nil? (tasks/ignore!   *conn* (random-uuid)))))
