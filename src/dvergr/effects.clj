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
            [malli.core :as m])
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

(def registry
  "Answering handlers by spec key (replay, faults). Filters are not here:
   `normalize` folds them into one `:admit`."
  {})

(defn normalize
  "The canonical form of a handler configuration: one `[:admit S]` (the
   intersection of every filter; omitted when it admits every class), then the
   answering handlers in the order given."
  [specs]
  (let [filter? #(or (= :admit (first %)) (contains? sugar (first %)))
        admit (reduce (fn [acc [k s]] (clojure.set/intersection acc (or (sugar k) (set s))))
                      all-classes (filter filter? specs))]
    (cond-> []
      (not= admit all-classes) (conj [:admit admit])
      :always (into (remove filter? specs)))))

(defn compose
  "Compose configurations: `outer`'s handlers enclose `inner`'s (a fork's
   stack is `(compose parent own)`). Filters meet, so the result never admits
   what either refused."
  [outer inner]
  (normalize (concat outer inner)))

(defn handlers
  "Build the handler stack from a configuration, outermost first."
  [specs]
  (mapv (fn [[k & args]]
          (if (= :admit k)
            (admission (first args))
            (apply (or (get registry k)
                       (throw (ex-info (str "Unknown effect handler " k) {:handler k})))
                   args)))
        (normalize specs)))

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

(defn boundary-resolver
  "The boundary for a sandbox. `binding-resolver` reads the world binding,
   where the runtime set the acting identity and `:effects {:handlers specs}`;
   `sink` holds the receipts. Either may be nil."
  [binding-resolver sink]
  (fn []
    (let [b (when binding-resolver (binding-resolver))
          subject (when (:agent-id b)
                    {:agent (:agent-id b) :room (:room-runtime-id b)})]
      {:sink sink
       :subject subject
       :handlers (into [(receipts sink subject)]
                       (handlers (get-in b [:effects :handlers])))})))
