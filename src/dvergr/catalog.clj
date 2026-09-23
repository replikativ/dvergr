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
     :wiki/v1 — a wiki from a folder of documents (`dvergr.catalog.wiki`)."
  (:require [clojure.string :as str]
            [dvergr.catalog.wiki :as wiki]
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
    :experiment-plan #(wiki/experiment-plan (assoc % :version 2))}})

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
