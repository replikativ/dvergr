(ns dvergr.sandbox.agent-programming-test
  "Provider-free acceptance tests for the functional agent surface in room SCI."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.activity :as activity]
            [dvergr.agent.observation :as observation]
            [dvergr.agent.program :as program]
            [dvergr.agent.run :as run]
            [dvergr.agent.world :as world]
            [dvergr.discourse :as d]
            [dvergr.resource :as resource]
            [dvergr.room.registry :as room-registry]
            [dvergr.room.store :as room-store]
            [dvergr.room.store.memory :as memory]
            [dvergr.rooms :as rooms]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.ns.agent :as agent-ns]
            [dvergr.sandbox.ns.data :as data-ns]
            [dvergr.sandbox.work :as sandbox-work]
            [dvergr.sandbox.ns.kb :as kb-ns]
            [dvergr.sandbox.ns.room :as room-ns]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.work :as native-work]))

(defn- wait-until [pred timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (cond
        (pred) true
        (< (System/nanoTime) deadline) (do (Thread/sleep 5) (recur))
        :else false))))

(defn- ordered-delete-store [delegate order]
  (reify room-store/PRoomStore
    (-store-room! [_ room-id metadata]
      (room-store/-store-room! delegate room-id metadata))
    (-load-room [_ id-or-slug]
      (room-store/-load-room delegate id-or-slug))
    (-delete-room! [_ room-id]
      (swap! order conj :delete)
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
      (room-store/-store-run! delegate room-id run))
    (-load-run [_ room-id run-id]
      (room-store/-load-run delegate room-id run-id))
    (-list-runs [_ room-id opts]
      (room-store/-list-runs delegate room-id opts))))

(deftest immutable-rosters-launch-composable-room-runs-from-sci
  (let [room    (d/make-room {:id :sci-agent-programming
                              :store (memory/make)})
        sci-ctx (sandbox/fork-for-session (:ctx room))]
    (try
      ;; Keep this acceptance test focused on the new surface. Production calls
      ;; the same injector from setup-agent-namespaces!; doc-coverage exercises
      ;; that full wiring and catches any omission there.
      (agent-ns/add-programming-ns! sci-ctx (:id room) (:ctx room) nil)
      (data-ns/add-spindel-extras-ns! sci-ctx (:ctx room)
                                      {:room-id (:id room)
                                       :room-incarnation (:incarnation room)})
      (testing "progressive help contains an executable composition example"
        (let [guide (sandbox/ns-doc-md sci-ctx 'dvergr.agent)]
          (is (re-find #":scripted.*:reply" guide))
          (is (re-find #":delay-ms" guide))
          (is (re-find #"result-spin" guide))
          (is (re-find #"owned-result-spin" guide))
          (is (re-find #"content-addressed EnvironmentDef" guide))
          (is (re-find #"comb/race" guide))
          (is (re-find #"await" guide)))
        (let [result
              (sandbox/eval-code
               sci-ctx
               (str "(require '[dvergr.agent :as agent]) "
                    "(let [a (agent/environment "
                    "{:id :training/example :task {:input 42} "
                    ":verifier {:id :checks/example :version 1} "
                    ":limits {:timeout-ms 1000}}) "
                    "b (agent/environment "
                    "{:limits {:timeout-ms 1000} "
                    ":verifier {:version 1 :id :checks/example} "
                    ":task {:input 42} :id :training/example}) "
                    "team (agent/make-agent (agent/roster) "
                    "{:id :candidate :program {:kind :echo}}) "
                    "dataset (agent/dataset "
                    "{:id :training/smoke :environments [a]}) "
                    "experiment (agent/experiment "
                    "{:id :training/paired :dataset dataset "
                    ":candidates [(agent/lookup team :candidate)] "
                    ":repetitions 2})] "
                    "{:same? (= a b) :ref (agent/environment-ref a) "
                    ":dataset-ref (agent/dataset-ref dataset) "
                    ":experiment-ref (agent/experiment-ref experiment) "
                    ":parallelism (:experiment/parallelism experiment)})"))]
          (is (:success result) (pr-str (:error result)))
          (is (true? (get-in result [:value :same?])))
          (is (uuid? (get-in result [:value :ref :environment/content-id])))
          (is (uuid? (get-in result
                             [:value :dataset-ref :dataset/content-id])))
          (is (uuid? (get-in result
                             [:value :experiment-ref :experiment/content-id])))
          (is (= 1 (get-in result [:value :parallelism]))))
        (let [guide (sandbox/ns-doc-md sci-ctx 'spindel.comb)]
          (is (re-find #"cancel losing branches" guide))
          (is (re-find #"owned-result-spin" guide)))
        (let [guide (sandbox/ns-doc-md sci-ctx 'spindel.work)]
          (is (re-find #"switch-to-latest" guide))
          (is (re-find #"parallel" guide))
          (is (re-find #"completion" guide))))
      (let [result
            (binding [ec/*execution-context* (:ctx room)]
              (sandbox/eval-code
               sci-ctx
               (str
                "(require '[dvergr.agent :as agent] "
                "         '[org.replikativ.spindel.spin.cps :refer [spin]] "
                "         '[org.replikativ.spindel.effects.await :refer [await]]) "
                "(let [empty-team (agent/roster {:id :investigation}) "
                "      team (-> empty-team "
                "               (agent/make-agent "
                "                {:id :analyst :skills #{:research} "
                "                 :program {:kind :scripted :reply \"evidence\"}}) "
                "               (agent/make-agent "
                "                {:id :reviewer :skills #{:review} "
                "                 :program {:kind :echo}})) "
                "      analyst (agent/hire! team :analyst {:task \"inspect\"}) "
                "      reviewer (agent/hire! team :reviewer {:task {:claim 42}}) "
                "      values @(spin [(-> (await (agent/result-spin analyst)) :run/value) "
                "                     (-> (await (agent/result-spin reviewer)) :run/value)])] "
                "  {:empty-count (count (agent/list empty-team)) "
                "   :team-count (count (agent/list team)) "
                "   :values values "
                "   :analyst-run (agent/run-id analyst) "
                "   :analyst-status (:run/status (agent/observe analyst))})")))]
        (testing "roster construction remains a value transformation"
          (is (:success result) (pr-str (:error result)))
          (is (= 0 (get-in result [:value :empty-count])))
          (is (= 2 (get-in result [:value :team-count]))))
        (testing "RunHandles compose through Spindel await in SCI"
          (is (= ["evidence" {:claim 42}] (get-in result [:value :values])))
          (is (uuid? (get-in result [:value :analyst-run])))
          (is (= :completed (get-in result [:value :analyst-status])))
          (is (empty? (run/active-runs (:id room))))))
      (finally
        (d/close-room! room)))))

(deftest sci-can-author-particles-and-an-independent-verifier
  (let [room      (d/make-room {:id :sci-self-programming-eval
                                :store (memory/make)})
        sci-ctx   (sandbox/fork-for-session (:ctx room))
        parent-id (:run/id (run/start! room :orchestrator (random-uuid) nil))]
    (try
      (agent-ns/add-programming-ns!
       sci-ctx (:id room) (:ctx room)
       {:program-kinds #{:scripted}
        :parent-run parent-id})
      (data-ns/add-spindel-extras-ns! sci-ctx (:ctx room))
      (let [result
            (binding [ec/*execution-context* (:ctx room)]
              (sandbox/eval-code
               sci-ctx
               (str
                "(require '[dvergr.agent :as agent] "
                "         '[org.replikativ.spindel.spin.cps :refer [spin]] "
                "         '[org.replikativ.spindel.effects.await :refer [await]] "
                "         '[spindel.comb :as comb]) "
                "(let [team (-> (agent/roster {:id :particle-eval}) "
                "               (agent/make-agent {:id :mod-five "
                "                 :program {:kind :scripted :delay-ms 50 "
                "                           :reply [8 23 38 53 68 83 98]}}) "
                "               (agent/make-agent {:id :mod-seven "
                "                 :program {:kind :scripted :delay-ms 50 "
                "                           :reply [2 9 16 23 30 37 44 51 58 65 72 79 86 93]}}) "
                "               (agent/make-agent {:id :verifier "
                "                 :program {:kind :scripted :delay-ms 50 "
                "                           :reply {:moduli [[3 2] [5 3] [7 2]] "
                "                                   :upper-bound 100}}})) "
                "      a (agent/hire! team :mod-five {:task :candidates}) "
                "      b (agent/hire! team :mod-seven {:task :candidates}) "
                "      v (agent/hire! team :verifier {:task :constraints}) "
                "      [ra rb rv] @(spin (await (comb/parallel "
                "                                  (agent/result-spin a) "
                "                                  (agent/result-spin b) "
                "                                  (agent/result-spin v)))) "
                "      [xs ys spec] (mapv :run/value [ra rb rv]) "
                "      candidates (filter (set ys) xs) "
                "      valid? (fn [n] (and (pos? n) (< n (:upper-bound spec)) "
                "                            (every? (fn [[m r]] (= r (mod n m))) "
                "                                    (:moduli spec)))) "
                "      answers (vec (filter valid? candidates))] "
                "  {:answer (first answers) :particles 2 :verified (= [23] answers)})")))]
        (is (:success result) (pr-str (:error result)))
        (is (= {:answer 23 :particles 2 :verified true} (:value result)))
        (is (wait-until #(= [parent-id]
                            (mapv :run/id (run/active-runs (:id room))))
                        1000))
        (run/finish! parent-id :completed)
        (let [root (run/run room parent-id)
              children (remove #(= parent-id (:run/id %)) (run/runs room))
              by-actor (into {} (map (juxt :run/actor identity)) children)]
          (is (= #{:mod-five :mod-seven :verifier} (set (keys by-actor))))
          (is (every? #(= parent-id (:run/parent %)) children))
          (is (= (into #{} (map :run/id) children)
                 (set (:run/caused-by root))))
          (is (every? #(= :completed (:run/status %)) children))
          (is (every? #(= :merged (:run/settlement-status %)) children))))
      (finally
        (run/finish! parent-id :cancelled)
        (d/close-room! room)))))

(deftest room-sci-programs-serial-work-without-an-llm
  (let [room    (d/make-room {:id :sci-structured-work
                              :store (memory/make)})
        sci-ctx (sandbox/fork-for-session (:ctx room))]
    (try
      (data-ns/add-spindel-extras-ns! sci-ctx (:ctx room)
                                      {:room-id (:id room)
                                       :room-incarnation (:incarnation room)})
      (let [result
            (binding [ec/*execution-context* (:ctx room)]
              (sandbox/eval-code
               sci-ctx
               (str
                "(require '[spindel.work :as work] "
                "         '[org.replikativ.spindel.spin.cps :refer [spin]] "
                "         '[org.replikativ.spindel.effects.await :refer [await]]) "
                "(let [seen (atom []) "
                "      c (work/serial "
                "         (fn [value] "
                "           (work/task "
                "             (swap! seen conj value) "
                "             value)))] "
                "  @(spin "
                "     (work/submit! c :first :first) "
                "     (work/submit! c :second :second) "
                "     (work/close! c) "
                "     (await (work/completion c)) "
                "     {:seen @seen :controller-map? (map? c) "
                "      :state (work/snapshot c)}))")))]
        (is (:success result) (pr-str (:error result)))
        (is (= [:first :second] (get-in result [:value :seen])))
        (is (false? (get-in result [:value :controller-map?]))
            "the SCI handle does not expose its owner context or live children as data")
        (is (= 0 (get-in result [:value :state :work/active])))
        (is (= 0 (get-in result [:value :state :work/queued])))
        (is (true? (get-in result [:value :state :terminal?]))))
      (finally
        (d/close-room! room)))))

(deftest fork-discard-cancels-and-joins-room-owned-sci-work
  (let [parent  (d/make-room {:id :sci-work-parent
                              :store (memory/make)})
        fork    (binding [ec/*execution-context* (:ctx parent)]
                  (d/fork-room parent {:isolation :ctx}))
        sci-ctx (sandbox/fork-for-session (:ctx fork))]
    (try
      (data-ns/add-spindel-extras-ns! sci-ctx (:ctx fork)
                                      {:room-id (:id fork)
                                       :room-incarnation (:incarnation fork)})
      (let [created
            (binding [ec/*execution-context* (:ctx fork)]
              (sandbox/eval-code
               sci-ctx
               (str
                "(require '[spindel.work :as work] "
                "         '[sync :as sync] "
                "         '[org.replikativ.spindel.effects.await :refer [await]]) "
                "(let [cleanup (atom false) "
                "      started (sync/deferred) "
                "      never (sync/deferred) "
                "      c (work/serial "
                "         (fn [_] "
                "           (work/task "
                "             (try "
                "               (sync/deliver! started true) "
                "               (await never) "
                "               (finally (reset! cleanup true))))))] "
                "  (work/submit! c :job) "
                "  {:cleanup cleanup :started started :controller c})")))]
        (is (:success created) (pr-str (:error created)))
        (let [{:keys [cleanup started controller]} (:value created)]
          (binding [ec/*execution-context* (:ctx fork)]
            (is (true? @(sp/spin (sp/await started)))))
          (d/discard fork)
          (is (true? @cleanup) "task finally runs before the fork substrate is discarded")
          (binding [ec/*execution-context* (:ctx fork)]
            (is (true? (:terminal? (sandbox-work/snapshot controller)))))))
      (finally
        (d/close-room! parent)))))

(deftest room-sci-work-admission-enforces-resource-ceilings
  (let [room    (d/make-room {:id :sci-work-ceilings
                              :store (memory/make)})
        sci-ctx (sandbox/fork-for-session (:ctx room))]
    (try
      (data-ns/add-spindel-extras-ns!
       sci-ctx (:ctx room)
       {:room-id (:id room)
        :room-incarnation (:incarnation room)
        :ceiling {:controllers 1
                  :concurrency 2
                  :capacity 4
                  :ingress-capacity 4
                  :event-taps 1}})
      (let [result
            (binding [ec/*execution-context* (:ctx room)]
              (sandbox/eval-code
               sci-ctx
               (str
                "(require '[spindel.work :as work]) "
                "(let [too-wide "
                "      (try (work/parallel {:concurrency 3} (fn [x] (work/task x))) "
                "           :not-rejected "
                "           (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))) "
                "      c (work/serial (fn [x] (work/task x))) "
                "      tap (work/events c) "
                "      second-tap "
                "      (try (work/events c) :not-rejected "
                "           (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))) "
                "      second-controller "
                "      (try (work/serial (fn [x] (work/task x))) :not-rejected "
                "           (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))] "
                "  (work/untap! c tap) "
                "  (work/close! c) "
                "  {:too-wide too-wide :second-tap second-tap "
                "   :second-controller second-controller})")))]
        (is (:success result) (pr-str (:error result)))
        (is (every? #(= :dvergr.sandbox.work/ceiling-exceeded %)
                    (vals (:value result)))))
      (finally
        (d/close-room! room)))))

(deftest room-work-registration-preserves-ownership-and-rejects-stale-incarnations
  (let [room (d/make-room {:id :sci-work-incarnation
                           :store (memory/make)})
        other-ctx (context/create-execution-context)
        other-room (d/make-room {:id :sci-work-incarnation
                                 :ctx other-ctx
                                 :store (memory/make)})
        controller (sandbox-work/create!
                    (:id room) (:incarnation room) (:ctx room) {:controllers 1}
                    :serial {} (fn [value] (sp/spin value)))]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (room-registry/register! room))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"controller limit"
           (sandbox-work/create! (:id room) (:incarnation room) (:ctx room) {:controllers 1}
                                 :serial {} (fn [value] (sp/spin value))))
          "an idempotent registry refresh does not forget live controllers")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"another live incarnation"
           (binding [ec/*execution-context* (:ctx room)]
             (room-registry/register! (assoc room :incarnation (random-uuid))))))
      (is (identical? room
                      (binding [ec/*execution-context* (:ctx room)]
                        (room-registry/lookup (:id room))))
          "a rejected replacement leaves the registered Room untouched")
      (is (identical? other-room
                      (binding [ec/*execution-context* other-ctx]
                        (room-registry/lookup (:id other-room))))
          "equal Room ids in independent root registries do not share lifecycle state")
      (finally
        (binding [ec/*execution-context* (:ctx room)]
          (sandbox-work/close! controller))
        (d/close-room! room)
        (d/close-room! other-room)))))

(deftest registry-reservation-does-not-hold-global-lock-while-draining
  (let [room (d/make-room {:id :registry-drain-target
                           :store (memory/make)})
        entered (promise)
        release (promise)]
    (room-registry/add-pre-unregister-hook!
     ::park-target-drain
     (fn [target]
       (when (= (:id room) (:id target))
         (deliver entered true)
         @release)))
    (let [unregister (future
                       (binding [ec/*execution-context* (:ctx room)]
                         (room-registry/unregister! (:id room))))]
      (is (= true (deref entered 2000 ::timeout)))
      (let [other-future
            (future
              (d/make-room {:id :registry-unrelated-room
                            :ctx (:ctx room)
                            :store (memory/make)}))
            other (deref other-future 1000 ::timeout)]
        (try
          (is (not= ::timeout other)
              "unrelated registration proceeds while a Room drains outside the monitor")
          (finally
            (deliver release true)
            (is (nil? (deref unregister 2000 ::timeout)))
            (when-not (= ::timeout other)
              (d/close-room! other))))))))

(deftest sci-room-deletion-uses-lifecycle-aware-supervisor-path
  (let [parent (d/make-room {:id :sci-delete-parent
                             :store (memory/make)})
        child (binding [ec/*execution-context* (:ctx parent)]
                (d/fork-room parent {:isolation :ctx}))
        shared-child (binding [ec/*execution-context* (:ctx parent)]
                       (d/fork-room parent {:isolation :none}))
        deleted (atom nil)]
    (try
      (with-redefs [rooms/delete-room!
                    (fn [room]
                      (reset! deleted room)
                      {:ok? true})]
        (let [ops (kb-ns/room-ops-map (:ctx parent) nil
                                      (select-keys parent [:id :incarnation]))]
          (is (= {:deleted (:id child)} (('delete! ops) child)))
          (is (identical? child @deleted))
          (is (= {:deleted (:id shared-child)} (('delete! ops) shared-child))
              "a subordinate sharing the source context is not mistaken for self")
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"cannot delete itself"
               (('delete! ops) (assoc parent :ctx (:ctx child)))))))
      (finally
        (d/discard child)
        (d/discard shared-child)
        (d/close-room! parent)))))

(deftest room-work-teardown-broadcasts-before-joining
  (let [room (d/make-room {:id :sci-work-broadcast
                           :store (memory/make)})
        controllers (vec
                     (repeatedly
                      2
                      #(sandbox-work/create!
                        (:id room) (:incarnation room) (:ctx room) nil :serial {}
                        (fn [value] (sp/spin value)))))
        cancellations (atom 0)]
    (try
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"failed waiting"
           (with-redefs [native-work/cancel!
                         (fn [_]
                           (when (= 1 (swap! cancellations inc))
                             (throw (ex-info "broken cancel" {}))))
                         native-work/completion
                         (fn [_]
                           (fn [success _failure] (success true)))]
             (sandbox-work/close-room-work! room))))
      (is (= 2 @cancellations)
          "one cancellation failure does not prevent sibling broadcast")
      (finally
        ;; Restore the real implementation and make the repeated close settle
        ;; the already-fenced generation before ordinary Room teardown.
        (sandbox-work/close-room-work! room)
        (d/close-room! room)))))

(deftest room-deletion-quiesces-sci-work-before-durable-delete
  (let [order (atom [])
        room (d/make-room {:id :sci-work-delete-order
                           :store (ordered-delete-store (memory/make) order)})
        close-room! d/close-room!]
    (with-redefs [d/close-room!
                  (fn [target]
                    (swap! order conj :quiesce)
                    (close-room! target))]
      (is (:ok? (rooms/delete-room! room))))
    (is (= :delete (last @order)))
    (is (some #{:quiesce} (butlast @order))
        "durable deletion happens only after the runtime quiescence fence")))

(deftest room-sci-race-cancels-and-settles-its-owned-loser
  (let [room (d/make-room {:id :sci-agent-owned-race
                           :store (memory/make)})
        sci-ctx (sandbox/fork-for-session (:ctx room))]
    (try
      (agent-ns/add-programming-ns! sci-ctx (:id room) (:ctx room) nil)
      (data-ns/add-spindel-extras-ns! sci-ctx (:ctx room))
      (let [result
            (binding [ec/*execution-context* (:ctx room)]
              (sandbox/eval-code
               sci-ctx
               (str
                "(require '[dvergr.agent :as agent] "
                "         '[org.replikativ.spindel.spin.cps :refer [spin]] "
                "         '[org.replikativ.spindel.effects.await :refer [await]] "
                "         '[spindel.comb :as comb]) "
                "(let [team (-> (agent/roster) "
                "               (agent/make-agent {:id :fast :program {:kind :scripted :delay-ms 10 :reply :fast}}) "
                "               (agent/make-agent {:id :slow :program {:kind :scripted :delay-ms 5000 :reply :slow}})) "
                "      a (agent/hire! team :fast {:task :solve}) "
                "      b (agent/hire! team :slow {:task :solve})] "
                "  @(spin (-> (await (comb/race (agent/owned-result-spin a) "
                "                                (agent/owned-result-spin b))) "
                "             :run/value)))")
               :timeout-ms 15000))]
        (is (not= "TimeoutException" (get-in result [:error :type]))
            (pr-str (:error result)))
        (is (:success result) (pr-str (:error result)))
        (is (= :fast (:value result)))
        (is (wait-until #(empty? (run/active-runs (:id room))) 1000))
        (let [by-actor (into {} (map (juxt :run/actor identity)) (run/runs room))]
          (is (= :completed (get-in by-actor [:fast :run/status])))
          (is (= :cancelled (get-in by-actor [:slow :run/status])))))
      (finally
        (d/close-room! room)))))

(deftest nested-run-sci-race-cancels-and-settles-its-owned-loser
  (let [room (d/make-room {:id :nested-sci-agent-owned-race
                           :store (memory/make)})
        parent-id (random-uuid)
        trigger (d/message :repl :_runs "coordinate" nil {:role :user})
        run-world (world/open! room parent-id :discard)
        work-room (:work run-world)
        supervisor (binding [ec/*execution-context* (:ctx room)]
                     @(sp/spin
                       (#'program/make-supervisor (:ctx room) (:ctx work-room))))
        ceiling {:program-kinds #{:echo :scripted}
                 :parent-run parent-id
                 :own-child! (fn [admit!]
                               (#'program/with-owned-child! supervisor admit!))}
        sci-ctx (sandbox/fork-for-session (:ctx work-room))]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (run/start! room :orchestrator trigger nil
                    {:id parent-id :kind :workflow})
        (d/post! room trigger))
      (agent-ns/add-programming-ns! sci-ctx (:id work-room) (:ctx work-room) ceiling)
      (data-ns/add-spindel-extras-ns! sci-ctx (:ctx work-room)
                                      {:room-id (:id work-room)
                                       :room-incarnation (:incarnation work-room)})
      (let [result
            (binding [ec/*execution-context* (:ctx work-room)]
              (sandbox/eval-code
               sci-ctx
               (str
                "(require '[dvergr.agent :as agent] "
                "         '[org.replikativ.spindel.spin.cps :refer [spin]] "
                "         '[org.replikativ.spindel.effects.await :refer [await]] "
                "         '[spindel.comb :as comb]) "
                "(let [team (-> (agent/roster) "
                "               (agent/make-agent {:id :fast :program {:kind :scripted :delay-ms 10 :reply :fast}}) "
                "               (agent/make-agent {:id :slow :program {:kind :scripted :delay-ms 5000 :reply :slow}})) "
                "      a (agent/hire! team :fast {:task :solve}) "
                "      b (agent/hire! team :slow {:task :solve})] "
                "  @(spin (-> (await (comb/race (agent/owned-result-spin a) "
                "                                (agent/owned-result-spin b))) "
                "             :run/value)))")
               :timeout-ms 15000))]
        (is (not= "TimeoutException" (get-in result [:error :type]))
            (pr-str (:error result)))
        (is (:success result) (pr-str (:error result)))
        (is (= :fast (:value result)))
        (is (wait-until #(= 1 (count (run/active-runs (:id room)))) 1000))
        (let [children (remove #(= parent-id (:run/id %)) (run/runs room))
              by-actor (into {} (map (juxt :run/actor identity)) children)]
          (is (= parent-id (get-in by-actor [:fast :run/parent])))
          (is (= parent-id (get-in by-actor [:slow :run/parent])))
          (is (= :completed (get-in by-actor [:fast :run/status])))
          (is (= :cancelled (get-in by-actor [:slow :run/status])))
          (is (= :merged (get-in by-actor [:fast :run/settlement-status])))
          (is (= :discarded (get-in by-actor [:slow :run/settlement-status])))
          (binding [ec/*execution-context* (:ctx room)]
            (is (nil? (room-registry/lookup
                       (get-in by-actor [:fast :run/world]))))
            (is (nil? (room-registry/lookup
                       (get-in by-actor [:slow :run/world])))))))
      (finally
        (try
          (binding [ec/*execution-context* (:ctx room)]
            (#'program/cancel-supervisor! supervisor)
            (#'program/seal-supervisor! supervisor)
            (let [waiter (future (#'program/await-supervisor! supervisor))]
              (when (= ::cleanup-timeout
                       (deref waiter 15000 ::cleanup-timeout))
                (future-cancel waiter)
                (throw (ex-info "Nested agent supervisor did not quiesce"
                                {:type ::cleanup-timeout})))))
          (finally
            (try
              (binding [ec/*execution-context* (:ctx room)]
                (when (some #(= parent-id (:run/id %))
                            (run/active-runs (:id room)))
                  (run/finish! parent-id :cancelled)))
              (world/settle! run-world :cancelled)
              (finally
                (d/close-room! room)))))))))

(deftest roomless-sci-can-build-rosters-but-cannot-launch-effects
  (let [ctx     (context/create-execution-context)
        sci-ctx (sandbox/fork-for-session ctx)]
    (try
      (agent-ns/add-programming-ns! sci-ctx nil ctx nil)
      (let [pure (sandbox/eval-code
                  sci-ctx
                  (str "(require '[dvergr.agent :as agent]) "
                       "(-> (agent/roster) "
                       "    (agent/make-agent {:id :worker "
                       "                       :program {:kind :echo}}) "
                       "    agent/list count)"))
            effect (sandbox/eval-code
                    sci-ctx
                    (str "(require '[dvergr.agent :as agent]) "
                         "(agent/hire! (agent/make-agent "
                         "               (agent/roster) "
                         "               {:id :worker :program {:kind :echo}}) "
                         "             :worker {:task :work})"))]
        (is (= 1 (:value pure)))
        (is (false? (:success effect)))
        (is (re-find #"room-scoped" (get-in effect [:error :message]))))
      (finally
        (context/stop-context! ctx)))))

(deftest delegation-ceiling-allows-pure-children-but-rejects-paid-children
  (let [room    (d/make-room {:id :sci-agent-delegation-ceiling
                              :store (memory/make)})
        sci-ctx (sandbox/fork-for-session (:ctx room))]
    (try
      (agent-ns/add-programming-ns!
       sci-ctx (:id room) (:ctx room)
       {:program-kinds #{:echo :scripted}})
      (let [prefix (str
                    "(require '[dvergr.agent :as agent] "
                    "         '[org.replikativ.spindel.spin.cps :refer [spin]] "
                    "         '[org.replikativ.spindel.effects.await :refer [await]]) "
                    "(def team (-> (agent/roster) "
                    "              (agent/make-agent {:id :pure :program {:kind :echo}}) "
                    "              (agent/make-agent {:id :paid :program {:kind :llm} "
                    "                                 :model-policy {:provider :test :model \"stub\"}}))) ")
            pure (binding [ec/*execution-context* (:ctx room)]
                   (sandbox/eval-code
                    sci-ctx
                    (str prefix
                         "@(spin (-> (await (agent/result-spin "
                         "                    (agent/hire! team :pure {:task :ok}))) "
                         "            :run/value))")))
            paid (binding [ec/*execution-context* (:ctx room)]
                   (sandbox/eval-code
                    sci-ctx
                    (str prefix
                         "(agent/hire! team :paid {:task :forbidden})")))]
        (is (= :ok (:value pure)))
        (is (false? (:success paid)))
        (is (re-find #"delegation ceiling" (get-in paid [:error :message])))
        (is (empty? (run/active-runs (:id room)))
            "rejected authority never admits a Run"))
      (finally
        (d/close-room! room)))))

(deftest ambient-parent-run-is-the-default-structural-parent
  (let [room      (d/make-room {:id :sci-agent-ambient-parent
                                :store (memory/make)})
        sci-ctx   (sandbox/fork-for-session (:ctx room))
        parent-id (:run/id (run/start! room :parent (random-uuid) nil))]
    (try
      (agent-ns/add-programming-ns!
       sci-ctx (:id room) (:ctx room)
       {:program-kinds #{:echo :scripted}
        :parent-run parent-id})
      (let [result
            (binding [ec/*execution-context* (:ctx room)]
              (sandbox/eval-code
               sci-ctx
               (str
                "(require '[dvergr.agent :as agent] "
                "         '[org.replikativ.spindel.spin.cps :refer [spin]] "
                "         '[org.replikativ.spindel.effects.await :refer [await]]) "
                "(let [team (agent/make-agent (agent/roster) "
                "                             {:id :child :program {:kind :echo}}) "
                "      child (agent/hire! team :child {:task :work})] "
                "  @(spin (await (agent/result-spin child))) "
                "  (:run/parent (agent/observe child)))")))]
        (is (:success result) (pr-str (:error result)))
        (is (= parent-id (:value result)))
        (let [child-id (:run/id (first (filter #(= :child (:run/actor %))
                                               (run/runs room))))]
          (is (= #{child-id}
                 (:run/caused-by (run/run room parent-id))))))
      (finally
        (run/finish! parent-id :completed)
        (d/close-room! room)))))

(deftest nested-sci-harness-inspects-only-its-run-tree
  (let [room       (d/make-room {:id :sci-agent-scoped-inspection
                                 :store (memory/make)})
        sci-ctx    (sandbox/fork-for-session (:ctx room))
        parent-id  (:run/id (run/start! room :orchestrator (random-uuid) nil))
        sibling-id (:run/id (run/start! room :private-sibling (random-uuid) nil))]
    (try
      (agent-ns/add-programming-ns!
       sci-ctx (:id room) (:ctx room)
       {:program-kinds #{:echo :scripted}
        :parent-run parent-id})
      (let [result
            (binding [ec/*execution-context* (:ctx room)]
              (sandbox/eval-code
               sci-ctx
               (str
                "(require '[dvergr.agent :as agent] "
                "         '[org.replikativ.spindel.spin.cps :refer [spin]] "
                "         '[org.replikativ.spindel.effects.await :refer [await]]) "
                "(let [team (agent/make-agent (agent/roster) "
                "                             {:id :analyst :program {:kind :echo}}) "
                "      child (agent/hire! team :analyst {:task :renewal-risk})] "
                "  @(spin (await (agent/result-spin child))) "
                "  (let [view (agent/inspect)] "
                "    {:scope (:observation/scope-run-id view) "
                "     :receipt (:observation/receipt-id view) "
                "     :actors (mapv :run/actor (:observation/runs view)) "
                "     :runs (get-in view [:observation/summary :runs])}))")))]
        (is (:success result) (pr-str (:error result)))
        (is (= parent-id (get-in result [:value :scope])))
        (is (= [:orchestrator :analyst] (get-in result [:value :actors])))
        (is (= 2 (get-in result [:value :runs])))
        (is (not-any? #{:private-sibling} (get-in result [:value :actors])))
        (is (some #(= (get-in result [:value :receipt]) (:activity/id %))
                  (mapcat activity/message-activities
                          (d/messages room {:limit 20}))))
        (is (false? (observation/consume-receipt! parent-id (random-uuid)))
            "writable Room activity alone cannot mint verifier authority")
        (is (observation/consume-receipt!
             parent-id (get-in result [:value :receipt])))
        (is (false? (observation/consume-receipt!
                     parent-id (get-in result [:value :receipt])))
            "inspection proof is single-use"))
      (finally
        (run/finish! parent-id :completed)
        (run/finish! sibling-id :completed)
        (d/close-room! room)))))

(deftest sci-inspection-without-an-ambient-run-fails-closed
  (let [room (d/make-room {:id :sci-agent-unscoped-inspection
                           :store (memory/make)})
        sci-ctx (sandbox/fork-for-session (:ctx room))]
    (try
      (agent-ns/add-programming-ns!
       sci-ctx (:id room) (:ctx room) {:program-kinds #{:echo}})
      (let [result (binding [ec/*execution-context* (:ctx room)]
                     (sandbox/eval-code
                      sci-ctx
                      "(require '[dvergr.agent :as agent]) (agent/inspect)"))]
        (is (false? (:success result)))
        (is (re-find #"requires an ambient Run scope"
                     (str (:error result)))))
      (finally
        (d/close-room! room)))))

(deftest sandbox-cannot-redirect-structural-parent-authority
  (let [room      (d/make-room {:id :sci-agent-parent-authority
                                :store (memory/make)})
        sci-ctx   (sandbox/fork-for-session (:ctx room))
        parent-id (random-uuid)]
    (try
      (agent-ns/add-programming-ns!
       sci-ctx (:id room) (:ctx room)
       {:program-kinds #{:echo :scripted}
        :parent-run parent-id})
      (let [result
            (binding [ec/*execution-context* (:ctx room)]
              (sandbox/eval-code
               sci-ctx
               (str
                "(require '[dvergr.agent :as agent]) "
                "(let [team (agent/make-agent (agent/roster) "
                "                             {:id :child :program {:kind :echo}})] "
                "  (agent/hire! team :child {:task :work "
                "                             :parent-run #uuid \""
                (random-uuid)
                "\"}))")))]
        (is (false? (:success result)))
        (is (re-find #"current Run" (get-in result [:error :message])))
        (is (empty? (run/active-runs (:id room))))
        (is (empty? (d/messages room {:limit 10}))))
      (finally
        (d/close-room! room)))))

(deftest sandbox-balance-projects-only-the-current-resource-wallet
  (let [room      (d/make-room {:id :sci-agent-balance
                                :store (memory/make)})
        sci-ctx   (sandbox/fork-for-session (:ctx room))
        parent-id (random-uuid)]
    (try
      (with-redefs [resource/balance (fn [actual-room]
                                       (is (= room actual-room))
                                       {"cpu-ms" 100M})
                    resource/run-balance (fn [actual-room actual-run]
                                           (is (= room actual-room))
                                           (is (= parent-id actual-run))
                                           {"cpu-ms" 40M})]
        (agent-ns/add-programming-ns!
         sci-ctx (:id room) (:ctx room)
         {:program-kinds #{:echo}
          :parent-run parent-id})
        (let [result (binding [ec/*execution-context* (:ctx room)]
                       (sandbox/eval-code
                        sci-ctx
                        "(require '[dvergr.agent :as agent]) (agent/balance)"))]
          (is (:success result) (pr-str (:error result)))
          (is (= {"cpu-ms" 40M} (:value result)))))
      (finally
        (d/close-room! room)))))

(deftest legacy-room-hire-is-not-an-alternate-delegation-path
  (let [room    (d/make-room {:id :sci-legacy-hire-ceiling
                              :store (memory/make)})
        sci-ctx (sandbox/fork-for-session (:ctx room))]
    (try
      (room-ns/add-room-ns!
       sci-ctx nil nil (:id room) (:ctx room)
       {:program-kinds #{:echo :scripted}})
      (let [result
            (binding [ec/*execution-context* (:ctx room)]
              (sandbox/eval-code
               sci-ctx
               "(require '[dvergr.room :as room]) (room/hire :sci-legacy-hire-ceiling {:goal :forbidden})"))]
        (is (false? (:success result)))
        (is (re-find #"(?:Could not|Unable to) resolve symbol:?.*room/hire"
                     (get-in result [:error :message])))
        (is (empty? (run/active-runs (:id room)))
            "the removed API cannot start an untracked execution"))
      (finally
        (d/close-room! room)))))

(deftest nested-sci-cannot-bypass-authority-through-cheap-llm-calls
  (let [ctx     (context/create-execution-context)
        sci-ctx (sandbox/fork-for-session ctx)]
    (try
      (kb-ns/add-llm-ns! sci-ctx {:provider-effects? false})
      (doseq [form ["(require '[llm]) (llm/call \"classify\" \"input\")"
                    "(require '[llm]) (llm/summarize \"input\")"]]
        (let [result (sandbox/eval-code sci-ctx form)]
          (is (false? (:success result)))
          (is (re-find #"provider effects.*delegation ceiling"
                       (get-in result [:error :message])))))
      (finally
        (context/stop-context! ctx)))))
