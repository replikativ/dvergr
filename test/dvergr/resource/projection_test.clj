(ns dvergr.resource.projection-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.resource.projection :as projection]))

(defn- book [purposes commands]
  (let [accounts (into {} (map (fn [[id purpose]]
                                 [id {:kind (if (#{:source :sink} purpose) purpose :wallet)
                                      :purpose (when-not (#{:source :sink} purpose) purpose)
                                      :run-id (when (= :run purpose) id) :balance {}}])) purposes)
        transfers (mapv (fn [[kind source destination amount]]
                          {:id (random-uuid) :kind kind :source source :destination destination
                           :resources {"microUSD" (bigdec amount)}}) commands)
        accounts (reduce (fn [out {:keys [source destination resources]}]
                           (-> out
                               (update-in [source :balance "microUSD"] (fnil - 0M) (resources "microUSD"))
                               (update-in [destination :balance "microUSD"] (fnil + 0M) (resources "microUSD"))))
                         accounts transfers)]
    {:accounts accounts :transfers transfers}))

(defn- spend [book ids] (projection/spend-by-run book ids))

(deftest worlds-are-available-while-reservations-and-holds-are-committed
  (let [types {:room :room :a :run :w :world :res :reservation :hold :hold :sink :sink}
        setup [[:grant :room :a 100] [:grant :a :w 30]]]
    (is (= {} (spend (book types setup) #{:a})))
    (is (= {:a {"microUSD" 7M}}
           (spend (book types (conj setup [:consume :w :sink 7])) #{:a})))
    (is (= {:a {"microUSD" 20M}}
           (spend (book types (conj setup [:grant :w :res 20])) #{:a})))
    (is (= {:a {"microUSD" 7M}}
           (spend (book types (into setup [[:grant :w :res 20] [:grant :res :hold 7]
                                           [:return :res :w 13]])) #{:a})))
    (is (= {:a {"microUSD" 20M}}
           (spend (book types (into setup [[:grant :w :res 20] [:consume :res :sink 20]])) #{:a})))))

(deftest each-run-is-its-own-attribution-boundary
  (let [view (book {:room :room :a :run :wa :world :b :run :wb :world :sink :sink}
                   [[:grant :room :a 100] [:grant :a :wa 60] [:consume :wa :sink 3]
                    [:grant :wa :b 40] [:grant :b :wb 20] [:consume :wb :sink 5]])]
    (is (= {:a {"microUSD" 3M} :b {"microUSD" 5M}} (spend view #{:a :b})))
    (is (= {:a {"microUSD" 3M}} (spend view #{:a})))))

(deftest mixed-funding-is-not-invented-attribution
  (doseq [second-source [:room :b]]
    (let [view (book {:room :room :a :run :b :run :w :world :sink :sink}
                     [[:grant :room :a 100] [:grant :room :b 100]
                      [:grant :a :w 20] [:grant second-source :w 10] [:consume :w :sink 5]])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ambiguous funding"
                            (spend view #{:a}))))))

(deftest refunds-and-resume-do-not-propagate-ownership-backwards
  (let [view (book {:room :room :r :run :p :run :s :run :sink :sink}
                   [[:grant :room :r 100] [:grant :r :p 100] [:consume :p :sink 20]
                    [:grant :p :s 80] [:consume :s :sink 10] [:return :s :r 70]])]
    (is (= {:p {"microUSD" 20M} :s {"microUSD" 10M}} (spend view #{:r :p :s})))))

(deftest escrow-claim-keeps-original-funding-provenance
  (let [view (book {:room :room :a :run :w :world :esc :escrow :claimed :world :sink :sink}
                   [[:grant :room :a 100] [:grant :a :w 30] [:grant :w :esc 30]
                    [:grant :esc :claimed 30] [:consume :claimed :sink 7]
                    [:return :claimed :room 23]])]
    (is (= {:a {"microUSD" 7M}} (spend view #{:a})))))

(deftest same-run-reservations-may-fund-one-hold
  (let [view (book {:room :room :a :run :r1 :reservation :r2 :reservation :h :hold}
                   [[:grant :room :a 100] [:grant :a :r1 20] [:grant :a :r2 30]
                    [:grant :r1 :h 20] [:grant :r2 :h 30]])]
    (is (= {:a {"microUSD" 50M}} (spend view #{:a})))))

(deftest unrelated-refunds-into-worlds-are-unsupported
  (let [view (book {:room :room :other :room :a :run :w :world}
                   [[:grant :room :a 100] [:grant :a :w 30] [:return :other :w 10]])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"funding ancestry" (spend view #{:a})))))

(deftest legacy-wallets-are-explicitly-unclassified
  (testing "an existing unclassified Run wallet is not an unfunded Run"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"classification"
                          (spend (book {:a nil} []) #{:a}))))
  (testing "an unclassified funded world cannot silently understate spend"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"classified wallet"
                          (spend (book {:room :room :a :run :w nil}
                                       [[:grant :room :a 100] [:grant :a :w 30]]) #{:a}))))
  (is (= {} (spend (book {:unrelated nil} []) #{:unfunded}))))
