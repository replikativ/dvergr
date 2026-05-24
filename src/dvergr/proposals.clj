(ns dvergr.proposals
  "Merge-proposal lifecycle on top of `dvergr.discourse`.

   A proposal is a deferred `d/hire`: fork the room, run a worker on
   a goal, capture their reply, persist a proposal entity, and KEEP THE
   FORK OPEN until a human (or another agent) calls
   `accept-proposal!` or `reject-proposal!`. Accept merges the fork
   into the parent; reject discards it. Failed proposals (test-fn says
   :pass? false) are auto-discarded at creation time.

   Datahike persistence (schema in `dvergr.chat.schema/proposal-schema`)
   tracks the proposal's status across daemon restarts; the live fork
   handle lives on the parent room's spindel execution-context under
   `[:dvergr/proposals proposal-id]` — lost on restart, so only
   proposals from the current session can be accept/reject'd. Future:
   pair `:proposal/fork-id` with substrate fork recovery (Open Q #11
   of discourse-model.md).

   Substrate-fork status: `d/fork-room` currently shares the parent's
   execution context — only the Room atoms (participants + log) are
   isolated, not file-system / Datahike state. That means proposals
   today track \"what did the agent say\" but do NOT isolate side
   effects the agent made (file writes, knowledge_add). Once
   yggdrasil-backed substrate forking lands, proposals get isolation
   for free — the API stays the same.

   Usage:

     (require '[dvergr.discourse  :as d]
              '[dvergr.personas   :as personas]
              '[dvergr.proposals  :as p])

     ;; Worker can be any Participant; personas/coder is the common pick.
     (let [proposal @(p/propose! {:room    daemon-room
                                  :worker  (personas/coder)
                                  :goal    \"implement feature X\"
                                  :conn    db-conn})]
       ;; ...present proposal to a human via the web UI...
       (p/accept-proposal! db-conn (:proposal/id proposal)))"
  (:refer-clojure :exclude [await])
  (:require [datahike.api :as dh]
            [taoensso.telemere :as tel]
            [org.replikativ.spindel.core :as sp :refer [spin await]]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.protocols :as rtp]
            [org.replikativ.spindel.spin.combinators :as comb]
            [dvergr.discourse :as d]))

;; ============================================================================
;; Fork-handle storage (ctx-scoped)
;;
;; Proposals persist metadata (`:proposal/...`) to Datahike, but the live
;; Room fork (needed for `merge-room`/`discard`) is held only in memory,
;; on the *parent room's* spindel execution-context under
;; `[:dvergr/proposals]`. That way forks inherit/branch the handle map
;; naturally (the daemon's proposals don't leak into sidecar or test
;; contexts), and a parent ctx that's torn down drops its proposal
;; handles with it. Lost on daemon restart — pending proposals from
;; prior sessions can only be inspected, not acted on.
;; ============================================================================

(def ^:private handles-path [:dvergr/proposals])

(defn- put-handle! [room proposal-id handle]
  (rtp/swap-state! (:ctx room) handles-path
                   (fn [m] (assoc (or m {}) proposal-id handle))))

(defn- read-handle [room proposal-id]
  (get (rtp/get-state (:ctx room) handles-path) proposal-id))

(defn- drop-handle! [room proposal-id]
  (rtp/swap-state! (:ctx room) handles-path
                   (fn [m] (dissoc (or m {}) proposal-id))))

(defn get-cached-handle
  "Return the live `{:fork :reply :room}` handle for a pending proposal,
   or nil if the daemon was restarted since it was created.

   Pass the same `room` that was passed to `propose!` so we know which
   ctx the handle lives on."
  [room proposal-id]
  (read-handle room proposal-id))

(defn forget-handles!
  "Drop every live proposal handle on `room`'s context. Used by tests to
   simulate a daemon restart without touching Datahike persistence."
  [room]
  (rtp/swap-state! (:ctx room) handles-path (constantly {})))

;; ============================================================================
;; Persistence
;; ============================================================================

(defn- persist-proposal!
  [conn proposal]
  (dh/transact conn [(select-keys proposal
                       [:proposal/id :proposal/agent-id :proposal/status
                        :proposal/summary :proposal/task :proposal/fork-id
                        :proposal/test-result :proposal/created-at
                        :proposal/resolved-at])]))

(defn- update-status!
  [conn proposal-id new-status]
  (dh/transact conn [{:db/id [:proposal/id proposal-id]
                      :proposal/status new-status
                      :proposal/resolved-at (java.util.Date.)}]))

;; ============================================================================
;; Queries
;; ============================================================================

(defn get-proposal
  "Pull the proposal entity by id from Datahike. Returns nil if absent."
  [conn proposal-id]
  (dh/q '[:find (pull ?e [*]) .
          :in $ ?pid
          :where [?e :proposal/id ?pid]]
        @conn proposal-id))

(defn list-proposals
  "List proposals sorted newest-first.

   Options:
     :status   — filter by status keyword (:pending :accepted :rejected :failed)
     :agent-id — filter by the participant's keyword id
     :limit    — max results (default 50)"
  [conn & {:keys [status agent-id limit] :or {limit 50}}]
  (let [results (cond
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
    (->> results
         (sort-by #(- (.getTime (or (:proposal/created-at %)
                                    (java.util.Date. 0)))))
         (take limit)
         vec)))

(defn pending-count
  "Count proposals in `:pending` status."
  [conn]
  (or (dh/q '[:find (count ?e) .
              :where
              [?e :proposal/id _]
              [?e :proposal/status :pending]]
            @conn)
      0))

;; ============================================================================
;; Lifecycle
;; ============================================================================

(defn propose!
  "Fork the room, hire the worker for one goal, persist a proposal entity,
   and keep the fork open for later accept/reject.

   Returns a Spin yielding the proposal map (Datahike-flavoured keys plus
   `:fork` and `:reply` for in-memory continuation).

   Required keys in `opts`:
     :room   — parent dvergr.discourse Room
     :worker — a Participant (e.g. from `dvergr.personas`) — the worker is
               joined into the FORK, never the parent
     :goal   — string content of the goal message
     :conn   — Datahike connection (proposal schema must be installed)

   Optional:
     :test-fn       — (fn [] → {:pass? bool :output str}) run after the
                      worker replies. `:pass?` false marks the proposal
                      `:failed` and auto-discards the fork.
     :budget-dollars — passthrough hint (kept on the proposal for
                       inspection; the worker enforces its own budget)
     :on-propose    — (fn [proposal]) callback after the entity is persisted
     :timeout-ms    — how long to wait for the worker's reply (default 60000)
     :from          — `:from` id on the goal message (default :proposer)"
  [{:keys [room worker goal conn test-fn budget-dollars on-propose
           timeout-ms from isolation]
    :or   {timeout-ms 60000
           from       :proposer
           isolation  :ctx}}]
  {:pre [(some? room) (some? worker) (string? goal) (some? conn)]}
  (spin
    (let [;; Substrate-isolated by default: the worker's writes (chat-ctx
          ;; datahike, KB additions, file edits via tools, ...) happen on
          ;; branched yggdrasil systems inside the fork's ctx. On
          ;; accept-proposal!, merge-room atomically merges them back; on
          ;; reject, discard-from-parent! drops the branches. Pass
          ;; `:isolation :none` to fall back to the old shared-ctx
          ;; behaviour (useful for ToM-style proposals where the worker
          ;; only emits a message and does not commit to any side effect).
          fork  (d/fork-room room {:isolation isolation})
          ;; Re-create the worker via its :factory so a single worker
          ;; value can drive multiple propose! calls. Without this the
          ;; worker's participant-spin stays bound to the first fork it
          ;; was joined to, and the second call's replies route to the
          ;; wrong room. Falls back to the raw worker if no factory.
          worker-instance (if-let [fac (:factory worker)]
                            (fac (:ctx fork))
                            worker)
          _     (d/join fork worker-instance)
          reply (binding [ec/*execution-context* (:ctx fork)]
                  (await
                    (comb/timeout
                      (d/ask fork (:id worker-instance)
                             {:content goal
                              :metadata {:from from}})
                      timeout-ms
                      ::timeout)))
          timed-out? (= reply ::timeout)
          test-result (when (and test-fn (not timed-out?))
                        (try (test-fn)
                             (catch Exception e
                               {:pass? false :error (.getMessage e)})))
          status (cond
                   timed-out?              :failed
                   (and test-fn
                        (not (:pass? test-result)))
                                           :failed
                   :else                   :pending)
          proposal-id (random-uuid)
          proposal    {:proposal/id         proposal-id
                       :proposal/agent-id   (:id worker-instance)
                       :proposal/summary    (if timed-out?
                                              "[timed out]"
                                              (str (:content reply)))
                       :proposal/task       goal
                       :proposal/fork-id    (str (:id fork))
                       :proposal/created-at (java.util.Date.)
                       :proposal/status     status}
          proposal    (cond-> proposal
                        test-result
                        (assoc :proposal/test-result (pr-str test-result)))]

      (persist-proposal! conn proposal)

      ;; Cache the live fork for accept/reject, or auto-discard on failure.
      (if (= :pending status)
        (put-handle! room proposal-id
                     {:fork fork :reply reply :room room})
        (d/discard fork))

      (tel/log! {:id   :proposal/created
                 :data {:id     proposal-id
                        :agent  (:id worker)
                        :status status
                        :budget budget-dollars}}
                "Proposal created")

      (when on-propose
        (try (on-propose proposal)
             (catch Exception e
               (tel/log! {:level :warn :id :proposal/on-propose-error
                          :data {:err (.getMessage e)}}
                         "on-propose callback threw"))))

      (assoc proposal
             :fork  fork
             :reply reply))))

(defn accept-proposal!
  "Merge a pending proposal's fork into its parent room. Updates Datahike
   status to `:accepted` and drops the in-memory handle.

   `room` is the parent room originally passed to `propose!` — it tells
   us which execution context the live fork handle lives on.

   Returns `:accepted`, or an error map (`:no-live-context` if the daemon
   has restarted since the proposal was created, `:not-found` if the id
   is unknown)."
  [room conn proposal-id]
  (if-let [{:keys [fork]} (read-handle room proposal-id)]
    (do (binding [ec/*execution-context* (:ctx room)]
          (d/merge-room room fork))
        (update-status! conn proposal-id :accepted)
        (drop-handle! room proposal-id)
        (tel/log! {:id :proposal/accepted :data {:id proposal-id}}
                  "Proposal accepted")
        :accepted)
    (if (get-proposal conn proposal-id)
      {:error :no-live-context
       :message "Proposal exists but its live fork is gone (daemon restarted). Cannot merge."}
      {:error :not-found
       :message (str "No proposal with id " proposal-id)})))

(defn reject-proposal!
  "Discard a pending proposal's fork. Updates Datahike status to
   `:rejected` and drops the in-memory handle. Without a live fork (e.g.
   after daemon restart), still flips the status so the proposal is
   removed from the pending queue.

   `room` is the parent room originally passed to `propose!`.

   Returns `:rejected`, or `{:error :not-found}` if the id is unknown."
  [room conn proposal-id]
  (if-let [{:keys [fork]} (read-handle room proposal-id)]
    (do (d/discard fork)
        (update-status! conn proposal-id :rejected)
        (drop-handle! room proposal-id)
        (tel/log! {:id :proposal/rejected :data {:id proposal-id}}
                  "Proposal rejected")
        :rejected)
    (if (get-proposal conn proposal-id)
      (do (update-status! conn proposal-id :rejected)
          :rejected)
      {:error :not-found
       :message (str "No proposal with id " proposal-id)})))
