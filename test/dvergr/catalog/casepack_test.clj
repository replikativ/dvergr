(ns dvergr.catalog.casepack-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dvergr.catalog.casepack :as cp]
            [dvergr.catalog.room :as room]))

(deftest csv-as-exports-write-it
  (is (= [["a" "b"] ["1" "x;y"]] (cp/parse-csv "a,b\n1,\"x;y\"\n")))
  (is (= [["konto" "betrag"] ["4400" "1.234,56"]] (cp/parse-csv "﻿konto;betrag\r\n4400;1.234,56\r\n"))
      "a byte-order mark, CRLF and a ; delimiter (German exports)")
  (is (= [["t"] ["say \"hi\"\nthen go"]] (cp/parse-csv "t\n\"say \"\"hi\"\"\nthen go\"\n")) "quotes and a line break in a field"))

(def ^:private spec
  {:name "invoice-coding" :title "Invoice coding" :id "no"
   :inputs ["vendor" "text"]
   :expected {"account" {:rule :exact} "amount" {:rule :number :tolerance 0.01} "tags" {:rule :set}}})

(def ^:private rows
  [{"no" "1" "vendor" "Acme" "text" "toner" "account" "4930" "amount" "119,00" "tags" "office, supplies"}
   {"no" "2" "vendor" "Beta" "text" "rent" "account" "4210" "amount" "1.500,00" "tags" "rent"}
   {"no" "2" "vendor" "Gamma" "text" "fuel" "account" "4530" "amount" "50" "tags" "car"}
   {"no" "4" "vendor" "Delta" "text" "phone" "account" "" "amount" "30" "tags" "phone"}
   {"no" "5" "vendor" "Acme" "text" "toner" "account" "4980" "amount" "119" "tags" "office"}
   {"no" "" "vendor" "Eps" "text" "x" "account" "1" "amount" "1" "tags" "a"}
   {"no" "7" "vendor" "Zeta" "text" "travel" "account" "4670" "amount" "about 20" "tags" "travel"}
   {"no" "8" "vendor" "Eta" "text" "post" "account" "4910" "amount" "4.80" "tags" "post"}])

(deftest certification-says-which-cases-can-grade
  (let [{:keys [certification files]} (cp/case-pack spec rows nil)
        by-id (into {} (map (juxt :id identity)) (:verdicts certification))
        reasons #(set (map :reason (:reasons (by-id %))))]
    (is (= 8 (:cases certification)))
    (is (= #{:duplicate-id} (reasons "2")) "both rows named 2")
    (is (= #{:unlabelled} (reasons "4")))
    (is (= #{:conflicting-outcomes} (reasons "1")) "the same inputs as case 5, another outcome")
    (is (= #{:conflicting-outcomes} (reasons "5")))
    (is (= #{:no-id} (reasons "row-6")))
    (is (= #{:outcome-not-gradable} (reasons "7")) "an amount that is not a number")
    (is (= :certified (:status (by-id "8"))))
    (is (= 1 (:certified certification)))
    (testing "the bundle holds the certified cases only"
      (is (empty? (room/check-files files)))
      (is (contains? files "cases/8/gold.edn"))
      (is (not (contains? files "cases/7/gold.edn"))))))

(def ^:private clean
  [{"no" "1" "vendor" "Acme" "text" "toner" "account" "4930" "amount" "119,00" "tags" "office, supplies"}
   {"no" "2" "vendor" "Beta" "text" "rent" "account" "4210" "amount" "1.500,00" "tags" "rent"}])

(deftest the-checker-scores-field-by-field
  (let [b (room/bundle "invoice-coding" (:files (cp/case-pack spec clean nil)))
        score #(room/run-checker (:checker b) {:files {"/out/answer.edn" (pr-str %)}
                                               :gold (get-in b [:cases "2" :gold])
                                               :params (get-in b [:definition :params])})]
    (is (= 1.0 (:reward (score {"account" "4210" "amount" 1500 "tags" ["Rent"]}))) "1.500,00 is 1500; sets ignore case")
    (is (= 1.0 (:reward (score {:account 4210 :amount "1500.004" :tags "rent"}))) "keyword keys, a number for a code, within tolerance")
    (is (< 0.6 (:reward (score {"account" "4210" "amount" 1500 "tags" ["rent" "extra"]})) 0.7))
    (is (= 0.0 (:reward (room/run-checker (:checker b) {:files {} :gold (get-in b [:cases "2" :gold])
                                                        :params (get-in b [:definition :params])}))))
    (testing "its calibration holds: the reference scores top, each damaged field is noticed"
      (is (:ok? (room/calibrate b)) (pr-str (:problems (room/calibrate b)))))))

(deftest attachments-come-along
  (let [dir (doto (io/file (System/getProperty "java.io.tmpdir") (str "casepack-" (random-uuid))) .mkdirs)]
    (spit (io/file dir "inv1.txt") "Invoice 1: toner, 119,00 EUR")
    (spit (io/file dir "cases.csv") "no;vendor;doc;account\n1;Acme;inv1.txt;4930\n2;Beta;missing.txt;4210\n")
    (let [{:keys [files certification]} (cp/from-table {:name "t" :id "no" :inputs ["vendor"] :attachments ["doc"]
                                                        :expected {"account" {:rule :exact}}}
                                                       (str (io/file dir "cases.csv")))]
      (is (= "Invoice 1: toner, 119,00 EUR" (get files "cases/1/fixtures/docs/inv1.txt")))
      (is (= {"vendor" "Acme"} (edn/read-string (get files "cases/1/fixtures/docs/case.edn"))))
      (is (= #{:missing-attachment} (set (map :reason (:reasons (second (:verdicts certification))))))))))
