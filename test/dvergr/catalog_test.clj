(ns dvergr.catalog-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.catalog :as catalog]
            [dvergr.ops :as ops]))

(def ^:private sources
  {"/docs/company.md" "Tessellate was founded in 1987 in Harrowgate Bay."
   "/docs/products.md" "Quill-3 (1991)."})

(def ^:private gold
  [{:id :founding :terms ["1987" "Harrowgate Bay"]}
   {:id :quill-3 :terms ["Quill-3" "1991"]}])

(defn- score [pages & [g]]
  (catalog/score-wiki {:pages pages :sources sources :gold g :target "/wiki" :source "/docs"}))

(def ^:private good-wiki
  {"/wiki/index.md" "# Wiki\n- [Tessellate](tessellate.md)\n- [Quill-3](quill-3.md)"
   "/wiki/tessellate.md" (str "Founded in 1987 in Harrowgate Bay. Makes the [Quill-3](quill-3.md).\n"
                              "[source](../docs/company.md)")
   "/wiki/quill-3.md" "Released in 1991 by [Tessellate](./tessellate.md). [source](../docs/products.md#quill)"})

(deftest a-complete-wiki-scores-full-marks
  (let [{:keys [scores checks reward]} (score good-wiki gold)]
    (is (= 1.0 reward))
    (is (= 1.0 (:coverage scores) (:pages-cited scores) (:citations-valid scores) (:links-valid scores)))
    (is (every? true? (vals checks)) (pr-str checks))
    (is (every? boolean? (vals checks)) "certification takes boolean checks")
    (is (contains? checks :fact/founding))
    (is (= "gold-facts" (:coverage-basis scores)))))

(deftest each-shortcoming-costs-its-share
  (testing "a page without a citation"
    (let [{:keys [scores checks reward]}
          (score (assoc good-wiki "/wiki/quill-3.md" "Released in 1991 by [Tessellate](tessellate.md).") gold)]
      (is (= 0.5 (:pages-cited scores)))
      (is (false? (:every-page-cited? checks)))
      (is (< reward 1.0))))
  (testing "a citation of a document that does not exist, and a broken link"
    (let [{:keys [scores checks]}
          (score (assoc good-wiki "/wiki/quill-3.md"
                        "Released in 1991. [x](missing.md) [source](../docs/nowhere.md)") gold)]
      (is (< (:citations-valid scores) 1.0))
      (is (< (:links-valid scores) 1.0))
      (is (false? (:citations-valid? checks)))
      (is (false? (:links-valid? checks)))))
  (testing "a missing fact"
    (let [{:keys [scores checks]}
          (score (assoc good-wiki "/wiki/quill-3.md" "A seismometer. [source](../docs/products.md)") gold)]
      (is (true? (:fact/founding checks)))
      (is (false? (:fact/quill-3 checks)))
      (is (= 0.5 (:coverage scores)))))
  (testing "an index that misses pages"
    (is (false? (:index? (:checks (score (assoc good-wiki "/wiki/index.md" "# Wiki") gold)))))))

(deftest no-pages-no-reward
  (is (= 0.0 (:reward (score {}))))
  (is (= 0.0 (:reward (score {"/wiki/index.md" "# Wiki"}))) "an index alone is not a wiki"))

(deftest without-gold-coverage-is-the-share-of-sources-cited
  (let [{:keys [scores]} (score (dissoc good-wiki "/wiki/quill-3.md"))]
    (is (= "sources-cited" (:coverage-basis scores)))
    (is (= 0.5 (:coverage scores)))))

(deftest external-links-and-anchors-are-handled
  (let [{:keys [scores]} (score {"/wiki/index.md" "[a](a.md) [web](https://example.org/x)"
                                 "/wiki/a.md" "[source](/docs/company.md) [top](#top) [i](index.md)"})]
    (is (= 1.0 (:citations-valid scores)) "absolute source paths count")
    (is (= 1.0 (:links-valid scores)) "external links and bare anchors are ignored")))

(deftest the-catalog-is-listed-and-its-benchmark-set-loads
  (let [listed (ops/invoke {:execution-ctx nil} :catalog/list {})]
    (is (some #(= "wiki/v1" (:id %)) listed)))
  (let [wf (catalog/lookup "wiki/v1")
        fixtures ((get-in wf [:benchmark :fixtures]))
        gold ((get-in wf [:benchmark :gold]))
        text (clojure.string/lower-case (apply str (vals fixtures)))]
    (is (= 6 (count fixtures)))
    (is (= 10 (count gold)))
    (is (every? (fn [{:keys [terms]}] (every? #(clojure.string/includes? text (clojure.string/lower-case %)) terms))
                gold)
        "every gold fact is stated in the fixtures")
    (is (= 1.0 (:reward (catalog/score-wiki
                         {:pages {"/wiki/index.md" "[all](all.md)"
                                  "/wiki/all.md" (str (apply str (vals fixtures)) "\n[source](../docs/company.md)")}
                          :sources fixtures :gold gold :target "/wiki" :source "/docs"})))
        "a wiki restating every source scores full marks")
    (is (= :catalog/wiki-v1-bench (:environment-id (catalog/plan wf {:benchmark? true}))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No catalog workflow" (catalog/lookup "nope/v9")))))
