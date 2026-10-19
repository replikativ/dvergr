(ns dvergr.rooms.file-tx
  "Pure metadata helpers shared by canonical file writes and publication."
  (:require [clojure.string :as str]
            [datahike.api :as d]))

(defn ancestors-of [absolute-path]
  (->> (str/split absolute-path #"/") (remove str/blank?) butlast
       (reductions #(str %1 "/" %2) "") rest))

(defn remove-empty-dirs-tx
  "Retract explicit directories that nothing lies beneath, deepest first.
   Evaluate against the transaction snapshot. Files replacing directories and
   directories containing unrelated work are never removed."
  [db dirs]
  (if-not (seq dirs)
    []
    (let [files (map first (d/q '[:find ?p :where [_ :geschichte.work/path ?p]] db))
          explicit (into {} (d/q '[:find ?p ?e :where [?e :geschichte.work-dir/path ?p]] db))]
      (loop [[dir & more] (sort-by count > (map #(subs % 1) dirs)) gone #{} tx []]
        (if-not dir
          tx
          (let [prefix (str dir "/")
                eid (get explicit dir)
                beneath (some #(str/starts-with? % prefix)
                              (concat files (remove gone (keys explicit))))]
            (if (and eid (not beneath))
              (recur more (conj gone dir) (conj tx [:db/retractEntity eid]))
              (recur more gone tx))))))))
