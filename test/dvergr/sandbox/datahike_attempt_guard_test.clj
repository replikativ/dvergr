(ns dvergr.sandbox.datahike-attempt-guard-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.chat.schema :as schema]
            [dvergr.sandbox.ns.datahike :as sandbox-datahike]))

(defn- conn []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :keep-history? false :schema-flexibility :write}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (schema/ensure-full-schema! conn)
      conn)))

(deftest sci-datahike-surface-rejects-certified-evaluation-writes-and-retractions
  (let [conn (conn)
        attempt-id (random-uuid)
        scorecard-id (random-uuid)
        guard @#'sandbox-datahike/assert-no-certified-evaluation-write!]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"host-owned"
         (guard conn [{:attempt/id attempt-id}])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"host-owned"
         (guard conn [[:db/add -1 :attempt/reward 1.0]])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"host-owned"
         (guard conn [{:scorecard/id scorecard-id}])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"host-owned"
         (guard conn [[:db/add -1 :scorecard.summary/reward-mean 1.0]])))
    ;; Host setup proves numeric retractEntity cannot bypass the namespace check.
    (d/transact conn [{:attempt/id attempt-id}])
    (let [eid (:db/id (d/entity @conn [:attempt/id attempt-id]))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"host-owned"
           (guard conn [[:db/retractEntity eid]]))))))
