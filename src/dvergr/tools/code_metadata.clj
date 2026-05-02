(ns dvergr.tools.code-metadata
  "Code metadata extraction and querying (simplified, no datahike yet).

   In the future this will use tools.analyzer + datahike for full semantic search.
   For now, provides simple in-memory code index for testing datalog queries."
  (:require [clojure.java.io :as io]
            [babashka.fs :as fs]
            [rewrite-clj.zip :as z]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Code Parsing (Simplified - just extract top-level forms)
;; ---------------------------------------------------------------------------

(defn extract-defs
  "Extract top-level def/defn forms from source code.
   Returns vector of maps with :type :name :file :line"
  [file-path source]
  (try
    (let [zloc (z/of-string source)]
      (loop [loc zloc
             results []]
        (cond
          (z/end? loc) results

          (and (z/list? loc)
               (contains? #{"defn" "def" "defmethod" "defmacro" "deftest"}
                          (-> loc z/down z/sexpr str)))
          (let [form-type (-> loc z/down z/sexpr str)
                form-name (try (-> loc z/down z/right z/sexpr str)
                               (catch Exception _ "unknown"))
                [row col] (try (z/position loc) (catch Exception _ [1 1]))]
            (recur (z/next loc)
                   (conj results
                         {:type (keyword form-type)
                          :name (symbol form-name)
                          :file file-path
                          :line row})))

          :else (recur (z/next loc) results))))
    (catch Exception e
      [])))

(defn index-file
  "Index a single Clojure file."
  [file-path]
  (when (.exists (io/file file-path))
    (let [source (slurp file-path)]
      (extract-defs file-path source))))

(defn index-directory
  "Index all Clojure files in a directory.
   Returns map of file-path -> [defs]"
  [dir-path]
  (->> (file-seq (io/file dir-path))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".clj"))
       (map str)
       (map (fn [path] [path (index-file path)]))
       (into {})))

;; ---------------------------------------------------------------------------
;; Simplified Datalog Query (In-Memory)
;; ---------------------------------------------------------------------------

(defn query-defs
  "Query indexed defs using datalog-like patterns.

   Supported patterns:
   [:find ?name :where [?e :type :defn]]
   [:find ?file ?line :where [?e :name 'greet]]
   [:find ?name :where [?e :type :defn] [?e :file \"sample.clj\"]]

   index: map of file-path -> [defs]
   pattern: datalog query (simplified)

   Returns vector of results matching :find clause"
  [index pattern]
  (try
    (let [all-defs (mapcat second index)
          where-clauses (drop 1 (drop-while #(not= :where %) pattern))
          find-vars (vec (take-while #(not= :where %) (drop 1 pattern)))

          ;; Simple pattern matching
          matches (filter
                   (fn [def-map]
                     (every?
                      (fn [[_ attr val]]
                        (cond
                          ;; Variable binding - always true
                          (and (symbol? val) (str/starts-with? (str val) "?"))
                          true

                          ;; Handle quoted symbols: 'foo becomes (quote foo)
                          (and (seq? val) (= 'quote (first val)))
                          (= (get def-map attr) (second val))

                          ;; Literal match
                          :else
                          (= (get def-map attr) val)))
                      where-clauses))
                   all-defs)]

      ;; Extract requested vars
      (mapv (fn [def-map]
              (if (= 1 (count find-vars))
                (let [var (first find-vars)
                      attr (keyword (subs (str var) 1))]
                  (get def-map attr))
                (mapv (fn [var]
                        (let [attr (keyword (subs (str var) 1))]
                          (get def-map attr)))
                      find-vars)))
            matches))

    (catch Exception e
      {:error (.getMessage e)})))

(comment
  ;; Test the indexing and querying
  (def idx (index-directory "test-data"))

  (query-defs idx
              '[:find ?name :where [?e :type :defn]])
  ;; => [greet farewell process-names calculate]

  (query-defs idx
              '[:find ?file ?line :where [?e :name 'greet]])
  ;; => [["test-data/sample.clj" 4]]

  (query-defs idx
              '[:find ?name :where [?e :type :defn] [?e :file "test-data/sample.clj"]])
  ;; => [greet farewell process-names calculate]
  )
