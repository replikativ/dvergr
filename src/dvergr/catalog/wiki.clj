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
                 (ws/open-memory-workspace! world)
                 (ws/seed! world files)
                 {:fixtures digest :files (count files)})})))

(defn environment
  "The EnvironmentDef of the v1 benchmark set under `setup` and `ev`."
  [setup ev {:keys [timeout-ms]}]
  (let [ref (evaluation/evaluator-ref ev)]
    (environment/make-environment
     {:id :catalog/wiki-v1
      :task (task params)
      :verifier {:id (:verifier/id ref) :version (:verifier/version ref)}
      :limits {:timeout-ms (or timeout-ms (* 10 60 1000)) :cancel-timeout-ms 30000}
      :world {:isolation :ctx :settlement :discard
              :setup (evaluation/world-setup-ref setup)}})))

(defn experiment!
  "Run the v1 benchmark set as an experiment: `repetitions` attempts per
   model in `models`, each in a discarded world, folded into a Scorecard.
   `dir` is the experiment directory. Call from a dedicated JVM or REPL (the
   runner moves Dvergr's state root into `dir`)."
  [{:keys [dir models repetitions budget-dollars timeout-ms prompt parallelism]
    :or {repetitions 1}}]
  (let [files (fixtures)
        setup (world-setup files)
        ev (evaluator (assoc params :gold (gold)))
        {:keys [team ids]} (workflow/candidates {:models models :profile "developer"
                                                 :budget-dollars budget-dollars :prompt prompt})]
    ((requiring-resolve 'dvergr.agent.experiment.runner/run!)
     {:dir dir
      :benchmark :catalog-wiki
      :capabilities {:world-setup setup :evaluator ev}
      :environments [(environment setup ev {:timeout-ms timeout-ms})]
      :team team
      :models (mapv #(:agent/model-policy (roster/agent team %)) ids)
      :dataset {:id :catalog/wiki-v1 :metadata {:fixtures (str (hasch/uuid files))}}
      :repetitions repetitions
      :parallelism (or parallelism 1)})))
