(ns dvergr.discourse.attention
  "Pure attention decisions for reactive conversational programs.

   A speech act says what happened in the Room. An attention decision says how
   one participant treats that fact. The executor then interprets the decision
   at a boundary it actually supports. Keeping these layers separate prevents
   message tags from becoming ambient process-control authority.")

(def execution-boundaries
  "Provider-neutral observation/integration boundaries. An interpreter reports
   only the boundaries it can actually honor. `:next-safe-boundary` is the
   portable request used when the policy does not depend on a provider-specific
   step."
  #{:now
    :next-safe-boundary
    :before-model
    :token
    :before-tool
    :after-tool
    :after-model
    :quiescent})

(def memory-modes #{:ignore :remember :include})
(def activation-modes #{:none :enqueue :wake})
(def control-modes #{:continue :integrate :restart :suspend :cancel})

(def ^:private decision-keys
  #{:memory :activation :control :at :priority :reason :metadata})

(def default-decision
  "The identity-like decision: retain awareness without waking or changing the
   active execution."
  {:memory :remember
   :activation :none
   :control :continue
   :at :next-safe-boundary
   :priority 0})

(defn decision
  "Validate and complete an attention decision map.

   The axes are intentionally independent: one fact may be remembered, enqueue
   later work, and leave the current execution alone. `:reason` is explanatory
   data, never process authority."
  [m]
  (when-not (map? m)
    (throw (ex-info "Attention decision must be a map"
                    {:type ::invalid-decision :decision m})))
  (let [unknown (seq (remove decision-keys (keys m)))
        d (merge default-decision m)]
    (when unknown
      (throw (ex-info "Attention decision has unknown keys"
                      {:type ::invalid-decision
                       :unknown-keys (vec unknown)
                       :decision m})))
    (when-not (memory-modes (:memory d))
      (throw (ex-info "Invalid attention memory mode"
                      {:type ::invalid-decision :axis :memory
                       :value (:memory d) :decision m})))
    (when-not (activation-modes (:activation d))
      (throw (ex-info "Invalid attention activation mode"
                      {:type ::invalid-decision :axis :activation
                       :value (:activation d) :decision m})))
    (when-not (control-modes (:control d))
      (throw (ex-info "Invalid attention control mode"
                      {:type ::invalid-decision :axis :control
                       :value (:control d) :decision m})))
    (when-not (execution-boundaries (:at d))
      (throw (ex-info "Invalid attention execution boundary"
                      {:type ::invalid-decision :axis :at
                       :value (:at d) :decision m})))
    (when-not (number? (:priority d))
      (throw (ex-info "Attention priority must be numeric"
                      {:type ::invalid-decision :axis :priority
                       :value (:priority d) :decision m})))
    (when (and (contains? d :reason) (some? (:reason d))
               (not (keyword? (:reason d))))
      (throw (ex-info "Attention reason must be a keyword or nil"
                      {:type ::invalid-decision :axis :reason
                       :value (:reason d) :decision m})))
    (when (and (contains? d :metadata) (some? (:metadata d))
               (not (map? (:metadata d))))
      (throw (ex-info "Attention metadata must be a map or nil"
                      {:type ::invalid-decision :axis :metadata
                       :value (:metadata d) :decision m})))
    d))

(defn observe
  "Remember a fact without waking or changing the active execution."
  ([] (observe nil))
  ([reason]
   (decision (cond-> {} reason (assoc :reason reason)))))

(defn enqueue
  "Retain a fact as later work without preempting the active execution."
  ([] (enqueue nil))
  ([reason]
   (decision (cond-> {:activation :enqueue :at :quiescent}
               reason (assoc :reason reason)))))

(defn restart
  "Include a fact and request replacement of the active computation at its next
   safe boundary. The executor decides how replacement is implemented."
  ([] (restart nil))
  ([reason]
   (decision (cond-> {:memory :include
                      :control :restart
                      :at :next-safe-boundary}
               reason (assoc :reason reason)))))

(def ^:private legacy-decisions
  {:observe (observe :legacy/observe)
   :queue (enqueue :legacy/queue)
   :steer (restart :legacy/steer)})

(defn normalize
  "Return a validated decision. The former `:observe`, `:queue`, and `:steer`
   values remain accepted so existing deployment policies migrate without a
   flag day."
  [x]
  (if-let [d (and (keyword? x) (legacy-decisions x))]
    d
    (decision x)))

(defn legacy-action
  "Project the subset implemented by the current LLM arbiter back to its former
   action vocabulary. Returns nil for valid decisions requiring a newer
   interpreter (wake, integrate, suspend, or cancel).

   This function is a compatibility boundary, not the conversational algebra."
  [x]
  (let [shape (select-keys (normalize x)
                           [:memory :activation :control :at :priority])]
    (case shape
      {:memory :include
       :activation :none
       :control :restart
       :at :next-safe-boundary
       :priority 0} :steer

      {:memory :remember
       :activation :enqueue
       :control :continue
       :at :quiescent
       :priority 0} :queue

      {:memory :remember
       :activation :none
       :control :continue
       :at :next-safe-boundary
       :priority 0} :observe

      nil)))

(defn boundary-event
  "Construct a provider-neutral live boundary event. Boundary events are
   process-local observations; durable Run/activity facts may reference them,
   but this constructor does not persist anything."
  ([type] (boundary-event type nil))
  ([type data]
   (when-not (execution-boundaries type)
     (throw (ex-info "Unknown execution boundary"
                     {:type ::invalid-boundary :boundary type})))
   (when-not (or (nil? data) (map? data))
     (throw (ex-info "Execution boundary data must be a map or nil"
                     {:type ::invalid-boundary :boundary type :data data})))
   (cond-> {:attention.boundary/type type}
     data (assoc :attention.boundary/data data))))
