(ns dvergr.simulations.db
  "Dedicated Datahike DB provisioning for simulations.

   Each simulation gets its own Datahike database stored at:
     data/simulations/<simulation-name>/

   Using konserve file backend (same as main DB). Simulation DBs are
   independent — each simulation defines its own schema as part of its
   code (first thing the code does is ensure its schema exists)."
  (:require [datahike.api :as d]
            [clojure.java.io :as io]
            [taoensso.telemere :as tel]))

(def ^:private simulations-dir "data/simulations")

(defn simulation-db-config
  "Return Datahike config for a simulation's dedicated DB."
  [simulation-name]
  {:store {:backend :file
           :path (str simulations-dir "/" simulation-name)
           :id (java.util.UUID/nameUUIDFromBytes (.getBytes (str "sim-" simulation-name)))}
   :schema-flexibility :write
   :keep-history? true})

(defn create-simulation-db!
  "Create a dedicated Datahike DB for a simulation.

   Returns the connection config map (pass to datahike.api/connect).
   Idempotent — if the DB already exists, returns config without error."
  [simulation-name]
  (let [cfg (simulation-db-config simulation-name)
        dir (io/file (:path (:store cfg)))]
    ;; Ensure parent directory exists
    (.mkdirs (.getParentFile dir))
    (when-not (d/database-exists? cfg)
      (d/create-database cfg)
      (tel/log! {:id :simulations.db/created
                 :data {:name simulation-name :path (:path (:store cfg))}}
                "Created simulation DB"))
    cfg))

(defn connect-simulation-db
  "Connect to an existing simulation DB. Returns connection."
  [simulation-name]
  (let [cfg (simulation-db-config simulation-name)]
    (when-not (d/database-exists? cfg)
      (throw (ex-info (str "Simulation DB does not exist: " simulation-name)
                      {:name simulation-name :config cfg})))
    (d/connect cfg)))

(defn destroy-simulation-db!
  "Delete a simulation's dedicated DB.

   This is irreversible. The simulation DB and all its data will be lost.
   Returns true if deleted, false if DB didn't exist."
  [simulation-name]
  (let [cfg (simulation-db-config simulation-name)]
    (if (d/database-exists? cfg)
      (do (d/delete-database cfg)
          (tel/log! {:id :simulations.db/destroyed
                     :data {:name simulation-name}}
                    "Destroyed simulation DB")
          true)
      false)))

(defn simulation-db-exists?
  "Check if a simulation's dedicated DB exists."
  [simulation-name]
  (d/database-exists? (simulation-db-config simulation-name)))
