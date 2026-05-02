(ns dvergr.simulations.sandbox
  "SCI sandbox environment for simulation execution.

   Each simulation runs in an isolated SCI context with:
   - intake.*   — read-only external data (hn, reddit, web, etc.)
   - dh/*       — datahike bound to the simulation's OWN dedicated DB
   - llm/*      — cheap LLM calls for summarization
   - spindel.*  — reactive composition (spin, await, combinators)
   - calendar/* — calendar queries

   NOT available (isolation boundary):
   - File I/O (no file namespace)
   - Shell access
   - Main DB writes
   - Network beyond intake functions"
  (:require [sci.core :as sci]
            [datahike.api :as dh]
            [dvergr.sandbox :as sandbox]
            [org.replikativ.spindel.engine.core :as rtc]
            [taoensso.telemere :as tel]))

(defn create-simulation-sci-ctx
  "Create an isolated SCI context for running simulation code.

   Args:
     sim-conn     - Datahike connection for the simulation's dedicated DB
     spindel-ctx  - Spindel execution context (for reactive primitives)
     main-conn    - Main Datahike connection (optional, for calendar)

   Returns an SCI context where:
   - `*db*` is bound to the simulation's dedicated DB connection
   - Intake namespaces are available for data gathering
   - LLM namespace is available for summarization
   - Datahike read/write goes to the simulation's own DB (not main DB)
   - No file I/O or shell access"
  [sim-conn spindel-ctx & {:keys [main-conn]}]
  (let [;; Create a spindel-backed SCI context (gives spin/await/track)
        sci-ctx (sandbox/fork-for-session spindel-ctx)
        ;; Add Java classes that the spindel macro context doesn't include
        _ (sci/merge-opts sci-ctx
                          {:classes {'java.util.Date java.util.Date
                                     'java.util.UUID java.util.UUID
                                     'java.time.Instant java.time.Instant
                                     'System System
                                     'Math Math
                                     'String String
                                     'Integer Integer
                                     'Long Long
                                     'Double Double}})]

    ;; Bind datahike query + write to the simulation's own DB.
    ;; Uses standard datahike API signatures — simulations pass (dh/db) explicitly.
    (let [db-atom (atom @sim-conn)]
      (sci/add-namespace! sci-ctx 'dh
                          {;; Read — standard datahike signatures
                           'q        (fn [query db & args] (apply dh/q query db args))
                           'pull     (fn [db pattern eid] (dh/pull db pattern eid))
                           'pull-many (fn [db pattern eids] (dh/pull-many db pattern eids))
                           'entity   (fn [db eid] (dh/entity db eid))
                           'datoms   (fn [db index & components] (apply dh/datoms db index components))
                           ;; db — returns current DB value
                           'db       (fn [] @db-atom)
                           ;; Write — transact returns TxReport, updates db-atom
                           'transact! (fn [tx-data]
                                        (let [report (dh/transact sim-conn {:tx-data tx-data})]
                                          (reset! db-atom @sim-conn)
                                          report))
                           'retract!  (fn [tx-data]
                                        (let [report (dh/transact sim-conn {:tx-data tx-data})]
                                          (reset! db-atom @sim-conn)
                                          report))}))

    ;; Add intake namespaces (read-only external data)
    (sandbox/add-intake-namespaces! sci-ctx)

    ;; Add LLM namespace (cheap summarization)
    (sandbox/add-llm-ns! sci-ctx)

    ;; Add spindel sync/combinator extras
    (sandbox/add-spindel-extras-ns! sci-ctx spindel-ctx)

    ;; Add fulltext search (read-only)
    (sandbox/add-search-ns! sci-ctx)

    ;; Add calendar if main-conn is available
    (when main-conn
      (sandbox/add-calendar-ns! sci-ctx main-conn))

    ;; Bind *db* as a convenience var pointing to the simulation's conn
    (sci/eval-string* sci-ctx (str "(def ^:dynamic *db* nil)"))
    (sci/add-namespace! sci-ctx 'user
                        {'*db* sim-conn})

    sci-ctx))

(defn eval-simulation
  "Evaluate simulation code in its isolated SCI context.

   Returns {:output ... :error? ... :duration-ms ...}"
  [sci-ctx code]
  (let [start (System/currentTimeMillis)]
    (try
      (let [result (sandbox/eval-code sci-ctx code)]
        (assoc result :duration-ms (- (System/currentTimeMillis) start)))
      (catch Exception e
        {:error? true
         :error (.getMessage e)
         :exception (str (class e))
         :duration-ms (- (System/currentTimeMillis) start)}))))
