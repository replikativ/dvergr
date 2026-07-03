(ns dvergr.agent.subagent
  "Programmable-from-SCI subagent delegation.

   `hire!` forks a subroom off the caller's room with `:isolation :ctx` — so the
   worker runs on a BRANCHED substrate that inherits the room's registered
   yggdrasil systems (datahike workspace + CRDTs). It hosts an ephemeral worker,
   delegates a goal, and merges the fork back (parent-controlled `accept-fn`) or
   discards it. The worker's CRDT/DB edits fold back into the parent on merge —
   the fork/merge that opencode/codex can't do.

   Returns a **Spin** yielding
     {:status :merged|:discarded|:timeout|:refused|:error :result :subagent-id}
   so an agent can `(await (room/hire …))` in the foreground OR hold the spin and
   await it later (background). Nests like rooms: a subagent can itself `hire!` —
   bounded by `max-depth`, and each worker's budget is ATTENUATED by default.

   Lifecycle is DURABLY TRACKED as `:dvergr/subagent` tagged messages in the
   parent room (spawn → `:running`, finish → the terminal status) — the same
   log-as-truth pattern as `dvergr.discourse/propose-merge!`. When the room has a
   `:store` those events persist; `pending` enumerates the still-running ones.
   (A dedicated system-db `:subagent/*` entity is the more robust cross-room /
   store-less option — a follow-up.)"
  (:require [dvergr.discourse :as d]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.spin.combinators :as comb]))

(defn- track!
  "Post a `:dvergr/subagent` lifecycle event into `room`'s log (durable when the
   room has a store)."
  [room ev]
  (d/post! room (assoc ev :type :dvergr/subagent :from :subagent-tracker)))

(def default-worker-budget
  "Budget ATTENUATION: an ephemeral worker gets a deliberately small dollar
   budget unless the hirer grants more — a subagent must never be able to
   spend like its principal by default."
  {:dollars 0.25})

(def max-depth
  "Structural runaway guard: a hire chain deeper than this is refused.
   Each hire stamps `:subagent-depth` into the FORK room's meta, so a
   worker hiring from inside its fork sees its own depth."
  3)

(defn- build-worker
  "Construct the ephemeral worker Participant. Accepts an explicit `:worker`
   (e.g. a scripted participant for tests) or a `:spec`
   {:id :provider :model :system-prompt :tools} → a fresh `llm-agent` with an
   ATTENUATED budget (`default-worker-budget` unless `:budget` grants more).

   `:ctx` (the caller's execution context) is REQUIRED for a real llm-agent: its
   turn bridges the blocking LLM call through a future and must re-bind the ctx
   when delivering back (without it: 'no execution context bound'). This mirrors
   `dvergr.orchestration.daemon/create-agent!`, which passes `:ctx`."
  [{:keys [worker spec budget subagent-id ctx]}]
  (or worker
      (let [llm-agent (requiring-resolve 'dvergr.discourse.llm/llm-agent)]
        (llm-agent (cond-> {:id     (or (:id spec) (keyword (str "sub-" subagent-id)))
                            :spec   (select-keys spec [:provider :model :system-prompt])
                            :tools  (:tools spec)
                            :budget (or budget default-worker-budget)}
                     ctx (assoc :ctx ctx))))))

(defn hire!
  "Delegate `goal` to an ephemeral subagent forked off `room`.

   opts:
     :goal        — the task (required)
     :worker      — a Participant to host (tests), OR
     :spec        — {:id :provider :model :system-prompt :tools} → a fresh llm-agent
     :budget      — worker dollar budget (default `default-worker-budget` — attenuated)
     :accept-fn   — (fn [reply] -> bool) merge vs discard (default: merge on reply)
     :timeout-ms  — wall clock (default 120000)
     :isolation   — :ctx (default; branches substrate + shared CRDTs) | :none

   Refuses (status :refused) when the hire chain is deeper than `max-depth`
   (each hire stamps :subagent-depth into its fork's meta). A throw inside
   the delegation DISCARDS the fork (never merge partial state) and yields
   status :error.

   Returns a Spin yielding {:status :result :subagent-id}."
  [room {:keys [goal accept-fn timeout-ms isolation] :as opts
         :or   {timeout-ms 120000 isolation :ctx accept-fn (constantly true)}}]
  (let [sid   (random-uuid)
        depth (or (some-> (:meta room) deref :subagent-depth) 0)]
    (sp/spin
     (if (>= depth max-depth)
       (do (track! room {:subagent/id sid :goal goal :status :refused
                         :reason (str "hire depth " depth " >= max " max-depth)})
           {:status :refused :result nil :subagent-id sid})
       (do
         (track! room {:subagent/id sid :goal goal :status :running})
         ;; Fork FIRST, then build the worker in the FORK's ctx — a real llm-agent
         ;; re-binds its :ctx across the LLM-call future, so it must match the ctx
         ;; the worker actually runs in (the fork's), not the parent's.
         (let [fork (d/fork-room room {:isolation isolation})]
           (swap! (:meta fork) assoc :subagent-depth (inc depth))
           (try
             (let [worker (build-worker (assoc opts :subagent-id sid :ctx (:ctx fork)))
                   _      (d/join fork worker)
                   reply  (sp/await (comb/timeout (d/ask fork (:id worker) {:content goal})
                                                  timeout-ms ::timeout))
                   status (cond (= reply ::timeout) :timeout
                                (accept-fn reply)   :merged
                                :else               :discarded)]
               (if (= status :merged) (d/merge-room room fork) (d/discard fork))
               (let [result (when (not= reply ::timeout) (:content reply))]
                 (track! room {:subagent/id sid :status status :result result})
                 {:status status :result result :subagent-id sid :fork-ref (:id fork)}))
             (catch Throwable t
               ;; NEVER merge partial state on error — discard the fork, then
               ;; surface the failure in the tracking log + return value.
               (try (d/discard fork) (catch Throwable _ nil))
               (track! room {:subagent/id sid :status :error :result (.getMessage t)})
               {:status :error :result (.getMessage t)
                :subagent-id sid :fork-ref (:id fork)}))))))))

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
