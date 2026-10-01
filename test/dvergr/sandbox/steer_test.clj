(ns dvergr.sandbox.steer-test
  "Steering a process from the sandbox: a program whose steps are random
  without a sample site — as a model call is — tilted by a reward, with a
  value estimate as twist, in canonical particle worlds. The posterior
  matches the exact tilted law."
  (:require [clojure.test :refer [deftest is]]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.ns.data :as data-ns]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]))

;; five fair coin steps tilted by e^{λ·heads}: heads ~ Binomial(5, e^λ/(1+e^λ))
(def ^:private lambda 0.8)
(def ^:private exact-mean (* 5 (/ (Math/exp lambda) (+ 1 (Math/exp lambda)))))

(deftest a-sandbox-program-steers-a-process
  (let [root (context/create-execution-context)]
    (try
      (binding [ec/*execution-context* root]
        (let [sci-ctx (sandbox/fork-for-session root)]
          (data-ns/add-inference-ns! sci-ctx)
          (let [result (sandbox/eval-code
                        sci-ctx
                        (str
                         "(require '[org.replikativ.spindel.spin.cps :refer [spin]] "
                         "         '[org.replikativ.spindel.effects.await :refer [await]] "
                         "         '[infer]) "
                         "(let [lambda " lambda " "
                         "      model (infer/steer {:init 0 "
                         "                          :step (fn [heads] (+ heads (rand-int 2))) "
                         "                          :value (fn [heads] (* lambda heads)) "
                         "                          :reward (fn [heads] (* lambda heads)) "
                         "                          :done? (fn [_] false) "
                         "                          :max-steps 5}) "
                         "      posterior @(spin (await (infer/smc-infer model 200 {:resampling :stratified})))] "
                         "  {:values (infer/values posterior) :weights (infer/log-weights posterior)})")
                        :timeout-ms 120000)
                {:keys [values weights]} (:value result)
                top (apply max weights)
                ws (map #(Math/exp (- % top)) weights)
                mean (/ (reduce + (map * ws values)) (reduce + ws))]
            (is (:success result) (pr-str (:error result)))
            (is (= 200 (count values)))
            (is (every? #(<= 0 % 5) values))
            (is (< (Math/abs (- mean exact-mean)) 0.25) (str mean " vs " exact-mean)))))
      (finally
        (context/stop-context! root)))))
