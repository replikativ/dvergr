(ns dvergr.agent.experiment.preflight-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.experiment.preflight :as preflight]))

(defn- cell [candidate env-id content rep]
  {:candidate/id candidate
   :environment {:environment/id env-id :environment/content-id content}
   :repetition rep})

(deftest the-pilot-is-one-cell-per-candidate-per-stratum
  (let [cells (for [c [:a :b]
                    [id content] [[:bench.sales/t1 1] [:bench.sales/t2 2] [:bench.hr/t3 3]]
                    r [0 1]]
                (cell c id content r))
        pilot (preflight/pilot-cells cells)]
    (is (= #{[:a 1] [:a 3] [:b 1] [:b 3]} pilot)
        "the first environment of each stratum, first repetition, per candidate")
    (is (nil? ((preflight/pilot-admit pilot) (cell :a :bench.sales/t1 1 0))))
    (is (some? ((preflight/pilot-admit pilot) (cell :a :bench.sales/t1 1 1)))
        "a pilot environment's later repetitions are not the pilot")
    (is (= :preflight-pilot (:reason ((preflight/pilot-admit pilot) (cell :a :bench.sales/t2 2 0)))))))

(defn- receipt [candidate usd tokens ms]
  {:attempt/elapsed-ms ms
   :attempt/metrics {:experiment-candidate candidate
                     :spend {:microdollars 0 :notional-microdollars (* usd 1e6)
                             :tokens {:input tokens :output 0}}}})

(deftest the-estimate-extrapolates-per-candidate-and-bounds-the-tail
  (let [est (preflight/estimate
             {:pilot-receipts [(receipt :cheap 0.01 1000 1000) (receipt :cheap 0.03 3000 3000)
                               (receipt :dear 1.0 50000 10000)]
              :remaining {:cheap 10 :dear 4} :parallelism 2
              :window-points 0.0})]
    (testing "expected is the pilot mean, per candidate, times the cells left"
      (is (< (Math/abs (- 200000.0 (get-in est [:candidates :cheap :list-microdollars :expected]))) 1e-6))
      (is (= 4000000.0 (get-in est [:candidates :dear :list-microdollars :expected]))))
    (testing "the conservative bound is above the mean, the worst seen at the dearest cell"
      (is (> (get-in est [:candidates :cheap :list-microdollars :conservative]) 200000.0))
      (is (< (Math/abs (- 300000.0 (get-in est [:candidates :cheap :list-microdollars :worst-seen]))) 1e-6))
      (is (= 8000000.0 (get-in est [:candidates :dear :list-microdollars :conservative]))
          "one sample: doubled"))
    (testing "wall time divides by the parallelism"
      (is (= (/ (+ (* 10 2000.0) (* 4 10000.0)) 2) (get-in est [:wall-ms :expected]))))
    (testing "a pilot that moved no whole point counts as one point: an upper bound"
      (is (pos? (get-in est [:window-points :expected]))))
    (testing "the gate compares the conservative bound with the budget"
      (is (nil? (preflight/gate est {:dollars 1.0})) "billed dollars: a subscription bills none")
      (is (contains? (preflight/gate est {:subscription 0.05}) :subscription))
      (is (nil? (preflight/gate est {:subscription 100.0}))))))
