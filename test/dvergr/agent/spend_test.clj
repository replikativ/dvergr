(ns dvergr.agent.spend-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.spend :as spend]
            [dvergr.chat.accounting :as acct]))

(def ^:private priced-model
  ;; a registry model with a price
  (let [id "claude-sonnet-4-5"]
    (assert (acct/get-model-pricing id) "the test model has a registry price")
    id))

(deftest a-provider-usage-record-is-priced-with-the-registry
  (let [usage {:input-tokens 1000 :output-tokens 100 :cache-read-tokens 10}
        s (spend/of-usage priced-model usage)]
    (is (= {:input 1000 :output 100 :cache-read 10} (:tokens s)))
    (is (true? (:priced? s)))
    (is (= (+ (acct/calculate-cost :input-tokens 1000 {:model priced-model})
              (acct/calculate-cost :output-tokens 100 {:model priced-model})
              (acct/calculate-cost :cache-read-tokens 10 {:model priced-model}))
           (:microdollars s)))
    (is (= (:microdollars s) (get-in s [:by-model priced-model :microdollars])))))

(deftest an-unknown-model-is-counted-but-not-priced
  (let [s (spend/of-usage "no-such-model" {:input-tokens 5 :output-tokens 5})]
    (is (= {:input 5 :output 5} (:tokens s)))
    (is (= 0 (:microdollars s)))
    (is (false? (:priced? s)) "no invented price")))

(deftest a-chat-budget-is-taken-as-accounted
  (let [s (spend/of-budget priced-model {:used 4200 :by-type {:input-tokens 300 :output-tokens 40}})]
    (is (= 4200 (:microdollars s)))
    (is (= {:input 300 :output 40} (:tokens s)))
    (is (true? (:priced? s)))))

(deftest roles-fold-and-an-unpriced-role-marks-the-total
  (let [s (spend/of-roles {:agent priced-model}
                          {:agent {:used 1000 :by-type {:input-tokens 100}}
                           :customer {:input-tokens 50 :output-tokens 5}})]
    (is (= 1000 (:microdollars s)) "the customer's model is unknown: counted, not priced")
    (is (= {:input 150 :output 5} (:tokens s)))
    (is (false? (:priced? s)))
    (is (= #{priced-model} (set (keys (:by-model s)))))))

(deftest totals-fold-and-nil-is-nothing
  (let [a (spend/of-budget priced-model {:used 10 :by-type {:input-tokens 1}})
        b (spend/of-budget priced-model {:used 20 :by-type {:input-tokens 2}})
        t (spend/total [a nil b])]
    (is (= 30 (:microdollars t)))
    (is (= {:input 3} (:tokens t)))
    (is (= 30 (get-in t [:by-model priced-model :microdollars])))
    (is (= spend/zero (spend/total [])))))

(deftest a-subscription-call-is-free-and-worth-its-list-price
  (let [usage {:input-tokens 6511 :output-tokens 23298}
        paid (spend/of-usage "claude-haiku-4-5" usage)
        sub (spend/of-usage "claude-code-haiku" usage)]
    (is (= 0 (:microdollars sub)) "the bill is what was paid")
    (is (true? (:priced? sub)))
    (is (pos? (:notional-microdollars sub)))
    (is (= (:microdollars paid) (:notional-microdollars sub) (spend/notional-microdollars sub))
        "worth what the API model would have cost")
    (is (= (:microdollars paid) (spend/notional-microdollars paid)) "a paid call is worth its cost")
    (is (not (contains? paid :notional-microdollars)))
    (testing "a budget already accounted, too"
      (let [b (spend/of-budget "codex-subscription-luna" {:used 0 :by-type usage})]
        (is (= 0 (:microdollars b)))
        (is (= (:microdollars (spend/of-usage "gpt-5.6-luna" usage)) (:notional-microdollars b)))))))

(deftest folding-keeps-old-spends-as-they-were
  (let [paid (spend/of-usage "claude-haiku-4-5" {:input-tokens 1000 :output-tokens 100})
        sub (spend/of-usage "claude-code-haiku" {:input-tokens 1000 :output-tokens 100})]
    (is (not (contains? (spend/total [paid paid]) :notional-microdollars))
        "spends recorded before notional costs fold exactly as before")
    (is (= (* 2 (:microdollars paid)) (:notional-microdollars (spend/total [paid sub])))
        "a mixed fold is worth the paid cost plus the subscription's list price")
    (is (= (:microdollars paid) (:microdollars (spend/total [paid sub]))))))
