(ns dvergr.agent.room-context-test
  "Tests for the per-[room,agent] working ChatContext (design D): caching,
   explicit attention admission, id-dedup, and durable reconstruction."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [dvergr.agent.room-context :as rc]
            [dvergr.agent.run :as run]
            [dvergr.agent.turn :as turn]
            [dvergr.discourse :as d]
            [dvergr.room.store.memory :as mem]
            [dvergr.room.store :as rstore]
            [dvergr.room.store.datahike :as store-dh]
            [dvergr.room.registry :as registry]
            [dvergr.sandbox :as sandbox]
            [dvergr.chat.context :as cctx]
            [dvergr.chat.schema :as schema]
            [datahike.api :as dh]
            [org.replikativ.spindel.engine.component :as component]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]))

(defn- ledger-count [conn]
  (or (dh/q '[:find (count ?e) . :where [?e :ledger/id _]] @conn) 0))

(defn- roles+contents [chat-ctx]
  (binding [ec/*execution-context* (:spindel-ctx chat-ctx)]
    (mapv (juxt :message/role :message/content) (cctx/get-messages chat-ctx))))

(defn- non-system-contents [chat-ctx]
  (->> (roles+contents chat-ctx)
       (remove #(= :system (first %)))
       (map second)))

(deftest ensure-ctx-caches-and-seeds-from-store
  (let [c (ctx/create-execution-context)]
    (binding [ec/*execution-context* c]
      (let [room (d/make-room {:id :rc-seed :ctx c :store (mem/make)})]
        (d/post! room (d/message :alice :var "earlier message" nil {:role :user}))
        (Thread/sleep 150)
        (let [cc1 (rc/ensure-ctx! room :var {:budget-dollars 1.0})
              cc2 (rc/ensure-ctx! room :var {:budget-dollars 1.0})]
          (try
            (is (identical? cc1 cc2) "second call returns the cached ctx (no rebuild)")
            (is (some #(str/includes? % "earlier message") (non-system-contents cc1))
                "seeded the conversation from the room store")
            (finally (rc/drop-ctx! :rc-seed :var))))))))

(deftest concurrent-first-context-creation-is-singleton
  (testing "one Room fence covers component allocation and cache publication"
    (let [c (ctx/create-execution-context)
          room (d/make-room {:id :rc-concurrent-first :ctx c :store (mem/make)})
          original turn/new-working-ctx
          constructor-entered (promise)
          second-started (promise)
          release-constructor (promise)
          calls (atom 0)]
      (try
        (with-redefs [turn/new-working-ctx
                      (fn [opts]
                        (swap! calls inc)
                        (deliver constructor-entered true)
                        @release-constructor
                        (original opts))]
          (let [first-result (future (rc/ensure-ctx! room :var
                                                     {:budget-dollars 1.0}))]
            (is (true? (deref constructor-entered 3000 ::timeout)))
            (let [second-result
                  (future
                    (deliver second-started true)
                    (rc/ensure-ctx! room :var {:budget-dollars 1.0}))]
              (is (true? (deref second-started 3000 ::timeout)))
              (deliver release-constructor true)
              (let [first-ctx (deref first-result 10000 ::timeout)
                    second-ctx (deref second-result 10000 ::timeout)]
                (is (not= ::timeout first-ctx))
                (is (identical? first-ctx second-ctx))
                (is (= 1 @calls))
                (is (= 1 (count (binding [ec/*execution-context* c]
                                  (component/registered)))))))))
        (finally
          (deliver release-constructor true)
          (rc/drop-ctx! :rc-concurrent-first :var)
          (ctx/close-context! c))))))

(deftest failed-hydration-is-unpublished-and-retryable
  (let [c (ctx/create-execution-context)
        room (d/make-room {:id :rc-hydration-retry :ctx c :store (mem/make)})
        original d/messages
        calls (atom 0)]
    (try
      (with-redefs [d/messages
                    (fn [& args]
                      (if (= 1 (swap! calls inc))
                        (throw (ex-info "injected hydration failure" {}))
                        (apply original args)))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"injected hydration failure"
                              (rc/ensure-ctx! room :var {:budget-dollars 1.0})))
        (is (nil? (rc/lookup (:id room) :var)))
        (is (empty? (binding [ec/*execution-context* c]
                      (component/registered))))
        (let [retried (rc/ensure-ctx! room :var {:budget-dollars 1.0})]
          (is (some? retried))
          (is (= 1 (count (binding [ec/*execution-context* c]
                            (component/registered)))))))
      (finally
        (d/close-room! room)))))

(deftest inbound-admission-waits-for-context-publication
  (let [c (ctx/create-execution-context)
        room (d/make-room {:id :rc-admission-during-hydration
                           :ctx c :store (mem/make)})
        original d/messages
        hydration-entered (promise)
        release-hydration (promise)
        msg-id (random-uuid)]
    (try
      (with-redefs [d/messages
                    (fn [& args]
                      (deliver hydration-entered true)
                      @release-hydration
                      (apply original args))]
        (let [ensure-result (future (rc/ensure-ctx! room :var
                                                    {:budget-dollars 1.0}))]
          (is (true? (deref hydration-entered 10000 ::timeout)))
          (let [append-result
                (future (rc/append-inbound! room :var msg-id :user
                                            "arrived during hydration"
                                            "Alice" nil))]
            (deliver release-hydration true)
            (let [working (deref ensure-result 10000 ::timeout)]
              (is (true? (deref append-result 10000 ::timeout)))
              (is (some #(str/includes? % "arrived during hydration")
                        (non-system-contents working)))))))
      (finally
        (deliver release-hydration true)
        (d/close-room! room)))))

(deftest working-context-construction-releases-on-rebind-failure
  (let [c (ctx/create-execution-context)]
    (try
      (with-redefs [sandbox/setup-agent-namespaces!
                    (fn [& _]
                      (throw (ex-info "injected namespace failure" {})))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"injected namespace failure"
                              (turn/new-working-ctx
                               {:execution-ctx c :title "failing"})))
        (is (empty? (binding [ec/*execution-context* c]
                      (component/registered)))))
      (finally
        (ctx/close-context! c)))))

(deftest unregister-fences-context-creation-and-stale-access
  (let [c (ctx/create-execution-context)
        room (d/make-room {:id :rc-unregister-race :ctx c :store (mem/make)})
        original turn/new-working-ctx
        constructor-entered (promise)
        release-constructor (promise)]
    (try
      (with-redefs [turn/new-working-ctx
                    (fn [opts]
                      (deliver constructor-entered true)
                      @release-constructor
                      (original opts))]
        (let [ensure-result (future (rc/ensure-ctx! room :var
                                                    {:budget-dollars 1.0}))]
          (is (true? (deref constructor-entered 3000 ::timeout)))
          (let [unregister-result
                (future
                  (binding [ec/*execution-context* c]
                    (registry/unregister! (:id room))))]
            (deliver release-constructor true)
            (is (not= ::timeout (deref ensure-result 10000 ::timeout)))
            (is (nil? (deref unregister-result 10000 ::timeout)))
            (is (nil? (binding [ec/*execution-context* c]
                        (registry/lookup (:id room)))))
            (is (nil? (rc/lookup (:id room) :var)))
            (is (empty? (binding [ec/*execution-context* c]
                          (component/registered))))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"closed Room incarnation"
                                  (rc/ensure-ctx! room :var
                                                  {:budget-dollars 1.0}))))))
      (finally
        (deliver release-constructor true)
        (ctx/close-context! c)))))

(deftest resource-cleanup-failure-still-unregisters-component
  (let [c (ctx/create-execution-context)
        working (turn/new-working-ctx {:execution-ctx c :title "cleanup"})]
    (try
      (is (= 1 (count (binding [ec/*execution-context* c]
                        (component/registered)))))
      (with-redefs [sandbox/release-agent-resources!
                    (fn [& _] (throw (java.io.IOException. "injected cleanup failure")))]
        (is (thrown-with-msg? java.io.IOException
                              #"injected cleanup failure"
                              (cctx/release-sci-in! working c))))
      (is (empty? (binding [ec/*execution-context* c]
                    (component/registered))))
      (finally
        (cctx/release-sci-in! working c)
        (ctx/close-context! c)))))

(deftest room-observation-does-not-bypass-attention
  (let [c (ctx/create-execution-context)]
    (binding [ec/*execution-context* c]
      (let [room (d/make-room {:id :rc-no-eager-fold :ctx c :store (mem/make)})
            cc   (rc/ensure-ctx! room :var {:budget-dollars 1.0})]
        (try
          (d/post! room (d/message :alice :var "hi var" nil {:role :user}))
          (let [contents (non-system-contents cc)
                has? (fn [s] (some #(str/includes? % s) contents))]
            (is (not (has? "hi var"))
                "durable Room visibility alone does not admit provider input")
            (is (true? (rc/append-inbound! :rc-no-eager-fold :var
                                           (random-uuid) :user "admitted"
                                           "Alice" nil)))
            (is (some #(str/includes? % "admitted") (non-system-contents cc))))
          (finally (rc/drop-ctx! :rc-no-eager-fold :var)))))))

(deftest append-inbound-dedups-by-id
  (let [c (ctx/create-execution-context)]
    (binding [ec/*execution-context* c]
      (let [room (d/make-room {:id :rc-dedup :ctx c :store (mem/make)})
            cc   (rc/ensure-ctx! room :var {:budget-dollars 1.0})
            id   (random-uuid)]
        (try
          (Thread/sleep 50)
          (is (true? (rc/append-inbound! :rc-dedup :var id :user "once" nil nil)) "first append")
          (is (nil? (rc/append-inbound! :rc-dedup :var id :user "again" nil nil)) "same id → no-op")
          (is (= 1 (count (filter #(= "once" %) (non-system-contents cc))))
              "appended exactly once despite two calls with the same id")
          (finally (rc/drop-ctx! :rc-dedup :var)))))))

(deftest forked-working-context-owns-child-persistence
  (let [parent-ctx (ctx/create-execution-context)
        child-ctx* (atom nil)
        parent-conn (schema/create-chat-db!
                     {:store {:backend :memory :id (random-uuid)}})
        child-conn (schema/create-chat-db!
                    {:store {:backend :memory :id (random-uuid)}})
        parent (d/make-room {:id :rc-db-parent
                             :ctx parent-ctx
                             :store (store-dh/make parent-conn)})]
    (try
      (let [working (rc/ensure-ctx! parent :var {:budget-dollars 1.0})]
        (let [child-ctx (ctx/fork-context parent-ctx :mode :frozen)
              _ (reset! child-ctx* child-ctx)
              child (d/make-room {:id :rc-db-child
                                  :ctx child-ctx
                                  :store (store-dh/make child-conn)})]
          (dh/transact child-conn
                       [(schema/create-chat-entity
                         {:id (:chat-id working) :title (:title working)
                          :budget 1000000})])
          (let [projected (rc/fork-ctx! parent child :var)]
            (is (identical? child-conn (:db-conn projected)))
            (is (not (identical? parent-conn (:db-conn projected))))
            (cctx/account-usage! projected :input-tokens 1
                                 :model "claude-sonnet-4-5")
            (is (= 0 (ledger-count parent-conn)))
            (is (= 1 (ledger-count child-conn))))))
      (finally
        (rc/drop-ctx! :rc-db-child :var)
        (rc/drop-ctx! :rc-db-parent :var)
        (dh/release child-conn)
        (dh/release parent-conn)
        (when-let [child-ctx @child-ctx*]
          (ctx/close-context! child-ctx))
        (ctx/close-context! parent-ctx)))))

(deftest durable-attention-rebuilds-provider-projection
  (testing "Run triggers and :include decisions enter provider input while
            durable :remember facts remain outside it"
    (let [c (ctx/create-execution-context)]
      (binding [ec/*execution-context* c]
        (let [room (d/make-room {:id :rc-cons :ctx c :store (mem/make)})
              trigger (d/message :alice :var "trigger" nil {:role :user})
              remembered (d/message :bob :var "remember only" nil {:role :user})]
          (try
            (d/post! room trigger)
            (let [cc (rc/ensure-ctx! room :var {:budget-dollars 1.0})
                  run-ref (run/start! room :var trigger cc)]
              (run/finish! (:run/id run-ref) :completed)
              ;; Arrives after the legacy baseline was materialized, as an
              ;; active-run observation would in production.
              (d/post! room remembered)
              (rstore/-store-attention!
               (:store room) :rc-cons
               {:attention/id (random-uuid)
                :attention/participant :var
                :attention/message-id (:id remembered)
                :attention/memory :remember
                :attention/activation :none
                :attention/control :continue
                :attention/at :next-safe-boundary
                :attention/priority 0
                :attention/status :ready
                :attention/created-at (java.util.Date.)})
              (rc/drop-ctx! :rc-cons :var))
            (let [restored (rc/ensure-ctx! room :var {:budget-dollars 1.0})
                  contents (non-system-contents restored)]
              (is (some #(str/includes? % "trigger") contents))
              (is (not-any? #(str/includes? % "remember only") contents)))
            (finally (rc/drop-ctx! :rc-cons :var))))))))

(deftest legacy-runs-materialize-a-provider-baseline
  (testing "pre-attention rooms keep non-trigger history even when Runs exist"
    (let [c (ctx/create-execution-context)]
      (binding [ec/*execution-context* c]
        (let [room (d/make-room {:id :rc-legacy-cutover :ctx c :store (mem/make)})
              trigger (d/message :alice :var "old trigger" nil {:role :user})
              context (d/message :bob :var "old supporting context" nil {:role :user})
              run-ctx (turn/new-working-ctx {:execution-ctx c
                                             :title "legacy run"
                                             :budget-dollars 1.0})]
          (try
            (d/post! room trigger)
            (d/post! room context)
            ;; Simulate a room persisted by the Run release before attention
            ;; projections existed; do not call ensure-ctx! yet.
            (let [run-ref (run/start! room :var trigger run-ctx)]
              (run/finish! (:run/id run-ref) :completed))
            ;; Simulate a crash after the first write of baseline migration.
            ;; A non-empty projection without the final marker must be retried.
            (let [decision-id
                  (rstore/attention-id (:id room) :var (:id trigger) nil
                                       :legacy-baseline-decision)]
              (rstore/-store-attention!
               (:store room) (:id room)
               {:attention/id decision-id
                :attention/participant :var
                :attention/message-id (:id trigger)
                :attention/memory :include
                :attention/activation :none
                :attention/control :continue
                :attention/at :now
                :attention/priority 0.0
                :attention/reason :migration/provider-baseline
                :attention/status :ready
                :attention/created-at (java.util.Date. (long (:ts trigger)))}))
            (let [restored (rc/ensure-ctx! room :var {:budget-dollars 1.0})
                  contents (non-system-contents restored)
                  facts (rstore/-list-attention (:store room) (:id room)
                                                {:participant :var})]
              (is (some #(str/includes? % "old trigger") contents))
              (is (some #(str/includes? % "old supporting context") contents))
              (is (= 2 (count (set (map :attention/message-id
                                        (filter #(= :applied (:attention/status %))
                                                facts)))))))
            (finally (rc/drop-ctx! :rc-legacy-cutover :var))))))))

(deftest baseline-marker-lookup-is-not-history-bounded
  (testing "an aged migration marker cannot retroactively admit ignored speech"
    (let [c (ctx/create-execution-context)]
      (binding [ec/*execution-context* c]
        (let [room-id :rc-aged-baseline-marker
              room (d/make-room {:id room-id :ctx c :store (mem/make)})
              original (d/message :alice :var "legacy baseline" nil {:role :user})
              ignored (d/message :bob :var "must stay ignored" nil {:role :user})]
          (try
            (d/post! room original)
            (rc/ensure-ctx! room :var {:budget-dollars 1.0})
            (rc/drop-ctx! room-id :var)
            (d/post! room ignored)
            (rstore/-store-attention!
             (:store room) room-id
             {:attention/id (random-uuid)
              :attention/participant :var
              :attention/message-id (:id ignored)
              :attention/memory :ignore
              :attention/activation :none
              :attention/control :continue
              :attention/at :now
              :attention/status :ready
              :attention/created-at (java.util.Date.)})
            ;; Age the deterministic marker beyond the ordinary projection window.
            (dotimes [_ 1001]
              (rstore/-store-attention!
               (:store room) room-id
               {:attention/id (random-uuid)
                :attention/participant :var
                :attention/message-id (random-uuid)
                :attention/memory :remember
                :attention/status :ready
                :attention/created-at (java.util.Date.)}))
            (let [restored (rc/ensure-ctx! room :var {:budget-dollars 1.0})
                  contents (non-system-contents restored)
                  retroactive-applied-id
                  (rstore/attention-id room-id :var (:id ignored) nil
                                       :legacy-baseline-applied)]
              (is (not-any? #(str/includes? % "must stay ignored") contents))
              (is (empty? (rstore/-list-attention
                           (:store room) room-id {:id retroactive-applied-id}))))
            (finally (rc/drop-ctx! room-id :var))))))))
