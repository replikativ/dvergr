(ns dvergr.benchmarks.tau2.harness
  "Dvergr's own agent loop as a tau2 candidate.

   The reference candidate (`dvergr.benchmarks.tau2.live/model-generate`)
   reproduces tau2's LLMAgent: one model step per protocol step. A harness
   candidate instead answers each user message with a complete Dvergr agent
   turn (`dvergr.chat.agent/run-agent-turn!`: provider formatting, tool
   execution, budget accounting, doom-loop detection, compaction) inside one
   working chat context per episode. The benchmark world stays a pure value:
   the episode driver hands it in, tools run against it, and the turn returns
   the new world plus every tool call as tau2-shaped messages, so DB and
   ACTION grading are unchanged.

   Action spaces:
     :tools  the domain tools as ordinary JSON-schema tools (tau2's surface)
     :repl   one `clojure_eval` tool; the domain tools are SCI functions in
             namespace `tau2` returning the same strings, so an agent can
             compose lookups, filter records, and compute in Clojure."
  (:require [clojure.string :as str]
            [dvergr.benchmarks.tau2.core :as t2]
            [dvergr.benchmarks.tau2.pyjson :as pj]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.chat.context :as chat-context]
            [dvergr.agent.turn :as turn]
            [dvergr.discourse :as d]
            [dvergr.model.providers :as providers]
            [dvergr.model.registry :as registry]
            [dvergr.room.store.memory :as memory]
            [dvergr.sandbox :as sandbox]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- stringify-keys [x]
  (cond
    (map? x) (into (array-map) (map (fn [[k v]] [(if (keyword? k) (name k) k) (stringify-keys v)])) x)
    (sequential? x) (mapv stringify-keys x)
    :else x))

(defn- call-recorder
  "A function executing one tool call against the shared world, logging the
   tau2 messages (assistant tool call + tool result)."
  [domain state]
  (fn [tool-name args]
    (let [id (str "call_" (count (:log @state)))
          args (stringify-keys (or args {}))
          {:keys [world content error]} ((:respond domain) (:world @state) :assistant tool-name args)]
      (swap! state (fn [s] (-> s
                               (assoc :world world)
                               (update :log conj
                                       {:role :assistant :content nil
                                        :tool-calls [{:id id :name tool-name :arguments args}]}
                                       {:role :tool :id id :content content :error error
                                        :requestor :assistant}))))
      content)))

(defn- schema-tools
  "tau2 JSON schemas as Dvergr tool definitions executing via `call!`."
  [domain call!]
  (into {}
        (map (fn [{:strs [function]}]
               (let [{:strs [name description parameters]} function]
                 [name {:name name :description description :parameters parameters
                        ;; Always a success: the model sees upstream's exact
                        ;; `Error: ...` text; the error flag lives in the log.
                        :execute (fn [input _ctx] {:type :success :content (call! name input)})}])))
        (:tool-schemas domain)))

(defn- install-tau2-namespace!
  "Bind every domain tool as a host function `tau2/<name>` in the SCI
   interpreter (one map argument with string keys; returns the tool's text),
   plus `tau2/parse` for JSON results."
  [sci-ctx domain call!]
  (sandbox/add-namespace!
   sci-ctx 'tau2
   (into {'parse (fn [s] (pj/parse s))}
         (map (fn [{:strs [function]}]
                (let [tool-name (get function "name")]
                  [(symbol tool-name) (fn ([] (call! tool-name {}))
                                        ([args] (call! tool-name args)))])))
         (:tool-schemas domain))))

(defn- repl-tools [domain sci-ctx execution-ctx]
  (let [tool {:name "clojure_eval"
              :description
              (str "Evaluate Clojure code in a sandbox. The customer-service tools are "
                   "functions in namespace `tau2`, each taking one map of arguments with "
                   "string keys and returning the tool's text result exactly as the tool "
                   "would, e.g. (tau2/get_order_details {\"order_id\" \"#W0000001\"}). "
                   "Results are JSON text or plain messages; (tau2/parse s) parses JSON text "
                   "into Clojure data. Available functions: "
                   (str/join ", " (map #(get-in % ["function" "name"]) (:tool-schemas domain))) ".")
              :parameters {"type" "object"
                           "properties" {"code" {"type" "string"
                                                 "description" "Clojure code to evaluate"}}
                           "required" ["code"]}
              :execute
              (fn [input _ctx]
                (let [code (or (get input :code) (get input "code"))
                      result (sandbox/eval-code sci-ctx code
                                                :timeout-ms 20000
                                                :execution-context execution-ctx)]
                  {:type :success
                   :content (str (when-let [out (not-empty (:stdout result))] (str out "\n"))
                                 (if (:success result)
                                   (pr-str (:value result))
                                   (str "Error: " (get-in result [:error :message]))))}))}]
    {"clojure_eval" tool}))

(defn make-agent-turn
  "Return `{:turn (fn [{:keys [world history message]}] -> {:world :reply :messages :usage})
            ;; Dvergr's own view of the episode: the system prompt as sent, every
     ;; model message with its tool uses (e.g. the clojure_eval code), and
     ;; every tool result, in chat order.
     :transcript (fn []
                   (mapv (fn [m]
                           (let [m (into {} (remove (fn [[k _]] (= :db/id k))) m)]
                             (cond-> m
                               (:message/tool-uses m)
                               (update :message/tool-uses
                                       (fn [tus] (mapv #(into {} (remove (fn [[k _]] (= :db/id k))) %)
                                                       tus))))))
                         (chat-context/get-messages chat-ctx)))
     :close (fn [])}` for one episode.

   `spec` is `{:model id :provider kw? :action-space :tools|:repl
   :max-model-steps n :budget-dollars n}`."
  [domain {:keys [model provider action-space max-model-steps budget-dollars]
           :or {action-space :tools max-model-steps 30 budget-dollars 5.0}}]
  (providers/ensure-initialized!)
  (let [model-id (registry/resolve-alias model)
        provider (or provider (:provider (registry/get-model! model-id)))
        room (d/make-room {:id (keyword (str "tau2-episode-" (random-uuid)))
                           :store (memory/make)})
        chat-ctx (turn/new-working-ctx {:execution-ctx (:ctx room)
                                        :title "tau2 candidate"
                                        :budget-dollars budget-dollars})
        state (atom {:world nil :log []})
        call! (call-recorder domain state)
        sci-ctx (chat-context/sci-context-in chat-ctx (:ctx room))
        _ (when (= :repl action-space) (install-tau2-namespace! sci-ctx domain call!))
        tool-map (case action-space
                   :tools (schema-tools domain call!)
                   :repl (repl-tools domain sci-ctx (:ctx room)))
        tool-ctx {:chat-ctx chat-ctx :tools tool-map :sci-ctx sci-ctx}]
    (chat-context/add-message! chat-ctx {:role :system
                                         :content (t2/agent-system-prompt domain)})
    (chat-context/add-message! chat-ctx {:role :assistant :content t2/first-agent-message})
    {:turn
     (fn [{:keys [world message]}]
       (reset! state {:world world :log []})
       (chat-context/add-message! chat-ctx {:role :user :content message})
       (let [outcome (loop [step 0]
                       (let [o (binding [ec/*execution-context* (:ctx room)]
                                 (chat-agent/run-agent-turn!
                                  chat-ctx {:provider provider :model model-id
                                            :tools tool-map :tool-ctx tool-ctx
                                            :auto-compact? false :turn-number step}))]
                         (cond
                           (not= :continue o) o
                           (>= (inc step) max-model-steps) :max-model-steps
                           :else (recur (inc step)))))
             reply (->> (chat-context/get-messages chat-ctx)
                        reverse
                        (some #(when (= :assistant (or (:message/role %) (:role %)))
                                 (or (:message/content %) (:content %)))))]
         {:world (:world @state)
          :messages (:log @state)
          :reply (when (= :complete outcome) reply)
          :outcome outcome
          :usage (chat-context/get-budget chat-ctx)}))
     ;; Dvergr's own view of the episode: the system prompt as sent, every
     ;; model message with its tool uses (e.g. the clojure_eval code), and
     ;; every tool result, in chat order.
     :transcript (fn []
                   (mapv (fn [m]
                           (let [m (into {} (remove (fn [[k _]] (= :db/id k))) m)]
                             (cond-> m
                               (:message/tool-uses m)
                               (update :message/tool-uses
                                       (fn [tus] (mapv #(into {} (remove (fn [[k _]] (= :db/id k))) %)
                                                       tus))))))
                         (chat-context/get-messages chat-ctx)))
     :close (fn []
              (try (chat-context/close-chat! chat-ctx) (catch Throwable _ nil))
              (d/close-room! room))}))
