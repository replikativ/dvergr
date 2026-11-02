(ns dvergr.catalog.checker
  "Caller-owned, bounded SCI checking. No scheduler, executor or authority.

   Call on an owned blocking worker. Deadlines/allocation guards are
   cooperative: a blocking host operation stays owned until it actually exits.
   This function never returns a cancellation acknowledgement in place of exit."
  (:require [dvergr.sandbox :as sandbox]
            [hasch.core :as hasch]
            [org.replikativ.spindel.engine.impl.simple :as simple]
            [sci.core :as sci])
  (:import [java.math BigDecimal BigInteger]
           [java.util Date]
           [java.util.concurrent.atomic AtomicBoolean]))

(def policy
  "Default host policy. Bounds are part of evaluator/promotion identity."
  {:version 1 :timeout-ms 20000 :max-allocated-bytes 134217728
   :max-source-bytes 524288 :min-checks 0 :max-checks 1000
   :max-check-key-length 256 :max-output-nodes 10000 :max-depth 32
   :max-output-bytes 2097152 :max-string-bytes 262144
   :max-numeric-digits 1024 :max-numeric-scale 100000
   :max-judge-requests 50 :max-judge-id-length 256 :max-judge-prompt-bytes 65536})

(def policy-id (hasch/uuid [::policy policy]))

(defn- refuse! [reason message]
  (throw (ex-info message {:type ::invalid :reason reason})))

(defn- options! [options]
  (when-not (and (map? options)
                 (every? (into (set (keys policy)) #{:deadline-nanos :allocation-check :prepare-input})
                         (keys options)))
    (refuse! :options "Unknown checker execution option"))
  (let [p (merge policy options)]
    (when-not (and (= (:version policy) (:version p))
                   (every? #(and (integer? (get p %)) (pos? (get p %)))
                           (remove #{:version :min-checks} (keys policy)))
                   (integer? (:min-checks p)) (<= 0 (:min-checks p) (:max-checks p))
                   (or (nil? (:deadline-nanos p)) (integer? (:deadline-nanos p)))
                   (or (nil? (:allocation-check p)) (fn? (:allocation-check p)))
                   (or (nil? (:prepare-input p)) (fn? (:prepare-input p))))
      (refuse! :options "Checker policy needs positive integer bounds and valid host hooks"))
    p))

(defn- source! [source {:keys [max-source-bytes]}]
  (when-not (and (string? source) (<= (count source) max-source-bytes)
                 (<= (alength (.getBytes ^String source "UTF-8")) max-source-bytes))
    (refuse! :source "Checker source exceeds its UTF-8 byte limit")))

(defn- numeric-size! [value {:keys [max-numeric-digits max-numeric-scale] :as p}]
  ;; Prebound before printing: even a finite decimal/ratio can carry millions
  ;; of digits. Only known immutable numeric implementations are admitted.
  (let [bits (* 4 max-numeric-digits)]
    (cond
      (instance? BigDecimal value)
      (when (or (> (.precision ^BigDecimal value) max-numeric-digits)
                (> (Math/abs (long (.scale ^BigDecimal value))) max-numeric-scale))
        (refuse! :output "Checker numeric representation exceeds its precision/scale limit"))
      (instance? BigInteger value)
      (when (> (.bitLength ^BigInteger value) bits)
        (refuse! :output "Checker integer exceeds its bit-length limit"))
      (instance? clojure.lang.BigInt value)
      (numeric-size! (.toBigInteger ^clojure.lang.BigInt value) p)
      (ratio? value)
      (do (numeric-size! (numerator value) p) (numeric-size! (denominator value) p))
      (or (instance? Long value) (instance? Integer value) (instance? Short value)
          (instance? Byte value) (instance? Double value) (instance? Float value)) nil
      :else (refuse! :output "Unsupported checker numeric representation"))))

(defn- bounded-value!
  "Inspect eager portable output only, charging a conservative encoded EDN
   byte bound including punctuation, scalar values and string escaping."
  [value {:keys [max-output-nodes max-depth max-output-bytes max-string-bytes] :as p} guard!]
  (let [nodes (volatile! 0) bytes (volatile! 0)
        charge! (fn [n]
                  (when (> (vswap! bytes + n) max-output-bytes)
                    (refuse! :output "Checker output exceeds its encoded byte limit")))
        text! (fn [s quoted?]
                (when (> (count s) max-string-bytes)
                  (refuse! :output "Checker text exceeds its byte limit"))
                (let [raw (alength (.getBytes ^String s "UTF-8"))
                      n (if quoted?
                          (+ 2 raw (reduce (fn [extra c]
                                             (+ extra (cond
                                                        (or (= c \\) (= c \")) 1
                                                        (Character/isISOControl ^char c) 5
                                                        :else 0))) 0 s))
                          raw)]
                  (when (> n max-string-bytes)
                    (refuse! :output "Checker text exceeds its encoded byte limit"))
                  (charge! n)))]
    (letfn [(walk! [x depth]
              (guard!)
              (when (or (> depth max-depth) (> (vswap! nodes inc) max-output-nodes))
                (refuse! :output "Checker output exceeds its structural limit"))
              (cond
                (nil? x) (do (charge! 3) nil)
                (boolean? x) (do (charge! (if x 4 5)) x)
                (number? x) (do (numeric-size! x p)
                                (when-not (Double/isFinite (double x))
                                  (refuse! :output "Checker output contains a non-finite number"))
                                (text! (pr-str x) false) x)
                (string? x) (do (text! x true) x)
                (keyword? x) (do (text! (str x) false) x)
                (symbol? x) (do (text! (str x) false) (with-meta x nil))
                (uuid? x) (do (charge! 44) x)
                (inst? x) (do (charge! 64) (Date. (inst-ms x)))
                (or (map? x) (vector? x) (set? x) (list? x))
                (do
                  (when (> (count x) max-output-nodes)
                    (refuse! :output "Checker collection exceeds its node limit"))
                  (charge! (+ 3 (* (if (map? x) 3 1) (count x))))
                  ;; Return plain snapshots, never hidden metadata retaining
                  ;; closures, unbounded values or interpreter/world state.
                  (cond
                    (map? x) (into {} (map (fn [[k v]] [(walk! k (inc depth)) (walk! v (inc depth))])) x)
                    (vector? x) (mapv #(walk! % (inc depth)) x)
                    (set? x) (into #{} (map #(walk! % (inc depth))) x)
                    :else (apply list (map #(walk! % (inc depth)) x))))
                :else (refuse! :output "Checker output must contain only bounded eager portable data")))]
      (walk! value 0))))

(defn- verdict! [value {:keys [min-checks max-checks max-check-key-length] :as p} guard!]
  (let [checks (:checks value) reward (:reward value)]
    (when (number? reward) (numeric-size! reward p))
    ;; Shape/count first: neither error reporting nor validation may traverse
    ;; a lazy checker return. The open verdict map retains bounded extensions.
    (when-not (and (map? value) (map? checks)
                   (<= min-checks (count checks) max-checks)
                   (every? (fn [[k v]]
                             (and (keyword? k) (<= (count (str k)) max-check-key-length) (boolean? v))) checks)
                   (number? reward) (Double/isFinite (double reward))
                   (<= 0.0 (double reward) 1.0))
      (refuse! :verdict "The checker returned no verdict: expected bounded boolean checks and finite reward in [0,1]"))
    (bounded-value! (assoc value :reward (double reward)) p guard!)))

(defn- requests! [value {:keys [max-judge-requests max-judge-id-length max-judge-prompt-bytes] :as p} guard!]
  ;; Present nil historically means no requests. Other non-vectors, including
  ;; lazy or infinite sequences, are refused before realization.
  (let [value (if (nil? value) [] value)]
    (when-not (and (vector? value) (<= (count value) max-judge-requests))
      (refuse! :judge-requests "judge-requests must return a bounded eager vector"))
    (let [value (bounded-value! value p guard!)]
      (when-not (and (every? (fn [request]
                               (and (map? request)
                                    (string? (:id request)) (<= 1 (count (:id request)) max-judge-id-length)
                                    (string? (:prompt request))
                                    (<= (alength (.getBytes ^String (:prompt request) "UTF-8")) max-judge-prompt-bytes))) value)
                     (= (count value) (count (set (map :id value)))))
        (refuse! :judge-requests "judge-requests needs unique bounded IDs and bounded string prompts"))
      value)))

(defn- invoke-on-worker! [source fname input p validate]
  (let [deadline (or (:deadline-nanos p) (+ (System/nanoTime) (* 1000000 (long (:timeout-ms p)))))
        stopped (AtomicBoolean. false)
        allocation-check (or (:allocation-check p)
                             (sandbox/make-resource-limits :max-bytes (:max-allocated-bytes p)))
        guard! (fn []
                 (when (or (.get stopped) (.isInterrupted (Thread/currentThread))
                           (<= deadline (System/nanoTime)))
                   (.set stopped true)
                   (refuse! :interrupted "Checker deadline or interruption reached"))
                 (try (allocation-check)
                      (catch Exception e (.set stopped true) (throw e))))]
    (guard!)
    (source! source p)
    (let [input (if-let [prepare (:prepare-input p)] (prepare guard! input) input)
          _ (guard!)
          ;; Both the context and its guard originate on the actual worker.
          ctx (sandbox/create-base-ctx :load-fn (constantly nil) :resource-limits guard!)
          {checker-ns :ns} (sci/eval-string+ ctx source)
          _ (guard!)
          f (sci/eval-string* ctx (str "(ns-resolve '" checker-ns " '" fname ")"))
          _ (guard!)]
      (if-not f
        {:present? false}
        (let [value (@f input)
              _ (guard!)
              result (validate value p guard!)]
          (guard!)
          {:present? true :value result})))))

(defn- invoke! [source fname input options validate]
  (when (simple/on-drain-thread?)
    (refuse! :engine-drain "Checker execution must run off the engine drain"))
  (let [p (options! options)
        run #(invoke-on-worker! source fname input p validate)]
    ;; A shared guard belongs to the caller's surrounding allocation extent.
    ;; Do not reset that extent between calibration reference/damaged answers.
    (if (:allocation-check p) (run) (sandbox/call-with-allocation-scope run))))

(defn run-checker
  "Check source/input on the CALLING owned worker. No effects or load path.
   Options are HOST policy, not SCI authority. :allocation-check and an
   absolute :deadline-nanos retain a caller-owned aggregate calibration scope;
   :prepare-input runs inside it and receives guard!/input. Returns a bounded
   eager verdict, preserving portable extension fields and normalizing reward.
   Cooperative limits cannot stop an uncooperative host call before it exits."
  ([source input] (run-checker source input {}))
  ([source input options]
   (let [result (invoke! source "check" input options verdict!)]
     (when-not (:present? result)
       (refuse! :missing-check "checker.clj defines no `check`"))
     (:value result))))

(defn judge-requests
  "Read bounded eager judge requests on the caller's owned worker. Missing
   function returns nil; present nil returns []. Validate the ENTIRE bounded
   vector before normal max-requests truncation, including its trailing items."
  ([source input max-requests] (judge-requests source input max-requests {}))
  ([source input max-requests options]
   (let [p (options! options)]
     (when-not (and (integer? max-requests) (<= 1 max-requests (:max-judge-requests p)))
       (refuse! :options "max-requests must fit the host judge request ceiling"))
     (let [result (invoke! source "judge-requests" input p requests!)]
       (when (:present? result) (subvec (:value result) 0 (min max-requests (count (:value result)))))))))
