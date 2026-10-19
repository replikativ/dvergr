(ns dvergr.agent.sci-program
  "Bounded portable source programs, evaluated only on their Run's worker.
   This host namespace is never injected into SCI."
  (:require [clojure.string :as str]
            [dvergr.sandbox :as sandbox]
            [sci.core :as sci]
            [sci.ctx-store :as ctx-store])
  (:import [java.io Writer]
           [java.nio.charset StandardCharsets]))

(def max-source-bytes 65536)
(def max-value-bytes 262144)
(def max-value-nodes 10000)
(def max-value-depth 64)
(def max-diagnostic-bytes 16384)

(defn- invalid! [message]
  (throw (ex-info message {:type ::invalid})))

(defn- byte-count [^String s]
  (alength (.getBytes s StandardCharsets/UTF_8)))

(defn portable-value!
  "Copy a finite data graph without metadata. Lazy results are realized only
   when requested, inside the caller's SCI store/worker. Opaque values fail."
  ([value] (portable-value! value false (fn [])))
  ([value realize-seqs? check!]
   (let [nodes (volatile! 0)
         bytes (volatile! 0)
         account! (fn [n]
                    (when (> (vswap! bytes + n) max-value-bytes)
                      (invalid! "SCI value exceeds its encoded byte bound")))]
     (letfn [(walk [x depth]
               (check!)
               (when (or (> depth max-value-depth)
                         (> (vswap! nodes inc) max-value-nodes))
                 (invalid! "SCI value exceeds its depth/node bound"))
               (cond
                 (record? x) (invalid! "SCI programs return data, not records or live capabilities")
                 (or (nil? x) (boolean? x) (keyword? x) (symbol? x)
                     (integer? x) (float? x) (ratio? x) (decimal? x)
                     (uuid? x) (string? x))
                 (do
                   (when (and (string? x) (> (count x) max-value-bytes))
                     (invalid! "SCI string exceeds its byte bound"))
                   (account! (+ 1 (byte-count (pr-str x))))
                   (if (symbol? x) (with-meta x nil) x))
                 (map? x)
                 (do (account! 2)
                     (into {} (map (fn [[k v]]
                                     ;; EDN maps also print comma/space separators.
                                     (account! 2)
                                     [(walk k (inc depth)) (walk v (inc depth))])) x))
                 (set? x)
                 (do (account! 3) (into #{} (map #(walk % (inc depth))) x))
                 (or (vector? x) (list? x) (and realize-seqs? (seq? x)))
                 (do (account! 2)
                     (let [xs (into [] (map #(walk % (inc depth))) x)]
                       (if (list? x) (apply list xs) xs)))
                 :else (invalid! "SCI programs require portable data, not pending computations or live capabilities")))]
       (walk value 0)))))

(defn validate!
  [{:keys [source entrypoint args timeout-ms] :as program}]
  (when-not (and (= :sci (:kind program))
                 (every? #{:kind :source :entrypoint :args :timeout-ms} (keys program))
                 (string? source) (<= 1 (count source) max-source-bytes)
                 (not (str/blank? source)) (<= (byte-count source) max-source-bytes)
                 (qualified-symbol? entrypoint)
                 (integer? timeout-ms) (<= 1 timeout-ms 600000)
                 (or (not (contains? program :args))
                     (and (vector? args) (<= (count args) 32))))
    (invalid! "Invalid SCI program: bounded source, qualified entrypoint, portable args vector and timeout-ms (1–600000) required"))
  (portable-value! (or args []))
  program)

(defn- diagnostic-writer [failure]
  (let [buffer (StringBuilder.)
        used (volatile! 0)
        append! (fn [text]
                  (when (> (vswap! used + (byte-count text)) max-diagnostic-bytes)
                    (let [e (ex-info "SCI diagnostic output exceeds its byte bound" {:type ::diagnostic-limit})]
                      (compare-and-set! failure nil e)
                      (throw e)))
                  (.append buffer ^String text))]
    {:text #(str buffer)
     :writer
     (proxy [Writer] []
       (write
         ([chunk] (append! (cond (string? chunk) chunk
                                 (integer? chunk) (str (char chunk))
                                 :else (String. ^chars chunk))))
         ([chunk offset length]
          (append! (if (string? chunk)
                     (subs chunk offset (+ offset length))
                     (String. ^chars chunk (int offset) (int length))))))
       (flush [])
       (close []))}))

(defn execute!
  "Run in the caller's supervised worker and fork-local SCI interpreter.
   check-run! is sticky host cancellation/deadline authority, not SCI input."
  [interpreter {:keys [source entrypoint args] :as program} task check-run!]
  (validate! program)
  (let [failure (atom nil)
        memory-check! (sandbox/make-resource-limits)
        check! #(do (when-let [e @failure] (throw e)) (check-run!) (memory-check!))
        out (diagnostic-writer failure)
        err (diagnostic-writer failure)
        interpreter (sci/merge-opts interpreter {:interrupt-fn check!})
        task (portable-value! task)
        args (portable-value! (or args []))
        ;; Inputs are data, never source text. Reader-delimiter symbols are
        ;; legal Clojure values; pr-str/reparse is NOT a safe quoting boundary.
        invocation (list 'clojure.core/apply (with-meta entrypoint nil)
                         (list 'quote task) (list 'quote args))]
    (ctx-store/with-ctx interpreter
      (sci/binding [sci/out (:writer out) sci/err (:writer err)]
        (check!)
        (sci/eval-string* interpreter source)
        (check!)
        (let [value (sci/eval-form interpreter invocation)
              value (portable-value! value true check!)]
          (check!)
          {:value value :stdout ((:text out)) :stderr ((:text err))})))))
