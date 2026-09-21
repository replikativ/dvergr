(ns dvergr.agent.conversation
  "Certified conversational episodes: multi-turn environments with a simulated
   counterpart, run inside Rooms and certified as Attempts.

   An episode is an explicit `:episode` Run in an experiment Room. Its dialogue
   happens in a child episode Room of the same durable store: the candidate
   and the environment's counterpart are ordinary Participants, so every
   message, candidate `:agent-turn` Run and tool activity is recorded by the
   Room itself. The environment additionally posts one non-triggering effect
   row per tool effect (the exact result, which `:_activity` rows deliberately
   omit). Certification reuses the trusted evaluation types unchanged:
   `environment/make-attempt-receipt` -> `attempt/make-attempt` ->
   `attempt/persist!`, and Scorecards via `experiment/make-scorecard`.

   Evidence names only rows of the experiment Room (the store requires
   evidence Runs/messages to share the Attempt's Room); the episode Room is
   referenced as plain data and re-verified by inspection. Blocking work
   (model calls, grading, certification, Room teardown) belongs on host
   threads, never on the Spindel drain. See doc/conversation-evaluation.md."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datahike.api :as dh]
            [dvergr.agent.attempt :as attempt]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.run :as run]
            [dvergr.agent.turn :as turn]
            [dvergr.artifact :as artifact]
            [dvergr.chat.schema :as schema]
            [dvergr.discourse :as d]
            [dvergr.room.store :as store]
            [dvergr.room.store.datahike :as dhs]
            [dvergr.substrate.datahike :as sdh]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as sdb]
            [hasch.core :as hasch])
  (:import [java.security MessageDigest]))

(def interpreter-version
  "Version of the conversational episode interpreter recorded on episode Runs
   and Attempt receipts."
  1)

;; ---------------------------------------------------------------------------
;; Experiment-local storage

(defn- sha256-hex [^bytes bytes]
  (apply str (map #(format "%02x" (bit-and % 0xff))
                  (.digest (MessageDigest/getInstance "SHA-256") bytes))))

(defrecord FileArtifactStore [dir]
  artifact/PArtifactStore
  (-put-value! [_ value]
    (let [bytes (.getBytes (binding [*print-length* nil *print-level* nil] (pr-str value)) "UTF-8")
          ref (sha256-hex bytes)
          f (io/file dir (str ref ".edn"))]
      (when-not (.exists f)
        (io/make-parents f)
        ;; Unique temp name: parallel writers of the same value must not share it.
        (let [tmp (io/file dir (str ref "." (random-uuid) ".tmp"))]
          (with-open [out (io/output-stream tmp)] (.write out bytes))
          (when-not (or (.renameTo tmp f) (.exists f))
            (throw (ex-info "Artifact write failed" {:ref ref :dir dir})))
          (.delete tmp)))
      ref))
  (-get-value [_ ref]
    (let [f (io/file dir (str ref ".edn"))]
      (when (.exists f) (edn/read-string (slurp f))))))

(defn file-artifact-store
  "Immutable EDN values addressed by the sha256 of their printed form, one
   file each under `dir` (inspectable with ordinary tools)."
  [dir]
  (->FileArtifactStore (str dir)))

(defn- store-config [dir]
  (let [path (.getAbsolutePath (io/file dir "store"))]
    {:store {:backend :file :path path
             :id (java.util.UUID/nameUUIDFromBytes (.getBytes (str "dvergr-experiment:" path) "UTF-8"))}
     :keep-history? true
     :schema-flexibility :write}))

(defn open-store!
  "Open (creating on first use) the durable experiment store under `dir`:
   a file-backed Datahike database with the chat schema plus an
   experiment-local artifact store. Returns `{:dir :cfg :conn :store}`."
  [dir]
  (let [base (store-config dir)
        fresh? (not (dh/database-exists? base))
        ;; Every Attempt is its own small commit, so an experiment store is the
        ;; write pattern diff buffering is for (`substrate.datahike/
        ;; diff-buf-size`; without it a 2000-commit store is several hundred MB).
        ;; Create-time-fixed: an existing store keeps what it was created with,
        ;; and datahike raises on a conflicting value at connect.
        cfg (cond-> base
              fresh? (assoc :index-config {:diff-buf-size sdh/diff-buf-size}))]
    ;; Datahike creates the directory itself; it must not pre-exist.
    (when fresh?
      (dh/create-database cfg))
    (let [conn (dh/connect cfg)]
      (schema/ensure-full-schema! conn)
      {:dir (str dir) :cfg cfg :conn conn
       :store (dhs/make conn (file-artifact-store (io/file dir "artifacts")))})))

(defn close-store! [{:keys [conn]}]
  (when conn (dh/release conn)))

(defn isolate-home!
  "Point Dvergr's state root (system DB, workspaces, blobs) at `dir` so an
   experiment never writes into a daemon's `.dvergr`. Process-wide: use a
   dedicated benchmark JVM or REPL."
  [dir]
  (paths/set-home! (str (io/file dir "home")))
  (sdb/reset-conn!)
  (str (io/file dir "home")))

;; ---------------------------------------------------------------------------
;; Episode Runs

(defn agent-provenance
  "Run provenance and receipt metrics identifying the candidate AgentDef."
  [agent]
  {:agent-def-hash (hasch/uuid agent)
   :program-kind (get-in agent [:agent/program :kind])
   :agent-version (:agent/version agent)
   :interpreter-version interpreter-version})

(defn open-episode!
  "Durably admit an `:episode` Run in `experiment-room`, triggered by a
   non-triggering \"episode opened\" row. Returns `{:run :opened}`."
  [experiment-room agent environment-ref episode-room-id]
  (let [opened (d/post! experiment-room
                        (d/message :environment turn/activity-id
                                   (pr-str {:episode/opened episode-room-id
                                            :environment environment-ref
                                            :agent-def-hash (hasch/uuid agent)})
                                   nil {:kind :environment/episode :role :tool}))
        {:keys [agent-def-hash program-kind agent-version]} (agent-provenance agent)
        run (run/start! experiment-room :environment opened nil
                        {:kind :episode
                         :provenance (cond-> {:run/agent-def-hash agent-def-hash
                                              :run/program-kind program-kind
                                              :run/interpreter-version interpreter-version}
                                       agent-version (assoc :run/agent-version agent-version))})]
    {:run run :opened opened}))

(defn finish-episode!
  "Finish the episode Run: :completed when graded, :failed for an
   infrastructure fault (`reason` records it)."
  [run-id status reason]
  (run/finish! run-id status (cond-> {} reason (assoc :reason reason))))

;; ---------------------------------------------------------------------------
;; Effect rows

(defn post-effect!
  "Record one environment effect as a non-triggering row in `room`. The
   payload is EDN in `:content` and carries an explicit `:seq` (store order is
   millisecond-granular)."
  [room effect]
  (d/post! room (d/message :environment turn/activity-id
                           (binding [*print-length* nil *print-level* nil] (pr-str effect))
                           nil {:kind :environment/effect :role :tool})))

(defn room-messages
  "Every stored message of `room-id`, oldest first (explicit large limit)."
  [room-store room-id]
  (->> (store/-list-messages room-store room-id {:limit 100000})
       (sort-by (juxt #(or (:ts %) (some-> ^java.util.Date (:created-at %) .getTime) 0)
                      #(str (:id %))))
       vec))

(defn effect-row? [message]
  (= :environment/effect (get-in message [:metadata :kind])))

(defn effects
  "Parsed effect payloads of `room-id` in `:seq` order."
  [room-store room-id]
  (->> (room-messages room-store room-id)
       (filter effect-row?)
       (map #(edn/read-string (:content %)))
       (sort-by :seq)
       vec))

;; ---------------------------------------------------------------------------
;; Certification

(defn certify!
  "Build, validate and persist the certified Attempt for a finished episode.

   `evidence` is portable data; its `:result`/`:trace` are copied into the
   receipt as required. The trace must name only experiment-Room rows."
  [experiment-room definition agent
   {:keys [run-id status started-at elapsed-ms checks reward metrics evidence]}]
  (let [{:keys [provider model]} (:agent/model-policy agent)
        receipt (environment/make-attempt-receipt
                 definition
                 (cond-> {:run-id run-id
                          :provider (or provider :dvergr)
                          :model (or model "unspecified")
                          :status status
                          :started-at started-at
                          :elapsed-ms elapsed-ms
                          :metrics (merge metrics (agent-provenance agent))
                          :checks checks
                          :reward reward}
                   (contains? evidence :result) (assoc :result (:result evidence))
                   (contains? evidence :trace) (assoc :trace (:trace evidence))))]
    (attempt/persist! experiment-room
                      (attempt/make-attempt definition agent receipt evidence :discard))))
