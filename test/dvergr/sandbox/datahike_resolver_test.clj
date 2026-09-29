(ns dvergr.sandbox.datahike-resolver-test
  "A query the sandbox runs is untrusted input: its function symbols resolve
   as Datahike's server resolves them, so a query clause cannot reach the
   host past SCI."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.ns.datahike :as sdh]))

(defn- sandbox-ctx []
  (doto (sandbox/create-base-ctx :load-fn (constantly nil))
    (sdh/add-datahike-ns! :resolver-test nil)))

(deftest a-query-clause-cannot-reach-the-host
  (let [ctx (sandbox-ctx)]
    (doseq [code ["(datahike.api/q '[:find ?x :in ?p :where [(slurp ?p) ?x]] \"/etc/hostname\")"
                  "(datahike.api/q '[:find ?x :in ?p :where [(clojure.core/slurp ?p) ?x]] \"/etc/hostname\")"
                  "(datahike.api/q '[:find ?x :where [(clojure.java.shell/sh \"true\") ?x]])"]]
      (let [r (sandbox/eval-code ctx code :timeout-ms 20000)]
        (is (false? (:success r)) code)
        (is (re-find #"Unknown function" (pr-str (:error r))) code)))
    (testing "pure functions still work"
      (is (= #{["a!"]}
             (:value (sandbox/eval-code ctx "(datahike.api/q '[:find ?x :in ?p :where [(str ?p \"!\") ?x]] \"a\")"
                                        :timeout-ms 20000)))))))
