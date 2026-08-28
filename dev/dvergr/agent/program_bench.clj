(ns dvergr.agent.program-bench
  "Opt-in, subscription-backed REPL probes for the agent programming surface.

   These are deliberately not CI tests: responses, latency, and provider access
   are nondeterministic and consume subscription resources. The corresponding
   provider-free language and lifecycle contracts live in ordinary tests. Run
   this namespace interactively to compare model comprehension using one fixed
   task and the exact production prompt/tool path."
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

(defn run-v1!
  "Run programming benchmark v1 through `provider`/`model`.

   Intended REPL usage:

     (run-v1! :codex-subscription \"codex-subscription-sol\")
     (run-v1! :claude-code \"claude-code-sonnet\")

   The returned report includes generated SCI calls so a failure distinguishes
   language/API confusion from model or transport failure."
  [provider model]
  (let [room-id (keyword (str "programming-bench-" (random-uuid)))
        room (d/make-room {:id room-id :store (memory/make)})
        team (roster/make-agent
              (roster/make-roster {:id :benchmark})
              {:id :orchestrator
               :prompt (str "Exercise Dvergr through clojure_eval. Complete the "
                            "requested computation before answering.")
               :tools #{:clojure_eval}
               :model-policy {:provider provider :model model}
               :program {:kind :llm
                         :max-model-steps 8
                         :budget-dollars 1.0
                         :auto-compact? false}})
        started (System/nanoTime)]
    (try
      (let [handle (binding [ec/*execution-context* (:ctx room)]
                     (program/hire! room team :orchestrator {:task task-v1}))
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
            child-runs (remove #(= root-run-id (:run/id %)) all-runs)]
        {:benchmark :programming/v1
         :provider provider
         :model model
         :task task-v1
         :expected expected-v1
         :passed? (and (= :completed (:run/status result))
                       (= expected-v1 parsed)
                       (= 2 (count child-runs))
                       (= #{:analyst :reviewer} (set (map :run/actor child-runs)))
                       (every? #(= root-run-id (:run/parent %)) child-runs)
                       (every? #(= :completed (:run/status %)) child-runs))
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
         :active-after (count (run/active-runs room-id))})
      (finally
        (d/close-room! room)))))
