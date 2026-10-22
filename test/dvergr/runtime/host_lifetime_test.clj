(ns dvergr.runtime.host-lifetime-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.ops :as ops]
            [dvergr.principal :as principal]
            [dvergr.runtime.host-lifetime :as lifetime]))

(defn- failure-type [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))

(deftest absent-hook-preserves-standalone-calls-and-malformed-hooks-refuse
  (is (= :result (lifetime/call {} (constantly :result))))
  (doseq [configuration [nil {} {:with-operation nil} {:with-operation false}
                         {:with-operation identity :extra true} identity]]
    (let [called (atom false)]
      (is (= ::lifetime/invalid-configuration
             (failure-type #(lifetime/call {:config {:host-lifecycle configuration}}
                                           (fn [] (reset! called true))))))
      (is (false? @called)))))

(deftest deferred-foreign-and-duplicate-forwarding-never-enter-later-work
  (let [saved (atom nil) calls (atom 0)
        body #(swap! calls inc)]
    (is (= ::lifetime/not-invoked
           (failure-type #(lifetime/call {:config {:host-lifecycle {:with-operation (fn [f] (reset! saved f))}}} body))))
    (is (= ::lifetime/invalid-forwarding (failure-type @saved)))
    (is (zero? @calls))
    (let [foreign (atom nil)]
      (is (= ::lifetime/not-invoked
             (failure-type #(lifetime/call
                             {:config {:host-lifecycle {:with-operation (fn [f] (reset! foreign @(future (failure-type f))))}}}
                             body))))
      (is (= ::lifetime/invalid-forwarding @foreign)))
    (is (zero? @calls))
    (is (= ::lifetime/invalid-forwarding
           (failure-type #(lifetime/call {:config {:host-lifecycle {:with-operation (fn [f] (f) (f))}}} body))))
    (is (= 1 @calls))))

(deftest invoke-owns-authorization-implementation-and-filtering-as-one-call
  (let [events (atom []) active (atom false)
        host {:config {:host-lifecycle
                       {:with-operation (fn [f]
                                          (swap! events conj :enter)
                                          (reset! active true)
                                          (try (f) (finally (reset! active false) (swap! events conj :exit))))}}}]
    (with-redefs [ops/operations (constantly {:test/lifetime
                                              {:kind :read :schema [:map]
                                               :authz {:action :read :resource [:global]
                                                       :filter (fn [_ result]
                                                                 (is @active)
                                                                 (swap! events conj :filter)
                                                                 result)}
                                               :impl (fn [_ _] (is @active) (swap! events conj :body) :result)}})
                  ops/authorize! (fn [& _] (is @active) (swap! events conj :authorize))]
      (is (= :result (ops/invoke host {:principal/id "human/fixture" :principal/kind :human} :test/lifetime {})))
      (is (= [:enter :authorize :body :filter :exit] @events)))
    (let [calls (atom 0)
          stale (assoc-in host [:config :host-lifecycle :with-operation]
                          (fn [_] (throw (ex-info "Retired exact owner" {:type ::retired}))))]
      (with-redefs [ops/authorize! (fn [& _] (swap! calls inc))]
        (is (= ::retired (failure-type #(ops/invoke stale principal/local-principal :model/list {}))))
        (is (zero? @calls))))))
