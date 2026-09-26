(ns dvergr.catalog.wiki-gen-test
  "Calibration of wiki/v3's generated benchmark set, on many seeds of every
   kind: the gold is stated in the documents it names, the generated reference
   wiki scores top, and each damaged variant loses what it damaged. This is
   what says a generated world is as sound a test as v2's hand-written one."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.catalog.wiki :as wiki]
            [dvergr.catalog.wiki-gen :as g]))

(def ^:private seeds (range 1 31))

(defn- score [w pages]
  (wiki/score-wiki-v2 {:pages pages :sources (g/documents w) :gold (g/gold w)
                       :target "/wiki" :source "/docs"}))

(defn- failing [{:keys [checks]}] (set (keep (fn [[k v]] (when-not v k)) checks)))

(defn- slug [s] (-> (str/lower-case s) (str/replace #"[^a-z0-9]+" "-") (str/replace #"(^-|-$)" "")))

(deftest a-world-is-a-function-of-its-seed
  (is (= (g/documents (g/world 7)) (g/documents (g/world 7))))
  (is (not= (g/documents (g/world 7)) (g/documents (g/world 8))))
  (is (= #{:water :energy :heating} (set (map (comp :kind g/world) seeds)))
      "every kind of organisation occurs")
  (testing "numbers are written the same way whatever the JVM's locale"
    (let [prev (java.util.Locale/getDefault)]
      (try (java.util.Locale/setDefault java.util.Locale/GERMANY)
           (is (= (g/documents (g/world 3))
                  (do (java.util.Locale/setDefault java.util.Locale/US) (g/documents (g/world 3)))))
           (finally (java.util.Locale/setDefault prev))))))

(deftest the-gold-is-stated-in-the-documents-it-names
  (doseq [seed seeds
          :let [w (g/world seed) docs (g/documents w) gold (g/gold w)]]
    (doseq [{:keys [id terms synthesis] srcs :sources} (:facts gold)
            :when (not synthesis)
            :let [text (str/lower-case (str/join "\n" (map #(get docs (str "/docs/" %)) srcs)))]
            term terms]
      (is (some #(str/includes? text (str/lower-case %)) (if (string? term) [term] term))
          (str "seed " seed ": " id " states " term " in " srcs)))
    (let [text (str/lower-case (str/join "\n" (vals docs)))]
      (doseq [{:keys [id value]} (:stale gold)]
        (is (some #(str/includes? text (str/lower-case %)) value) (str "seed " seed ": " id)))
      (doseq [d (:distractors gold)]
        (is (str/includes? text (str/lower-case d)) (str "seed " seed ": " d))))))

(deftest the-reference-wiki-scores-top-on-every-seed
  (doseq [seed seeds
          :let [w (g/world seed) r (score w (g/reference-wiki w))]]
    (is (empty? (failing r)) (str "seed " seed ": " (failing r)))
    (is (= 1.0 (:reward r)) (str "seed " seed))))

(defn- variants
  "`w`'s reference wiki, damaged in the ways v2's calibration damages its own."
  [w]
  (let [ref (g/reference-wiki w)
        docs (g/documents w)
        plant (str "/wiki/" (slug (:plant-new w)) ".md")
        coop (str "/wiki/" (slug (:org w)) ".md")]
    {:dump {"/wiki/index.md" "# Wiki\n\n- [All](all.md)\n"
            "/wiki/all.md" (str (str/join "\n\n" (vals docs)) "\n\n[source](../docs/"
                                (:charter (g/document-names w)) ")")}
     :stale (assoc ref plant (str "# " (:plant-new w) "\n\nThe " (:plant-old w) " plant runs at "
                                  (get-in (g/gold w) [:stale 2 :value 0]) " for the [" (:org w) "]("
                                  (slug (:org w)) ".md) ([report](../docs/"
                                  (:report-1 (g/document-names w)) ")).\n"))
     :invented-numbers (update ref coop str "\nIt employs 312 people, runs 47 stations, laid 380 kilometres of"
                               " mains and opened its laboratory in 1987.\n")
     :uncited (into {} (map (fn [[p t]] [p (str/replace t #"\s*\(\[[^\]]*\]\(\.\./docs/[^)]*\)\)" "")])) ref)
     :distractor (assoc ref "/wiki/other.md"
                        (str "# " (:other-place w) "\n\n" (:director w) " plans a pumping station at "
                             (:other-site w) " for " (:other-farms w) " farms ([news](../docs/"
                             (:other (g/document-names w)) ")).\n"))
     :partial (select-keys ref ["/wiki/index.md" coop plant])}))

(deftest each-damage-costs-what-it-damaged-on-every-seed
  (doseq [seed seeds
          :let [w (g/world seed)
                ref (:reward (score w (g/reference-wiki w)))
                vs (variants w)
                by (into {} (map (fn [[k pages]] [k (score w pages)])) vs)]]
    (doseq [[k r] by]
      (is (<= (:reward r) (- ref 0.04)) (str "seed " seed ": " k " scores " (:reward r))))
    (let [f (failing (:dump by))]
      (is (contains? f :no-copied-pages?) (str "seed " seed " dump"))
      (is (contains? f :entity/gm3) (str "seed " seed " dump"))
      (is (< (:reward (:dump by)) 0.5) (str "seed " seed " dump")))
    (let [f (failing (:stale by))]
      (is (contains? f :current/plant-capacity-old) (str "seed " seed " stale"))
      (is (contains? f :current/plant-old-name) (str "seed " seed " stale"))
      (is (contains? f :fact/plant-capacity) (str "seed " seed " stale: the current value is gone")))
    (let [r (:invented-numbers by)]
      (is (contains? (failing r) :grounded?) (str "seed " seed " invented"))
      (is (= #{"312" "47" "380" "1987"} (set (map :number (get-in r [:scores :unsupported-numbers]))))
          (str "seed " seed " invented")))
    (let [f (failing (:uncited by))]
      (is (contains? f :every-page-cited?) (str "seed " seed " uncited"))
      (is (contains? f :fact/founded) (str "seed " seed " uncited")))
    (is (contains? (failing (:distractor by)) :no-distractor-facts?) (str "seed " seed " distractor"))
    (let [f (failing (:partial by))]
      (is (contains? f :entity/designer) (str "seed " seed " partial"))
      (is (contains? f :fact/incident-dates) (str "seed " seed " partial")))))

(deftest the-held-out-split-needs-its-key
  (is (= [1 2 3] (g/seeds :dev 3)))
  (is (thrown? clojure.lang.ExceptionInfo (g/seeds :test 3)))
  (let [a (g/seeds :test 5 "k1") b (g/seeds :test 5 "k2")]
    (is (= a (g/seeds :test 5 "k1")) "stable for a key")
    (is (not= a b) "and different for another")
    (is (every? pos? a))
    (is (empty? (filter (set (g/seeds :dev 1000)) a)) "and not dev seeds")))
