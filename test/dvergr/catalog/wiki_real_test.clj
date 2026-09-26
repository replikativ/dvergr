(ns dvergr.catalog.wiki-real-test
  "Calibration of wiki/v3's real corpus (six dated snapshots of the Wikipedia
   article on the Mondragon Corporation, CC BY-SA 4.0): the curated gold is
   stated in the documents it names, a hand-written reference wiki scores top,
   and damaged variants lose what they damaged, as for v2."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.catalog.wiki :as wiki]))

(def ^:private sources (wiki/real-documents :mondragon))
(def ^:private gold (wiki/real-gold :mondragon))

(def ^:private reference
  (let [dir (io/file (io/resource "dvergr/catalog/wiki-real/mondragon/calibration/reference"))]
    (into {} (for [f (.listFiles dir) :when (str/ends-with? (.getName f) ".md")]
               [(str "/wiki/" (.getName f)) (slurp f)]))))

(defn- score [pages]
  (wiki/score-wiki-v2 {:pages pages :sources sources :gold gold :target "/wiki" :source "/docs"}))

(defn- failing [{:keys [checks]}] (set (keep (fn [[k v]] (when-not v k)) checks)))

(deftest the-corpus-carries-its-licence
  (let [licence (slurp (io/resource "dvergr/catalog/wiki-real/mondragon/LICENSE.md"))]
    (is (str/includes? licence "CC BY-SA 4.0"))
    (doseq [doc (keys sources)]
      (is (str/includes? licence (last (str/split doc #"/"))) (str doc " is attributed")))))

(deftest the-gold-is-stated-in-the-documents-it-names
  (doseq [{:keys [id terms] srcs :sources} (:facts gold)
          src srcs
          :let [text (str/lower-case (get sources (str "/docs/" src) ""))]]
    (is (every? (fn [term] (some #(str/includes? text (str/lower-case %)) (if (string? term) [term] term)))
                terms)
        (str id " is stated in " src)))
  (testing "every stale value occurs in the corpus"
    (let [text (str/lower-case (str/join "\n" (vals sources)))]
      (doseq [{:keys [id value]} (:stale gold)]
        (is (some #(str/includes? text (str/lower-case %)) value) (str id))))))

(deftest the-reference-wiki-scores-top
  (let [{:keys [reward scores] :as r} (score reference)]
    (is (empty? (failing r)) (pr-str (failing r) (select-keys scores [:unsupported-numbers :copied-pages :stale-values])))
    (is (= 1.0 reward) (pr-str scores))))

(deftest each-damage-costs-what-it-damaged
  (let [ref (:reward (score reference))
        corp "/wiki/mondragon-corporation.md"
        variants
        {:dump {"/wiki/index.md" "# Wiki\n\n- [All](all.md)\n"
                "/wiki/all.md" (str (str/join "\n\n" (vals sources)) "\n\n[source](../docs/mondragon-2025.md)")}
         ;; the 2008 headcount stated as the current one
         :stale (assoc reference corp
                       (str "# Mondragon Corporation\n\nThe Mondragon Corporation, founded on 14 April 1956 by "
                            "[José María Arizmendiarrieta](jose-maria-arizmendiarrieta.md), employs 92,773 people "
                            "([snapshot](../docs/mondragon-2010.md), [2025 snapshot](../docs/mondragon-2025.md)).\n"))
         :former-heads-as-current (assoc reference "/wiki/inigo-ucin.md"
                                         (str "# Iñigo Ucín\n\nThe [Mondragon Corporation](mondragon-corporation.md) is led "
                                              "by Iñigo Ucín, Javier Sotil and Txema Gisasola "
                                              "([snapshot](../docs/mondragon-2016.md), [2025 snapshot](../docs/mondragon-2025.md)).\n"))
         :invented-numbers (update reference corp str "\nIt runs 312 factories in 47 countries and had revenue of €31,500 million in 2023.\n")
         :uncited (into {} (map (fn [[p t]] [p (str/replace t #"\s*\(\[[^\]]*\]\(\.\./docs/[^)]*\)\)" "")])) reference)
         :partial (select-keys reference ["/wiki/index.md" "/wiki/inigo-ucin.md"])}
        by (into {} (map (fn [[k pages]] [k (score pages)])) variants)]
    (doseq [[k r] by :when (not= k :former-heads-as-current)]
      (is (<= (:reward r) (- ref 0.04)) (str k " scores clearly below the reference: " (:reward r))))
    ;; Currency's weight is shared by the stale values (8 here, 5 in v2), so two
    ;; former heads stated as current cost 0.15 × 2/8: below the reference, but
    ;; by less than the other damages (doc/benchmarks.md, wiki v3).
    (is (< (:reward (:former-heads-as-current by)) ref))
    (is (contains? (failing (:dump by)) :no-copied-pages?))
    (let [f (failing (:stale by))]
      (is (contains? f :current/employees-2008))
      (is (contains? f :fact/employees) "the current headcount is gone"))
    (let [f (failing (:former-heads-as-current by))]
      (is (contains? f :current/president-sotil))
      (is (contains? f :current/chairman-gisasola)))
    (is (= #{"312" "47" "31500" "2023"}
           (set (map :number (get-in (:invented-numbers by) [:scores :unsupported-numbers])))))
    (is (contains? (failing (:uncited by)) :every-page-cited?))
    (is (contains? (failing (:partial by)) :entity/founder))))

(deftest the-real-split-runs-the-real-corpora
  (let [plan (wiki/experiment-plan {:version 3 :split :real :models ["claude-haiku-4-5"]})
        [env] (:environments plan)]
    (is (= 1 (count (:environments plan))))
    (is (= {:corpus :mondragon :split :real} (:environment/metadata env)))
    (is (= [:mondragon] (get-in plan [:dataset :metadata :worlds])))))
