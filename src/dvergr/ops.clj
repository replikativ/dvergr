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
            [dvergr.catalog :as catalog]
            [dvergr.agent.experiment.runner :as runner]
            [dvergr.agent.experiment.stats :as xstats]
            [dvergr.jobs :as jobs]
            [dvergr.resource :as resource]
            [dvergr.agent.fields :as fields]
            [dvergr.rooms :as rooms]
            [dvergr.rooms.forks :as forks]
            [dvergr.rooms.repo :as room-repo]
            [dvergr.rooms.stats :as stats]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store :as rstore]
            [dvergr.discourse :as d]
            [dvergr.model.registry :as reg]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [taoensso.telemere :as tel]))

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

(defn- check-rates
  "Per candidate, the share of its Attempts that passed each check:
   `{candidate {check rate}}`, from the certified Attempts of `entries`."
  [entries load-attempt]
  (into {}
        (for [[candidate es] (group-by :candidate/id entries)
              :let [checks (keep #(some-> (load-attempt (:attempt/id %)) :attempt/receipt :attempt/checks) es)]
              :when (seq checks)]
          [(kw->str candidate)
           (into (sorted-map)
                 (for [k (distinct (mapcat keys checks))]
                   [(kw->str k) (/ (double (count (filter #(true? (get % k)) checks))) (count checks))]))])))

(defn- comparison
  "Every other candidate of a Scorecard against `baseline` (a candidate id;
   default the leaderboard's top row): the probability that its pass rate is
   higher, and that it is no worse by more than 5 points; its reward minus the
   baseline's, paired by world (the same environments, repetitions averaged)
   with a 95% interval; and what a pass costs next to the baseline's."
  [entries rows baseline]
  (let [by (into {} (map (juxt :candidate identity)) rows)
        base (or baseline (:candidate (first rows)))
        per-world (into {}
                        (for [[c es] (group-by (comp kw->str :candidate/id) entries)]
                          [c (update-vals (group-by #(get-in % [:environment :environment/content-id]) es)
                                          #(/ (reduce + 0.0 (map :reward %)) (count %)))]))]
    (when-let [b (by base)]
      {:baseline base
       :margin 0.05
       :candidates
       (vec (for [{:keys [candidate] :as r} rows
                  :when (not= candidate base)
                  :let [rate [(:passed r) (:attempts r)]
                        base-rate [(:passed b) (:attempts b)]
                        worlds (per-world candidate)
                        base-worlds (per-world base)
                        diff (xstats/paired-difference (for [[env x] worlds :let [y (get base-worlds env)] :when y] [x y]))
                        cost (:microdollars-per-pass r)
                        base-cost (:microdollars-per-pass b)]]
              {:candidate candidate
               :p-pass-rate-higher (xstats/prob-rate-above rate base-rate 0.0)
               :p-pass-rate-no-worse (xstats/prob-rate-above rate base-rate 0.05)
               :reward-difference (:mean diff)
               :reward-difference-interval (:interval diff)
               :paired-worlds (:n diff 0)
               :microdollars-per-pass-saved (when (and cost base-cost) (- base-cost cost))
               :cost-per-pass-ratio (when (and cost base-cost (pos? base-cost)) (/ (double cost) base-cost))}))})))

(defn- scorecard-data
  "A Scorecard as a leaderboard: one row per candidate, ranked by reward mean
   then by cost per pass, with 95% intervals for the pass rate (Jeffreys) and
   the mean reward (`dvergr.agent.experiment.stats`). With `:full?`, also
   every cell, and with `:load-attempt` (id → certified Attempt) the pass rate
   of every check per candidate; with `:compare?`, every candidate against
   `:baseline` (`comparison`)."
  [sc & [{:keys [full? load-attempt compare? baseline]}]]
  (when sc
    (let [exp (:scorecard/experiment sc)
          rewards (update-vals (group-by :candidate/id (:scorecard/entries sc)) #(mapv :reward %))
          rows (->> (:scorecard/summary sc)
                    (map (fn [s]
                           {:candidate       (kw->str (:candidate/id s))
                            :attempts        (:attempt-count s)
                            :passed          (:passed-count s)
                            :pass-rate       (when (pos? (:attempt-count s 0))
                                               (/ (double (:passed-count s)) (:attempt-count s)))
                            :pass-rate-interval (xstats/pass-rate-interval (:passed-count s 0) (:attempt-count s 0))
                            :reward-mean     (:reward-mean s)
                            :reward-interval (xstats/mean-interval (get rewards (:candidate/id s)))
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
        load-attempt (assoc :check-rates (check-rates (:scorecard/entries sc) load-attempt))
        compare? (assoc :comparison (comparison (:scorecard/entries sc) rows baseline))
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
   [:timeout-ms {:optional true} [:int {:description "per attempt (default 10 minutes)"}]]
   [:parallelism {:optional true} [:int {:min 1 :max 8 :description "attempts running at once (default 4)"}]]])

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

(declare workflow-result)

(defn- workflow-rows
  "The attempts of the workflow job `job-id` as `workflow-result` rows, from
   the store: the job Run's children, each with its certified Attempt, its
   world and, while the world is open, its review."
  [room job-id]
  (let [children (->> (run/runs room {:root-run-id job-id :limit 1000})
                      (filter #(= job-id (:run/parent %)))
                      (sort-by #(some-> ^java.util.Date (:run/started-at %) .getTime)))]
    (vec (for [c children
               :let [a (attempts/attempt room (:run/id c))]
               :when a
               :let [world (:run/world c)]]
           {:attempt a
            :world world
            :review (when (and world (rreg/lookup world))
                      (workflow/review world))}))))

(defn- job-result
  "What a finished job produced, read from its room's store."
  [daemon {:keys [id kind room-value status]}]
  (when (and room-value (= :completed status))
    (in-ctx daemon
            (binding [ec/*execution-context* (:ctx room-value)]
              (case kind
                :workflow
                (let [rows (workflow-rows room-value id)]
                  (workflow-result daemon room-value
                                   (get-in (first rows) [:attempt :attempt/environment :environment/task])
                                   rows))
                :experiment
                ;; The Scorecard of this job's experiment: a room may keep several.
                (let [exps (->> (run/runs room-value {:root-run-id id :limit 1000})
                                (filter #(= id (:run/parent %)))
                                (keep #(attempts/attempt room-value (:run/id %)))
                                (keep #(get-in % [:attempt/receipt :attempt/metrics :experiment-content-id]))
                                set)
                      sc (some #(first (experiment/scorecards room-value {:experiment-content-id % :limit 1}))
                               exps)]
                  (cond-> {:room (id->str (:id room-value))}
                    sc (assoc :summary (:scorecard/summary sc))
                    (nil? sc) (assoc :incomplete (experiment/progress room-value))))
                nil)))))

(defn- job-data
  "A job as plain data; `result?` adds what it produced once completed."
  ([daemon job] (job-data daemon job false))
  ([daemon job result?]
   (cond-> {:id (str (:id job))
            :kind (some-> (:kind job) name)
            :room (id->str (:room job))
            :status (some-> (:status job) name)
            :started-at (:started-at job)
            :finished-at (:finished-at job)}
     (:error job) (assoc :error (:error job))
     (:reason job) (assoc :reason (name (:reason job)))
     result? (assoc :result (job-result daemon job)))))

(defn- start-workflow-job
  "Start `opts` (a `workflow/start` option map) on room `r` as a job, a Run
   whose children are the attempts; the job data a client polls."
  [daemon r op opts]
  (let [finish (promise)]
    (-> (in-ctx daemon
                (jobs/start! r {:kind :workflow}
                             (fn [job-id]
                               (let [{:keys [spin] :as started}
                                     (workflow/start r (assoc opts :parent-run job-id))]
                                 (deliver finish (:finish started))
                                 spin))
                             ;; The attempts' files, committed in their worlds.
                             :finish #(@finish %)))
        (->> (job-data daemon))
        (assoc :op op :task (:task opts) :poll-after-ms 10000))))

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
                        ok (count (filter #(= 1.0 (:reward %)) xs))
                        rewards (keep :reward xs)]
                    {:model model :attempts (count xs) :completed ok
                     :mean-reward (when (seq rewards)
                                    (/ (Math/round (* 1000 (/ (reduce + rewards) (count rewards)))) 1000.0))
                     :microdollars md
                     :microdollars-per-completion (when (pos? ok) (quot md ok))})))
          (sort-by (juxt #(- (or (:mean-reward %) 0)) #(- (:completed %)) :microdollars))
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

   :experiment/progress
   {:doc (str "What the experiments in a room have done so far, from its store: per experiment and "
              "candidate the certified cells, verdicts, faults, mean reward, spend and failure causes, "
              "and the Runs still running. The same while it runs and after.")
    :kind :read
    :schema [:map [:room Room]]
    :impl (fn [daemon {:keys [room]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon (experiment/progress r))))}

   :catalog/list
   {:doc "Workflows that come with their own checker and benchmark set; run one with catalog_start."
    :kind :read
    :schema [:map]
    :impl (fn [_daemon _] (mapv catalog/describe (vals catalog/workflows)))}

   :job/status
   {:doc (str "A job's status, and its result once completed. `wait-ms` (at most 25000) "
              "waits that long for it to finish before answering.")
    :kind :read
    :schema [:map [:job [:string {:description "job id"}]]
             [:wait-ms {:optional true} [:int {:min 0 :max 25000 :description "wait up to this long (default 0)"}]]]
    :impl (fn [daemon {:keys [job wait-ms]}]
            (or (some-> (in-ctx daemon (jobs/status (uuid-arg job) (or wait-ms 0)))
                        (#(job-data daemon % true)))
                (throw (ex-info (str "No job " job) {:type ::no-job}))))}

   :job/list
   {:doc "Jobs, newest first (running and recently finished), optionally of one room; results omitted."
    :kind :read
    :schema [:map [:room {:optional true} Room]]
    :impl (fn [daemon {:keys [room]}]
            (let [r (when room (resolve-room daemon room))]
              (mapv #(job-data daemon %) (in-ctx daemon (if r (jobs/jobs r) (jobs/jobs))))))}

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
                                            (rstore/-load-attempt (rstore/conversation-id r) (uuid-arg id)))
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
   {:doc "One Scorecard with every cell (candidate x environment x repetition: reward, pass, spend), each candidate's pass rate per check, and every candidate against a baseline: probability of a higher / no-worse pass rate, paired reward difference, cost per pass saved."
    :kind :read
    :schema [:map [:room Room] [:id [:string {:description "scorecard content id"}]]
             [:baseline {:optional true} [:string {:description "candidate to compare the others against (default: the top of the leaderboard)"}]]]
    :impl (fn [daemon {:keys [room id baseline]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon
                      (scorecard-data (experiment/scorecard r (uuid-arg id))
                                      {:full? true :compare? true :baseline baseline
                                       :load-attempt (when-let [st (:store r)]
                                                       #(rstore/-load-attempt st (rstore/conversation-id r) %))}))))}

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
              (start-workflow-job daemon r "workflow/start" (dissoc args :room))))}

   :catalog/start
   {:doc (str "Run a catalog workflow (catalog_list) as a job, scored by its own checker. "
              "Without `room`: on its benchmark set, in a new room seeded with the fixtures, "
              "scored against known answers: compare models and prompts. With `room`: on "
              "your room's own files, scored by what can be checked without answers. "
              "Poll job_status; adopt a world with room_merge.")
    :kind :write
    :schema [:map
             [:workflow [:string {:description "catalog workflow id, e.g. wiki/v1"}]]
             [:room {:optional true} Room]
             [:attempts {:optional true} [:int {:min 1 :max 8 :description "attempts per model (default 1)"}]]
             [:models {:optional true} [:vector {:description "model ids or aliases (default: the configured default)"} :string]]
             [:budget-dollars {:optional true} [:double {:description "budget per attempt in USD (default 0.50)"}]]
             [:timeout-ms {:optional true} [:int {:description "per attempt (default 10 minutes)"}]]
             [:parallelism {:optional true} [:int {:min 1 :max 8 :description "attempts running at once (default 4)"}]]]
    :impl (fn [daemon {:keys [workflow room] :as args}]
            (let [wf (catalog/lookup workflow)
                  benchmark? (nil? room)
                  r (if room
                      (resolve-room daemon room)
                      (let [slug (str "bench-" (slugify (subs (str (:id wf)) 1)) "-"
                                      (subs (str (random-uuid)) 0 8))]
                        (in-ctx daemon
                                (rooms/create-room! {:title (str (:title wf) " (benchmark)") :slug slug})
                                (let [r (rreg/lookup (keyword slug))]
                                  (catalog/seed! r ((get-in wf [:benchmark :fixtures])))
                                  r))))
                  {:keys [task profile evaluator environment-id]} (catalog/plan wf {:benchmark? benchmark?})]
              (when r
                (-> (start-workflow-job daemon r "catalog/start"
                                        (merge (select-keys args [:attempts :models :budget-dollars :timeout-ms :parallelism])
                                               {:task task :profile profile :evaluator evaluator
                                                :environment-id environment-id}))
                    (assoc :workflow (subs (str (:id wf)) 1)
                           :mode (if benchmark? "benchmark" "room"))))))}

   :catalog/benchmark
   {:doc (str "Benchmark models on a catalog workflow's benchmark set, as an experiment in a new "
              "room: each attempt in a discarded world with the fixtures, scored by the workflow's "
              "checker, folded into a Scorecard with the bill. With `room`, the job, its Attempts "
              "and the Scorecard are kept in that room (its dashboards show them) while the "
              "attempts still fork the new fixture room. Returns a job; experiment_progress "
              "{room} shows every certified cell while it runs.")
    :kind :write
    :schema [:map
             [:workflow [:string {:description "catalog workflow id, e.g. wiki/v2"}]]
             [:models [:vector {:description "model ids or aliases"} :string]]
             [:room {:optional true} [:string {:description "room id or slug that keeps the results (default: the new fixture room)"}]]
             [:environments {:optional true} [:int {:min 1 :max 50 :description "generated worlds, for workflows that generate them (wiki/v3; default 6)"}]]
             [:scale {:optional true} [:int {:min 1 :max 10 :description "documents per generated world, as a multiple (wiki/v3; default 1 = twelve documents)"}]]
             [:prose {:optional true} [:int {:min 0 :max 20 :description "paragraphs of routine text per generated document (wiki/v3; default 0)"}]]
             [:noise {:optional true} [:int {:min 0 :max 4 :description "kinds of noise in generated documents: 1 scan artefacts, 2 and an erratum, 3 and short names, 4 and retyped documents (wiki/v3; default 0)"}]]
             [:split {:optional true} [:enum {:description "dev (public, the default), test (held out: the host's key) or real (real corpora)"} "dev" "test" "real"]]
             [:repetitions {:optional true} [:int {:min 1 :max 10 :description "attempts per model (default 1)"}]]
             [:budget-dollars {:optional true} [:double {:description "budget per attempt in USD (default 0.50)"}]]
             [:timeout-ms {:optional true} [:int {:description "per attempt (default 10 minutes)"}]]]
    :impl (fn [daemon {:keys [workflow models repetitions room] :as args}]
            (let [wf (catalog/lookup workflow)
                  plan-fn (or (:experiment-plan wf)
                              (throw (ex-info (str workflow " has no benchmark set") {:type ::no-benchmark})))
                  control (when room
                            (or (resolve-room daemon room)
                                (throw (ex-info (str "No room " room) {:type ::no-room :room room}))))
                  plan (plan-fn (cond-> (select-keys args [:models :budget-dollars :timeout-ms])
                                  (:environments args) (assoc :n (:environments args))
                                  (:split args) (assoc :split (keyword (:split args)))
                                  (:scale args) (assoc :scale (:scale args))
                                  (:prose args) (assoc :prose (:prose args))
                                  (:noise args) (assoc :noise (:noise args))))
                  slug (str "bench-" (slugify (subs (str (:id wf)) 1)) "-" (subs (str (random-uuid)) 0 8))
                  r (in-ctx daemon
                            (rooms/create-room! {:title (str (:title wf) " (benchmark)") :slug slug})
                            (rreg/lookup (keyword slug)))
                  exp (runner/experiment-def (assoc plan :id (keyword slug) :repetitions (or repetitions 1)))]
              (-> (in-ctx daemon
                          (if control
                            ;; The records in `control`, the worlds forked from `r`.
                            (jobs/start! control {:kind :experiment :ctx (:ctx r)}
                                         #(runner/run-in control (assoc plan :experiment exp :parent-run %
                                                                        :world-parent r))
                                         ;; The fixture room's work is done once the job is
                                         ;; over (every cell's world settled, every record in
                                         ;; `control`): retire it, keeping its history.
                                         :settled (fn [_ _]
                                                    (let [{:keys [ok? error]} (in-ctx daemon (rooms/archive-room! r))]
                                                      (when-not ok?
                                                        (tel/log! {:level :warn :id ::fixture-room-not-archived
                                                                   :data {:room (id->str (:id r)) :error error}}
                                                                  "A benchmark's fixture room could not be archived")))))
                            (jobs/start! r {:kind :experiment}
                                         #(runner/run-in r (assoc plan :experiment exp :parent-run %)))))
                  (->> (job-data daemon))
                  (assoc :op "catalog/benchmark" :task workflow :models models
                         :room (if control (id->str (:id control)) slug)
                         :poll-after-ms 30000)
                  (cond-> control (assoc :fixture-room slug)))))}

   :job/cancel
   {:doc "Cancel a running job (e.g. a workflow_start): its attempts are stopped."
    :kind :write
    :schema [:map [:job [:string {:description "job id"}]]]
    :impl (fn [daemon {:keys [job]}]
            (or (some->> (in-ctx daemon (jobs/cancel! (uuid-arg job))) (job-data daemon))
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

   :room/import
   {:doc (str "Create a room from a Git repository: its workspace is a clone of a local checkout "
              "(path; its committed state) or a remote URL (https, ssh), with history. Its agents then "
              "work on it in forks (workflow_start), reviewed and merged as usual; room_export returns "
              "the changes as a patch.")
    :kind :write
    :schema [:map [:room [:string {:description "slug of the NEW room"}]]
             [:source [:string {:description "local checkout path, or https/ssh Git URL"}]]
             [:title {:optional true} :string]
             [:branch {:optional true} [:string {:description "branch of a local checkout (default: its current one)"}]]]
    :impl (fn [daemon {:keys [room source title branch]}]
            (in-ctx daemon (room-repo/import-room! {:slug room :title title :source source :branch branch})))}

   :room/export
   {:doc (str "The room's changes since room_import, as a patch for `git apply` in the source checkout "
              "(after adopting an attempt with room_merge). Commits the room's pending work first.")
    :kind :write
    :schema [:map [:room Room]]
    :impl (fn [daemon {:keys [room]}]
            (when-let [r (resolve-room daemon room)]
              (in-ctx daemon (room-repo/export r))))}

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
