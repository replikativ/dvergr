(ns dvergr.agent.program-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [dvergr.agent.program :as program]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.agent.turn :as turn]
            [dvergr.agent.world :as world]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.chat.context :as chat-context]
            [dvergr.chat.schema :as chat-schema]
            [dvergr.discourse :as d]
            [dvergr.model.chat :as model-chat]
            [dvergr.model.providers :as providers]
            [dvergr.resource :as resource]
            [dvergr.room.registry :as registry]
            [dvergr.rooms.forks :as forks]
            [dvergr.room.store :as room-store]
            [dvergr.room.store.memory :as memory]
            [dvergr.room.store.datahike :as datahike-store]
            [dvergr.tools :as tools]
            [kontor.governance :as kontor-governance]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.effects.await :refer [await]]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.spin.combinators :as comb]
            [org.replikativ.spindel.yggdrasil :as ygg]))

(defn- test-roster []
  (-> (roster/make-roster {:id :test-team})
      (roster/make-agent {:id :analyst
                          :skills #{:research}
                          :program {:kind :scripted :reply "evidence"}})
      (roster/make-agent {:id :reviewer
                          :skills #{:review}
                          :program {:kind :echo}})))

(defn- test-room [id]
  (d/make-room {:id id :store (memory/make)}))

(defn- datahike-test-room [id]
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :keep-history? false
             :schema-flexibility :write}]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)]
      (chat-schema/ensure-full-schema! conn)
      [(d/make-room {:id id :store (datahike-store/make conn)}) conn])))

(defn- resource-test-room [id]
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :keep-history? true
             :schema-flexibility :write}
        chat-id (random-uuid)]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)]
      (chat-schema/ensure-full-schema! conn)
      (dh/transact conn
                   [(merge (chat-schema/create-chat-entity
                            {:id chat-id :title (name id)})
                           {:room/slug (room-store/room-id->slug id)
                            :room/type :internal})])
      (resource/install-connection! conn id chat-id)
      [(d/make-room {:id id :store (datahike-store/make conn)}) conn])))

(defn- close-resource-test-room! [room conn]
  (kontor-governance/ungovern! conn)
  (d/close-room! room))

(defn- fail-terminal-store [delegate fail-terminal?]
  (reify room-store/PRoomStore
    (-store-room! [_ room-id metadata]
      (room-store/-store-room! delegate room-id metadata))
    (-load-room [_ id-or-slug]
      (room-store/-load-room delegate id-or-slug))
    (-delete-room! [_ room-id]
      (room-store/-delete-room! delegate room-id))
    (-list-rooms [_]
      (room-store/-list-rooms delegate))
    (-store-message! [_ room-id message]
      (room-store/-store-message! delegate room-id message))
    (-message-thread-root [_ room-id message-id]
      (room-store/-message-thread-root delegate room-id message-id))
    (-list-messages [_ room-id opts]
      (room-store/-list-messages delegate room-id opts))
    (-store-run! [_ room-id run]
      (when-not (and @fail-terminal?
                     (contains? room-store/terminal-run-statuses
                                (:run/status run)))
        (room-store/-store-run! delegate room-id run)))
    (-load-run [_ room-id run-id]
      (room-store/-load-run delegate room-id run-id))
    (-list-runs [_ room-id opts]
      (room-store/-list-runs delegate room-id opts))))

(defn- wait-until [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (>= (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 5) (recur))))))

(deftest hire-is-run-backed-and-output-correlated
  (let [room (test-room :program-hire)
        team (test-roster)]
    (try
      (let [handle (binding [ec/*execution-context* (:ctx room)]
                     (program/hire! room team :analyst {:task "investigate"}))
            result (binding [ec/*execution-context* (:ctx room)] @handle)
            durable (program/observe room handle)
            messages (d/messages room {:limit 10})
            trigger (first (filter #(= (:run/trigger durable) (:id %)) messages))
            output (first (filter #(= (:run/id durable)
                                      (get-in % [:metadata :run-id]))
                                  messages))]
        (is (= :completed (:run/status result)))
        (is (= "evidence" (:run/value result)))
        (is (= :agent-task (:run/kind durable)))
        (is (= :analyst (:run/actor durable)))
        (is (= :test-team (:run/roster durable)))
        (is (= 1 (:run/agent-version durable)))
        (is (= :scripted (:run/program-kind durable)))
        (is (= program/interpreter-version (:run/interpreter-version durable)))
        (is (uuid? (:run/agent-def-hash durable)))
        (is (= :ctx (:run/isolation durable)))
        (is (= :automatic (:run/settlement-policy durable)))
        (is (= :merged (:run/settlement-status durable)))
        (is (keyword? (:run/world durable)))
        (is (= "investigate" (:content trigger)))
        (is (= program/run-sink (:to trigger)))
        (is (= "evidence" (:content output)))
        (is (= program/run-sink (:to output)))
        (is (= (:id trigger) (:in-reply-to output)))
        (is (empty? (run/active-runs :program-hire))))
      (finally
        (d/close-room! room)))))

(deftest hire-splits-and-returns-a-conserved-resource-vector
  (let [[room conn] (resource-test-room :program-resources)
        team (roster/make-agent
              (roster/make-roster {:id :resource-team})
              {:id :worker
               :program {:kind :scripted :delay-ms 250 :reply :done}})]
    (try
      (resource/mint! room {:id (random-uuid)
                            :resources {resource/microdollars 10M}})
      (let [handle (binding [ec/*execution-context* (:ctx room)]
                     (program/hire! room team :worker
                                    {:task :work
                                     :resources {resource/microdollars 4M}}))]
        (is (wait-until #(= {resource/microdollars 4M}
                            (resource/run-balance room (program/run-id handle)))
                        2000))
        (is (= {resource/microdollars 6M} (resource/balance room)))
        (is (= :completed
               (:run/status
                (binding [ec/*execution-context* (:ctx room)] @handle))))
        ;; Public completion is durability-first: unused authority has already
        ;; returned through the stable receipt before the handle resolves.
        (is (= {resource/microdollars 10M} (resource/balance room)))
        (is (= {} (resource/run-balance room (program/run-id handle)))))
      (finally
        (close-resource-test-room! room conn)))))

(deftest ignored-owned-child-delays-parent-resource-settlement
  (let [[room conn] (resource-test-room :program-owned-child-resources)
        team (-> (roster/make-roster {:id :nested-resource-team})
                 (roster/make-agent
                  {:id :parent :program {:kind :scripted :reply :parent-done}})
                 (roster/make-agent
                  {:id :child :program {:kind :scripted :reply :child-done}}))
        parent-runtime (promise)
        status-key (keyword "dvergr.agent.program" "status")
        value-key (keyword "dvergr.agent.program" "value")
        execute-program
        (fn [control-room work-room run-id _chat-id agent _task _trigger supervisor]
          (if (= :parent (:agent/id agent))
            (sp/spin
             (deliver parent-runtime [supervisor work-room])
             (sp/await (comb/sleep 1500))
             {status-key :completed value-key :parent-done})
            (sp/spin
             (sp/await (comb/sleep 2500))
             {status-key :completed value-key :child-done})))]
    (try
      (resource/mint! room {:id (random-uuid)
                            :resources {resource/microdollars 10M}})
      (with-redefs-fn
        {#'program/execute-program execute-program}
        (fn []
          (binding [ec/*execution-context* (:ctx room)]
            (let [parent (program/hire!
                          room team :parent
                          {:task :coordinate
                           :resources {resource/microdollars 10M}})
                  [supervisor parent-work] (deref parent-runtime 2000
                                                  [::timeout nil])
                  child (binding [ec/*execution-context* (:ctx parent-work)]
                          (#'program/with-owned-child!
                           supervisor
                           #(do
                              (program/hire-in!
                               room parent-work team :child
                               {:task :delegated
                                :parent-run (program/run-id parent)
                                :resources {resource/microdollars 4M}}))))]
              (is (not= ::timeout supervisor))
              (is (= ::timeout (deref parent 1750 ::timeout))
                  "ignoring an owned handle cannot let the parent settle early")
              (is (= {} (resource/balance room)))
              (is (= {resource/microdollars 6M}
                     (resource/run-balance room (program/run-id parent))))
              (is (= {resource/microdollars 4M}
                     (resource/run-balance room (program/run-id child))))
              (is (= :child-done
                     (:run/value
                      (binding [ec/*execution-context* (:ctx parent-work)]
                        (deref child 5000 ::timeout)))))
              (is (= :parent-done (:run/value (deref parent 5000 ::timeout))))
              (is (= {resource/microdollars 10M} (resource/balance room)))
              (is (= {} (resource/run-balance room (program/run-id parent))))
              (is (= {} (resource/run-balance room (program/run-id child))))))))
      (finally
        (deliver parent-runtime [::closed nil])
        (close-resource-test-room! room conn)))))

(deftest cancellation-during-child-admission-retains-the-ownership-lease
  (let [[room conn] (resource-test-room :program-owned-child-cancel-race)
        team (-> (roster/make-roster {:id :cancel-race-team})
                 (roster/make-agent
                  {:id :parent :program {:kind :scripted :reply :parent-done}})
                 (roster/make-agent
                  {:id :child :program {:kind :scripted :reply :child-done}}))
        parent-runtime (promise)
        admission-entered (promise)
        release-admission (promise)
        status-key (keyword "dvergr.agent.program" "status")
        value-key (keyword "dvergr.agent.program" "value")
        execute-program
        (fn [_control-room work-room _run-id _chat-id agent _task _trigger supervisor]
          (if (= :parent (:agent/id agent))
            (sp/spin
             (deliver parent-runtime [supervisor work-room])
             (sp/await (comb/sleep 5000))
             {status-key :completed value-key :parent-done})
            (sp/spin
             (sp/await (comb/sleep 1000))
             {status-key :completed value-key :child-done})))]
    (try
      (resource/mint! room {:id (random-uuid)
                            :resources {resource/microdollars 10M}})
      (with-redefs-fn
        {#'program/execute-program execute-program}
        (fn []
          (binding [ec/*execution-context* (:ctx room)]
            (let [parent (program/hire!
                          room team :parent
                          {:task :coordinate
                           :resources {resource/microdollars 10M}})
                  [supervisor parent-work] (deref parent-runtime 2000
                                                  [::timeout nil])
                  child-future
                  (future
                    (binding [ec/*execution-context* (:ctx parent-work)]
                      (#'program/with-owned-child!
                       supervisor
                       #(do
                          (deliver admission-entered true)
                          @release-admission
                          (program/hire-in!
                           room parent-work team :child
                           {:task :delegated
                            :parent-run (program/run-id parent)
                            :resources {resource/microdollars 4M}})))))]
              (is (not= ::timeout supervisor))
              (is (= true (deref admission-entered 2000 ::timeout)))
              (is (program/cancel! parent))
              (is (= ::timeout (deref parent 100 ::timeout))
                  "the admission reservation prevents early parent settlement")
              (deliver release-admission true)
              (let [child (deref child-future 3000 ::timeout)]
                (is (not= ::timeout child))
                (is (run/cancel-requested? (program/run-id child)))
                (let [child-result
                      (binding [ec/*execution-context* (:ctx parent-work)]
                        (deref child 4000 ::timeout))]
                  (is (= :cancelled (:run/status child-result))
                      (pr-str {:result child-result
                               :durable (program/observe room child)})))
                (is (= :cancelled (:run/status (deref parent 5000 ::timeout))))
                (is (= {resource/microdollars 10M} (resource/balance room)))
                (is (= {} (resource/run-balance room (program/run-id parent))))
                (is (= {} (resource/run-balance room (program/run-id child)))))))))
      (finally
        (deliver parent-runtime [::closed nil])
        (deliver admission-entered false)
        (deliver release-admission true)
        (close-resource-test-room! room conn)))))

(deftest failed-resource-admission-starts-no-agent-effect
  (let [[room conn] (resource-test-room :program-resource-refusal)
        team (test-roster)]
    (try
      (resource/mint! room {:id (random-uuid)
                            :resources {resource/microdollars 3M}})
      (is (thrown? Throwable
                   (binding [ec/*execution-context* (:ctx room)]
                     (program/hire! room team :analyst
                                    {:task :too-expensive
                                     :resources {resource/microdollars 4M}}))))
      (is (= {resource/microdollars 3M} (resource/balance room)))
      (is (empty? (d/messages room {:limit 10})))
      (is (empty? (run/active-runs :program-resource-refusal)))
      (is (= :failed
             (:run/status
              (first (room-store/-list-runs (:store room) (:id room)
                                            {:limit 1})))))
      (finally
        (close-resource-test-room! room conn)))))

(deftest settlement-policy-controls-the-isolated-run-world
  (let [room (test-room :program-settlement)
        team (test-roster)]
    (try
      (testing "review retains an inspectable fork after execution"
        (let [handle (binding [ec/*execution-context* (:ctx room)]
                       (program/hire! room team :analyst
                                      {:task "proposal" :settlement :review}))
              result (binding [ec/*execution-context* (:ctx room)] @handle)
              durable (program/observe room handle)
              fork (binding [ec/*execution-context* (:ctx room)]
                     (registry/lookup (:run/world durable)))]
          (is (= :completed (:run/status result)))
          (is (= :review (:run/settlement-status result)))
          (is (= :review (:run/settlement-status durable)))
          (is (= (:run/id durable) (some-> fork :meta deref :run-id)))
          (is (= {:fork/purpose :run
                  :fork/owner (:run/id durable)
                  :fork/status :open}
                 (select-keys (d/fork-descriptor fork)
                              [:fork/purpose :fork/owner :fork/status])))
          (let [action (binding [ec/*execution-context* (:ctx room)]
                         (forks/merge! fork))]
            (is (:ok? action) (pr-str action)))
          (is (= :merged (:fork/status (d/fork-descriptor fork))))
          (is (= :merged (:run/settlement-status
                          (program/observe room handle))))))
      (testing "review authority can be promoted to durable governance"
        (let [handle (binding [ec/*execution-context* (:ctx room)]
                       (program/hire! room team :analyst
                                      {:task "governed proposal" :settlement :review}))
              _result (binding [ec/*execution-context* (:ctx room)] @handle)
              durable (program/observe room handle)
              fork (binding [ec/*execution-context* (:ctx room)]
                     (registry/lookup (:run/world durable)))
              proposal-id (random-uuid)
              prepared (atom nil)
              transfer (binding [ec/*execution-context* (:ctx room)]
                         (forks/adopt! fork proposal-id
                                       {:prepare! #(reset! prepared %)
                                        :abort! (fn [_])}))]
          (is (:ok? transfer) (pr-str transfer))
          (is (= proposal-id (:fork/owner @prepared)))
          (is (= :adopted (:run/settlement-status
                           (program/observe room handle))))
          (is (binding [ec/*execution-context* (:ctx room)]
                (nil? (registry/lookup (:run/world durable)))))
          ;; The governance owner, not the detached Run world, now settles it.
          (is (nil? (ygg/discard-fork! (:fork/handle transfer))))
          (d/release-transferred-fork! transfer)))
      (testing "discard removes a successful work plane"
        (let [handle (binding [ec/*execution-context* (:ctx room)]
                       (program/hire! room team :analyst
                                      {:task "ephemeral" :settlement :discard}))
              result (binding [ec/*execution-context* (:ctx room)] @handle)
              durable (program/observe room handle)]
          (is (= :discarded (:run/settlement-status result)))
          (is (= :policy (:run/settlement-reason durable)))
          (is (binding [ec/*execution-context* (:ctx room)]
                (nil? (registry/lookup (:run/world durable)))))))
      (finally
        (d/close-room! room)))))

(deftest run-handles-compose-through-spindel-await
  (let [room (test-room :program-compose)
        team (test-roster)]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [a (program/hire! room team :analyst {:task "research"})
              b (program/hire! room team :reviewer {:task {:review "claim"}})
              joined (sp/spin
                      [(-> (await (program/result-spin a)) :run/value)
                       (-> (await (program/result-spin b)) :run/value)])]
          (is (= ["evidence" {:review "claim"}] @joined))))
      (finally
        (d/close-room! room)))))

(deftest recursive-hire-separates-control-room-from-immediate-world-parent
  (let [control-room (test-room :program-recursive-control)
        parent-run (random-uuid)
        parent-world (binding [ec/*execution-context* (:ctx control-room)]
                       (world/open! control-room parent-run :review))
        work-parent (:work parent-world)
        team (test-roster)]
    (try
      (let [handle (binding [ec/*execution-context* (:ctx work-parent)]
                     (program/hire-in! control-room work-parent team :analyst
                                       {:task "nested"
                                        :parent-run parent-run
                                        :settlement :review}))
            result (binding [ec/*execution-context* (:ctx work-parent)] @handle)
            durable (program/observe control-room handle)
            child-world (binding [ec/*execution-context* (:ctx work-parent)]
                          (registry/lookup (:run/world durable)))]
        (is (= :completed (:run/status result)))
        (is (= parent-run (:run/parent durable)))
        (is (= (:id control-room) (:run/room handle)))
        (is (= (:id work-parent) (:parent-id child-world))
            "the nested world settles only into its immediate parent")
        (is (= (:run/id durable)
               (:fork/owner (d/fork-descriptor child-world))))
        (binding [ec/*execution-context* (:ctx work-parent)]
          (d/discard child-world)))
      (finally
        (binding [ec/*execution-context* (:ctx control-room)]
          (d/discard work-parent))
        (d/close-room! control-room)))))

(deftest llm-program-lifts-the-native-turn-loop
  (let [room (test-room :program-llm)
        team (roster/make-agent
              (roster/make-roster {:id :native-team})
              {:id :researcher
               :prompt "Investigate carefully."
               :tools #{:clojure_eval}
               :model-policy {:provider :codex-subscription
                              :model "codex-subscription-sol"}
               :program {:kind :llm :max-model-steps 4 :auto-compact? false}})
        calls (atom [])]
    (try
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/run-agent-turn!
                    (fn [chat-ctx opts]
                      (swap! calls conj {:chat-id (:chat-id chat-ctx)
                                         :opts opts
                                         :system (->> (chat-context/get-messages chat-ctx)
                                                      (filter #(= :system
                                                                  (:message/role %)))
                                                      first
                                                      :message/content)
                                         :thread (.getName (Thread/currentThread))})
                      (if (= 1 (count @calls))
                        (do
                          (chat-context/add-message!
                           chat-ctx
                           {:role :assistant
                            :content ""
                            :tool-uses [{:tool-use/id "call-1"
                                         :tool-use/name "clojure_eval"
                                         :tool-use/input {:code "(+ 1 1)"}}]})
                          (chat-context/add-message!
                           chat-ctx
                           {:role :tool-result
                            :tool-use-id "call-1"
                            :content "2"})
                          :continue)
                        (do
                          (chat-context/add-message!
                           chat-ctx {:role :assistant :content "The result is 2."})
                          :complete)))]
        (let [handle (binding [ec/*execution-context* (:ctx room)]
                       (program/hire! room team :researcher
                                      {:task "calculate"}))
              result (binding [ec/*execution-context* (:ctx room)] @handle)
              durable (program/observe room handle)
              messages (d/messages room {:limit 20})
              activity (first (filter #(= :_activity (:to %)) messages))]
          (is (= :completed (:run/status result)))
          (is (= "The result is 2." (:run/value result)))
          (is (uuid? (get-in result [:run/metrics :prompt-id])))
          (is (= {:provider :codex-subscription
                  :model "codex-subscription-sol"
                  :model-steps 2
                  :usage {:used 0 :by-type {}}}
                 (dissoc (:run/metrics result) :prompt-id)))
          (is (= :completed (:run/status durable)))
          (is (uuid? (:run/chat-id durable)))
          (is (= 2 (count @calls)))
          (is (= [0 1] (mapv #(get-in % [:opts :turn-number]) @calls)))
          (is (re-find #"Your sandbox" (:system (first @calls))))
          (is (not (re-find #"shared room" (:system (first @calls))))
              "private Run programs use the workflow prompt profile")
          (is (every? #(= :codex-subscription
                          (get-in % [:opts :provider])) @calls))
          (is (every? #(= (program/run-id handle)
                          (get-in % [:opts :run-id])) @calls))
          (is (= (get-in activity [:metadata :run-id])
                 (program/run-id handle)))
          (is (= ["clojure_eval"]
                 (mapv :tool-use/name
                       (get-in activity [:metadata :tool-uses]))))
          (is (= [{:activity/kind :tool
                   :activity/verb :invoke
                   :activity/run-id (program/run-id handle)
                   :activity/tool-name "clojure_eval"
                   :activity/tool-use-id "call-1"}]
                 (mapv #(select-keys % [:activity/kind :activity/verb
                                        :activity/run-id :activity/tool-name
                                        :activity/tool-use-id])
                       (get-in activity [:metadata :activities]))))))
      (finally
        (d/close-room! room)))))

(deftest llm-clojure-eval-can-recursively-hire-from-its-active-sci-world
  (let [room (test-room :program-llm-recursive-sci-hire)
        team (roster/make-agent
              (roster/make-roster {:id :recursive-team})
              {:id :orchestrator
               :tools #{:clojure_eval}
               :model-policy {:provider :test :model "stub"}
               :program {:kind :llm :max-model-steps 2 :auto-compact? false}})
        calls (atom 0)]
    (try
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/messages->api-format (fn [messages _ _] messages)
                    model-chat/chat
                    (fn [_ _]
                      (if (= 1 (swap! calls inc))
                        {:content ""
                         :tool-calls
                         [{:id "recursive-hire"
                           :name "clojure_eval"
                           :input
                           {:code
                            (str
                             "(require '[dvergr.agent :as agent] "
                             "         '[org.replikativ.spindel.spin.cps :refer [spin]] "
                             "         '[org.replikativ.spindel.effects.await :refer [await]]) "
                             "(let [team (-> (agent/roster) "
                             "               (agent/make-agent {:id :child "
                             "                                  :program {:kind :scripted :reply :ok}})) "
                             "      child (agent/hire! team :child {:task :work})] "
                             "  @(spin (-> (await (agent/result-spin child)) :run/value)))")}}]
                         :usage {:input-tokens 0 :output-tokens 0}
                         :stop-reason :tool-use}
                        {:content "done"
                         :tool-calls nil
                         :usage {:input-tokens 0 :output-tokens 0}
                         :stop-reason :end-turn}))]
        (let [root (binding [ec/*execution-context* (:ctx room)]
                     (program/hire! room team :orchestrator {:task "delegate"}))
              result (binding [ec/*execution-context* (:ctx room)] @root)
              children (remove #(= (program/run-id root) (:run/id %))
                               (run/runs room))]
          (is (= :completed (:run/status result)))
          (is (= 1 (count children)))
          (is (= :child (:run/actor (first children))))
          (is (= (program/run-id root) (:run/parent (first children))))
          (is (= :completed (:run/status (first children))))
          (is (= :merged (:run/settlement-status (first children))))))
      (finally
        (d/close-room! room)))))

(deftest parallel-llm-hires-have-independent-chat-contexts
  (let [room (test-room :program-llm-isolation)
        team (roster/make-agent
              (roster/make-roster)
              {:id :particle
               :model-policy {:provider :test :model "stub"}
               :program {:kind :llm :max-model-steps 1}})
        seen (atom [])]
    (try
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/run-agent-turn!
                    (fn [chat-ctx _]
                      (let [task (->> (chat-context/get-messages chat-ctx)
                                      (filter #(= :user (:message/role %)))
                                      last :message/content)]
                        (swap! seen conj [(:chat-id chat-ctx) task])
                        (chat-context/add-message!
                         chat-ctx {:role :assistant :content task})
                        :complete))]
        (binding [ec/*execution-context* (:ctx room)]
          (let [a (program/hire! room team :particle {:task :a})
                b (program/hire! room team :particle {:task :b})]
            (is (= #{":a" ":b"}
                   (set (map :run/value [@a @b]))))
            (is (= 2 (count (set (map first @seen))))
                "each particle gets a fresh ChatContext"))))
      (finally
        (d/close-room! room)))))

(deftest hire-limits-can-only-restrict-an-llm-agent-policy
  (let [room (test-room :program-restrictive-limits)
        team (roster/make-agent
              (roster/make-roster)
              {:id :particle
               :model-policy {:provider :test :model "stub"}
               :program {:kind :llm :max-model-steps 4
                         :budget-dollars 2.0}})
        calls (atom 0)]
    (try
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/run-agent-turn!
                    (fn [_ _]
                      (swap! calls inc)
                      :continue)]
        (let [handle (binding [ec/*execution-context* (:ctx room)]
                       (program/hire!
                        room team :particle
                        {:task :bounded
                         :limits {:max-model-steps 1
                                  :budget-dollars 0.01}}))
              result (binding [ec/*execution-context* (:ctx room)] @handle)]
          (is (= :failed (:run/status result)))
          (is (= 1 @calls))
          (is (= {:max-model-steps 1 :budget-dollars 0.01}
                 (get-in result [:run/metrics :limits])))))
      (finally
        (d/close-room! room)))))

(deftest delegation-tools-are-thin-run-interpreter-adapters
  (let [room (test-room :program-tool-delegation)
        parent-run (random-uuid)]
    (try
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/run-agent-turn!
                    (fn [chat-ctx _]
                      (chat-context/add-message!
                       chat-ctx {:role :assistant :content "delegated evidence"})
                      :complete)]
        (doseq [[tool-name expected-settlement]
                [["spawn_agent" :merged]
                 ["propose_change" :review]]]
          (let [result (binding [ec/*execution-context* (:ctx room)]
                         (tools/execute
                          tool-name {:task "inspect" :profile "worker"}
                          {:room room
                           :execution-ctx (:ctx room)
                           :run-id parent-run
                           :model-policy {:provider :test :model "stub"}}))
                run-id (get-in result [:metadata :run-id])
                durable (run/run room run-id)]
            (is (= :success (:type result)) (pr-str result))
            (is (uuid? run-id))
            (is (= :agent-task (:run/kind durable)))
            (is (= parent-run (:run/parent durable)))
            (is (= "delegate" (namespace (:run/actor durable))))
            (is (= :llm (:run/program-kind durable)))
            (is (= expected-settlement (:run/settlement-status durable)))
            ;; Assert the user-facing adapter as well as the durable projection.
            (is (re-find #"delegated evidence" (:content result)))
            (when (= :review expected-settlement)
              (binding [ec/*execution-context* (:ctx room)]
                (d/discard (registry/lookup (:run/world durable))))))))
      (finally
        (d/close-room! room)))))

(deftest discarded-world-retains-the-parent-owned-model-trace
  (let [[room conn] (datahike-test-room :program-trace-control-plane)
        team (roster/make-agent
              (roster/make-roster)
              {:id :audited
               :model-policy {:provider :test :model "stub"}
               :program {:kind :llm :max-model-steps 1}})]
    (try
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/run-agent-turn!
                    (fn [chat-ctx _]
                      (chat-context/add-message!
                       chat-ctx {:role :assistant :content "audited result"})
                      :complete)]
        (let [handle (binding [ec/*execution-context* (:ctx room)]
                       (program/hire! room team :audited
                                      {:task "inspect" :settlement :discard}))
              result (binding [ec/*execution-context* (:ctx room)] @handle)
              durable (program/observe room handle)
              roles (dh/q '[:find [?role ...]
                            :in $ ?chat-id
                            :where
                            [?chat :chat/id ?chat-id]
                            [?message :message/chat ?chat]
                            [?message :message/role ?role]]
                          @conn (:run/chat-id durable))]
          (is (= :discarded (:run/settlement-status result)))
          (is (= #{:system :user :assistant} (set roles))
              "discard drops work effects, not the model/tool audit trace")))
      (finally
        (d/close-room! room)
        (dh/release conn)))))

(deftest llm-program-settles-at-budget-boundary
  (let [room (test-room :program-llm-budget)
        team (roster/make-agent
              (roster/make-roster)
              {:id :bounded
               :model-policy {:provider :test :model "stub"}
               :program {:kind :llm :budget-dollars 0.01}})]
    (try
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/run-agent-turn! (fn [_ _] :continue)
                    chat-context/budget-exceeded? (constantly true)]
        (let [handle (binding [ec/*execution-context* (:ctx room)]
                       (program/hire! room team :bounded {:task "bounded"}))
              result (binding [ec/*execution-context* (:ctx room)] @handle)]
          (is (= {:run/id (program/run-id handle)
                  :run/status :waiting
                  :run/reason :budget-exhausted
                  :run/settlement-status :review
                  :run/settlement-reason :execution-waiting}
                 (dissoc result :run/world :run/metrics)))
          (is (= 1 (get-in result [:run/metrics :model-steps])))
          (is (uuid? (get-in result [:run/metrics :prompt-id])))
          (is (= :waiting (:run/status (program/observe room handle))))
          (is (empty? (filter #(= (program/run-id handle)
                                  (get-in % [:metadata :run-id]))
                              (d/messages room {:limit 10}))))
          (binding [ec/*execution-context* (:ctx room)]
            (d/discard (registry/lookup (:run/world result))))))
      (finally
        (d/close-room! room)))))

(deftest llm-program-cancellation-aborts-the-live-turn
  (let [room (test-room :program-llm-cancel)
        team (roster/make-agent
              (roster/make-roster)
              {:id :worker
               :model-policy {:provider :test :model "stub"}
               :program {:kind :llm}})
        started (promise)]
    (try
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/run-agent-turn!
                    (fn [_ {:keys [cancel?]}]
                      (deliver started true)
                      (loop []
                        (if (cancel?)
                          :cancelled
                          (do (Thread/sleep 5) (recur)))))]
        (binding [ec/*execution-context* (:ctx room)]
          (let [handle (program/hire! room team :worker {:task "stop"})]
            (is (= true (deref started 10000 ::timeout)))
            (is (true? (program/cancel! handle)))
            (is (= :cancelled (:run/status (deref handle 2000 ::timeout))))
            (is (= :cancelled (:run/status (program/observe room handle)))))))
      (finally
        (d/close-room! room)))))

(deftest structured-llm-cancellation-retains-collected-metrics
  (let [room (test-room :program-llm-structured-cancel)
        team (roster/make-agent
              (roster/make-roster)
              {:id :worker
               :model-policy {:provider :test :model "stub"}
               :program {:kind :llm}})
        calls (atom 0)
        entered-second (promise)]
    (try
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/run-agent-turn!
                    (fn [_ {:keys [cancel?]}]
                      (if (= 1 (swap! calls inc))
                        :continue
                        (do
                          (deliver entered-second true)
                          (loop []
                            (if (cancel?)
                              :cancelled
                              (do (Thread/sleep 5) (recur)))))))]
        (binding [ec/*execution-context* (:ctx room)]
          (let [handle (program/hire! room team :worker {:task "stop structurally"})]
            (is (= true (deref entered-second 10000 ::timeout)))
            (is (= :winner
                   @(comb/race (sp/spin :winner)
                               (program/owned-result-spin handle))))
            (let [result (deref handle 2000 ::timeout)]
              (is (= :cancelled (:run/status result)))
              (is (= {:provider :test
                      :model "stub"
                      :usage {:used 0 :by-type {}}}
                     (dissoc (:run/metrics result)
                             :prompt-id :model-steps)))
              (is (<= 1 (get-in result [:run/metrics :model-steps]) 2)
                  "cancellation may win before or after the worker acknowledges step two")
              (is (uuid? (get-in result [:run/metrics :prompt-id])))))))
      (finally
        (d/close-room! room)))))

(deftest llm-cleanup-failure-retains-collected-metrics
  (let [room (test-room :program-llm-cleanup-failure)
        team (roster/make-agent
              (roster/make-roster)
              {:id :worker
               :model-policy {:provider :test :model "stub"}
               :program {:kind :llm :max-model-steps 1}})]
    (try
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/run-agent-turn!
                    (fn [chat-ctx _]
                      (chat-context/add-message!
                       chat-ctx {:role :assistant :content "done"})
                      :complete)
                    chat-context/close-chat!
                    (fn [_] (throw (ex-info "cleanup exploded" {})))]
        (binding [ec/*execution-context* (:ctx room)]
          (let [result @(program/hire! room team :worker {:task "clean up"})]
            (is (= :failed (:run/status result)))
            (is (= "cleanup exploded" (:run/error result)))
            (is (= 1 (get-in result [:run/metrics :model-steps])))
            (is (uuid? (get-in result [:run/metrics :prompt-id]))))))
      (finally
        (d/close-room! room)))))

(deftest llm-program-uses-one-normalized-tool-contract
  (let [room (test-room :program-llm-tools)
        team (-> (roster/make-roster)
                 (roster/make-agent
                  {:id :with-tool
                   :tools #{:clojure_eval}
                   :model-policy {:provider :test :model "stub"}
                   :program {:kind :llm :max-model-steps 1 :auto-compact? false}})
                 (roster/make-agent
                  {:id :without-tools
                   :model-policy {:provider :test :model "stub"}
                   :program {:kind :llm :max-model-steps 1 :auto-compact? false}}))
        advertised (atom [])]
    (try
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/messages->api-format (fn [messages _ _] messages)
                    model-chat/chat
                    (fn [_ {:keys [tools]}]
                      (swap! advertised conj tools)
                      {:content "done"
                       :tool-calls nil
                       :usage {:input-tokens 0 :output-tokens 0}
                       :stop-reason :end-turn})]
        (binding [ec/*execution-context* (:ctx room)]
          (is (= "done" (:run/value
                         @(program/hire! room team :with-tool {:task "one"}))))
          (is (= "done" (:run/value
                         @(program/hire! room team :without-tools {:task "two"})))))
        (is (= ["clojure_eval"] (mapv :name (first @advertised))))
        (is (empty? (second @advertised))
            "omitting AgentDef tools advertises none rather than the global registry"))
      (finally
        (d/close-room! room)))))

(deftest llm-runtime-initialization-does-not-block-the-room-executor
  (let [room (test-room :program-llm-init-worker)
        team (roster/make-agent
              (roster/make-roster)
              {:id :worker
               :model-policy {:provider :test :model "stub"}
               :program {:kind :llm :max-model-steps 1}})
        entered (promise)
        release (promise)
        original-new-working-ctx turn/new-working-ctx]
    (try
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    turn/new-working-ctx
                    (fn [opts]
                      (deliver entered true)
                      @release
                      (original-new-working-ctx opts))
                    chat-agent/run-agent-turn!
                    (fn [chat-ctx _]
                      (chat-context/add-message!
                       chat-ctx {:role :assistant :content "ready"})
                      :complete)]
        (binding [ec/*execution-context* (:ctx room)]
          (let [handle (program/hire! room team :worker {:task "initialize"})]
            (is (= true (deref entered 10000 ::timeout)))
            (let [unrelated (future
                              (binding [ec/*execution-context* (:ctx room)]
                                @(sp/spin :responsive)))]
              (is (= :responsive (deref unrelated 1000 ::timeout))
                  "an unrelated Spin drains while context/schema/SCI setup blocks"))
            (deliver release true)
            (is (= "ready" (:run/value (deref handle 10000 ::timeout)))))))
      (finally
        (deliver release true)
        (d/close-room! room)))))

(deftest cancellation-before-worker-registration-is-sticky
  (let [room (test-room :program-llm-cancel-before-init)
        team (roster/make-agent
              (roster/make-roster)
              {:id :worker
               :model-policy {:provider :test :model "stub"}
               :program {:kind :llm :max-model-steps 1}})
        entered-post (promise)
        release-post (promise)
        init-calls (atom 0)
        original-post d/post!]
    (try
      (with-redefs [d/post! (fn [target message]
                              (deliver entered-post true)
                              @release-post
                              (original-post target message))
                    turn/new-working-ctx
                    (fn [_]
                      (swap! init-calls inc)
                      (throw (ex-info "cancelled init must not run" {})))]
        (let [hiring (future
                       (binding [ec/*execution-context* (:ctx room)]
                         (program/hire! room team :worker {:task "stop"})))
              _ (is (= true (deref entered-post 5000 ::timeout)))
              run-id (:run/id (first (run/active-runs (:id room))))
              closing (future (d/close-room! room))]
          (is (wait-until #(run/cancel-requested? run-id) 5000))
          (deliver release-post true)
          (let [handle (deref hiring 2000 ::timeout)]
            (is (not= ::timeout handle))
            (is (nil? (deref closing 3000 ::timeout)))
            (is (zero? @init-calls))
            (is (= :cancelled (:run/status (program/observe room handle)))))))
      (finally
        (deliver release-post true)
        (d/close-room! room)))))

(deftest cancellation-between-model-steps-does-not-start-another-step
  (let [room (test-room :program-llm-cancel-between-steps)
        team (roster/make-agent
              (roster/make-roster)
              {:id :worker
               :model-policy {:provider :test :model "stub"}
               :program {:kind :llm :max-model-steps 4}})
        between (promise)
        release (promise)
        calls (atom 0)]
    (try
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/run-agent-turn! (fn [_ _]
                                                 (swap! calls inc)
                                                 :continue)
                    turn/post-turn-activity! (fn [& _]
                                               (deliver between true)
                                               @release)]
        (binding [ec/*execution-context* (:ctx room)]
          (let [handle (program/hire! room team :worker {:task "observe"})]
            (is (= true (deref between 10000 ::timeout)))
            (is (true? (program/cancel! handle)))
            (deliver release true)
            (is (= :cancelled (:run/status (deref handle 3000 ::timeout))))
            (is (= 1 @calls)))))
      (finally
        (deliver release true)
        (d/close-room! room)))))

(deftest room-close-waits-for-native-worker-termination
  (let [room (test-room :program-llm-drain)
        team (roster/make-agent
              (roster/make-roster)
              {:id :worker
               :model-policy {:provider :test :model "stub"}
               :program {:kind :llm}})
        started (promise)
        release (promise)]
    (try
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/run-agent-turn!
                    (fn [_ _]
                      (deliver started true)
                      ;; Deliberately ignore Future.cancel's interrupt. The Run
                      ;; must remain live until native work really terminates.
                      (loop []
                        (if (realized? release)
                          :cancelled
                          (do
                            (try
                              (Thread/sleep 10)
                              (catch InterruptedException _ nil))
                            (recur)))))]
        (binding [ec/*execution-context* (:ctx room)]
          (let [handle (program/hire! room team :worker {:task "drain"})]
            (is (= true (deref started 10000 ::timeout)))
            (let [closing (future (d/close-room! room))]
              (is (wait-until #(run/cancel-requested? (program/run-id handle)) 1000))
              (Thread/sleep 50)
              (is (false? (realized? closing))
                  "Room substrate stays open while the interrupted worker is live")
              (is (some #(= (program/run-id handle) (:run/id %))
                        (run/active-runs (:id room))))
              (deliver release true)
              (is (nil? (deref closing 3000 ::timeout)))
              (is (= :cancelled
                     (:run/status (program/observe room handle))))))))
      (finally
        (deliver release true)
        (d/close-room! room)))))

(deftest cancellation-is-targeted-and-acknowledged
  (let [room (test-room :program-cancel)
        team (roster/make-agent
              (roster/make-roster)
              {:id :slow
               :program {:kind :scripted :delay-ms 500 :reply "too late"}})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [handle (program/hire! room team :slow {:task "stop"})]
          (is (some? (program/observe room handle))
              "hire! returns only after durable admission")
          (is (program/cancel! handle))
          (is (= :cancelled (:run/status @handle)))
          (is (= :cancelled (:run/status (program/observe room handle))))
          (is (empty? (filter #(= (program/run-id handle)
                                  (get-in % [:metadata :run-id]))
                              (d/messages room {:limit 10}))))))
      (finally
        (d/close-room! room)))))

(deftest structural-parent-is-explicit
  (let [room (test-room :program-parent)
        team (test-roster)
        parent-id (random-uuid)]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [handle (program/hire! room team :analyst
                                    {:task "child" :parent-run parent-id})]
          @handle
          (is (= parent-id (:run/parent (program/observe room handle))))))
      (finally
        (d/close-room! room)))))

(deftest hire-validates-the-effect-envelope-before-starting
  (let [room (test-room :program-validation)
        team (test-roster)]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (is (thrown? clojure.lang.ExceptionInfo
                     (program/hire! room team :missing {:task "work"})))
        (is (thrown? clojure.lang.ExceptionInfo
                     (program/hire! room team :analyst {})))
        (is (thrown? clojure.lang.ExceptionInfo
                     (program/hire! room team :analyst
                                    {:task "work" :metadata {:callback identity}})))
        (is (thrown? clojure.lang.ExceptionInfo
                     (program/hire!
                      room
                      (roster/make-agent
                       (roster/make-roster)
                       {:id :typo :program {:kind :scripted :repy "missed"}})
                      :typo {:task "work"})))
        (is (empty? (run/active-runs (:id room))))
        (is (empty? (d/messages room {:limit 10}))))
      (finally
        (d/close-room! room)))))

(deftest closed-admission-rejects-hire-before-posting
  (let [room (test-room :program-admission-closed)
        team (test-roster)
        posts (atom 0)]
    (try
      (run/close-room-admission! room)
      (with-redefs [d/post! (fn [& _] (swap! posts inc))]
        (binding [ec/*execution-context* (:ctx room)]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"admission is closed"
               (program/hire! room team :analyst {:task "too late"})))))
      (is (zero? @posts))
      (is (empty? (run/active-runs (:id room))))
      (is (empty? (d/messages room {:limit 10})))
      (finally
        (d/close-room! room)))))

(deftest teardown-cannot-miss-a-hire-between-admission-and-trigger-post
  (let [room (test-room :program-admission-race)
        team (roster/make-agent
              (roster/make-roster)
              {:id :slow
               :program {:kind :scripted :delay-ms 500 :reply "too late"}})
        entered-post (promise)
        release-post (promise)
        original-post d/post!]
    (try
      (with-redefs [d/post! (fn [target message]
                              (deliver entered-post true)
                              @release-post
                              (original-post target message))]
        (let [hire-future
              (future
                (binding [ec/*execution-context* (:ctx room)]
                  (program/hire! room team :slow {:task "racing close"})))
              _ (is (= true (deref entered-post 5000 ::timeout)))
              admitted-id (:run/id (first (run/active-runs (:id room))))
              close-future (future (d/close-room! room))]
          (is (wait-until #(run/cancel-requested? admitted-id) 5000)
              "close fenced admission and found the reserved Run")
          (is (false? (realized? close-future))
              "teardown waits rather than removing the substrate")
          (deliver release-post true)
          (let [handle (deref hire-future 2000 ::timeout)]
            (is (not= ::timeout handle))
            (is (nil? (deref close-future 2000 ::timeout)))
            (is (= :cancelled (:run/status (program/observe room handle))))
            (is (empty? (run/active-runs (:id room)))))))
      (finally
        (deliver release-post true)
        (d/close-room! room)))))

(deftest structured-race-cancels-the-losing-run-spin
  (let [room (test-room :program-race)
        team (roster/make-agent
              (roster/make-roster)
              {:id :slow
               :program {:kind :scripted :delay-ms 500 :reply "too late"}})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (dotimes [_ 10]
          (let [handle (program/hire! room team :slow {:task "race"})
                winner (sp/spin :winner)]
            (is (= :winner @(comb/race winner (program/owned-result-spin handle))))
            (is (wait-until #(= :cancelled
                                (:run/status (program/observe room handle)))
                            1000))))
        (is (empty? (run/active-runs (:id room)))))
      (finally
        (d/close-room! room)))))

(deftest nested-structured-race-settles-the-losing-run
  (let [room (test-room :program-nested-race)
        team (-> (roster/make-roster)
                 (roster/make-agent
                  {:id :fast
                   :program {:kind :scripted :delay-ms 10 :reply :fast}})
                 (roster/make-agent
                  {:id :slow
                   :program {:kind :scripted :delay-ms 5000 :reply :slow}}))]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [fast (program/hire! room team :fast {:task :solve})
              slow (program/hire! room team :slow {:task :solve})]
          (is (= :fast
                 (:run/value
                  @(sp/spin
                    (sp/await
                     (comb/race (program/owned-result-spin fast)
                                (program/owned-result-spin slow)))))))
          (is (wait-until #(= :cancelled
                              (:run/status (program/observe room slow)))
                          1000))
          (is (= :completed (:run/status (program/observe room fast))))
          (is (empty? (run/active-runs (:id room))))))
      (finally
        (d/close-room! room)))))

(deftest passive-result-observers-do-not-own-the-shared-run
  (let [room (test-room :program-passive-observers)
        team (roster/make-agent
              (roster/make-roster)
              {:id :slow
               :program {:kind :scripted :delay-ms 75 :reply "shared"}})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [handle (program/hire! room team :slow {:task "observe"})
              losing-observer (program/result-spin handle)
              surviving-observer (program/result-spin handle)]
          (is (= :winner @(comb/race (sp/spin :winner) losing-observer)))
          (is (= "shared" (:run/value @surviving-observer)))
          (is (= :completed (:run/status (program/observe room handle))))))
      (finally
        (d/close-room! room)))))

(deftest terminal-persistence-retries-without-losing-the-result
  (let [delegate (memory/make)
        unavailable? (atom true)
        room (d/make-room {:id :program-terminal-retry
                           :store (fail-terminal-store delegate unavailable?)})
        team (roster/make-agent
              (roster/make-roster)
              {:id :worker :program {:kind :scripted :reply "durable"}})]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [handle (program/hire! room team :worker {:task "persist"})]
          (is (= ::timeout (deref handle 100 ::timeout))
              "completion remains unresolved while its terminal receipt fails")
          (is (= :running (:run/status (program/observe room handle))))
          (is (= 1 (count (run/active-runs (:id room))))
              "the live lease makes the recovery boundary observable")
          (reset! unavailable? false)
          (is (= "durable" (:run/value (deref handle 2000 ::timeout))))
          (is (= :completed (:run/status (program/observe room handle))))
          (is (empty? (run/active-runs (:id room))))))
      (finally
        (reset! unavailable? false)
        (d/close-room! room)))))

(deftest direct-program-input-does-not-wake-a-same-id-participant
  (let [room (test-room :program-routing)
        team (test-roster)
        deliveries (atom 0)]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (d/join room
                (d/participant
                 {:id :analyst
                  :on-message (fn [_ _]
                                (sp/spin (swap! deliveries inc) nil))}))
        @(program/hire! room team :analyst {:task "direct"})
        (Thread/sleep 25)
        (is (zero? @deliveries)))
      (finally
        (d/close-room! room)))))

(deftest room-scoped-cancellation-rejects-another-rooms-run-id
  (let [a (test-room :program-room-a)
        b (test-room :program-room-b)
        team (roster/make-agent
              (roster/make-roster)
              {:id :slow
               :program {:kind :scripted :delay-ms 500 :reply "done"}})]
    (try
      (let [handle (binding [ec/*execution-context* (:ctx b)]
                     (program/hire! b team :slow {:task "private"}))]
        (is (false? (program/cancel! a (program/run-id handle))))
        (is (true? (program/cancel! b (program/run-id handle))))
        (binding [ec/*execution-context* (:ctx b)] @handle)
        (is (= :cancelled (:run/status (program/observe b handle)))))
      (finally
        (d/close-room! a)
        (d/close-room! b)))))

(deftest live-handles-reject-cross-fork-observation
  (let [room (test-room :program-context-owner)
        team (test-roster)
        fork-ctx (context/fork-context (:ctx room))]
    (try
      (let [handle (binding [ec/*execution-context* (:ctx room)]
                     (program/hire! room team :analyst {:task "owned"}))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"another Spindel context"
             (binding [ec/*execution-context* fork-ctx]
               (program/result-spin handle))))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"another Spindel context"
             (binding [ec/*execution-context* fork-ctx]
               (program/cancel! handle))))
        (binding [ec/*execution-context* (:ctx room)]
          (is (= :completed (:run/status @handle)))))
      (finally
        (context/close-context! fork-ctx)
        (d/close-room! room)))))

(deftest discarding-an-isolated-fork-settles-hired-runs-first
  (let [parent (test-room :program-fork-parent)
        fork (d/fork-room parent {:isolation :ctx})
        team (roster/make-agent
              (roster/make-roster)
              {:id :slow
               :program {:kind :scripted :delay-ms 500 :reply "too late"}})
        events (atom [])
        watch-key (Object.)]
    (run/watch-runs! watch-key #(swap! events conj %))
    (try
      (let [handle (binding [ec/*execution-context* (:ctx fork)]
                     (program/hire! fork team :slow {:task "discard"}))
            id (program/run-id handle)]
        (d/discard fork)
        (is (empty? (run/active-runs (:id fork))))
        (is (= :cancelled
               (some (fn [{:keys [type run]}]
                       (when (and (= :run/finished type)
                                  (= id (:run/id run)))
                         (:run/status run)))
                     @events)))
        (is (empty? (filter #(= id (get-in % [:metadata :run-id]))
                            (d/messages fork {:limit 10}))))
        (is (binding [ec/*execution-context* (:ctx parent)]
              (nil? (registry/lookup (:id fork))))))
      (finally
        (run/unwatch-runs! watch-key)
        (d/close-room! parent)))))

(deftest closing-a-room-settles-its-program-runs-before-closing-spindel
  (let [room (test-room :program-close)
        team (roster/make-agent
              (roster/make-roster)
              {:id :slow
               :program {:kind :scripted :delay-ms 500 :reply "too late"}})
        handle (binding [ec/*execution-context* (:ctx room)]
                 (program/hire! room team :slow {:task "close"}))]
    (d/close-room! room)
    (is (empty? (run/active-runs (:id room))))
    (is (= :cancelled (:run/status (program/observe room handle))))
    (is (nil? (d/close-room! room)) "close remains idempotent")))
