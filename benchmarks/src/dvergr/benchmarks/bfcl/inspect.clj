(ns dvergr.benchmarks.bfcl.inspect
  "Read a BFCL experiment directory.

     (def xs (open \".dvergr/benchmarks/bfcl-luna\"))
     (report xs)      ; per candidate: accuracy by category, why it loses
     (failures xs :luna-repl)
     (close xs)"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.agent.conversation :as conv]
            [dvergr.benchmarks.bfcl.core :as bfcl]
            [dvergr.room.store :as store]))

(defn open
  "Open the experiment's store (read it with the functions below, then `close`)."
  [dir]
  (assoc (conv/open-store! dir) :room-id (keyword "bfcl" (.getName (io/file dir)))))

(defn close [xs] (conv/close-store! xs))

(defn attempts
  "Certified Attempts of the experiment, oldest first."
  [{:keys [store room-id]}]
  (->> (store/-list-attempts store room-id {:limit 1000000})
       (sort-by #(get-in % [:attempt/receipt :attempt/started-at]))
       vec))

(defn- row [attempt]
  (let [receipt (:attempt/receipt attempt)
        checks (:attempt/checks receipt)]
    {:candidate (get-in attempt [:attempt/agent :agent/id])
     :task-id (get-in attempt [:attempt/environment :environment/task :task-id])
     :category (get-in attempt [:attempt/environment :environment/task :category])
     :valid? (= 1.0 (:attempt/reward receipt))
     :error-type (some (fn [[k v]] (when (and (false? v) (= "bfcl.error" (namespace k))) (name k)))
                       checks)
     :calls (get-in attempt [:attempt/evidence :calls])
     :content (get-in attempt [:attempt/evidence :content])
     :usage (get-in attempt [:attempt/evidence :episode :usage])
     :unsatisfiable? (contains? bfcl/unsatisfiable
                                (get-in attempt [:attempt/environment :environment/task :task-id]))}))

(defn rows
  "One row per Attempt: `{:candidate :task-id :category :valid? :error-type
   :calls :content :usage :unsatisfiable?}`."
  [xs]
  (mapv row (attempts xs)))

(defn report
  "`{candidate {:tasks n :passed n :summary (bfcl/summary ..) :errors
   {error-type n}}}` of the experiment."
  [xs]
  (into (sorted-map)
        (map (fn [[candidate rs]]
               [candidate
                {:tasks (count rs)
                 :passed (count (filter :valid? rs))
                 :summary (bfcl/summary
                           (into {} (map (fn [[category crs]]
                                           [category {:correct (count (filter :valid? crs))
                                                      :total (count crs)}]))
                                 (group-by :category rs)))
                 :errors (into (sorted-map) (frequencies (keep :error-type rs)))}]))
        (group-by :candidate (rows xs))))

(defn failures
  "The failed rows of `candidate`, with what it emitted."
  [xs candidate]
  (->> (rows xs)
       (filter #(and (= candidate (:candidate %)) (not (:valid? %))))
       (mapv #(select-keys % [:task-id :category :error-type :calls :content]))))

(defn print-report [xs]
  (doseq [[candidate {:keys [tasks passed summary errors]}] (report xs)]
    (println (str (name candidate) ": " passed "/" tasks))
    (doseq [[category accuracy] (:categories summary)]
      (println (format "  %-24s %.2f" category accuracy)))
    (when (seq errors)
      (println "  lost on:" (str/join ", " (map (fn [[e n]] (str e " x" n)) errors))))))
