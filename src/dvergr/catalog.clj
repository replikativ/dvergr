(ns dvergr.catalog
  "Workflows that come with their own measure: a task, a trusted checker that
   scores what an attempt left in its world, and a benchmark set (fixtures with
   known answers) to compare models and prompts on.

   A catalog workflow runs through `dvergr.agent.workflow` like any task: each
   attempt on its own fork, certified and billed. The difference is the score.
   On the benchmark set it measures against known facts; on a user's own room
   it checks what can be checked without them (structure, citations, links).

   Workflows:
     :wiki/v1 — a wiki from a folder of documents."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.substrate.geschichte :as gs]
            [muschel.fs :as mfs]
            [org.replikativ.spindel.engine.core :as ec]))

;; ============================================================================
;; Workspace access
;; ============================================================================

(def ^:private max-files 500)
(def ^:private max-bytes (* 2 1024 1024))

(defn- room-fs [room]
  (binding [ec/*execution-context* (:ctx room)]
    (gs/filesystem)))

(defn read-tree
  "`{path text}` of the files under `dir` in `room`'s workspace (recursive,
   bounded by file count and total size)."
  [room dir]
  (let [fs (room-fs room)
        total (atom 0)]
    (letfn [(walk [path acc]
              (reduce (fn [acc {:keys [type] entry-path :path}]
                        (cond
                          (<= max-files (count acc)) (reduced acc)
                          (= :dir type) (walk entry-path acc)
                          (= :file type)
                          (let [text (str (mfs/read-file fs entry-path))]
                            (if (< max-bytes (swap! total + (count text)))
                              (reduced acc)
                              (assoc acc entry-path text)))
                          :else acc))
                      acc
                      (try (mfs/list-dir fs path) (catch Throwable _ nil))))]
      (if (mfs/exists? fs dir) (walk dir {}) {}))))

(defn seed!
  "Write `files` (`{path text}`, absolute workspace paths) into `room`'s
   workspace."
  [room files]
  (let [fs (room-fs room)]
    (doseq [[path text] (sort files)]
      (let [parent (subs path 0 (max 1 (str/last-index-of path "/")))]
        (loop [dirs (reverse (take-while #(not= "/" %) (iterate #(subs % 0 (max 1 (str/last-index-of % "/"))) parent)))]
          (when-let [d (first dirs)]
            (when-not (mfs/exists? fs d) (mfs/mkdir fs d))
            (recur (rest dirs)))))
      (when-not (mfs/write-string! fs path text false)
        (throw (ex-info (str "Could not write " path) {:type ::seed-failed :path path}))))
    ;; Committed: a merge into a dirty workspace is refused.
    ((requiring-resolve 'dvergr.rooms.forks/commit-workspace!) room
                                                               (str "Seed " (count files) " files"))
    (count files)))

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

(defn- wiki-evaluator [{:keys [source target gold]}]
  (evaluation/make-evaluator
   {:id :catalog/wiki :version 1
    ;; Runs on the host against the attempt's world, after its Run: portable
    ;; text only, bounded, so the evidence can be kept with the Attempt.
    :capture (fn [{world :world/room}]
               {:pages (read-tree world target)
                :sources (read-tree world source)})
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

;; ============================================================================
;; The catalog
;; ============================================================================

(defn- resource-tree
  "`{\"/docs/x.md\" text}` for the files listed under a resource directory."
  [dir names]
  (into {} (for [n names] [(str "/docs/" n) (slurp (io/resource (str dir "/docs/" n)))])))

(def ^:private wiki-v1-docs
  ["company.md" "products.md" "people.md" "incident-2016.md" "customers.md" "research.md"])

(defn- wiki-task [{:keys [source target]}]
  (str "The folder " source " contains source documents. Write a wiki in " target " from them:\n"
       "- one Markdown page per important entity (organisation, person, product, event);\n"
       "- " target "/index.md linking every page;\n"
       "- links between related pages, as relative Markdown links;\n"
       "- on every page at least one citation of the document it draws on, as a relative "
       "Markdown link such as [source](../" (subs source 1) "/products.md).\n"
       "State only what the sources say. Use the file tools to read and write files."))

(def workflows
  "The catalog, by id."
  {:wiki/v1
   {:id :wiki/v1
    :title "Wiki from a folder of documents"
    :doc (str "Turns the documents in /docs into a linked wiki in /wiki with an index and a "
              "citation on every page. Scored on the benchmark set against ten known facts; on "
              "your own room by structure, citations and links.")
    :params {:source "/docs" :target "/wiki"}
    :profile "developer"
    :task wiki-task
    :evaluator (fn [params gold] (wiki-evaluator (assoc params :gold gold)))
    :benchmark {:fixtures #(resource-tree "dvergr/catalog/wiki-v1" wiki-v1-docs)
                :gold #(edn/read-string (slurp (io/resource "dvergr/catalog/wiki-v1/gold.edn")))}}})

(defn lookup
  "The catalog workflow `id` (keyword or \"ns/name\" string), or throws."
  [id]
  (or (get workflows (if (keyword? id) id (keyword (str id))))
      (throw (ex-info (str "No catalog workflow " id "; known: "
                           (str/join ", " (map #(subs (str %) 1) (keys workflows))))
                      {:type ::unknown-workflow :id id}))))

(defn describe [{:keys [id title doc params profile]}]
  {:id (subs (str id) 1) :title title :doc doc :params params :profile profile})

(defn plan
  "What `workflow/start` needs for catalog workflow `wf`: the task text, the
   evaluator, the environment id, and whether gold facts score it (benchmark
   mode, on the fixtures) or structure alone (a user's room)."
  [wf {:keys [benchmark?]}]
  (let [params (:params wf)
        gold (when benchmark? ((get-in wf [:benchmark :gold])))]
    {:task ((:task wf) params)
     :profile (:profile wf)
     :evaluator ((:evaluator wf) params gold)
     :environment-id (keyword "catalog" (str (namespace (:id wf)) "-" (name (:id wf))
                                             (when benchmark? "-bench")))}))
