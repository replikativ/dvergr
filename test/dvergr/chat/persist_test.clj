(ns dvergr.chat.persist-test
  "H2 — one message-durability policy: success, retry-once, and dead-letter
   (never a silent drop, never a throw into the caller)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [dvergr.chat.persist :as persist]))

(defn- fresh-conn []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn [{:db/ident :thing/name
                         :db/valueType :db.type/string
                         :db/cardinality :db.cardinality/one}])
      conn)))

(deftest persist-success
  (testing "a good transact returns true and lands"
    (let [conn (fresh-conn)]
      (is (true? (persist/persist-tx! conn [{:thing/name "ok"}] {:op :t})))
      (is (= #{["ok"]} (d/q '[:find ?n :where [_ :thing/name ?n]] (d/db conn)))))))

(deftest persist-dead-letters-never-throws
  (testing "a permanently-bad transact never throws, returns false, and is
            dead-lettered + surfaced rather than silently dropped"
    (let [conn (fresh-conn)
          before (count @persist/dead-letters)
          ;; a long where a string is required — fails schema, retry won't help
          result (persist/persist-tx! conn [{:thing/name 123}]
                                      {:op :store-message :msg-id "m1"})]
      (is (false? result))
      (is (= (inc before) (count @persist/dead-letters)))
      (is (= "m1" (get-in (last @persist/dead-letters) [:ctx :msg-id]))))))
