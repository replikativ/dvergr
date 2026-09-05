(ns dvergr.agent.program-bench
  "Opt-in, model-backed REPL environments for the agent programming surface.

   These are deliberately not CI tests: responses, latency, and provider access
   are nondeterministic and consume subscription resources. Each environment has
   a provider-independent verifier over durable Room/Run facts, so the reported
   reward never depends on trusting the model's prose. The corresponding
   language and lifecycle contracts live in ordinary deterministic tests."
  (:require [clojure.edn :as edn]
            [datahike.api :as dh]
            [dvergr.activity :as activity]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.experiment :as experiment]
            [dvergr.agent.observation :as observation]
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

(def effect-safe-repl-prompt-v1
  "Exercise Dvergr through clojure_eval and complete the requested computation before answering. Treat calls ending in ! as durable effects: inspect or validate their complete call shape before executing them, execute each requested effect exactly once, and never run documentation examples as probes. agent/hire! takes an options map; put every task value, including a map-valued task, under the :task key. If a later pure composition fails, reuse the handles already created instead of hiring again.")

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
       "Use the exact namespaces `[dvergr.agent :as agent]`, "
       "`[org.replikativ.spindel.spin.cps :refer [spin]]`, "
       "`[org.replikativ.spindel.effects.await :refer [await]]`, and "
       "`[spindel.comb :as comb]`; compose the three result Spins with "
       "`comb/parallel`. "
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

(def renewal-risk-task-v1
  (str "Use clojure_eval to build and execute a small business-analysis harness. "
       "Construct an immutable roster with two cheap simulated specialists. "
       "The :sales specialist is scripted to return "
       "{:account :acme :renewal 120000 :days 14}. The :support specialist is "
       "scripted to return {:account :acme :severity :high :open-critical 3}. "
       "Hire and compositionally await both specialists. Then call "
       "(agent/inspect) from this Run and derive its Run count, active count, "
       "and actor set; do not guess them. Combine the evidence into exactly "
       "this EDN shape, setting :scope to the UUID returned by "
       ":observation/scope-run-id and :inspection to the UUID returned by "
       ":observation/receipt-id rather than guessing either: "
       "{:account :acme :risk :high :renewal 120000 "
       ":open-critical 3 :observed-runs 3 :active 1 "
       ":observed-actors #{:orchestrator :sales :support} "
       ":scope <UUID> :inspection <UUID>}. "
       "The host Room also "
       "contains private work outside your structural Run tree; it must not "
       "appear in agent/inspect. Execute the program rather than describing it."))

(def expected-renewal-risk-v1
  {:account :acme
   :risk :high
   :renewal 120000
   :open-critical 3
   :observed-runs 3
   :active 1
   :observed-actors #{:orchestrator :sales :support}})

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
  [{:keys [result run-result parsed-value durable-status active-after]} expected]
  (let [execution-result (or run-result result)]
    {:root-completed? (= :completed (:run/status execution-result))
     :durably-completed? (= :completed durable-status)
     :exact-result? (= expected parsed-value)
     :quiescent? (zero? active-after)}))

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

(defn- renewal-risk-checks
  [{:keys [result run-result parsed-value durable-status active-after root-run-id
           root-causes child-runs inspection-receipts]}]
  (let [execution-result (or run-result result)
        owned (filterv #(= root-run-id (:run/parent %)) child-runs)
        outside (remove #(= root-run-id (:run/parent %)) child-runs)
        owned-ids (into #{} (map :run/id) owned)
        reported (dissoc parsed-value :scope :inspection)]
    {:root-completed? (= :completed (:run/status execution-result))
     :durably-completed? (= :completed durable-status)
     :quiescent? (zero? active-after)
     :business-result? (= expected-renewal-risk-v1 reported)
     :scope-from-observation? (= root-run-id (:scope parsed-value))
     :inspection-executed?
     (and (contains? inspection-receipts (:inspection parsed-value))
          (observation/consume-receipt! root-run-id
                                        (:inspection parsed-value)))
     :two-specialists? (= 2 (count owned))
     :expected-specialists? (= #{:sales :support}
                               (set (map :run/actor owned)))
     :all-results-observed? (= owned-ids root-causes)
     :specialists-completed? (every? #(= :completed (:run/status %)) owned)
     :private-control-run-present? (= #{:private-audit}
                                      (set (map :run/actor outside)))
     :private-control-run-not-reported?
     (not (contains? (:observed-actors parsed-value) :private-audit))}))

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

(defn- renewal-risk-environment [room-id _definition]
  (let [room (d/make-room {:id room-id :store (memory/make)})
        private-run (run/start! room :private-audit (random-uuid) nil)]
    (run/finish! (:run/id private-run) :completed)
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

(def ^:private renewal-risk-setup-ref
  {:setup/id :dvergr.environment/renewal-risk-room :setup/version 1})

(def ^:private trusted-setups
  {memory-setup-ref memory-environment
   resource-setup-ref resource-environment
   renewal-risk-setup-ref renewal-risk-environment})

(def ^:private trusted-world-setups
  (into {}
        (map (fn [ref]
               [ref (evaluation/make-world-setup
                     {:id (:setup/id ref)
                      :version (:setup/version ref)
                      :basis (:setup/basis ref)
                      ;; The current Room factories establish their shared
                      ;; baseline before the experiment. This exact per-Run
                      ;; hook still closes the semantic setup gate and is where
                      ;; fork-local scenario transactions compose next.
                      :prepare (fn [_] {:setup/ref ref})})]))
        [memory-setup-ref resource-setup-ref renewal-risk-setup-ref]))

(def ^:private trusted-verifiers
  {#:verifier{:id :programming/join-checks-v1 :version 1} join-checks
   #:verifier{:id :programming/race-checks-v1 :version 1} race-checks
   #:verifier{:id :programming/self-programming-checks-v1 :version 1}
   self-programming-checks
   #:verifier{:id :programming/resource-delegation-checks-v1 :version 1}
   resource-checks
   #:verifier{:id :business/renewal-risk-checks-v1 :version 1}
   renewal-risk-checks})

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
                     ;; Resource/time limits govern normal execution. This is
                     ;; only a runaway fuse for a malfunctioning provider loop.
                     :max-model-steps 16
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
                      :metadata {:kind :conserved-resource-delegation}})

   :business/renewal-risk-brief-v1
   (environment-case :business/renewal-risk-brief-v1 renewal-risk-task-v1
                     :business/renewal-risk-checks-v1
                     expected-renewal-risk-v1
                     {:setup renewal-risk-setup-ref
                      :metadata {:kind :business-workflow
                                 :domain :revenue-operations
                                 :capabilities #{:delegation
                                                 :scoped-observation
                                                 :evidence-synthesis}}})})

(defn environment-definition
  "Return the exact portable definition for a named benchmark environment."
  [environment-id]
  (some-> (get environments environment-id) :definition))

(def ^:private paired-environment-ids
  #{:programming/join-v1
    :programming/race-v1
    :programming/self-programming-v1})

(defn- paired-environment [environment-id]
  (when-not (contains? paired-environment-ids environment-id)
    (throw (ex-info
            "Environment requires trusted setup not yet supported by paired runs"
            {:environment-id environment-id
             :supported paired-environment-ids})))
  (let [definition (environment-definition environment-id)
        verifier (:environment/verifier definition)
        world (:environment/world definition)]
    (when-not (= memory-setup-ref (:setup world))
      (throw (ex-info "Paired environment does not use the memory baseline"
                      {:environment-id environment-id
                       :setup (:setup world)})))
    (environment/make-environment
     (cond-> {:id (:environment/id definition)
              :version (:environment/version definition)
              :task (:environment/task definition)
              :verifier
              (cond-> {:id (:verifier/id verifier)
                       :version (:verifier/version verifier)}
                (contains? verifier :verifier/basis)
                (assoc :basis (:verifier/basis verifier)))
              :limits (:environment/limits definition)
              :world (assoc world :settlement :discard)}
       (contains? definition :environment/metadata)
       (assoc :metadata (:environment/metadata definition))))))

(defn- scoped-observation
  [{:keys [room run-id result durable]}]
  (let [all-runs (run/runs room {:root-run-id run-id :limit 200})
        run-ids (into #{} (map :run/id) all-runs)
        trigger-ids (into #{} (keep :run/trigger) all-runs)
        messages (d/messages room {:limit 500
                                   :run-ids run-ids
                                   :message-ids trigger-ids})
        projection (observation/snapshot
                    room run-id
                    {:run-limit 200 :message-limit 500
                     :content-limit 1000 :content-budget 64000
                     :detail-limit 500})
        activity-messages (filter #(= :_activity (:to %)) messages)
        tool-trace (mapv activity/tool-trace-entry activity-messages)
        parsed (parse-edn (:run/value result))
        run-trace (mapv #(select-keys % [:run/id :run/parent :run/caused-by
                                         :run/actor :run/status :run/error
                                         :run/settlement-status])
                        all-runs)
        child-runs (filterv #(not= run-id (:run/id %)) run-trace)]
    (let [evidence
          {:run-result (select-keys result
                                    [:run/id :run/status :run/value :run/error
                                     :run/metrics :run/world
                                     :run/settlement-status])
           :result parsed
           :parsed-value parsed
           :durable-status (:run/status durable)
           :root-causes (set (:run/caused-by durable))
           :room-id (:id room)
           :root-run-id run-id
           :child-runs child-runs
           :tool-calls tool-trace
           :inspection-receipts
           (into #{}
                 (comp
                  (mapcat activity/message-activities)
                  (filter #(and (= run-id (:activity/run-id %))
                                (= :observation (:activity/kind %))
                                (= :inspect (:activity/verb %))))
                  (map :activity/id))
                 messages)
           :active-after (count (filter #(contains? #{:running :cancelling}
                                                    (:run/status %))
                                        run-trace))
           :trace {:runs run-trace
                   :messages (:observation/messages projection)
                   :tool-calls tool-trace}}]
      (when-not (roster/data-value? evidence)
        (throw (ex-info
                "Paired verifier produced non-portable evidence"
                {:non-portable
                 (into {}
                       (keep (fn [[k value]]
                               (when-not (roster/data-value? value)
                                 [k (some-> value class str)])))
                       evidence)})))
      evidence)))

(defn- paired-evaluator [definition]
  (let [verifier-ref (:environment/verifier definition)
        verify (or (get trusted-verifiers verifier-ref)
                   (throw (ex-info "Environment names an untrusted verifier"
                                   {:environment
                                    (environment/environment-ref definition)
                                    :verifier verifier-ref})))]
    (evaluation/make-evaluator
     (cond-> {:id (:verifier/id verifier-ref)
              :version (:verifier/version verifier-ref)
              :observe scoped-observation
              :verify (fn [_ evidence]
                        (let [checks (verify evidence)]
                          {:checks checks
                           :reward (if (every? true? (vals checks)) 1.0 0.0)}))}
       (contains? verifier-ref :verifier/basis)
       (assoc :basis (:verifier/basis verifier-ref))))))

(defn- candidate-agent [team {:keys [id provider model prompt] :as candidate}]
  (when-not (and (keyword? id) (keyword? provider) (string? model))
    (throw (ex-info "Candidate requires keyword :id/:provider and string :model"
                    {:candidate candidate})))
  (when-not (or (nil? prompt) (string? prompt))
    (throw (ex-info "Candidate :prompt must be a string when present"
                    {:candidate candidate})))
  (roster/make-agent
   team
   {:id id
    :prompt (or prompt
                (str "Exercise Dvergr through clojure_eval. Complete the "
                     "requested computation before answering."))
    :tools #{:clojure_eval}
    :model-policy {:provider provider :model model}
    :program {:kind :llm :max-model-steps 16
              :budget-dollars 1.0 :auto-compact? false}}))

(defn paired-experiment-spin
  "Build a repeated paired live-model experiment and return its lazy Spin.

   The caller owns `room`, so durable Runs and Attempts remain inspectable after
   completion. Candidates are maps with keyword `:id`/`:provider`, string
   `:model`, and an optional string `:prompt`; this makes prompt policy an exact
   content-addressed experimental variable. Supported environments currently
   need no setup beyond the shared memory baseline: join, race, and
   self-programming v1. Host execution options are `:parallelism`,
   `:max-parallelism`, `:max-attempts`, and an optional caller-owned
   `:cleanup-group` for operation-scoped cleanup."
  [room {:keys [environment-ids candidates repetitions id]
         :or {repetitions 1 id :live/paired-v1}
         :as opts}]
  (when-not (and (vector? environment-ids) (seq environment-ids))
    (throw (ex-info ":environment-ids must be a non-empty vector"
                    {:environment-ids environment-ids})))
  (when-not (and (vector? candidates) (seq candidates))
    (throw (ex-info ":candidates must be a non-empty vector"
                    {:candidates candidates})))
  (let [definitions (mapv paired-environment environment-ids)
        team (reduce candidate-agent
                     (roster/make-roster {:id :live/paired-candidates})
                     candidates)
        dataset (experiment/make-dataset
                 {:id (keyword (namespace id) (str (name id) "-dataset"))
                  :environments definitions
                  :metadata {:source :dvergr.agent.program-bench}})
        experiment-definition
        (experiment/make-experiment
         {:id id
          :dataset dataset
          :candidates (roster/agents team)
          :repetitions repetitions
          :metadata {:kind :live-model-comparison}})
        evaluators (into {}
                         (map (fn [definition]
                                (let [evaluator (paired-evaluator definition)]
                                  [(evaluation/evaluator-ref evaluator)
                                   evaluator])))
                         definitions)]
    (experiment/run room team experiment-definition evaluators
                    (assoc (select-keys opts [:parallelism :max-parallelism
                                              :max-attempts :cleanup-group])
                           :world-setups trusted-world-setups))))

(defn run-paired-experiment!
  "Convenience REPL entry point for a live paired experiment.

   Creates and closes an ephemeral in-memory Room, returning the portable
   ExperimentDef, execution summary, certified Attempts, and Scorecard. Use
   `paired-experiment-spin` with a caller-owned Room when interactive durable
   inspection is more important than convenience."
  [opts]
  (let [room-id (keyword (str "paired-bench-" (random-uuid)))
        room (d/make-room {:id room-id :store (memory/make)})
        cleanup-group (evaluation/cleanup-group)]
    (try
      (let [result (binding [ec/*execution-context* (:ctx room)]
                     @(paired-experiment-spin
                       room (assoc opts :cleanup-group cleanup-group)))]
        (select-keys result [:experiment :execution :attempts :scorecard]))
      (finally
        ;; Parallel failure cancels sibling evaluations immediately, while
        ;; their world discard runs off-drain. Join that physical cleanup
        ;; before invalidating the ephemeral Room context/store.
        (try
          (evaluation/await-cleanups-for! room cleanup-group)
          (catch Throwable error
            ;; The caller can recover/inspect this process-local Room from
            ;; ex-data. Closing here would destroy the fork authority whose
            ;; cleanup just failed.
            (throw (ex-info "Benchmark Room retained for cleanup recovery"
                            {:type ::cleanup-recovery-required
                             :room/id (:id room)
                             :room room}
                            error))))
        (d/close-room! room)))))

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
        started-at (System/currentTimeMillis)
        started-nanos (System/nanoTime)]
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
            tool-trace (mapv activity/tool-trace-entry activity)
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
                         :tool-calls tool-trace
                         :inspection-receipts
                         (into #{}
                               (comp
                                (mapcat activity/message-activities)
                                (filter #(and (= root-run-id (:activity/run-id %))
                                              (= :observation
                                                 (:activity/kind %))
                                              (= :inspect (:activity/verb %))))
                                (map :activity/id))
                               messages)
                         :active-after (count (run/active-runs room-id))}
            observation (merge observation
                               (when resource-observation
                                 (resource-observation root-run-id child-runs)))
            checks (verify observation)
            passed? (every? true? (vals checks))
            reward (if passed? 1.0 0.0)
            elapsed-ms (long (/ (- (System/nanoTime) started-nanos) 1000000))
            run-trace (mapv #(select-keys % [:run/id :run/parent :run/caused-by
                                             :run/actor :run/status :run/error
                                             :run/settlement-status])
                            all-runs)
            resources (when resource-observation
                        (select-keys observation
                                     [:room-balance :root-balance :child-balances
                                      :resource-receipts]))
            receipt-opts
            (cond-> {:run-id root-run-id
                     :provider provider
                     :model model
                     :status (:run/status result)
                     :started-at started-at
                     :elapsed-ms elapsed-ms
                     :metrics (merge (:run/metrics result)
                                     {:timed-out? timed-out?})
                     :checks checks
                     :reward reward
                     :result parsed
                     :trace {:runs run-trace :tool-calls tool-trace}}
              resources (assoc :resources resources))
            attempt-receipt
            (environment/make-attempt-receipt definition receipt-opts)]
        (cond->
         {:environment environment-id
          :environment-ref (environment/environment-ref definition)
          :environment-definition definition
          :provider provider
          :model model
          :task task
          :expected expected
          :checks checks
          :reward reward
          :passed? passed?
          :timed-out? timed-out?
          :elapsed-ms elapsed-ms
          :attempt-receipt attempt-receipt
          :prompt-id (get-in result [:run/metrics :prompt-id])
          :usage (get-in result [:run/metrics :usage])
          ;; Each activity row is a tool-bearing provider exchange. A successful
          ;; exact answer requires one final, non-tool provider exchange as well.
          :model-steps (cond-> (count activity)
                         (= :completed (:run/status result)) inc)
          :result result
          :parsed-value parsed
          :durable-status (:run/status durable)
          :runs run-trace
          :tool-calls tool-trace
          :active-after (:active-after observation)}
          resources (assoc :resources resources)))
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

(defn run-renewal-risk-v1!
  "Run the first simulated business workflow and recursive-observation eval."
  [provider model]
  (run-environment! :business/renewal-risk-brief-v1 provider model))
