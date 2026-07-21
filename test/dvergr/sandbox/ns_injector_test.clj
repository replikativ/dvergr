(ns dvergr.sandbox.ns-injector-test
  "The generic consumer namespace-injector registry — how a domain kernel (e.g.
   accounting) plugs a surface into `clojure_eval` WITHOUT dvergr depending on it."
  (:require [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [dvergr.sandbox :as sb]))

(deftest registry-is-idempotent-and-removable
  (let [f (fn [_ _] nil)]
    (try
      (sb/register-ns-injector! f)
      (sb/register-ns-injector! f)                 ; idempotent — same fn once
      (is (= 1 (count (filter #{f} (sb/registered-ns-injectors)))))
      (sb/unregister-ns-injector! f)
      (is (not (some #{f} (sb/registered-ns-injectors))))
      (finally (sb/unregister-ns-injector! f)))))

(deftest registered-injector-exposes-a-callable-namespace
  ;; A consumer registers an injector that mounts a namespace; the sandbox runs
  ;; every registered injector during setup, so the surface is callable in SCI —
  ;; and the room context (opts) reaches the injector. This is the exact seam a
  ;; domain kernel uses; dvergr never names the kernel.
  (let [injector (fn [sci-ctx opts]
                   (sci/add-namespace! sci-ctx 'demo
                                       {'ping    (constantly :pong)
                                        'room-id (:room-id opts)}))]
    (try
      (sb/register-ns-injector! injector)
      (let [ctx (sci/init {})]
        ;; simulate the sandbox setup's injector pass
        (doseq [f (sb/registered-ns-injectors)]
          (f ctx {:room-id :r1 :room-conn ::rc :kb-conn ::kb}))
        (testing "injected surface is callable in clojure_eval"
          (is (= :pong (sci/eval-string* ctx "(require '[demo]) (demo/ping)"))))
        (testing "room context reaches the injector via opts"
          (is (= :r1 (sci/eval-string* ctx "demo/room-id")))))
      (finally (sb/unregister-ns-injector! injector)))))
