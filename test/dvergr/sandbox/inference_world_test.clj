(ns dvergr.sandbox.inference-world-test
  "Dvergr's SCI inference surface defaults effectful particles to canonical worlds."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.ns.data :as data-ns]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.protocols :as rtp]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [sci.core :as sci]
            [yggdrasil.convergent.gset :as g]))

(defn- memory-gset [id]
  (g/gset id {:store-config {:backend :memory :id (random-uuid)}}
          {:sync? true}))

(deftest sci-smc-defaults-to-isolated-canonical-worlds
  (let [root (context/create-execution-context)]
    (try
      (binding [ec/*execution-context* root]
        (let [knowledge (ygg/register!
                         (-> (memory-gset "dvergr-inference-kb")
                             (g/conj :root {:sync? true})))
              sci-ctx (sandbox/fork-for-session root)]
          (data-ns/add-inference-ns! sci-ctx)
          ;; These are deliberately opaque host capabilities. SCI can request
          ;; one mutation in its current particle world but cannot obtain a
          ;; Yggdrasil handle or settlement authority.
          (sci/add-namespace!
           sci-ctx 'particle
           {'id (fn []
                  (rtp/get-state ec/*execution-context*
                                 [:inference :particle-id]))
            'write! (fn [value]
                      (let [signal (ygg/system-signal "dvergr-inference-kb")]
                        (reset! signal
                                (g/conj @signal value {:sync? true})))
                      value)
            'values (fn []
                      (g/elements @(ygg/system-signal "dvergr-inference-kb")
                                  {:sync? true}))})
          (let [result
                (sandbox/eval-code
                 sci-ctx
                 (str
                  "(require '[org.replikativ.spindel.spin.cps :refer [spin]] "
                  "         '[org.replikativ.spindel.effects.await :refer [await]] "
                  "         '[org.replikativ.spindel.inference.effects :refer [observe]] "
                  "         '[dist] '[infer] '[particle]) "
                  "(let [model (spin "
                  "              (let [id (particle/id)] "
                  "                (particle/write! id) "
                  "                (observe (dist/normal 0.0 1.0) 0.0 :id :evidence) "
                  "                (particle/values))) "
                  "      posterior @(spin (await (infer/smc-infer "
                  "                              model 4 {:resample-threshold 2.0})))] "
                  "  {:values (infer/values posterior) "
                  "   :weights (infer/log-weights posterior) "
                  "   :ess (infer/ess posterior) "
                  "   :worlds (infer/worlds posterior) "
                  "   :raw-particles (:particles posterior) "
                  "   :raw-executor (:executor posterior)})")
                 ;; Canonical particle worlds fork and settle four complete
                 ;; execution contexts. Keep the watchdog well above normal
                 ;; latency so a memory-constrained full-suite worker does not
                 ;; cancel healthy inference midway through settlement.
                 :timeout-ms 60000)
                {:keys [values weights ess worlds raw-particles raw-executor]}
                (:value result)]
            (is (:success result) (pr-str (:error result)))
            (testing "posterior values retain independent selected ancestry"
              (is (= 4 (count values)))
              (is (every? #(and (= 2 (count %)) (contains? % :root)) values)))
            (testing "portable inference projections contain no live authority"
              (is (= 4 (count weights)))
              (is (number? ess))
              (is (= 4 (count worlds)))
              (is (every? #(and (map? %)
                                (= :particle (:fork/purpose %)))
                          worlds))
              (is (nil? raw-particles))
              (is (nil? raw-executor)))
            (testing "particle writes do not contaminate the ambient room world"
              (is (= #{:root} (g/elements @knowledge {:sync? true})))))
          (let [pure
                (sandbox/eval-code
                 sci-ctx
                 (str
                  "(let [posterior @(spin (await (infer/smc-infer "
                  "                              (spin 7) 2 {:world-policy :fresh})))] "
                  "  {:values (infer/values posterior) "
                  "   :worlds (infer/worlds posterior) "
                  "   :mean (:mean (infer/query posterior identity)) "
                  "   :predictions (infer/predict posterior inc 3)})")
                 :timeout-ms 30000)]
            (testing "a proven-pure model may explicitly choose the cheap path"
              (is (:success pure) (pr-str (:error pure)))
              (is (= {:values [7 7]
                      :worlds []
                      :mean 7.0
                      :predictions [8 8 8]}
                     (:value pure)))))))
      (finally
        (context/stop-context! root)))))
