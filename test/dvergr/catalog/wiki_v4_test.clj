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
  (is (= [{:heading nil :text "intro\n" :index 0} {:heading "One" :text "a\n> \"q\" [[d]]\n" :index 1}]
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
     :stale (update ref plant
                    (fn [t] (let [r1 (str/replace (:report-1 n) #"\.md$" "")
                                  pr (str "The " (:plant-old w) " plant runs at " (get-in (g/gold w) [:stale 2 :value 0]) " [[" r1 "]].")
                                  q (#'v4/best-sentence (get docs (str "/docs/" (:report-1 n))) pr [])]
                              (str t "\n## Capacity now\n\n" pr "\n> \"" q "\" [[" r1 "]]\n"))))
     :invented-numbers (update ref coop str "\n## Staff\n\nIt employs 312 people, runs 77 stations, laid 380 kilometres of"
                               " mains and opened its laboratory in 1987.\n")
     ;; the claims' own citations gone; quotes, Sources lines and front matter kept
     :uncited (into {} (map (fn [[p t]]
                              [p (->> (str/split-lines t)
                                      (map #(if (or (str/starts-with? % ">") (str/starts-with? % "**Sources:**"))
                                              %
                                              (str/replace % #"\[\[(?!atoms/|overviews/)[^\]]+\]\]" "")))
                                      (str/join "\n"))]))
                    ref)
     :distractor (update ref coop str "\n## Nearby\n\n" (:director w) " plans a pumping station at " (:other-site w) " for "
                         (:other-farms w) " farms [[" (str/replace (:other n) #"\.md$" "") "]].\n")
     :unquoted (into {} (map (fn [[p t]] [p (str/replace t #"(?m)^> \"[^\"]+\" \[\[[^\]]+\]\]\n" "")])) ref)
     :partial (select-keys ref ["/wiki/index.md" overview coop plant])}))

(def ^:private expected
  "The check each damage must fail, and the checks it may fail besides (a
   keyword namespace such as :fact stands for all its checks); `:any` for a
   wiki damaged everywhere."
  {:dump [:no-copied-pages? :any]
   :misquote [:quotes-faithful? #{:every-claim-quoted?}]
   :headless [:every-quote-under-a-heading? #{:every-claim-quoted?}]
   :bad-citation [:citations-valid? #{:quotes-faithful? :every-claim-quoted?}]
   :orphan [:no-orphan-atoms? #{}]
   :no-front-matter [:front-matter? #{}]
   :sources-out-of-sync [:front-matter? #{}]
   :stale [:current/plant-capacity-old #{:current}]
   :invented-numbers [:grounded? #{}]
   :uncited [:fact/founded #{:fact :every-claim-quoted?}]
   :distractor [:no-distractor-facts? #{:every-claim-quoted? :front-matter?}]
   :unquoted [:every-claim-quoted? #{}]
   :partial [:links-valid? #{:fact :entity :current}]})

(defn- allowed? [allowed k]
  (or (= :any allowed) (contains? allowed k) (contains? allowed (some-> (namespace k) keyword))))

(deftest each-damage-fails-its-check-only-and-costs-on-every-seed
  (doseq [w worlds
          :let [ref (:reward (score w (v4/reference-wiki w)))]
          [k pages] (variants w)
          :let [r (score w pages) [must allowed] (expected k) f (failing r)]]
    (is (contains? f must) (str (label w) ": " k " fails " f))
    (is (every? #(or (= must %) (allowed? allowed %)) f) (str (label w) ": " k " also fails " (disj f must)))
    (is (< (:reward r) ref) (str (label w) ": " k " scores " (:reward r)))))

(deftest a-dump-and-an-uncited-wiki-score-low
  (doseq [w (take 5 worlds)
          :let [vs (variants w)]]
    (is (< (:reward (score w (:dump vs))) 0.5) (label w))
    (is (< (:reward (score w (:uncited vs))) 0.7) (label w))))

;; ============================================================================
;; Fixtures: the paths the generated worlds do not reach
;; ============================================================================

(def ^:private docs
  {"/docs/plant.md" "---\ndate: 2020\n---\n# The plant\n\n## Capacity\nThe plant delivers 500 units a day.\n\n## Staff\nIt employs forty people from the valley.\n"
   "/docs/charter.md" "---\ndate: 1953\n---\n# Charter\n\nThe cooperative was founded in 1953 by forty households.\n"})

(def ^:private gold
  {:entities {} :relations [] :distractors [] :stale []
   :facts [{:id :founded :terms ["1953"] :sources ["charter.md"]}
           {:id :capacity :terms ["500 units"] :sources ["plant.md"]}]})

(defn- atom-page [fm & claims]
  (str "---\n" fm "\n---\n# Atom\n\n" (str/join "\n" claims) "\n**Sources:** [[plant]], [[charter]]\n"))

(defn- wiki [& atom-texts]
  (into {"/wiki/index.md" "# W\n\n- [[overviews/o]]\n"
         "/wiki/overviews/o.md" (str "---\ntype: overview\n---\n# O\n\n"
                                     (str/join (for [i (range (count atom-texts))] (str "- [[atoms/a" i "]]\n"))))}
        (map-indexed (fn [i t] [(str "/wiki/atoms/a" i ".md") t]) atom-texts)))

(def ^:private good-fm "type: entity\nsource: [plant.md, charter.md]\nentry-point: The plant and the cooperative.")
(def ^:private capacity-claim "## Capacity\nThe plant delivers 500 units a day [[plant#Capacity]].\n> \"The plant delivers 500 units a day\" [[plant#Capacity]]\n")
(def ^:private founded-claim "## Founded\nIt was founded in 1953 [[charter]].\n> \"founded in 1953 by forty households\" [[charter]]\n")

(defn- run [pages] (v4/score-wiki-v4 {:pages pages :sources docs :gold gold}))

(deftest a-sectioned-source-is-cited-by-section
  (let [r (run (wiki (atom-page good-fm capacity-claim founded-claim)))]
    (is (empty? (failing r)) (str (failing r) " " (:scores r)))
    (is (= 1.0 (:reward r))))
  (testing "citing a sectioned document whole is not enough"
    (let [r (run (wiki (atom-page good-fm (str/replace capacity-claim "[[plant#Capacity]]" "[[plant]]") founded-claim)))]
      (is (contains? (failing r) :citations-valid?))
      (is (contains? (failing r) :quotes-faithful?))))
  (testing "a quote from another section is not faithful"
    (let [r (run (wiki (atom-page good-fm (str/replace capacity-claim "> \"The plant delivers 500 units a day\" [[plant#Capacity]]"
                                                       "> \"The plant delivers 500 units a day\" [[plant#Staff]]")
                                  founded-claim)))]
      (is (contains? (failing r) :quotes-faithful?)))))

(deftest a-quote-must-carry-evidence-for-its-claim
  (testing "an empty quote is not evidence"
    (let [r (run (wiki (atom-page good-fm (str/replace capacity-claim "\"The plant delivers 500 units a day\"" "\" \"") founded-claim)))]
      (is (contains? (failing r) :quotes-faithful?))
      (is (contains? (failing r) :every-claim-quoted?))))
  (testing "a faithful quote about something else does not evidence the claim"
    (let [r (run (wiki (atom-page good-fm (str/replace capacity-claim "> \"The plant delivers 500 units a day\" [[plant#Capacity]]"
                                                       "> \"It employs forty people from the valley\" [[plant#Staff]]")
                                  founded-claim)))]
      (is (not (contains? (failing r) :quotes-faithful?)))
      (is (contains? (failing r) :every-claim-quoted?))))
  (testing "two claims under one heading do not share a quote"
    (let [r (run (wiki (atom-page good-fm capacity-claim "## Capacity\nIt delivers 500 units a day [[plant#Capacity]].\n" founded-claim)))]
      (is (contains? (failing r) :every-claim-quoted?)))))

(deftest a-fact-counts-in-the-claims-own-words
  (testing "stated only inside a quote"
    (let [r (run (wiki (atom-page good-fm capacity-claim "## Founded\nOn its founding [[charter]].\n> \"founded in 1953 by forty households\" [[charter]]\n")))]
      (is (contains? (failing r) :fact/founded))))
  (testing "stated only in a link's target"
    (let [r (run (wiki (atom-page good-fm capacity-claim "## Founded\nSee [the charter](/docs/charter.md#1953) [[charter]].\n> \"founded in 1953 by forty households\" [[charter]]\n")))]
      (is (contains? (failing r) :fact/founded))))
  (testing "a Sources line does not cite for the last claim"
    (let [r (run (wiki (atom-page good-fm capacity-claim "## Founded\nIt was founded in 1953.\n")))]
      (is (contains? (failing r) :fact/founded)))))

(deftest front-matter-is-checked-and-never-crashes-scoring
  (is (map? (run (wiki (atom-page "type: entity\nsource: plant.md\n  - charter.md\nentry-point: x" capacity-claim founded-claim))))
      "a list item after a scalar makes a list, it does not crash scoring")
  (doseq [fm ["type: []\nsource: [plant.md, charter.md]\nentry-point: x"
              "type: entity\nsource: [plant.md, charter.md]\nentry-point: []"
              "type: entity\nsource: [plant.md]\nentry-point: x"
              "type: overview\nsource: [plant.md, charter.md]\nentry-point: x"]]
    (let [r (run (wiki (atom-page fm capacity-claim founded-claim)))]
      (is (contains? (failing r) :front-matter?) fm))))

(deftest links-resolve-from-their-page
  (let [pages (assoc (wiki (atom-page good-fm capacity-claim founded-claim))
                     "/wiki/overviews/o.md" "---\ntype: overview\n---\n# O\n\n- [A](../atoms/a0.md)\n- [P](p.md)\n")
        with-p (assoc pages "/wiki/overviews/p.md" "---\ntype: overview\n---\n# P\n\n- [[atoms/a0]]\n")]
    (is (contains? (failing (run pages)) :links-valid?) "p.md is /wiki/overviews/p.md, which does not exist")
    (is (not (contains? (failing (run with-p)) :links-valid?)))))

(deftest pages-outside-the-tiers-earn-nothing
  (let [r (run (assoc (wiki (atom-page good-fm capacity-claim founded-claim)) "/wiki/extra.md" "# Extra\n\nMore.\n"))]
    (is (contains? (failing r) :no-untiered-pages?))
    (is (< (:reward r) 1.0))))
