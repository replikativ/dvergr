(ns dvergr.actors-test
  "Tests for dvergr.actors — durable actor identity backed by Datahike."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as dh]
            [dvergr.actors :as actors]
            [dvergr.chat.schema :as schema]))

;; ============================================================================
;; Fixture: in-memory Datahike with schema installed
;; ============================================================================

(def ^:dynamic *conn* nil)

(defn- mem-db-fixture [f]
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :keep-history? false
             :schema-flexibility :write}]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)]
      (schema/install-schema! conn)
      (binding [*conn* conn]
        (try (f)
             (finally
               (dh/release conn)
               (dh/delete-database cfg)))))))

(use-fixtures :each mem-db-fixture)

;; ============================================================================
;; Tests
;; ============================================================================

(deftest spawn-and-lookup
  (testing "spawn-agent! writes a row that lookup returns"
    (let [actor (actors/spawn-agent! *conn*
                                     {:id          :scribe
                                      :name        "Scribe"
                                      :profile-ref "scribe.md"
                                      :skills      #{:writing :prose}
                                      :config      {:provider :fireworks
                                                    :model    "test-model"}
                                      :cost        {:dollars-per-task 0.05}})]
      (is (= :scribe (:id actor)))
      (is (= :agent  (:kind actor)))
      (is (= "Scribe" (:name actor)))
      (is (= #{:writing :prose} (:skills actor)))
      (is (= :online (:status actor)))
      (is (instance? java.util.Date (:created-at actor)))
      (is (= {:provider :fireworks :model "test-model"} (:config actor)))
      (is (= {:dollars-per-task 0.05} (:cost actor))))))

(deftest list-with-filters
  (testing "list-actors filters by kind, status, skill"
    (actors/spawn-agent! *conn* {:id :a :skills #{:research}})
    (actors/spawn-agent! *conn* {:id :b :skills #{:prose}})
    (actors/spawn-agent! *conn* {:id :c :skills #{:research :prose}})
    (actors/dismiss! *conn* :b)
    (is (= 3 (count (actors/list-actors *conn*))))
    (is (= 2 (count (actors/list-actors *conn* :status :online))))
    (is (= 1 (count (actors/list-actors *conn* :status :retired))))
    (is (= #{:a :c}
           (set (map :id (actors/list-actors *conn* :skill :research)))))))

(deftest dismiss-flags-retired
  (testing "dismiss! marks status :retired but keeps the row"
    (actors/spawn-agent! *conn* {:id :tmp :name "Temp"})
    (is (= :online (:status (actors/lookup *conn* :tmp))))
    (actors/dismiss! *conn* :tmp)
    (let [a (actors/lookup *conn* :tmp)]
      (is (= :retired (:status a)))
      (is (= "Temp"  (:name a))) ; row preserved
      )))

(deftest skill-add-remove
  (testing "add-skill! / remove-skill! mutate the skill set"
    (actors/spawn-agent! *conn* {:id :s :skills #{:writing}})
    (actors/add-skill!    *conn* :s :research)
    (is (= #{:writing :research} (:skills (actors/lookup *conn* :s))))
    (actors/remove-skill! *conn* :s :writing)
    (is (= #{:research} (:skills (actors/lookup *conn* :s))))))

(deftest update-patches-fields
  (testing "update-actor! merges patch into stored row"
    (actors/spawn-agent! *conn* {:id :x :name "Old" :config {:model "a"}})
    (actors/update-actor! *conn* :x {:name "New" :config {:model "b"}})
    (let [a (actors/lookup *conn* :x)]
      (is (= "New" (:name a)))
      (is (= "b"   (-> a :config :model))))))

(deftest external-refs-round-trip
  (testing ":external-refs is EDN-serialized and restored as a map"
    (actors/spawn-agent! *conn*
                         {:id :u
                          :external-refs {:telegram 12345
                                          :email    "u@example.com"}})
    (let [a (actors/lookup *conn* :u)]
      (is (= {:telegram 12345 :email "u@example.com"} (:external-refs a))))))

(deftest lookup-by-external-ref-finds-actor
  (testing "reverse-lookup by channel + value returns the actor"
    (actors/spawn-human! *conn* {:id :alice :external-refs {:telegram 555}})
    (actors/spawn-human! *conn* {:id :bob   :external-refs {:telegram 777}})
    (is (= :alice (:id (actors/lookup-by-external-ref *conn* :telegram 555))))
    (is (= :bob   (:id (actors/lookup-by-external-ref *conn* :telegram 777))))
    (is (nil? (actors/lookup-by-external-ref *conn* :telegram 999)))))

(deftest ensure-external-actor-is-idempotent
  (testing "first call creates a :human row; repeat calls return the same actor"
    (let [a1 (actors/ensure-external-actor! *conn* :telegram 4242 :name "Zed")
          a2 (actors/ensure-external-actor! *conn* :telegram 4242)]
      (is (= :human (:kind a1)))
      (is (= {:telegram 4242} (:external-refs a1)))
      (is (= "Zed" (:name a1)))
      (is (= (:id a1) (:id a2)))               ; no duplicate row
      (is (= 1 (count (actors/list-actors *conn* :kind :human)))))))
