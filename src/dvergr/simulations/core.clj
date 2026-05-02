(ns dvergr.simulations.core
  "Simulation lifecycle management.


   Simulations are executable Clojure code (SCI) with dedicated Datahike DBs.
   They can model competitors, markets, scenarios, or any structured data
   the agents need to maintain.

   Lifecycle:
     (create! conn {:name \"acmedb\" :code source :maintainer :sentinel})
     (run! conn \"acmedb\" execution-ctx)
     (update-code! conn \"acmedb\" new-code)
     (start-loop! conn \"acmedb\" execution-ctx)

   Agents interact with simulations programmatically via SCI functions
   exposed in the `sim` namespace (see add-simulation-ns! in sandbox.clj)."
  (:refer-clojure :exclude [run!])
  (:require [datahike.api :as d]
            [dvergr.simulations.db :as sim-db]
            [dvergr.simulations.sandbox :as sim-sandbox]
            [clojure.edn :as edn]
            [taoensso.telemere :as tel]))

;; =============================================================================
;; Create
;; =============================================================================

(defn create!
  "Create a new simulation.

   1. Creates a dedicated Datahike DB for the simulation
   2. Transacts the simulation entity into the main DB
   3. Returns the simulation map with :simulation/id and :simulation/db-uri

   Args:
     conn - Main Datahike connection
     opts - {:name        string (required, unique)
             :code        string (Clojure source, required)
             :maintainer  keyword (agent-id, optional)
             :budget      long (microdollars, default 0)
             :interval-ms long (sweep interval, default 0 = manual)
             :entity-id   uuid (optional link to knowledge entity)}"
  [conn {:keys [name code maintainer budget interval-ms entity-id]}]
  {:pre [(string? name) (string? code)]}
  (let [;; Create dedicated DB
        db-cfg (sim-db/create-simulation-db! name)
        sim-id (random-uuid)
        now    (java.util.Date.)
        entity (cond-> {:simulation/id         sim-id
                        :simulation/name       name
                        :simulation/code       code
                        :simulation/db-uri     (pr-str db-cfg)
                        :simulation/status     :draft
                        :simulation/version    1
                        :simulation/budget     (or budget 0)
                        :simulation/interval-ms (or interval-ms 0)
                        :simulation/created-at now
                        :simulation/updated-at now}
                 maintainer (assoc :simulation/maintainer maintainer)
                 entity-id  (assoc :simulation/entity [:entity/id entity-id]))]
    (d/transact conn [entity])
    (tel/log! {:id :simulations/created
               :data {:name name :id sim-id :maintainer maintainer}}
              "Simulation created")
    entity))

;; =============================================================================
;; Query
;; =============================================================================

(defn get-simulation
  "Get a simulation by name. Returns entity map or nil."
  [conn name]
  (d/q '[:find (pull ?e [*]) .
         :in $ ?name
         :where [?e :simulation/name ?name]]
       @conn name))

(defn get-simulation-by-id
  "Get a simulation by UUID."
  [conn sim-id]
  (d/q '[:find (pull ?e [*]) .
         :in $ ?id
         :where [?e :simulation/id ?id]]
       @conn sim-id))

(defn list-simulations
  "List all simulations, optionally filtered by status or maintainer."
  [conn & {:keys [status maintainer]}]
  (let [base-results (d/q '[:find [(pull ?e [*]) ...]
                            :where [?e :simulation/id _]]
                          @conn)]
    (cond->> base-results
      status     (filter #(= status (:simulation/status %)))
      maintainer (filter #(= maintainer (:simulation/maintainer %)))
      true       (sort-by #(- (.getTime (or (:simulation/updated-at %) (java.util.Date. 0))))))))

;; =============================================================================
;; Update
;; =============================================================================

(defn update-code!
  "Update a simulation's code. Bumps version and updated-at.

   Args:
     conn    - Main Datahike connection
     name    - Simulation name
     new-code - New Clojure source code string"
  [conn name new-code]
  (let [sim (get-simulation conn name)]
    (when-not sim
      (throw (ex-info (str "Simulation not found: " name) {:name name})))
    (d/transact conn [{:db/id          [:simulation/name name]
                       :simulation/code       new-code
                       :simulation/version    (inc (or (:simulation/version sim) 0))
                       :simulation/updated-at (java.util.Date.)}])
    (tel/log! {:id :simulations/code-updated
               :data {:name name :version (inc (or (:simulation/version sim) 0))}}
              "Simulation code updated")))

(defn set-status!
  "Update a simulation's status.

   Valid statuses: :draft :active :paused :archived"
  [conn name new-status]
  {:pre [(#{:draft :active :paused :archived} new-status)]}
  (d/transact conn [{:db/id [:simulation/name name]
                     :simulation/status    new-status
                     :simulation/updated-at (java.util.Date.)}]))

;; =============================================================================
;; Delete
;; =============================================================================

(defn delete!
  "Archive a simulation and optionally destroy its dedicated DB.

   By default only archives (status -> :archived). Pass :destroy-db? true
   to also delete the dedicated Datahike DB (irreversible)."
  [conn name & {:keys [destroy-db?]}]
  (set-status! conn name :archived)
  (when destroy-db?
    (sim-db/destroy-simulation-db! name))
  (tel/log! {:id :simulations/deleted
             :data {:name name :db-destroyed? (boolean destroy-db?)}}
            "Simulation deleted"))

;; =============================================================================
;; Run
;; =============================================================================

(defn run!
  "Run a simulation's code once.

   1. Loads simulation entity from main DB
   2. Connects to the simulation's dedicated DB
   3. Creates an isolated SCI context
   4. Evaluates the simulation code
   5. Returns {:output ... :error? ... :duration-ms ...}

   Args:
     conn          - Main Datahike connection
     name          - Simulation name
     execution-ctx - Spindel execution context"
  [conn name execution-ctx]
  (let [sim (get-simulation conn name)]
    (when-not sim
      (throw (ex-info (str "Simulation not found: " name) {:name name})))
    (let [sim-conn (sim-db/connect-simulation-db name)
          sci-ctx  (sim-sandbox/create-simulation-sci-ctx
                     sim-conn execution-ctx :main-conn conn)
          result   (sim-sandbox/eval-simulation sci-ctx (:simulation/code sim))]
      (tel/log! {:id :simulations/run
                 :data {:name name
                        :duration-ms (:duration-ms result)
                        :error? (:error? result)}}
                "Simulation run completed")
      result)))

(defn query-db
  "Query a simulation's dedicated Datahike DB.

   Args:
     name  - Simulation name
     query - Datalog query

   Returns query results."
  [name query & args]
  (let [sim-conn (sim-db/connect-simulation-db name)]
    (apply d/q query @sim-conn args)))

;; =============================================================================
;; Periodic Loop
;; =============================================================================

(defn start-loop!
  "Start a periodic loop for a simulation.

   If interval-ms > 0, creates a future that:
   - Runs the simulation at the configured interval
   - Tracks budget consumption (TODO)
   - Stops when budget exhausted or status changed

   Returns the future (cancel with future-cancel).
   Sets status to :active."
  [conn name execution-ctx]
  (let [sim (get-simulation conn name)]
    (when-not sim
      (throw (ex-info (str "Simulation not found: " name) {:name name})))
    (let [interval (or (:simulation/interval-ms sim) 0)]
      (when (<= interval 0)
        (throw (ex-info "Simulation has no interval configured (interval-ms = 0)"
                        {:name name})))
      (set-status! conn name :active)
      (future
        (tel/log! {:id :simulations/loop-started :data {:name name :interval-ms interval}}
                  "Simulation loop started")
        (loop []
          (let [current (get-simulation conn name)]
            (when (= :active (:simulation/status current))
              (try
                (run! conn name execution-ctx)
                (catch Exception e
                  (tel/log! {:id :simulations/loop-error
                             :data {:name name :error (.getMessage e)}}
                            "Simulation loop error")))
              (Thread/sleep interval)
              (recur))))
        (tel/log! {:id :simulations/loop-stopped :data {:name name}}
                  "Simulation loop stopped")))))

;; =============================================================================
;; SCI Namespace for Agent Access
;; =============================================================================

(defn add-simulation-ns!
  "Expose simulation functions as 'sim namespace in an SCI context.

   After calling this, agents can interact with simulations programmatically:

     (require '[sim])
     (sim/list)
     (sim/get \"acmedb\")
     (sim/run \"acmedb\")
     (sim/query \"acmedb\" '[:find ?e :where [?e :signal/type]])
     (sim/create {:name \"example-bank\" :code \"...\" :maintainer :sentinel})
     (sim/update-code \"acmedb\" new-code)

   This replaces tool-based interaction — agents call functions directly."
  [sci-ctx conn execution-ctx]
  (require 'sci.core)
  (let [sci-add-ns! @(ns-resolve 'sci.core 'add-namespace!)]
    (sci-add-ns! sci-ctx 'sim
                 {'list       (fn [& {:as opts}]
                                (apply list-simulations conn (mapcat identity opts)))
                  'get        (fn [name] (get-simulation conn name))
                  'run        (fn [name] (run! conn name execution-ctx))
                  'query      (fn [name query & args]
                                (apply query-db name query args))
                  'create     (fn [opts] (create! conn opts))
                  'update-code (fn [name code] (update-code! conn name code))
                  'set-status (fn [name status] (set-status! conn name status))
                  'delete     (fn [name & {:as opts}]
                                (apply delete! conn name (mapcat identity opts)))})))
