(ns dvergr.agent.program-bench
  "Opt-in, model-backed REPL environments for the agent programming surface.

   These are deliberately not CI tests: responses, latency, and provider access
   are nondeterministic and consume subscription resources. Each environment has
   a provider-independent verifier over durable Room/Run facts, so the reported
   reward never depends on trusting the model's prose. The corresponding
   language and lifecycle contracts live in ordinary deterministic tests."
  (:require [clojure.edn :as edn]
            [datahike.api :as dh]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.program :as program]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.chat.schema :as chat-schema]
            [dvergr.discourse :as d]
            [dvergr.resource :as resource]
            [dvergr.room.store :as room-store]
            [dvergr.room.store.datahike :as datahike-store]
            [dvergr.room.store.memory :as memory]
            [kontor.governance :as kontor-governance]
            [kontor.resource :as kontor-resource]
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

(def resource-task-v1
  (str "Use clojure_eval to solve this task. Your Run owns 10000 microUSD. "
       "Construct an immutable roster with two scripted specialists: an "
       ":analyst that waits 1000 milliseconds and replies \"evidence\", and a "
       ":reviewer that waits 1000 milliseconds and replies {:claim 42}. Hire "
       "the analyst with 2000 microUSD and the reviewer with 3000 microUSD. "
       "Compositionally await both result Spins, then record (agent/balance) "
       "after both resources have returned. Your final answer must be exactly "
       "this EDN value: {:analyst \"evidence\" :reviewer {:claim 42} "
       ":returned {\"microUSD\" 10000M}}. "
       "Do not merely describe the code; execute it."))

(def expected-resource-v1
  {:analyst "evidence"
   :reviewer {:claim 42}
   :returned {resource/microdollars 10000M}})

(def self-programming-task-v1
  (str "Use clojure_eval to author and execute a recursive Dvergr program. "
       "Construct an immutable roster with three cheap simulated specialists. "
       "A :mod-five particle is scripted to return [8 23 38 53 68 83 98]. "
       "A :mod-seven particle is scripted to return "
       "[2 9 16 23 30 37 44 51 58 65 72 79 86 93]. "
       "A :verifier specialist is scripted to return the independent data "
       "{:moduli [[3 2] [5 3] [7 2]] :upper-bound 100}. Hire all three and "
       "compositionally await their result Spins in parallel. Intersect the "
       "particle candidates, interpret the verifier data as executable checks, "
       "and select the unique positive integer satisfying every remainder and "
       "the bound. Return only an EDN map shaped like "
       "{:answer <computed-integer> :particles 2 :verified <boolean>}. Do not "
       "merely describe the program; create the specialists, await their "
       "results, and execute it."))

(def expected-self-programming-v1
  {:answer 23 :particles 2 :verified true})

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

(defn- self-programming-checks
  [{:keys [root-run-id root-causes child-runs] :as observation}]
  (let [by-actor (into {} (map (juxt :run/actor identity)) child-runs)
        child-ids (into #{} (map :run/id) child-runs)]
    (merge
     (common-checks observation expected-self-programming-v1)
     {:three-specialists? (= 3 (count child-runs))
      :expected-specialists? (= #{:mod-five :mod-seven :verifier}
                                (set (keys by-actor)))
      :structural-parentage? (every? #(= root-run-id (:run/parent %)) child-runs)
      :all-results-observed? (= child-ids root-causes)
      :specialists-completed? (every? #(= :completed (:run/status %)) child-runs)
      :specialists-merged? (every? #(= :merged (:run/settlement-status %))
                                   child-runs)})))

(defn- receipt-view [receipt]
  (select-keys receipt [:id :kind :source :destination :resources]))

(defn- resource-checks
  [{:keys [room-id root-run-id child-runs room-balance root-balance child-balances
           resource-receipts]
    :as observation}]
  (let [by-actor (into {} (map (juxt :run/actor identity)) child-runs)
        analyst-id (get-in by-actor [:analyst :run/id])
        reviewer-id (get-in by-actor [:reviewer :run/id])
        room-wallet (resource/room-wallet-id room-id)
        receipt-contract
        {:root-allocation
         {:id (resource/allocation-id root-run-id)
          :kind :grant :source room-wallet :destination root-run-id
          :resources {resource/microdollars 10000M}}
         :analyst-allocation
         {:id (resource/allocation-id analyst-id)
          :kind :grant :source root-run-id :destination analyst-id
          :resources {resource/microdollars 2000M}}
         :reviewer-allocation
         {:id (resource/allocation-id reviewer-id)
          :kind :grant :source root-run-id :destination reviewer-id
          :resources {resource/microdollars 3000M}}
         :analyst-return
         {:id (resource/return-id analyst-id)
          :kind :return :source analyst-id :destination root-run-id
          :resources {resource/microdollars 2000M}}
         :reviewer-return
         {:id (resource/return-id reviewer-id)
          :kind :return :source reviewer-id :destination root-run-id
          :resources {resource/microdollars 3000M}}
         :root-return
         {:id (resource/return-id root-run-id)
          :kind :return :source root-run-id :destination room-wallet
          :resources {resource/microdollars 10000M}}}]
    (merge
     (common-checks observation expected-resource-v1)
     {:two-children? (= 2 (count child-runs))
      :expected-actors? (= #{:analyst :reviewer} (set (keys by-actor)))
      :structural-parentage? (every? #(= root-run-id (:run/parent %)) child-runs)
      :children-completed? (every? #(= :completed (:run/status %)) child-runs)
      :children-merged? (every? #(= :merged (:run/settlement-status %)) child-runs)
      :room-resources-conserved? (= {resource/microdollars 20000M} room-balance)
      :root-wallet-returned? (empty? root-balance)
      :child-wallets-returned? (every? empty? (vals child-balances))
      :canonical-resource-receipts? (= receipt-contract resource-receipts)})))

(defn- memory-environment [room-id _definition]
  (let [room (d/make-room {:id room-id :store (memory/make)})]
    {:room room
     :close! #(d/close-room! room)}))

(defn- close-resource-environment! [cfg conn room]
  (try
    (when room (d/close-room! room))
    (finally
      (try
        (kontor-governance/ungovern! conn)
        (finally
          (dh/release conn)
          (dh/delete-database cfg))))))

(defn- resource-environment [room-id definition]
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :keep-history? true
             :schema-flexibility :write}
        chat-id (random-uuid)
        {:keys [room-resources run-resources]}
        (:environment/limits definition)]
    (dh/create-database cfg)
    (let [conn (try
                 (dh/connect cfg)
                 (catch Throwable error
                   (try
                     (dh/delete-database cfg)
                     (catch Throwable cleanup-error
                       (.addSuppressed error cleanup-error)))
                   (throw error)))
          room* (atom nil)]
      (try
        (chat-schema/ensure-full-schema! conn)
        (dh/transact conn
                     [(merge (chat-schema/create-chat-entity
                              {:id chat-id :title (name room-id)})
                             {:room/slug (room-store/room-id->slug room-id)
                              :room/type :internal})])
        (resource/install-connection! conn room-id chat-id)
        (let [room (d/make-room {:id room-id
                                 :store (datahike-store/make conn)})]
          (reset! room* room)
          (resource/mint! room {:id (random-uuid)
                                :resources room-resources})
          {:room room
           :hire-opts {:resources run-resources}
           :resource-observation
           (fn [root-run-id child-runs]
             (let [by-actor (into {} (map (juxt :run/actor identity)) child-runs)
                   analyst-id (get-in by-actor [:analyst :run/id])
                   reviewer-id (get-in by-actor [:reviewer :run/id])
                   receipt (fn [id]
                             (some-> (kontor-resource/receipt conn id)
                                     receipt-view))]
               {:room-balance (resource/balance room)
                :root-balance (resource/run-balance room root-run-id)
                :child-balances
                (into {}
                      (map (fn [child]
                             [(:run/id child)
                              (resource/run-balance room (:run/id child))]))
                      child-runs)
                :resource-receipts
                {:root-allocation (receipt (resource/allocation-id root-run-id))
                 :analyst-allocation (receipt (resource/allocation-id analyst-id))
                 :reviewer-allocation (receipt (resource/allocation-id reviewer-id))
                 :analyst-return (receipt (resource/return-id analyst-id))
                 :reviewer-return (receipt (resource/return-id reviewer-id))
                 :root-return (receipt (resource/return-id root-run-id))}}))
           :close! #(close-resource-environment! cfg conn room)})
        (catch Throwable error
          (try
            (close-resource-environment! cfg conn @room*)
            (catch Throwable cleanup-error
              (.addSuppressed error cleanup-error)))
          (throw error))))))

(def ^:private memory-setup-ref
  {:setup/id :dvergr.environment/memory-room :setup/version 1})

(def ^:private resource-setup-ref
  {:setup/id :dvergr.environment/resource-room :setup/version 1})

(def ^:private trusted-setups
  {memory-setup-ref memory-environment
   resource-setup-ref resource-environment})

(def ^:private trusted-verifiers
  {#:verifier{:id :programming/join-checks-v1 :version 1} join-checks
   #:verifier{:id :programming/race-checks-v1 :version 1} race-checks
   #:verifier{:id :programming/self-programming-checks-v1 :version 1}
   self-programming-checks
   #:verifier{:id :programming/resource-delegation-checks-v1 :version 1}
   resource-checks})

(defn- environment-case
  [id task verifier-id expected & [{:keys [setup limits world metadata]}]]
  {:definition
   (environment/make-environment
    {:id id
     :version 1
     :task task
     :verifier {:id verifier-id :version 1}
     :limits (merge {:timeout-ms 120000
                     :cancel-timeout-ms 10000
                     :max-model-steps 8
                     :budget-dollars 1.0}
                    limits)
     :world (merge {:isolation :ctx
                    :settlement :automatic
                    :setup (or setup memory-setup-ref)}
                   world)
     :metadata metadata})
   :expected expected})

(def ^:private environments
  {:programming/join-v1
   (environment-case :programming/join-v1 task-v1
                     :programming/join-checks-v1 expected-v1)

   :programming/race-v1
   (environment-case :programming/race-v1 race-task-v1
                     :programming/race-checks-v1 expected-race-v1)

   :programming/self-programming-v1
   (environment-case :programming/self-programming-v1 self-programming-task-v1
                     :programming/self-programming-checks-v1
                     expected-self-programming-v1
                     {:metadata {:kind :recursive-agent-program}})

   :programming/resource-delegation-v1
   (environment-case :programming/resource-delegation-v1 resource-task-v1
                     :programming/resource-delegation-checks-v1
                     expected-resource-v1
                     {:setup resource-setup-ref
                      :limits {:room-resources {resource/microdollars 20000M}
                               :run-resources {resource/microdollars 10000M}}
                      :metadata {:kind :conserved-resource-delegation}})})

(defn environment-definition
  "Return the exact portable definition for a named benchmark environment."
  [environment-id]
  (some-> (get environments environment-id) :definition))

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
  (let [{:keys [definition expected]}
        (or (get environments environment-id)
            (throw (ex-info "Unknown programming environment"
                            {:environment-id environment-id
                             :known (set (keys environments))})))
        definition (environment/validate-environment definition)
        task (:environment/task definition)
        verifier-ref (:environment/verifier definition)
        verify (or (get trusted-verifiers verifier-ref)
                   (throw (ex-info "Environment names an untrusted verifier"
                                   {:environment-id environment-id
                                    :verifier verifier-ref
                                    :trusted (set (keys trusted-verifiers))})))
        {:keys [timeout-ms cancel-timeout-ms max-model-steps budget-dollars]}
        (:environment/limits definition)
        {:keys [isolation settlement setup]} (:environment/world definition)
        _ (when-not (= :ctx isolation)
            (throw (ex-info "Benchmark runner only supports isolated ctx worlds"
                            {:environment-id environment-id
                             :isolation isolation})))
        setup-environment (or (get trusted-setups setup)
                              (throw (ex-info "Environment names an untrusted setup"
                                              {:environment-id environment-id
                                               :setup setup
                                               :trusted (set (keys trusted-setups))})))
        room-id (keyword (str "programming-bench-" (random-uuid)))
        {:keys [room hire-opts resource-observation close!]}
        (setup-environment room-id definition)
        team (roster/make-agent
              (roster/make-roster {:id :benchmark})
              {:id :orchestrator
               :prompt (str "Exercise Dvergr through clojure_eval. Complete the "
                            "requested computation before answering.")
               :tools #{:clojure_eval}
               :model-policy {:provider provider :model model}
               :program {:kind :llm
                         :max-model-steps max-model-steps
                         :budget-dollars budget-dollars
                         :auto-compact? false}})
        started (System/nanoTime)]
    (try
      (let [handle (binding [ec/*execution-context* (:ctx room)]
                     (program/hire! room team :orchestrator
                                    (merge hire-opts
                                           {:task task :settlement settlement})))
            initial-result (binding [ec/*execution-context* (:ctx room)]
                             (deref handle timeout-ms ::timeout))
            timed-out? (= ::timeout initial-result)
            _ (when timed-out?
                (binding [ec/*execution-context* (:ctx room)]
                  (program/cancel! handle)))
            result (if timed-out?
                     (binding [ec/*execution-context* (:ctx room)]
                       (deref handle cancel-timeout-ms
                              {:run/id (program/run-id handle)
                               :run/status :failed
                               :run/error (str "Cancellation did not quiesce within "
                                               cancel-timeout-ms "ms")}))
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
                         :root-causes (set (:run/caused-by durable))
                         :room-id room-id
                         :root-run-id root-run-id
                         :child-runs child-runs
                         :active-after (count (run/active-runs room-id))}
            observation (merge observation
                               (when resource-observation
                                 (resource-observation root-run-id child-runs)))
            checks (verify observation)
            passed? (every? true? (vals checks))]
        (cond->
         {:environment environment-id
          :environment-ref (environment/environment-ref definition)
          :environment-definition definition
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
          :runs (mapv #(select-keys % [:run/id :run/parent :run/caused-by :run/actor
                                      :run/status :run/error
                                      :run/settlement-status])
                      all-runs)
          :tool-calls
          (mapv (fn [message]
                  (mapv #(select-keys % [:tool-use/name :tool-use/input])
                        (get-in message [:metadata :tool-uses])))
                activity)
          :active-after (:active-after observation)}
          resource-observation
          (assoc :resources
                 (select-keys observation
                              [:room-balance :root-balance :child-balances
                               :resource-receipts]))))
      (finally
        (close!)))))

(defn run-v1!
  "Compatibility entry point for the original parallel-join environment."
  [provider model]
  (run-environment! :programming/join-v1 provider model))

(defn run-race-v1!
  "Run the ownership-aware race and loser-cancellation environment."
  [provider model]
  (run-environment! :programming/race-v1 provider model))

(defn run-resource-v1!
  "Run conserved sibling delegation and affine resource-return environment."
  [provider model]
  (run-environment! :programming/resource-delegation-v1 provider model))

(defn run-self-programming-v1!
  "Run the model-authored particle and verifier environment."
  [provider model]
  (run-environment! :programming/self-programming-v1 provider model))
