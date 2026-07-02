(ns dvergr.agent.subagent
  "Programmable-from-SCI subagent delegation.

   `hire!` forks a subroom off the caller's room with `:isolation :ctx` — so the
   worker runs on a BRANCHED substrate that inherits the room's registered
   yggdrasil systems (datahike workspace + CRDTs). It hosts an ephemeral worker,
   delegates a goal, and merges the fork back (parent-controlled `accept-fn`) or
   discards it. The worker's CRDT/DB edits fold back into the parent on merge —
   the fork/merge that opencode/codex can't do.

   Returns a **Spin** yielding
     {:status :merged|:discarded|:timeout :result :subagent-id}
   so an agent can `(await (room/hire …))` in the foreground OR hold the spin and
   await it later (background). Nests like rooms: a subagent can itself `hire!`.

   Lifecycle is DURABLY TRACKED as `:dvergr/subagent` tagged messages in the
   parent room (spawn → `:running`, finish → the terminal status) — the same
   log-as-truth pattern as `dvergr.discourse/propose-merge!`. When the room has a
   `:store` those events persist; `pending` enumerates the still-running ones.
   (A dedicated system-db `:subagent/*` entity is the more robust cross-room /
   store-less option — a follow-up.)"
  (:require [dvergr.discourse :as d]
            [org.replikativ.spindel.core :as sp]))

(defn- track!
  "Post a `:dvergr/subagent` lifecycle event into `room`'s log (durable when the
   room has a store)."
  [room ev]
  (d/post! room (assoc ev :type :dvergr/subagent :from :subagent-tracker)))

(defn- build-worker
  "Construct the ephemeral worker Participant. Accepts an explicit `:worker`
   (e.g. a scripted participant for tests) or a `:spec`
   {:id :provider :model :system-prompt :tools} → a fresh `llm-agent`."
  [{:keys [worker spec subagent-id]}]
  (or worker
      (let [llm-agent (requiring-resolve 'dvergr.discourse.llm/llm-agent)]
        (llm-agent {:id    (or (:id spec) (keyword (str "sub-" subagent-id)))
                    :spec  (select-keys spec [:provider :model :system-prompt])
                    :tools (:tools spec)}))))

(defn hire!
  "Delegate `goal` to an ephemeral subagent forked off `room`.

   opts:
     :goal        — the task (required)
     :worker      — a Participant to host (tests), OR
     :spec        — {:id :provider :model :system-prompt :tools} → a fresh llm-agent
     :accept-fn   — (fn [reply] -> bool) merge vs discard (default: merge on reply)
     :timeout-ms  — wall clock (default 120000)
     :isolation   — :ctx (default; branches substrate + shared CRDTs) | :none

   Returns a Spin yielding {:status :result :subagent-id}."
  [room {:keys [goal accept-fn timeout-ms isolation] :as opts
         :or   {timeout-ms 120000 isolation :ctx}}]
  (let [sid    (random-uuid)
        worker (build-worker (assoc opts :subagent-id sid))]
    (sp/spin
     (track! room {:subagent/id sid :goal goal :worker (:id worker) :status :running})
     (let [r (sp/await
              (d/hire room worker (cond-> {:goal goal :isolation isolation :timeout-ms timeout-ms}
                                    accept-fn (assoc :accept-fn accept-fn))))]
       (track! room {:subagent/id sid :status (:status r)
                     :result (some-> (:reply r) :content)})
       {:status (:status r) :result (some-> (:reply r) :content) :subagent-id sid}))))

(defn pending
  "The still-running subagents spawned in `room` — a scan of the durable
   `:dvergr/subagent` lifecycle log (latest event per id is `:running`)."
  [room]
  (->> (d/log room)
       (filter #(= :dvergr/subagent (:type %)))
       (group-by :subagent/id)
       (keep (fn [[_ evs]] (let [latest (last evs)]
                             (when (= :running (:status latest)) latest))))
       vec))
