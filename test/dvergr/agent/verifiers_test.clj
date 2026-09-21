(ns dvergr.agent.verifiers-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.verifiers :as verifiers]))

(defn- evaluator [id tier]
  (evaluation/make-evaluator
   (cond-> {:id id :observe (fn [_] {}) :verify (fn [_ _] {:checks {} :reward 0.0})}
     tier (assoc :tier tier))))

(deftest evaluators-carry-a-trust-tier
  (is (= :trusted (evaluation/evaluator-tier (evaluator ::default nil))))
  (is (= :ad-hoc (evaluation/evaluator-tier (evaluator ::scratch :ad-hoc))))
  (is (thrown? clojure.lang.ExceptionInfo (evaluator ::bogus :certified))))

(deftest registry-binds-references-once
  (let [e (evaluator ::registered :room)
        ref (evaluation/evaluator-ref e)
        setup (evaluation/make-world-setup {:id ::setup :prepare (fn [_] nil)})
        setup-ref (evaluation/world-setup-ref setup)]
    (try
      (is (= ref (verifiers/register-evaluator! e)))
      (is (identical? e (verifiers/evaluator ref)))
      (is (= :room (verifiers/tier ref)))
      (is (identical? e (get (verifiers/evaluators) ref)))
      (testing "re-registering the same capability is idempotent"
        (is (= ref (verifiers/register-evaluator! e))))
      (testing "a reference cannot be rebound to a different capability"
        (is (thrown? clojure.lang.ExceptionInfo
                     (verifiers/register-evaluator! (evaluator ::registered :trusted)))))
      (is (= setup-ref (verifiers/register-world-setup! setup)))
      (is (identical? setup (get (verifiers/world-setups) setup-ref)))
      (is (thrown? clojure.lang.ExceptionInfo (verifiers/register-evaluator! {:ref ref})))
      (finally
        (verifiers/unregister! ref)
        (verifiers/unregister! setup-ref)))
    (is (nil? (verifiers/evaluator ref)))
    (is (nil? (verifiers/tier ref)))))
