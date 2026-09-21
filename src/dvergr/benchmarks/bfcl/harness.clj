(ns dvergr.benchmarks.bfcl.harness
  "Dvergr's own agent step as a BFCL candidate.

   The reference candidate is the model behind one API call with the compiled
   tools. A harness candidate is ONE step of Dvergr's agent loop
   (`dvergr.chat.agent/run-agent-turn!`: provider formatting, tool execution,
   budget accounting) in a working chat context of the Run's world. BFCL grades
   the calls of the first response and executes nothing, so the functions are
   RECORDERS: they note the call and return a stub, and the step is never
   followed by a second one.

   Action spaces:
     :tools  the task's functions as ordinary JSON-schema tools
     :repl   one `clojure_eval` tool; the functions are SCI functions in
             namespace `bfcl`, each taking one map of arguments. Several calls
             are several forms in one evaluation, whatever the provider does
             with parallel tool calls."
  (:require [clojure.string :as str]
            [dvergr.agent.turn :as turn]
            [dvergr.benchmarks.bfcl.core :as bfcl]
            [dvergr.benchmarks.tau2.core :as t2]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.chat.context :as chat-context]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.ns.doc :as ns-doc]
            [org.replikativ.spindel.engine.core :as ec]))

(def ^:private recorded-reply "Recorded.")

(defn- json-tools
  "The compiled tools as Dvergr tool definitions that record their call."
  [tools record!]
  (into {}
        (map (fn [{:strs [name description input_schema]}]
               [name {:name name :description description :parameters input_schema
                      :execute (fn [input _ctx]
                                 (record! name input)
                                 {:type :success :content recorded-reply})}]))
        tools))

(defn- param-doc [[param {:strs [type description]}]]
  (str "  \"" param "\" (" type ")" (when-not (str/blank? description) (str ": " description))))

(defn- install-bfcl-namespace!
  "The task's functions as documented SCI functions `bfcl/<name>`."
  [sci-ctx tools record!]
  (let [fns (into {} (map (fn [{:strs [name]}]
                            [(symbol name) (fn ([] (record! name {}) recorded-reply)
                                             ([args] (record! name args) recorded-reply))]))
                  tools)
        docs (into {} (map (fn [{:strs [name description input_schema]}]
                             [(symbol name)
                              ['([args])
                               (str description "\nArguments (a map with string keys; required: "
                                    (str/join ", " (get input_schema "required")) "):\n"
                                    (str/join "\n" (map param-doc (get input_schema "properties"))))]]))
                   tools)]
    (sandbox/add-namespace! sci-ctx 'bfcl (ns-doc/with-docs fns docs))))

(defn- repl-tools [tools sci-ctx execution-ctx]
  {"clojure_eval"
   {:name "clojure_eval"
    :description
    (str "Evaluate Clojure code in a sandbox. The available functions are in namespace `bfcl`, "
         "each taking ONE map of arguments with string keys, e.g. "
         "(bfcl/some_function {\"city\" \"Paris\" \"days\" 3}). To call several functions, or one "
         "function several times, put every call in the SAME evaluation: "
         "(do (bfcl/f {...}) (bfcl/g {...})). Calls are recorded, not executed; they return "
         "\"Recorded.\". If no function fits the request, do not evaluate anything and answer in text. "
         "(clojure.repl/doc bfcl/<name>) describes a function. Functions: "
         (str/join ", " (map #(str "bfcl/" (get % "name")) tools)) ".")
    :parameters {"type" "object"
                 "properties" {"code" {"type" "string" "description" "Clojure code to evaluate"}}
                 "required" ["code"]}
    :execute (fn [input _ctx]
               (let [code (or (get input :code) (get input "code"))
                     result (sandbox/eval-code sci-ctx code :timeout-ms 20000
                                               :execution-context execution-ctx)]
                 {:type :success
                  :content (if (:success result)
                             (pr-str (:value result))
                             (str "Error: " (get-in result [:error :message])))}))}})

(def repl-guidance
  "What the REPL candidate is told about its action space, by variant. The
   `:direct` wording (\"you answer by calling functions\") made a model call a
   near-miss function on irrelevance tasks that the JSON-tool candidates left
   alone; `:neutral` says what a tool schema says: the functions exist, use
   them when they fit."
  {:direct "You answer by calling functions through the `clojure_eval` tool."
   :neutral (str "Functions are available through the `clojure_eval` tool. Call them when one of "
                 "them does what the request asks for; when none does, answer in text and "
                 "evaluate nothing.")
   ;; measured and rejected: also telling the model to answer in text when
   ;; \"the request lacks something a function requires\" makes it ask for
   ;; clarification where BFCL's relevance tasks expect a call
   :cautious (str "Functions are available through the `clojure_eval` tool. Use them only when "
                  "one of them does what the request asks for; when none does, or the request "
                  "lacks something a function requires, answer in text and evaluate nothing.")})

(defn- function-docs
  "The functions with their parameters, for the REPL candidate's prompt: the
   JSON-tool candidate gets the same information as tool schemas."
  [tools]
  (str/join "\n\n"
            (map (fn [{:strs [name description input_schema]}]
                   (str "bfcl/" name ": " description "\n  required: "
                        (str/join ", " (get input_schema "required")) "\n"
                        (str/join "\n" (map param-doc (get input_schema "properties")))))
                 tools)))

(defn step
  "One Dvergr agent step on `task` in `room`'s world. `spec` is `{:provider
   :model :action-space :budget-dollars}`. Returns `{:calls :content :usage
   :outcome}`; `:calls` is upstream's decoded shape, `[{tool-name args}]`, in
   the order the candidate made them."
  [room task {:keys [system messages]} {:keys [provider model action-space budget-dollars guidance
                                               parallel-tool-calls]
                                        :or {action-space :tools budget-dollars 1.0 guidance :neutral}}]
  (let [tools (bfcl/compile-tools (:functions task))
        calls (atom [])
        record! (fn [tool-name args]
                  (swap! calls conj {tool-name (t2/stringify-keys (dissoc (or args {}) :db/id))}))
        chat-ctx (turn/new-working-ctx {:execution-ctx (:ctx room)
                                        :title "bfcl candidate"
                                        :budget-dollars budget-dollars})
        sci-ctx (chat-context/sci-context-in chat-ctx (:ctx room))
        tool-map (case action-space
                   :tools (json-tools tools record!)
                   :repl (do (install-bfcl-namespace! sci-ctx tools record!)
                             (repl-tools tools sci-ctx (:ctx room))))
        system (case action-space
                 :tools system
                 :repl (str (when system (str system "\n\n"))
                            (or (repl-guidance guidance)
                                (throw (ex-info "Unknown REPL guidance" {:guidance guidance})))
                            "\n\n"
                            (function-docs tools)))]
    (try
      (when-not (str/blank? system)
        (chat-context/add-message! chat-ctx {:role :system :content system}))
      (doseq [{:keys [role content]} messages]
        (chat-context/add-message! chat-ctx {:role role :content content}))
      (let [outcome (binding [ec/*execution-context* (:ctx room)]
                      (chat-agent/run-agent-turn!
                       chat-ctx {:provider provider :model model
                                 :tools tool-map
                                 :tool-ctx {:chat-ctx chat-ctx :tools tool-map :sci-ctx sci-ctx}
                                 :model-opts (when (some? parallel-tool-calls)
                                               {:parallel-tool-calls parallel-tool-calls})
                                 :auto-compact? false :turn-number 0}))
            reply (->> (chat-context/get-messages chat-ctx)
                       reverse
                       (some #(when (= :assistant (or (:message/role %) (:role %)))
                                (or (:message/content %) (:content %)))))]
        {:calls @calls
         :content (when-not (str/blank? (str reply)) (str reply))
         :outcome outcome
         :usage (select-keys (chat-context/get-budget chat-ctx) [:used :by-type])})
      (finally
        (try (chat-context/close-chat! chat-ctx) (catch Throwable _ nil))))))
