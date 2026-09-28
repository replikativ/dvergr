(ns dvergr.benchmarks.bird.compat
  "Does SQL over Datahike answer BIRD's questions as SQLite does? Every gold
   query runs on SQLite (the gold) and through pg-datahike on the same data
   loaded into Datahike, raw and through `bird.dialect`; the report counts
   matches, wrong results and errors by kind. Zero model tokens: it measures
   the substrate a SQL candidate on Datahike stands on, and what pg-datahike
   still lacks for analytics workloads."
  (:require [clojure.string :as str]
            [datahike.pg :as pg]
            [dvergr.benchmarks.bird.core :as bird]
            [dvergr.benchmarks.bird.dialect :as dialect]
            [dvergr.benchmarks.bird.load :as load])
  (:import [datahike.pg PgWireServer$QueryResult]))

(defn- pg-value
  "A pg-datahike text value as a Clojure value, by its column's type oid."
  [oid s]
  (when (some? s)
    (case (int oid)
      (20 21 23) (Long/parseLong s)
      (700 701 1700) (Double/parseDouble s)
      16 (if (= "t" s) 1 0)
      s)))

(defn pg-execute
  "Run `sql` through pg-datahike's handler `h`: `{:rows}` or `{:error :sqlstate}`."
  [h sql]
  (let [^PgWireServer$QueryResult r (.execute h ^String sql)]
    (if (.-error r)
      {:error (str (.-error r)) :sqlstate (.-sqlstate r)}
      (let [oids (vec (.-columnOids r))]
        {:columns (vec (.-columnNames r))
         :rows (mapv (fn [row] (mapv #(pg-value (nth oids %1 25) %2) (range) row)) (.-rows r))}))))

(defn- attempt [h sql gold]
  (let [r (try (pg-execute h sql)
               (catch Throwable t {:error (or (ex-message t) (str (class t))) :sqlstate "exception"}))]
    (cond
      (:error r) {:status :error :error (:error r) :sqlstate (:sqlstate r)}
      (bird/same-result? (:rows r) (:rows gold)) {:status :match}
      :else {:status :wrong :got (take 3 (:rows r)) :want (take 3 (:rows gold))})))

(defn check-database
  "The report rows of every question on `db-id`."
  [db-id questions {:keys [root] :or {root (bird/root)}}]
  (let [conn (load/load! db-id {:root root})
        h (pg/make-query-handler conn {:max-result-rows false})]
    (with-open [sq (bird/connect root db-id)]
      (vec (for [{:keys [question-id sql difficulty]} questions
                 :let [gold (try (bird/execute sq sql) (catch Exception e {:error (ex-message e)}))]]
             (if (:error gold)
               {:question-id question-id :db-id db-id :gold-error (:error gold)}
               (let [shimmed (dialect/to-postgres sql)]
                 {:question-id question-id :db-id db-id :difficulty difficulty
                  :raw (attempt h sql gold)
                  :shimmed (assoc (attempt h shimmed gold) :sql shimmed)})))))))

(defn- error-kind [msg]
  (let [m (str/lower-case (str msg))]
    (cond
      (re-find #"strftime|iif|julianday|function .* does not exist|unknown function" m) :missing-function
      (re-find #"parse|syntax|unexpected|encountered" m) :parse
      (re-find #"column .* does not exist|unknown column|not found" m) :unknown-column
      (re-find #"relation .* does not exist|unknown table" m) :unknown-table
      (re-find #"type|cast|operator" m) :types
      :else :other)))

(defn summarize
  "Counts of the report `rows`: per mode (:raw, :shimmed), statuses and error
   kinds; per difficulty, the shimmed match rate."
  [rows]
  (let [graded (remove :gold-error rows)]
    {:questions (count rows)
     :gold-errors (count (filter :gold-error rows))
     :raw (frequencies (map (comp :status :raw) graded))
     :shimmed (frequencies (map (comp :status :shimmed) graded))
     :shimmed-errors (frequencies (keep #(when (= :error (get-in % [:shimmed :status]))
                                           (error-kind (get-in % [:shimmed :error])))
                                        graded))
     :shimmed-by-difficulty (into {} (map (fn [[d rs]] [d (format "%d/%d" (count (filter #(= :match (get-in % [:shimmed :status])) rs))
                                                                (count rs))]))
                                  (group-by :difficulty graded))}))

(defn report
  "The whole dev set: `{:rows :summary}`; `db-ids` restricts it."
  ([] (report nil))
  ([db-ids]
   (let [qs (cond->> (bird/questions) (seq db-ids) (filter #((set db-ids) (:db-id %))))
         rows (vec (mapcat (fn [[db-id q]] (check-database db-id q {})) (group-by :db-id qs)))]
     {:rows rows :summary (summarize rows)})))
