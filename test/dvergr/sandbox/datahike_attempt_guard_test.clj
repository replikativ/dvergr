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

(deftest sci-datahike-surface-rejects-certified-attempt-writes-and-retractions
  (let [conn (conn)
        attempt-id (random-uuid)
        guard @#'sandbox-datahike/assert-no-attempt-write!]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"host-certified"
         (guard conn [{:attempt/id attempt-id}])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"host-certified"
         (guard conn [[:db/add -1 :attempt/reward 1.0]])))
    ;; Host setup proves numeric retractEntity cannot bypass the namespace check.
    (d/transact conn [{:attempt/id attempt-id}])
    (let [eid (:db/id (d/entity @conn [:attempt/id attempt-id]))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"host-certified"
           (guard conn [[:db/retractEntity eid]]))))))
