(ns dvergr.substrate.spin-test
  "Cancelling a spin that parks on an await while it is being cancelled (the
   race that left a cancelled job :running; fixed in spindel 0.1.110, #116,
   and 0.1.111, #118), on its own and as the child its cancelled parent
   awaits."
  (:require [clojure.test :refer [deftest is]]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.core :as spin-core]))

(deftest a-spin-that-parks-while-it-is-cancelled-is-rejected
  (let [ctx (context/create-execution-context)]
    (try
      (binding [ec/*execution-context* ctx]
        (let [d (sp/deferred)
              s (sp/spin (sp/await d))
              outcome (promise)
              abort spin-core/abort-spin-chain!
              parked? (atom false)]
          ;; Deterministic interleaving: the first time the cancellation is
          ;; about to be cached, the spin runs to its await and parks.
          (with-redefs [spin-core/abort-spin-chain!
                        (fn [sid err]
                          (when (compare-and-set! parked? false true)
                            (s (fn [v] (deliver outcome [:resolved v]))
                               (fn [e] (deliver outcome [:rejected (:type (ex-data e))]))))
                          (abort sid err))]
            (spin-core/cancel-spin! s))
          (is (= [:rejected spin-core/spin-cancelled] (deref outcome 5000 ::stranded))
              "the parked await is rejected with the cancellation")
          (is (empty? (ec/get-state [:await-conts (spin-core/spin-id s)]))
              "no continuation stays parked")))
      (finally
        (context/stop-context! ctx)))))

(deftest a-child-that-parks-while-its-parent-is-cancelled-is-rejected
  (let [ctx (context/create-execution-context)]
    (try
      (binding [ec/*execution-context* ctx]
        (let [d1 (sp/deferred)
              d2 (sp/deferred)
              child (sp/spin (sp/await d1) (sp/await d2))
              child-id (spin-core/spin-id child)
              parent (sp/spin (sp/await child))
              outcome (promise)
              abort spin-core/abort-spin-chain!
              moved? (atom false)]
          ;; parent parks awaiting child, child parks on d1
          (parent (fn [v] (deliver outcome [:resolved v]))
                  (fn [e] (deliver outcome [:rejected (:type (ex-data e))])))
          ;; Deterministic interleaving: as the parent's cancellation reaches
          ;; the child, the child's d1 delivers and it moves on to park on d2,
          ;; after the child's parked continuations were read and before its
          ;; cancellation is cached.
          (with-redefs [spin-core/abort-spin-chain!
                        (fn [sid err]
                          (when (and (= sid child-id) (compare-and-set! moved? false true))
                            (let [[_ cont] (first (ec/get-state [:await-conts child-id]))]
                              ((:resolve-fn cont) :d1)))
                          (abort sid err))]
            (spin-core/cancel-spin! parent))
          (is @moved? "the child moved on to its second await")
          (is (= [:rejected spin-core/spin-cancelled] (deref outcome 5000 ::stranded)))
          (is (empty? (ec/get-state [:await-conts child-id]))
              "the child's second await does not stay parked")
          (is (ec/spin-is-cancelled? child-id))))
      (finally
        (context/stop-context! ctx)))))
