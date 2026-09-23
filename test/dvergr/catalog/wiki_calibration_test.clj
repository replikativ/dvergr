(ns dvergr.catalog.wiki-calibration-test
  "Calibration of the wiki/v2 checker: a hand-written reference wiki scores top,
   and each damaged variant loses exactly what it damaged. There is no upstream
   to be equivalent to (unlike BFCL or tau2); this is what says the checker
   measures what it claims."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.catalog.wiki :as wiki]))

(def ^:private sources (wiki/fixtures-v2))
(def ^:private gold (wiki/gold-v2))

(def ^:private reference
  (let [dir (io/file (io/resource "dvergr/catalog/wiki-v2/calibration/reference"))]
    (into {} (for [f (.listFiles dir) :when (str/ends-with? (.getName f) ".md")]
               [(str "/wiki/" (.getName f)) (slurp f)]))))

(defn- score [pages]
  (wiki/score-wiki-v2 {:pages pages :sources sources :gold gold :target "/wiki" :source "/docs"}))

(defn- failing [{:keys [checks]}] (set (keep (fn [[k v]] (when-not v k)) checks)))

(deftest the-gold-is-stated-in-the-documents-it-names
  (doseq [{:keys [id terms synthesis] srcs :sources} (:facts gold)
          :when (not synthesis)]
    (let [text (str/lower-case (str/join "\n" (map #(get sources (str "/docs/" %)) srcs)))]
      (is (every? (fn [term] (some #(str/includes? text (str/lower-case %)) (if (string? term) [term] term)))
                  terms)
          (str id " is stated in " srcs))))
  (testing "every stale value and distractor occurs in the corpus"
    (let [text (str/lower-case (str/join "\n" (vals sources)))]
      (doseq [{:keys [id value]} (:stale gold)]
        (is (some #(str/includes? text (str/lower-case %)) value) (str id)))
      (doseq [d (:distractors gold)]
        (is (str/includes? text (str/lower-case d)) d)))))

(deftest the-reference-wiki-scores-top
  (let [{:keys [reward scores] :as r} (score reference)]
    (is (empty? (failing r)) (pr-str (failing r) (select-keys scores [:unsupported-numbers :copied-pages :stale-values])))
    (is (<= 0.99 reward) (pr-str scores))))

(defn- replace-page [pages path text] (assoc pages (str "/wiki/" path) text))

(def ^:private variants
  {:dump
   {"/wiki/index.md" "# Wiki\n\n- [All](all.md)\n"
    "/wiki/all.md" (str (str/join "\n\n" (vals sources)) "\n\n[source](../docs/charter-1953.md)")}

   :stale
   (replace-page reference "kessel-works.md"
                 (str "# Kessel Works\n\nThe Eastbank plant treats 9 million litres a day for the "
                      "[Morrow Valley Water Cooperative](morrow-valley-water-cooperative.md) "
                      "([report](../docs/annual-report-2010.md)).\n"))

   :invented-numbers
   (update reference "/wiki/morrow-valley-water-cooperative.md" str
           "\nThe cooperative employs 312 people, runs 47 pumping stations, laid 380 kilometres of"
           " mains and opened its laboratory in 1987.\n")

   :uncited
   (into {} (map (fn [[p t]] [p (str/replace t #"\s*\(\[[^\]]*\]\(\.\./docs/[^)]*\)\)" "")])) reference)

   :distractor
   (replace-page reference "morrow-ridge.md"
                 "# Morrow Ridge\n\nIts director Colm Hartigan plans a pumping station at Lark Hollow for 310 farms ([news](../docs/morrow-ridge.md)).\n")

   :partial
   (select-keys reference ["/wiki/index.md" "/wiki/morrow-valley-water-cooperative.md"
                           "/wiki/mira-szabo.md" "/wiki/kessel-works.md"])})

(deftest a-number-the-source-spells-out-is-grounded
  ;; The charter says "forty-two"; a page may write 42.
  (let [r (score (update reference "/wiki/morrow-valley-water-cooperative.md"
                         str/replace "forty-two" "42"))]
    (is (empty? (get-in r [:scores :unsupported-numbers])))
    (is (= 1.0 (:reward r)))))

(deftest each-damage-costs-what-it-damaged
  (let [ref (score reference)
        by (into {} (map (fn [[k pages]] [k (score pages)])) variants)]
    (doseq [[k r] by]
      (is (<= (:reward r) (- (:reward ref) 0.04)) (str k " scores clearly below the reference: " (:reward r))))
    (testing "a dump of the sources: copied, no entity pages, no facts"
      (let [r (:dump by) f (failing r)]
        (is (contains? f :no-copied-pages?))
        (is (contains? f :entity/mira-szabo))
        (is (contains? f :fact/founded))
        (is (< (:reward r) 0.5) (pr-str (:scores r)))))
    (testing "stale values stated as current"
      (let [f (failing (:stale by))]
        (is (contains? f :current/plant-9m))
        (is (contains? f :current/eastbank-name))
        (is (contains? f :fact/plant-flow) "the current value is gone")))
    (testing "invented numbers are unsupported"
      (let [r (:invented-numbers by)]
        (is (contains? (failing r) :grounded?))
        (is (= #{"312" "47" "380" "1987"} (set (map :number (get-in r [:scores :unsupported-numbers])))))))
    (testing "without citations facts do not count and numbers are unsupported"
      (let [r (:uncited by) f (failing r)]
        (is (contains? f :every-page-cited?))
        (is (contains? f :fact/founded))
        (is (contains? f :grounded?))))
    (testing "the unrelated organisation's facts"
      (is (contains? (failing (:distractor by)) :no-distractor-facts?)))
    (testing "missing pages"
      (let [f (failing (:partial by))]
        (is (contains? f :entity/jonas-kessel))
        (is (contains? f :fact/notice-dates))))))
