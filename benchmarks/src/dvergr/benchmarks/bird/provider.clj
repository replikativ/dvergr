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
            [datahike.pg :as pg]
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
  "4: the Datalog description names :find's set semantics and :with. 3:
   identifiers canonical (bird.load/2); a final reply that is only a query
   counts as submitted. 2: a plain Datalog query (an EDN vector) runs as is;
   every engine's schema shows example rows."
  4)

(def engines #{:sqlite :pg-datahike :datalog})

;; ---------------------------------------------------------------------------
;; Engines: run a query, get rows

(defonce ^:private handlers (atom {}))

(defn- handler [db-id]
  (or (get @handlers db-id)
      (get (swap! handlers assoc db-id (pg/make-query-handler (load/load! db-id) {:max-result-rows 100000}))
           db-id)))

(defn- rows-of
  "A Datalog expression's value as result rows."
  [v]
  (cond
    (nil? v) []
    (and (coll? v) (not (map? v)))
    (mapv #(if (and (coll? %) (not (map? %))) (vec %) [%]) v)
    :else [[v]]))

(defn- datalog-ctx [db-id]
  (let [ctx (sandbox/create-base-ctx :load-fn (constantly nil))
        db (d/db (load/load! db-id))]
    (sandbox/add-namespace! ctx 'user {'q (fn [query & inputs] (apply d/q query db inputs))
                                       'pull (fn [pattern eid] (d/pull db pattern eid))})
    ctx))

(defn run-query
  "Rows of `query` (text) on `engine` over database `db-id`: `{:rows}` or
   `{:error}`."
  [engine db-id query {:keys [root] :or {root (bird/root)}}]
  (try
    (case engine
      :sqlite (with-open [c (bird/connect root db-id)]
                (select-keys (bird/execute c query {:timeout-s 60}) [:rows]))
      :pg-datahike (select-keys (compat/pg-execute (handler db-id) query) [:rows :error])
      :datalog (if (str/starts-with? (str/triml query) "[")
                 ;; a plain Datalog query, as SQL is plain SQL
                 {:rows (rows-of (d/q (edn/read-string query) (d/db (load/load! db-id))))}
                 (let [r (sandbox/eval-code (datalog-ctx db-id) query :timeout-ms 60000)]
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
   :datalog (str "Datahike's Datalog: either a plain query, an EDN vector such as "
                 "[:find ?n :where [?e :t/name ?n] [?e :t/height ?h] [(> ?h 200)]], "
                 "or, to sort or limit (Datalog has no ORDER BY or LIMIT), a Clojure expression "
                 "over (q query & inputs) such as "
                 "(->> (q '[:find ?n ?h :where [?e :t/name ?n] [?e :t/height ?h]]) (sort-by second >) (take 1)) "
                 "-- in Clojure ' quotes the one form after it and is not closed; the value must be a "
                 "collection of result rows. :find returns a SET: identical rows collapse, and so do "
                 "the values an aggregate sees, so aggregate per entity with :with, e.g. "
                 "[:find (avg ?h) :with ?e :where [?e :t/height ?h]] (without :with ?e, heights "
                 "that occur twice count once)")})

(defn system-prompt [engine]
  (str "You answer a question about a database by writing a query in " (language engine) ". "
       "Use the `query` tool to run queries and look at the data (it shows up to 50 rows), then "
       "call `submit` with the one query whose result answers the question. The answer is graded "
       "by running your submitted query and comparing its result rows (as a set) with the "
       "correct ones, so return exactly the columns the question asks for, no extra columns."))

(def ^:private tools
  [{"type" "function"
    "function" {"name" "query" "description" "Run a query; see its result rows (up to 50) or its error."
                "parameters" {"type" "object" "properties" {"query" {"type" "string"}} "required" ["query"]}}}
   {"type" "function"
    "function" {"name" "submit" "description" "Submit the query whose result answers the question. Ends the task."
                "parameters" {"type" "object" "properties" {"query" {"type" "string"}} "required" ["query"]}}}])

;; ---------------------------------------------------------------------------
;; Capabilities

(defn- question-of [questions definition]
  (let [{:keys [db-id question-id]} (:environment/task definition)]
    (or (get questions [db-id question-id])
        (throw (ex-info "EnvironmentDef names an unknown BIRD question"
                        {:type ::unknown-question :db-id db-id :question-id question-id})))))

(defn- engine-of [agent] (get-in agent [:agent/metadata :bird/engine] :sqlite))

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
  [{:keys [question engine generate max-turns cancelled?]}]
  (let [{:keys [db-id]} question
        user (str "Question: " (:question question)
                  (when-not (str/blank? (:evidence question)) (str "\nEvidence: " (:evidence question)))
                  "\n\nSchema:\n" (schema-text engine db-id {}))
        opening [{:role :user :content user}]]
    (loop [turn 0 history opening queries [] usage {}]
      (let [done (fn [termination submitted turns history usage]
                   {:termination termination :submitted submitted :queries queries :usage usage
                    :model-steps turns :transcript (subvec history 1)})]
        (cond
          (and cancelled? (cancelled?)) (done :cancelled nil turn history usage)
          (>= turn max-turns) (done :max-turns nil turn history usage)
          :else
          (let [{:keys [content tool-calls] :as response}
                (generate {:system (system-prompt engine) :messages history :tools tools})
                usage (merge-with #(if (and (number? %1) (number? %2)) (+ %1 %2) %2)
                                  usage (select-keys (:usage response) [:input-tokens :output-tokens
                                                                        :cache-read-tokens]))
                history (conj history (cond-> {:role :assistant :content content}
                                        (seq tool-calls) (assoc :tool-calls (vec tool-calls))))
                submit (some #(when (= "submit" (:name %)) %) tool-calls)]
            (cond
              submit (done :submitted (get-in submit [:arguments :query] (get-in submit [:arguments "query"]))
                           (inc turn) history usage)
              (empty? tool-calls)
              ;; a final reply that is only a query is its submission (for
              ;; every engine alike): models answer so as often as they call
              (if-let [q (reply-query engine content)]
                (done :submitted q (inc turn) history usage)
                (done :no-submission nil (inc turn) history usage))
              :else
              (let [results (mapv (fn [{:keys [id arguments]}]
                                    (let [q (or (:query arguments) (get arguments "query"))]
                                      [id q (run-query engine db-id (str q) {})]))
                                  tool-calls)]
                (recur (inc turn)
                       (into history (map (fn [[id _ r]] {:role :tool :id id :content (shown r)})) results)
                       (into queries (map (fn [[_ q r]] {:query q :error (:error r)})) results)
                       usage)))))))))

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
                (episode! {:question question :engine (engine-of agent)
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
                     :submitted (:submitted outcome)
                     :queries (:queries outcome)
                     :episode (select-keys outcome [:model-steps :usage])
                     :transcript (:transcript outcome)
                     :spend (if (map? (:usage outcome))
                              (spend/of-usage (get-in agent [:agent/model-policy :model]) (:usage outcome))
                              spend/zero)}))
       :verify (fn [definition {:keys [engine submitted] :as evidence}]
                 (let [question (question-of by-id definition)
                       submitted? (and (= :submitted (get-in evidence [:result :termination]))
                                       (not (str/blank? (str submitted))))
                       r (when submitted? (run-query engine (:db-id question) submitted {}))
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
  "AgentDefs for `{:id :model :provider :engine :budget-dollars}`."
  [specs]
  (reduce (fn [team {:keys [id model provider engine budget-dollars] :or {engine :sqlite budget-dollars 1.0}}]
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
                :metadata {:bird/engine engine}})))
          (roster/make-roster {:id :bird/candidates})
          specs))
