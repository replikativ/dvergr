(ns dvergr.model.subscription-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.model.subscription :as subscription]))

(def ^:private codex-headers
  ;; as the Codex backend sends them with every response
  {"x-codex-plan-type" "pro"
   "x-codex-primary-used-percent" "40"
   "x-codex-primary-window-minutes" "10080"
   "x-codex-primary-reset-at" "1791066632"
   "x-codex-secondary-used-percent" "0"
   "x-codex-secondary-window-minutes" "0"
   "x-codex-secondary-reset-at" ""})

(deftest codex-reports-its-weekly-window
  (is (= {:plan "pro"
          :windows {:primary {:used 0.4 :window-minutes 10080 :resets-at-ms 1791066632000}}}
         (subscription/codex-reading codex-headers))
      "a window of zero minutes is no window")
  (is (nil? (subscription/codex-reading {"content-type" "text/event-stream"}))))

(deftest the-governor-stops-new-cells-at-the-allowance-or-a-full-window
  (let [p ::test-provider
        at (fn [u] (subscription/observe! p {:windows {:primary {:used u :window-minutes 10080}}}))]
    (testing "counted from the first reading after it is made"
      (subscription/forget! p)
      (let [admit (subscription/governor {:share 0.10 :pause-at 0.80} #{p})]
        (is (nil? (admit)) "no reading yet: nothing to refuse")
        (at 0.40)
        (is (nil? (admit)) "the first reading is the baseline")
        (at 0.49)
        (is (nil? (admit)))
        (at 0.50)
        (is (= {:reason :allowance-used :window :primary :share 0.10}
               (select-keys (admit) [:reason :window :share])))))
    (testing "a window at the pause mark refuses whoever used it"
      (at 0.79)
      (let [admit (subscription/governor {:share 0.10 :pause-at 0.80} #{p})]
        (is (nil? (admit)))
        (at 0.80)
        (is (= :window-full (:reason (admit))))))
    (testing "an unmetered provider is never refused"
      (is (nil? ((subscription/governor {:share 0.0 :pause-at 0.0} #{::no-meter})))))
    (subscription/forget! p)))

(deftest runs-calibrate-what-a-token-costs
  (let [f (java.io.File/createTempFile "calibration" ".edn")]
    (.delete f)
    (binding [subscription/*calibration-file* (str f)]
      (is (nil? (subscription/points-per-token ::p)) "nothing recorded")
      (subscription/record-run! ::p {:tokens 10000000 :points 0.0 :cells 100})
      (subscription/record-run! ::p {:tokens 30000000 :points 0.02 :cells 300})
      (subscription/record-run! ::p {:tokens 0 :points 0.01})
      (subscription/record-run! ::other {:tokens 5 :points 0.5})
      (let [{:keys [points-per-token tokens points runs]} (subscription/points-per-token ::p)]
        (is (= [40000000 2] [tokens runs]) "an empty run records nothing; providers apart")
        (is (< (Math/abs (- 0.02 points)) 1e-12))
        (is (< (Math/abs (- (/ 0.03 40000000) points-per-token)) 1e-18)
            "the unseen part of a point is counted once: it errs high")))
    (.delete f)))
