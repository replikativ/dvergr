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
            [dvergr.benchmarks.tau2.pyjson :as pj]
            [dvergr.benchmarks.tau2.retail :as retail]))

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
   :user-guidelines "740a29dfa64d7bc08eea3bf7493575b914a63f744acbaf7f199ee07eddaf72d3"})

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

(def ^:private domain-impls
  {"retail" {:respond retail/respond
             :normalize-db retail/normalize-db
             :db-hash retail/db-hash
             :tools-resource "benchmarks/tau2/retail-tools.json"}})

(defn load-domain
  "Load one text domain from a pinned tau2-bench checkout. Every data file is
   verified against the upstream digest recorded in `file-digests`."
  ([domain] (load-domain domain {}))
  ([domain {:keys [root] :or {root default-root}}]
   (let [impl (or (get domain-impls domain)
                  (throw (ex-info "Unsupported tau2 domain" {:domain domain})))
         dir (io/file root "data/tau2/domains" domain)
         digests (get file-digests domain)
         read-json #(pj/parse (verified-slurp (io/file dir %) (get digests %)))
         db ((:normalize-db impl) (read-json "db.json"))
         tasks (read-json "tasks.json")]
     (merge impl
            {:domain domain
             :db db
             :initial-db-hash ((:db-hash impl) db)
             :policy (verified-slurp (io/file dir "policy.md") (get digests "policy.md"))
             :tasks (into (array-map) (map (juxt #(get % "id") identity)) tasks)
             :splits (read-json "split_tasks.json")
             :tool-schemas (pj/parse (slurp (io/resource (:tools-resource impl))))
             :user-guidelines (verified-slurp
                               (io/file root "data/tau2/user_simulator/simulation_guidelines.md")
                               (:user-guidelines file-digests))}))))

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
  (str (str/replace (:user-guidelines domain) "<PERSONA_GUIDELINES>" "")
       "\n\n<scenario>\n" (scenario-str (get task "user_scenario")) "\n</scenario>"))

;; ---------------------------------------------------------------------------
;; Episode protocol

(defn- has-text? [content] (and (string? content) (not (str/blank? content))))

(defn- stringify-keys [x]
  (cond
    (map? x) (into (array-map) (map (fn [[k v]] [(if (keyword? k) (name k) k)
                                                 (stringify-keys v)])) x)
    (sequential? x) (mapv stringify-keys x)
    :else x))

(defn run-episode
  "Run one task and return `{:messages :termination :db :usage}`.

   `agent` and `user` are generate functions
   `(fn [{:keys [system messages tools]}] -> {:content str :tool-calls [{:id :name :arguments}] :usage map})`.
   Messages use tau2's shape: `{:role :assistant|:user|:tool :content str
   :tool-calls [...] :id str :error bool}`; the user simulator receives the
   role-flipped history of the text exchange, exactly like upstream."
  [domain task {:keys [agent user max-steps max-errors enforce-protocol?]
                :or {max-steps 200 max-errors 10 enforce-protocol? false}}]
  (let [agent-system (agent-system-prompt domain)
        user-system (user-system-prompt domain task)
        tools (:tool-schemas domain)
        respond (:respond domain)
        greeting {:role :assistant :content first-agent-message}]
    (loop [db (:db domain)
           trajectory [greeting]
           ;; histories as seen by each participant
           agent-view [greeting]
           user-view [{:role :user :content first-agent-message}]
           to :user
           steps 0
           errors 0
           usage []]
      (let [done (fn [reason]
                   {:messages trajectory :termination reason :db db
                    :steps steps :errors errors :usage usage})]
        (cond
          (>= steps max-steps) (done :max-steps)
          (>= errors max-errors) (done :too-many-errors)

          (= to :user)
          (let [{:keys [content usage] :as reply} (user {:system user-system
                                                         :messages user-view})
                msg {:role :user :content content}
                trajectory (conj trajectory msg)
                usage-log (conj usage {:role :user :usage (:usage reply)})]
            (cond
              (not (has-text? content))
              {:messages trajectory :termination :user-error :db db
               :steps (inc steps) :errors errors :usage usage-log}

              (some #(str/includes? content %) user-stop-tokens)
              {:messages trajectory :termination :user-stop :db db
               :steps (inc steps) :errors errors :usage usage-log}

              :else
              (recur db trajectory (conj agent-view msg)
                     (conj user-view {:role :assistant :content content})
                     :agent (inc steps) errors usage-log)))

          (= to :agent)
          (let [{:keys [content tool-calls] :as reply}
                (agent {:system agent-system :messages agent-view :tools tools})
                tool-calls (not-empty (mapv #(update % :arguments stringify-keys) tool-calls))
                msg (cond-> {:role :assistant :content content}
                      tool-calls (assoc :tool-calls tool-calls))
                trajectory (conj trajectory msg)
                usage-log (conj usage {:role :agent :usage (:usage reply)})
                stop? (and (string? content) (str/includes? content stop-token))]
            (cond
              ;; `validate()` rejects empty messages. Mixed text+tool-call
              ;; messages are only an error under tau2's opt-in
              ;; `enforce_communication_protocol` (default off); otherwise they
              ;; route to the environment and the user never sees the text.
              (or (and (not tool-calls) (not (has-text? content)))
                  (and enforce-protocol? tool-calls (has-text? content)))
              {:messages trajectory :termination :agent-error :db db
               :steps (inc steps) :errors errors :usage usage-log}

              stop?
              {:messages trajectory :termination :agent-stop :db db
               :steps (inc steps) :errors errors :usage usage-log}

              tool-calls
              ;; AGENT -> ENV is one step, ENV -> AGENT is the next.
              (let [[db results] (reduce (fn [[db results] {:keys [id name arguments]}]
                                           (let [{db' :db :keys [content error]}
                                                 (respond db name arguments)]
                                             [db' (conj results {:role :tool :id id
                                                                 :content content
                                                                 :error error})]))
                                         [db []]
                                         tool-calls)]
                (recur db (into trajectory results)
                       (into (conj agent-view msg) results)
                       user-view :agent (+ steps 2)
                       (+ errors (count (filter :error results)))
                       usage-log))

              :else
              (recur db trajectory (conj agent-view msg)
                     (conj user-view {:role :user :content content})
                     :user (inc steps) errors usage-log))))))))

;; ---------------------------------------------------------------------------
;; Grading

(defn gold-db
  "The database after replaying a task's gold actions (errors ignored, as
   upstream logs and continues)."
  [domain task]
  (reduce (fn [db {:strs [name arguments]}]
            (:db ((:respond domain) db name arguments)))
          (:db domain)
          (get-in task ["evaluation_criteria" "actions"])))

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
  [domain task {:keys [messages termination db]} {:keys [judge]}]
  (if-not (#{:agent-stop :user-stop} termination)
    {:reward 0.0 :termination termination
     :note "Simulation terminated prematurely"}
    (let [basis (set (get-in task ["evaluation_criteria" "reward_basis"]))
          hash-fn (:db-hash domain)
          db-match (= (hash-fn (gold-db domain task)) (hash-fn db))
          comm (communicate-checks task messages)
          nl (when (contains? basis "NL_ASSERTION")
               (if judge
                 (nl-assertion-checks task messages judge)
                 (throw (ex-info "Task requires an NL assertion judge"
                                 {:type ::judge-required
                                  :task (get task "id")}))))
          components (cond-> {}
                       (contains? basis "DB") (assoc :db (if db-match 1.0 0.0))
                       (contains? basis "COMMUNICATE")
                       (assoc :communicate (if (every? :met comm) 1.0 0.0))
                       (contains? basis "NL_ASSERTION")
                       (assoc :nl-assertion (if (every? :met nl) 1.0 0.0)))]
      (when-let [unsupported (seq (remove #{"DB" "COMMUNICATE" "NL_ASSERTION"} basis))]
        (throw (ex-info "Unsupported reward basis" {:basis (set unsupported)})))
      {:reward (reduce * 1.0 (vals components))
       :termination termination
       :reward-breakdown components
       :db-match db-match
       :communicate-checks comm
       :nl-assertions nl})))
