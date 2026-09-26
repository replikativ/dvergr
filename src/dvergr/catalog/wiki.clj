(ns dvergr.catalog.wiki
  "wiki/v1: a linked wiki from a folder of documents, with its checker, its
   benchmark set, and the pieces that run it as an experiment (the same
   contract as the tau2 and BFCL providers, doc/benchmarks.md).

     world setup   gives the attempt's world a workspace of its own and seeds
                   the fixtures into /docs (committed).
     program       the ordinary LLM agent with file tools: no protocol.
     evaluator     captures /wiki and /docs from the world after the Run and
                   scores them (`score-wiki`); the gold facts stay in its
                   closure, an EnvironmentDef names only the variant.

   The workflow path (`dvergr.catalog`, catalog_start) uses the same task and
   checker on a room kept for review; the experiment path discards its worlds
   and folds its Attempts into a Scorecard."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.workflow :as workflow]
            [dvergr.catalog.wiki-gen :as gen]
            [dvergr.catalog.workspace :as ws]
            [hasch.core :as hasch]))

(def params {:source "/docs" :target "/wiki"})

;; ============================================================================
;; The wiki checker
;; ============================================================================

(defn- normalize-path
  "Resolve `target` (relative or absolute) against the directory of `from`."
  [from target]
  (let [base (if (str/starts-with? target "/")
               []
               (vec (butlast (remove str/blank? (str/split from #"/")))))
        segs (reduce (fn [acc s]
                       (case s
                         ("" ".") acc
                         ".." (if (seq acc) (pop acc) acc)
                         (conj acc s)))
                     base
                     (str/split target #"/"))]
    (str "/" (str/join "/" segs))))

(def ^:private link-re #"\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")

(defn- links-of [path text]
  (for [[_ target] (re-seq link-re text)
        :let [t (first (str/split target #"#" 2))]
        :when (and (not (str/blank? t))
                   (not (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*:" t)))]
    (normalize-path path t)))

(defn- ratio [n d] (if (pos? d) (double (/ n d)) 0.0))

(defn score-wiki
  "Score a wiki (`pages`: `{path text}` under `target`) built from `sources`
   (the source paths, or `{path text}`), against `gold` facts when given.
   Returns `{:scores {measure number} :checks {check boolean} :reward r}`,
   r in [0, 1]: `:checks` has one entry per gold fact (`:fact/<id>`)."
  [{:keys [pages sources gold target source]}]
  (let [sources (set (if (map? sources) (keys sources) sources))
        pages (into {} (filter (fn [[p _]] (str/ends-with? p ".md"))) pages)
        index-path (str target "/index.md")
        content (remove #(= index-path (key %)) pages)
        links (into {} (map (fn [[p t]] [p (links-of p t)])) pages)
        internal (for [[p ls] links l ls :when (str/starts-with? l (str target "/"))] [p l])
        cites (for [[p ls] links l ls :when (str/starts-with? l (str source "/"))] [p l])
        valid-cite? (fn [[_ l]] (contains? sources l))
        cited-pages (set (map first (filter valid-cite? cites)))
        index-links (set (get links index-path))
        text (str/lower-case (str/join "\n" (vals pages)))
        facts (when (seq gold)
                (filterv (fn [{:keys [terms]}]
                           (every? #(str/includes? text (str/lower-case %)) terms))
                         gold))
        cited-sources (set (filter #(contains? sources %) (map second cites)))
        coverage (if (seq gold)
                   (ratio (count facts) (count gold))
                   (ratio (count cited-sources) (count sources)))
        index? (and (contains? pages index-path)
                    (<= 0.8 (ratio (count (filter #(contains? index-links (key %)) content))
                                   (count content))))
        scores {:pages (count pages)
                :coverage coverage
                :coverage-basis (if (seq gold) "gold-facts" "sources-cited")
                :pages-cited (ratio (count (filter #(contains? cited-pages (key %)) content))
                                    (count content))
                :citations-valid (ratio (count (filter valid-cite? cites)) (count cites))
                :links-valid (ratio (count (filter (fn [[_ l]] (contains? pages l)) internal))
                                    (count internal))}
        found (set (map :id facts))
        checks (into {:has-pages? (boolean (seq content))
                      :index? (boolean index?)
                      :every-page-cited? (= 1.0 (:pages-cited scores))
                      :citations-valid? (= 1.0 (:citations-valid scores))
                      :links-valid? (= 1.0 (:links-valid scores))
                      :coverage-complete? (= 1.0 coverage)}
                     (for [{:keys [id]} gold] [(keyword "fact" (name id)) (contains? found id)]))
        reward (if (empty? content)
                 0.0
                 (+ (* 0.4 coverage)
                    (* 0.25 (:pages-cited scores))
                    (* 0.1 (:citations-valid scores))
                    (* 0.15 (:links-valid scores))
                    (* 0.1 (if index? 1.0 0.0))))]
    {:scores scores :checks checks :reward (/ (Math/round (* 1000 reward)) 1000.0)}))

(defn evaluator
  "The wiki checker as an Evaluator, scoring against `gold` facts (nil: by
   sources cited)."
  [{:keys [source target gold]}]
  (evaluation/make-evaluator
   {:id :catalog/wiki :version 1
    ;; Runs on the host against the attempt's world, after its Run: portable
    ;; text only, bounded, so the evidence can be kept with the Attempt.
    :capture (fn [{world :world/room}]
               {:pages (ws/read-tree world target)
                :sources (ws/read-tree world source)})
    :observe (fn [{:keys [default result] captured :execution/evidence}]
               (let [sources (vec (sort (keys (:sources captured))))]
                 (assoc default
                        :run-status (:run/status result)
                        :pages (:pages captured)
                        ;; Membership is all the checker needs of the sources.
                        :sources sources
                        :scores (:scores (score-wiki {:pages (:pages captured) :sources sources
                                                      :gold gold :target target :source source})))))
    :verify (fn [_ {:keys [run-status pages sources]}]
              (let [{:keys [checks reward]}
                    (score-wiki {:pages pages :sources sources :gold gold
                                 :target target :source source})]
                {:checks (assoc checks :completed? (= :completed run-status))
                 :reward (if (= :completed run-status) reward 0.0)}))}))

(defn- resource-tree
  "`{\"/docs/x.md\" text}` for the files listed under a resource directory."
  [dir names]
  (into {} (for [n names] [(str "/docs/" n) (slurp (io/resource (str dir "/docs/" n)))])))

(def ^:private wiki-v1-docs
  ["company.md" "products.md" "people.md" "incident-2016.md" "customers.md" "research.md"])

(defn task
  "The instruction an attempt is given."
  [{:keys [source target]}]
  (str "The folder " source " contains source documents. Write a wiki in " target " from them:\n"
       "- one Markdown page per important entity (organisation, person, product, event);\n"
       "- " target "/index.md linking every page;\n"
       "- links between related pages, as relative Markdown links;\n"
       "- on every page at least one citation of the document it draws on, as a relative "
       "Markdown link such as [source](../" (subs source 1) "/products.md).\n"
       "State only what the sources say. Use the file tools to read and write files."))

(defn fixtures
  "The v1 benchmark set: `{\"/docs/x.md\" text}`."
  []
  (resource-tree "dvergr/catalog/wiki-v1" wiki-v1-docs))

(defn gold
  "The v1 gold facts."
  []
  (edn/read-string (slurp (io/resource "dvergr/catalog/wiki-v1/gold.edn"))))

;; ============================================================================
;; v2: facts attributed to their source, currency, grounding, entities
;; ============================================================================
;;
;; v1 counted a fact wherever its terms appeared, so a page that pasted every
;; source scored full marks, and nothing penalised an invented number or a
;; value that is no longer true. v2 scores what a reader relies on:
;;
;;   coverage    a fact counts on a page that states it AND cites a document
;;               it comes from, and that is not a copy of a source.
;;   currency    a superseded or wrong value (an old name, an old count, a
;;               blog's wrong year) only near a qualifier ("formerly", "in
;;               2010", "the blog claims").
;;   grounding   every number of two or more digits on a page appears in a
;;               source the page cites (or is a gold synthesis value).
;;   entities    a page per gold entity; relations as links between them.
;;   distractors nothing of the unrelated organisation in the corpus.
;;
;; Calibration (test/dvergr/catalog/wiki_calibration_test.clj) pins that a
;; hand-written reference wiki scores top and that each damaged variant (a
;; dump, stale values, invented numbers, no citations, ...) loses the checks
;; it should.

(def ^:private v2-docs
  ["charter-1953.md" "blog-history.md" "annual-report-2010.md" "newsletter-2012.md"
   "annual-report-2014.md" "press-release-rename.md" "press-release-rename-copy.md"
   "incident-2018.md" "board-minutes-2019.md" "annual-report-2022.md" "agenda-2023.md"
   "morrow-ridge.md"])

(defn fixtures-v2 [] (resource-tree "dvergr/catalog/wiki-v2" v2-docs))

(defn gold-v2 [] (edn/read-string (slurp (io/resource "dvergr/catalog/wiki-v2/gold.edn"))))

(defn- lower [s] (str/lower-case (str s)))

(defn- term-in? [text term]
  (let [t (lower text)]
    (if (string? term)
      (str/includes? t (lower term))
      (boolean (some #(str/includes? t (lower %)) term)))))

(defn- strip-link-targets
  "Page text without link targets (`](…)`): a cited file name is not a claim."
  [text]
  (str/replace text #"\]\([^)]*\)" "]"))

(defn- words [text] (re-seq #"[a-z0-9']+" (lower text)))

(defn- shingles [text n]
  (into #{} (map #(str/join " " %)) (partition n 1 (words text))))

(defn- numbers
  "The numbers of two or more digits in `text`, normalised (`5,050` → `5050`)."
  [text]
  (into #{}
        (comp (map #(str/replace % #"[,.]" ""))
              (filter #(<= 2 (count %))))
        (re-seq #"\d[\d,.]*\d|\d" (strip-link-targets text))))

(def ^:private number-words
  (let [ones ["zero" "one" "two" "three" "four" "five" "six" "seven" "eight" "nine" "ten"
              "eleven" "twelve" "thirteen" "fourteen" "fifteen" "sixteen" "seventeen"
              "eighteen" "nineteen"]
        tens {"twenty" 20 "thirty" 30 "forty" 40 "fifty" 50 "sixty" 60 "seventy" 70
              "eighty" 80 "ninety" 90}]
    (merge (zipmap ones (range))
           tens
           (into {} (for [[t n] tens [o i] (map vector (rest (take 10 ones)) (range 1 10))]
                      [(str t "-" o) (+ n i)])))))

(defn- source-numbers
  "The numbers a source states, in digits or in words (forty-two is 42): a
   page may write either."
  [text]
  (into (numbers text)
        (comp (keep number-words) (map str) (filter #(<= 2 (count %))))
        (re-seq #"[a-z]+(?:-[a-z]+)?" (lower text))))

(defn- file-name [path] (last (str/split path #"/")))

(defn- page-title [path text]
  (or (some->> (re-find #"(?m)^#\s+(.+)$" text) second str/trim)
      (file-name path)))

(defn- slug [s] (-> (lower s) (str/replace #"[^a-z0-9]+" "-") (str/replace #"(^-|-$)" "")))

(defn- entity-page
  "The page for an entity named by `aliases`: its title or file name names it."
  [pages aliases]
  (some (fn [[path text]]
          (let [title (lower (page-title path text))
                fname (file-name path)]
            (when (some #(or (str/includes? title (lower %))
                             (str/includes? fname (slug %)))
                        aliases)
              path)))
        (sort pages)))

(defn- qualified?
  "Whether the occurrence of `value` at `idx` in `text` sits near a qualifier."
  [text idx value ok-near]
  (let [t (lower text)
        from (max 0 (- idx 120))
        to (min (count t) (+ idx (count value) 120))
        window (str (subs t from idx) " " (subs t (min to (+ idx (count value))) to))]
    (boolean (some #(str/includes? window (lower %)) ok-near))))

(defn- occurrences [text value]
  (let [t (lower text) v (lower value)]
    (loop [from 0 acc []]
      (let [i (str/index-of t v from)]
        (if (nil? i) acc (recur (+ i (count v)) (conj acc i)))))))

(defn- ratio* [n d] (if (pos? d) (double (/ n d)) 1.0))

(defn score-wiki-v2
  "Score a wiki against the v2 gold (`dvergr/catalog/wiki-v2/gold.edn`).
   `pages` and `sources` are `{path text}` (`sources` may be the paths only
   when coverage and grounding are not needed). Returns `{:scores :checks
   :reward}`; see the section comment."
  [{:keys [pages sources gold target source]}]
  (let [{:keys [entities facts stale relations distractors]} gold
        pages (into {} (filter (fn [[p _]] (str/ends-with? p ".md"))) pages)
        index-path (str target "/index.md")
        content (into {} (remove #(= index-path (key %))) pages)
        source-text (fn [path] (get sources path ""))
        links (into {} (map (fn [[p t]] [p (links-of p t)])) pages)
        cited (fn [page] (into #{} (filter #(contains? sources %)) (get links page)))
        cites (for [[p ls] links l ls :when (str/starts-with? l (str source "/"))] [p l])
        internal (for [[p ls] links l ls :when (str/starts-with? l (str target "/"))] [p l])
        ;; copies: a page most of whose 8-word shingles are a source's
        source-shingles (reduce into #{} (map #(shingles % 8) (vals sources)))
        copied (into #{}
                     (keep (fn [[p t]]
                             (let [sh (shingles (strip-link-targets t) 8)]
                               (when (and (<= 40 (count sh))
                                          (<= 0.75 (ratio* (count (filter source-shingles sh)) (count sh))))
                                 p))))
                     content)
        own (apply dissoc content copied)
        found (into #{}
                    (keep (fn [{:keys [id terms] srcs :sources}]
                            (let [wanted (set (map #(str source "/" %) srcs))]
                              (when (some (fn [[p t]]
                                            (and (every? #(term-in? (strip-link-targets t) %) terms)
                                                 (some wanted (cited p))))
                                          own)
                                id))))
                    facts)
        stale-hits (into #{}
                         (keep (fn [{:keys [id value ok-near]}]
                                 (when (some (fn [[_ t]]
                                               (let [t (strip-link-targets t)]
                                                 (some (fn [v] (some #(not (qualified? t % v ok-near))
                                                                     (occurrences t v)))
                                                       value)))
                                             pages)
                                   id)))
                         stale)
        allowed (into #{} (comp (filter :synthesis) (mapcat :terms) (mapcat #(if (string? %) [%] %))
                                (mapcat numbers))
                      facts)
        number-claims (for [[p t] content n (numbers t)] [p n])
        unsupported (vec (for [[p n] number-claims
                               :when (not (or (contains? allowed n)
                                              (some #(contains? (source-numbers (source-text %)) n) (cited p))))]
                           {:page p :number n}))
        entity-pages (into {} (for [[id aliases] entities] [id (entity-page content aliases)]))
        linked? (fn [a b] (let [pa (entity-pages a) pb (entity-pages b)]
                            (boolean (and pa pb (or (some #{pb} (get links pa))
                                                    (some #{pa} (get links pb)))))))
        rels (filter (fn [[a b]] (linked? a b)) relations)
        text (lower (str/join "\n" (vals pages)))
        contamination (filterv #(str/includes? text (lower %)) distractors)
        cited-pages (count (filter #(seq (cited (key %))) content))
        index-links (set (get links index-path))
        index? (and (contains? pages index-path)
                    (<= 0.8 (ratio* (count (filter #(contains? index-links (key %)) content)) (count content))))
        scores {:pages (count pages)
                :coverage (ratio* (count found) (count facts))
                :currency (ratio* (- (count stale) (count stale-hits)) (count stale))
                :grounding (ratio* (- (count number-claims) (count unsupported)) (count number-claims))
                :entities (ratio* (count (filter val entity-pages)) (count entities))
                :relations (ratio* (count rels) (count relations))
                :pages-cited (ratio* cited-pages (count content))
                :citations-valid (ratio* (count (filter (fn [[_ l]] (contains? sources l)) cites)) (count cites))
                :links-valid (ratio* (count (filter (fn [[_ l]] (contains? pages l)) internal)) (count internal))
                :copied-pages (vec (sort copied))
                :stale-values (vec (sort stale-hits))
                :unsupported-numbers (vec (take 10 unsupported))
                :distractor-terms contamination}
        checks (-> {:has-pages? (boolean (seq content))
                    :index? (boolean index?)
                    :every-page-cited? (= (count content) cited-pages)
                    :citations-valid? (= 1.0 (:citations-valid scores))
                    :links-valid? (= 1.0 (:links-valid scores))
                    :grounded? (<= 0.95 (:grounding scores))
                    :no-copied-pages? (empty? copied)
                    :no-distractor-facts? (empty? contamination)}
                   (into (for [{:keys [id]} facts] [(keyword "fact" (name id)) (contains? found id)]))
                   (into (for [{:keys [id]} stale] [(keyword "current" (name id)) (not (contains? stale-hits id))]))
                   (into (for [[id p] entity-pages] [(keyword "entity" (name id)) (some? p)])))
        ;; An invented number is the error a reader cannot see: each 1% of
        ;; unsupported numbers costs 5% of the grounding weight.
        grounding-credit (max 0.0 (- 1.0 (* 5.0 (- 1.0 (:grounding scores)))))
        reward (if (empty? content)
                 0.0
                 (+ (* 0.25 (:coverage scores))
                    (* 0.15 (:currency scores))
                    (* 0.20 grounding-credit)
                    (* 0.10 (:entities scores))
                    (* 0.05 (:relations scores))
                    (* 0.05 (:pages-cited scores))
                    (* 0.05 (:citations-valid scores))
                    (* 0.05 (:links-valid scores))
                    (* 0.05 (if index? 1.0 0.0))
                    (* 0.05 (if (empty? contamination) 1.0 0.0))))]
    {:scores scores :checks checks :reward (/ (Math/round (* 1000 reward)) 1000.0)}))

(defn evaluator-v2
  "The v2 checker as an Evaluator. Sources are captured with their text:
   grounding needs it."
  [{:keys [source target gold]}]
  (evaluation/make-evaluator
   {:id :catalog/wiki :version 2
    :capture (fn [{world :world/room}]
               {:pages (ws/read-tree world target)
                :sources (ws/read-tree world source)})
    :observe (fn [{:keys [default result] captured :execution/evidence}]
               (let [scored (score-wiki-v2 {:pages (:pages captured) :sources (:sources captured)
                                            :gold gold :target target :source source})]
                 (assoc default
                        :run-status (:run/status result)
                        :pages (:pages captured)
                        :scores (:scores scored)
                        :checks (:checks scored)
                        :reward (:reward scored))))
    :verify (fn [_ {:keys [run-status checks reward]}]
              {:checks (assoc checks :completed? (= :completed run-status))
               :reward (if (= :completed run-status) reward 0.0)})}))

(defn task-v2
  "The v2 instruction: v1's, plus what v2 scores."
  [p]
  (str (task p) "\n"
       "The documents differ in authority and date; some are outdated or wrong. State the "
       "current value and mark older ones as former. Do not copy documents verbatim, and "
       "do not include facts about organisations other than the one the documents are about."))

;; ============================================================================
;; As an experiment
;; ============================================================================

(defn world-setup
  "Installs `files` (the benchmark set) in each attempt's world: a workspace
   of its own, the files in /docs, committed. Its evidence is their digest."
  [files]
  (let [digest (str (hasch/uuid files))]
    (evaluation/make-world-setup
     {:id :catalog/wiki-fixtures :version 1 :basis {:fixtures digest}
      :prepare (fn [{world :room}]
                 (ws/ensure-workspace! world)
                 (ws/seed! world files)
                 {:fixtures digest :files (count files)})})))

(defn environment
  "The EnvironmentDef of a benchmark set (`version` 1 or 2) under `setup` and
   `ev`."
  [setup ev {:keys [timeout-ms version] :or {version 1}}]
  (let [ref (evaluation/evaluator-ref ev)]
    (environment/make-environment
     {:id (keyword "catalog" (str "wiki-v" version))
      :task (if (= 2 version) (task-v2 params) (task params))
      :verifier {:id (:verifier/id ref) :version (:verifier/version ref)}
      ;; Not finishing in time is the candidate's verdict, not a fault.
      :limits {:timeout-ms (or timeout-ms (* 10 60 1000)) :cancel-timeout-ms 30000
               :on-timeout :verdict}
      :world {:isolation :ctx :settlement :discard
              :setup (evaluation/world-setup-ref setup)}})))

(declare experiment-plan-v1-v2)

;; ============================================================================
;; v3: generated worlds
;; ============================================================================
;;
;; v2's checker on many worlds instead of one (`dvergr.catalog.wiki-gen`): an
;; environment per seed, its documents written into the attempt's world by the
;; setup and its gold derived from the same seed by the evaluator, so one
;; setup and one evaluator serve any number of worlds. The seed is in the
;; environment's metadata (and so in its content id); the dev split is public,
;; the test split is derived from a key the host keeps.

(defn- seed-of [environment]
  (or (get-in environment [:environment/metadata :seed])
      (throw (ex-info "A wiki/v3 environment names its seed" {:type ::no-seed}))))

(defn world-setup-v3
  "Writes the documents of the environment's world into /docs of each
   attempt's world (a workspace of its own, committed)."
  []
  (evaluation/make-world-setup
   {:id :catalog/wiki-generated :version 1 :basis {:generator gen/version}
    :prepare (fn [{world :room environment :environment}]
               (let [seed (seed-of environment)
                     docs (gen/documents (gen/world seed))]
                 (ws/ensure-workspace! world)
                 (ws/seed! world docs)
                 {:seed seed :files (count docs) :fixtures (str (hasch/uuid docs))}))}))

(defn evaluator-v3
  "v2's checker, against the gold of the environment's world."
  [{:keys [source target]}]
  (evaluation/make-evaluator
   {:id :catalog/wiki :version 3
    :capture (fn [{world :world/room}]
               {:pages (ws/read-tree world target)
                :sources (ws/read-tree world source)})
    :observe (fn [{:keys [default result environment] captured :execution/evidence}]
               (let [scored (score-wiki-v2 {:pages (:pages captured) :sources (:sources captured)
                                            :gold (gen/gold (gen/world (seed-of environment)))
                                            :target target :source source})]
                 (assoc default
                        :run-status (:run/status result)
                        :pages (:pages captured)
                        :scores (:scores scored)
                        :checks (:checks scored)
                        :reward (:reward scored))))
    :verify (fn [_ {:keys [run-status checks reward]}]
              {:checks (assoc checks :completed? (= :completed run-status))
               :reward (if (= :completed run-status) reward 0.0)})}))

(defn environments-v3
  "One EnvironmentDef per seed of `split` (`:dev`, or `:test` with `test-key`)."
  [setup ev {:keys [timeout-ms split n seeds test-key] :or {split :dev n 6}}]
  (let [ref (evaluation/evaluator-ref ev)]
    (mapv (fn [seed]
            (environment/make-environment
             {:id :catalog/wiki-v3
              :task (task-v2 params)
              :verifier {:id (:verifier/id ref) :version (:verifier/version ref)}
              :limits {:timeout-ms (or timeout-ms (* 10 60 1000)) :cancel-timeout-ms 30000
                       :on-timeout :verdict}
              :world {:isolation :ctx :settlement :discard
                      :setup (evaluation/world-setup-ref setup)}
              :metadata {:seed seed :split split :generator gen/version}}))
          (or seeds (gen/seeds split n test-key)))))

(defn experiment-plan
  "What running a benchmark set (`version` 1, the default, 2 or 3) needs,
   wherever it runs: capabilities, environments, the candidate team (one per
   model), the model specs, the dataset. v3 takes `:split` (`:dev`, the
   default, or `:test`), `:n` worlds (default 6), explicit `:seeds`, and the
   held-out split's key (`:test-key`, default the environment variable
   DVERGR_WIKI_TEST_KEY)."
  [{:keys [models budget-dollars timeout-ms prompt version] :or {version 1} :as opts}]
  (if (= 3 version)
    (let [setup (world-setup-v3)
          ev (evaluator-v3 params)
          envs (environments-v3 setup ev (update opts :test-key #(or % (System/getenv "DVERGR_WIKI_TEST_KEY"))))
          {:keys [team ids]} (workflow/candidates {:models models :profile "developer"
                                                   :budget-dollars budget-dollars :prompt prompt})]
      {:benchmark :catalog-wiki
       :capabilities {:world-setup setup :evaluator ev}
       :environments envs
       :team team
       :models (mapv #(:agent/model-policy (roster/agent team %)) ids)
       :dataset {:id :catalog/wiki-v3
                 :metadata {:generator gen/version :split (or (:split opts) :dev)
                            :seeds (mapv #(get-in % [:environment/metadata :seed]) envs)}}})
    (experiment-plan-v1-v2 opts)))

(defn- experiment-plan-v1-v2
  [{:keys [models budget-dollars timeout-ms prompt version] :or {version 1}}]
  (let [v2? (= 2 version)
        files (if v2? (fixtures-v2) (fixtures))
        setup (world-setup files)
        ev (if v2?
             (evaluator-v2 (assoc params :gold (gold-v2)))
             (evaluator (assoc params :gold (gold))))
        {:keys [team ids]} (workflow/candidates {:models models :profile "developer"
                                                 :budget-dollars budget-dollars :prompt prompt})]
    {:benchmark :catalog-wiki
     :capabilities {:world-setup setup :evaluator ev}
     :environments [(environment setup ev {:timeout-ms timeout-ms :version version})]
     :team team
     :models (mapv #(:agent/model-policy (roster/agent team %)) ids)
     :dataset {:id (keyword "catalog" (str "wiki-v" version))
               :metadata {:fixtures (str (hasch/uuid files))}}}))

(defn experiment!
  "Run a benchmark set as an experiment in its own process: `repetitions`
   attempts per model in `models`, each in a discarded world, folded into a
   Scorecard, stored under `dir` (the runner moves Dvergr's state root into
   `dir`: a dedicated JVM or REPL). In a daemon, use the `catalog/benchmark`
   op instead, which runs the same plan in one of its rooms."
  [{:keys [dir repetitions parallelism] :or {repetitions 1} :as opts}]
  ((requiring-resolve 'dvergr.agent.experiment.runner/run!)
   (assoc (experiment-plan opts)
          :dir dir
          :repetitions repetitions
          :parallelism (or parallelism 1))))
