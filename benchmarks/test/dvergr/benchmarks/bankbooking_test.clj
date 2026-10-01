(ns dvergr.benchmarks.bankbooking-test
  "The bank-booking case pack: bookings → a DATEV EXTF Buchungsstapel →
   Kontor's importer → a certified bundle whose checker is calibrated."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.benchmarks.bankbooking :as bb]
            [dvergr.catalog.room :as room]
            [kontor.import-datev.buchungsstapel :as bs]))

(deftest a-year-of-bookings-round-trips-through-datev
  (let [books (bb/bookings {:noise false})
        text (bb/extf {} books)
        {:keys [header bookings]} (bs/parse-buchungsstapel text)]
    (is (str/starts-with? text "EXTF;510;21;Buchungsstapel;7;"))
    (is (= 2025 (:fiscal-year header)))
    (is (= (count books) (count bookings)))
    (is (= (mapv (juxt :gegenkonto #(if (= "-" (:bu %)) nil (:bu %))) books)
           (mapv (juxt :gegenkonto :bu-schluessel) bookings))
        "contra accounts and tax keys survive")
    (is (= (mapv :amount books) (mapv :amount bookings)) "signed as the bank sees them")))

(deftest the-case-pack-certifies-what-can-grade
  (let [{:keys [files certification]} (bb/case-pack (bb/extf {} (bb/bookings {})))]
    (is (= {:certified 259 :conflicting-outcomes 2 :duplicate-id 2 :unlabelled 1} (:by-reason certification))
        "a real history's defects are found and named")
    (let [b (room/bundle "bank-booking" files)]
      (is (contains? (:fixtures b) "/docs/kontenrahmen.txt"))
      (is (:ok? (room/calibrate b)))
      (testing "the checker grades the contra account and the tax key"
        (let [[_ c] (first (:cases b))
              v #(room/run-checker (:checker b) {:files {"/out/answer.edn" (pr-str %)} :gold (:gold c)
                                                 :params (get-in b [:definition :params])})]
          (is (= 1.0 (:reward (v (get-in c [:gold :expected])))))
          (is (= 0.5 (:reward (v (assoc (get-in c [:gold :expected]) "bu" "3"))))))))))
