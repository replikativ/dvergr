(ns dvergr.actors-human-test
  "Tests for :human actor creation and dispatch routing."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as dh]
            [dvergr.actors :as actors]
            [dvergr.chat.schema :as schema]
            [dvergr.orchestration.skills :as skills]
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

;; ============================================================================
;; spawn-human!
;; ============================================================================

(deftest spawn-human-requires-external-refs
  (testing "missing :external-refs throws"
    (is (thrown? AssertionError
                 (actors/spawn-human! *conn* {:id :alice})))
    (is (thrown? AssertionError
                 (actors/spawn-human! *conn* {:id :alice :external-refs {}})))))

(deftest spawn-human-roundtrips
  (let [h (actors/spawn-human! *conn*
                               {:id :alice
                                :name "Alice"
                                :external-refs {:telegram 12345
                                                :email "alice@example.com"}
                                :skills #{:legal-review :prose}})]
    (is (= :human (:kind h)))
    (is (= "Alice" (:name h)))
    (is (= {:telegram 12345 :email "alice@example.com"} (:external-refs h)))
    (is (= #{:legal-review :prose} (:skills h)))
    (is (= :online (:status h)))))

(deftest list-actors-filters-by-kind
  (actors/spawn-agent! *conn* {:id :a1 :skills #{:research}})
  (actors/spawn-human! *conn* {:id :h1
                               :external-refs {:email "h@x"}
                               :skills #{:research}})
  (is (= 1 (count (actors/list-actors *conn* :kind :agent))))
  (is (= 1 (count (actors/list-actors *conn* :kind :human))))
  (is (= 2 (count (actors/list-actors *conn* :skill :research)))))

;; ============================================================================
;; Dispatch routing — humans vs agents
;; ============================================================================

(deftest dispatch-to-human-creates-task-record
  (actors/spawn-human! *conn*
                       {:id :alice
                        :name "Alice"
                        :external-refs {:email "alice@x"}
                        :skills #{:legal-review}})
  (let [result (skills/dispatch! *conn* :legal-review
                                 {:task    "review the new NDA"
                                  :room-id :boardroom
                                  :from-actor :var})]
    (is (= :dispatched (:status result)))
    (is (= :alice (:id (:actor result))))
    (is (= "review the new NDA" (:content (:task result))))
    (is (= :pending (:status (:task result))))
    (is (= :legal-review (:skill (:task result))))
    (is (= :var (:from-actor (:task result))))
    ;; The task is in the ledger:
    (is (= 1 (count (tasks/list-tasks *conn* :actor-id :alice))))))

(deftest dispatch-to-agent-records-no-task
  (testing "agents react to inbox; no task row needed"
    (actors/spawn-agent! *conn* {:id :scribe :skills #{:writing}})
    (let [result (skills/dispatch! *conn* :writing
                                   {:task "draft a haiku" :room-id :boardroom})]
      (is (= :dispatched (:status result)))
      (is (= :scribe (:id (:actor result))))
      (is (nil? (:task result)))
      (is (= 0 (count (tasks/list-tasks *conn*)))))))

(deftest dispatch-no-provider
  (let [result (skills/dispatch! *conn* :nobody-has-this
                                 {:task "tilt at windmills"})]
    (is (= :no-provider (:status result)))
    (is (nil? (:actor result)))))

(deftest dispatch-prefers-pinned-priority
  (testing "human with explicit override beats default-priority agent"
    (actors/spawn-agent! *conn* {:id :scribe :skills #{:prose}})
    (actors/spawn-human! *conn*
                         {:id :alice
                          :external-refs {:email "a@x"}
                          :skills #{:prose}
                          :skill-priorities {:prose 1000}})
    (let [result (skills/dispatch! *conn* :prose
                                   {:task "polish this"})]
      (is (= :alice (:id (:actor result)))
          "Alice's :prose priority 1000 should beat scribe's default 100"))))
