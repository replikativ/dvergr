(ns dvergr.benchmarks.bird.load
  "A BIRD database in Datahike: every SQLite table as attributes
   `:table/column` with pg-datahike's row marker (`:table/db-row-exists`), so
   the same data answers SQL (pg-datahike) and Datalog (`d/q`).

   Identifiers are canonical (`ident`): lower case, since SQLite's are
   case-insensitive (`CDSCode` and `cdscode` are one column) and PostgreSQL
   folds unquoted names to lower case; and a keyword a Datalog query can write
   (no spaces or parentheses), which `bird.dialect` maps quoted SQL names to. A column's value type comes from
   the values it holds, not its declared type, which SQLite does not enforce:
   all integers → long, all numbers → double, anything else → string (a
   number in such a column is kept as SQLite's text of it).

   A loaded database is a file store under the cache, built once and reused
   (an Attempt's world forks it, copy-on-write)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [dvergr.benchmarks.bird.core :as bird])
  (:import [java.sql Connection]))

(def version-tag
  "Part of every BIRD verifier's basis: a change to how data is loaded is a
   change to what a score means."
  "bird.load/2: identifiers lower-cased with other than [a-z0-9_] as _, value-inferred types, pg-datahike row markers")

(defn ident
  "An identifier as Datahike stores it: lower case (SQLite's are
   case-insensitive), and every character other than a-z, 0-9 and _ as _, so
   each column is a keyword a Datalog query can write literally
   (`Free Meal Count (K-12)` → `free_meal_count__k_12_`)."
  [s]
  (str/replace (str/lower-case (str s)) #"[^a-z0-9_]" "_"))

(defn tables
  "`{table [{:name :declared :pk?}]}` of the SQLite database (identifiers as
   SQLite spells them)."
  [^Connection conn]
  (let [names (map first (:rows (bird/execute conn "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")))]
    (into (sorted-map)
          (for [t names]
            [t (mapv (fn [[_ name declared _ _ pk]] {:name name :declared declared :pk? (pos? (long pk))})
                     (:rows (bird/execute conn (str "PRAGMA table_info(\"" t "\")"))))]))))

(defn- value-type [values]
  (let [vs (remove nil? values)]
    (cond
      (empty? vs) :db.type/string
      (every? integer? vs) :db.type/long
      (every? number? vs) :db.type/double
      :else :db.type/string)))

(defn- coerce [type v]
  (when (some? v)
    (case type
      :db.type/long (long v)
      :db.type/double (double v)
      :db.type/string (if (and (number? v) (not (integer? v)) (== v (Math/rint v)))
                        ;; SQLite prints a whole real as 1.0
                        (str (double v))
                        (str v)))))

(defn- attr [table col] (keyword (ident table) (ident col)))

(defn store-config [db-id]
  {:store {:backend :file
           :path (str (System/getProperty "user.home") "/.cache/dvergr-bench/bird/datahike/v2/" db-id)
           :id (java.util.UUID/nameUUIDFromBytes (.getBytes (str "bird/v2/" db-id)))}
   :keep-history? false
   :schema-flexibility :write
   :index :datahike.index/persistent-set})

(defn load!
  "Load database `db-id` into Datahike (unless it is there) and return a
   connection. `:force?` rebuilds."
  ([db-id] (load! db-id {}))
  ([db-id {:keys [root force? batch] :or {root (bird/root) batch 5000}}]
   (let [cfg (store-config db-id)
         done (io/file (get-in cfg [:store :path]) ".bird-loaded")]
     (when (and force? (d/database-exists? cfg))
       (d/delete-database cfg))
     (if (and (d/database-exists? cfg) (.exists done))
       (d/connect cfg)
       (do
         (when (d/database-exists? cfg) (d/delete-database cfg))
         (d/create-database cfg)
         (let [conn (d/connect cfg)]
           (with-open [sq (bird/connect root db-id)]
             (doseq [[t cols] (tables sq)]
               (let [{:keys [rows]} (bird/execute sq (str "SELECT * FROM \"" t "\"") {:max-rows Long/MAX_VALUE
                                                                                      :timeout-s 600})
                     types (mapv (fn [i] (value-type (map #(nth % i) rows))) (range (count cols)))]
                 (d/transact conn {:tx-data (into [{:db/ident (keyword (ident t) "db-row-exists")
                                                    :db/valueType :db.type/boolean
                                                    :db/cardinality :db.cardinality/one}]
                                                  (map (fn [{:keys [name]} type]
                                                         {:db/ident (attr t name)
                                                          :db/valueType type
                                                          :db/cardinality :db.cardinality/one})
                                                       cols types))})
                 (doseq [chunk (partition-all batch rows)]
                   (d/transact conn {:tx-data
                                     (mapv (fn [row]
                                             (into {(keyword (ident t) "db-row-exists") true}
                                                   (keep (fn [[{:keys [name]} type v]]
                                                           (when-let [c (coerce type v)] [(attr t name) c])))
                                                   (map vector cols types row)))
                                           chunk)})))))
           (spit done (pr-str {:loaded-at (java.util.Date.)}))
           conn))))))
