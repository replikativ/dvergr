(ns dvergr.agent.experiment.stats-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.experiment.stats :as stats]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-5))

(deftest beta-quantiles-match-a-reference
  ;; scipy.stats.beta.ppf
  (is (close? 0.223529 (stats/beta-quantile 5.5 5.5 0.025)))
  (is (close? 0.776471 (stats/beta-quantile 5.5 5.5 0.975)))
  (is (close? 0.217196 (stats/beta-quantile 0.5 10.5 0.975)))
  (is (close? 0.618685 (stats/beta-quantile 9.5 1.5 0.025)))
  (is (close? 0.385728 (stats/beta-quantile 2 3 0.5)))
  (is (close? 0.3 (stats/beta-cdf 1 1 0.3)) "Beta(1,1) is uniform"))

(deftest a-pass-rate-comes-with-its-range
  (is (nil? (stats/pass-rate-interval 0 0)))
  (let [[lo hi] (stats/pass-rate-interval 5 10)]
    (is (close? 0.223529 lo))
    (is (close? 0.776471 hi)))
  (testing "at 0 and n passes the interval reaches the bound"
    (is (= 0.0 (first (stats/pass-rate-interval 0 10))))
    (is (close? 0.217196 (second (stats/pass-rate-interval 0 10))))
    (is (= 1.0 (second (stats/pass-rate-interval 4 4)))))
  (testing "more attempts, a narrower range"
    (let [width (fn [[lo hi]] (- hi lo))]
      (is (< (width (stats/pass-rate-interval 30 40)) (width (stats/pass-rate-interval 3 4)))))))

(deftest a-mean-reward-comes-with-its-range
  (is (nil? (stats/mean-interval [])))
  (is (nil? (stats/mean-interval [0.7])) "one attempt says nothing about spread")
  (is (= [0.5 0.5] (stats/mean-interval [0.5 0.5 0.5])))
  (let [[lo hi] (stats/mean-interval [0.6 0.8])]
    ;; mean 0.7, sd 0.1414, t(1) = 12.706: clipped to [0, 1]
    (is (= 0.0 lo))
    (is (= 1.0 hi)))
  (let [[lo hi] (stats/mean-interval [0.6 0.7 0.8 0.7 0.6 0.8 0.7 0.7])]
    (is (< 0.6 lo 0.7 hi 0.8))))
