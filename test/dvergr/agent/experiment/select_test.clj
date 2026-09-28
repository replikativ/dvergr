(ns dvergr.agent.experiment.select-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.experiment.select :as select]))

(defn- entries
  "Scorecard entries of `candidate` over worlds 0..n-1: `(reward w)`, `(cost w)`."
  [candidate n reward cost]
  (for [w (range n)]
    {:candidate/id candidate
     :environment {:environment/content-id w}
     :reward (reward w)
     :spend {:notional-microdollars (cost w)}}))

(deftest the-cheapest-non-inferior-variant-is-chosen
  (let [base (entries :base 12 #(if (even? %) 1.0 0.5) (constantly 100))
        same-cheaper (entries :lean 12 #(if (even? %) 1.0 0.5) (constantly 70))
        better-dearer (entries :rich 12 (constantly 1.0) (constantly 150))
        worse-cheapest (entries :cheap 12 (constantly 0.2) (constantly 10))
        chosen (select/choose (concat base same-cheaper better-dearer worse-cheapest)
                              :base [:lean :rich :cheap])]
    (is (= :lean (:choice chosen)))
    (is (= [:lean] (:shortlist chosen)) "the dearer and the worse variants are not on it")
    (is (= :cheaper-and-good-enough (:reason chosen)))
    (testing "every comparison says why"
      (let [by (into {} (map (juxt :variant identity)) (:comparisons chosen))]
        (is (< (Math/abs (- 0.7 (get-in by [:lean :cost :ratio]))) 1e-9))
        (is (= 12 (get-in by [:lean :worlds])))
        (is (< (first (get-in by [:cheap :reward :interval])) -0.05) "cheapest, but worse")))))

(deftest the-baseline-stays-without-a-cheaper-non-inferior-variant
  (let [base (entries :base 8 (constantly 0.8) (constantly 100))
        dearer (entries :rich 8 (constantly 0.8) (constantly 120))
        worse (entries :cheap 8 (constantly 0.1) (constantly 10))]
    (is (= {:choice :base :reason :none-cheaper}
           (select-keys (select/choose (concat base dearer) :base [:rich]) [:choice :reason])))
    (is (= {:choice :base :reason :none-good-enough}
           (select-keys (select/choose (concat base worse) :base [:cheap]) [:choice :reason])))))

(deftest a-pilot-chooses-on-estimates-a-held-out-run-proves
  ;; noisy but equal on average, 30% cheaper: good enough to try, not proven
  (let [base (entries :base 12 #(if (even? %) 1.0 0.0) (constantly 100))
        noisy (entries :lean 12 #(if (zero? (mod % 3)) 1.0 0.4) (constantly 70))
        all (concat base noisy)]
    (is (= :lean (:choice (select/choose all :base [:lean]))) "on the estimate")
    (is (= :base (:choice (select/choose all :base [:lean] {:rule :non-inferior})))
        "not proven non-inferior at twelve worlds")))
