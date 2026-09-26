(ns dvergr.catalog.wiki-real-test
  "Calibration of wiki/v3's real corpora (dated snapshots of Wikipedia
   articles, CC BY-SA 4.0): the curated gold is stated in the documents it
   names, a hand-written reference wiki scores top, and damaged variants lose
   what they damaged, as for v2."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.catalog.wiki :as wiki]))

(def ^:private corpora (sort (keys wiki/real-corpora)))

(defn- reference [corpus]
  (let [dir (io/file (io/resource (str (:dir (wiki/real-corpora corpus)) "/calibration/reference")))]
    (into {} (for [f (.listFiles dir) :when (str/ends-with? (.getName f) ".md")]
               [(str "/wiki/" (.getName f)) (slurp f)]))))

(defn- score [corpus pages]
  (wiki/score-wiki-v2 {:pages pages :sources (wiki/real-documents corpus) :gold (wiki/real-gold corpus)
                       :target "/wiki" :source "/docs"}))

(defn- failing [{:keys [checks]}] (set (keep (fn [[k v]] (when-not v k)) checks)))

(defn- dump [corpus latest]
  {"/wiki/index.md" "# Wiki\n\n- [All](all.md)\n"
   "/wiki/all.md" (str (str/join "\n\n" (vals (wiki/real-documents corpus))) "\n\n[source](../docs/" latest ")")})

(defn- uncited [pages]
  (into {} (map (fn [[p t]] [p (str/replace t #"\s*\(\[[^\]]*\]\(\.\./docs/[^)]*\)(, \[[^\]]*\]\(\.\./docs/[^)]*\))*\)" "")])) pages))

(deftest every-corpus-carries-its-licence
  (doseq [corpus corpora
          :let [licence (slurp (io/resource (str (:dir (wiki/real-corpora corpus)) "/LICENSE.md")))]]
    (is (str/includes? licence "CC BY-SA 4.0") (str corpus))
    (doseq [doc (keys (wiki/real-documents corpus))]
      (is (str/includes? licence (last (str/split doc #"/"))) (str corpus ": " doc " is attributed")))))

(deftest the-gold-is-stated-in-the-documents-it-names
  (doseq [corpus corpora
          :let [sources (wiki/real-documents corpus) gold (wiki/real-gold corpus)]]
    (doseq [{:keys [id terms] srcs :sources} (:facts gold)
            src srcs
            :let [text (str/lower-case (get sources (str "/docs/" src) ""))]]
      (is (every? (fn [term] (some #(str/includes? text (str/lower-case %)) (if (string? term) [term] term)))
                  terms)
          (str corpus ": " id " is stated in " src)))
    (testing (str corpus ": every stale value occurs in the corpus")
      (let [text (str/lower-case (str/join "\n" (vals sources)))]
        (doseq [{:keys [id value]} (:stale gold)]
          (is (some #(str/includes? text (str/lower-case %)) value) (str corpus ": " id)))))))

(deftest the-reference-wiki-scores-top
  (doseq [corpus corpora
          :let [{:keys [reward scores] :as r} (score corpus (reference corpus))]]
    (is (empty? (failing r)) (pr-str corpus (failing r) (select-keys scores [:unsupported-numbers :copied-pages :stale-values])))
    (is (= 1.0 reward) (pr-str corpus scores))))

(deftest mondragon-damage-costs-what-it-damaged
  (let [reference (reference :mondragon)
        score (partial score :mondragon)
        ref (:reward (score reference))
        corp "/wiki/mondragon-corporation.md"
        variants
        {:dump (dump :mondragon "mondragon-2025.md")
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
         :uncited (uncited reference)
         :partial (select-keys reference ["/wiki/index.md" "/wiki/inigo-ucin.md"])}
        by (into {} (map (fn [[k pages]] [k (score pages)])) variants)]
    ;; each stale value stated as current costs 0.03, however many the corpus has
    (doseq [[k r] by]
      (is (<= (:reward r) (- ref 0.04)) (str k " scores clearly below the reference: " (:reward r))))
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

(deftest john-lewis-damage-costs-what-it-damaged
  (let [reference (reference :john-lewis)
        score (partial score :john-lewis)
        ref (:reward (score reference))
        jlp "/wiki/john-lewis-partnership.md"
        tarry "/wiki/jason-tarry.md"
        variants
        {:dump (dump :john-lewis "john-lewis-2025.md")
         ;; the oldest snapshot's founding year and headcount as current
         :stale (-> reference
                    (assoc "/wiki/key-figures.md"
                           (str "# Key figures\n\nThe [John Lewis Partnership](john-lewis-partnership.md) has 68,430 "
                                "employees; its founding year is 1920 ([snapshot](../docs/john-lewis-2010.md)).\n"))
                    (update "/wiki/index.md" str "- [Key figures](key-figures.md)\n"))
         ;; a chairman who has left, stated as the chairman
         :former-chair-as-current (-> reference
                                      (update jlp str/replace "[Jason Tarry](jason-tarry.md) is the Chairman, Rita Clifton the Deputy Chairman"
                                              "Rita Clifton is the Deputy Chairman")
                                      (assoc tarry
                                             (str "# Jason Tarry\n\nJason Tarry is a partner of the [John Lewis Partnership]"
                                                  "(john-lewis-partnership.md) ([snapshot](../docs/john-lewis-2025.md)). It is led "
                                                  "by Dame Sharon White ([snapshot](../docs/john-lewis-2022.md)).\n")))
         ;; the Ocado supply as the 2019 snapshot tells it: still running
         :planned-as-done (update reference jlp str/replace
                                  #"(?s)## Ocado.*"
                                  "## Ocado\n\nThe Partnership supplies the Ocado web supermarket with own-brand goods ([2019 snapshot](../docs/john-lewis-2019.md)).\n")
         :invented-numbers (update reference jlp str "\nIt runs 331 shops and made a profit of £612 million in 2021.\n")
         :uncited (uncited reference)
         :partial (select-keys reference ["/wiki/index.md" "/wiki/waitrose.md"])}
        by (into {} (map (fn [[k pages]] [k (score pages)])) variants)]
    (doseq [[k r] by :when (not= k :planned-as-done)]
      (is (<= (:reward r) (- ref 0.04)) (str k " scores clearly below the reference: " (:reward r))))
    ;; one missing fact of fourteen costs 0.25/14: below the reference, by less
    (is (< (:reward (:planned-as-done by)) ref))
    (is (contains? (failing (:dump by)) :no-copied-pages?))
    (let [f (failing (:stale by))]
      (is (contains? f :current/employees-2008))
      (is (contains? f :current/founded-1920)))
    (let [f (failing (:former-chair-as-current by))]
      (is (contains? f :current/chair-white))
      (is (contains? f :fact/chairman)))
    (is (contains? (failing (:planned-as-done by)) :fact/ocado-ended))
    (is (= #{"331" "612" "2021"}
           (set (map :number (get-in (:invented-numbers by) [:scores :unsupported-numbers])))))
    (is (contains? (failing (:uncited by)) :every-page-cited?))
    (is (contains? (failing (:partial by)) :entity/founder))))

(deftest the-real-split-runs-the-real-corpora
  (let [plan (wiki/experiment-plan {:version 3 :split :real :models ["claude-haiku-4-5"]})]
    (is (= [{:corpus :john-lewis :split :real} {:corpus :mondragon :split :real}]
           (mapv :environment/metadata (:environments plan))))
    (is (= [:john-lewis :mondragon] (get-in plan [:dataset :metadata :worlds])))))
