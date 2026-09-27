(ns dvergr.effects
  "The boundary between sandbox code and the world (doc/effects.md), as
   effects and handlers.

   An effect is data: an operation from a closed vocabulary (the signature
   below), the resource it acts on, and optionally extra classes for this
   request. A capability injected into a sandbox performs it with `perform!`,
   passing the function that does the real work (the executor).

   A handler is `(fn [effect next] value)`: it answers with a value (a
   recorded or injected result), refuses by throwing, or forwards to the rest
   of the stack with `next`, doing something around it. The executor is the
   innermost handler, the world. Handlers only resume once and at once (tail
   resumption), so no continuation is captured: SCI code is not CPS-transformed.
   Resuming later (an approval) parks the calling thread; resuming several ways
   (counterfactuals) re-executes against the receipt log.

   The stack is configured per world, as data in the world binding
   (`[[:admit #{:read :network}] [:read-only]]`), and built from `registry`.
   It forks with the world, and sandbox code cannot change it.

   The algebra (`normalize`, `compose`): a refusing handler is a filter `admit
   S`, the classes it lets through (`read-only` = `admit #{:read}`). Filters
   compose by intersection, so they commute, are idempotent, and `admit` of
   every class is the identity. A stack has one canonical order: receipts
   (observe everything, outermost), the filter, then answering handlers
   (replay, faults, in the order given), then the world. Composing two
   configurations concatenates and normalizes; a fork composes its own onto
   its parent's, so it can narrow authority and never widen it."
  (:require [clojure.set]
            [clojure.string :as str]
            [dvergr.authority :as authority]
            [malli.core :as m]
            [org.replikativ.spindel.engine.core :as ec])
  (:import [java.security MessageDigest]))

;; ---------------------------------------------------------------------------
;; Signature
;; ---------------------------------------------------------------------------

(def vocabulary
  "Operations dvergr defines: the classes each touches (`:read :write :network
   :egress :spend :process :lifecycle :schedule :global`), and the shapes of
   its resource and result. Closed: an unknown operation is a programming error
   in the capability, not a denial. Events (`:class #{}`) are recorded inside
   an effect, not decided."
  (let [path [:map [:path :string]]
        pair [:map [:src :string] [:dst :string]]]
    {:http/request {:class #{:network}
                    :resource [:map [:method :keyword] [:url [:maybe :string]]]
                    :result [:map [:status [:maybe :int]] [:headers :map] [:body :any]]}
     :fs/read   {:class #{:read}  :resource path :result :string}
     :fs/list   {:class #{:read}  :resource [:map [:path :string] [:glob {:optional true} :string]]
                 :result [:vector :string]}
     :fs/stat   {:class #{:read}  :resource path :result :any}
     :fs/write  {:class #{:write} :resource path :result :string}
     :fs/mkdir  {:class #{:write} :resource path :result [:maybe :string]}
     :fs/delete {:class #{:write} :resource path :result :any}
     :fs/move   {:class #{:write} :resource pair :result [:maybe :string]}
     :fs/copy   {:class #{:write} :resource pair :result [:maybe :string]}
     :git/read  {:class #{:read}  :resource [:map [:op :keyword]] :result :any}
     :git/add   {:class #{:write} :resource [:map [:paths [:vector :any]]] :result [:= :ok]}
     :git/commit {:class #{:write} :resource [:map [:message :string]] :result :string}
     ;; rooms: the op's room (its id or slug as given)
     :room/read    {:class #{:read} :resource :map :result :any}
     :room/post    {:class #{:write} :resource [:map [:room :string]] :result :any}
     :room/write   {:class #{:write} :resource :map :result :any}
     :room/join    {:class #{:write} :resource [:map [:room :string] [:who :string]] :result :any}
     :room/create  {:class #{:lifecycle} :resource [:map [:slug :string]] :result :any}
     :room/fork    {:class #{:lifecycle} :resource [:map [:room :string]] :result :any}
     :room/merge   {:class #{:write :lifecycle} :resource [:map [:room :string] [:fork :string]] :result :any}
     :room/discard {:class #{:lifecycle} :resource [:map [:room :string]] :result :any}
     :room/delete  {:class #{:lifecycle} :resource [:map [:room :string]] :result :any}
     :model/call        {:class #{:spend :network} :resource [:map [:model :string]] :result :any}
     :process/run       {:class #{:process} :resource [:map [:cmd :string]] :result :any}
     :process/directive {:class #{:process} :resource :map :result :any}
     :schedule/create   {:class #{:schedule} :resource :map :result :any}
     :schedule/cancel   {:class #{:schedule} :resource :map :result :any}
     :db/transact       {:class #{:write} :resource [:map [:datoms :int]] :result :any}
     :db/create         {:class #{:lifecycle} :resource [:map [:name :string]] :result :any}
     :db/delete         {:class #{:lifecycle} :resource [:map [:name :string]] :result :any}
     :eval/run          {:class #{} :resource [:map [:cpu-ms :int] [:wall-ms :int]]}
     :http/secret-injected {:class #{} :resource :map}
     :http/secret-denied   {:class #{} :resource :map}}))

(defn operation [kind]
  (or (get vocabulary kind)
      (throw (ex-info (str "Unknown effect kind " kind) {:effect kind}))))

(defn effect-classes
  "The classes of `effect`: its operation's, plus any the capability adds for
   this request (an HTTP request that sends data is also `:egress`)."
  [{kind :effect extra :class}]
  (into (:class (operation kind)) extra))

(defn valid-result?
  "Whether `value` has the shape `kind` returns: what a replayed, injected or
   stand-in answer must satisfy."
  [kind value]
  (m/validate (:result (operation kind) :any) value))

;; ---------------------------------------------------------------------------
;; Receipts
;; ---------------------------------------------------------------------------

(def ^:private receipt-cap
  "Receipts kept in memory per sink; older ones are dropped. Durable receipts
   come with replay."
  2000)

(defn make-sink
  "A receipt sink: an atom holding the most recent receipts, newest last."
  []
  (atom []))

(defn- record! [sink receipt]
  (when sink
    (swap! sink (fn [rs]
                  (let [rs (conj rs receipt)]
                    (if (> (count rs) receipt-cap)
                      (subvec rs (- (count rs) receipt-cap))
                      rs)))))
  receipt)

(defn digest
  "A short content digest of an effect's result: reads are recorded by what
   they returned, without the body."
  [x]
  (when (some? x)
    (let [bytes (.getBytes ^String (if (string? x) x (pr-str x)) "UTF-8")
          md (MessageDigest/getInstance "SHA-256")]
      (apply str (map #(format "%02x" %) (take 12 (.digest md bytes)))))))

(def ^:private ^:dynamic *answered-by*
  "Per `perform!`: which handler produced the value (`:world` unless one
   answered without forwarding)."
  nil)

(defn answer
  "For a handler that answers instead of forwarding: record who answered and
   return `value`."
  [by value]
  (some-> *answered-by* (vreset! by))
  value)

(defn deny!
  "For a handler that refuses: throw the denial `perform!` receipts."
  [effect by reason]
  (throw (ex-info (str "Effect denied (" (name (:effect effect)) "): " reason)
                  {:type :effect/denied :effect (:effect effect) :by by})))

(defn receipts
  "The outermost handler: records every effect with its decision, who decided
   or answered, timing and a digest of the result."
  [sink subject]
  (fn [effect next]
    (let [base (cond-> (assoc (select-keys effect [:effect :resource])
                              :class (effect-classes effect)
                              :at (java.util.Date.))
                 subject (assoc :subject subject))
          t0 (System/nanoTime)
          ms #(quot (- (System/nanoTime) t0) 1000000)]
      (try
        (let [v (next effect)]
          (record! sink (assoc base :decision :allowed :by @*answered-by* :ms (ms)
                               :digest (digest ((or (:result-of effect) identity) v))))
          v)
        (catch clojure.lang.ExceptionInfo e
          (let [{:keys [type by]} (ex-data e)]
            (record! sink (if (= :effect/denied type)
                            (assoc base :decision :denied :by by)
                            (assoc base :decision :allowed :by @*answered-by* :ms (ms)
                                   :error (.getName (class e))))))
          (throw e))
        (catch Throwable t
          (record! sink (assoc base :decision :allowed :by @*answered-by* :ms (ms)
                               :error (.getName (class t))))
          (throw t))))))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(def all-classes
  #{:read :write :network :egress :spend :process :lifecycle :schedule :global})

(defn admission
  "The filter: refuse effects with a class outside `admit`. `admit` of
   `#{:read}` is read-only."
  [admit]
  (let [admit (set admit)]
    (fn [effect next]
      (let [missing (remove admit (effect-classes effect))]
        (cond
          (empty? missing) (next effect)
          (= admit #{:read}) (deny! effect :read-only "read-only: this effect writes or reaches out")
          :else (deny! effect :admission (str "not granted: " (vec missing))))))))

(def ^:private sugar
  "Specs that are names for a filter."
  {:read-only #{:read}})

(defn- authority-handler
  "The filter asking `dvergr.authority/decide` for room effects: `ctx` holds
   the subject and a function returning the current relations."
  [{:keys [subject relations]}]
  (fn [effect next]
    (let [failed (authority/decide (if relations (relations) {}) subject effect)]
      (if (seq failed)
        (deny! effect :authority
               (str (some-> (:agent subject) name) " may not "
                    (str/join ", " (map (fn [[action ref]] (str (name action) " " ref)) failed))
                    " (its own room, rooms beneath it and rooms it takes part in; else a grant)"))
        (next effect)))))

(def ^:private predicate-filters
  "Filters that are predicates over the subject and resource rather than
   class sets. Several of the same compose to one (idempotent)."
  {:authority authority-handler})

;; ---------------------------------------------------------------------------
;; Answering handlers: record, replay, faults
;;
;; Their configuration is data (`[:replay {:id …}]`); the state a run needs (a
;; recording, a replay cursor, fault counters) is host-side, keyed by that id,
;; and created by the host before the run (`recording!`, `replay!`, `faults!`).
;; Sandbox code can reach none of it.
;; ---------------------------------------------------------------------------

(defonce ^:private states (atom {}))

(defn- state [id]
  (or (get @states id)
      (throw (ex-info (str "No effect handler state " id) {:id id}))))

(defn release!
  "Drop the host-side state of a recording, replay or fault injection."
  [id]
  (swap! states dissoc id)
  nil)

(defn effect-key
  "What identifies an effect for replay and faults: its operation and
   resource, plus `:key` when the capability gives a finer one."
  [{kind :effect :keys [resource key]}]
  (cond-> [kind resource] key (conj key)))

(defn recording!
  "Start a recording; `[:record {:id id}]` appends every effect it sees (its
   key and result, or the error it raised). Returns the id."
  []
  (let [id (random-uuid)]
    (swap! states assoc id (atom []))
    id))

(defn recorded
  "The entries of a recording, in order: `{:key :value}` or `{:key :error}`."
  [id]
  @(state id))

(defn replay!
  "Start replaying `entries` (from `recorded`); `[:replay {:id id}]` answers
   each effect with the next entry for its key, in order, and never reaches
   the world. Returns the id."
  [entries]
  (let [id (random-uuid)]
    (swap! states assoc id (atom (reduce (fn [qs e] (update qs (:key e) (fnil conj []) e))
                                         {} entries)))
    id))

(defn faults!
  "Start a seeded fault injection: `rate` (0..1) of the effects whose kind is in
   `only` (all when nil) fail, each with a fault drawn from `kinds` (`:error`,
   `:timeout`, and for HTTP `:rate-limit`, `:server-error`). The n-th occurrence
   of an effect key faults as a function of `(seed, key, n)` alone, so a faulty
   run is reproducible. `[:faults {:id id}]`. Returns the id."
  [{:keys [seed rate only kinds] :or {seed 0 rate 0.1 kinds [:error :timeout]}}]
  (let [id (random-uuid)]
    (swap! states assoc id {:seed seed :rate rate :only (some-> only set) :kinds (vec kinds)
                            :counts (atom {})})
    id))

(defn- record-handler [_ctx {:keys [id]}]
  (fn [effect next]
    (let [log (state id)
          k (effect-key effect)]
      (try
        (let [v (next effect)]
          (swap! log conj {:key k :value v})
          v)
        (catch clojure.lang.ExceptionInfo e
          ;; a refusal is the stack's own decision, not the world's answer
          (when-not (= :effect/denied (:type (ex-data e)))
            (swap! log conj {:key k :error (.getMessage e)}))
          (throw e))
        (catch Throwable t
          (swap! log conj {:key k :error (str (.getName (class t)) ": " (.getMessage t))})
          (throw t))))))

(defn- replay-handler [_ctx {:keys [id]}]
  (fn [effect _next]
    (let [queues (state id)
          k (effect-key effect)
          [entry] (get @queues k)]
      (when-not entry
        (throw (ex-info (str "Replay diverged: nothing recorded for " (pr-str k))
                        {:type :effect/replay-divergence :key k})))
      (swap! queues update k subvec 1)
      (if (contains? entry :error)
        (do (answer :replay nil)
            (throw (ex-info (str "Replayed error: " (:error entry))
                            {:type :effect/replayed-error :key k})))
        (answer :replay (:value entry))))))

(defn- fault-for
  "The fault the n-th occurrence of `k` gets under `cfg`, or nil."
  [{:keys [seed rate kinds]} k n]
  (let [rng (java.util.Random. (hash [seed k n]))]
    (when (< (.nextDouble rng) rate)
      (nth kinds (.nextInt rng (count kinds))))))

(defn- faults-handler [_ctx {:keys [id]}]
  (fn [effect next]
    (let [{:keys [only counts] :as cfg} (state id)
          kind (:effect effect)
          k (effect-key effect)]
      (if (and only (not (contains? only kind)))
        (next effect)
        (let [n (get (swap! counts update k (fnil inc -1)) k)
              fault (fault-for cfg k n)
              http? (= :http/request kind)]
          (case (if (and (#{:rate-limit :server-error} fault) (not http?)) :error fault)
            nil (next effect)
            :rate-limit (answer :faults {:status 429 :headers {"retry-after" "1"} :body "rate limited"})
            :server-error (answer :faults {:status 503 :headers {} :body "service unavailable"})
            :timeout (do (answer :faults nil)
                         (throw (ex-info (str "Injected fault: " (name kind) " timed out")
                                         {:type :effect/fault :fault :timeout})))
            (do (answer :faults nil)
                (throw (ex-info (str "Injected fault: " (name kind) " failed")
                                {:type :effect/fault :fault :error})))))))))

(def registry
  "Answering handlers by spec key, each `(fn [ctx & args])`. Filters are not
   here: `normalize` folds class filters into one `:admit` and keeps each
   predicate filter once. Answering handlers do not commute: `[[:record r]
   [:faults f]]` records the injected faults, `[[:faults f] [:record r]]` only
   what reached the world."
  {:record record-handler
   :replay replay-handler
   :faults faults-handler})

(defn normalize
  "The canonical form of a handler configuration: one `[:admit S]` (the
   intersection of every class filter; omitted when it admits every class),
   the predicate filters (each once, in a fixed order), then the answering
   handlers in the order given."
  [specs]
  (let [class-filter? #(or (= :admit (first %)) (contains? sugar (first %)))
        predicate? #(contains? predicate-filters (first %))
        admit (reduce (fn [acc [k s]] (clojure.set/intersection acc (or (sugar k) (set s))))
                      all-classes (filter class-filter? specs))]
    (cond-> []
      (not= admit all-classes) (conj [:admit admit])
      :always (into (sort-by pr-str (distinct (filter predicate? specs))))
      :always (into (remove #(or (class-filter? %) (predicate? %)) specs)))))

(defn compose
  "Compose configurations: `outer`'s handlers enclose `inner`'s (a fork's
   stack is `(compose parent own)`). Filters meet, so the result never admits
   what either refused."
  [outer inner]
  (normalize (concat outer inner)))

(defn handlers
  "Build the handler stack from a configuration, outermost first. `ctx` is
   what the runtime knows at the call (the subject, the relations)."
  ([specs] (handlers specs {}))
  ([specs ctx]
   (mapv (fn [[k & args]]
           (cond
             (= :admit k) (admission (first args))
             (contains? predicate-filters k) ((predicate-filters k) ctx)
             :else (apply (or (get registry k)
                              (throw (ex-info (str "Unknown effect handler " k) {:handler k})))
                          ctx args)))
         (normalize specs))))

;; ---------------------------------------------------------------------------
;; perform
;; ---------------------------------------------------------------------------

(defn perform!
  "Perform `effect` through the stack `boundary-fn` returns (`{:handlers
   [...]}`; nil ⇒ straight to the world, no receipts). `executor` is the
   world: a function of no arguments."
  [boundary-fn effect executor]
  (let [stack (:handlers (when boundary-fn (boundary-fn)))
        world (fn [_] (executor))
        run (reduce (fn [next h] (fn [e] (h e next))) world (reverse stack))]
    (effect-classes effect)                          ; the vocabulary is closed
    (binding [*answered-by* (volatile! :world)]
      (run effect))))

(defn note!
  "Record an event inside an effect (not a decision)."
  [boundary-fn kind resource]
  (let [{:keys [sink subject]} (when boundary-fn (boundary-fn))]
    (effect-classes {:effect kind})
    (record! sink (cond-> {:effect kind :class #{} :resource resource
                           :at (java.util.Date.) :decision :noted}
                    subject (assoc :subject subject)))))

(defn full-reach?
  "An MCP connection (the owner's, within its selection) and host code keep
   their reach; an agent's cross-room effects are decided by `can?`."
  [agent-id]
  (or (nil? agent-id) (= "mcp" (namespace agent-id))))

;; ---------------------------------------------------------------------------
;; World configuration: handlers every sandbox in a world runs under
;; ---------------------------------------------------------------------------

(def ^:private world-path
  "Where a world (a Room's execution context) keeps its handler configuration.
   It is context state, so a fork inherits its parent's and may narrow it or
   add answering handlers of its own."
  [:dvergr/effects :handlers])

(defn install-world!
  "Compose `specs` onto the handlers of the world `ctx`: every sandbox that
   runs there, an agent's or an MCP connection's, performs under them."
  [ctx specs]
  (binding [ec/*execution-context* ctx]
    (ec/swap-state! world-path #(compose % specs))))

(defn world-handlers
  "The handler configuration of the world `ctx`."
  [ctx]
  (binding [ec/*execution-context* ctx]
    (ec/get-state world-path)))

(defn set-world-recording!
  "Name `id` as the recording of the world `ctx`, for host code that reads
   what the world did (an evaluator's capture)."
  [ctx id]
  (binding [ec/*execution-context* ctx]
    (ec/swap-state! [:dvergr/effects :recording] (constantly id))))

(defn world-recording
  "The entries recorded in the world `ctx` (`recorded`), or nil."
  [ctx]
  (when-let [id (binding [ec/*execution-context* ctx] (ec/get-state [:dvergr/effects :recording]))]
    (try (recorded id) (catch clojure.lang.ExceptionInfo _ nil))))

(defn fetched-pages
  "The pages a recording shows fetched: `{url body}` of its successful GET
   requests with a text body."
  [entries]
  (into {} (for [{[kind resource] :key value :value} entries
                 :when (and (= :http/request kind) (= :get (:method resource))
                            (string? (:body value)) (< (or (:status value) 500) 400))]
             [(:url resource) (:body value)])))

(defn environment-handlers!
  "Host-side: the handler configuration an environment's `:world :effects`
   asks for, with the state it needs created. Portable data in, `{:specs
   [...] :release (fn [])}` out:

     {:faults {:seed 7 :rate 0.1 :only #{:http/request} :kinds [:rate-limit]}
      :record true           ; record every effect (`:recording` id returned)
      :read-only true
      :admit #{:read :network}}"
  [{:keys [faults record read-only admit]}]
  (let [f (when faults (faults! faults))
        r (when record (recording!))]
    {:specs (cond-> []
              admit (conj [:admit (set admit)])
              read-only (conj [:read-only])
              r (conj [:record {:id r}])
              f (conj [:faults {:id f}]))
     :recording r
     :release #(do (some-> f release!) (some-> r release!))}))

(defn boundary-resolver
  "The boundary for a sandbox. `binding-resolver` reads the capability's world
   binding, where the runtime set the acting identity and `:effects {:handlers
   specs}`; `sink` holds the receipts. Options: `:world`, a function returning
   the world's handler configuration (outside the binding's), and
   `:relations`, returning the room relations for the authority filter the
   runtime adds for every agent. Any may be nil."
  ([binding-resolver sink] (boundary-resolver binding-resolver sink {}))
  ([binding-resolver sink {:keys [relations world]}]
   (fn []
     (let [b (when binding-resolver (binding-resolver))
           agent-id (:agent-id b)
           subject (when agent-id {:agent agent-id :room (:room-runtime-id b)})
           specs (cond-> (compose (when world (world)) (get-in b [:effects :handlers]))
                   (not (full-reach? agent-id)) (conj [:authority]))]
       {:sink sink
        :subject subject
        :handlers (into [(receipts sink subject)]
                        (handlers specs {:subject subject :relations relations}))}))))
