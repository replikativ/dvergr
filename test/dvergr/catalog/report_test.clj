(ns dvergr.catalog.report-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dvergr.catalog.report :as report]))

(def ^:private result
  {:scorecard {:scorecard/entries
               (vec (concat
                     (for [i (range 4)]
                       {:attempt/id (keyword (str "a" i)) :candidate/id :luna :passed? (< i 3) :reward (if (< i 3) 1.0 0.5)
                        :spend {:notional-microdollars 2000 :tokens {:input 900 :output 100}}})
                     (for [i (range 4)]
                       {:attempt/id (keyword (str "b" i)) :candidate/id :haiku :passed? (< i 1) :reward (if (< i 1) 1.0 0.0)
                        :spend {:notional-microdollars 6000 :tokens {:input 1800 :output 200}}})))}
   :receipts (vec (concat
                   (for [i (range 4)] {:attempt/id (keyword (str "a" i)) :attempt/elapsed-ms 20000
                                       :attempt/checks {:answer-present true :bu (< i 3) :gegenkonto true}})
                   (for [i (range 4)] {:attempt/id (keyword (str "b" i)) :attempt/elapsed-ms 40000
                                       :attempt/checks {:answer-present true :bu (< i 1) :gegenkonto (< i 2)}})))})

(deftest the-frontier-and-where-answers-fail
  (let [rs (into {} (map (juxt :candidate identity)) (report/rows result))]
    (is (= [4 3] ((juxt :n :passes) (rs :luna))))
    (is (= 0.875 (:reward (rs :luna))))
    (is (= 2000 (:notional-per-attempt (rs :luna))))
    (is (= 24000 (:notional-per-pass (rs :haiku))) "four attempts' cost over one pass")
    (is (= {:bu 3 :gegenkonto 2} (:failed-checks (rs :haiku))))
    (is (= 20.0 (:median-seconds (rs :luna)))))
  (let [md (report/markdown {:title "Bank booking" :result result
                             :certification {:cases 264 :certified 259 :by-reason {:certified 259 :duplicate-id 2 :unlabelled 1}}})]
    (is (str/includes? md "| luna | 4 | 3 (75%) |"))
    (is (str/includes? md "| bu | 3 / 4 | 1 / 4 |") "haiku, then luna")
    (is (str/includes? md "259 of 264 cases could grade an answer; the others were excluded: 2 duplicate id, 1 unlabelled"))))
