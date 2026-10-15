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

(deftest sci-smc-forks-canonical-worlds-on-request
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
                  (keyword (str "particle-"
                                (rtp/get-state ec/*execution-context*
                                               [:inference :slot]))))
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
                  "         '[org.replikativ.foerster.effects :refer [observe]] "
                  "         '[dist] '[infer] '[particle]) "
                  "(let [model (spin "
                  "              (let [id (particle/id)] "
                  "                (particle/write! id) "
                  "                (observe (dist/normal 0.0 1.0) 0.0 :id :evidence) "
                  "                (particle/values))) "
                  "      posterior @(spin (await (infer/smc-infer "
                  "                              model 4 {:resample-threshold 2.0 :world-policy :fork})))] "
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
          (testing "by default (fresh worlds) a model that needs room state fails, writing nothing"
            (let [fresh (sandbox/eval-code
                         sci-ctx
                         (str "(let [model (spin (particle/write! (particle/id)) (particle/values))] "
                              "  @(spin (await (infer/smc-infer model 2))))")
                         :timeout-ms 60000)]
              (is (not (:success fresh)))
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

(deftest a-grant-funds-forked-particles-from-the-calling-world
  ;; doc/design/speculation.md: :grant-microdollars becomes the ledger
  ;; authority of the calling world's savepoint session and a microUSD grant
  (let [funded @#'dvergr.sandbox.ns.data/funded-world-opts
        authority (Object.)
        world (org.replikativ.spindel.engine.context/create-execution-context)]
    (try
      (is (= {:world-policy :fresh} (funded {:world-policy :fresh :authority :forged}))
          "no grant: nothing added, and an :authority from the sandbox is dropped")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":world-policy :fork"
                            (funded {:world-policy :fresh :grant-microdollars 10})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive number"
                            (funded {:world-policy :fork :grant-microdollars -1})))
      (binding [org.replikativ.spindel.engine.core/*execution-context* world]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no budget"
                              (funded {:world-policy :fork :grant-microdollars 10})))
        (org.replikativ.spindel.engine.protocols/swap-state!
         world [:savepoint/session] (constantly {:scope (atom {:authority authority})}))
        (is (= {:world-policy :fork :authority authority
                :grant {dvergr.resource/microdollars 10M}}
               (funded {:world-policy :fork :grant-microdollars 10}))))
      (finally
        (org.replikativ.spindel.engine.context/stop-context! world)))))

(deftest a-model-needs-only-infer-and-dist
  ;; sample, observe and factor from infer/, as foerster.core re-exports them,
  ;; and the current distributions (uniform-discrete) in dist/
  (let [root (context/create-execution-context)]
    (try
      (binding [ec/*execution-context* root]
        (let [sci-ctx (sandbox/fork-for-session root)]
          (data-ns/add-inference-ns! sci-ctx)
          (let [mean (sci/eval-string*
                      sci-ctx
                      (str "(require '[org.replikativ.spindel.spin.cps :refer [spin]] "
                           "         '[infer :refer [sample observe factor]]) "
                           "(def model "
                           "  (spin (let [a (sample (dist/uniform-discrete 1 7) :id :a) "
                           "              b (sample (dist/uniform-discrete 1 7) :id :b)] "
                           "         (factor (if (= 7 (+ a b)) 0.0 ##-Inf)) "
                           "         (observe (dist/normal (+ a b) 1.0) 7.0 :id :reading) "
                           "         a))) "
                           "(:mean (infer/query @(infer/importance-sampling model 2000) identity))"))]
            (is (< 3.0 mean 4.0) (str mean)))))
      (finally
        (context/stop-context! root)))))
