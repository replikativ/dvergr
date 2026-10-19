(ns dvergr.system.default-repository-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.system.db :as db]))

(defn- with-store [f]
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :keep-history? false}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (try (d/transact conn db/schema)
           (with-redefs [db/get-conn (constantly conn)] (f conn))
           (finally (d/release conn) (d/delete-database cfg))))))

(defn- repository [name]
  (db/register-system! {:type :repo :name name :scope (str "/virtual/" (random-uuid))}))

(deftest explicit-default-needs-owner-grant-and-never-follows-attachment-order
  (with-store
    (fn [conn]
      (let [a (repository "a") b (repository "similarly-named-room-repo")
            room (db/create-room! {:slug "default-owned" :default-repository-id a})]
        (is (nil? (db/default-repository room)))
        (db/attach! room b :owner)
        (is (nil? (db/default-repository room)) "A different owner grant cannot supply the default")
        (db/attach! room a :read-write)
        (is (nil? (db/default-repository room)))
        (db/attach! room a :owner)
        (is (= a (:system/id (db/default-repository room))))
        (let [snapshot @conn]
          (db/detach! room a)
          (is (nil? (db/default-repository room)))
          (is (= a (:system/id (db/default-repository snapshot room)))))
        (is (nil? (db/default-repository nil)))
        (is (nil? (db/default-repository (str room))))))))

(deftest refresh-keeps-identity-and-cannot-retarget-or-configure-legacy-rooms
  (with-store
    (fn [_]
      (let [a (repository "a") b (repository "b")
            room (db/create-room! {:slug "stable-default" :default-repository-id a})
            legacy (db/create-room! {:slug "no-marker"})]
        (db/attach! room a :owner)
        (db/attach! legacy b :owner)
        (is (= room (db/create-room! {:slug "stable-default" :name "Updated"})))
        (is (= room (db/create-room! {:slug "stable-default" :default-repository-id a})))
        (is (thrown? clojure.lang.ExceptionInfo
                     (db/create-room! {:slug "stable-default" :name "Not adopted" :default-repository-id b})))
        (is (= a (:system/id (db/default-repository room))))
        (is (= "stable-default" (:room/name (db/room-by-slug "stable-default"))))
        (is (nil? (db/default-repository legacy)))
        (is (thrown? clojure.lang.ExceptionInfo
                     (db/create-room! {:slug "no-marker" :default-repository-id b})))
        (is (nil? (db/default-repository legacy)))))))

(deftest invalid-supplied-defaults-never-create-a-room
  (with-store
    (fn [_]
      (let [wrong (db/register-system! {:type :kb :name "Not a repository" :scope "/virtual/kb"})]
        (doseq [id [nil "" (str (random-uuid)) (random-uuid) wrong]]
          (is (thrown? clojure.lang.ExceptionInfo
                       (db/create-room! {:slug "invalid-default" :default-repository-id id})))
          (is (nil? (db/room-by-slug "invalid-default"))))))))

(deftest wrong-type-and-ambiguous-path-do-not-resolve
  (with-store
    (fn [conn]
      (let [a (repository "a")
            room (db/create-room! {:slug "checked-default" :default-repository-id a})]
        (db/attach! room a :owner)
        (d/transact conn [[:db/add [:system/id a] :system/type :data]])
        (is (nil? (db/default-repository room)))
        (d/transact conn [[:db/add [:system/id a] :system/type :repo]])
        (db/register-system! {:type :repo :name "Duplicate locator"
                              :scope (:system/scope (db/system-by-id a))})
        (is (nil? (db/default-repository room)))))))

(deftest concurrent-room-creators-cannot-replace-the-winning-default
  (with-store
    (fn [_]
      (let [a (repository "a") b (repository "b") start (promise)
            writers (mapv (fn [id]
                            (future @start
                                    (try {:room (db/create-room! {:slug "racing-default" :default-repository-id id})
                                          :repository id}
                                         (catch clojure.lang.ExceptionInfo _ :conflict)))) [a b])]
        (deliver start true)
        (let [results (mapv #(deref % 10000 :timeout) writers)
              winner (first (filter map? results))]
          (is (not-any? #{:timeout} results))
          (is (= 1 (count (filter map? results))))
          (is (= 1 (count (filter #{:conflict} results))))
          (db/attach! (:room winner) (:repository winner) :owner)
          (is (= (:repository winner) (:system/id (db/default-repository (:room winner))))))))))

(deftest identical-concurrent-defaults-return-one-committed-room-identity
  (with-store
    (fn [_]
      (let [a (repository "a") start (promise)
            writers (mapv (fn [_] (future @start (db/create-room! {:slug "same-default" :default-repository-id a})))
                          (range 4))]
        (deliver start true)
        (let [ids (mapv #(deref % 10000 :timeout) writers)]
          (is (every? uuid? ids))
          (is (= 1 (count (set ids)))))))))
