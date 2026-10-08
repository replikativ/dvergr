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
                   (for [i (range 4)] {:attempt/id (keyword (str "a" i)) :attempt/elapsed-ms 20000 :attempt/started-at (* i 10000)
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
    (is (= 20.0 (:median-seconds (rs :luna))))
    (is (= [900 100] ((juxt :input-per-attempt :output-per-attempt) (rs :luna))))
    (is (= 80.0 (/ (:total-seconds (rs :luna)) 1))) "four attempts of 20 s")
  (let [md (report/markdown {:title "Bank booking" :result result
                             :certification {:cases 264 :certified 259 :by-reason {:certified 259 :duplicate-id 2 :unlabelled 1}}})]
    (is (str/includes? md "| luna | 4 | 3 (75%) |"))
    (is (str/includes? md "| luna | 900 | 100 | – | 20 s | 20 s | 1.3 min | 0.8 min | $0.0080 |")
        "no cache split recorded: unknown, not 0%; four 20 s attempts started 10 s apart: 50 s on the clock")
    (is (str/includes? md "| bu | 3 / 4 | 1 / 4 |") "haiku, then luna")
    (is (str/includes? md "259 of 264 cases could grade an answer; the others were excluded: 2 duplicate id, 1 unlabelled"))))

(deftest an-unfinished-experiment-says-so
  ;; a cell that never reached a verdict leaves no Scorecard: the report said
  ;; nothing at all, its tables empty
  (let [md (report/markdown {:title "t" :result {:scorecard {:incomplete {:cells 1 :faults 1}}}})]
    (is (str/includes? md "**Incomplete:** 1 cell did not finish"))))

(deftest a-paid-candidate-costs-what-it-was-billed
  ;; a Fireworks candidate's spend has no notional figure, only the bill; the
  ;; report showed it as $0
  (let [entry (fn [id md] {:candidate/id :fireworks :attempt/id id :passed? true :reward 1.0
                           :spend {:microdollars md :tokens {:input 900 :output 100}}})
        rs (into {} (map (juxt :candidate identity))
                 (report/rows {:scorecard {:scorecard/entries [(entry 1 2862) (entry 2 1138)]} :receipts []}))]
    (is (= 2000 (:notional-per-attempt (rs :fireworks))))))
