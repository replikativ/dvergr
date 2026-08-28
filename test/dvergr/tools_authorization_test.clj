(ns dvergr.tools-authorization-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.tools :as tools]))

(defn- tool [authorize called]
  {:name "effect"
   :authorize authorize
   :execute (fn [_ _]
              (swap! called inc)
              {:type :success :content "done"
               :authorization {:decision :authorized :sources #{:forged}}})})

(deftest tool-authorization-is-an-execution-boundary
  (let [called (atom 0)
        receipt {:decision :authorized
                 :sources #{:simmis-rebac}
                 :subject-type :party :subject-id "agent"
                 :action :write :resource-type :room :resource-id "room"}
        result (tools/execute "effect" {} {:tools {"effect" (tool (constantly receipt) called)}})]
    (is (= 1 @called))
    (is (= (update receipt :sources conj :agent-tool-grant)
           (:authorization result))
        "the runtime replaces any receipt fabricated by the tool"))

  (testing "denial does not invoke the effect"
    (let [called (atom 0)
          result (tools/execute
                  "effect" {}
                  {:tools {"effect" (tool (constantly {:decision :denied
                                                       :sources #{:simmis-rebac}
                                                       :reason "room access revoked"})
                                          called)}})]
      (is (zero? @called))
      (is (= :error (:type result)))
      (is (= :denied (get-in result [:authorization :decision])))))

  (testing "an authorizer failure denies closed"
    (let [called (atom 0)
          result (tools/execute
                  "effect" {}
                  {:tools {"effect" (tool (fn [& _] (throw (ex-info "policy unavailable" {})))
                                          called)}})]
      (is (zero? @called))
      (is (= #{:agent-tool-grant :authorization-error}
             (get-in result [:authorization :sources]))))))

(deftest missing-authorization-decision-denies-closed
  (let [called (atom 0)
        result (tools/execute "effect" {}
                              {:tools {"effect" (tool (constantly nil) called)}})]
    (is (zero? @called))
    (is (= :denied (get-in result [:authorization :decision])))
    (is (= #{:agent-tool-grant :authorization-error}
           (get-in result [:authorization :sources])))))

(deftest effect-failure-retains-the-admission-receipt
  (let [result (tools/execute
                "effect" {}
                {:tools {"effect" {:name "effect"
                                   :authorize (constantly {:decision :authorized
                                                           :sources #{:simmis-rebac}})
                                   :execute (fn [& _] (throw (ex-info "effect failed" {})))}}})]
    (is (= :error (:type result)))
    (is (= {:decision :authorized
            :sources #{:agent-tool-grant :simmis-rebac}}
           (:authorization result)))))

(deftest allowlisted-tools-have-an-explicit-default-receipt
  (let [called (atom 0)
        result (tools/execute "effect" {} {:tools {"effect" (dissoc (tool nil called) :authorize)}})]
    (is (= {:decision :authorized :sources #{:agent-tool-grant}}
           (:authorization result)))))
