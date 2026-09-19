(ns dvergr.benchmarks.tau2.core
  "tau2-bench on Dvergr: task data, upstream prompts, the half-duplex
   agent/user/environment protocol, and trusted grading.

   The protocol mirrors tau2's `Orchestrator` (half duplex): the agent opens
   with a fixed greeting, a simulated user answers, and each agent message
   goes to the environment when it carries tool calls, otherwise to the user.
   `###STOP###` from either side ends the episode. Model access is injected as
   `generate` functions, so the same driver runs deterministic stubs in tests
   and any Dvergr provider live.

   Grading follows tau2's `evaluate_simulation` with `EvaluationType.ALL`:
   the product of the components named by each task's `reward_basis` (DB hash
   against the replayed gold actions, communicate-info substring checks, and
   the LLM-judged NL assertions). Because the environment is a pure value, the
   predicted database is the episode's final world directly rather than a
   replay of its tool calls."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.benchmarks.tau2.airline :as airline]
            [dvergr.benchmarks.tau2.banking :as banking]
            [dvergr.benchmarks.tau2.banking.db :as banking-db]
            [dvergr.benchmarks.tau2.pyjson :as pj]
            [dvergr.benchmarks.tau2.python :as py]
            [dvergr.benchmarks.tau2.retail :as retail]
            [dvergr.benchmarks.tau2.telecom :as telecom]))

;; ---------------------------------------------------------------------------
;; Provenance and data

(def upstream
  {:repository "https://github.com/sierra-research/tau2-bench"
   :revision "b7ea9074c1cba482b30687fecdb5c8425fd6f619"
   :version "1.0.1"})

(def ^:private file-digests
  {"retail" {"db.json" "413a65160adbdb5fde0ffc0015c49b6d70250b10c18128de169b597af7766765"
             "tasks.json" "8e03ebce7901bd6218e7a7dc3105faa9324091a68058f7fe61c65262868812e8"
             "policy.md" "2c9652afbce57d6e087768d37cda64d31c53d50b3e3225cfdb791bac66466467"
             "split_tasks.json" "ed0580ec52575b63fbf76568af42490da6ee7783ecb4aa81af46961291358f20"}
   "banking_knowledge" {"db.json" "e692feb797c659f0e21ff7380aa87beb4e95a0694d7bb7945f073102f0293d28"
                        "tasks/" "b9efd448d38015c5707b61a341e6423c27ba7a8cc62f7fa726379b1e4f018206"}
   :user-guidelines "740a29dfa64d7bc08eea3bf7493575b914a63f744acbaf7f199ee07eddaf72d3"
   :user-guidelines-tools "cbf3d8a4d8642fd04e559862f1afef55d7dd4e6a7e727ca49e239023c599de0c"})

(def default-root
  "A tau2-bench checkout next to Dvergr. Override with `:root`."
  "../tau2-bench")

(defn- verified-slurp [path expected-digest]
  (let [text (slurp path)
        digest (pj/sha256-hex text)]
    (when (not= digest expected-digest)
      (throw (ex-info "tau2 data file does not match the pinned upstream revision"
                      {:type ::data-digest-mismatch
                       :path (str path) :expected expected-digest :actual digest
                       :upstream upstream})))
    text))

(defn- read-guidelines [root tools?]
  (let [file (if tools? "simulation_guidelines_tools.md" "simulation_guidelines.md")]
    (verified-slurp (io/file root "data/tau2/user_simulator" file)
                    (get file-digests (if tools? :user-guidelines-tools :user-guidelines)))))

(defn- load-retail [root]
  (let [dir (io/file root "data/tau2/domains/retail")
        digests (get file-digests "retail")
        read-json #(pj/parse (verified-slurp (io/file dir %) (get digests %)))
        db (retail/normalize-db (read-json "db.json"))
        tasks (read-json "tasks.json")]
    {:domain "retail"
     :db db
     :initial-db-hash (retail/db-hash db)
     :initial-world (fn [_task] db)
     ;; Retail has no user tools: upstream raises for user tool calls.
     :respond (fn [world requestor name args]
                (if (= requestor :user)
                  {:world world :content "Error: User tools not available" :error true}
                  (let [{d :db :keys [content error]} (retail/respond world name args)]
                    {:world d :content content :error error})))
     :world-hash retail/db-hash
     :policy (verified-slurp (io/file dir "policy.md") (get digests "policy.md"))
     :tasks (into (array-map) (map (juxt #(get % "id") identity)) tasks)
     :splits (read-json "split_tasks.json")
     :tool-schemas (pj/parse (slurp (io/resource "benchmarks/tau2/retail-tools.json")))
     :user-tool-schemas (constantly nil)
     :user-guidelines (read-guidelines root false)}))

(defn- load-banking [root]
  (let [dir (io/file root "data/tau2/domains/banking_knowledge")
        digests (get file-digests "banking_knowledge")
        base-db (banking-db/normalize-db
                 (pj/parse (verified-slurp (io/file dir "db.json") (get digests "db.json"))))
        ;; `get_tasks` reads tasks/task_*.json (sorted); the combined
        ;; tasks.json in the same directory is stale upstream.
        task-files (sort-by #(.getName ^java.io.File %)
                            (filter #(re-matches #"task_.*\.json" (.getName ^java.io.File %))
                                    (.listFiles (io/file dir "tasks"))))
        task-texts (mapv slurp task-files)
        tasks-digest (pj/sha256-hex (str/join "\n" (map (fn [f t] (str (.getName ^java.io.File f) ":" (pj/sha256-hex t)))
                                                         task-files task-texts)))
        _ (when (not= tasks-digest (get digests "tasks/"))
            (throw (ex-info "tau2 data file does not match the pinned upstream revision"
                            {:type ::data-digest-mismatch :path (str (io/file dir "tasks"))
                             :expected (get digests "tasks/") :actual tasks-digest
                             :upstream upstream})))
        tasks (mapv pj/parse task-texts)
        index (banking/build-bm25 (banking/load-documents (io/file dir "documents")))
        kits (banking/toolkits index)
        meta @banking/metadata
        user-schemas (get meta "user_tools")]
    {:domain "banking_knowledge"
     :retrieval-config "bm25"
     :db base-db
     :initial-db-hash (banking-db/db-hash base-db)
     :initial-world (fn [task] (banking/initial-world base-db task))
     :respond (fn [world requestor name args]
                (banking/respond kits world requestor name args))
     :world-hash (comp banking-db/db-hash :db)
     :policy (banking/policy (io/file dir "prompts"))
     :tasks (into (array-map) (map (juxt #(get % "id") identity)) tasks)
     :splits {"base" (mapv #(get % "id") tasks)}
     :tool-schemas (get meta "tools")
     ;; `environment.get_user_tools(include=task.user_tools) or None`
     :user-tool-schemas (fn [task]
                          (let [include (get task "user_tools")]
                            (not-empty
                             (if (nil? include)
                               user-schemas
                               (filterv #(some #{(get-in % ["function" "name"])} include)
                                        user-schemas)))))
     :user-guidelines (read-guidelines root false)
     :user-guidelines-tools (read-guidelines root true)}))

(defn load-domain
  "Load one text domain from a pinned tau2-bench checkout. Data files are
   verified against the upstream digests recorded in `file-digests`.
   Supported: \"retail\", \"airline\", \"banking_knowledge\" (bm25 retrieval),
   \"telecom\"."
  ([domain] (load-domain domain {}))
  ([domain {:keys [root] :or {root default-root}}]
   (case domain
     "retail" (load-retail root)
     "airline" (airline/load-airline root)
     "banking_knowledge" (load-banking root)
     "telecom" (telecom/load-telecom root)
     (throw (ex-info "Unsupported tau2 domain" {:domain domain})))))

(defn split-tasks
  "Tasks of a named upstream split (\"train\", \"test\", \"base\")."
  [domain split]
  (mapv #(get-in domain [:tasks %]) (get-in domain [:splits split])))

;; ---------------------------------------------------------------------------
;; Upstream prompts (checked against the oracle's `prompts` export)

(def agent-instruction
  (str "You are a customer service agent that helps the user according to the <policy> provided below.\n"
       "In each turn you can either:\n"
       "- Send a message to the user.\n"
       "- Make a tool call.\n"
       "You cannot do both at the same time.\n"
       "\n"
       "Try to be helpful and always follow the policy. Always make sure you generate valid JSON only."))

(def first-agent-message "Hi! How can I help you today?")

(def stop-token "###STOP###")
(def user-stop-tokens ["###STOP###" "###TRANSFER###" "###OUT-OF-SCOPE###"])

(defn agent-system-prompt [domain]
  (str "<instructions>\n" agent-instruction "\n</instructions>\n<policy>\n"
       (:policy domain) "\n</policy>"))

(defn- indent
  "Python `textwrap.indent`: prefix every line that is not whitespace-only."
  [text prefix]
  (->> (str/split text #"(?<=\n)")
       (map #(if (str/blank? %) % (str prefix %)))
       (apply str)))

(defn- instructions-str [instructions]
  (if (string? instructions)
    instructions
    (let [{:strs [domain reason_for_call known_info unknown_info task_instructions]} instructions]
      (str/join "\n"
                (cond-> [(str "Domain: " domain)
                         (str "Reason for call:\n" (indent reason_for_call "\t"))]
                  known_info (conj (str "Known info:\n" (indent known_info "\t")))
                  unknown_info (conj (str "Unknown info:\n" (indent unknown_info "\t")))
                  true (conj (str "Task instructions:\n" (indent task_instructions "\t"))))))))

(defn- scenario-str [{:strs [persona instructions]}]
  (str/join "\n"
            (cond-> []
              persona (into ["Persona:" (indent persona "\t")])
              true (into ["Instructions:" (indent (instructions-str instructions) "\t")]))))

(defn user-system-prompt [domain task]
  (let [tools? (some? ((:user-tool-schemas domain) task))
        guidelines (if tools? (:user-guidelines-tools domain) (:user-guidelines domain))]
    (str (str/replace guidelines "<PERSONA_GUIDELINES>" "")
         "\n\n<scenario>\n" (scenario-str (get task "user_scenario")) "\n</scenario>")))

;; ---------------------------------------------------------------------------
;; Episode protocol

(defn- has-text? [content] (and (string? content) (not (str/blank? content))))

(defn stringify-keys
  "Keyword keys to strings, recursively (tool arguments are JSON objects)."
  [x]
  (cond
    (map? x) (into (array-map) (map (fn [[k v]] [(if (keyword? k) (name k) k)
                                                 (stringify-keys v)])) x)
    (sequential? x) (mapv stringify-keys x)
    :else x))

(defn- execute-calls
  "Run tool calls in order for `requestor`; returns `[world tool-messages]`."
  [respond world requestor tool-calls]
  (reduce (fn [[world results] {:keys [id name arguments]}]
            (let [{world' :world :keys [content error]} (respond world requestor name arguments)]
              [world' (conj results {:role :tool :id id :content content :error error
                                     :requestor requestor})]))
          [world []]
          tool-calls))

(defn- normalize-calls [tool-calls]
  (not-empty (mapv #(update % :arguments stringify-keys) tool-calls)))

(defn run-episode
  "Run one task and return `{:messages :termination :world :db :usage}`.

   `agent` and `user` are generate functions (or `agent-turn`, a whole-turn
   candidate `(fn [{:keys [world message]}] -> {:world :reply :messages})`)
   `(fn [{:keys [system messages tools]}] -> {:content str :tool-calls [{:id :name :arguments}] :usage map})`.
   Messages use tau2's shape: `{:role :assistant|:user|:tool :content str
   :tool-calls [...] :id str :error bool :requestor :assistant|:user}`.

   Each participant sees its own history like upstream: the agent receives
   user text plus its own tool traffic; the user simulator receives a
   role-flipped history of agent text plus its own tool calls and results
   (dual control, e.g. banking user tools)."
  [domain task {:keys [agent agent-turn user max-steps max-errors enforce-protocol?]
                :or {max-steps 200 max-errors 10 enforce-protocol? false}}]
  (let [agent-system (agent-system-prompt domain)
        user-system (user-system-prompt domain task)
        agent-tools (:tool-schemas domain)
        user-tools ((:user-tool-schemas domain) task)
        respond (:respond domain)
        greeting {:role :assistant :content first-agent-message}
        malformed? (fn [content calls]
                     (or (and (not calls) (not (has-text? content)))
                         (and enforce-protocol? calls (has-text? content))))]
    (loop [world ((:initial-world domain) task)
           trajectory [greeting]
           ;; histories as seen by each participant
           agent-view [greeting]
           user-view [{:role :user :content first-agent-message}]
           to :user
           steps 0
           errors 0
           usage []]
      (let [done (fn [reason & {:keys [trajectory steps usage]
                                :or {trajectory trajectory steps steps usage usage}}]
                   {:messages trajectory :termination reason :world world
                    :db (if (map? world) (get world :db world) world)
                    :steps steps :errors errors :usage usage})]
        (cond
          (>= steps max-steps) (done :max-steps)
          (>= errors max-errors) (done :too-many-errors)

          (= to :user)
          (let [{:keys [content tool-calls] :as reply}
                (user (cond-> {:system user-system :messages user-view}
                        user-tools (assoc :tools user-tools)))
                calls (normalize-calls tool-calls)
                msg (cond-> {:role :user :content content}
                      calls (assoc :tool-calls calls))
                trajectory (conj trajectory msg)
                usage-log (conj usage {:role :user :usage (:usage reply)})]
            (cond
              (malformed? content calls)
              (done :user-error :trajectory trajectory :steps (inc steps) :usage usage-log)

              (and (string? content) (some #(str/includes? content %) user-stop-tokens))
              (done :user-stop :trajectory trajectory :steps (inc steps) :usage usage-log)

              calls
              (let [own (cond-> {:role :assistant :content content} calls (assoc :tool-calls calls))
                    [world results] (execute-calls respond world :user calls)]
                (recur world (into trajectory results) agent-view
                       (into (conj user-view own) results)
                       :user (+ steps 2) (+ errors (count (filter :error results)))
                       usage-log))

              :else
              (recur world trajectory (conj agent-view msg)
                     (conj user-view {:role :assistant :content content})
                     :agent (inc steps) errors usage-log)))

          (and (= to :agent) agent-turn)
          ;; Whole-turn candidate (e.g. Dvergr's own agent loop): the tool
          ;; traffic is spliced into the trajectory with tau2's step and error
          ;; accounting, then the reply goes to the user.
          (let [{:keys [world reply messages outcome] :as result}
                (agent-turn {:world world :message (:content (peek agent-view))})
                results (filter #(= :tool (:role %)) messages)
                steps (+ steps (* 2 (count results)) 1)
                errors (+ errors (count (filter :error results)))
                msg {:role :assistant :content reply}
                trajectory (conj (into trajectory messages) msg)
                usage-log (conj usage {:role :agent :usage (:usage result) :outcome outcome})
                done* (fn [reason]
                        {:messages trajectory :termination reason :world world
                         :db (if (map? world) (get world :db world) world)
                         :steps steps :errors errors :usage usage-log})]
            (cond
              (not (has-text? reply)) (done* :agent-error)
              (str/includes? reply stop-token) (done* :agent-stop)
              (>= errors max-errors) (done* :too-many-errors)
              :else (recur world trajectory (conj (into agent-view messages) msg)
                           (conj user-view {:role :user :content reply})
                           :user steps errors usage-log)))

          (= to :agent)
          (let [{:keys [content tool-calls] :as reply}
                (agent {:system agent-system :messages agent-view :tools agent-tools})
                calls (normalize-calls tool-calls)
                msg (cond-> {:role :assistant :content content}
                      calls (assoc :tool-calls calls))
                trajectory (conj trajectory msg)
                usage-log (conj usage {:role :agent :usage (:usage reply)})]
            (cond
              ;; `validate()` rejects empty messages. Mixed text+tool-call
              ;; messages are only an error under tau2's opt-in
              ;; `enforce_communication_protocol` (default off); otherwise they
              ;; route to the environment and the user never sees the text.
              (malformed? content calls)
              (done :agent-error :trajectory trajectory :steps (inc steps) :usage usage-log)

              (and (string? content) (str/includes? content stop-token))
              (done :agent-stop :trajectory trajectory :steps (inc steps) :usage usage-log)

              calls
              ;; AGENT -> ENV is one step, ENV -> AGENT is the next.
              (let [[world results] (execute-calls respond world :assistant calls)]
                (recur world (into trajectory results)
                       (into (conj agent-view msg) results)
                       user-view :agent (+ steps 2)
                       (+ errors (count (filter :error results)))
                       usage-log))

              :else
              (recur world trajectory (conj agent-view msg)
                     (conj user-view {:role :user :content content})
                     :user (inc steps) errors usage-log))))))))

;; ---------------------------------------------------------------------------
;; Grading

(defn gold-world
  "The world after replaying a task's gold actions from its initial world
   (errors ignored, as upstream logs and continues)."
  [domain task]
  (reduce (fn [world {:strs [name arguments requestor]}]
            (:world ((:respond domain) world (if (= "user" requestor) :user :assistant)
                                       name arguments)))
          ((:initial-world domain) task)
          (get-in task ["evaluation_criteria" "actions"])))

(defn gold-db
  "Retail compatibility: the gold world's database."
  [domain task]
  (let [w (gold-world domain task)] (if (map? w) (get w :db w) w)))

(defn action-checks
  "tau2 `ActionEvaluator`: every gold action must match some tool call of
   either participant. Only the keys the *predicted* call used are compared
   unless the action names `compare_args` (upstream quirk kept)."
  [task messages]
  (let [calls (mapcat :tool-calls (filter #(#{:assistant :user} (:role %)) messages))]
    (vec (for [{:strs [name arguments compare_args] :as action}
               (get-in task ["evaluation_criteria" "actions"])]
           {:action action
            :met (boolean
                  (some (fn [{call-name :name call-args :arguments}]
                          (and (= name call-name)
                               (let [ks (if (nil? compare_args) (keys call-args) compare_args)]
                                 (or (empty? ks)
                                     (py/py-eq (select-keys call-args ks)
                                               (select-keys arguments ks))))))
                        calls))}))))

(defn communicate-checks [task messages]
  (vec (for [info (get-in task ["evaluation_criteria" "communicate_info"])]
         {:info info
          :met (boolean
                (some #(and (= :assistant (:role %))
                            (has-text? (:content %))
                            (str/includes? (str/replace (str/lower-case (:content %)) "," "")
                                           (str/lower-case info)))
                      messages))})))

(def nl-judge-system
  "\n        TASK\n        - You will be given a list of expected outcomes and a conversation that was collected during a test case run.\n        - The conversation is between an agent and a customer.\n        - Your job is to evaluate whether the agent satisfies each of the expected outcomes.\n        - Grade each expected outcome individually.\n\n        FORMAT\n        - Your response should be a JSON object with the following fields:\n        - `reasoning`: a short explanation for your classification\n        - `metExpectation`: `true` if the agent satisfies the expected outcomes, `false` otherwise\n        - `expectedOutcome`: repeat the expectation from the input that you are grading\n        \n        Example response structure:\n        {\n            \"results\": [\n                {\n                    \"expectedOutcome\": \"<one of the expected outcomes from the input>\",\n                    \"reasoning\": \"<reasoning trace>\",\n                    \"metExpectation\": <false or true>,\n                }\n            ]\n        }\n        ")

(defn- py-str-repr
  "Python `repr(str)` for printable text."
  [s]
  (let [q (if (and (str/includes? s "'") (not (str/includes? s "\""))) "\"" "'")]
    (str q
         (-> s
             (str/replace "\\" "\\\\")
             (str/replace "\n" "\\n")
             (str/replace "\t" "\\t")
             (cond-> (= q "'") (str/replace "'" "\\'")))
         q)))

(defn- py-message-str [{:keys [role content]}]
  (str (name role) ": " (if (nil? content) "None" content)))

(defn nl-judge-prompt [messages assertions]
  (str "\n        conversation:\n        "
       (str/join "\n" (map py-message-str messages))
       "\n        \n        expectedOutcomes:\n        "
       "[" (str/join ", " (map py-str-repr assertions)) "]"
       "\n        "))

(defn- parse-judge-json [content]
  (let [trimmed (-> content str/trim
                    (str/replace #"^```(?:json)?\s*" "")
                    (str/replace #"\s*```$" ""))]
    (pj/parse trimmed)))

(defn nl-assertion-checks
  "LLM-judged NL assertions. `judge` is a generate function; upstream uses
   gpt-4.1 at temperature 0, so the judge model is part of the grading
   identity and must be reported with results."
  [task messages judge]
  (let [assertions (get-in task ["evaluation_criteria" "nl_assertions"])]
    (if (empty? assertions)
      []
      (let [{:keys [content]} (judge {:system nl-judge-system
                                      :messages [{:role :user
                                                  :content (nl-judge-prompt messages assertions)}]})
            results (get (parse-judge-json content) "results")]
        (mapv (fn [r] {:assertion (get r "expectedOutcome")
                       :met (true? (get r "metExpectation"))
                       :justification (get r "reasoning")})
              results)))))

(defn grade
  "tau2 `evaluate_simulation` with EvaluationType.ALL for one episode.
   `judge` may be nil when no task in scope needs NL assertions."
  [domain task {:keys [messages termination world db]} {:keys [judge]}]
  (if-not (#{:agent-stop :user-stop} termination)
    {:reward 0.0 :termination termination
     :note "Simulation terminated prematurely"}
    (let [basis (set (get-in task ["evaluation_criteria" "reward_basis"]))
          hash-fn (or (:world-hash domain) (:db-hash domain))
          final (if (some? world) world db)
          db-match (= (hash-fn (gold-world domain task)) (hash-fn final))
          comm (communicate-checks task messages)
          actions (action-checks task messages)
          nl (when (contains? basis "NL_ASSERTION")
               (if judge
                 (nl-assertion-checks task messages judge)
                 (throw (ex-info "Task requires an NL assertion judge"
                                 {:type ::judge-required
                                  :task (get task "id")}))))
          ;; Telecom grades the final world (agent DB + user device) with
          ;; the domain's own environment assertions.
          env (when (contains? basis "ENV_ASSERTION")
                ((or (:env-assertion-checks domain)
                     (throw (ex-info "Domain has no environment assertions" {:domain (:domain domain)})))
                 task final))
          components (cond-> {}
                       (contains? basis "DB") (assoc :db (if db-match 1.0 0.0))
                       (contains? basis "ACTION")
                       (assoc :action (if (every? :met actions) 1.0 0.0))
                       (contains? basis "COMMUNICATE")
                       (assoc :communicate (if (every? :met comm) 1.0 0.0))
                       (contains? basis "NL_ASSERTION")
                       (assoc :nl-assertion (if (every? :met nl) 1.0 0.0))
                       (contains? basis "ENV_ASSERTION")
                       (assoc :env-assertion (if (every? :met env) 1.0 0.0)))]
      (when-let [unsupported (seq (remove #{"DB" "ACTION" "COMMUNICATE" "NL_ASSERTION" "ENV_ASSERTION"} basis))]
        (throw (ex-info "Unsupported reward basis" {:basis (set unsupported)})))
      {:reward (reduce * 1.0 (vals components))
       :termination termination
       :reward-breakdown components
       :db-match db-match
       :action-checks (when (contains? basis "ACTION") actions)
       :communicate-checks comm
       :nl-assertions nl
       :env-assertion-checks env})))
