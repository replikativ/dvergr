;; Verdicts of one candidate of a Dvergr experiment, for pairing with a reference run
;; (mcnemar.py). Reads the certified Attempts the experiment keeps in its home (the `:dir`
;; given to `run!`) and keeps, per case, the latest verdict of CANDIDATE; a case passes when
;; its reward is 1.0. The case id is the last part of the environment id
;; (:bird.<db>/q-<question-id>, :spreadsheetbench/t-<task-id>).
;;
;; Run from the repository root, in a JVM of its own (it opens the home):
;;   clojure -J-Xmx2g -M:benchmarks benchmarks/reference/dvergr_verdicts.clj DIR CANDIDATE OUT.json [ROOM]
;; CANDIDATE is the candidate's :id (e.g. luna); ROOM the experiment room's slug when the home
;; keeps several. Experiment directories of the layout before homes (artifacts/) are read by
;; dvergr_verdicts.py instead.
(require '[dvergr.agent.experiment :as experiment]
         '[dvergr.agent.experiment.runner :as runner]
         '[dvergr.room.store :as store]
         '[jsonista.core :as j]
         '[clojure.java.io :as io])

(let [[dir cand out room] *command-line-args*]
  (when-not (and dir cand out)
    (binding [*out* *err*] (println "usage: dvergr_verdicts.clj DIR CANDIDATE OUT.json [ROOM]"))
    (System/exit 2))
  (let [xs (runner/open-experiment dir room)
        verdicts
        (try
          (->> (store/-list-attempts (:store xs) (:room-id xs) {:limit 1000000})
               (filter experiment/verdict?)
               (filter #(= (name cand) (some-> (get-in % [:attempt/receipt :attempt/metrics :experiment-candidate]) name)))
               (group-by #(-> (get-in % [:attempt/receipt :attempt/environment :environment/id]) name
                              (clojure.string/replace #"^[qt]-" "")))
               (into (sorted-map)
                     (map (fn [[case-id as]]
                            (let [a (last (sort-by #(or (:attempt/certified-at %) 0) as))]
                              [case-id (if (>= (or (get-in a [:attempt/receipt :attempt/reward]) 0) 1.0) 1 0)])))))
          (finally (runner/close-experiment xs)))]
    (when (empty? verdicts)
      (binding [*out* *err*] (println "no verdicts of candidate" cand "in" dir))
      (System/exit 1))
    (j/write-value (io/file out) verdicts)
    (println dir cand ":" (reduce + (vals verdicts)) "/" (count verdicts) "pass")
    (shutdown-agents)
    (System/exit 0)))
