(ns dvergr.catalog.report-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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

(deftest unreported-and-mixed-resources-remain-unknown-in-rows-and-markdown
  (let [unknown {:microdollars 0 :priced? false :tokens {}}
        known {:microdollars 20 :priced? true :tokens {:input 10 :output 2 :cache-read 3}}
        result {:scorecard {:scorecard/entries
                            [{:attempt/id :a :candidate/id :mixed :passed? true :reward 1.0 :spend known}
                             {:attempt/id :b :candidate/id :mixed :passed? false :reward 0.0 :spend unknown}
                             {:attempt/id :c :candidate/id :zero :passed? true :reward 1.0
                              :spend {:microdollars 0 :priced? true :tokens {:input 0 :output 0}}}]}}
        rows (into {} (map (juxt :candidate identity)) (report/rows result))
        md (report/markdown {:title "Unknown usage" :result result})]
    (is (nil? (:notional-total (rows :mixed))))
    (is (nil? (:notional-per-attempt (rows :mixed))))
    (is (nil? (:notional-per-pass (rows :mixed))))
    (is (nil? (:tokens-per-attempt (rows :mixed))))
    (is (nil? (:cache-share (rows :mixed))))
    (is (= 0 (:notional-total (rows :zero))))
    (is (= 0 (:tokens-per-attempt (rows :zero))))
    (is (str/includes? md "| mixed | 2 | 1 (50%) |"))
    (is (str/includes? md "| mixed | – | – | – | – | – | – | – | – |"))
    (is (str/includes? md "never as measured zero"))
    (testing "a missing cache split in one otherwise reported attempt is unknown"
      (let [r (report/rows {:scorecard {:scorecard/entries
                                        [{:candidate/id :a :spend known}
                                         {:candidate/id :a :spend (update known :tokens dissoc :cache-read)}]}})]
        (is (nil? (:cache-share (first r))))))
    (testing "an unreported judge is present but not priced at zero"
      (let [r (assoc result :receipts [{:attempt/id :a :attempt/metrics {:judge-spend unknown}}])
            row (first (filter #(= :mixed (:candidate %)) (report/rows r)))
            md (report/markdown {:result r})]
        (is (:judge-recorded? row))
        (is (nil? (:judge-notional-total row)))
        (is (str/includes? md "| **judge, all** | – | – | |"))))))

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

(deftest a-judge-s-cost-is-the-experiment-s-not-the-candidate-s
  (let [judged (update result :receipts
                       (fn [rs] (mapv #(cond-> %
                                         (= "a" (subs (name (:attempt/id %)) 0 1))
                                         (assoc-in [:attempt/metrics :judge-spend]
                                                   {:microdollars 0 :notional-microdollars 500}))
                                      rs)))
        rs (into {} (map (juxt :candidate identity)) (report/rows judged))
        md (report/markdown {:title "t" :result judged})]
    (is (= 2000 (:notional-per-attempt (rs :luna))) "the candidate's cost leaves the judge out")
    (is (= [2000 0] ((juxt :judge-notional-total :judge-billed-total) (rs :luna))))
    (is (nil? (:judge-notional-total (rs :haiku))) "no judge recorded: none shown")
    (is (str/includes? md "### Judge"))
    (is (str/includes? md "| luna | $0.0020 | $0.0000 | $0.0005 |"))
    (is (str/includes? md "| **judge, all** | $0.0020 | $0.0000 | |"))
    (is (not (str/includes? (report/markdown {:title "t" :result result}) "### Judge"))
        "a report without a judge has no judge section")))

(defn- cell-entries
  "Scorecard entries of `candidate`, one case per character of `s` (1 a pass,
   0 a fail), `reps` repetitions each passing alike."
  ([candidate s] (cell-entries candidate s 1))
  ([candidate s reps]
   (vec (for [[i ch] (map-indexed vector s) r (range reps)]
          {:attempt/id (keyword (str (name candidate) "-" i "-" r)) :candidate/id candidate
           :environment {:environment/content-id (keyword (str "case-" i))} :repetition r
           :passed? (= ch \1) :reward (if (= ch \1) 1.0 0.4) :spend {}}))))

(defn- bits [& runs] (apply str (mapcat (fn [[n ch]] (repeat n ch)) (partition 2 runs))))

(def ^:private paired-result
  ;; the post's example: on 100 cases, A right where B is wrong on 12, B
  ;; right where A is wrong on 2; C differs from B on 5 against 6
  {:scorecard {:scorecard/entries
               (vec (concat (cell-entries :a (bits 50 \1 12 \1 2 \0 36 \0))
                            (cell-entries :b (bits 50 \1 12 \0 2 \1 36 \0))
                            (cell-entries :c (bits 44 \1 6 \0 12 \0 2 \1 5 \1 31 \0))))}
   ;; one of C's cells faulted (a transport error is no verdict)
   :receipts (vec (for [p [:a :b :c] i (range 100) :let [id (keyword (str (name p) "-" i "-0"))]]
                    (if (= id :c-99-0)
                      {:attempt/id id :attempt/status :failed :attempt/metrics {:failure {:kind :transport}}}
                      {:attempt/id id :attempt/status :completed})))})

(deftest the-report-compares-candidates-paired
  (let [{:keys [policy comparisons]} (report/paired paired-result)
        by (into {} (map (juxt (juxt :a :b) identity)) comparisons)]
    (is (= "all-pairs" policy))
    (is (= {:cases 100 :both-passed 50 :a-only 12 :b-only 2 :both-failed 36}
           (select-keys (by ["a" "b"]) [:cases :both-passed :a-only :b-only :both-failed])))
    (is (< (Math/abs (- 0.012939 (:p (by ["a" "b"])))) 1e-5))
    (is (= "a-better" (:verdict (by ["a" "b"]))) "Holm over 3: 0.039, still below 0.05")
    (is (= [99 1] ((juxt :cases :excluded-cases) (by ["b" "c"]))) "C's faulted case is left out of its pairs")
    (is (= [6 5] ((juxt :a-only :b-only) (by ["b" "c"]))))
    (is (= "no-difference" (:verdict (by ["b" "c"])))))
  (let [md (report/markdown {:title "t" :result paired-result})]
    (is (str/includes? md "## Paired comparisons"))
    (is (str/includes? md "| a | b | 100 | 50 | 12 | 2 | 36 | 0.013 | 0.039 | a better |"))
    (is (str/includes? md "no detectable difference at these cases"))
    (is (str/includes? md "b / c: 1 case"))
    (is (not (str/includes? md "Tied")) "one repetition: McNemar's table")))

(deftest paired-with-repetitions-and-a-baseline
  (let [result {:scorecard {:scorecard/entries (vec (concat (cell-entries :a (bits 8 \1 2 \0) 3)
                                                            (cell-entries :b (bits 2 \1 8 \0) 3)))}}
        {:keys [policy comparisons]} (report/paired result {:baseline :a})
        [c] comparisons]
    (is (= "against-baseline" policy))
    (is (= ["b" "a"] ((juxt :a :b) c)) "the baseline is B")
    (is (= [10 3 6 0] ((juxt :cases :repetitions :b-only :a-only) c)) "a case counts once, not three times")
    (is (= "b-better" (:verdict c)) "6 against 0: p = 0.031")
    (is (str/includes? (report/markdown {:title "t" :result result :baseline :a}) "| Tied |")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No candidate z"
                        (report/paired paired-result {:baseline :z})))
  (is (nil? (report/paired result)) "entries without cases: no pairs to show"))

(deftest only-verdicts-are-paired
  (let [entries (vec (concat (cell-entries :a "1111") (cell-entries :b "0000")))
        ok #(hash-map :attempt/id % :attempt/status :completed)
        fault #(hash-map :attempt/id % :attempt/status :failed :attempt/metrics {:failure {:kind :transport}})
        pair #(first (:comparisons (report/paired {:scorecard {:scorecard/entries entries} :receipts %})))]
    (testing "a receipt that could not be loaded is no verdict"
      (is (= [3 1] ((juxt :cases :excluded-cases) (pair (mapv ok [:a-0-0 :a-1-0 :a-2-0 :b-0-0 :b-1-0 :b-2-0 :b-3-0]))))))
    (testing "a case both faulted on is left out, and counted"
      (let [c (pair (into (mapv ok [:a-0-0 :a-1-0 :a-2-0 :b-0-0 :b-1-0 :b-2-0]) (map fault [:a-3-0 :b-3-0])))]
        (is (= [3 1 0] ((juxt :cases :excluded-cases :excluded-cells) c)))))
    (testing "repetitions only one candidate has are left out, and said so"
      (let [result {:scorecard {:scorecard/entries (vec (concat (cell-entries :a "11" 2)
                                                                (remove #(= :b-1-1 (:attempt/id %)) (cell-entries :b "10" 2))))}}
            c (first (:comparisons (report/paired result)))]
        (is (= [2 0 1] ((juxt :cases :excluded-cases :excluded-cells) c)))
        (is (str/includes? (report/markdown {:title "t" :result result}) "a / b: 1 cell without a counterpart"))))))
