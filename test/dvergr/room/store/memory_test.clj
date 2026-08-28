(ns dvergr.room.store.memory-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.room.store.contract :as contract]
            [dvergr.room.store :as store]
            [dvergr.room.store.memory :as memory]))

(deftest message-envelope-contract
  (contract/assert-message-envelope! (memory/make) :envelope-memory))

(deftest run-lifecycle-contract
  (contract/assert-run-lifecycle! (memory/make) :runs-memory))

(deftest rejects-unmodelled-durable-metadata
  (let [st (memory/make)]
    (store/-store-room! st :strict-memory {:slug "strict-memory"})
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unknown durable message"
         (store/-store-message!
          st :strict-memory
          {:id (random-uuid) :from :alice :content "hi"
           :metadata {:unmodelled/value 1}})))))
