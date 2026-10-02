(ns dvergr.benchmarks.bird.experiment
  "BIRD experiments (`dvergr.agent.experiment.runner`).

     (run! {:dir \"~/.cache/dvergr-bench/bird-dev\" :db-ids [\"superhero\" \"toxicology\"]
            :sample 10 :repetitions 2
            :candidates [{:id :sqlite :model \"codex-subscription-luna\" :engine :sqlite}
                         {:id :datalog :model \"codex-subscription-luna\" :engine :datalog}]})

   `:sample` takes that many questions per database, the same ones for a
   given `:seed`."
  (:refer-clojure :exclude [run!])
  (:require [dvergr.agent.experiment.runner :as runner]
            [dvergr.benchmarks.bird.core :as bird]
            [dvergr.benchmarks.bird.provider :as provider]
            [dvergr.benchmarks.pyjson :as pj]
            [hasch.core :as hasch])
  (:import [java.util ArrayList Collections Random]))

(defn split-of
  "`:dev` or `:eval` for a question: a third are `:dev`, fixed by the digest
   of `db-id/question-id`. Tune on `:dev`, report on `:eval`."
  [{:keys [db-id question-id]}]
  (if (< (Long/parseLong (subs (pj/sha256-hex (str db-id "/" question-id)) 0 8) 16) (quot 0x100000000 3))
    :dev
    :eval))

(defn select-questions
  [{:keys [db-ids sample seed question-ids split] :or {seed 20260928}}]
  (let [qs (bird/questions)
        qs (cond->> qs
             (seq db-ids) (filter #((set db-ids) (:db-id %)))
             split (filter #(= split (split-of %))))]
    (if question-ids
      (filterv #((set question-ids) (:question-id %)) qs)
      (into [] (mapcat (fn [[_ qs]]
                         (if (or (nil? sample) (>= sample (count qs)))
                           qs
                           (let [l (ArrayList. ^java.util.Collection (vec qs))]
                             (Collections/shuffle l (Random. (long seed)))
                             (let [chosen (set (map :question-id (take sample l)))]
                               (filterv #(chosen (:question-id %)) qs))))))
            (sort-by key (group-by :db-id qs))))))

(defn run!
  [{:keys [candidates agent-generate max-turns] :or {max-turns 20} :as opts}]
  (let [selected (select-questions opts)
        caps (provider/capabilities selected {:agent-generate agent-generate})]
    (runner/run!
     (merge
      (select-keys opts [:dir :repetitions :parallelism :experiment-id :fault-retries :claude-cli :claude-env
                         :host-context-note :usage-pause-threshold :usage-retries
                         :preflight :allowance])
      {:benchmark :bird
       :capabilities caps
       :environments (mapv #(provider/environment-def % caps {:max-turns max-turns}) selected)
       :team (provider/candidate-roster candidates)
       :models candidates
       :dataset {:id (keyword "bird" (str "questions-" (hasch/uuid (mapv (juxt :db-id :question-id) selected))))
                 :metadata {:upstream "BIRD dev 2024-06-27" :questions (count selected)}}
       :metadata {:sample (:sample opts) :seed (:seed opts)}}))))
