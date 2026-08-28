(ns dvergr.agent.roster
  "Pure, immutable construction of agent rosters and versioned AgentDefs.

   A Roster is workflow data, not a registry handle. `make-agent` returns a new
   value and never starts a Participant, opens a provider, or mutates a Room.
   Effectful installation and execution consume these values at a later boundary."
  (:refer-clojure :exclude [agent])
  (:require [clojure.set :as set]))

(defn data-value?
  "Whether `x` is portable AgentDef data. Runtime handles, functions, atoms,
   connections, and provider clients deliberately fail this predicate."
  [x]
  (cond
    (or (nil? x)
        (boolean? x)
        (string? x)
        (number? x)
        (keyword? x)
        (symbol? x)
        (uuid? x)) true
    (map? x) (every? (fn [[k v]] (and (data-value? k) (data-value? v))) x)
    (vector? x) (every? data-value? x)
    (set? x) (every? data-value? x)
    (list? x) (every? data-value? x)
    :else false))

(defn- portable! [label value]
  (when-not (data-value? value)
    (throw (ex-info (str label " must contain only portable data")
                    {:type ::non-portable-data :label label :value value})))
  value)

(def ^:private friendly-keys
  {:id :agent/id
   :version :agent/version
   :status :agent/status
   :skills :agent/skills
   :name :agent/name
   :prompt :agent/prompt
   :program :agent/program
   :tools :agent/tools
   :model-policy :agent/model-policy
   :metadata :agent/metadata})

(def ^:private canonical-keys (set (vals friendly-keys)))
(def ^:private agent-spec-keys (into (set (keys friendly-keys)) canonical-keys))

(defn- known-agent-keys! [label m]
  (when-let [unknown (seq (remove agent-spec-keys (keys (or m {}))))]
    (throw (ex-info (str label " contains unknown AgentDef keys")
                    {:type ::unknown-agent-keys
                     :label label
                     :unknown (set unknown)
                     :allowed agent-spec-keys}))))

(defn- keyword-set!
  [label value]
  (when-not (and (coll? value)
                 (not (map? value))
                 (every? keyword? value))
    (throw (ex-info (str label " must be a collection of keywords")
                    {:type ::invalid-keyword-set :label label :value value})))
  (set value))

(defn- canonicalize
  "Normalize friendly AgentDef keys before merging. Canonical keys win within
   one map; the caller's map then wins over defaults regardless of which key
   spelling either side used."
  [m]
  (let [friendly (reduce-kv (fn [out k v]
                              (if-let [canonical (get friendly-keys k)]
                                (assoc out canonical v)
                                out))
                            {} (or m {}))
        canonical (reduce-kv (fn [out k v]
                               (if (contains? friendly-keys k)
                                 out
                                 (assoc out k v)))
                             {} (or m {}))]
    (merge friendly canonical)))

(defn- normalize-agent
  [defaults spec]
  (known-agent-keys! "Roster defaults" defaults)
  (known-agent-keys! "AgentDef" spec)
  (let [spec    (merge (canonicalize defaults) (canonicalize spec))
        id      (:agent/id spec)
        version (or (:agent/version spec) 1)
        program (:agent/program spec)
        agent   (cond-> {:agent/id id
                         :agent/version version
                         :agent/status (or (:agent/status spec) :available)
                         :agent/skills (keyword-set! "AgentDef :skills"
                                                     (or (:agent/skills spec) #{}))}
                  (:agent/name spec)
                  (assoc :agent/name (:agent/name spec))

                  (:agent/prompt spec)
                  (assoc :agent/prompt (:agent/prompt spec))

                  program (assoc :agent/program program)

                  (:agent/tools spec)
                  (assoc :agent/tools (keyword-set! "AgentDef :tools"
                                                    (:agent/tools spec)))

                  (:agent/model-policy spec)
                  (assoc :agent/model-policy (:agent/model-policy spec))

                  (:agent/metadata spec)
                  (assoc :agent/metadata (:agent/metadata spec)))]
    (when-not (keyword? id)
      (throw (ex-info "AgentDef requires a keyword :id"
                      {:type ::invalid-agent-id :id id :spec spec})))
    (when-not (and (integer? version) (pos? version))
      (throw (ex-info "AgentDef version must be a positive integer"
                      {:type ::invalid-agent-version :version version :agent/id id})))
    (when-not (keyword? (:agent/status agent))
      (throw (ex-info "AgentDef :status must be a keyword"
                      {:type ::invalid-agent-status
                       :status (:agent/status agent)
                       :agent/id id})))
    (doseq [[k v] [[:agent/name (:agent/name agent)]
                   [:agent/prompt (:agent/prompt agent)]]
            :when (and (some? v) (not (string? v)))]
      (throw (ex-info (str k " must be a string")
                      {:type ::invalid-agent-field :key k :value v :agent/id id})))
    (when (and program (not (map? program)))
      (throw (ex-info "AgentDef :program must be a data map"
                      {:type ::invalid-program :agent/id id :program program})))
    (portable! "AgentDef" agent)))

(defn make-roster
  "Create an immutable Roster.

   Options are portable data:
   - `:id`       optional logical identity
   - `:defaults` shallow defaults applied by `make-agent`
   - `:scope`    resource/authority grant interpreted at execution time
   - `:metadata` caller-owned descriptive data"
  ([] (make-roster {}))
  ([{:keys [id defaults scope metadata]
     :or {defaults {} scope {}}
     :as opts}]
   (when-let [unknown (seq (remove #{:id :defaults :scope :metadata}
                                   (keys opts)))]
     (throw (ex-info "Unknown Roster options"
                     {:type ::unknown-roster-options
                      :unknown (set unknown)
                      :allowed #{:id :defaults :scope :metadata}})))
   (when (and id (not (keyword? id)))
     (throw (ex-info "Roster :id must be a keyword"
                     {:type ::invalid-roster-id :id id})))
   (when-not (map? defaults)
     (throw (ex-info "Roster :defaults must be a map"
                     {:type ::invalid-roster-defaults :defaults defaults})))
   (when-not (map? scope)
     (throw (ex-info "Roster :scope must be a map"
                     {:type ::invalid-roster-scope :scope scope})))
   (when (and metadata (not (map? metadata)))
     (throw (ex-info "Roster :metadata must be a map"
                     {:type ::invalid-roster-metadata :metadata metadata})))
   (portable! "Roster options" {:id id :defaults defaults :scope scope :metadata metadata})
   (cond-> {:roster/agents {}
            :roster/defaults defaults
            :roster/scope scope}
     id (assoc :roster/id id)
     metadata (assoc :roster/metadata metadata))))

(defn agent-ref
  "Return the stable reference for an AgentDef value."
  [agent]
  (select-keys agent [:agent/id :agent/version]))

(defn agents
  "Every AgentDef in `roster`, deterministically ordered by id."
  [roster]
  (->> (:roster/agents roster)
       vals
       (sort-by (comp str :agent/id))
       vec))

(defn agent
  "Resolve an AgentDef by keyword id or `agent-ref`. A versioned stale ref is an
   error rather than silently selecting a revised program."
  [roster id-or-ref]
  (let [id       (if (map? id-or-ref) (:agent/id id-or-ref) id-or-ref)
        expected (when (map? id-or-ref) (:agent/version id-or-ref))
        found    (get-in roster [:roster/agents id])]
    (when (and found expected (not= expected (:agent/version found)))
      (throw (ex-info "AgentRef points to a different AgentDef version"
                      {:type ::stale-agent-ref
                       :agent/id id
                       :expected-version expected
                       :actual-version (:agent/version found)})))
    found))

(defn make-agent
  "Return a new Roster containing `spec` as a portable AgentDef.

   Adding an identical definition is idempotent. Reusing an id with different
   data is rejected; use `revise-agent` so old Run references retain meaning."
  [roster spec]
  (let [definition (normalize-agent (:roster/defaults roster) spec)
        id         (:agent/id definition)
        prior      (agent roster id)]
    (cond
      (nil? prior) (assoc-in roster [:roster/agents id] definition)
      (= prior definition) roster
      :else (throw (ex-info "Agent id already names a different definition"
                            {:type ::agent-conflict
                             :agent/id id
                             :existing prior
                             :proposed definition})))))

(defn revise-agent
  "Return a new Roster with `id` revised by `patch` and its version incremented."
  [roster id patch]
  (when-let [forbidden (seq (filter #(contains? patch %)
                                    [:id :agent/id :version :agent/version]))]
    (throw (ex-info "revise-agent cannot change AgentDef identity or version"
                    {:type ::immutable-agent-identity
                     :agent/id id
                     :forbidden (set forbidden)})))
  (let [prior (or (agent roster id)
                  (throw (ex-info "Cannot revise an unknown agent"
                                  {:type ::unknown-agent :agent/id id})))
        friendly-prior
        {:id (:agent/id prior)
         :version (inc (:agent/version prior))
         :status (:agent/status prior)
         :skills (:agent/skills prior)
         :name (:agent/name prior)
         :prompt (:agent/prompt prior)
         :program (:agent/program prior)
         :tools (:agent/tools prior)
         :model-policy (:agent/model-policy prior)
         :metadata (:agent/metadata prior)}
        revised (normalize-agent friendly-prior patch)]
    (when-not (and (= id (:agent/id revised))
                   (= (inc (:agent/version prior)) (:agent/version revised)))
      (throw (ex-info "Revised AgentDef violated roster identity invariants"
                      {:type ::agent-invariant-violation
                       :agent/id id
                       :prior prior
                       :revised revised})))
    (assoc-in roster [:roster/agents id] revised)))

(defn select-agents
  "Select AgentDefs deterministically.

   Selector keys:
   - `:id`, `:status`
   - `:skill` requires one skill
   - `:skills` requires all skills
   - `:where` portable map of exact AgentDef key/value matches"
  [roster {:keys [id status skill skills where]
           :or {skills #{} where {}}}]
  (let [required (cond-> (set skills) skill (conj skill))]
    (->> (agents roster)
         (filter #(if id (= id (:agent/id %)) true))
         (filter #(if status (= status (:agent/status %)) true))
         (filter #(set/subset? required (:agent/skills %)))
         (filter #(every? (fn [[k v]] (= v (get % k))) where))
         vec)))
