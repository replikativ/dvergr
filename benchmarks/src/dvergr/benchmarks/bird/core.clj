(ns dvergr.benchmarks.bird.core
  "BIRD (text-to-SQL over real databases, https://bird-bench.github.io,
   CC BY-SA 4.0): the questions, the SQLite databases they are asked of, and
   upstream's grading.

   A task is a question (with its `evidence`, the external knowledge BIRD
   gives the model) over one database; the gold is the result of its
   reference SQL on SQLite. Upstream's execution accuracy compares the SETS of
   result rows: `set(predicted) == set(gold)`, with Python's equality, so 1 and
   1.0 are the same value and row order and duplicates do not count.

   The dev set is `BIRD_ROOT` (default `~/.cache/dvergr-bench/bird/dev_20240627`,
   from https://bird-bench.oss-cn-beijing.aliyuncs.com/dev.zip, with
   `dev_databases.zip` unpacked in it)."
  (:require [clojure.java.io :as io]
            [jsonista.core :as json])
  (:import [java.sql Connection DriverManager ResultSet ResultSetMetaData]))

(defn root []
  (or (System/getenv "BIRD_ROOT")
      (str (System/getProperty "user.home") "/.cache/dvergr-bench/bird/dev_20240627")))

(defn available?
  ([] (available? (root)))
  ([root] (.exists (io/file root "dev_databases"))))

(defn questions
  "The dev questions: `[{:question-id :db-id :question :evidence :sql :difficulty}]`."
  ([] (questions (root)))
  ([root]
   (mapv (fn [{:strs [question_id db_id question evidence SQL difficulty]}]
           {:question-id question_id :db-id db_id :question question :evidence evidence
            :sql SQL :difficulty difficulty})
         (json/read-value (io/file root "dev.json")))))

(defn sqlite-file [root db-id]
  (io/file root "dev_databases" db-id (str db-id ".sqlite")))

(defn connect
  "A read-only JDBC connection to database `db-id`."
  ^Connection [root db-id]
  (DriverManager/getConnection (str "jdbc:sqlite:file:" (.getCanonicalPath (sqlite-file root db-id))
                                    "?mode=ro")))

(defn- row-values [^ResultSet rs n]
  (mapv #(.getObject rs (int %)) (range 1 (inc n))))

(defn execute
  "Run `sql` on `conn`: `{:columns [..] :rows [[..] ..]}`, at most
   `max-rows` rows (default 100000), within `timeout-s` (default 60)."
  ([conn sql] (execute conn sql {}))
  ([^Connection conn ^String sql {:keys [max-rows timeout-s] :or {max-rows 100000 timeout-s 60}}]
   (with-open [st (.createStatement conn)]
     (.setQueryTimeout st (int timeout-s))
     (with-open [rs (.executeQuery st sql)]
       (let [md ^ResultSetMetaData (.getMetaData rs)
             n (.getColumnCount md)]
         {:columns (mapv #(.getColumnName md (int %)) (range 1 (inc n)))
          :rows (loop [acc (transient []) i 0]
                  (if (and (< i max-rows) (.next rs))
                    (recur (conj! acc (row-values rs n)) (inc i))
                    (persistent! acc)))})))))

(defn canonical
  "A result value as upstream's comparison sees it: numbers compare by value
   (1 = 1.0), so every number becomes a double; text stays text."
  [v]
  (cond
    (nil? v) nil
    (instance? Boolean v) (if v 1.0 0.0)
    (number? v) (double v)
    (bytes? v) (vec v)
    :else (str v)))

(defn row-set
  "The set of `rows` (each a vector of values), canonical."
  [rows]
  (into #{} (map #(mapv canonical %)) rows))

(defn same-result?
  "Upstream's execution accuracy: the sets of result rows are equal."
  [predicted-rows gold-rows]
  (= (row-set predicted-rows) (row-set gold-rows)))
