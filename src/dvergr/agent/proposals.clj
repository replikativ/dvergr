(ns dvergr.agent.proposals
  "Merge proposal lifecycle — formalized fork/test/propose/review/accept|reject.

   A proposal wraps the ask! → merge!/discard! pattern with:
   - Datahike persistence for tracking
   - Optional test-fn execution in the forked context
   - Status lifecycle: :pending → :accepted | :rejected | :failed
   - Callbacks for notifications (e.g. Telegram alerts)

   Usage:
     ;; Create proposal
     (def p (await (propose! agent \"Improve profile\" {:budget-dollars 0.30})))

     ;; List pending
     (list-proposals conn :status :pending)

     ;; Accept or reject
     (accept-proposal! conn (:proposal/id p))
     (reject-proposal! conn (:proposal/id p))"
  (:refer-clojure :exclude [await])
  (:require [dvergr.agent.task :as prim]
            [org.replikativ.spindel.core :refer [spin]]
            [org.replikativ.spindel.core :refer [await]]
            [datahike.api :as dh]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; In-memory result cache
;; ============================================================================

;; Proposals persist metadata to Datahike, but the live :child-ctx (needed
;; for merge/discard) is held in memory. The cache maps proposal-id -> result.
;; Lost on daemon restart — only pending proposals from the current session
;; can be accepted/rejected. Future: serialize fork-id for recovery.

(defonce ^:private result-cache (atom {}))

(defn get-cached-result
  "Get the live cached result for a proposal by ID. Returns nil if not cached."
  [proposal-id]
  (get @result-cache proposal-id))

;; ============================================================================
;; Persistence Helpers
;; ============================================================================

(defn- persist-proposal!
  "Transact a proposal entity into Datahike."
  [conn proposal]
  (dh/transact conn [(select-keys proposal
                        [:proposal/id :proposal/agent-id :proposal/status
                         :proposal/summary :proposal/task :proposal/fork-id
                         :proposal/test-result :proposal/created-at
                         :proposal/resolved-at])]))

(defn- update-status!
  "Update proposal status and optionally set resolved-at."
  [conn proposal-id new-status]
  (dh/transact conn [{:db/id [:proposal/id proposal-id]
                       :proposal/status new-status
                       :proposal/resolved-at (java.util.Date.)}]))

;; ============================================================================
;; Queries
;; ============================================================================

(defn get-proposal
  "Get a proposal by ID. Returns entity map or nil."
  [conn proposal-id]
  (dh/q '[:find (pull ?e [*]) .
          :in $ ?pid
          :where [?e :proposal/id ?pid]]
        @conn proposal-id))

(defn list-proposals
  "List proposals, optionally filtered by status or agent-id.

   Options:
     :status   - Filter by status keyword (:pending, :accepted, :rejected, :failed)
     :agent-id - Filter by agent keyword
     :limit    - Max results (default 50)"
  [conn & {:keys [status agent-id limit] :or {limit 50}}]
  (let [base-results
        (cond
          (and status agent-id)
          (dh/q '[:find [(pull ?e [*]) ...]
                  :in $ ?s ?a
                  :where
                  [?e :proposal/id _]
                  [?e :proposal/status ?s]
                  [?e :proposal/agent-id ?a]]
                @conn status agent-id)

          status
          (dh/q '[:find [(pull ?e [*]) ...]
                  :in $ ?s
                  :where
                  [?e :proposal/id _]
                  [?e :proposal/status ?s]]
                @conn status)

          agent-id
          (dh/q '[:find [(pull ?e [*]) ...]
                  :in $ ?a
                  :where
                  [?e :proposal/id _]
                  [?e :proposal/agent-id ?a]]
                @conn agent-id)

          :else
          (dh/q '[:find [(pull ?e [*]) ...]
                  :where [?e :proposal/id _]]
                @conn))]
    (->> base-results
         (sort-by #(- (.getTime (or (:proposal/created-at %) (java.util.Date. 0)))))
         (take limit)
         vec)))

;; ============================================================================
;; Proposal Lifecycle
;; ============================================================================

(defn propose!
  "Fork context, run agent task, persist proposal entity.

   Returns a spin yielding the proposal map (Datahike entity + :result key
   holding the full ask! result for later merge/discard).

   Args:
     agent    - Agent config map (as used by prim/ask!)
     task-str - Task description string
     opts     - Options map:
       :budget-dollars  - Budget for the forked task (default $0.50)
       :parent-chat-ctx - Parent ChatContext to fork from
       :test-fn         - (fn [] -> {:pass? bool :output str}) to run in forked ctx
       :on-propose      - (fn [proposal]) callback after creation
       :conn            - Datahike connection for persistence (required)"
  [agent task-str {:keys [budget-dollars parent-chat-ctx test-fn on-propose conn workflow]}]
  {:pre [(some? conn) (string? task-str)]}
  (spin
    (let [result (await (prim/ask! agent task-str
                          (cond-> {:budget-dollars (or budget-dollars 0.50)
                                   :parent-chat-ctx parent-chat-ctx}
                            workflow (assoc :workflow workflow))))
          proposal-id (random-uuid)
          base {:proposal/id         proposal-id
                :proposal/agent-id   (or (:id agent) (keyword (or (:name agent) "unknown")))
                :proposal/summary    (prim/extract-result result)
                :proposal/task       task-str
                :proposal/fork-id    (str (hash (:child-ctx result)))
                :proposal/created-at (java.util.Date.)
                :proposal/status     (if (prim/successful? result) :pending :failed)}

          ;; Run tests in forked context if provided and task succeeded
          proposal (if (and test-fn (prim/successful? result))
                     (try
                       (let [test-result (test-fn)]
                         (assoc base
                           :proposal/test-result (pr-str test-result)
                           :proposal/status (if (:pass? test-result) :pending :failed)))
                       (catch Exception e
                         (assoc base
                           :proposal/test-result (pr-str {:pass? false :error (.getMessage e)})
                           :proposal/status :failed)))
                     base)]

      ;; Persist to Datahike
      (persist-proposal! conn proposal)

      ;; Cache the live result for later merge/discard
      (swap! result-cache assoc proposal-id result)

      ;; If failed, auto-discard the fork
      (when (= :failed (:proposal/status proposal))
        (prim/discard! result))

      (tel/log! {:id :proposal/created
                 :data {:id proposal-id
                        :agent (:proposal/agent-id proposal)
                        :status (:proposal/status proposal)}}
                "Proposal created")

      ;; Notify callback
      (when on-propose (on-propose proposal))

      ;; Return proposal with live result attached
      (assoc proposal :result result))))

(defn accept-proposal!
  "Accept a proposal: merge the forked context to parent.

   Args:
     conn        - Datahike connection
     proposal-id - UUID of proposal to accept

   Returns :accepted or :error map."
  [conn proposal-id]
  (if-let [result (get @result-cache proposal-id)]
    (do
      (prim/merge! result)
      (update-status! conn proposal-id :accepted)
      (swap! result-cache dissoc proposal-id)
      (tel/log! {:id :proposal/accepted :data {:id proposal-id}} "Proposal accepted")
      :accepted)
    (let [proposal (get-proposal conn proposal-id)]
      (if proposal
        {:error :no-live-context
         :message "Proposal exists but its live context is lost (daemon restarted?). Cannot merge."}
        {:error :not-found
         :message (str "No proposal with id " proposal-id)}))))

(defn reject-proposal!
  "Reject a proposal: discard the forked context.

   Args:
     conn        - Datahike connection
     proposal-id - UUID of proposal to reject

   Returns :rejected or :error map."
  [conn proposal-id]
  (if-let [result (get @result-cache proposal-id)]
    (do
      (prim/discard! result)
      (update-status! conn proposal-id :rejected)
      (swap! result-cache dissoc proposal-id)
      (tel/log! {:id :proposal/rejected :data {:id proposal-id}} "Proposal rejected")
      :rejected)
    (let [proposal (get-proposal conn proposal-id)]
      (if proposal
        (do
          ;; Mark as rejected even without live context
          (update-status! conn proposal-id :rejected)
          :rejected)
        {:error :not-found
         :message (str "No proposal with id " proposal-id)}))))

(defn pending-count
  "Return count of pending proposals."
  [conn]
  (or (dh/q '[:find (count ?e) .
              :where
              [?e :proposal/id _]
              [?e :proposal/status :pending]]
            @conn)
      0))
