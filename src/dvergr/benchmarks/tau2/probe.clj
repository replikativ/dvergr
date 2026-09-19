(ns dvergr.benchmarks.tau2.probe
  "Decision-point probes: re-sample one candidate turn from a recorded episode.

   A certified episode's log fixes the dialogue and every tool effect, so the
   world and the candidate's history at any customer message can be rebuilt
   exactly (the world by replaying the effects through the verified
   transcription). A probe gives that prefix to a fresh candidate -- Dvergr's
   agent turn with the same system prompt and tools as the episode candidate --
   and runs only the agent's answer to that message, `n` times. That costs one
   agent turn instead of a whole episode, so a prompt or harness change can be
   checked against the recorded decision points where candidates went wrong
   before a full run confirms it.

   Prior tool traffic is replayed in the candidate's own action space: JSON
   tool calls for :tools, one `(tau2/<tool> args)` evaluation per call for
   :repl. Probes are diagnostics, never certified results."
  (:require [clojure.string :as str]
            [dvergr.benchmarks.tau2.core :as t2]
            [dvergr.benchmarks.tau2.episode :as ep]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.chat.context :as chat-context]
            [dvergr.discourse :as d]
            [dvergr.agent.turn :as turn]
            [dvergr.model.providers :as providers]
            [dvergr.model.registry :as registry]
            [dvergr.room.store.memory :as memory]
            [dvergr.tools :as tools]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- sorted-log [log] (sort-by :seq log))

(defn world-at
  "The world just before log entry `cut`: the task's initial world with every
   earlier tool effect replayed."
  [domain task log cut]
  (reduce (fn [w {:keys [kind requestor tool arguments]}]
            (if (= :tool kind)
              (:world ((:respond domain) w requestor tool arguments))
              w))
          ((:initial-world domain) task)
          (filter #(< (:seq %) cut) (sorted-log log))))

(defn- replayed-call [action-space i {:keys [tool arguments content]}]
  (let [id (str "probe_" i)]
    (case action-space
      :tools [{:role :assistant :content ""
               :tool-uses [{:tool-use/id id :tool-use/name tool :tool-use/input arguments}]}
              {:role :tool-result :tool-use-id id :content content}]
      :repl [{:role :assistant :content ""
              :tool-uses [{:tool-use/id id :tool-use/name "clojure_eval"
                           :tool-use/input {:code (str "(tau2/" tool " " (pr-str arguments) ")")}}]}
             {:role :tool-result :tool-use-id id :content (str "=> " (pr-str content))}])))

(defn prefix-messages
  "The candidate's chat history up to and including the customer message
   `cut` (after the greeting, which every candidate context starts with)."
  [log cut action-space]
  (->> (sorted-log log)
       (filter #(<= (:seq %) cut))
       (drop-while #(and (= :message (:kind %)) (= :assistant (:role %))
                         (= t2/first-agent-message (:content %))))
       (map-indexed vector)
       (mapcat (fn [[i {:keys [kind role requestor content] :as e}]]
                 (case kind
                   :message [{:role role :content content}]
                   :tool (when (= :assistant requestor) (replayed-call action-space i e)))))
       vec))

(defn customer-messages
  "`[seq content]` of every customer message in the log: the probe points."
  [log]
  (->> (sorted-log log)
       (filter #(and (= :message (:kind %)) (= :user (:role %))))
       (mapv (juxt :seq :content))))

(defn- run-turn
  "One Dvergr agent turn from `history` over `world`. Returns
   `{:reply :calls :world :outcome :transcript}`."
  [domain agent world history {:keys [provider model max-model-steps]}]
  (let [action-space (get-in agent [:agent/metadata :conversation/action-space] :tools)
        room (d/make-room {:id (keyword (str "tau2-probe-" (random-uuid))) :store (memory/make)})
        ;; Probes are diagnostics: nothing about them is persisted.
        chat-ctx (turn/new-working-ctx {:execution-ctx (:ctx room) :title "tau2 probe"
                                        :budget-dollars 5.0 :durable? false})
        state (atom {:world world :calls []})
        call! (fn [tool-name args]
                (let [args (t2/stringify-keys (or args {}))
                      {w :world :keys [content error]} ((:respond domain) (:world @state) :assistant tool-name args)]
                  (swap! state #(-> % (assoc :world w)
                                    (update :calls conj {:tool tool-name :arguments args :error error})))
                  content))
        sci-ctx (chat-context/sci-context-in chat-ctx (:ctx room))
        schemas (:tool-schemas domain)
        tool-map (case action-space
                   :tools (into {} (map (fn [{:strs [function]}]
                                          (let [{:strs [name description parameters]} function]
                                            [name {:name name :description description :parameters parameters
                                                   :execute (fn [input _] {:type :success :content (call! name input)})}])))
                                schemas)
                   :repl (do (ep/install-tau2-fns! sci-ctx schemas call!)
                             {"clojure_eval" (tools/get-tool "clojure_eval")}))
        tool-ctx {:chat-ctx chat-ctx :tools tool-map :sci-ctx sci-ctx :execution-ctx (:ctx room)}]
    (try
      (chat-context/add-message! chat-ctx {:role :system :content (ep/agent-system-prompt domain agent)})
      (chat-context/add-message! chat-ctx {:role :assistant :content t2/first-agent-message})
      (doseq [m history] (chat-context/add-message! chat-ctx m))
      (let [outcome (loop [step 0]
                      (let [o (binding [ec/*execution-context* (:ctx room)]
                                (chat-agent/run-agent-turn!
                                 chat-ctx {:provider provider :model model :tools tool-map
                                           :tool-ctx tool-ctx :auto-compact? false :turn-number step}))]
                        (cond (not= :continue o) o
                              (>= (inc step) (or max-model-steps 30)) :max-model-steps
                              :else (recur (inc step)))))
            msgs (chat-context/get-messages chat-ctx)
            reply (some #(when (= :assistant (or (:message/role %) (:role %)))
                           (or (:message/content %) (:content %)))
                        (reverse msgs))
            new-msgs (drop (+ 2 (count history)) msgs)]
        {:outcome outcome
         :reply (when (= :complete outcome) reply)
         :calls (:calls @state)
         :world (:world @state)
         :code (vec (for [m new-msgs tu (:message/tool-uses m)
                          :when (= "clojure_eval" (:tool-use/name tu))]
                      (get-in tu [:tool-use/input :tool-input.clojure-eval/code]
                              (str (:tool-use/input tu)))))})
      (finally
        (try (chat-context/close-chat! chat-ctx) (catch Throwable _ nil))
        (d/close-room! room)))))

(defn probe!
  "Re-sample the candidate's answer to customer message `cut` of a recorded
   episode `n` times (in parallel). `candidate` is an experiment candidate
   spec (`{:harness :dvergr :action-space :repl-guidance :model}`); `log` is
   the certified trajectory (`(:trajectory (:attempt/evidence attempt))`).
   Returns a vector of `{:reply :calls :code :outcome :world}`."
  [domain task log cut candidate n]
  (providers/ensure-initialized!)
  (let [{:keys [action-space repl-guidance model provider max-model-steps]
         :or {action-space :tools}} candidate
        model-id (registry/resolve-alias model)
        agent {:agent/metadata (cond-> {:conversation/harness :dvergr
                                        :conversation/action-space action-space}
                                 repl-guidance (assoc :conversation/repl-guidance repl-guidance))}
        opts {:provider (or provider (:provider (registry/get-model! model-id)))
              :model model-id :max-model-steps max-model-steps}
        world (world-at domain task log cut)
        history (prefix-messages log cut action-space)]
    (->> (range n)
         (mapv (fn [_] (future (try (run-turn domain agent world history opts)
                                    (catch Throwable t
                                      {:error (.getMessage t)
                                       :at (mapv str (take 12 (.getStackTrace t)))})))))
         (mapv deref))))

(defn summarize
  "Compact view of probe results: reply head, domain calls, evaluated code."
  [results]
  (mapv (fn [{:keys [reply calls code outcome error]}]
          (cond-> {:outcome outcome
                   :calls (mapv :tool calls)
                   :reply (some-> reply (subs 0 (min 240 (count reply))))}
            (seq code) (assoc :code (mapv #(subs % 0 (min 200 (count %))) code))
            error (assoc :error error)))
        results))
