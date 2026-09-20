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
            [org.replikativ.spindel.engine.context :as ectx]
            [dvergr.agent.run :as run]
            [dvergr.benchmarks.tau2.core :as t2]
            [dvergr.benchmarks.tau2.live :as live]
            [dvergr.benchmarks.tau2.pyjson :as pj]
            [dvergr.benchmarks.tau2.schemas :as schemas]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.chat.context :as cc]
            [dvergr.discourse :as d]
            [dvergr.discourse.attention :as attention]
            [dvergr.discourse.generation :as gen]
            [dvergr.discourse.llm :as llm]
            [dvergr.model.providers :as providers]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.ns.doc :as ns-doc]
            [dvergr.tools :as tools]
            [malli.core :as m]
            [malli.error :as me]
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

(deftype CtxAtom [ctx path]
  ;; An atom whose value lives in an execution context's state, so forking
  ;; the context forks the value (copy-on-write) with the tau2 world and the
  ;; candidate's working context. Update fns must be pure: state swaps retry.
  clojure.lang.IDeref
  (deref [_] (binding [ec/*execution-context* ctx] (ec/get-state path)))
  clojure.lang.IAtom
  (swap [_ f] (binding [ec/*execution-context* ctx] (ec/swap-state! path f)))
  (swap [_ f x] (binding [ec/*execution-context* ctx] (ec/swap-state! path #(f % x))))
  (swap [_ f x y] (binding [ec/*execution-context* ctx] (ec/swap-state! path #(f % x y))))
  (swap [_ f x y args] (binding [ec/*execution-context* ctx] (ec/swap-state! path #(apply f % x y args))))
  (compareAndSet [_ old new]
    (binding [ec/*execution-context* ctx]
      (let [set? (volatile! false)]
        (ec/swap-state! path (fn [cur] (if (= cur old) (do (vreset! set? true) new) (do (vreset! set? false) cur))))
        @set?)))
  (reset [_ new] (binding [ec/*execution-context* ctx] (ec/swap-state! path (constantly new)))))

(defn- ctx-atom
  "Episode state cell `k` in `room`'s execution context."
  [room k]
  (->CtxAtom (:ctx room) [::cells k]))

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

(defn- param-type [{:strs [type anyOf items]}]
  (cond
    (= "array" type) (str "array of " (param-type items))
    type type
    anyOf (str/join " | " (map param-type anyOf))
    :else "any"))

(defn- tool-args
  "`[[name type required? description]]` of a tool's parameters, schema order."
  [{:strs [properties required]}]
  (let [req (set required)]
    (for [[k p] properties]
      [k (param-type p) (contains? req k) (get p "description")])))

(defn- tool-call-shape [{:strs [name parameters]}]
  (let [args (tool-args parameters)]
    (str "(tau2/" name (when (seq args)
                         (str " {" (str/join " " (map #(pr-str (first %)) args)) "}"))
         ")")))

(defn- tool-doc
  "Description, one line per argument and, when the domain has curated types,
   the type of the parsed result -- as `doc` and the prompt show it."
  [domain-name {:strs [name description parameters]}]
  (str/join "\n" (concat [(str/trim (str description))]
                          (for [[k t req? d] (tool-args parameters)]
                            (str "  " k " (" t (when-not req? ", optional") ")"
                                 (when d (str ": " d))))
                          (when-let [t (schemas/returns domain-name name)]
                            [(str "  returns (after tau2/parse): " t)]))))

(defn types-doc
  "The domain's named malli types, one per line, or nil."
  [domain-name]
  (when-let [reg (schemas/registry domain-name)]
    (str/join "\n" (for [[k form] (sort-by (comp str key) reg)]
                      (str k " " (pr-str form))))))

(defn tool-signatures
  "The domain tools as Clojure calls with their full descriptions: the same
   information the JSON-tools candidate receives in its tool schemas, plus the
   curated result types."
  ([domain] (tool-signatures domain false))
  ([domain with-types?]
   (str (str/join "\n\n" (for [{:strs [function]} (:tool-schemas domain)]
                             (str (tool-call-shape function) "\n" (tool-doc (:domain domain) function))))
        (if-let [types (and with-types? (types-doc (:domain domain)))]
          (str "\n\nResult types (malli; string keys; `(tau2/check type x)` validates):\n" types)
          (when (types-doc (:domain domain))
            (str "\n\nResult types are malli schemas: `(tau2/types)` lists them, "
                 "`(tau2/check type x)` validates data against one."))))))

(defn data-shape
  "A compact description of parsed JSON data: map keys with their value
   shapes, id-keyed maps (all values of one shape) as `{\"<id>\" shape}` with
   their count, vectors as `[shape]` with their count, scalars as type names."
  ([x] (data-shape x 3))
  ([x depth]
   (cond
     (map? x)
     (let [vs (vals x)
           keyed? (or (and (> (count x) 1) (every? map? vs)
                           (apply = (map (comp set keys) vs)))
                      ;; many keys with one scalar type: a lookup table
                      (and (> (count x) 8) (not-any? coll? vs)
                           (apply = (map type vs))))]
       (cond
         (zero? depth) (str "map of " (count x))
         keyed? {(str "<" (count x) " keys, e.g. " (pr-str (first (keys x))) ">")
                 (data-shape (first vs) (dec depth))}
         :else (into (array-map) (map (fn [[k v]] [k (data-shape v (dec depth))])) x)))
     (sequential? x) (if (or (empty? x) (zero? depth))
                       (str "vector of " (count x))
                       [(str "<" (count x) " items>") (data-shape (first x) (dec depth))])
     (string? x) "string"
     (number? x) "number"
     (boolean? x) "boolean"
     (nil? x) "null"
     :else (str (type x)))))

(defn install-tau2-fns!
  "Domain tools as documented SCI functions `tau2/<tool>` (one map argument,
   the tool's exact text result) plus `tau2/parse` for JSON text,
   `tau2/shape`, and -- with curated types -- `tau2/check` and
   `tau2/types`, so `(clojure.repl/doc tau2/<tool>)` and `(sandbox/doc 'tau2)`
   describe them. `call!` is `(fn [tool-name args] -> text)`."
  [sci-ctx domain call!]
  (let [schemas (:tool-schemas domain)
        domain-name (:domain domain)
        reg (schemas/registry domain-name)
        opts {:registry (merge (m/default-schemas) reg)}
        fns (cond-> (into {'parse (fn [s] (pj/parse s))
                           'shape (fn [x] (data-shape (if (string? x) (pj/parse x) x)))}
                          (map (fn [{:strs [function]}]
                                 (let [tool-name (get function "name")]
                                   [(symbol tool-name)
                                    (fn ([] (call! tool-name {}))
                                      ([args] (call! tool-name args)))])))
                          schemas)
              reg (assoc 'check (fn [type x]
                                  (some-> (m/explain type (if (string? x) (pj/parse x) x) opts)
                                          me/humanize))
                         'types (fn [] reg)))
        docs (cond-> (into {'parse ['([json-text])
                                    "Parse a tool's JSON text result into Clojure data (string keys)."]
                            'shape ['([data-or-json-text])
                                    "Compact structure of parsed data: keys and value types; maps keyed by ids show one sample value and the count."]}
                           (map (fn [{:strs [function]}]
                                  (let [args (tool-args (get function "parameters"))]
                                    [(symbol (get function "name"))
                                     [(list (if (seq args)
                                              [{:strs (mapv (comp symbol first) args)}]
                                              []))
                                      (tool-doc domain-name function)]])))
                           schemas)
               reg (assoc 'check ['([type data-or-json-text])
                                  "nil when the data matches the named result type (e.g. :tau2.retail/order), else the humanized errors."]
                          'types ['([]) "The domain's named result types: {type malli-form}."]))]
    (sandbox/add-namespace! sci-ctx 'tau2 (ns-doc/with-docs fns docs))))

(defn- install-tau2-namespace! [sci-ctx episode]
  (install-tau2-fns! sci-ctx (:domain episode)
                     (fn [tool-name args] (effect! episode :assistant tool-name args))))

(def ^:private repl-guidance-head
  (str "\n\n<tools>\nYou act through the `clojure_eval` tool, a Clojure (SCI) REPL; "
       "definitions persist across evaluations. The customer-service tools are "
       "functions in namespace `tau2`, each taking one map with string keys and "
       "returning the tool's text result exactly. `(tau2/parse s)` turns a JSON "
       "result into Clojure data (string keys). "))

(def ^:private repl-guidance-tail
  (str "Several read calls can run in one evaluation. Write actions "
       "(modify/cancel/exchange/return/transfer and similar) take effect "
       "immediately: only call them after explicit user confirmation, exactly "
       "once. `(clojure.repl/doc tau2/<tool>)` and `(sandbox/doc 'tau2)` show "
       "these docs at runtime.\n\n%s\n</tools>"))

(def repl-guidance
  "Variants of the REPL action-space guidance, selected by the candidate's
   `:conversation/repl-guidance` (default :shape)."
  {:compute
   (str repl-guidance-head
        "Whenever an answer depends on counting, filtering, comparing or summing "
        "(available variants, totals, prices), compute it in code over the parsed "
        "data instead of reading JSON by eye. " repl-guidance-tail)
   :inspect
   (str repl-guidance-head
        "Use it to select exactly what you need, but a computed value only shows "
        "what you asked for: first look at the records themselves (their keys and "
        "the fields that decide the answer, such as availability, status, options "
        "and prices), then filter, count, compare or sum in code over the parsed "
        "data rather than by eye, checking those deciding fields explicitly. "
        repl-guidance-tail)
   :shape
   (str repl-guidance-head
        "`(tau2/shape x)` shows the structure of a result (x parsed or JSON "
        "text): which values are maps keyed by ids (iterate with `vals`), which "
        "are vectors, and the keys of their elements. Check the shape before "
        "computing over data, then filter, count, compare or sum in code rather "
        "than by eye, checking the fields that decide the answer (availability, "
        "status, options, prices) explicitly. " repl-guidance-tail)})

(def ^:private with-types-section?
  "Guidance variants whose prompt includes the full result-type section."
  #{:typed})

(defn agent-system-prompt
  "The system prompt a candidate receives (recorded as a hash in evidence)."
  [domain agent]
  (let [base (t2/agent-system-prompt domain)
        g (get-in agent [:agent/metadata :conversation/repl-guidance] :shape)]
    (if (= :repl (get-in agent [:agent/metadata :conversation/action-space]))
      (str base (format (repl-guidance (if (= :typed g) :shape g))
                        (tool-signatures domain (contains? with-types-section? g))))
      base)))

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
        model-steps (ctx-atom room :model-steps)
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
                      (if (> (swap! model-steps (fnil inc 0)) (or max-model-steps 100))
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
                      (select-keys [:used :by-type])))
     ;; The harness's own view, as portable data: every model message with
     ;; its tool uses (e.g. the clojure_eval code) and each tool result. The
     ;; system prompt is recorded by hash, not repeated here.
     :transcript (fn []
                   (->> (cc/get-messages (room-context/ensure-ctx! room :agent ctx-opts))
                        (remove #(= :system (:message/role %)))
                        (mapv (fn [m]
                                (cond-> {:role (:message/role m)
                                         :content (str (:message/content m))}
                                  (:message/tool-use-id m) (assoc :tool-use-id (str (:message/tool-use-id m)))
                                  (seq (:message/tool-uses m))
                                  (assoc :tool-uses
                                         (mapv (fn [tu] {:id (str (:tool-use/id tu))
                                                         :name (:tool-use/name tu)
                                                         :input (pr-str (dissoc (:tool-use/input tu) :db/id))})
                                               (:message/tool-uses m))))))))}))

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
        history (doto (ctx-atom room :agent-history)
                  (swap! #(or % [{:role :assistant :content t2/first-agent-message}])))]
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
     :usage (fn [] (get-in @(:state episode) [:usage :agent]))
     ;; The reference loop has no state beyond the graded log.
     :transcript (fn [] nil)}))

(defn- customer-message-count [episode]
  (count (filter #(and (= :message (:kind %)) (= :user (:role %))) (:log @(:state episode)))))

(defn- customer
  "The simulated user: role-flipped history of the dialogue, its own tools
   through the effect interpreter, stop tokens end the episode."
  [episode user-generate]
  (let [{:keys [domain room task]} episode
        system (t2/user-system-prompt domain task)
        user-tools ((:user-tool-schemas domain) task)
        history (doto (ctx-atom room :customer-history) (swap! #(or % [])))
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
                       (cond
                         (bound-exceeded! episode)
                         (do (d/post! room (d/message :customer :_activity reply nil
                                                      {:role :user :kind :tau2/after-bound}))
                             nil)

                         ;; Checkpoint: hold the k-th customer message back.
                         ;; The candidate is idle, so the Room is a clean fork
                         ;; point; each branch delivers it.
                         (= (:checkpoint-at episode) (customer-message-count episode))
                         (do (swap! (:state episode) assoc :pending {:content reply})
                             (end! episode :checkpoint)
                             nil)

                         :else
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

(defn- new-episode [domain task limits room checkpoint-at]
  {:domain domain :task task :limits limits :room room
   :state (ctx-atom room :state)
   :lock (Object.) :ended (promise) :failure (atom nil) :turns (atom [])
   :checkpoint-at checkpoint-at})

(defn- watch-candidate-runs!
  "A candidate turn that fails, is cancelled, or stops on its budget posts no
   reply; end the episode. The callback runs under the Run lifecycle lock and
   only delivers. Finished candidate Runs are also collected in `:turns`: a
   forked world has no store to list them from."
  [episode]
  (let [room-id (:id (:room episode))]
    (run/watch-runs! room-id
                     (fn [{:keys [type run]}]
                       (when (and (= :run/finished type)
                                  (= room-id (:run/room run))
                                  (= :agent (:run/actor run)))
                         (swap! (:turns episode) conj [(:run/id run) (:run/status run)]))
                       (when (and (= :run/finished type)
                                  (= room-id (:run/room run))
                                  (= :agent (:run/actor run))
                                  (not (ended? episode)))
                         (case (:run/status run)
                           :completed nil
                           :waiting (end! episode :agent-budget)
                           (end! episode :agent-error)))))))

(defn- attach!
  "Join the candidate and the customer. Returns the candidate."
  [episode agent agent-generate user]
  (let [room (:room episode)
        candidate (case (get-in agent [:agent/metadata :conversation/harness] :dvergr)
                    :dvergr (dvergr-candidate episode agent)
                    :reference (reference-candidate episode agent agent-generate))]
    (binding [ec/*execution-context* (:ctx room)]
      (d/join room (:participant candidate))
      (d/join room (customer episode user)))
    candidate))

(defn- conclude!
  "Cancel candidate Runs once the episode ended, grade, finish the episode
   Run and certify its Attempt."
  [{:keys [experiment-room domain task definition agent judge run-id opened
           started-at started-nanos base-metrics evidence-extra]}
   episode candidate]
  (let [{:keys [room]} episode
        room-id (:id room)]
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
          evidence (merge
                    {:result {:termination termination
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
                                                  :nl-assertions :env-assertion-checks :note])
                     :failure failure}
                    evidence-extra)]
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
       :episode-room-id room-id :termination termination :grade grade :failure failure})))

(defn- close-quietly! [room]
  (when room
    (try (d/close-room! room)
         (catch Throwable t
           (tel/log! {:level :warn :id ::episode-room-close-failed
                      :data {:room (:id room) :error (.getMessage t)}}
                     "episode Room teardown failed")))))

(defn episode-snapshot
  "The tau2 world and episode state of `room`, read under its context:
   `{:world :log :steps :errors :usage}`."
  [room]
  (let [st @(ctx-atom room :state)]
    (assoc (select-keys st [:log :steps :errors :usage])
           :world (world room))))

(defn prepare-world!
  "Install `task`'s initial world in `room` (the trusted world setup)."
  [room domain task]
  (set-world! room ((:initial-world domain) task))
  nil)

(defn converse!
  "Run one tau2 conversation to its end inside `room`, whose world was
   prepared with `prepare-world!`: join the candidate and the simulated
   customer, deliver the greeting, wait for termination (or `cancelled?`),
   then cancel and await candidate Runs and leave the Room quiescent.

   Blocking host work. Returns portable data:
   `{:termination :steps :errors :usage :failure :agent-runs :transcript}`; the graded log
   and final world stay in the Room's context (`episode-snapshot`). An
   infrastructure fault is returned under `:failure`, never thrown, so the
   caller decides how to certify it."
  [{:keys [room domain task agent agent-generate user limits timeout-ms cancelled?]
    :or {limits {:max-steps 200 :max-errors 10} timeout-ms (* 30 60 1000)
         cancelled? (constantly false)}}]
  (let [room-id (:id room)
        episode (new-episode domain task limits room nil)
        _ (reset! (:state episode) {:seq 0 :steps 0 :errors 0 :log [] :usage {}})
        _ (watch-candidate-runs! episode)
        candidate* (volatile! nil)]
    (try
      (try
        (let [candidate (attach! episode agent agent-generate user)]
          (vreset! candidate* candidate)
          (binding [ec/*execution-context* (:ctx room)]
            ;; The customer logs the greeting on arrival like every candidate
            ;; message, which counts a step; the greeting is not a tau2 step.
            (swap! (:state episode) update :steps dec)
            (d/post! room (d/message :agent :customer t2/first-agent-message)))
          ((:after-greeting candidate))
          ;; `:timeout-ms nil`: the caller bounds the conversation itself.
          (let [deadline (when timeout-ms (+ (System/currentTimeMillis) timeout-ms))]
            (loop []
              (cond
                (ended? episode) nil
                (cancelled?) (end! episode :cancelled)
                (and deadline (> (System/currentTimeMillis) deadline)) (end! episode :timeout)
                :else (do (deref (:ended episode) 100 nil) (recur))))))
        (catch InterruptedException e (end! episode :cancelled) (throw e))
        (catch Throwable t (fault! episode :setup t)))
      (run/cancel-room-runs! room-id)
      (let [quiescent? (await-quiescence! room-id 60000)
            {:keys [steps errors usage]} @(:state episode)
            failure (or @(:failure episode)
                        (when-not quiescent?
                          {:source :teardown :message "candidate Runs did not quiesce"}))]
        {:termination @(:ended episode)
         :steps steps :errors errors
         :usage (cond-> usage
                  @candidate* (assoc :agent ((:usage @candidate*))))
         :agent-runs @(:turns episode)
         :transcript (when @candidate* ((:transcript @candidate*)))
         :failure failure})
      (finally
        (run/unwatch-runs! room-id)
        ;; Settlement requires a world with no participants.
        (doseq [id [:agent :customer]]
          (try (d/leave room id) (catch Throwable _ nil)))))))

(defn run!
  "Run one certified episode on the calling (host) thread. Returns
   `{:attempt :episode-room-id :termination :grade :failure}`.

   opts: :experiment-room :store (PRoomStore) :domain :task :definition
   (EnvironmentDef) :agent (candidate AgentDef) :user and :judge (tau2
   generate fns) :limits {:max-steps :max-errors} :timeout-ms :repetition
   :experiment-content-id, and for the reference harness an optional
   `:agent-generate` (tests).

   With `:checkpoint-at k` the episode stops before the k-th customer message
   reaches the candidate and returns `{:checkpoint cp}` instead: the Room
   stays open as a fork point for `branch!` (release it with
   `release-checkpoint!`); nothing is graded or certified."
  [{:keys [experiment-room store domain task definition agent agent-generate user judge
           limits timeout-ms repetition experiment-content-id checkpoint-at]
    :or {limits {:max-steps 200 :max-errors 10} timeout-ms (* 30 60 1000)}
    :as opts}]
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
        room* (atom nil)
        keep-room? (volatile! false)]
    (try
      (let [room (d/make-room {:id room-id :store store :parent-id (:id experiment-room)
                               :title (str (:domain domain) " task " (get task "id"))})
            _ (reset! room* room)
            episode (new-episode domain task limits room checkpoint-at)
            _ (reset! (:state episode) {:seq 0 :steps 0 :errors 0 :log [] :usage {}})
            _ (watch-candidate-runs! episode)
            candidate (attach! episode agent agent-generate user)]
        (try
          (set-world! room ((:initial-world domain) task))
          (binding [ec/*execution-context* (:ctx room)]
            ;; The customer logs the greeting on arrival like every candidate
            ;; message, which counts a step; the greeting is not a tau2 step.
            (swap! (:state episode) update :steps dec)
            (d/post! room (d/message :agent :customer t2/first-agent-message)))
          ((:after-greeting candidate))
          (when (= ::timeout (deref (:ended episode) timeout-ms ::timeout))
            (end! episode :timeout))
          (catch Throwable t (fault! episode :setup t)))
        (if (and checkpoint-at (= :checkpoint @(:ended episode)) (not @(:failure episode)))
          (do (conv/finish-episode! run-id :cancelled :checkpoint)
              (vreset! keep-room? true)
              {:checkpoint (merge (select-keys opts [:experiment-room :store :domain :task :definition
                                                     :agent :agent-generate :user :judge :timeout-ms
                                                     :repetition :experiment-content-id])
                                  {:room room :limits limits :at checkpoint-at
                                   :pending (:pending @(:state episode))
                                   :log (:log @(:state episode))})
               :episode-room-id room-id :termination :checkpoint})
          (conclude! {:experiment-room experiment-room :domain domain :task task
                      :definition definition :agent agent :judge judge :run-id run-id
                      :opened opened :started-at started-at :started-nanos started-nanos
                      :base-metrics base-metrics}
                     episode candidate)))
      (catch Throwable t
        ;; Admission succeeded but the episode could not complete normally:
        ;; still finish the Run and certify a :failed Attempt.
        (certify-fault! experiment-room definition agent run-id opened room-id
                        started-at started-nanos base-metrics t))
      (finally
        (run/unwatch-runs! room-id)
        (when-not @keep-room? (close-quietly! @room*))))))

(defn branch!
  "Continue checkpoint `cp` (from `run!` with `:checkpoint-at`) as a new
   certified episode in a copy-on-write fork of its world: the tau2 world,
   the dialogue and episode log so far, the customer's history and the
   candidate's working context (chat history and REPL heap) are the fork's,
   so branches never see each other or change the checkpoint. The withheld
   customer message is delivered and the episode runs to its end.

   `opts` may override :user and :judge (e.g. another simulator), and
   :agent-generate for the reference harness; the candidate AgentDef stays
   the checkpoint's. The Attempt records `{:branch {:checkpoint-room :at}}`."
  ([cp] (branch! cp {}))
  ([cp opts]
   (let [{:keys [experiment-room store domain task definition agent agent-generate user judge
                 limits timeout-ms repetition experiment-content-id room at pending]
          :or {timeout-ms (* 30 60 1000)}} (merge cp opts)
         parent room
         started-at (System/currentTimeMillis)
         started-nanos (System/nanoTime)
         room-id (keyword "tau2" (str "br-" (random-uuid)))
         harness (get-in agent [:agent/metadata :conversation/harness] :dvergr)
         branch-info {:checkpoint-room (:id parent) :at at}
         base-metrics {:repetition (or repetition 0)
                       :experiment-content-id experiment-content-id
                       :harness harness
                       :branch branch-info}
         {episode-run :run opened :opened}
         (conv/open-episode! experiment-room agent (environment/environment-ref definition) room-id)
         run-id (:run/id episode-run)
         room* (atom nil)]
     (try
       (let [child-ctx (ectx/fork-context (:ctx parent) :mode :frozen)
             room (d/make-room {:id room-id :store store :parent-id (:id experiment-room)
                                :ctx child-ctx
                                :title (str (:domain domain) " task " (get task "id") " branch at " at)})
             _ (reset! room* room)
             ;; The candidate's working context (chat history + SCI heap) forks
             ;; with the world; project it into the branch before the
             ;; candidate participant is built.
             _ (when (= :dvergr harness) (room-context/fork-ctx! parent room :agent))
             episode (new-episode domain task limits room nil)
             _ (watch-candidate-runs! episode)
             candidate (attach! episode agent agent-generate user)]
         (try
           ;; Rebinds the branch's tools (the forked heap's tau2/* still
           ;; point at the checkpoint's world) before the message arrives.
           ((:after-greeting candidate))
           (binding [ec/*execution-context* (:ctx room)]
             (d/post! room (d/message :customer :agent (:content pending) nil {:role :user})))
           (when (= ::timeout (deref (:ended episode) timeout-ms ::timeout))
             (end! episode :timeout))
           (catch Throwable t (fault! episode :setup t)))
         (conclude! {:experiment-room experiment-room :domain domain :task task
                     :definition definition :agent agent :judge judge :run-id run-id
                     :opened opened :started-at started-at :started-nanos started-nanos
                     :base-metrics base-metrics :evidence-extra {:branch branch-info}}
                    episode candidate))
       (catch Throwable t
         (certify-fault! experiment-room definition agent run-id opened room-id
                         started-at started-nanos base-metrics t))
       (finally
         (run/unwatch-runs! room-id)
         (close-quietly! @room*))))))

(defn release-checkpoint!
  "Close a checkpoint's Room once no more branches will be taken."
  [cp]
  (close-quietly! (:room cp)))
