(ns dvergr.catalog
  "Workflows that come with their own measure: a task, a trusted checker that
   scores what an attempt left in its world, and a benchmark set (fixtures with
   known answers) to compare models and prompts on.

   A catalog workflow runs through `dvergr.agent.workflow` like any task: each
   attempt on its own fork, certified and billed, the world kept for review.
   On the benchmark set it measures against known facts; on a user's own room
   it checks what can be checked without them (structure, citations, links).
   The same checker runs as an experiment (discarded worlds, a Scorecard)
   from the workflow's own namespace, e.g. `dvergr.catalog.wiki/experiment!`.

   Workflows:
     :wiki/v1 — a wiki from a folder of documents (`dvergr.catalog.wiki`);
     :wiki/v2 — the same, on a harder hand-written world;
     :wiki/v3 — v2's checker on generated worlds (`dvergr.catalog.wiki-gen`)."
  (:require [clojure.string :as str]
            [dvergr.agent.evaluators :as evaluators]
            [dvergr.catalog.wiki :as wiki]
            [dvergr.catalog.wiki-gen :as gen]
            [dvergr.catalog.workspace :as ws]))

(def seed! ws/seed!)
(def read-tree ws/read-tree)
(def score-wiki wiki/score-wiki)

(def workflows
  "The catalog, by id."
  {:wiki/v1
   {:id :wiki/v1
    :title "Wiki from a folder of documents"
    :doc (str "Turns the documents in /docs into a linked wiki in /wiki with an index and a "
              "citation on every page. Scored on the benchmark set against ten known facts; on "
              "your own room by structure, citations and links.")
    :params wiki/params
    :profile "developer"
    :task wiki/task
    :evaluator (fn [params gold] (wiki/evaluator (assoc params :gold gold)))
    :benchmark {:fixtures wiki/fixtures :gold wiki/gold}
    :experiment-plan #(wiki/experiment-plan (assoc % :version 1))}

   :wiki/v2
   {:id :wiki/v2
    :title "Wiki from a folder of documents (v2)"
    :doc (str "The wiki workflow scored on a harder set: dated sources of different authority, "
              "outdated and wrong values, a distractor. Measures facts attributed to their source, "
              "currency, grounded numbers, entity pages and relations. On your own room: v1's "
              "structural checks.")
    :params wiki/params
    :profile "developer"
    :task wiki/task-v2
    :evaluator (fn [params gold]
                 (if gold
                   (wiki/evaluator-v2 (assoc params :gold gold))
                   (wiki/evaluator params)))
    :benchmark {:fixtures wiki/fixtures-v2 :gold wiki/gold-v2}
    :experiment-plan #(wiki/experiment-plan (assoc % :version 2))}

   :wiki/v3
   {:id :wiki/v3
    :title "Wiki from a folder of documents (v3, generated worlds)"
    :doc (str "v2's checker on many generated worlds: each an organisation of one of several kinds, "
              "its documents of different date and authority (stale values, a wrong blog, a "
              "distractor) and its gold derived from the world. A public dev split and a held-out "
              "test split; on your own room: v1's structural checks.")
    :params wiki/params
    :profile "developer"
    :task wiki/task-v2
    :evaluator (fn [params gold]
                 (if gold
                   (wiki/evaluator-v2 (assoc params :gold gold))
                   (wiki/evaluator params)))
    ;; One world, for catalog_start's benchmark mode; catalog_benchmark runs many.
    :benchmark {:fixtures #(gen/documents (gen/world 1)) :gold #(gen/gold (gen/world 1))}
    :experiment-plan #(wiki/experiment-plan (assoc % :version 3))}})

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

;; The benchmark sets' checkers and fixtures, offered to experiments agents
;; start (`dvergr.agent/run-experiment!`): scored against the gold facts.
(evaluators/register-evaluator! (wiki/evaluator (assoc wiki/params :gold (wiki/gold))))
(evaluators/register-evaluator! (wiki/evaluator-v2 (assoc wiki/params :gold (wiki/gold-v2))))
(evaluators/register-world-setup! (wiki/world-setup (wiki/fixtures)))
(evaluators/register-world-setup! (wiki/world-setup (wiki/fixtures-v2)))
