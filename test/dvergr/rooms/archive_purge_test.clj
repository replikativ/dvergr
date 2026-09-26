(ns dvergr.rooms.archive-purge-test
  "Deleting a room archives it: closed, neither listed nor hydrated again, its
   history kept. An archived room can be brought back, or purged for good; a
   purge refuses a room that is not archived, and needs its slug repeated."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.ops :as ops]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as sdb]))

(def ^:dynamic *daemon* nil)

(use-fixtures :once
  (fn [f]
    (let [prev-home (paths/home)]
      (paths/set-home! (str (System/getProperty "java.io.tmpdir") "/dvergr-archive-test-" (random-uuid)))
      (sdb/reset-conn!)
      (let [d (daemon/start! {:agents {}})]
        (try
          (binding [*daemon* d] (f))
          (finally
            (try (daemon/stop! d) (catch Exception _))
            (sdb/reset-conn!)
            (paths/set-home! prev-home)))))))

(defn- listed? [slug] (some #(= slug (:room/slug %)) (sdb/all-rooms)))
(defn- live? [slug] (some? (ops/resolve-room *daemon* slug)))
(defn- messages [slug] (ops/invoke *daemon* :room/messages {:room slug}))

(deftest delete-archives-unarchive-restores-purge-removes
  (ops/invoke *daemon* :room/create {:slug "archived-room"})
  (ops/invoke *daemon* :room/post {:room "archived-room" :content "remember this"})
  (let [before (messages "archived-room")]
    (is (seq before))
    (testing "delete archives: closed and not listed, the registry row kept"
      (is (= {:deleted "archived-room" :archived true}
             (select-keys (ops/invoke *daemon* :room/delete {:room "archived-room"}) [:deleted :archived])))
      (is (not (live? "archived-room")))
      (is (not (listed? "archived-room")))
      (is (:room/archived-at (sdb/room-by-slug "archived-room"))))
    (testing "unarchive brings it back with its history"
      (ops/invoke *daemon* :room/unarchive {:room "archived-room"})
      (is (live? "archived-room"))
      (is (listed? "archived-room"))
      (is (= (map :content before) (map :content (messages "archived-room")))))
    (testing "a purge refuses a room that is not archived, or unconfirmed"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not archived"
                            (ops/invoke *daemon* :room/purge {:room "archived-room" :confirm "archived-room"})))
      (ops/invoke *daemon* :room/delete {:room "archived-room"})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"confirm"
                            (ops/invoke *daemon* :room/purge {:room "archived-room" :confirm "yes"}))))
    (testing "purge removes the room and the stores it owns"
      (let [owned (map :system (filter #(= :owner (:permission %))
                                       (sdb/systems-for-room (:room/id (sdb/room-by-slug "archived-room")))))
            dirs (keep :system/scope owned)]
        (is (seq owned))
        (is (pos? (:owned-systems-removed
                   (ops/invoke *daemon* :room/purge {:room "archived-room" :confirm "archived-room"}))))
        (is (nil? (sdb/room-by-slug "archived-room")))
        (is (not-any? #(.exists (io/file %)) dirs) "its stores are gone from disk")
        (is (thrown? clojure.lang.ExceptionInfo
                     (ops/invoke *daemon* :room/unarchive {:room "archived-room"})))))))
