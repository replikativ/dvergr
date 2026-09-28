(ns dvergr.benchmarks.automationbench.harness
  "Dvergr's own agent loop as an AutomationBench candidate.

   The reference candidate is upstream's loop: the model behind one API call
   per step. A harness candidate is Dvergr's agent loop
   (`dvergr.chat.agent/run-agent-turn!`: provider formatting, tool execution,
   budget accounting) in a working chat context of the Run's world, a turn at
   a time until it replies without tools or reaches the step bound. Every
   call still goes through the service and is recorded
   (`ep/call-tool!`), so its world replays like the reference's.

   Action spaces:
     :tools  upstream's tools (`api_search`, `api_fetch`, `base64_encode`) as
             ordinary JSON-schema tools, schemas as upstream shows them
     :repl   one `clojure_eval` tool; the tools are SCI functions in namespace
             `ab` (`ab/search`, `ab/fetch`, `ab/base64`) returning upstream's
             text, and `ab/parse` reads a JSON result into data, so the
             candidate can filter, join and compute over records in code
             instead of by eye"
  (:require [dvergr.agent.turn :as turn]
            [dvergr.benchmarks.automationbench.episode :as ep]
            [dvergr.benchmarks.pyjson :as pj]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.chat.context :as chat-context]
            [dvergr.model.providers :as providers]
            [dvergr.resource :as resource]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.ns.doc :as ns-doc]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- json-tools
  "Upstream's tool schemas as Dvergr tools calling through the service."
  [schemas call!]
  (into {}
        (map (fn [{:strs [function]}]
               (let [{:strs [name description parameters]} function]
                 [name {:name name :description description :parameters parameters
                        :execute (fn [input _ctx]
                                   {:type :success :content (call! name input)})}])))
        schemas))

(def ^:private repl-fns
  "`ab/<fn>` → upstream tool and how the one map argument maps onto it."
  {'search ["api_search" "Search the available API endpoints by keyword: {\"query\" \"inbox messages\" \"top_k\" 5}. Returns the endpoints (method, url, parameters) as JSON text."]
   'fetch ["api_fetch" "Call an endpoint: {\"method\" \"GET\" \"url\" \"https://...\" \"params\" {...} \"body\" {...}}. params and body may be maps; returns the API's JSON text."]
   'base64 ["base64_encode" "Encode text to base64url (Gmail raw/body fields): {\"text\" \"...\"}."]})

(defn- json-arg
  "`params`/`body` given as data become the JSON text upstream's tool takes."
  [args]
  (reduce (fn [m k] (let [v (get m k)] (if (or (map? v) (sequential? v)) (assoc m k (pj/dumps v)) m)))
          args ["params" "body"]))

(defn- install-ab-namespace! [sci-ctx call!]
  (let [fns (into {'parse (fn [s] (pj/parse s))}
                  (map (fn [[sym [tool _]]]
                         [sym (fn ([] (call! tool {}))
                                ([args] (call! tool (json-arg (pj/stringify-keys args)))))]))
                  repl-fns)
        docs (into {'parse ['([json-text]) "Parse a JSON text result into Clojure data (string keys)."]}
                   (map (fn [[sym [_ doc]]] [sym ['([args]) doc]]))
                   repl-fns)]
    (sandbox/add-namespace! sci-ctx 'ab (ns-doc/with-docs fns docs))))

(def ^:private guidance-head
  (str "\n\n<tools>\nYou act through the `clojure_eval` tool, a Clojure (SCI) REPL; definitions "
       "persist across evaluations. The APIs are functions in namespace `ab`, each taking one map "
       "with string keys and returning the API's text exactly: `(ab/search {\"query\" \"...\"})` "
       "finds endpoints, `(ab/fetch {\"method\" \"GET\" \"url\" \"...\" \"params\" {...}})` calls "
       "one (params and body may be maps), `(ab/base64 {\"text\" \"...\"})` encodes Gmail bodies. "
       "`(ab/parse s)` turns a JSON result into data. "))

(def ^:private guidance-tail
  (str "Writes (POST/PUT/PATCH/DELETE) take effect immediately: make each exactly once.\n</tools>"))

(def repl-guidance
  "What the REPL candidate is told about its action space, by variant (the
   candidate's `:repl-guidance`, default `:compute`); variants are what a
   pilot tunes over."
  {:compute (str guidance-head
                 "Several calls can run in one evaluation. Whenever a decision depends on "
                 "filtering, matching, comparing or summing records, do it in code over parsed data "
                 "rather than by eye. " guidance-tail)
   ;; fewer model steps: read everything a decision needs in one evaluation,
   ;; and return only what the decision needs
   :batch (str guidance-head
               "Batch: in ONE evaluation, fetch every record a decision needs (several `ab/fetch` "
               "calls, parsed), then return only the fields that decide it (a small map or vector), "
               "not whole API responses. Match, filter, compare and sum in code. " guidance-tail)
   :lean (str guidance-head guidance-tail)})

(defn- repl-tools [sci-ctx execution-ctx]
  {"clojure_eval"
   {:name "clojure_eval"
    :description "Evaluate Clojure code in the sandbox; the APIs are the functions in namespace `ab`."
    :parameters {"type" "object"
                 "properties" {"code" {"type" "string" "description" "Clojure code to evaluate"}}
                 "required" ["code"]}
    :execute (fn [input _ctx]
               (let [code (or (get input :code) (get input "code"))
                     result (sandbox/eval-code sci-ctx code :timeout-ms 60000
                                               :execution-context execution-ctx)]
                 {:type :success
                  :content (if (:success result)
                             (let [v (:value result)] (if (string? v) v (pr-str v)))
                             (str "Error: " (get-in result [:error :message])))}))}})

(defn system-prompt
  "The candidate's system prompt: the task's, plus the REPL guidance for `:repl`."
  ([system action-space] (system-prompt system action-space :compute))
  ([system action-space guidance]
   (case action-space
     :tools system
     :repl (str system (or (repl-guidance guidance)
                           (throw (ex-info "Unknown REPL guidance"
                                           {:type ::unknown-guidance :guidance guidance
                                            :known (set (keys repl-guidance))})))))))

(defn run-episode!
  "Dvergr's agent loop on the task in `room`'s world. `spec` is `{:provider
   :model :action-space :budget-dollars}`. Returns what
   `episode/run-episode!` returns, `:usage` being the chat budget."
  [{:keys [room max-turns cancelled? model-scope] :as episode}
   {:keys [provider model action-space budget-dollars guidance]
    :or {action-space :tools budget-dollars 2.0 guidance :compute}}]
  (let [{:keys [system messages tools]} (ep/task-prompt room)
        calls (atom [])
        call! (fn [name args] (ep/call-tool! (assoc episode :calls calls) name args))
        chat-ctx (turn/new-working-ctx {:execution-ctx (:ctx room)
                                        :title "automationbench candidate"
                                        :budget-dollars budget-dollars})
        sci-ctx (chat-context/sci-context-in chat-ctx (:ctx room))
        tool-map (case action-space
                   :tools (json-tools tools call!)
                   :repl (do (install-ab-namespace! sci-ctx call!)
                             (repl-tools sci-ctx (:ctx room))))
        transcript (fn []
                     (->> (chat-context/get-messages chat-ctx)
                          (remove #(= :system (:message/role %)))
                          (mapv (fn [m]
                                  (cond-> {:role (:message/role m) :content (str (:message/content m))}
                                    (seq (:message/tool-uses m))
                                    (assoc :tool-calls
                                           (mapv (fn [tu] {:id (str (:tool-use/id tu)) :name (:tool-use/name tu)
                                                           :arguments (pr-str (dissoc (:tool-use/input tu) :db/id))})
                                                 (:message/tool-uses m))))))))]
    (try
      (providers/ensure-initialized!)
      (chat-context/add-message! chat-ctx {:role :system :content (system-prompt system action-space guidance)})
      (doseq [{:keys [role content]} messages]
        (chat-context/add-message! chat-ctx {:role role :content content}))
      (let [[termination steps]
            (loop [turn 0]
              (cond
                (and cancelled? (cancelled?)) [:cancelled turn]
                (>= turn max-turns) [:max-turns turn]
                :else
                (let [outcome (binding [ec/*execution-context* (:ctx room)
                                        resource/*model-scope* model-scope]
                                (chat-agent/run-agent-turn!
                                 chat-ctx {:provider provider :model model :tools tool-map
                                           :tool-ctx {:chat-ctx chat-ctx :tools tool-map :sci-ctx sci-ctx}
                                           :auto-compact? false :turn-number turn}))]
                  ;; a failed turn (a provider error, a model that keeps
                  ;; answering nothing) ends the episode with the world as
                  ;; it is, as upstream's rollout ends on an error
                  (case outcome
                    :continue (recur (inc turn))
                    :complete [:agent-stop (inc turn)]
                    :cancelled [:cancelled (inc turn)]
                    [:agent-error (inc turn)]))))]
        {:termination termination :calls @calls :model-steps steps
         :usage (select-keys (chat-context/get-budget chat-ctx) [:used :by-type])
         :transcript (transcript)})
      (finally
        (try (chat-context/close-chat! chat-ctx) (catch Throwable _ nil))))))

(defn guidance-sha256
  "Digest of what a REPL candidate is told beyond the task (in its AgentDef)."
  ([action-space] (guidance-sha256 action-space :compute))
  ([action-space guidance]
   (when (= :repl action-space) (pj/sha256-hex (system-prompt "" :repl guidance)))))
