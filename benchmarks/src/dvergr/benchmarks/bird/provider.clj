(ns dvergr.benchmarks.bird.provider
  "BIRD as a benchmark provider (doc/benchmarks.md): one question over one
   database, answered by a query the candidate submits, graded by running it
   and comparing its rows with the gold SQL's rows on SQLite (upstream's
   execution accuracy).

   One protocol for every engine, so the comparison is of the query language
   and its substrate, not of the loop: the candidate sees the question, BIRD's
   evidence and the schema, has a `query` tool (run a query, see up to 50
   rows) and a `submit` tool (the answer), and at most `:max-turns` model
   steps. The engine is the candidate's (`:bird/engine` in its AgentDef):

     :sqlite       SQL on SQLite, as upstream
     :pg-datahike  SQL on the same data in Datahike, through pg-datahike
     :datalog      a Clojure expression over `(q query & inputs)`, Datahike's
                   Datalog on that data, evaluated in the sandbox: Datalog has
                   no ORDER BY or LIMIT, so ranking is `sort-by` and `take`

   The gold SQL, the gold rows and upstream's difficulty stay here; an
   EnvironmentDef names a question by database and id."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [datahike.query.resolve :as resolve]
            [datahike.pg :as pg]
            [datahike.pg.sql.classify :as pg-classify]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.spend :as spend]
            [dvergr.agent.verifiers :as verifiers]
            [dvergr.benchmarks.bird.compat :as compat]
            [dvergr.benchmarks.bird.core :as bird]
            [dvergr.benchmarks.bird.dialect :as dialect]
            [dvergr.benchmarks.bird.load :as load]
            [dvergr.benchmarks.live :as live]
            [dvergr.model.registry :as registry]
            [dvergr.sandbox :as sandbox]))

(def version
  "14: a candidate's SQL may only read (one SELECT/WITH statement, nothing
   that writes data, schema, files or session state); pg-datahike runs each
   query in a fresh session; SQLite connections cannot ATTACH files. 13: the Datalog description says rows are already distinct (`(distinct ?x)`
   is an aggregate: one cell holding a set) and how to return the row with
   the largest value without its value (bind the max by subquery, match it):
   the two Datalog-only failures of the held-out tier-1 run. 12: Datahike 0.8.1903 binds a function clause to a constant itself, so
   bind-constants is gone; the Datalog description offers if (on a bound
   condition), clojure.math and constant bindings instead of the get/format
   workarounds. 11: (q query $) in an expression (the subquery clause's spelling) works; a
   function clause bound to a constant is that equality (bind-constants, until 12).
   10: the Datalog description names the pure functions a clause may call,
   subqueries, the 0-based :order-by index, CASE/round/date idioms and the
   flat-call rule; a :nested candidate may nest calls (desugar-nested). 9:
   agent-written queries resolve functions as the Datahike server does
   (safe-symbol-resolver), not the embedded default that reaches the host; a
   plain query may hold a regex literal. 8: a final reply that is not a query that runs (the answer's value, a
   fragment) gets one reminder to submit; a submitted query that fails comes back with its error (every engine);
   a Datalog query has a 60 s deadline; a map written as vector clauses
   reads as that vector; the Datalog description is shorter and orders in
   the vector form. 7: the arithmetic example takes each aggregate with ffirst from its own
   query (6's nested destructuring was copied with broken brackets). 6: the
   map form's :order-by/:limit/:offset (Datahike has them) is the
   way to sort and limit; ordering by a variable :find does not return is
   allowed; a map query is a plain query. 5: the sorting example drops its sort key from the answer; aggregates
   count every row of the join, as in SQL (`with-rows`); an
   exact ratio grades as its nearest double; in the Datalog sandbox
   `double` converts a ratio to its nearest double; a vector that is not a
   query (its first element is not a keyword) is evaluated as an expression;
   the Datalog description says aggregates see every row, to stay exact, and
   to keep aggregates out of :where and of expressions. 4: the Datalog description names :find's set semantics and :with. 3:
   identifiers canonical (bird.load/2); a final reply that is only a query
   counts as submitted. 2: a plain Datalog query (an EDN vector) runs as is;
   every engine's schema shows example rows."
  14)

(def engines #{:sqlite :pg-datahike :datalog})

;; ---------------------------------------------------------------------------
;; Engines: run a query, get rows

(defn- handler
  "A fresh pg-datahike session over database `db-id`: no session state
   (SET, an open transaction) carries from one query to the next."
  [db-id]
  (pg/make-query-handler (load/load! db-id) {:max-result-rows 100000}))

(def ^:private writing-words
  "Keywords of statements that change data, schema, files or the session."
  #{"insert" "update" "delete" "merge" "upsert" "create" "drop" "alter" "truncate" "attach" "detach"
    "pragma" "vacuum" "reindex" "analyze" "copy" "grant" "revoke" "set" "reset" "begin" "commit"
    "rollback" "savepoint" "release" "lock" "call" "do" "execute" "prepare" "deallocate" "listen"
    "notify" "load" "import"})

(defn read-only-sql
  "nil when `sql` is one read statement (SELECT or WITH, nothing that writes
   data, schema, files or session state, outside string literals), else why
   not. A candidate's query runs on shared data: it may only read."
  [sql]
  (let [toks (->> (pg-classify/tokenize-all (str sql))
                  (remove #(= :comment (:type %)))
                  reverse (drop-while #(= ";" (:text %))) reverse)
        words (keep #(when (= :ident (:type %)) (str/lower-case (:text %))) toks)]
    (cond
      (empty? toks) "an empty query"
      (some #(= ";" (:text %)) toks) "one statement at a time"
      (not (#{"select" "with"} (first words))) "only SELECT (or WITH … SELECT) queries"
      :else (when-let [w (some writing-words words)]
              (str "a query may only read (" (str/upper-case w) ")")))))

(defn- rows-of
  "A Datalog expression's value as result rows."
  [v]
  (cond
    (nil? v) []
    (and (coll? v) (not (map? v)))
    (mapv #(if (and (coll? %) (not (map? %))) (vec %) [%]) v)
    :else [[v]]))

(def ^:private bag-aggregates
  "The aggregates duplicates change: those `:find`'s set semantics would
   mislead."
  '#{count sum avg median variance stddev})

(defn- lvar? [x] (and (symbol? x) (str/starts-with? (name x) "?")))

(def ^:private clause-keys
  #{:find :keys :strs :syms :with :in :where :order-by :limit :offset :timeout})

(defn- clause-parts
  "A vector-form query as `[[keyword forms] ...]`, in order; only clause
   keywords start a clause (`:desc` in `:order-by` does not)."
  [v]
  (reduce (fn [acc x] (if (clause-keys x) (conj acc [x []]) (update-in acc [(dec (count acc)) 1] conj x)))
          [] v))

(defn with-rows
  "`query` with SQL's row semantics for its aggregates: when `:find` has an
   aggregate duplicates change and there is no `:with`, every entity
   variable `:where` binds (the first place of a data pattern) that `:find`
   does not use becomes `:with`, so an aggregate sees one value per row of
   the join, as in SQL, instead of the set of distinct values.
   `count-distinct` still counts distinct values. Other queries are
   returned as they are."
  [query]
  (let [m (cond (map? query) query
                (vector? query) (into {} (clause-parts query))
                :else nil)
        find (:find m)
        find-vars (set (filter lvar? (flatten (map #(if (seq? %) (seq %) [%]) find))))
        agg? (some #(and (seq? %) (bag-aggregates (first %))) find)
        entity-vars (->> (:where m)
                         (filter #(and (vector? %) (lvar? (first %)) (keyword? (second %))))
                         (map first) distinct
                         (remove (into find-vars (filter lvar? (flatten (seq (:in m)))))))]
    (if (or (nil? m) (not agg?) (contains? m :with) (empty? entity-vars))
      query
      (if (map? query)
        (assoc query :with (vec entity-vars))
        (let [parts (clause-parts query)
              at (or (first (keep-indexed (fn [i [k]] (when (#{:in :where} k) i)) parts)) (count parts))
              parts (vec (concat (take at parts) [[:with (vec entity-vars)]] (drop at parts)))]
          (into [] (mapcat (fn [[k forms]] (cons k forms))) parts))))))

(def ^:private lazy-forms
  "Macros and special forms: desugaring evaluates every argument first, which
   is not their meaning (and `(and (pos? ?n) (/ ?a ?n))` would divide by
   zero), so they cannot appear inside an expression."
  '#{and or if when when-not cond case let fn if-let when-let})

(defn- call-form? [x]
  (and (seq? x) (symbol? (first x)) (not= 'quote (first x))))

(defn- query-literal? [x]
  (or (and (vector? x) (= :find (first x))) (and (map? x) (contains? x :find))))

(declare desugar-nested)

(defn- flatten-call
  "`form` (a call) as `[clauses value]`: each nested call argument bound to a
   fresh variable in its own clause, in evaluation order, and the call with
   those variables in their place. A query literal passed to `q` is
   desugared as a query of its own; other data (vectors, maps, sets, quote)
   stays as it is."
  [form fresh!]
  (let [[head & args] form]
    (when (lazy-forms head)
      (throw (ex-info (str "(" head " …) cannot be nested inside an expression: its arguments would all "
                           "be evaluated first. Write separate clauses (several predicates are an "
                           "and; an (or …) clause is an or), or for a CASE use (get {k v} ?x default).")
                      {:form form})))
    (let [[clauses args] (reduce (fn [[cs as] a]
                                   (cond
                                     (call-form? a) (let [[inner v] (flatten-call a fresh!)
                                                          ?v (fresh!)]
                                                      [(into cs (conj (vec inner) [(apply list (first v) (rest v)) ?v]))
                                                       (conj as ?v)])
                                     (and (query-literal? a) ('#{q datahike.api/q} head))
                                     [cs (conj as (desugar-nested a))]
                                     :else [cs (conj as a)]))
                                 [[] []] args)]
      [clauses (apply list head args)])))

(defn- clause-vars [c] (set (filter lvar? (flatten (map #(if (seq? %) (seq %) %) (if (seq? c) (rest c) c))))))

(defn- desugar-clauses
  "A :where clause list with nested calls flattened (`flatten-call`); fresh
   variables stay local to the scope they are made in: an `or`/`not` whose
   branches gain some becomes `or-join`/`not-join` over the variables it
   had."
  [clauses fresh!]
  (vec
   (mapcat
    (fn [c]
      (cond
        ;; [(f args) binding?]
        (and (vector? c) (call-form? (first c)))
        (let [[call & binding] c
              [pre call] (flatten-call call fresh!)]
          (conj (vec pre) (into [call] binding)))

        (and (seq? c) ('#{or and not} (first c)))
        (let [before (clause-vars c)
              body (if (= 'or (first c))
                     ;; each branch one clause: several become an (and …)
                     (mapv (fn [b] (if (and (seq? b) (= 'and (first b)))
                                     (apply list 'and (desugar-clauses (rest b) fresh!))
                                     (let [d (desugar-clauses [b] fresh!)]
                                       (if (= 1 (count d)) (first d) (apply list 'and d)))))
                           (rest c))
                     ;; not/and: their clauses are already a conjunction
                     (desugar-clauses (rest c) fresh!))
              after (clause-vars (apply list (first c) body))
              join (vec (sort-by str (filter before after)))]
          [(if (= before after)
             (apply list (first c) body)
             (case (first c)
               or (apply list 'or-join join body)
               not (apply list 'not-join join body)
               and (apply list 'and body)))])

        (and (seq? c) ('#{or-join not-join} (first c)))
        (let [[k vars & body] c]
          [(apply list k vars (desugar-clauses body fresh!))])

        :else [c]))
    clauses)))

(defn desugar-nested
  "`query` (vector or map form) with nested calls in its :where clauses
   flattened into fresh variables, as a SQL user writes them:
   [(> (/ ?w ?h) 0.5)] -> [(/ ?w ?h) ?__1] [(> ?__1 0.5)]. Other queries as
   they are."
  [query]
  (let [n (atom 0)
        fresh! #(symbol (str "?__" (swap! n inc)))
        m (cond (map? query) query (vector? query) (into {} (clause-parts query)) :else nil)]
    (if-not (seq (:where m))
      query
      (let [where (desugar-clauses (:where m) fresh!)]
        (if (map? query)
          (assoc query :where where)
          (into [] (mapcat (fn [[k forms]] (cons k (if (= :where k) where forms)))) (clause-parts query)))))))

(defn- literal? [x] (or (string? x) (number? x) (keyword? x) (boolean? x)))

(defn- as-map-query
  "A vector-form query that uses the map form's `:order-by`, `:limit` or
   `:offset` as the map form; other queries as they are."
  [query]
  (if (and (vector? query) (some #{:order-by :limit :offset} query))
    (into {} (map (fn [[k forms]] [k (if (#{:limit :offset} k) (first forms) (vec forms))]))
          (clause-parts query))
    query))

(def query-timeout-ms
  "A Datalog query's deadline, as a SQLite query's (`bird/execute`); Datahike
   stops the query there and frees what it built (datahike#1098)."
  60000)

(defn- q-bounded
  "`query` as the candidate wrote it, so as untrusted input: with a deadline,
   and resolving function symbols as the Datahike server does
   (`safe-symbol-resolver`: pure clojure.core, clojure.string, subqueries),
   not as embedded Datahike does (any var, reflection), which would let a
   query clause reach the host (slurp, shell) past the sandbox."
  [query db inputs]
  (binding [resolve/*symbol-resolver* resolve/safe-symbol-resolver]
    (d/q {:query query :args (into [db] inputs) :timeout query-timeout-ms})))

(defn run-q
  "`query` on `db`, as the candidate means it (`:nested?`: nested calls
   flattened, `desugar-nested`): aggregates with SQL's row
   semantics (`with-rows`), and ordering by a variable `:find` does not
   return, as SQL allows: the variable is found too and dropped from the
   rows (not with aggregates, whose grouping another variable would change)."
  ([db query inputs] (run-q db query inputs {}))
  ([db query inputs {:keys [nested?]}]
   (let [q (with-rows (as-map-query (cond-> query nested? desugar-nested)))
         find (when (map? q) (:find q))
         find-vars (set (filter lvar? find))
         hidden (when (and (map? q) (not-any? seq? find))
                  (->> (:order-by q) (filter lvar?) distinct (remove find-vars) vec))]
     (if (seq hidden)
       (mapv #(vec (take (count find) %)) (q-bounded (update q :find into hidden) db inputs))
       (q-bounded q db inputs)))))

(defn- datalog-ctx [db-id nested?]
  (let [ctx (sandbox/create-base-ctx :load-fn (constantly nil))
        db (d/db (load/load! db-id))]
    (sandbox/add-namespace! ctx 'user {'q (fn [query & inputs]
                                            ;; the db is bound already: (q query $) as in
                                            ;; a subquery clause names it again
                                            (run-q db query (remove #(identical? db %) inputs) {:nested? nested?}))
                                       '$ db
                                       'pull (fn [pattern eid] (d/pull db pattern eid))
                                       ;; Clojure's (double ratio) is not the nearest double
                                       'double (fn [x] (if (ratio? x) (bird/ratio->double x) (clojure.core/double x)))})
    ctx))

(defn read-query
  "`text` as a Datalog query (data), or nil when it is not one: a vector
   opening with a keyword such as :find, or a map with :find. A map written
   as a vector's clauses (`{:find ?n :where [...]}`, which is not a map: its
   forms do not pair) is read as that vector."
  [text]
  (let [t (str/trim (str text))
        query? #(or (and (vector? %) (keyword? (first %))) (and (map? %) (contains? % :find)))
        ;; Clojure's reader, not EDN's: a query may hold a regex literal
        ;; (#"(?i)man"); *read-eval* off, so #= cannot evaluate
        read #(try (binding [*read-eval* false] (read-string %)) (catch Exception _ ::unreadable))]
    (when (#{\[ \{} (first t))
      (let [v (read t)]
        (cond
          (query? v) v
          (and (= ::unreadable v) (str/starts-with? t "{") (str/ends-with? t "}"))
          (let [w (read (str "[" (subs t 1 (dec (count t))) "]"))]
            (when (and (vector? w) (= :find (first w))) w))
          :else nil)))))

(defn- plain-query? [query] (some? (read-query query)))

(defn run-query
  "Rows of `query` (text) on `engine` over database `db-id`: `{:rows}` or
   `{:error}`. `:nested?`: Datalog calls may nest (`desugar-nested`)."
  [engine db-id query {:keys [root nested?] :or {root (bird/root)}}]
  (try
    (case engine
      (:sqlite :pg-datahike) (when-let [why (read-only-sql query)] (throw (ex-info why {})))
      nil)
    (case engine
      :sqlite (with-open [c (bird/connect root db-id)]
                (select-keys (bird/execute c query {:timeout-s 60}) [:rows]))
      :pg-datahike (select-keys (compat/pg-execute (handler db-id) query) [:rows :error])
      :datalog (if (plain-query? query)
                 ;; a plain Datalog query, as SQL is plain SQL
                 {:rows (rows-of (run-q (d/db (load/load! db-id)) (read-query query) [] {:nested? nested?}))}
                 (let [r (sandbox/eval-code (datalog-ctx db-id nested?) query :timeout-ms 60000)]
                   (if (:success r)
                     {:rows (rows-of (:value r))}
                     {:error (str (get-in r [:error :message]))}))))
    (catch Throwable t {:error (or (ex-message t) (str (class t)))})))

;; ---------------------------------------------------------------------------
;; What the candidate is told

(defn- sqlite-schema [root db-id]
  (with-open [c (bird/connect root db-id)]
    (->> (:rows (bird/execute c "SELECT sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"))
         (map first) (remove nil?) (str/join ";\n\n"))))

(defn- samples
  "Three example rows per table, as `{table [row-maps]}` of SQLite values."
  [root db-id]
  (with-open [c (bird/connect root db-id)]
    (into (sorted-map)
          (for [[t cols] (load/tables c)]
            [t (let [{:keys [columns rows]} (bird/execute c (str "SELECT * FROM \"" t "\" LIMIT 3"))]
                 (mapv #(zipmap columns %) rows))]))))

(defn- example-rows [root db-id]
  (str "\n\nExample rows:\n"
       (str/join "\n" (for [[t rows] (samples root db-id)] (str (str/lower-case t) ": " (pr-str rows))))))

(defn schema-text
  "The schema as the candidate on `engine` sees it; every engine gets the
   same example rows."
  [engine db-id {:keys [root] :or {root (bird/root)}}]
  (case engine
    :sqlite (str (sqlite-schema root db-id) (example-rows root db-id))
    :pg-datahike (str "PostgreSQL dialect. Identifiers are lower case, with every character other "
                      "than a-z, 0-9 and _ written as _ (as below).\n\n"
                      (dialect/to-postgres (str/lower-case (sqlite-schema root db-id)))
                      (example-rows root db-id))
    :datalog (let [ex (samples root db-id)]
               (with-open [c (bird/connect root db-id)]
                 (str "Datahike (Datalog). Every table row is an entity with the marker attribute "
                      ":<table>/db-row-exists true; every column is an attribute :<table>/<column>, "
                      "listed below (lower case, other characters as _). A NULL is a missing attribute. "
                      "Foreign keys are plain values: join by equal values, not refs.\n\n"
                      (str/join "\n\n"
                                (for [[t cols] (load/tables c)]
                                  (str "table " (load/ident t) ":\n"
                                       (str/join "\n" (map #(str "  :" (load/ident t) "/"
                                                                 (load/ident (:name %))
                                                                 "  (declared " (:declared %) ")")
                                                           cols))
                                       "\n  example rows: " (pr-str (get ex t))))))))))

(def ^:private language
  {:sqlite "SQL (SQLite dialect)"
   :pg-datahike "SQL (PostgreSQL dialect)"
   :datalog (str "Datahike's Datalog. A query is an EDN vector: "
                 "[:find ?n :where [?e :t/name ?n] [?e :t/height ?h] [(> ?h 200)]]. To sort and limit, end "
                 "it with :order-by and :limit: "
                 "[:find ?n :where [?e :t/name ?n] [?e :t/height ?h] :order-by ?h :desc :limit 1] "
                 "(:offset n skips n rows; several keys: :order-by ?a :asc ?b :desc; by an aggregate, its "
                 "column index counted from 0: [:find ?c (count ?e) :where [?e :t/c ?c] :order-by 1 :desc :limit 1]). "
                 "Aggregates (count, sum, avg, min, max, count-distinct) go in :find only and see every row "
                 "of the join, as in SQL. Rows are already distinct: to list values, :find ?x; (distinct ?x) "
                 "is an aggregate that returns ONE cell holding a set. A subquery binds one value inside "
                 ":where, e.g. the maximum: [(q [:find (max ?h) :where [_ :t/height ?h]] $) [[?mx]]] "
                 "[?e :t/height ?mx]; that is also how to return the row with the largest value WITHOUT the "
                 "value as a column (:order-by needs its column in :find). "
                 "Clauses may call any pure clojure.core, clojure.string or clojure.math function (subs, str, "
                 "count, parse-long, parse-double, re-find, clojure.string/lower-case, clojure.math/round, "
                 "clojure.math/floor, clojure.math/pow, …), no Java methods. A constant in the binding tests "
                 "equality: [(subs ?id 6 7) \"4\"]. "
                 "NESTING "
                 "A CASE binds its condition, then if: [(> ?h 200) ?tall] [(if ?tall \"tall\" \"short\") ?c]; "
                 "clojure.math/round rounds to an integer (half up), to 2 places "
                 "[(format \"%.2f\" ?x) ?s] [(parse-double ?s) ?r]; dates are ISO text: [(subs ?d 0 4) ?year]. "
                 "For arithmetic on aggregates (ratios, percentages) write a "
                 "Clojure expression over (q query), one aggregate per q: "
                 "(let [n (ffirst (q '[:find (count ?e) :where [?e :t/h ?h] [(> ?h 200)]])) "
                 "total (ffirst (q '[:find (count ?e) :where [?e :t/h ?h]]))] [[(* 100 (/ n total))]]). "
                 "Integers divide exactly; keep it so (100, not 100.0): the result is graded as its nearest "
                 "double. A query's value is its rows, with exactly the columns the question asks for")})

(def ^:private nesting
  {false (str "Each call is its own clause: bind a sub-expression to a variable first, "
              "[(subs ?d 0 4) ?y] [(= ?y \"1991\")], not [(= (subs ?d 0 4) \"1991\")] (an error). ")
   true (str "Calls may nest, [(= (subs ?d 0 4) \"1991\")], [(> (/ ?a ?b) 0.5)], but not and/or/if "
             "inside an expression: write separate clauses, or an (or ...) clause. ")})

(defn- language-of [engine nested?]
  (cond-> (language engine)
    (= :datalog engine) (str/replace "NESTING " (nesting (boolean nested?)))))

(defn system-prompt
  ([engine] (system-prompt engine false))
  ([engine nested?]
   (str "You answer a question about a database by writing a query in " (language-of engine nested?) ". "
        "Use the `query` tool to run queries and look at the data (it shows up to 50 rows), then "
        "call `submit` with the one query whose result answers the question. The answer is graded "
        "by running your submitted query and comparing its result rows (as a set) with the "
        "correct ones, so return exactly the columns the question asks for, no extra columns.")))

(def ^:private tools
  [{"type" "function"
    "function" {"name" "query" "description" "Run a query; see its result rows (up to 50) or its error."
                "parameters" {"type" "object" "properties" {"query" {"type" "string"}} "required" ["query"]}}}
   {"type" "function"
    "function" {"name" "submit" "description" "Submit the query whose result answers the question. Ends the task, unless the query fails: then its error comes back and nothing is submitted."
                "parameters" {"type" "object" "properties" {"query" {"type" "string"}} "required" ["query"]}}}])

;; ---------------------------------------------------------------------------
;; Capabilities

(defn- question-of [questions definition]
  (let [{:keys [db-id question-id]} (:environment/task definition)]
    (or (get questions [db-id question-id])
        (throw (ex-info "EnvironmentDef names an unknown BIRD question"
                        {:type ::unknown-question :db-id db-id :question-id question-id})))))

(defn- engine-of [agent] (get-in agent [:agent/metadata :bird/engine] :sqlite))
(defn- nested-of [agent] (boolean (get-in agent [:agent/metadata :bird/nested])))

(defn- shown [{:keys [rows error]}]
  (if error
    (str "Error: " error)
    (str (count rows) " row(s)" (when (> (count rows) 50) ", first 50") ":\n"
         (str/join "\n" (map pr-str (take 50 rows))))))

(defn reply-query
  "The query a final reply consists of, or nil: the reply without code
   fences, when it reads as one query in `engine`'s language."
  [engine content]
  (let [t (-> (str content) str/trim
              (str/replace #"(?s)^```[a-zA-Z]*\s*(.*?)\s*```$" "$1") str/trim)]
    (when (case engine
            (:sqlite :pg-datahike) (re-find #"(?i)^(select|with)\s" t)
            :datalog (re-find #"^[\[(]" t))
      t)))

(defn- episode!
  "The query/submit loop. Returns `{:termination :submitted :queries :usage
   :transcript :model-steps}`."
  [{:keys [question engine nested? generate max-turns cancelled?]}]
  (let [{:keys [db-id]} question
        user (str "Question: " (:question question)
                  (when-not (str/blank? (:evidence question)) (str "\nEvidence: " (:evidence question)))
                  "\n\nSchema:\n" (schema-text engine db-id {}))
        opening [{:role :user :content user}]]
    (loop [turn 0 history opening queries [] usage {} reminded? false]
      (let [done (fn [termination submitted turns history usage]
                   {:termination termination :submitted submitted :queries queries :usage usage
                    :model-steps turns :transcript (subvec history 1)})]
        (cond
          (and cancelled? (cancelled?)) (done :cancelled nil turn history usage)
          (>= turn max-turns) (done :max-turns nil turn history usage)
          :else
          (let [{:keys [content tool-calls] :as response}
                (generate {:system (system-prompt engine nested?) :messages history :tools tools})
                usage (merge-with #(if (and (number? %1) (number? %2)) (+ %1 %2) %2)
                                  usage (select-keys (:usage response) [:input-tokens :output-tokens
                                                                        :cache-read-tokens]))
                history (conj history (cond-> {:role :assistant :content content}
                                        (seq tool-calls) (assoc :tool-calls (vec tool-calls))))
                submit (some #(when (= "submit" (:name %)) %) tool-calls)]
            (cond
              submit
              (let [q (str (get-in submit [:arguments :query] (get-in submit [:arguments "query"])))
                    r (run-query engine db-id q {:nested? nested?})]
                (if-not (:error r)
                  (done :submitted q (inc turn) history usage)
                  ;; a query that fails cannot be the answer: say so, and
                  ;; let the agent fix it (every engine alike)
                  (recur (inc turn)
                         (into history (map (fn [{:keys [id name]}]
                                              {:role :tool :id id
                                               :content (if (= "submit" name)
                                                          (str "Not submitted: the query fails: " (:error r)
                                                               "\nFix it and submit again.")
                                                          "Not run: a submit in the same turn failed.")}))
                               tool-calls)
                         (conj queries {:query q :error (:error r) :submit? true})
                         usage reminded?)))
              (empty? tool-calls)
              ;; a final reply that is only a query that runs is its
              ;; submission (for every engine alike): models answer so as
              ;; often as they call. Anything else (the answer's value, a
              ;; fragment) gets one reminder to submit the query.
              (let [q (reply-query engine content)
                    runs? (and q (not (:error (run-query engine db-id q {:nested? nested?}))))]
                (cond
                  runs? (done :submitted q (inc turn) history usage)
                  reminded? (done :no-submission nil (inc turn) history usage)
                  :else (recur (inc turn)
                               (conj history {:role :user
                                              :content (str "That is not a submission: the answer is graded by "
                                                            "running a query. Call `submit` with the one query "
                                                            "whose result answers the question.")})
                               queries usage true)))
              :else
              (let [results (mapv (fn [{:keys [id arguments]}]
                                    (let [q (or (:query arguments) (get arguments "query"))]
                                      [id q (run-query engine db-id (str q) {:nested? nested?})]))
                                  tool-calls)]
                (recur (inc turn)
                       (into history (map (fn [[id _ r]] {:role :tool :id id :content (shown r)})) results)
                       (into queries (map (fn [[_ q r]] {:query q :error (:error r)})) results)
                       usage reminded?)))))))))

(defonce ^:private gold-cache (atom {}))

(defn- gold-rows [{:keys [db-id question-id sql]}]
  (or (get @gold-cache [db-id question-id])
      (let [rows (with-open [c (bird/connect (bird/root) db-id)] (:rows (bird/execute c sql {:timeout-s 300})))]
        (swap! gold-cache assoc [db-id question-id] rows)
        rows)))

(defn capabilities
  "The trusted capabilities over `questions` (`bird/questions`).
   `:agent-generate` `(fn [question]) -> generate fn` replaces the model."
  [questions {:keys [agent-generate]}]
  (let [by-id (into {} (map (juxt (juxt :db-id :question-id) identity)) questions)
        basis {:upstream "BIRD dev 2024-06-27" :loader load/version-tag}]
    {:protocol
     (evaluation/make-protocol
      {:id :bird/query-submit :version version :basis basis :limit-keys #{:max-turns}
       :run (fn [{:keys [agent environment cancelled? model-scope]}]
              (let [question (question-of by-id environment)]
                (episode! {:question question :engine (engine-of agent) :nested? (nested-of agent)
                           :max-turns (get-in environment [:environment/limits :max-turns] 20)
                           :cancelled? cancelled?
                           :generate (live/scoped (if agent-generate
                                                    (agent-generate question)
                                                    (live/model-generate (:agent/model-policy agent)))
                                                  model-scope)})))})
     :evaluator
     (evaluation/make-evaluator
      {:id :bird/execution-accuracy :version version :basis basis :tier :trusted
       :observe (fn [{:keys [result durable agent]}]
                  (let [outcome (:run/value result)]
                    {:result {:termination (or (:termination outcome) :infrastructure-error)}
                     :failure (when-not (= :completed (:run/status result))
                                {:status (:run/status result) :reason (:run/reason durable)
                                 :message (:run/error durable)})
                     :engine (engine-of agent)
                     :nested? (nested-of agent)
                     :submitted (:submitted outcome)
                     :queries (:queries outcome)
                     :episode (select-keys outcome [:model-steps :usage])
                     :transcript (:transcript outcome)
                     :spend (if (map? (:usage outcome))
                              (spend/of-usage (get-in agent [:agent/model-policy :model]) (:usage outcome))
                              spend/zero)}))
       :verify (fn [definition {:keys [engine nested? submitted] :as evidence}]
                 (let [question (question-of by-id definition)
                       submitted? (and (= :submitted (get-in evidence [:result :termination]))
                                       (not (str/blank? (str submitted))))
                       r (when submitted? (run-query engine (:db-id question) submitted {:nested? nested?}))
                       correct? (boolean (and r (not (:error r))
                                              (bird/same-result? (:rows r) (gold-rows question))))]
                   {:reward (if correct? 1.0 0.0)
                    :checks {:submitted submitted?
                             :runs (boolean (and r (not (:error r))))
                             :correct correct?}}))})}))

(defn register!
  [{:keys [protocol evaluator]}]
  {:protocol (verifiers/register-protocol! protocol)
   :verifier (verifiers/register-evaluator! evaluator)})

(defn environment-def
  "EnvironmentDef of one question."
  [{:keys [db-id question-id difficulty]} {:keys [protocol evaluator]}
   {:keys [max-turns timeout-ms] :or {max-turns 20 timeout-ms (* 10 60 1000)}}]
  (let [ver (evaluation/evaluator-ref evaluator)]
    (environment/make-environment
     {:id (keyword (str "bird." db-id) (str "q-" question-id))
      :task {:db-id db-id :question-id question-id}
      :verifier (cond-> {:id (:verifier/id ver) :version (:verifier/version ver)}
                  (:verifier/basis ver) (assoc :basis (:verifier/basis ver)))
      :limits {:max-turns max-turns :timeout-ms timeout-ms :cancel-timeout-ms 30000}
      :world {:isolation :ctx :settlement :discard
              :protocol (evaluation/protocol-ref protocol)}
      :metadata {:benchmark :bird :difficulty difficulty}})))

(defn candidate-roster
  "AgentDefs for `{:id :model :provider :engine :nested :budget-dollars}`;
   `:nested` (Datalog): calls may nest in clauses (`desugar-nested`)."
  [specs]
  (reduce (fn [team {:keys [id model provider engine nested budget-dollars] :or {engine :sqlite budget-dollars 1.0}}]
            (when-not (engines engine)
              (throw (ex-info "Unknown BIRD engine" {:type ::unknown-engine :engine engine :known engines})))
            (let [model-id (registry/resolve-alias model)]
              (roster/make-agent
               team
               {:id id
                :prompt "BIRD candidate (the prompt is the question's)"
                :tools #{}
                :model-policy {:provider (or provider (:provider (registry/get-model! model-id))) :model model-id}
                :program {:kind :llm :max-model-steps 20 :budget-dollars budget-dollars}
                :metadata (cond-> {:bird/engine engine} nested (assoc :bird/nested true))})))
          (roster/make-roster {:id :bird/candidates})
          specs))
