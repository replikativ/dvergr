(ns dvergr.rooms.fork-isolation-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.discourse :as d]
            [dvergr.room.store.memory :as memory]
            [dvergr.rooms.forks :as forks]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]))

(deftest isolation-is-the-rooms-own-capability-not-its-contexts-ancestry
  (let [host (d/make-room {:id (keyword (str "isolation-host-" (random-uuid)))
                           :store (memory/make)})
        ;; Daemon rooms have their own contexts forked from the host. This
        ;; ancestry does not make the room, or a conversational fork of it,
        ;; an isolated Room fork with substrate settlement authority.
        room-ctx (binding [ec/*execution-context* (:ctx host)]
                   (context/fork-context (:ctx host)))
        room (d/make-room {:id (keyword (str "isolation-room-" (random-uuid)))
                           :ctx room-ctx :store (memory/make)})]
    (try
      (testing "a normal room may already have a forked execution context"
        (is (some? (:parent-ctx room-ctx)))
        (is (false? (forks/fork? room)))
        (is (false? (forks/ctx-fork? room)))
        (is (false? (forks/ctx-fork? host)))
        (is (false? (forks/ctx-fork? nil))))
      (binding [ec/*execution-context* room-ctx]
        (let [shared (d/fork-room room {:isolation :none})]
          (try
            (testing "a conversational fork shares that already-forked context"
              (is (forks/fork? shared))
              (is (identical? room-ctx (:ctx shared)))
              (is (nil? (d/fork-handle shared)))
              (is (false? (forks/ctx-fork? shared))))
            (let [isolated (d/fork-room room {:isolation :ctx})]
              (try
                (testing "an isolated fork has its own canonical capability"
                  (is (forks/fork? isolated))
                  (is (not (identical? room-ctx (:ctx isolated))))
                  (is (some? (d/fork-handle isolated)))
                  (is (true? (forks/ctx-fork? isolated))))
                (finally (d/discard isolated))))
            (finally (d/discard shared)))))
      (finally
        (d/close-room! room)
        (d/close-room! host)))))
