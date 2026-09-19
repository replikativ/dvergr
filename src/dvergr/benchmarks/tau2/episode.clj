(ns dvergr.benchmarks.tau2.episode
  "One tau2 task as a certified conversational episode inside Rooms.

   Episode Room participants:
     :agent     the candidate: Dvergr's production `llm-agent` (JSON tools or
                the `clojure_eval` REPL action space) or tau2's reference
                LLMAgent loop
     :customer  the simulated user (trusted environment participant)

   The tau2 world value lives in the episode Room's execution-context state.
   Every tool call of either side goes through one effect interpreter: under
   the episode lock it applies the verified transcription, advances the world
   and the episode log together, then records an effect row. tau2's step and
   error bounds are enforced there and after every dialogue message.

   The host thread orchestrates: admit the episode Run, set up, wait for
   termination, cancel and await candidate Runs, grade, certify, tear down.
   Whatever fails after admission, the episode Run is finished and an Attempt
   is certified: infrastructure faults as status :failed (reward 0, excluded
   from metrics), model outcomes as :completed. See
   doc/conversation-evaluation.md."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [dvergr.agent.conversation :as conv]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.room-context :as room-context]
            [dvergr.agent.run :as run]
            [dvergr.benchmarks.tau2.core :as t2]
            [dvergr.benchmarks.tau2.live :as live]
            [dvergr.benchmarks.tau2.pyjson :as pj]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.chat.context :as cc]
            [dvergr.discourse :as d]
            [dvergr.discourse.attention :as attention]
            [dvergr.discourse.generation :as gen]
            [dvergr.discourse.llm :as llm]
            [dvergr.model.providers :as providers]
            [dvergr.sandbox :as sandbox]
            [dvergr.tools :as tools]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [taoensso.telemere :as tel]))

(def ^:private world-path [::world])

;; ---------------------------------------------------------------------------
;; Episode state

(defn- world [room]
  (binding [ec/*execution-context* (:ctx room)]
    (ec/get-state world-path)))

(defn- set-world! [room w]
  (binding [ec/*execution-context* (:ctx room)]
    (ec/swap-state! world-path (constantly w))))

(defn- ended? [episode] (realized? (:ended episode)))

(defn- end!
  "Deliver the episode termination once."
  [{:keys [ended]} termination]
  (deliver ended termination))

(defn- stack-head
  "The first frames of `t`'s stack (and its root cause's), for fault evidence."
  [^Throwable t]
  (let [root (last (take-while some? (iterate #(.getCause ^Throwable %) t)))]
    (cond-> {:frames (mapv str (take 15 (.getStackTrace t)))}
      (not (identical? root t))
      (assoc :root {:class (.getName (class root)) :message (.getMessage ^Throwable root)
                    :frames (mapv str (take 15 (.getStackTrace ^Throwable root)))}))))

(defn- fault!
  "Record an infrastructure fault (never scored as model behavior)."
  [{:keys [failure] :as episode} source error]
  (compare-and-set! failure nil
                    {:source source
                     :class (if (instance? Throwable error) (.getName (class error)) "error-result")
                     :message (if (instance? Throwable error) (.getMessage ^Throwable error) (str error))
                     :data (when (instance? clojure.lang.ExceptionInfo error)
                             (pr-str (ex-data error)))
                     :stack (when (instance? Throwable error) (stack-head error))})
  (end! episode :infrastructure-error))

(defn- bound-exceeded!
  "End the episode when tau2's step or error bound is reached."
  [{:keys [room state limits] :as episode}]
  (let [{:keys [steps errors]} @state]
    (when-let [reason (cond (>= steps (:max-steps limits)) :max-steps
                            (>= errors (:max-errors limits)) :too-many-errors)]
      (end! episode reason)
      (run/cancel-room-runs! (:id room))
      reason)))

(defn- add-usage! [{:keys [state]} role usage]
  (when (map? usage)
    (swap! state update-in [:usage role]
           (fn [acc] (merge-with (fn [a b] (if (and (number? a) (number? b)) (+ a b) b))
                                 (or acc {})
                                 (select-keys usage [:input-tokens :output-tokens
                                                     :cache-read-tokens :cache-creation-tokens]))))))

(defn- add-steps!
  "tau2 accounting: a generated message is one step, a tool batch plus its
   results two."
  [{:keys [state]} n]
  (swap! state update :steps + n))

(defn- log-entry!
  "Append to the episode log (the graded order) and return its seq."
  [{:keys [state]} entry]
  (:seq (swap! state (fn [s] (let [n (inc (:seq s))]
                               (-> s (assoc :seq n)
                                   (update :log conj (assoc entry :seq n))))))))

(defn- effect!
  "Apply one tool call of `requestor` (:assistant | :user). World and log
   advance together under the episode lock, then the effect row is posted;
   a failed post is an infrastructure fault. Returns the tool content."
  [{:keys [domain room state lock] :as episode} requestor tool-name args]
  (locking lock
    (if (ended? episode)
      "Error: The conversation has ended."
      (let [args (if (map? args) (t2/stringify-keys args) args)
            {w :world :keys [content error]} ((:respond domain) (world room) requestor tool-name args)
            _ (set-world! room w)
            _ (when error (swap! state update :errors inc))
            seq-no (log-entry! episode {:kind :tool :requestor requestor :tool tool-name
                                        :arguments args :content content :error error})]
        (try
          (conv/post-effect! room {:seq seq-no :requestor requestor :tool tool-name
                                   :arguments args :content content :error error})
          (catch Throwable t (fault! episode :effect-row t)))
        (bound-exceeded! episode)
        content))))

(defn- record-message!
  "Log a dialogue message (one step); the durable row is the Room message."
  [episode role content]
  (add-steps! episode 1)
  (log-entry! episode {:kind :message :role role :content content}))

(defn trajectory
  "tau2-shaped messages from the episode log (tool calls with their results)."
  [log]
  (vec (mapcat (fn [{:keys [kind role content requestor tool arguments error seq]}]
                 (case kind
                   :message [{:role role :content content}]
                   :tool (let [id (str "call_" seq)
                               caller (if (= :user requestor) :user :assistant)]
                           [{:role caller :content nil
                             :tool-calls [{:id id :name tool :arguments arguments}]}
                            {:role :tool :id id :content content :error error
                             :requestor caller}])))
               (sort-by :seq log))))

;; ---------------------------------------------------------------------------
;; Candidate tools

(defn- schema-tool-map [episode schemas requestor]
  (into {}
        (map (fn [{:strs [function]}]
               (let [{:strs [name description parameters]} function]
                 [name {:name name :description description :parameters parameters
                        :execute (fn [input _ctx]
                                   {:type :success
                                    :content (effect! episode requestor name input)})}])))
        schemas))

(defn- install-tau2-namespace!
  "Domain tools as SCI functions `tau2/<tool>` (one map argument, the tool's
   exact text result) plus `tau2/parse` for JSON text."
  [sci-ctx episode]
  (sandbox/add-namespace!
   sci-ctx 'tau2
   (into {'parse (fn [s] (pj/parse s))}
         (map (fn [{:strs [function]}]
                (let [tool-name (get function "name")]
                  [(symbol tool-name)
                   (fn ([] (effect! episode :assistant tool-name {}))
                     ([args] (effect! episode :assistant tool-name args)))])))
         (get-in episode [:domain :tool-schemas]))))

(def repl-guidance
  "Appended to the agent system prompt for the REPL action space."
  (str "\n\n<tools>\nYou act through the `clojure_eval` tool, a Clojure (SCI) REPL. "
       "The customer-service tools are Clojure functions in namespace `tau2`, "
       "each taking one map with string keys and returning the tool's text "
       "result exactly, e.g. (tau2/get_order_details {\"order_id\" \"#W0000001\"}). "
       "(tau2/parse s) turns JSON text into Clojure data, so you can filter, "
       "count and select precisely in code instead of reading long JSON by eye. "
       "Write actions (modify/cancel/exchange/return/transfer and similar) take "
       "effect immediately: only call them after explicit user confirmation, "
       "exactly once. Available: %s.\n</tools>"))

(defn agent-system-prompt
  "The system prompt a candidate receives (recorded as a hash in evidence)."
  [domain agent]
  (cond-> (t2/agent-system-prompt domain)
    (= :repl (get-in agent [:agent/metadata :conversation/action-space]))
    (str (format repl-guidance
                 (str/join ", " (map #(get-in % ["function" "name"]) (:tool-schemas domain)))))))

;; ---------------------------------------------------------------------------
;; Participants

(defn- enqueue-policy [_] (attention/enqueue :tau2/half-duplex))

(defn- dvergr-candidate
  "Dvergr's production LLM participant with the environment's tools. Returns
   `{:participant :after-greeting (fn []) :usage (fn [])}`."
  [episode agent]
  (providers/ensure-initialized!)
  (let [{:keys [domain room]} episode
        {:keys [provider model]} (:agent/model-policy agent)
        {:keys [max-model-steps budget-dollars]} (:agent/program agent)
        action-space (get-in agent [:agent/metadata :conversation/action-space] :tools)
        system-prompt (agent-system-prompt domain agent)
        budget (double (or budget-dollars 5.0))
        model-steps (atom 0)
        ctx-opts {:system-prompt system-prompt :budget-dollars budget}
        tool-map (case action-space
                   :tools (schema-tool-map episode (:tool-schemas domain) :assistant)
                   :repl {"clojure_eval" (tools/get-tool "clojure_eval")})]
    {:participant
     (llm/llm-agent
      {:id :agent
       :ctx (:ctx room)
       :spec {:provider provider :model model :system-prompt system-prompt}
       :tools tool-map
       :budget {:dollars budget}
       :compaction {:auto? false}
       :room-safe? false
       :attention-policy enqueue-policy
       ;; Strip the wall-clock now-note (tau2 worlds have their own frozen
       ;; clock), cap model steps per episode, and account two steps per
       ;; model step that ran tools (the text reply is counted by the
       ;; customer when it arrives).
       :run-turn-fn (fn [chat-ctx opts]
                      (if (> (swap! model-steps inc) (or max-model-steps 100))
                        (do (end! episode :max-model-steps) :error)
                        (let [outcome (chat-agent/run-agent-turn! chat-ctx (dissoc opts :system-suffix))]
                          (when (= :continue outcome) (add-steps! episode 2))
                          outcome)))})
     ;; The working ctx hydrates from the store on first use, so it is
     ;; resolved after the greeting is durable (both action spaces see it).
     :after-greeting
     (fn []
       (let [chat-ctx (room-context/ensure-ctx! room :agent ctx-opts)]
         (when (= :repl action-space)
           (install-tau2-namespace! (cc/sci-context chat-ctx) episode))))
     :usage (fn []
              (some-> (room-context/ensure-ctx! room :agent ctx-opts) cc/get-budget
                      (select-keys [:used :by-type])))}))

(defn- generate-loop
  "Model steps until a text message: tool calls go through the effect
   interpreter as `requestor`. Returns the text, or nil once the episode has
   ended. Throws on provider failure."
  [episode role generate request-fn history requestor]
  (loop []
    (when-not (ended? episode)
      (let [{:keys [content tool-calls usage]} (generate (request-fn @history))
            calls (not-empty (vec tool-calls))]
        (add-usage! episode role usage)
        (swap! history conj (cond-> {:role :assistant :content content}
                              calls (assoc :tool-calls calls)))
        (if calls
          (do (add-steps! episode 2)
              (doseq [{:keys [id name arguments]} calls]
                (swap! history conj {:role :tool :id id
                                     :content (effect! episode requestor name arguments)}))
              (recur))
          content)))))

(defn- reference-candidate
  "tau2's LLMAgent: one model step per protocol step, tool calls executed by
   the environment, text goes to the customer. Each inbound message is one
   `:agent-turn` Run whose cancellation interrupts the model call."
  [episode agent agent-generate]
  (let [{:keys [domain room]} episode
        generate (or agent-generate (live/model-generate (:agent/model-policy agent)))
        system (t2/agent-system-prompt domain)
        tools (:tool-schemas domain)
        history (atom [{:role :assistant :content t2/first-agent-message}])]
    {:participant
     (d/participant
      {:id :agent
       :ctx (:ctx room)
       :on-message
       (fn [_p msg]
         (sp/spin
          (let [run-id (:run/id (run/start! room :agent msg nil))
                h (gen/future-handle
                   (:ctx room)
                   (fn []
                     (swap! history conj {:role :user :content (:content msg)})
                     (generate-loop episode :agent generate
                                    (fn [h] {:system system :messages h :tools tools})
                                    history :assistant)))
                _ (run/register-cancel-hook! run-id ::model-call (:cancel! h))
                reply (sp/await (:done h))]
            (cond
              (gen/error-result? reply)
              (do (fault! episode :agent-model (::gen/error reply))
                  (run/finish! run-id :failed {:reason :provider-error})
                  nil)

              (and (string? reply) (not (str/blank? reply)))
              (d/after-reply-emission {:to :customer :content reply}
                                      (fn [_] (run/finish! run-id :completed))
                                      (fn [_] (run/finish! run-id :failed)))

              :else
              (do (run/finish! run-id (if (ended? episode) :cancelled :failed)
                               {:reason :no-reply})
                  (when-not (ended? episode) (end! episode :agent-error))
                  nil)))))})
     :after-greeting (fn [])
     :usage (fn [] (get-in @(:state episode) [:usage :agent]))}))

(defn- customer
  "The simulated user: role-flipped history of the dialogue, its own tools
   through the effect interpreter, stop tokens end the episode."
  [episode user-generate]
  (let [{:keys [domain room task]} episode
        system (t2/user-system-prompt domain task)
        user-tools ((:user-tool-schemas domain) task)
        history (atom [])
        ;; Recorded, but addressed to the activity channel so it cannot start
        ;; another candidate turn.
        record-final! (fn [reply kind]
                        (record-message! episode :user reply)
                        (d/post! room (d/message :customer :_activity reply nil
                                                 {:role :user :kind kind})))]
    (d/participant
     {:id :customer
      :ctx (:ctx room)
      :on-message
      (fn [_p msg]
        (sp/spin
         (when (and (= :agent (:from msg)) (not (ended? episode)))
           (let [text (str (:content msg))]
             (record-message! episode :assistant text)
             (cond
               (str/includes? text t2/stop-token) (do (end! episode :agent-stop) nil)
               (bound-exceeded! episode) nil
               :else
               (let [h (gen/future-handle
                        (:ctx room)
                        (fn []
                          (swap! history conj {:role :user :content text})
                          (generate-loop episode :customer user-generate
                                         (fn [h] (cond-> {:system system :messages h}
                                                   user-tools (assoc :tools user-tools)))
                                         history :user)))
                     reply (sp/await (:done h))]
                 (cond
                   (gen/error-result? reply) (do (fault! episode :user-model (::gen/error reply)) nil)
                   (ended? episode) nil
                   (not (and (string? reply) (not (str/blank? reply))))
                   (do (end! episode :user-error) nil)
                   (some #(str/includes? reply %) t2/user-stop-tokens)
                   (do (record-final! reply :tau2/stop) (end! episode :user-stop) nil)
                   :else
                   (do (record-message! episode :user reply)
                       (if (bound-exceeded! episode)
                         (do (d/post! room (d/message :customer :_activity reply nil
                                                      {:role :user :kind :tau2/after-bound}))
                             nil)
                         {:to :agent :content reply :metadata {:role :user}})))))))))})))

;; ---------------------------------------------------------------------------
;; Orchestration (host thread)

(defn- await-quiescence! [room-id timeout-ms]
  (loop [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (cond
      (empty? (run/active-runs room-id)) true
      (> (System/currentTimeMillis) deadline) false
      :else (do (Thread/sleep 50) (recur deadline)))))

(defn- checks-from [grade termination]
  (merge {:terminated-normally (contains? #{:agent-stop :user-stop} termination)}
         (into {} (map (fn [[k v]] [k (= 1.0 (double v))])) (:reward-breakdown grade))))

(defn- elapsed-ms [started-nanos]
  (long (/ (- (System/nanoTime) started-nanos) 1000000)))

(defn- certify-fault!
  "Certify an episode whose orchestration failed after admission."
  [experiment-room definition agent run-id opened room-id started-at started-nanos metrics t]
  (let [failure {:source :orchestration :class (.getName (class t)) :message (.getMessage ^Throwable t)
                 :stack (stack-head t)}]
    (tel/log! {:level :error :id ::episode-fault :error t :data {:room room-id}}
              "tau2 episode orchestration failed")
    (try (conv/finish-episode! run-id :failed :infrastructure-error) (catch Throwable _ nil))
    {:attempt (conv/certify!
               experiment-room definition agent
               {:run-id run-id :status :failed :started-at started-at
                :elapsed-ms (elapsed-ms started-nanos)
                :checks {:terminated-normally false} :reward 0.0
                :metrics (assoc metrics :termination :infrastructure-error)
                :evidence {:result {:termination :infrastructure-error}
                           :trace {:runs [{:run/id run-id}] :messages [{:message/id (:id opened)}]}
                           :episode {:room room-id}
                           :failure failure}})
     :episode-room-id room-id :termination :infrastructure-error :failure failure}))

(defn run!
  "Run one certified episode on the calling (host) thread. Returns
   `{:attempt :episode-room-id :termination :grade :failure}`.

   opts: :experiment-room :store (PRoomStore) :domain :task :definition
   (EnvironmentDef) :agent (candidate AgentDef) :user and :judge (tau2
   generate fns) :limits {:max-steps :max-errors} :timeout-ms :repetition
   :experiment-content-id, and for the reference harness an optional
   `:agent-generate` (tests)."
  [{:keys [experiment-room store domain task definition agent agent-generate user judge
           limits timeout-ms repetition experiment-content-id]
    :or {limits {:max-steps 200 :max-errors 10} timeout-ms (* 30 60 1000)}}]
  (let [started-at (System/currentTimeMillis)
        started-nanos (System/nanoTime)
        room-id (keyword "tau2" (str "ep-" (random-uuid)))
        harness (get-in agent [:agent/metadata :conversation/harness] :dvergr)
        base-metrics {:repetition (or repetition 0)
                      :experiment-content-id experiment-content-id
                      :harness harness}
        {episode-run :run opened :opened}
        (conv/open-episode! experiment-room agent (environment/environment-ref definition) room-id)
        run-id (:run/id episode-run)
        room* (atom nil)]
    (try
      (let [room (d/make-room {:id room-id :store store :parent-id (:id experiment-room)
                               :title (str (:domain domain) " task " (get task "id"))})
            _ (reset! room* room)
            episode {:domain domain :task task :limits limits :room room
                     :state (atom {:seq 0 :steps 0 :errors 0 :log [] :usage {}})
                     :lock (Object.) :ended (promise) :failure (atom nil)}
            ;; A candidate turn that fails, is cancelled, or stops on its
            ;; budget posts no reply; end the episode. The callback runs under
            ;; the Run lifecycle lock and only delivers.
            _ (run/watch-runs! room-id
                               (fn [{:keys [type run]}]
                                 (when (and (= :run/finished type)
                                            (= room-id (:run/room run))
                                            (= :agent (:run/actor run))
                                            (not (ended? episode)))
                                   (case (:run/status run)
                                     :completed nil
                                     :waiting (end! episode :agent-budget)
                                     (end! episode :agent-error)))))
            candidate (case harness
                        :dvergr (dvergr-candidate episode agent)
                        :reference (reference-candidate episode agent agent-generate))]
        (try
          (set-world! room ((:initial-world domain) task))
          (binding [ec/*execution-context* (:ctx room)]
            (d/join room (:participant candidate))
            (d/join room (customer episode user))
            ;; The customer logs the greeting on arrival like every candidate
            ;; message, which counts a step; the greeting is not a tau2 step.
            (swap! (:state episode) update :steps dec)
            (d/post! room (d/message :agent :customer t2/first-agent-message)))
          ((:after-greeting candidate))
          (when (= ::timeout (deref (:ended episode) timeout-ms ::timeout))
            (end! episode :timeout))
          (catch Throwable t (fault! episode :setup t)))
        (run/cancel-room-runs! room-id)
        (let [quiescent? (await-quiescence! room-id 60000)
              termination @(:ended episode)
              [final-world {:keys [log steps errors usage]}]
              (locking (:lock episode) [(world room) @(:state episode)])
              failure (or @(:failure episode)
                          (when-not quiescent?
                            {:source :teardown :message "candidate Runs did not quiesce"}))
              grade (when-not failure
                      (try (t2/grade domain task {:messages (trajectory log) :termination termination
                                                  :world final-world}
                                     {:judge judge})
                           (catch Throwable t (fault! episode :grading t) nil)))
              failure (or failure @(:failure episode))
              status (if (and grade (not failure)) :completed :failed)
              world-hash (when final-world ((:world-hash domain) final-world))
              evidence {:result {:termination termination
                                 :reward-breakdown (:reward-breakdown grade)}
                        :trace {:runs [{:run/id run-id}] :messages [{:message/id (:id opened)}]}
                        :episode {:room room-id
                                  :agent-runs (mapv (juxt :run/id :run/status)
                                                    (run/runs room {:limit 100000}))
                                  :effects (count (filter #(= :tool (:kind %)) log))
                                  :dialogue (count (filter #(= :message (:kind %)) log))}
                        :trajectory log
                        :prompts {:agent-system-sha256 (pj/sha256-hex (agent-system-prompt domain agent))
                                  :user-system-sha256 (pj/sha256-hex (t2/user-system-prompt domain task))}
                        :world {:final-hash world-hash}
                        :grading (select-keys grade [:db-match :action-checks :communicate-checks
                                                     :nl-assertions :note])
                        :failure failure}]
          (conv/finish-episode! run-id status (when failure :infrastructure-error))
          {:attempt (conv/certify!
                     experiment-room definition agent
                     {:run-id run-id :status status :started-at started-at
                      :elapsed-ms (elapsed-ms started-nanos)
                      :checks (if grade (checks-from grade termination) {:terminated-normally false})
                      :reward (if grade (double (:reward grade)) 0.0)
                      :metrics (merge base-metrics
                                      {:steps steps :errors errors :termination termination
                                       :world-hash world-hash
                                       :usage (assoc usage :agent ((:usage candidate)))})
                      :evidence evidence})
           :episode-room-id room-id :termination termination :grade grade :failure failure}))
      (catch Throwable t
        ;; Admission succeeded but the episode could not complete normally:
        ;; still finish the Run and certify a :failed Attempt.
        (certify-fault! experiment-room definition agent run-id opened room-id
                        started-at started-nanos base-metrics t))
      (finally
        (run/unwatch-runs! room-id)
        (when-let [room @room*]
          (try (d/close-room! room)
               (catch Throwable t
                 (tel/log! {:level :warn :id ::episode-room-close-failed
                            :data {:room room-id :error (.getMessage t)}}
                           "episode Room teardown failed"))))))))
