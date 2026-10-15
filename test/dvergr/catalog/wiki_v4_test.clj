(ns dvergr.catalog.wiki-v4-test
  "Calibration of wiki/v4 on v3's generated worlds: the generated reference
   wiki (atoms, an overview, an index; claims with verbatim quotes) scores 1.0
   with every check true on every seed, and each damaged variant fails the
   check it damaged and scores lower."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.catalog.wiki-gen :as g]
            [dvergr.catalog.wiki-v4 :as v4]))

(def ^:private worlds
  (concat (map g/world (range 1 21))
          (for [scale [3 5] seed (range 1 6)] (g/world seed {:scale scale}))
          (for [noise [2 4] seed (range 1 6)] (g/world seed {:noise noise}))
          (for [seed (range 1 6)] (g/world seed {:scale 3 :prose 2 :noise 4}))))

(defn- label [w] (str "seed " (:seed w) " scale " (:scale w 1) " prose " (:prose w 0) " noise " (:noise w 0)))

(defn- score [w pages]
  (v4/score-wiki-v4 {:pages pages :sources (g/documents w) :gold (g/gold w)}))

(defn- failing [{:keys [checks]}] (set (keep (fn [[k v]] (when-not v k)) checks)))

(deftest the-normaliser-matches-vault-wiki
  (is (= "4200 members at 5% in may 3 2019" (v4/normalize "4,200  Members at 5 %\nin May 3, 2019")))
  (is (= "12 million" (v4/normalize "$12 million")))
  (is (= "3 sensors" (v4/normalize "three sensors")))
  (is (= "someone" (v4/normalize "someone")) "number words are whole words")
  (testing "a scanned source matches its unscanned quote"
    (is (str/includes? (v4/source-text "The plant was re-\nnamed in 2019.\n") (v4/normalize "The plant was renamed in 2019.")))))

(deftest pages-parse-as-vault-wiki-writes-them
  (let [[fm body] (v4/front-matter "---\ntype: entity\nsource: [a.md, b.md]\nentry-point: \"What it is\"\ntags:\n  - x\n  - y\n---\n# T\n")]
    (is (= {:type "entity" :source ["a.md" "b.md"] :entry-point "What it is" :tags ["x" "y"]} fm))
    (is (= "# T\n" body)))
  (is (= [{:heading nil :text "intro\n"} {:heading "One" :text "a\n> \"q\" [[d]]\n"}]
         (v4/claims "intro\n## One\na\n> \"q\" [[d]]\n")))
  (is (= [{:quote "q" :citations [{:target "d" :heading "S" :kind :wiki}]}]
         (v4/quotes "> \"q\" [[d#S]]")))
  (is (= {"first-part" "x\n" "second" "y\n"} (v4/sections "# T\n## First part\nx\n## Second\ny\n"))))

(deftest the-reference-wiki-scores-top-on-every-seed
  (doseq [w worlds
          :let [r (score w (v4/reference-wiki w))]]
    (is (empty? (failing r)) (str (label w) ": " (failing r) " " (select-keys (:scores r) [:unfaithful-quotes :bad-citations :front-matter-problems :orphan-atoms :broken-links :stale-values :unsupported-numbers :copied-pages])))
    (is (= 1.0 (:reward r)) (str (label w)))
    (is (pos? (get-in r [:scores :quotes])) "the reference quotes its sources")))

(defn- atom-path [w name] (str "/wiki/atoms/" (v4/slug name) ".md"))

(defn- variants
  "`w`'s v4 reference wiki, damaged one way each."
  [w]
  (let [ref (v4/reference-wiki w)
        docs (g/documents w)
        n (g/document-names w)
        coop (atom-path w (:org w))
        plant (atom-path w (:plant-new w))
        overview (str "/wiki/overviews/" (v4/slug (:org w)) ".md")
        first-quote (fn [text] (re-find #"(?m)^> \"[^\"]+\" \[\[[^\]]+\]\]$" text))
        numeric-quote (fn [text] (re-find #"(?m)^> \"[^\"]*\d[^\"]*\" \[\[[^\]]+\]\]$" text))]
    {:dump {"/wiki/index.md" "# Wiki\n\n- [[overviews/all]]\n"
            "/wiki/overviews/all.md" "---\ntype: overview\n---\n# All\n\n- [[atoms/all]]\n"
            "/wiki/atoms/all.md" (str "---\ntype: entity\nsource: [" (:charter n) "]\nentry-point: everything\n---\n# All\n\n"
                                      (str/join "\n\n" (vals docs)) "\n\n**Sources:** [[" (str/replace (:charter n) #"\.md$" "") "]]\n")}
     :misquote (update ref coop (fn [t] (let [q (numeric-quote t)]
                                          (str/replace-first t q (str/replace-first q #"\d" #(str (mod (inc (parse-long %)) 10)))))))
     :headless (update ref coop (fn [t] (let [q (first-quote t)]
                                          (-> t (str/replace-first (str q "\n") "")
                                              (str/replace-first #"(?m)^## " (str q "\n\n## "))))))
     :bad-citation (update ref coop (fn [t] (let [q (first-quote t)]
                                              (str/replace-first t q (str/replace q #"\]\]$" "#No such section]]")))))
     :orphan (update ref overview (fn [t] (str/replace t (re-pattern (str "(?m)^- \\[\\[atoms/" (v4/slug (:plant-new w)) "\\]\\].*\n")) "")))
     :no-front-matter (update ref plant #(str/replace % #"(?s)^---\n.*?\n---\n" ""))
     :sources-out-of-sync (update ref coop #(str/replace % #"(?m)^(\*\*Sources:\*\* \[\[[^\]]+\]\]), .*$" "$1"))
     :stale (assoc ref plant (str "---\ntype: entity\nsource: [" (:report-1 n) "]\nentry-point: The plant.\n---\n# " (:plant-new w)
                                  "\n\n## Capacity\n\nThe " (:plant-old w) " plant runs at " (get-in (g/gold w) [:stale 2 :value 0])
                                  " for the " (:org w) " ([[" (v4/slug (:org w)) "]]) [[" (str/replace (:report-1 n) #"\.md$" "") "]].\n\n"
                                  "**Sources:** [[" (str/replace (:report-1 n) #"\.md$" "") "]]\n"))
     :invented-numbers (update ref coop str "\n## Staff\n\nIt employs 312 people, runs 77 stations, laid 380 kilometres of"
                               " mains and opened its laboratory in 1987.\n")
     :uncited (into {} (map (fn [[p t]] [p (-> t (str/replace #"\[\[(?!atoms/|overviews/)[^\]]+\]\]" ""))])) ref)
     :distractor (update ref coop str "\n## Nearby\n\n" (:director w) " plans a pumping station at " (:other-site w) " for "
                         (:other-farms w) " farms [[" (str/replace (:other n) #"\.md$" "") "]].\n")
     :unquoted (into {} (map (fn [[p t]] [p (str/replace t #"(?m)^> \"[^\"]+\" \[\[[^\]]+\]\]\n" "")])) ref)
     :partial (select-keys ref ["/wiki/index.md" overview coop plant])}))

(def ^:private expected
  "The check each damage must fail."
  {:dump :no-copied-pages?
   :misquote :quotes-faithful?
   :headless :every-quote-under-a-heading?
   :bad-citation :citations-valid?
   :orphan :no-orphan-atoms?
   :no-front-matter :front-matter?
   :sources-out-of-sync :front-matter?
   :stale :current/plant-capacity-old
   :invented-numbers :grounded?
   :uncited :fact/founded
   :distractor :no-distractor-facts?
   :unquoted :every-claim-quoted?
   :partial :links-valid?})

(deftest each-damage-fails-its-check-and-costs-on-every-seed
  (doseq [w worlds
          :let [ref (:reward (score w (v4/reference-wiki w)))]
          [k pages] (variants w)
          :let [r (score w pages)]]
    (is (contains? (failing r) (expected k)) (str (label w) ": " k " fails " (failing r)))
    (is (< (:reward r) ref) (str (label w) ": " k " scores " (:reward r)))))

(deftest a-dump-and-an-uncited-wiki-score-low
  (doseq [w (take 5 worlds)
          :let [vs (variants w)]]
    (is (< (:reward (score w (:dump vs))) 0.5) (label w))
    (is (< (:reward (score w (:uncited vs))) 0.7) (label w))))
