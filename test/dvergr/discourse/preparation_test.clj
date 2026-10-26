(ns dvergr.discourse.preparation-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.room-context :as room-context]
            [dvergr.agent.run :as run]
            [dvergr.agent.program :as program]
            [dvergr.agent.turn :as turn]
            [dvergr.chat.context :as chat-context]
            [dvergr.discourse :as d]
            [dvergr.discourse.llm :as llm]
            [dvergr.runtime.preparation :as preparation]
            [dvergr.sandbox.work :as sandbox-work]
            [dvergr.room.store.memory :as memory]
            [dvergr.room.registry :as registry]
            [dvergr.substrate.load :as load]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.component :as component]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.spin.core :as spin-core]
            [org.replikativ.spindel.engine.core :as ec])
  (:import [java.util.concurrent CountDownLatch]))

(defn- wait-for-release! [^CountDownLatch gate]
  ;; Model a host loader which cannot promise immediate interruption. A Future
  ;; cancellation acknowledgement must not be mistaken for physical completion.
  (loop []
    (when-not (try (.await gate) true
                   (catch InterruptedException _ false))
      (recur))))

(defn- ask! [room actor text]
  (let [result (promise)]
    (binding [ec/*execution-context* (:ctx room)]
      (sp/spawn! (sp/spin (deliver result (sp/await (d/ask room actor {:content text}))))))
    result))

(deftest namespace-preparation-does-not-own-the-engine-drain
  (let [room (d/make-room {:id :preparation-progress :store (memory/make)})
        entered (promise)
        gate (CountDownLatch. 1)
        require! load/require!
        calls (atom 0)]
    (try
      (with-redefs [load/require!
                    (fn [symbol]
                      (when (= 'dvergr.intake.bash symbol)
                        (deliver entered {:ctx ec/*execution-context* :spin ec/*spin-id*})
                        (wait-for-release! gate))
                      (require! symbol))]
        (d/join room (d/echo :sibling (:ctx room)))
        (d/join room
                (llm/llm-agent {:id :agent :tools #{}
                                :spec {:provider :test :model "fixture"}
                                :run-turn-fn (fn [ctx _]
                                               (swap! calls inc)
                                               (chat-context/add-message!
                                                ctx {:role :assistant :content "ready"})
                                               :complete)}))
        (d/post! room (d/message :human :agent "prepare"))
        (let [worker (deref entered 30000 ::timeout)]
          (is (not= ::timeout worker))
          (is (identical? (:ctx room) (:ctx worker)))
          (is (nil? (:spin worker)) "native setup never inherits the engine spin identity")
          (is (not= ::timeout (deref (ask! room :sibling "progress") 3000 ::timeout))
              "same-room routing and a sibling reactive handler progress while the loader is blocked")
          (is (zero? @calls))
          (is (= ::preparation/not-quiescent
                 (try (d/fork-room room {:clone-participants? false}) nil
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
              "a partially installed world is never snapshotted"))
        (.countDown gate)
        (let [answer (deref (ask! room :agent "continue") 30000 ::timeout)]
          (is (not= ::timeout answer))))
      (finally
        (.countDown gate)
        (d/close-room! room)))))

(deftest departed-participant-retains-preparation-until-physical-cleanup
  (let [room (d/make-room {:id :preparation-cancel :store (memory/make)})
        entered (promise)
        gate (CountDownLatch. 1)
        release-observed (promise)
        close-started (promise)
        require! load/require!
        release! chat-context/release-sci-in!
        model-calls (atom 0)
        baseline (binding [ec/*execution-context* (:ctx room)]
                   (count (component/registered)))]
    (try
      (with-redefs [load/require!
                    (fn [symbol]
                      (when (= 'dvergr.intake.bash symbol)
                        (deliver entered true)
                        (wait-for-release! gate))
                      (require! symbol))
                    chat-context/release-sci-in!
                    (fn [ctx execution-ctx]
                      (deliver release-observed
                               (set (map :kind (vals (:workers @(:state program/*worker-supervisor*))))))
                      (release! ctx execution-ctx))]
        (d/join room
                (llm/llm-agent {:id :agent :tools #{}
                                :spec {:provider :test :model "fixture"}
                                :run-turn-fn (fn [_ _] (swap! model-calls inc) :complete)}))
        (d/post! room (d/message :human :agent "prepare"))
        (is (= true (deref entered 30000 ::timeout)))
        (d/leave room :agent)
        (is (nil? (get @(:participants room) :agent)))
        (is (nil? (room-context/lookup (:id room) :agent)))
        (is (not (realized? release-observed))
            "abandonment cannot release an interpreter a live loader still holds")
        (is (= (inc baseline)
               (binding [ec/*execution-context* (:ctx room)]
                 (count (component/registered)))))
        (is (seq (get @(:meta room) preparation/leases-key))
            "leaving routing does not drop physical resource ownership")
        (add-watch (:meta room) ::closing
                   (fn [_ _ _ state]
                     (when (= :tearing-down (get-in state [:dvergr/fork-transfer-state :state]))
                       (deliver close-started true))))
        (let [closed (future (d/close-room! room) :closed)]
          (is (= true (deref close-started 3000 ::timeout)))
          (is (not (realized? closed)))
          (.countDown gate)
          (is (= :closed (deref closed 30000 ::timeout)))
          (is (= #{:cleanup} (deref release-observed 3000 ::timeout))
              "SCI release runs only after the normal preparation worker acknowledged exit"))
        (is (zero? @model-calls))
        (is (nil? (room-context/lookup (:id room) :agent)))
        (is (empty? (run/active-runs (:id room))))
        (is (empty? (filter #(= :agent (:from %)) (d/messages room)))))
      (finally
        (.countDown gate)
        (remove-watch (:meta room) ::closing)
        (d/close-room! room)))))

(deftest failed-cleanup-transfer-keeps-lexical-constructor-ownership
  (doseq [transfer [(fn [_] (throw (ex-info "transfer failed" {})))
                    (constantly nil)]]
    (let [room (d/make-room {:id (keyword (str "transfer-" (random-uuid)))})
          releases (atom 0)
          release! chat-context/release-sci-in!]
      (try
        (with-redefs [chat-context/release-sci-in!
                      (fn [ctx execution-ctx]
                        (swap! releases inc)
                        (release! ctx execution-ctx))]
          (is (thrown? clojure.lang.ExceptionInfo
                       (turn/new-working-ctx {:execution-ctx (:ctx room)
                                              :own-cleanup! transfer})))
          (is (= 1 @releases))
          (is (empty? (binding [ec/*execution-context* (:ctx room)]
                        (component/registered)))))
        (finally (d/close-room! room))))))

(deftest failed-preparation-cleanup-cannot-advance-to-model-execution
  (let [room (d/make-room {:id :preparation-cleanup-failure})
        model-calls (atom 0)]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (let [scope (program/preparation-scope (:ctx room))
              worker (program/start-preparation!
                      scope
                      (fn []
                        ;; Synthetic cleanup owns no external resource. Its
                        ;; failed acknowledgement must still fail closed.
                        (program/register-preparation-cleanup!
                         scope #(throw (ex-info "cleanup failed" {})))
                        :prepared))]
          (is (= :prepared @(program/preparation-result worker)))
          (program/complete-preparation! scope)
          (is (= ::program/preparation-cleanup-failed
                 (try @(sp/spin
                        (sp/await (program/preparation-completion scope))
                        (swap! model-calls inc))
                      nil
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
          (is (zero? @model-calls))
          (is (not (program/preparation-finished? scope)))
          (is (not (program/preparation-admission-ready? scope)))
          (is (= ::program/preparation-cleanup-failed
                 (try (program/await-preparation! scope 3000) nil
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))
      (finally (d/close-room! room)))))

(deftest delayed-leave-never-removes-a-replacement-participant
  (let [room (d/make-room {:id :preparation-leave-generation})
        original (d/join room (d/echo :agent (:ctx room)))
        entered (promise)
        gate (CountDownLatch. 1)
        cleanup @#'d/cleanup-owned-spin!]
    (try
      (with-redefs-fn
        {#'d/cleanup-owned-spin!
         (fn [target spin]
           (when (identical? spin (:process original))
             (deliver entered true)
             (.await gate))
           (cleanup target spin))}
        (fn []
          (let [left (future (d/leave room :agent (:participant/incarnation original)))]
            (is (= true (deref entered 3000 ::timeout)))
            (let [replacement (d/join room original)]
              (doseq [field [:active :pumps :subs :obligations :deferred-inbox]]
                (is (not (identical? (field original) (field replacement)))))
              (.countDown gate)
              (is (nil? (deref left 3000 ::timeout)))
              (is (= (:participant/incarnation replacement)
                     (:participant/incarnation (get @(:participants room) :agent))))
              (d/leave room :agent (:participant/incarnation original))
              (is (identical? (:active replacement)
                              (:active (get @(:participants room) :agent))))))))
      (finally
        (.countDown gate)
        (d/close-room! room)))))

(deftest failed-close-drain-is-retained-and-exclusively-retryable
  (let [room (d/make-room {:id :preparation-close-recovery})
        calls (atom 0)
        retry-entered (promise)
        gate (CountDownLatch. 1)
        drain preparation/drain-room!
        retrying (atom nil)]
    (try
      (with-redefs [preparation/drain-room!
                    (fn [target]
                      (case (swap! calls inc)
                        1 (throw (ex-info "injected unconfirmed physical drain" {}))
                        2 (do (deliver retry-entered true) (.await gate) (drain target))
                        (drain target)))]
        (is (thrown? clojure.lang.ExceptionInfo (d/close-room! room)))
        (let [token (get-in @(:meta room) [:dvergr/fork-transfer-state :token])]
          (is (= :recovery-required (get-in @(:meta room) [:dvergr/fork-transfer-state :state])))
          (is (identical? room (binding [ec/*execution-context* (:ctx room)] (registry/lookup (:id room)))))
          (reset! retrying (future (d/close-room! room)))
          (is (= true (deref retry-entered 3000 ::timeout)))
          (is (= token (get-in @(:meta room) [:dvergr/fork-transfer-state :token])))
          (is (= ::registry/lifecycle-in-progress
                 (try (d/close-room! room) nil
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
          (is (= :tearing-down (get-in @(:meta room) [:dvergr/fork-transfer-state :state])))
          (.countDown gate)
          (is (nil? (deref @retrying 3000 ::timeout)))))
      (finally
        (.countDown gate)
        (when @retrying (deref @retrying 3000 nil))
        (d/close-room! room)))))

(deftest shared-root-shutdown-waits-for-pre-cache-preparation
  (let [root (context/create-execution-context)
        first-room (d/make-room {:id :shutdown-first :ctx root})
        room (d/make-room {:id :shutdown-pending :ctx root})
        participant (d/join room (d/echo :agent root))
        scope (binding [ec/*execution-context* root] (program/preparation-scope root))
        entered (promise)
        closing (promise)
        gate (CountDownLatch. 1)
        stopped (atom nil)]
    (try
      (preparation/admit! room participant
                          {:cancel! #(program/cancel-preparation! scope)
                           :join! #(program/await-preparation! scope %)
                           :finished? #(program/preparation-finished? scope)})
      (program/start-preparation! scope #(do (deliver entered true) (wait-for-release! gate)))
      (is (= true (deref entered 3000 ::timeout)))
      (is (nil? (room-context/lookup (:id room) :agent)))
      (add-watch (:meta room) ::shutdown
                 (fn [_ _ _ state]
                   (when (= :tearing-down (get-in state [:dvergr/fork-transfer-state :state]))
                     (deliver closing true))))
      (reset! stopped (future (d/close-registered-rooms! root)))
      (is (= true (deref closing 3000 ::timeout)))
      (is (not (realized? @stopped)))
      (let [alive (promise)]
        (binding [ec/*execution-context* root]
          (sp/spawn! (sp/spin (deliver alive :alive))))
        (is (= :alive (deref alive 3000 ::timeout))
            "closing one Room must not close the executor a sibling still needs"))
      (.countDown gate)
      (let [shutdown (deref @stopped 3000 ::timeout)]
        (is (not= ::timeout shutdown))
        (is (= 1 (count (:contexts shutdown))))
        (is (identical? root (first (:contexts shutdown))))
        (is (empty? (binding [ec/*execution-context* root] (registry/list-rooms)))))
      (finally
        (.countDown gate)
        (when @stopped (deref @stopped 3000 nil))
        (remove-watch (:meta room) ::shutdown)
        (d/close-room! first-room {:close-context? false})
        (d/close-room! room {:close-context? false})
        (context/close-context! root)))))

(deftest shutdown-refuses-an-unexpected-late-room-before-releasing-contexts
  (let [root (context/create-execution-context)
        room (d/make-room {:id :shutdown-original :ctx root})
        late (atom nil)
        close d/close-room!]
    (try
      (with-redefs [d/close-room!
                    (fn [target opts]
                      (close target opts)
                      (when (= (:id room) (:id target))
                        (reset! late (d/make-room {:id :shutdown-late :ctx root}))))]
        (is (= ::d/shutdown-not-quiescent
               (try (d/close-registered-rooms! root) nil
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
      (is (identical? @late (binding [ec/*execution-context* root] (registry/lookup :shutdown-late))))
      (is (= 1 (count (:contexts (d/close-registered-rooms! root)))))
      (finally
        (when @late (d/close-room! @late {:close-context? false}))
        (context/close-context! root)))))

(deftest shutdown-receipt-retains-unregistered-contexts-across-failures
  (let [root (context/create-execution-context)
        first-ctx (context/fork-context root :mode :frozen)
        second-ctx (context/fork-context root :mode :frozen)
        first-room (d/make-room {:id :shutdown-receipt-first :ctx first-ctx})
        second-room (d/make-room {:id :shutdown-receipt-second :ctx second-ctx})
        close-room d/close-room!
        attempts (atom 0)]
    (try
      (with-redefs [d/close-room!
                    (fn [room opts]
                      (if (= 2 (swap! attempts inc))
                        (throw (ex-info "injected Room drain failure" {}))
                        (close-room room opts)))]
        (is (thrown? clojure.lang.ExceptionInfo (d/close-registered-rooms! root))))
      (let [receipt (binding [ec/*execution-context* root] (registry/shutdown-context-receipt!))]
        (is (= #{(:fork-id root) (:fork-id first-ctx) (:fork-id second-ctx)} (set (keys @receipt))))
        (is (= 1 (count (binding [ec/*execution-context* root] (registry/list-rooms)))))
        (let [shutdown (d/close-registered-rooms! root)
              closed (atom [])
              fail? (atom true)]
          (is (identical? root (last (:contexts shutdown))))
          (is (= 3 (count (:contexts shutdown))))
          (with-redefs [context/close-context!
                        (fn [execution-context]
                          (swap! closed conj (:fork-id execution-context))
                          (when (compare-and-set! fail? true false)
                            (throw (ex-info "injected physical close failure" {}))))]
            (is (thrown? clojure.lang.ExceptionInfo (d/close-shutdown-contexts! shutdown)))
            (is (= 3 (count @receipt)) "a failed physical close acknowledges nothing")
            (d/close-shutdown-contexts! (d/close-registered-rooms! root))
            (is (= (:fork-id root) (last @closed))))
          (is (empty? @receipt))))
      (finally
        (d/close-room! first-room {:close-context? false})
        (d/close-room! second-room {:close-context? false})
        (context/close-context! first-ctx)
        (context/close-context! second-ctx)
        (context/close-context! root)))))

(deftest room-cache-cleanup-retains-failed-entries-until-explicit-close-retry
  (doseq [failed-agent [:first :second]]
    (let [room (d/make-room {:id (keyword (str "cache-cleanup-retry-" (random-uuid)))})
          first-ctx (room-context/ensure-ctx! room :first {:sandbox #{}})
          second-ctx (room-context/ensure-ctx! room :second {:sandbox #{}})
          failed-ctx (if (= :first failed-agent) first-ctx second-ctx)
          failed-capability (:capability-id failed-ctx)
          other-capability (:capability-id (if (= :first failed-agent) second-ctx first-ctx))
          fail? (atom true)
          released (atom [])
          closed (atom [])
          release! chat-context/release-sci-in!
          close! context/close-context!]
      (try
        (with-redefs [chat-context/release-sci-in!
                      (fn [cctx execution-ctx]
                        (swap! released conj (:capability-id cctx))
                        (when (and @fail? (= failed-capability (:capability-id cctx)))
                          (throw (ex-info "injected cache release failure" {})))
                        (release! cctx execution-ctx))
                      context/close-context!
                      (fn [execution-ctx]
                        (swap! closed conj execution-ctx)
                        (close! execution-ctx))]
          (is (thrown? clojure.lang.ExceptionInfo (d/close-room! room)))
          (is (= {failed-capability 1 other-capability 1} (frequencies @released))
              "a failed entry does not skip releasing other detached entries")
          (is (empty? @closed) "an incomplete receipt prevents context closure")
          (is (nil? (room-context/lookup (:id room) :first)))
          (is (nil? (room-context/lookup (:id room) :second)))
          (let [leases (vals (get @(:meta room) preparation/leases-key))
                retained (mapcat #(vals @(:resource %)) leases)]
            (is (= [:room-cleanup] (mapv :kind leases)))
            (is (= 1 (count retained)))
            (is (identical? failed-ctx (:chat-ctx (first retained)))
                "the exact failed owner survives after cache detachment"))
          (is (identical? room (binding [ec/*execution-context* (:ctx room)]
                                 (registry/lookup (:id room)))))
          (let [alive (promise)]
            (binding [ec/*execution-context* (:ctx room)]
              (sp/spawn! (sp/spin (deliver alive :alive))))
            (is (= :alive (deref alive 3000 ::timeout))))
          (reset! fail? false)
          (d/close-room! room)
          (is (= {failed-capability 2 other-capability 1} (frequencies @released))
              "only failed owners are retried")
          (is (= 1 (count @closed)))
          (is (empty? (get @(:meta room) preparation/leases-key)))
          (is (= :closed (get-in @(:meta room) [:dvergr/fork-transfer-state :state]))))
        (finally
          (reset! fail? false)
          (d/close-room! room))))))

(deftest raw-room-replacement-requires-its-old-cache-owner-to-close-first
  (let [root (context/create-execution-context)
        room (d/make-room {:id :replacement-cache-owner :ctx root})
        original (room-context/ensure-ctx! room :agent {:sandbox #{}})
        replacement (assoc room :incarnation (random-uuid)
                           :meta (atom {}) :participants (atom {}))
        releases (atom [])
        release! chat-context/release-sci-in!]
    (try
      (with-redefs [chat-context/release-sci-in!
                    (fn [cctx execution-ctx]
                      (swap! releases conj cctx)
                      (release! cctx execution-ctx))]
        (is (= ::sandbox-work/room-incarnation-conflict
               (try (binding [ec/*execution-context* root]
                      (registry/register! replacement))
                    nil
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (is (identical? room (binding [ec/*execution-context* root]
                               (registry/lookup (:id room)))))
        (is (identical? original (room-context/ensure-ctx! room :agent {:sandbox #{}})))
        (is (empty? @releases))
        (d/close-room! room {:close-context? false})
        (is (= 1 (count @releases)))
        (is (identical? original (first @releases)))
        (binding [ec/*execution-context* root] (registry/register! replacement))
        (let [fresh (room-context/ensure-ctx! replacement :agent {:sandbox #{}})]
          (is (not (identical? original fresh)))
          (is (not= (:capability-id original) (:capability-id fresh))))
        (d/close-room! replacement {:close-context? false})
        (is (= 2 (count @releases))))
      (finally
        (binding [ec/*execution-context* root]
          (when-let [current (registry/lookup (:id room))]
            (d/close-room! current {:close-context? false})))
        (context/close-context! root)))))

(defn- watch-settlement-finish! [room finished]
  (run/watch-runs!
   (keyword (str "settlement-finish-" (random-uuid)))
   (fn [{:keys [type run]}]
     (when (and (= :run/finished type) (= (:id room) (:run/room run))
                (= :settlement (:run/kind run)))
       (deliver finished run)))))

(deftest root-context-close-preserves-distinct-fork-context-owners
  (let [room (d/make-room {:id :root-executor-coowner})
        child (d/fork-room room {:isolation :ctx :clone-participants? false})]
    (try
      (is (not (identical? (:ctx room) (:ctx child))))
      (is (identical? (:executor (:ctx room)) (:executor (:ctx child))))
      (is (= ::d/context-still-owned
             (try (d/close-room! room) nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
      (is (identical? room (binding [ec/*execution-context* (:ctx room)] (registry/lookup (:id room)))))
      (is (identical? child (binding [ec/*execution-context* (:ctx room)] (registry/lookup (:id child)))))
      (let [alive (promise)]
        (binding [ec/*execution-context* (:ctx child)]
          (sp/spawn! (sp/spin (deliver alive :alive))))
        (is (= :alive (deref alive 3000 ::timeout))))
      (d/discard child)
      (d/close-room! room)
      (is (= :closed (get-in @(:meta room) [:dvergr/fork-transfer-state :state])))
      (finally
        (when-not (= :closed (get-in @(:meta room) [:dvergr/fork-transfer-state :state]))
          (d/discard child)
          (d/close-room! room))))))

(deftest root-context-close-recognizes-a-same-world-root-facade
  (let [root (context/create-execution-context)
        facade (assoc root :metadata (assoc (:metadata root) :test/facade true))
        room (d/make-room {:id :root-facade-owner :ctx root})
        coowner (d/make-room {:id :root-facade-coowner :ctx facade})]
    (try
      (is (not (identical? root facade)))
      (is (= (:fork-id root) (:fork-id facade)))
      (is (identical? (:executor root) (:executor facade)))
      (is (= ::d/context-still-owned
             (try (d/close-room! room) nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
      (is (identical? room (binding [ec/*execution-context* root] (registry/lookup (:id room)))))
      (d/close-room! coowner {:close-context? false})
      (d/close-room! room)
      (is (= :closed (get-in @(:meta room) [:dvergr/fork-transfer-state :state])))
      (finally
        (d/close-room! coowner {:close-context? false})
        (d/close-room! room)))))

(defn- fork-recorder [fork! child finalizing]
  (fn
    ([room] ((fork-recorder fork! child finalizing) room {}))
    ([room opts]
     (let [fork (fork! room opts)]
       (reset! child fork)
       (add-watch (:meta fork) ::extent-finalizing
                  (fn [_ _ _ state]
                    (when (= :discard (get-in state [:dvergr/fork-transfer-state :operation]))
                      (deliver finalizing true))))
       fork))))

(deftest cancelled-speculation-before-owned-admission-never-creates-a-fork
  (let [room (d/make-room {:id :cancelled-before-speculation})
        entered (promise)
        exited (promise)
        gate (CountDownLatch. 1)
        fork-calls (atom 0)
        fork! d/fork-room
        on-worker! program/on-worker!]
    (try
      (with-redefs [program/on-worker!
                    (fn [target owner f]
                      (deliver entered true)
                      (try
                        (.await gate)
                        (on-worker! target owner f)
                        (finally (deliver exited true))))
                    d/fork-room
                    (fn
                      ([target] (swap! fork-calls inc) (fork! target {}))
                      ([target opts] (swap! fork-calls inc) (fork! target opts)))]
        (let [task (d/simulate-reply room :unused {:content "must not start"})]
          (binding [ec/*execution-context* (:ctx room)]
            (sp/spawn! task {:on-error (fn [_])}))
          (is (= true (deref entered 3000 ::timeout)))
          (binding [ec/*execution-context* (:ctx room)] (spin-core/cancel-spin! task))
          (is (= true (deref exited 3000 ::timeout)))
          (is (zero? @fork-calls))
          (is (empty? (run/active-runs (:id room))))))
      (finally
        (.countDown gate)
        (d/close-room! room)))))

(deftest concurrent-speculation-awaiters-clean-every-admitted-native-extent
  (let [room (d/make-room {:id :shared-speculative-extent})
        entered (promise)
        second-entered (promise)
        second-awaiting (promise)
        attached (promise)
        first-result (promise)
        second-result (promise)
        gate (CountDownLatch. 1)
        extent-exited (CountDownLatch. 2)
        forks (atom [])
        bodies (atom 0)
        discards (atom [])
        fork! d/fork-room
        discard! d/discard
        owned-worker! program/on-worker!
        record-fork! (fn [target opts]
                       (let [fork (fork! target opts)]
                         (swap! forks conj (:id fork))
                         fork))]
    (try
      (with-redefs [d/fork-room
                    (fn
                      ([target] (record-fork! target {}))
                      ([target opts] (record-fork! target opts)))
                    d/discard (fn [fork]
                                (let [result (discard! fork)]
                                  (swap! discards conj (:id fork))
                                  result))
                    program/on-worker!
                    (fn [target owner f]
                      (try (owned-worker! target owner f)
                           (finally (.countDown extent-exited))))]
        (let [task (d/imagine-conversation
                    room
                    (fn [_]
                      (when (= 2 (swap! bodies inc))
                        (deliver second-entered true))
                      (deliver entered true)
                      (.await gate)
                      (sp/spin :shared)))
              first-waiter (binding [ec/*execution-context* (:ctx room)]
                             (sp/spin (sp/await task)))
              second-waiter (binding [ec/*execution-context* (:ctx room)]
                              (sp/spin
                               (deliver second-awaiting true)
                               (sp/await task)))]
          (binding [ec/*execution-context* (:ctx room)]
            (sp/spawn! first-waiter {:on-success #(deliver first-result %)
                                     :on-error #(deliver first-result {:error %})}))
          (is (= true (deref entered 30000 ::timeout)))
          (binding [ec/*execution-context* (:ctx room)]
            (sp/spawn! second-waiter {:on-success #(deliver second-result %)
                                      :on-error #(deliver second-result {:error %})}))
          (is (= true (deref second-awaiting 3000 ::timeout)))
          ;; This engine event runs after the second body has parked at await,
          ;; while the first native extent remains physically blocked.
          (binding [ec/*execution-context* (:ctx room)]
            (sp/spawn! (sp/spin (deliver attached true))))
          (is (= true (deref attached 3000 ::timeout)))
          ;; Ordinary Spins deliberately re-enter on a second uncached await
          ;; (Spindel stale_run_test's sibling-awaiter regression). Both native
          ;; evaluations must be admitted before either is allowed to finish.
          (is (= true (deref second-entered 30000 ::timeout)))
          (.countDown gate)
          (let [a (deref first-result 30000 ::timeout)
                b (deref second-result 30000 ::timeout)]
            (is (= :shared (:outcome a)))
            (is (= a b)))
          ;; Both callbacks can receive the latest evaluation's result before
          ;; the older native evaluation physically exits. Join both owned
          ;; worker boundaries, whose return follows durable Run retirement.
          (is (.await extent-exited 30 java.util.concurrent.TimeUnit/SECONDS))
          (is (= 2 (count @forks)))
          (is (= 2 @bodies))
          (is (= (frequencies @forks) (frequencies @discards)))
          (is (every? #{1} (vals (frequencies @discards))))
          (is (empty? (d/log room)))
          (is (empty? (run/active-runs (:id room))))))
      (finally
        (.countDown gate)
        (d/close-room! room)))))

(deftest cancelled-speculation-retains-its-run-through-physical-child-cleanup
  (let [room (d/make-room {:id :cancelled-speculative-extent :store (memory/make)})
        entered (promise)
        finalizing (promise)
        finished (promise)
        outer (promise)
        outer-returned (promise)
        child (atom nil)
        gate (CountDownLatch. 1)
        require! load/require!
        fork! d/fork-room
        on-worker! program/on-worker!
        watcher (watch-settlement-finish! room finished)]
    (try
      (with-redefs [load/require!
                    (fn [symbol]
                      (when (= 'dvergr.intake.bash symbol)
                        (deliver entered true)
                        (wait-for-release! gate))
                      (require! symbol))
                    d/fork-room (fork-recorder fork! child finalizing)
                    program/on-worker!
                    (fn [target owner f]
                      (deliver outer {:thread (Thread/currentThread) :spin ec/*spin-id*})
                      (try (on-worker! target owner f)
                           (finally
                             (deliver outer-returned (.isInterrupted (Thread/currentThread))))))]
        (d/join room (llm/llm-agent {:id :agent :tools #{}
                                     :spec {:provider :test :model "fixture"}
                                     :run-turn-fn (fn [_ _] :complete)}))
        (let [task (d/simulate-reply room :agent {:content "hypothetical"})]
          (binding [ec/*execution-context* (:ctx room)]
            (sp/spawn! task {:on-error (fn [_])}))
          (is (= true (deref entered 30000 ::timeout)))
          (is (= 1 (count (run/active-runs (:id room)))))
          (binding [ec/*execution-context* (:ctx room)] (spin-core/cancel-spin! task))
          (is (= true (deref finalizing 3000 ::timeout)))
          (let [{:keys [thread spin]} (deref outer 3000 ::timeout)]
            (is (nil? spin) "only a native host waiter can be interrupted")
            (.interrupt ^Thread thread)
            (.interrupt ^Thread thread))
          (is (not (realized? finished)) "cancellation is not physical cleanup acknowledgement")
          (is (= 1 (count (run/active-runs (:id room)))))
          (let [alive (promise)]
            (binding [ec/*execution-context* (:ctx room)]
              (sp/spawn! (sp/spin (deliver alive :alive))))
            (is (= :alive (deref alive 3000 ::timeout))))
          (.countDown gate)
          (is (= :cancelled (:run/status (deref finished 30000 ::timeout))))
          (is (= true (deref outer-returned 3000 ::timeout)) "restore the host interrupt after Run retirement")
          (is (empty? (run/active-runs (:id room))))
          (is (nil? (binding [ec/*execution-context* (:ctx room)] (registry/lookup (:id @child)))))))
      (finally
        (.countDown gate)
        (when @child (remove-watch (:meta @child) ::extent-finalizing))
        (run/unwatch-runs! watcher)
        (when @child
          (doseq [lease (vals (get @(:meta @child) preparation/leases-key))]
            ((:join! lease) 30000))
          (d/discard @child))
        (d/close-room! room)))))

(deftest failed-speculative-discard-retains-canonical-shared-context-owners
  (let [room (d/make-room {:id :failed-speculative-discard :store (memory/make)})
        entered (promise)
        finalizing (promise)
        finished (promise)
        outcome (promise)
        child (atom nil)
        gate (CountDownLatch. 1)
        require! load/require!
        fork! d/fork-room
        watcher (watch-settlement-finish! room finished)]
    (try
      (with-redefs [load/require!
                    (fn [symbol]
                      (when (= 'dvergr.intake.bash symbol)
                        (deliver entered true)
                        (wait-for-release! gate))
                      (require! symbol))
                    d/fork-room (fork-recorder fork! child finalizing)]
        (d/join room (llm/llm-agent {:id :agent :tools #{}
                                     :spec {:provider :test :model "fixture"}
                                     :run-turn-fn (fn [_ _] :complete)}))
        (let [task (d/imagine-conversation
                    room
                    (fn [fork]
                      (d/post! fork (d/message :human :agent "prepare"))
                      ;; This workflow constructor now runs at the native host
                      ;; boundary, not on the engine. Fail only after the child
                      ;; actually owns a noninterruptible preparation worker.
                      (when-not (= true (deref entered 30000 ::timeout))
                        (throw (ex-info "child did not enter setup" {})))
                      (sp/spin (throw (ex-info "synthetic workflow failure" {})))))]
          (binding [ec/*execution-context* (:ctx room)]
            (sp/spawn! task {:on-success #(deliver outcome {:value %})
                             :on-error #(deliver outcome {:error %})}))
          (is (= true (deref finalizing 30000 ::timeout)))
          ;; Do not lift the gate: exercise the actual existing five-second
          ;; physical drain deadline, rather than injecting a fake early error.
          (is (= :failed (:run/status (deref finished 15000 ::timeout))))
          (is (instance? Throwable (:error (deref outcome 3000 ::timeout))))
          (is (identical? (:ctx room) (:ctx @child)))
          (is (= ::d/context-still-owned
                 (try (d/close-room! room) nil
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
          (is (identical? room (binding [ec/*execution-context* (:ctx room)] (registry/lookup (:id room)))))
          (is (identical? @child (binding [ec/*execution-context* (:ctx room)] (registry/lookup (:id @child)))))
          (let [alive (promise)]
            (binding [ec/*execution-context* (:ctx room)]
              (sp/spawn! (sp/spin (deliver alive :alive))))
            (is (= :alive (deref alive 3000 ::timeout))))
          (let [leases (vals (get @(:meta @child) preparation/leases-key))]
            (is (seq leases))
            (.countDown gate)
            (doseq [lease leases] ((:join! lease) 30000)))
          (d/discard @child)
          (is (nil? (binding [ec/*execution-context* (:ctx room)] (registry/lookup (:id @child)))))
          (d/close-room! room)
          (is (= :closed (get-in @(:meta room) [:dvergr/fork-transfer-state :state])))))
      (finally
        (.countDown gate)
        (when @child (remove-watch (:meta @child) ::extent-finalizing))
        (run/unwatch-runs! watcher)
        (when-not (= :closed (get-in @(:meta room) [:dvergr/fork-transfer-state :state]))
          (when @child
            (doseq [lease (vals (get @(:meta @child) preparation/leases-key))]
              ((:join! lease) 30000))
            (d/discard @child))
          (d/close-room! room))))))
