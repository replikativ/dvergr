(ns dvergr.sandbox.ns.intake-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.sandbox.ns.intake :as intake]))

(deftest optional-mail-namespace-is-resolved-from-one-snapshot
  (testing "an unavailable or partially loaded optional namespace is skipped"
    (is (nil? (#'intake/resolve-mail-bindings nil))))
  (testing "all native callables are captured together"
    (let [ns-sym (symbol (str "dvergr.test.mail-" (random-uuid)))
          mail-ns (create-ns ns-sym)]
      (try
        (doseq [host-name '[list-inbox search-mail read-message sync-inbox!]]
          (intern mail-ns host-name (fn [& _] host-name)))
        (let [bindings (#'intake/resolve-mail-bindings mail-ns)]
          (is (= #{'inbox 'search 'read 'sync!} (set (keys bindings))))
          (is (every? fn? (vals bindings))))
        (finally
          (remove-ns ns-sym))))))

(deftest incomplete-mail-namespace-is-not-mounted
  (let [ns-sym (symbol (str "dvergr.test.mail-incomplete-" (random-uuid)))
        mail-ns (create-ns ns-sym)]
    (try
      (intern mail-ns 'list-inbox (fn [] []))
      (is (nil? (#'intake/resolve-mail-bindings mail-ns)))
      (finally
        (remove-ns ns-sym)))))
