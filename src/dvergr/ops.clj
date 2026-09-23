(ns dvergr.ops
  "Central operations specification — ONE data map of dvergr's surface operations
   (rooms, agents, forks), from which the bindings are DERIVED rather than
   hand-rolled. Modeled on `datahike.api.specification`: the spec is data, and
   `spec->…` projections render it onto a binding (MCP today; the TUI/web/Telegram
   field-specs, command grammars, and form-parsers over time).

   Each entry is `{:doc :kind :schema :impl}`:
     :kind   — :read (a resource — what a UI renders) or :write (a mutation/tool)
     :schema — MALLI schema for the args (a `[:map …]`). The single source: MCP
               projects it to a JSON-Schema inputSchema via `malli.json-schema`
               (`input-schema`), and the HTTP/JSON API (`dvergr.web.api`) uses it
               directly for request coercion + the OpenAPI doc.
     :impl   — `(fn [daemon args] -> data)`. It binds the daemon execution
               context, resolves handles (room id/slug → Room), calls the
               ALREADY-shared op (`dvergr.agent.ops` / `dvergr.rooms` /
               `dvergr.rooms.forks` / `dvergr.discourse`), and returns plain data.
               The shared fns stay the logic; this is the normalized adapter every
               surface shares — so a new op is added once and appears, named
               identically, everywhere.

   Coding tools (`clojure_eval`, `knowledge_*`, file tools, …) are NOT specified
   here — they are the `dvergr.tools` registry, re-served by their own names.

   Op key → binding name: `:room/post` → `room_post` (MCP tool), etc."
  (:require [clojure.string :as str]
            [malli.json-schema :as mjs]
            [dvergr.agent.episode :as attempts]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.ops :as aops]
            [dvergr.agent.run :as run]
            [dvergr.agent.workflow :as workflow]
            [dvergr.jobs :as jobs]
            [dvergr.resource :as resource]
            [dvergr.agent.fields :as fields]
            [dvergr.rooms :as rooms]
            [dvergr.rooms.forks :as forks]
            [dvergr.rooms.stats :as stats]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store :as rstore]
            [dvergr.discourse :as d]
            [dvergr.model.registry :as reg]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.core :as spin-core]
            [org.replikativ.spindel.yggdrasil :as ygg]))

;; ============================================================================
;; Context + handle resolution
;; ============================================================================

(defn- slugify [s]
  (-> (str s) str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"(^-+|-+$)" "")))

(defn- dctx [daemon] (:execution-ctx daemon))

(defmacro ^:private in-ctx [daemon & body]
  `(binding [ec/*execution-context* (dctx ~daemon)] ~@body))

(defn- id->str
  "Room/actor ids are keywords (built-ins) OR UUIDs (created rooms) — both → string."
  [id]
  (cond (keyword? id) (name id) (some? id) (str id) :else nil))

(defn resolve-room
  "`room` arg → a live Room. Accepts a Room, a room-id (keyword/UUID/string), or a slug.
   Public so bindings (e.g. the MCP resource-subscription layer) can resolve handles."
  [daemon room]
  (in-ctx daemon
          (cond
            (or (nil? room) (record? room)) room
            :else
            (or (rreg/lookup room)                         ; the value as-is (keyword/UUID)
                (when (string? room)
                  (or (rreg/lookup (keyword room))
                      (try (rreg/lookup (java.util.UUID/fromString room)) (catch Throwable _ nil))
                      (some-> (rstore/slug->room-id room) rreg/lookup)))))))

;; ---- plain-data projections (serializable for any binding) ----

(defn- room-data [r]
  (when r
    {:id           (id->str (:id r))
     :title        (or (some-> r :meta deref :title) (id->str (:id r)))
     :parent       (id->str (some-> r :meta deref :conversation-id))
     :participants (try (mapv name (keys @(:participants r))) (catch Throwable _ []))}))

(defn- room-result
  "Project a Room OR a room-id (what create-room!/fork! return) to room-data."
  [daemon x]
  (in-ctx daemon (room-data (if (record? x) x (rreg/lookup x)))))

(defn- kw->str
  "A namespaced keyword in full (`:bfcl.simple_python/task-3`), a bare one by name."
  [k]
  (cond (keyword? k) (if (namespace k) (str (namespace k) "/" (name k)) (name k))
        (some? k) (str k)
        :else nil))

(defn- uuid-arg [x]
  (cond (uuid? x) x
        (string? x) (try (java.util.UUID/fromString x) (catch Throwable _ nil))
        :else nil))

;; ---- the evaluation read model: what the MCP tool and the web view share ----

(defn- spend-data [spend]
  (when spend
    {:microdollars (:microdollars spend 0)
     :dollars      (/ (double (:microdollars spend 0)) 1e6)
     :priced?      (:priced? spend true)
     :tokens       (:tokens spend {})
     :by-model     (into {} (map (fn [[m sp]] [(str m) {:microdollars (:microdollars sp 0)
                                                        :tokens (:tokens sp {})}]))
                         (:by-model spend {}))}))

(defn- run-data [r]
  (when r
    {:id             (some-> (:run/id r) str)
     :parent         (some-> (:run/parent r) str)
     :actor          (id->str (:run/actor r))
     :kind           (some-> (:run/kind r) name)
     :status         (some-> (:run/status r) name)
     :reason         (some-> (:run/reason r) name)
     :error          (:run/error r)
     :settlement     (some-> (:run/settlement-status r) name)
     :world          (some-> (:run/world r) str)
     :program-kind   (some-> (:run/program-kind r) name)
     :created-at     (:run/created-at r)
     :started-at     (:run/started-at r)
     :ended-at       (:run/ended-at r)}))

(defn- attempt-data
  "One certified Attempt as a leaderboard row: who, on what, reward, checks,
   the bill. With `:full?`, also the evidence (transcripts, calls, traces)."
  [a & [{:keys [full?]}]]
  (when a
    (let [receipt (:attempt/receipt a)
          env (:attempt/environment a)
          metrics (:attempt/metrics receipt)]
      (cond-> {:id           (some-> (:attempt/id a) str)
               :content-id   (some-> (:attempt/content-id a) str)
               :run-id       (some-> (:attempt/run-id a) str)
               :agent        (kw->str (get-in a [:attempt/agent :agent/id]))
               :agent-hash   (some-> (:attempt/agent-def-hash a) str)
               :environment  {:id (kw->str (:environment/id env))
                              :content-id (some-> (:environment/content-id env) str)
                              :task (:environment/task env)}
               :provider     (id->str (:attempt/provider receipt))
               :model        (:attempt/model receipt)
               :status       (some-> (:attempt/status receipt) name)
               :started-at   (:attempt/started-at receipt)
               :elapsed-ms   (:attempt/elapsed-ms receipt)
               :reward       (:attempt/reward receipt)
               :checks       (:attempt/checks receipt)
               :verifier-trust (some-> (:verifier-trust metrics) name)
               :spend        (spend-data (:spend metrics))
               :metrics      (dissoc metrics :spend)
               :certified-at (:attempt/certified-at a)}
        full? (assoc :evidence (:attempt/evidence a)
                     :result (:attempt/result receipt))))))

(defn- scorecard-data
  "A Scorecard as a leaderboard: one row per candidate, ranked by reward mean
   then by cost per pass. With `:full?`, also every cell."
  [sc & [{:keys [full?]}]]
  (when sc
    (let [exp (:scorecard/experiment sc)
          rows (->> (:scorecard/summary sc)
                    (map (fn [s]
                           {:candidate       (kw->str (:candidate/id s))
                            :attempts        (:attempt-count s)
                            :passed          (:passed-count s)
                            :pass-rate       (when (pos? (:attempt-count s 0))
                                               (/ (double (:passed-count s)) (:attempt-count s)))
                            :reward-mean     (:reward-mean s)
                            :spend           (spend-data (:spend s))
                            :microdollars-per-attempt (:microdollars-per-attempt s)
                            :microdollars-per-pass    (:microdollars-per-pass s)}))
                    (sort-by (juxt #(- (or (:reward-mean %) 0))
                                   #(or (:microdollars-per-pass %) Long/MAX_VALUE)))
                    vec)]
      (cond-> {:id             (some-> (:scorecard/content-id sc) str)
               :experiment     {:id (kw->str (:experiment/id exp))
                                :version (:experiment/version exp)
                                :content-id (some-> (:experiment/content-id exp) str)
                                :candidates (mapv #(kw->str (:candidate/id %)) (:experiment/candidates exp))
                                :environments (count (get-in exp [:experiment/dataset :dataset/environments]))
                                :repetitions (:experiment/repetitions exp)}
               :leaderboard    rows
               :cells          (count (:scorecard/entries sc))}
        full? (assoc :entries (mapv (fn [e]
                                      {:candidate (kw->str (:candidate/id e))
                                       :environment (some-> (get-in e [:environment :environment/content-id]) str)
                                       :repetition (:repetition e)
                                       :attempt-id (some-> (:attempt/id e) str)
                                       :reward (:reward e)
                                       :passed? (:passed? e)
                                       :spend (spend-data (:spend e))})
                                    (:scorecard/entries sc)))))))

(defn- msg-data [m]
  {:id             (some-> (:id m) str)
   :from           (some-> (:from m) name)
   :to             (some-> (:to m) name)
   :role           (:role m)
   :ts             (:ts m)
   :in-reply-to    (some-> (:in-reply-to m) str)
   :thread-root-id (some-> (d/thread-root-id m) str)
   :content        (:content m)})

;; ============================================================================
;; Reusable malli arg fragments — referenced from the specification below.
;; ============================================================================

(def ^:private Room    [:string {:description "room id or slug"}])
(def ^:private Fork    [:string {:description "fork room id/slug"}])

(def ^:private WorkflowArgs
  [:map [:room Room]
   [:task [:string {:description "what the agent should do"}]]
   [:attempts {:optional true} [:int {:min 1 :max 8 :description "attempts per model (default 1)"}]]
   [:models {:optional true} [:vector {:description "model ids or aliases (default: the configured default)"} :string]]
   [:budget-dollars {:optional true} [:double {:description "budget per attempt in USD (default 0.50)"}]]
   [:profile {:optional true} [:enum {:description "developer = may edit files; worker = read-only (default)"} "developer" "worker"]]
   [:timeout-ms {:optional true} [:int {:description "per attempt (default 10 minutes)"}]]])

(defn- wallet-data
  "A room's (or one Run's) conserved resources as plain data."
  [room run-id]
  (let [bal (try (if run-id
                   (resource/run-balance room run-id)
                   (resource/balance room))
                 (catch Throwable e {::unavailable (ex-message e)}))]
    (if-let [why (::unavailable bal)]
      {:available false :reason why}
      {:available true
       :resources (into (sorted-map)
                        (map (fn [[k v]] [(str k) (if (number? v) (double v) v)]))
                        bal)})))

(defn- job-data [job]
  (-> job
      (update :status name)
      (update :started-at #(some-> % java.util.Date.))
      (update :finished-at #(some-> % java.util.Date.))))

(defn- workflow-result
  "A workflow's rows as the data every binding shows: the attempts with their
   worlds and reviews, the per-model table, and the room's wallet after."
  [daemon room task rows]
  (let [attempts (mapv (fn [{:keys [attempt world review]}]
                         (assoc (attempt-data attempt)
                                :world (id->str world)
                                :review review))
                       rows)]
    {:task task
     :attempts attempts
     :by-model
     (->> attempts
          (group-by :model)
          (mapv (fn [[model xs]]
                  (let [md (reduce + 0 (keep #(get-in % [:spend :microdollars]) xs))
                        ok (count (filter #(= 1.0 (:reward %)) xs))]
                    {:model model :attempts (count xs) :completed ok
                     :microdollars md
                     :microdollars-per-completion (when (pos? ok) (quot md ok))})))
          (sort-by (juxt #(- (:completed %)) :microdollars))
          vec)
     :wallet (in-ctx daemon (wallet-data room nil))
     :adopt (str "room_merge {room: <world>, expect-state: <review.state>} adopts one attempt; "
                 "room_discard {room: <world>} drops one")}))
(def ^:private AgentId [:string {:description "agent id"}])

;; ============================================================================
;; The specification
;; ============================================================================

(def specification
  {;; ---- reads (resources) ----
   :room/list
   {:doc "List all rooms (id, title, participants)."
    :kind :read
    :schema [:map]
    :impl (fn [daemon _] (in-ctx daemon (mapv room-data (rreg/list-rooms))))}

   :room/messages
   {:doc "Recent messages in a room — the conversation history."
    :kind :read
    :schema [:map [:room Room]
             [:limit {:optional true} [:int {:description "max messages (default 50)"}]]]
    :impl (fn [daemon {:keys [room limit]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon (mapv msg-data (d/messages r {:limit (or limit 50)})))))}

   ;; ---- the evaluation read model ----
   :run/list
   {:doc "Durable Runs of a room, newest first: status, settlement, actor, lineage."
    :kind :read
    :schema [:map [:room Room]
             [:limit {:optional true} [:int {:description "max runs (default 50)"}]]
             [:status {:optional true} [:string {:description "running | completed | failed | cancelled"}]]
             [:root {:optional true} [:string {:description "a root run id: that Run and its descendants"}]]]
    :impl (fn [daemon {:keys [room limit status root]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon
                      (mapv run-data
                            (run/runs r (cond-> {:limit (or limit 50)}
                                          status (assoc :status (keyword status))
                                          root (assoc :root-run-id (uuid-arg root))))))))}

   :run/detail
   {:doc "One durable Run: status, settlement, error, world, timestamps."
    :kind :read
    :schema [:map [:room Room] [:id [:string {:description "run id"}]]]
    :impl (fn [daemon {:keys [room id]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon (run-data (run/run r (uuid-arg id))))))}

   :room/wallet
   {:doc "Conserved resources of a room's wallet, or of one Run's wallet: what is left to spend."
    :kind :read
    :schema [:map [:room Room]
             [:run {:optional true} [:string {:description "a run id: that Run's wallet"}]]]
    :impl (fn [daemon {:keys [room run]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon (wallet-data r (some-> run uuid-arg)))))}

   :job/status
   {:doc (str "A job's status, and its result once completed. `wait-ms` (at most 25000) "
              "waits that long for it to finish before answering.")
    :kind :read
    :schema [:map [:job [:string {:description "job id"}]]
             [:wait-ms {:optional true} [:int {:min 0 :max 25000 :description "wait up to this long (default 0)"}]]]
    :impl (fn [_daemon {:keys [job wait-ms]}]
            (or (some-> (jobs/status job (or wait-ms 0)) job-data)
                (throw (ex-info (str "No job " job " (jobs live in the daemon; a restart drops them)")
                                {:type ::no-job}))))}

   :job/list
   {:doc "Jobs, newest first (running and recently finished), optionally of one room; results omitted."
    :kind :read
    :schema [:map [:room {:optional true} Room]]
    :impl (fn [daemon {:keys [room]}]
            (let [rid (when room (some-> (resolve-room daemon room) :id id->str))]
              (mapv #(dissoc (job-data %) :result) (jobs/jobs rid))))}

   :attempt/list
   {:doc "Certified Attempts of a room, newest first — each a leaderboard row: agent, environment, reward, checks, the bill."
    :kind :read
    :schema [:map [:room Room]
             [:limit {:optional true} [:int {:description "max attempts (default 50)"}]]
             [:environment {:optional true} [:string {:description "environment id"}]]
             [:model {:optional true} [:string {:description "model id"}]]
             [:status {:optional true} [:string {:description "completed | failed | cancelled"}]]]
    :impl (fn [daemon {:keys [room limit environment model status]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon
                      (mapv attempt-data
                            (attempts/attempts r (cond-> {:limit (or limit 50)}
                                                   environment (assoc :environment-id (keyword environment))
                                                   model (assoc :model model)
                                                   status (assoc :status (keyword status))))))))}

   :attempt/detail
   {:doc "One certified Attempt with its evidence: transcript, calls, traces, result."
    :kind :read
    :schema [:map [:room Room] [:id [:string {:description "attempt id"}]]]
    :impl (fn [daemon {:keys [room id]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon
                      (attempt-data (some-> (:store r)
                                            (rstore/-load-attempt (:id r) (uuid-arg id)))
                                    {:full? true}))))}

   :scorecard/list
   {:doc "Completed Scorecards of a room, newest first, each with its leaderboard (candidates ranked by reward, then cost per pass)."
    :kind :read
    :schema [:map [:room Room]
             [:limit {:optional true} [:int {:description "max scorecards (default 20)"}]]
             [:experiment {:optional true} [:string {:description "experiment id"}]]]
    :impl (fn [daemon {:keys [room limit experiment]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon
                      (mapv scorecard-data
                            (experiment/scorecards r (cond-> {:limit (or limit 20)}
                                                       experiment (assoc :experiment-id (keyword experiment))))))))}

   :scorecard/detail
   {:doc "One Scorecard with every cell: candidate x environment x repetition, reward, pass, spend."
    :kind :read
    :schema [:map [:room Room] [:id [:string {:description "scorecard content id"}]]]
    :impl (fn [daemon {:keys [room id]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon (scorecard-data (experiment/scorecard r (uuid-arg id)) {:full? true}))))}

   :agent/list
   {:doc "List all agents (durable — includes offline), with model/provider/status."
    :kind :read
    :schema [:map]
    :impl (fn [daemon _] (in-ctx daemon (vec (aops/list-agents))))}

   :agent/config
   {:doc "An agent's full configuration (model, provider, tags, prompt source)."
    :kind :read
    :schema [:map [:id AgentId]]
    :impl (fn [daemon {:keys [id]}] (in-ctx daemon (aops/get-agent (keyword id))))}

   :room/stats
   {:doc "A room's stats — message count, cost, agents, fork count, last-active."
    :kind :read
    :schema [:map [:room Room]]
    :impl (fn [daemon {:keys [room]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon (stats/room-stats r))))}

   :room/detail
   {:doc "A room's identity + lineage — title, parent, fork status, participants."
    :kind :read
    :schema [:map [:room Room]]
    :impl (fn [daemon {:keys [room]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon
                      {:id           (id->str (:id r))
                       :slug         (:slug r)
                       :title        (or (:title r) (name (:id r)))
                       :parent-id    (some-> (:parent-id r) id->str)
                       :fork?        (forks/fork? r)
                       :forked-from  (some-> (:meta r) deref :forked-from id->str)
                       :participants (->> (some-> (:participants r) deref keys)
                                          (remove #(str/starts-with? (name %) "_"))
                                          (mapv id->str))})))}

   :room/diff
   {:doc "The diff of a fork against its parent (what reconcile-merge would apply)."
    :kind :read
    :schema [:map [:room Fork]]
    :impl (fn [daemon {:keys [room]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon (forks/fork-diff r))))}

   :models/list
   {:doc "Available LLM models (for agent configuration): id, name, provider, context."
    :kind :read
    :schema [:map]
    :impl (fn [daemon _]
            (in-ctx daemon
                    (mapv #(select-keys % [:id :name :provider :context]) (reg/list-models))))}

   :system/stats
   {:doc "Whole-system rollup — room count, message count, agents online, total cost."
    :kind :read
    :schema [:map]
    :impl (fn [daemon _]
            (in-ctx daemon (stats/system-stats)))}

   ;; ---- writes (tools) ----
   :room/post
   {:doc "Post a message into a room — participate in the chat. The room's agents react."
    :kind :write
    :schema [:map [:room Room]
             [:text [:string {:description "message text"}]]
             [:as {:optional true}
              [:string {:description "poster actor (binding-specific: :local, the tg-user, :mcp …); default :mcp"}]]]
    :impl (fn [daemon {:keys [room text as]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon
                      (d/post! r (d/message (or (some-> as keyword) :mcp)
                                            (d/room-target r) text nil {:role :user}))
                      {:posted true :room (id->str (:id r))})))}

   :room/create
   {:doc "Create a new room."
    :kind :write
    :schema [:map [:title :string]
             [:slug {:optional true} :string]
             [:agents {:optional true} [:vector {:description "agent ids to join"} :string]]]
    :impl (fn [daemon {:keys [title slug agents]}]
            (in-ctx daemon
                    (let [sl (or slug (slugify title))]
                ;; create-room! returns the room-id keyword; look the live Room up.
                      (rooms/create-room! (cond-> {:title title :slug sl}
                                            agents (assoc :agent-ids (mapv keyword agents))))
                      (room-data (rreg/lookup (keyword sl))))))}

   :room/delete
   {:doc "Delete a room."
    :kind :write
    :schema [:map [:room Room]]
    :impl (fn [daemon {:keys [room]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon (rooms/delete-room! r) {:deleted (id->str (:id r))})))}

   :workflow/attempt
   {:doc (str "Run a task several times per model, each attempt on its own copy-on-write "
              "fork of the room (data, files, REPL), each with its own budget. Returns "
              "every attempt's outcome, score, bill and diff, with the world to adopt: "
              "room_merge one world, room_discard the others.")
    :kind :write
    :schema WorkflowArgs
    :impl (fn [daemon {:keys [room] :as args}]
            (when-let [r (resolve-room daemon room)]
              (workflow-result daemon r (:task args)
                               (workflow/attempt! r (dissoc args :room)))))}

   :workflow/start
   {:doc (str "Start workflow_attempt as a job and return at once: run a task several times "
              "per model, each attempt on its own fork of the room, each with its own budget. "
              "Poll job_status {job, wait-ms} until it is completed; its result lists every "
              "attempt's outcome, bill and review, the per-model table and the room's wallet. "
              "Adopt one world with room_merge {room: world, expect-state: review.state}, "
              "room_discard the others. job_cancel stops it.")
    :kind :write
    :schema WorkflowArgs
    :impl (fn [daemon {:keys [room] :as args}]
            (when-let [r (resolve-room daemon room)]
              (let [{:keys [spin ctx finish]} (workflow/start r (dissoc args :room))]
                (-> (jobs/start! {:op "workflow/start" :room (id->str (:id r)) :task (:task args)}
                                 #(workflow-result daemon r (:task args)
                                                   (finish (binding [ec/*execution-context* ctx] @spin)))
                                 :cancel #(binding [ec/*execution-context* ctx]
                                            (spin-core/cancel-spin! spin)))
                    job-data
                    (assoc :poll-after-ms 10000)))))}

   :job/cancel
   {:doc "Cancel a running job (e.g. a workflow_start): its attempts are stopped."
    :kind :write
    :schema [:map [:job [:string {:description "job id"}]]]
    :impl (fn [_daemon {:keys [job]}]
            (or (some-> (jobs/cancel! job) job-data)
                (throw (ex-info (str "No job " job) {:type ::no-job}))))}

   :room/fork
   {:doc "Fork a room for speculative work (O(1) copy-on-write); merge or discard later."
    :kind :write
    :schema [:map [:room Room]]
    :impl (fn [daemon {:keys [room]}]
            (when-let [r (resolve-room daemon room)]
              (room-result daemon (in-ctx daemon (forks/fork! r)))))}

   :room/merge
   {:doc "Reconcile-merge a fork back into its parent. Pass `expect-state` (from room_review or a workflow attempt's review) to merge only the state that was reviewed: a fork that changed since is refused."
    :kind :write
    :schema [:map [:room Fork]
             [:expect-state {:optional true}
              [:string {:description "the fork state token the merge was decided on"}]]]
    :impl (fn [daemon {:keys [room expect-state]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon
                      (when expect-state
                        (let [now (forks/state-token r)]
                          (when (not= expect-state now)
                            (throw (ex-info (str "Fork " (id->str (:id r)) " changed since it was reviewed"
                                                 " (reviewed " expect-state ", now " now "); review it again")
                                            {:type ::fork-changed :expected expect-state :actual now})))))
                      (let [{:keys [ok? error] :as res} (forks/reconcile-merge! r)]
                        (when-not ok?
                          (throw (ex-info (str "Merge of " (id->str (:id r)) " failed: " error)
                                          {:type ::merge-failed :error error})))
                        (cond-> {:merged (id->str (:id r))}
                          (:parent-slug res) (assoc :into (:parent-slug res))
                          (:reconciled res) (assoc :reconciled (:reconciled res)))))))}

   :room/review
   {:doc "What a reviewer decides a merge on: the fork's tier (trivial, reviewable, conflict), a per-system summary of its changes, the conflict count, and its state token for room_merge's expect-state."
    :kind :read
    :schema [:map [:room Fork]]
    :impl (fn [daemon {:keys [room]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon (workflow/review (:id r)))))}

   :room/discard
   {:doc "Discard a fork without merging."
    :kind :write
    :schema [:map [:room Fork]]
    :impl (fn [daemon {:keys [room]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon (forks/discard! r) {:discarded (id->str (:id r))})))}

   :agent/create
   {:doc "Create an agent (provision a durable row + bring it online)."
    :kind :write
    :schema (fields/create-schema)
    :impl (fn [daemon {:keys [id] :as fields}]
            (let [id (some-> id str str/trim not-empty)]
              (if-not id
                {:error "id required"}
                (in-ctx daemon
                        ((requiring-resolve 'dvergr.orchestration.daemon/provision-agent!)
                         daemon (assoc fields :id (keyword id)))
                        {:created id}))))}

   :agent/update
   {:doc "Update an agent's config (partial patch: model/provider/tags/prompt/…)."
    :kind :write
    :schema [:map [:id AgentId] [:patch (fields/update-schema)]]
    :impl (fn [daemon {:keys [id patch]}]
            (in-ctx daemon (aops/update-agent! (keyword id) patch) {:updated id}))}

   :agent/open
   {:doc "Open (ensure) a direct-message room with an agent; returns the room."
    :kind :write
    :schema [:map [:id AgentId]]
    :impl (fn [daemon {:keys [id]}]
            (room-result daemon
                         (in-ctx daemon
                                 ((requiring-resolve 'dvergr.orchestration.daemon/ensure-agent-room!)
                                  daemon (keyword id)))))}

   :room/invite
   {:doc "Invite (join) an agent into a room."
    :kind :write
    :schema [:map [:room Room] [:agent AgentId]]
    :impl (fn [daemon {:keys [room agent]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon
                      (let [aid  (keyword agent)
                            cfg  ((requiring-resolve 'dvergr.orchestration.daemon/agent-join-config) daemon aid)]
                        (if-not cfg
                          {:error (str "Unknown agent: " agent)}
                          (let [safe ((requiring-resolve 'dvergr.orchestration.daemon/resolve-safe-config) cfg)]
                            ((requiring-resolve 'dvergr.orchestration.daemon/join-agent-to-room!) daemon safe r)
                            {:invited agent :room (id->str (:id r))}))))))}

   :agent/delete
   {:doc "Delete an agent — stop the live participant, then retract its row + persona."
    :kind :write
    :schema [:map [:id AgentId]]
    :impl (fn [daemon {:keys [id]}]
            (in-ctx daemon
              ;; Stop the running agent first (the runtime half), then delete the
              ;; durable half — the complete deletion every surface should do.
                    (try ((requiring-resolve 'dvergr.orchestration.daemon/stop-agent!) daemon (keyword id))
                         (catch Throwable _ nil))
                    (aops/delete-agent! (keyword id))
                    {:deleted id}))}})

;; ============================================================================
;; Derivation helpers
;; ============================================================================

(defn op->name
  "Op key → flat binding name: `:room/post` → \"room_post\"."
  [op]
  (str (namespace op) "_" (name op)))

(defn malli-schema
  "The malli args schema for op `op` (a `[:map …]`) — the source the HTTP/JSON
   API coerces against directly."
  [op]
  (:schema (get specification op)))

(defn input-schema
  "JSON Schema for op `op`'s args — the malli `:schema` projected via
   `malli.json-schema`, for the MCP inputSchema / OpenAPI."
  [op]
  (mjs/transform (malli-schema op)))

(defn reads  [] (into {} (filter (comp #(= :read  (:kind %)) val) specification)))
(defn writes [] (into {} (filter (comp #(= :write (:kind %)) val) specification)))

(defn invoke
  "Run op `op` (a spec key) with `args` (a keyword map) against `daemon`. Returns
   the op's data result, or throws. The single call-path every binding uses."
  [daemon op args]
  (if-let [{:keys [impl]} (get specification op)]
    (impl daemon (or args {}))
    (throw (ex-info (str "Unknown op: " op) {:op op}))))
