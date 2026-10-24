(ns dvergr.benchmarks.company-cash-model-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.benchmarks.company-cash.experiment :as experiment]
            [dvergr.benchmarks.company-cash.generate :as generate]
            [dvergr.benchmarks.company-cash.kernel :as kernel]
            [dvergr.benchmarks.company-cash.model :as model]
            [dvergr.benchmarks.company-cash.reference :as reference]
            [dvergr.catalog.room :as catalog]))

(def ^:private case-data
  (delay (generate/generate-case "dev-runway" (get generate/regression-specs "dev-runway"))))

(def ^:private conservative-model
  "(ns conservative-model)
   (defn fit [observations seed budget] (:company observations))
   (defn simulate [company actions seed n]
     (let [cash (- (+ (:cash company)
                      (reduce + (for [invoice (:invoices company)
                                      :when (= :factor (get actions (:id invoice)))]
                                  (* (- 1.0 (:factoring-fee company)) (:amount invoice)))))
                   (reduce + (:outflows company)))]
       (vec (repeat n {:final-cash cash :final-value cash :ruin? (neg? cash)}))))")

(deftest frozen-model-runs-with-public-data-and-independent-seeds
  (let [case @case-data
        result (model/execute conservative-model (:observations case) {} {:samples 8})]
    (is (= 8 (count (:outcomes result))))
    (is (= 20 (:simulation-count result)))
    (is (string? (:source-id result)))
    (is (= (:outcomes result)
           (:outcomes (model/execute conservative-model (:observations case) {} {:samples 8}))))))

(deftest a-model-that-churns-short-lived-garbage-still-runs
  ;; A Monte Carlo allocates far more than it keeps; the allocation backstop
  ;; must not stop it (a live attempt's model failed at 128 MB in 0.5 s).
  (let [case @case-data
        churning (str/replace conservative-model
                              "(defn fit [observations seed budget] (:company observations))"
                              "(defn fit [observations seed budget]
                                 (dotimes [_ 1500] (count (vec (range 20000))))
                                 (:company observations))")
        result (model/execute churning (:observations case) {} {:samples 8})]
    (is (= 8 (count (:outcomes result))))))

(deftest correct-answer-without-a-model-earns-zero
  (let [case @case-data
        answer (generate/reference-answer (:gold case))
        verdict (model/score-files {"/out/answer.edn" (pr-str answer)} (:gold case) (:observations case) {})]
    (is (zero? (:reward verdict)))
    (is (false? (get-in verdict [:checks :model-executable?])))))

(deftest relative-skill-never-awards-improvement-to-the-baseline
  (is (zero? (model/forecast-skill 10.0 10.0 9.0 1.0e-6)))
  (is (zero? (model/forecast-skill 9.1 9.1 9.0 1.0e-6)) "small baseline gaps still put baseline at zero")
  (is (= 1.0 (model/forecast-skill 9.0 9.1 9.0 1.0e-6)))
  (is (= -1.0 (model/forecast-skill 11.0 10.0 9.0 1.0e-6)))
  (is (nil? (model/forecast-skill 9.0 9.0 9.0 1.0e-6)) "no measurable improvement opportunity")
  (is (nil? (model/forecast-skill 0.2500001 0.2500001 0.25 1.0e-6))))

(deftest model-code-cannot-publish-to-process-global-taps
  (let [payload (keyword (str "model-payload-" (random-uuid)))
        barrier (keyword (str "model-barrier-" (random-uuid)))
        seen (atom [])
        drained (promise)
        handler (fn [value] (swap! seen conj value) (when (= barrier value) (deliver drained true)))]
    (add-tap handler)
    (try
      (is (thrown-with-msg? Exception #"host taps"
                            (model/execute (str "(ns tapping-model) (tap> " payload ")")
                                           (:observations @case-data) {} {:samples 4})))
      ;; The single host tap queue delivers this after any publication the
      ;; model could have made. Coordination uses delivery, never a delay.
      (is (tap> barrier))
      (is (= true (deref drained 5000 ::timeout)))
      (is (not-any? #{payload} @seen))
      (finally (remove-tap handler)))))

(deftest invalid-models-fail-the-executable-contract
  (let [observations (:observations @case-data)]
    (doseq [source ["(ns missing-fit) (defn simulate [& args] [])"
                    "(ns lazy-posterior) (defn fit [& args] (range)) (defn simulate [& args] [])"
                    "(ns bad-output) (defn fit [& args] {}) (defn simulate [& args] [])"
                    "(ns nan-output) (defn fit [& args] {}) (defn simulate [p a s n] (vec (repeat n {:final-cash ##NaN :final-value 0 :ruin? false})))"
                    "(ns impossible-money) (defn fit [& args] {}) (defn simulate [p a s n] (vec (repeat n {:final-cash 1.0e20 :final-value 1.0e20 :ruin? false})))"]]
      (is (thrown? Exception (model/execute source observations {} {:samples 4})) source))
    (testing "a model cannot reach host secrets through namespace loading or Java"
      (doseq [source ["(ns host-access) (System/getenv \"HOME\")"
                      "(ns host-import (:require [clojure.java.shell :as shell]))"]]
        (is (thrown? Exception (model/execute source observations {} {:samples 4})))))
    (testing "nonterminating interpreted work consumes its call allowance"
      (is (thrown-with-msg? Exception #"allowance|deadline"
                            (model/execute "(ns looping) (defn fit [& args] (loop [] (recur))) (defn simulate [& args] [])"
                                           observations {} {:samples 4 :max-calls 1000}))))))

(deftest nonconserving-model-cannot-pass-with-a-good-final-answer
  (let [case @case-data
        source "(ns fake-model) (defn fit [& args] {}) (defn simulate [p a s n] (vec (repeat n {:final-cash 0 :final-value 0 :ruin? false})))"
        files {"/out/model/receivables.clj" source
               "/out/answer.edn" (pr-str (generate/reference-answer (:gold case)))}
        verdict (model/score-files files (:gold case) (:observations case) {})]
    (is (zero? (:reward verdict)))
    (is (re-find #"factoring conservation" (get-in verdict [:metrics :model-error])))))

(deftest raw-predictive-losses-and-decision-results-stay-separate
  (let [case @case-data
        files {"/out/model/receivables.clj" conservative-model
               "/out/answer.edn" (pr-str (generate/reference-answer (:gold case)))}
        verdict (model/score-files files (:gold case) (:observations case) {:samples 8 :scoring-worlds 32})]
    (is (true? (get-in verdict [:checks :model-executable?])))
    (is (number? (get-in verdict [:metrics :model-pinball-loss])))
    (is (number? (get-in verdict [:metrics :model-ruin-brier])))
    (is (= 1.0 (get-in verdict [:metrics :decision :decision])))
    (is (false? (get-in verdict [:checks :model-no-worse-than-pooled?])))
    (is (< (:reward verdict) 1.0) "a strong submitted answer cannot hide a poor simulator")))

(deftest history-only-reference-is-a-self-contained-executable-model
  (let [case @case-data
        result (model/execute (reference/model-source) (:observations case) {} {:samples 8})]
    (is (= 8 (count (:outcomes result))))
    (is (pos? (get-in result [:posterior :posterior :ess])))))

(deftest frozen-model-must-refit-on-unseen-observations
  (let [case @case-data
        probe (-> case (assoc :id :unseen-company)
                  (update-in [:gold :cash] + 1000)
                  (update-in [:observations :company :cash] + 1000))
        files {"/out/answer.edn" (pr-str (generate/reference-answer (:gold case)))
               "/out/model/receivables.clj" conservative-model}
        reusable (model/score-panels files (:gold case) (:observations case)
                                     {:samples 4 :scoring-worlds 16} [probe])
        hardcoded (str/replace conservative-model "(:company observations)"
                               (str "(quote " (pr-str (get-in case [:observations :company])) ")"))
        memorized (model/score-panels (assoc files "/out/model/receivables.clj" hardcoded)
                                      (:gold case) (:observations case)
                                      {:samples 4 :scoring-worlds 16} [probe])]
    (is (true? (get-in reusable [:checks :model-transfer-executable?])))
    (is (= 2 (get-in reusable [:metrics :model-panel-count])))
    (is (false? (get-in memorized [:checks :model-transfer-executable?])))
    (is (zero? (:reward memorized)))))

(deftest transfer-forecast-failure-appears-in-the-aggregate-checks
  (with-redefs [model/score-files
                (fn [_ _ observations _]
                  (let [score (if (:original? observations) 0.8 0.0)]
                    {:checks {:model-executable? true :model-cash-skilful? (>= score 0.5)}
                     :metrics {:model-score score :model-cash-skill score
                               :model-ruin-skill nil :baseline-model-score 0.5 :decision-reward 1.0}}))]
    (let [result (model/score-panels {} {} {:original? true} {}
                                     [{:id :transfer :gold {} :observations {}}])]
      (is (= 0.4 (get-in result [:metrics :model-score])))
      (is (false? (get-in result [:checks :model-cash-skilful?])))
      (is (false? (get-in result [:checks :model-transfer-cash-skilful?]))))))

(deftest wrong-ruin-forecasts-are-penalized-when-pooled-baseline-is-already-correct
  (let [case @case-data
        ;; A fully factored contract still reports ruin correctly. Only the
        ;; no-action forecasts are deliberately reversed in the damaged model.
        wrong (str/replace conservative-model ":ruin? (neg? cash)"
                           ":ruin? (if (every? #{:factor} (vals actions)) (neg? cash) true)")
        gold (let [g (assoc (:gold case) :cash 1000000)]
               (assoc g :oracle (generate/score g (get-in g [:oracle :plan])) :idle (generate/score g {})))
        base-files {"/out/answer.edn" (pr-str (generate/reference-answer gold))}
        observations (assoc-in (:observations case) [:company :cash] 1000000)
        correct (model/score-files (assoc base-files "/out/model/receivables.clj" conservative-model)
                                   gold observations {:samples 4 :scoring-worlds 16})
        damaged (model/score-files (assoc base-files "/out/model/receivables.clj" wrong)
                                   gold observations {:samples 4 :scoring-worlds 16})]
    (is (nil? (get-in damaged [:metrics :model-ruin-skill])))
    (is (= 0.0 (get-in damaged [:metrics :model-ruin-quality])))
    (is (= 1.0 (get-in correct [:metrics :model-ruin-quality])))
    (is (< (:reward damaged) (:reward correct)))))

(deftest artifact-experiment-selects-a-split-and-versions-evaluation-policy
  (let [bundle (catalog/read-dir "examples/workflows/company-cash")
        opts {:models ["codex-subscription-sol-6.1@medium"] :split :development :cases 1}
        a (experiment/experiment-plan bundle opts)
        b (experiment/experiment-plan bundle (assoc opts :model-options {:scoring-seed 991}))
        env (first (:environments a))]
    (is (= (generate/checker-source) (:checker bundle)))
    (is (= 1 (count (:environments a))))
    (is (= "dev-studio" (get-in env [:environment/metadata :case])))
    (is (= ["/out/answer.edn" "/out/model/receivables.clj"]
           (get-in env [:environment/limits :required-outputs])))
    (is (not= (:environment/verifier env) (:environment/verifier (first (:environments b)))))
    (is (thrown? Exception (experiment/experiment-plan bundle (assoc opts :split :missing))))))

(deftest owned-observer-evaluates-frozen-source-and-independent-transfer-business
  (let [bundle (catalog/read-dir "examples/workflows/company-cash")
        plan (experiment/experiment-plan bundle
                                         {:models ["codex-subscription-sol-6.1@medium"]
                                          :split :development :cases 1
                                          :model-options {:samples 4 :scoring-worlds 16}})
        environment (first (:environments plan))
        ev (get-in plan [:capabilities :evaluator])
        gold (catalog/gold-of bundle environment)
        files {"/out/answer.edn" (pr-str (generate/reference-answer gold))
               "/out/model/receivables.clj" conservative-model}
        evidence ((:observe ev) {:environment environment :result {:run/status :completed}
                                 :default {} :execution/evidence {:files files}})
        verdict ((:verify ev) environment evidence)]
    (is (= files (:files evidence)))
    (is (= 2 (get-in verdict [:metrics :model-panel-count])))
    (is (true? (get-in verdict [:checks :model-transfer-executable?])))
    (is (<= 0.0 (:reward verdict) 1.0))))
