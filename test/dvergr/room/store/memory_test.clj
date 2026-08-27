(ns dvergr.room.store.memory-test
  (:require [clojure.test :refer [deftest]]
            [dvergr.room.store.contract :as contract]
            [dvergr.room.store.memory :as memory]))

(deftest message-envelope-contract
  (contract/assert-message-envelope! (memory/make) :envelope-memory))
