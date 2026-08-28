(ns dvergr.agent.program-bench
  "Opt-in, model-backed REPL environments for the agent programming surface.

   These are deliberately not CI tests: responses, latency, and provider access
   are nondeterministic and consume subscription resources. Each environment has
   a provider-independent verifier over durable Room/Run facts, so the reported
   reward never depends on trusting the model's prose. The corresponding
   language and lifecycle contracts live in ordinary deterministic tests."
  (:require [clojure.edn :as edn]
            [dvergr.agent.program :as program]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.discourse :as d]
            [dvergr.room.store.memory :as memory]
            [org.replikativ.spindel.engine.core :as ec])
  (:import [java.io PushbackReader StringReader]))

(def task-v1
  (str "Use clojure_eval to solve this task. Construct an immutable roster "
       "containing an :analyst with a scripted reply of evidence and a "
       ":reviewer with an echo program. Hire both: ask the analyst to "
       "investigate and give the reviewer the map {:claim 42}. Compose their "
       "result Spins with Spindel await inside spin. Your final answer must be "
       "exactly this EDN value: {:analyst \"evidence\" :reviewer {:claim 42}}. "
       "Do not merely describe the code; execute it."))

(def expected-v1 {:analyst "evidence" :reviewer {:claim 42}})

(def race-task-v1
  (str "Use clojure_eval to solve this task. Construct an immutable roster with "
       "a :fast scripted agent that waits 10 milliseconds and replies :fast, "
       "and a :slow scripted agent that waits 5000 milliseconds and replies "
       ":slow. Hire both, then race their ownership-coupled result Spins with "
       "spindel.comb/race so the losing Run is really cancelled. Await the race "
       "compositionally in a spin. Your final answer must be exactly this EDN "
       "value: :fast. Do not merely describe the code; execute it."))

(def expected-race-v1 :fast)

(defn- parse-edn [value]
  (when (string? value)
    (with-open [reader (PushbackReader. (StringReader. value))]
      (try
        (let [eof (Object.)
              parsed (edn/read {:eof eof} reader)
              trailing (edn/read {:eof eof} reader)]
          (when (and (not (identical? eof parsed))
                     (identical? eof trailing))
            parsed))
        (catch Throwable _ nil)))))

(defn- common-checks
  [{:keys [result parsed-value durable-status active-after]} expected]
  {:root-completed? (= :completed (:run/status result))
   :durably-completed? (= :completed durable-status)
   :exact-result? (= expected parsed-value)
   :quiescent? (zero? active-after)})

(defn- join-checks [{:keys [root-run-id child-runs] :as observation}]
  (let [by-actor (group-by :run/actor child-runs)]
    (merge
     (common-checks observation expected-v1)
     {:two-children? (= 2 (count child-runs))
      :expected-actors? (= #{:analyst :reviewer} (set (keys by-actor)))
      :structural-parentage? (every? #(= root-run-id (:run/parent %)) child-runs)
      :children-completed? (every? #(= :completed (:run/status %)) child-runs)})))

(defn- race-checks [{:keys [root-run-id child-runs] :as observation}]
  (let [by-actor (into {} (map (juxt :run/actor identity)) child-runs)]
    (merge
     (common-checks observation expected-race-v1)
     {:two-children? (= 2 (count child-runs))
      :expected-actors? (= #{:fast :slow} (set (keys by-actor)))
      :structural-parentage? (every? #(= root-run-id (:run/parent %)) child-runs)
      :winner-completed? (= :completed (get-in by-actor [:fast :run/status]))
      :loser-cancelled? (= :cancelled (get-in by-actor [:slow :run/status]))})))

(def ^:private environments
  {:programming/join-v1
   {:task task-v1
    :expected expected-v1
    :max-model-steps 8
    :verify join-checks}

   :programming/race-v1
   {:task race-task-v1
    :expected expected-race-v1
    :max-model-steps 8
    :verify race-checks}})

(defn run-environment!
  "Run a named programming environment through `provider`/`model`.

   The returned `:checks` are computed by trusted host code from the exact
   parsed result plus durable Run projections. `:reward` is currently a strict
   binary reward: every check must pass. Generated SCI calls remain in the
   report so failures can be attributed to language/API confusion, provider
   behavior, or runtime semantics.

     (run-environment! :programming/join-v1 :codex-subscription
                       \"codex-subscription-sol\")
     (run-environment! :programming/race-v1 :claude-code
                       \"claude-code-sonnet\")"
  [environment-id provider model]
  (let [{:keys [task expected max-model-steps verify]}
        (or (get environments environment-id)
            (throw (ex-info "Unknown programming environment"
                            {:environment-id environment-id
                             :known (set (keys environments))})))
        room-id (keyword (str "programming-bench-" (random-uuid)))
        room (d/make-room {:id room-id :store (memory/make)})
        team (roster/make-agent
              (roster/make-roster {:id :benchmark})
              {:id :orchestrator
               :prompt (str "Exercise Dvergr through clojure_eval. Complete the "
                            "requested computation before answering.")
               :tools #{:clojure_eval}
               :model-policy {:provider provider :model model}
               :program {:kind :llm
                         :max-model-steps max-model-steps
                         :budget-dollars 1.0
                         :auto-compact? false}})
        started (System/nanoTime)]
    (try
      (let [handle (binding [ec/*execution-context* (:ctx room)]
                     (program/hire! room team :orchestrator {:task task}))
            initial-result (binding [ec/*execution-context* (:ctx room)]
                             (deref handle 120000 ::timeout))
            timed-out? (= ::timeout initial-result)
            _ (when timed-out?
                (binding [ec/*execution-context* (:ctx room)]
                  (program/cancel! handle)))
            result (if timed-out?
                     (binding [ec/*execution-context* (:ctx room)]
                       (deref handle 10000
                              {:run/id (program/run-id handle)
                               :run/status :failed
                               :run/error "Cancellation did not quiesce within 10s"}))
                     initial-result)
            durable (program/observe room handle)
            all-runs (run/runs room {:limit 20})
            messages (d/messages room {:limit 100})
            activity (filter #(= :_activity (:to %)) messages)
            parsed (parse-edn (:run/value result))
            root-run-id (program/run-id handle)
            child-runs (remove #(= root-run-id (:run/id %)) all-runs)
            observation {:result result
                         :parsed-value parsed
                         :durable-status (:run/status durable)
                         :root-run-id root-run-id
                         :child-runs child-runs
                         :active-after (count (run/active-runs room-id))}
            checks (verify observation)
            passed? (every? true? (vals checks))]
        {:environment environment-id
         :provider provider
         :model model
         :task task
         :expected expected
         :checks checks
         :reward (if passed? 1.0 0.0)
         :passed? passed?
         :timed-out? timed-out?
         :elapsed-ms (long (/ (- (System/nanoTime) started) 1000000))
         ;; Each activity row is a tool-bearing provider exchange. A successful
         ;; exact answer requires one final, non-tool provider exchange as well.
         :model-steps (cond-> (count activity)
                        (= :completed (:run/status result)) inc)
         :result result
         :parsed-value parsed
         :durable-status (:run/status durable)
         :runs (mapv #(select-keys % [:run/id :run/parent :run/actor
                                     :run/status :run/error])
                     all-runs)
         :tool-calls
         (mapv (fn [message]
                 (mapv #(select-keys % [:tool-use/name :tool-use/input])
                       (get-in message [:metadata :tool-uses])))
               activity)
         :active-after (:active-after observation)})
      (finally
        (d/close-room! room)))))

(defn run-v1!
  "Compatibility entry point for the original parallel-join environment."
  [provider model]
  (run-environment! :programming/join-v1 provider model))

(defn run-race-v1!
  "Run the ownership-aware race and loser-cancellation environment."
  [provider model]
  (run-environment! :programming/race-v1 provider model))
