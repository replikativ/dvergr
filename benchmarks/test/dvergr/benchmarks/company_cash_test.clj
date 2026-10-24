(ns dvergr.benchmarks.company-cash-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [dvergr.benchmarks.company-cash.generate :as generate]
            [dvergr.benchmarks.company-cash.kernel :as kernel]
            [dvergr.benchmarks.company-cash.scoring :as scoring]
            [dvergr.benchmarks.company-cash.reference :as reference]
            [dvergr.catalog.room :as catalog])
  (:import [java.security MessageDigest]))

(def ^:private regression-cases
  (delay (into {} (for [[id spec] generate/regression-specs]
                    [id (generate/generate-case id spec)]))))

(defn- sha256 [text]
  (apply str (map #(format "%02x" (bit-and 255 %))
                  (.digest (MessageDigest/getInstance "SHA-256") (.getBytes ^String text "UTF-8")))))

(deftest historical-cases-are-reproduced-from-versioned-source
  (let [expected (edn/read-string (slurp (io/resource "dvergr/benchmarks/company_cash/regression-hashes.edn")))]
    (is (= 20 (count expected)))
    (doseq [[id {:keys [gold files]}] @regression-cases]
      (is (= (get expected (str id "/gold.edn")) (sha256 (str (pr-str gold) "\n"))) id)
      (doseq [[name content] files]
        (is (= (get expected (str id "/fixtures/inputs/" name)) (sha256 content)) [id name])))))

(deftest exported-effect-free-checker-and-native-scorer-agree
  (let [source (generate/checker-source)]
    (doseq [[id {:keys [gold]}] @regression-cases
            answer [(generate/reference-answer gold)
                    (assoc (generate/reference-answer gold) :cash {:p10 1.0e12 :p50 1.0e12 :p90 1.0e12})]]
      (let [input {:gold gold :files {"/out/answer.edn" (pr-str answer)}}
            native (scoring/check input)
            interpreted (catalog/run-checker source input)]
        (is (= (:checks native) (:checks interpreted)) id)
        (is (= (:reward native) (:reward interpreted)) id)))))

(deftest generated-businesses-have-disjoint-identities-and-public-observations
  (let [dev (generate/varied-spec :development 0 20261024)
        held-out (generate/varied-spec :held-out 0 20261024)
        case (generate/generate-case "development-000" dev)
        observation (:observations case)]
    (is (= dev (generate/varied-spec :development 0 20261024)))
    (is (not= (:seed dev) (:seed held-out)))
    (is (empty? (set/intersection (set (:names dev)) (set (:names held-out)))))
    (is (= #{:history :company :notes} (set (keys observation))))
    (is (not-any? #(contains? (:company observation) %) [:truth :pooled :oracle :payment-odds :seed]))
    (is (every? #(or (= :written-off (:outcome %)) (number? (:days-late %))) (:history observation)))
    (is (= "development-000" (:case (generate/calibration case))))))

(deftest all-factor-outcomes-conserve-cash-independently-of-payment-parameters
  (let [g (:gold (get @regression-cases "dev-runway"))
        plan (zipmap (map :id (:invoices g)) (repeat :factor))
        a (kernel/simulate g {} plan)
        b (kernel/simulate (assoc g :seed 991) {} plan)
        expected (- (+ (:cash g) (* (- 1.0 (:factoring-fee g)) (reduce + (map :amount (:invoices g)))))
                    (reduce + (:outflows g)))]
    (is (= a b))
    (is (every? #(< (Math/abs (- expected (:final-cash %))) 1.0e-6) a))))

(deftest reference-inference-is-seeded-and-has-finite-normalized-weights
  (let [history (get-in @regression-cases ["dev-studio" :observations :history])
        infer (fn [] (let [r (java.util.Random. 71)] (reference/posterior history 8 #(.nextDouble r))))
        posterior (infer)]
    (is (= posterior (infer)))
    (is (< (Math/abs (- 1.0 (reduce + (:weights posterior)))) 1.0e-10))
    (is (<= 1.0 (:ess posterior) 8.000001))))

(deftest sparse-unusable-generation-is-rejected-before-nonfinite-baselines
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"paid untreated"
                        (generate/pooled [{:customer "C" :action :reminder :outcome :paid :paid -10 :due -20}]
                                         {"C" {}}))))

(deftest unobserved-customers-retain-the-reference-prior
  (let [r (java.util.Random. 7)
        posterior (reference/posterior [] ["new-customer"] 4 #(.nextDouble r))
        world (reference/draw-world posterior #(.nextDouble r))]
    (is (= #{"new-customer"} (set (keys (:customers world)))))
    (is (Double/isFinite (get-in world [:customers "new-customer" :mu])))
    (is (pos? (get-in world [:customers "new-customer" :sigma])))))
