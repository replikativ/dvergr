(ns dvergr.agent.budget-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as dh]
            [dvergr.agent.budget :as budget]
            [dvergr.agent.run :as run]
            [dvergr.agent.world :as world]
            [dvergr.chat.schema :as schema]
            [dvergr.discourse :as d]
            [dvergr.resource :as resource]
            [dvergr.resource.authority :as authority]
            [dvergr.room.store :as store]
            [dvergr.room.store.datahike :as datahike-store]
            [kontor.governance :as governance]
            [kontor.resource :as kontor]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.world.scope :as scope]))

(defn- cps [op]
  (let [done (promise)]
    (op #(deliver done [:ok %]) #(deliver done [:error %]))
    (let [[status value] (deref done 10000 [:error (ex-info "cleanup timed out" {})])]
      (if (= :ok status) value (throw value)))))

(defn- ledger-room []
  (let [id :budget-worlds
        cfg {:store {:backend :memory :id (random-uuid)}
             :keep-history? true :schema-flexibility :write}
        chat-id (random-uuid)]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)]
      (schema/ensure-full-schema! conn)
      (dh/transact conn [(merge (schema/create-chat-entity {:id chat-id :title "Budget worlds"})
                                {:room/slug (store/room-id->slug id) :room/type :internal})])
      (resource/install-connection! conn id chat-id)
      [(d/make-room {:id id :store (datahike-store/make conn)}) conn])))

(deftest outstanding-world-grants-are-not-spend
  (let [[room conn] (ledger-room)
        id (random-uuid)
        worlds (scope/create {:authority (authority/authority room)
                              :fork-opts {:systems :none}})
        lease (scope/begin-activity! worlds :test)
        run-world (atom nil)]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (resource/mint! room {:id (random-uuid) :resources {resource/microdollars 100M}})
        (run/start! room :worker (d/message :test :_runs "budget" nil) nil {:id id})
        (resource/allocate-run! room id nil {resource/microdollars 100M})
        (reset! run-world (world/open! room id :discard))
        (let [child (:child-ctx
                     (cps #(scope/fork! worlds (:ctx (:work @run-world))
                                        {:grant {resource/microdollars 30M}} %1 %2)))]
          (is (= {} (budget/spend-of room id)) "a live world holds 30, but consumed nothing")
          (store/-transfer-resources! (:store room)
                                      {:id (random-uuid) :kind :consume
                                       :source (authority/wallet-of room child)
                                       :destination kontor/sink-account
                                       :resources {resource/microdollars 7M}})
          (is (= {resource/microdollars 7M} (budget/spend-of room id)))
          (let [auth (:authority @worlds)
                copies (cps #(scope/copy! worlds child 2 {:split? true} %1 %2))
                leaving (:child-ctx (first copies))
                key (random-uuid)
                arriving (:child-ctx (cps #(scope/fork! worlds (:ctx (:work @run-world)) nil %1 %2)))]
            (is (= {resource/microdollars 7M} (budget/spend-of room id))
                "copy grants remain available, not new spend")
            (scope/escrow! auth leaving key)
            (is (= {resource/microdollars 7M} (budget/spend-of room id)))
            (scope/claim! auth key arriving)
            (is (= {resource/microdollars 7M} (budget/spend-of room id)))
            (store/-transfer-resources! (:store room)
                                        {:id (random-uuid) :kind :consume
                                         :source (authority/wallet-of room arriving)
                                         :destination kontor/sink-account
                                         :resources {resource/microdollars 3M}})
            (is (= {resource/microdollars 10M} (budget/spend-of room id))))
          (scope/end-activity! worlds lease)
          (cps (scope/discard! worlds))
          (is (= {resource/microdollars 10M} (budget/spend-of room id))))
        (world/settle! @run-world :failed)
        (run/finish! id :failed {:reason :test-cleanup}))
      (finally
        (scope/end-activity! worlds lease)
        (cps (scope/discard! worlds))
        (when @run-world (world/settle! @run-world :failed))
        (when (some #(= id (:run/id %)) (run/active-runs (:id room)))
          (run/finish! id :failed {:reason :test-cleanup}))
        (governance/ungovern! conn)
        (d/close-room! room)
        (dh/release conn)))))

(deftest actual-reservations-holds-and-charges-count-once
  (let [[room conn] (ledger-room)
        id (random-uuid)
        run-world (atom nil)]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (resource/install-unit! room {:symbol resource/model-dispatches :precision 0})
        (resource/mint! room {:id (random-uuid) :resources {resource/microdollars 100M
                                                            resource/model-dispatches 10M}})
        (run/start! room :worker (d/message :test :_runs "reserve" nil) nil {:id id})
        (resource/allocate-run! room id nil {resource/microdollars 100M resource/model-dispatches 10M})
        (reset! run-world (world/open! room id :discard))
        (binding [ec/*execution-context* (:ctx (:work @run-world))
                  resource/*model-scope* (resource/model-scope room id)
                  resource/*spend-wallet* (resource/spending (resource/spend-wallet room id) [:reservation-test])
                  resource/*dispatch-bound* 30M
                  resource/*dispatches* (atom [])]
          (resource/reserve-dispatch!)
          (is (= {resource/microdollars 30M} (budget/spend-of room id)))
          (resource/dispatch-status! 200)
          (resource/settle-dispatches! 7M)
          (is (= {resource/microdollars 7M} (budget/spend-of room id)))
          (resource/charge-spend! 7M)
          (is (= {resource/microdollars 7M} (budget/spend-of room id))))
        (world/settle! @run-world :failed)
        (run/finish! id :failed {:reason :test-cleanup}))
      (finally
        (when @run-world (world/settle! @run-world :failed))
        (when (some #(= id (:run/id %)) (run/active-runs (:id room)))
          (run/finish! id :failed {:reason :test-cleanup}))
        (governance/ungovern! conn)
        (d/close-room! room)
        (dh/release conn)))))

(deftest unsupported-store-cannot-fake-a-consistent-ledger-view
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"immutable attribution view"
                        (budget/spend-of {:id :no-view :store {}} (random-uuid)))))

(deftest unrelated-incomplete-history-does-not-poison-a-new-run
  (let [fresh (random-uuid) legacy (random-uuid)
        view {:accounts {fresh {:kind :wallet :purpose :run :run-id fresh :balance {}}
                         legacy {:kind :wallet :balance {}}}
              :transfers []}
        room {:id :partial-history
              :store (reify store/PResourceReadView (-resource-read-view [_] view))}]
    (with-redefs [run/run (fn [_ id] (when (#{fresh legacy} id) {:run/id id}))
                  run/runs (fn [& _] [{:run/id fresh :run/role :fresh}
                                      {:run/id legacy :run/role :legacy}])]
      (is (= {} (budget/spend-of room fresh)))
      (is (= {} (budget/spend-by-role room fresh)))
      (is (thrown? clojure.lang.ExceptionInfo (budget/spend-of room legacy)))
      (is (thrown? clojure.lang.ExceptionInfo (budget/spend-by-role room legacy))))))

(deftest spend-is-room-scoped-on-a-shared-ledger
  (let [[room conn] (ledger-room)
        other-id :budget-other
        other-chat (random-uuid)
        id (random-uuid)
        other (atom nil)]
    (try
      (dh/transact conn [(merge (schema/create-chat-entity {:id other-chat :title "Other budget"})
                                {:room/slug (store/room-id->slug other-id) :room/type :internal})])
      (resource/install-connection! conn other-id other-chat)
      (reset! other (d/make-room {:id other-id :store (datahike-store/make conn)}))
      (binding [ec/*execution-context* (:ctx @other)]
        (resource/mint! @other {:id (random-uuid) :resources {resource/microdollars 100M}})
        (run/start! @other :worker (d/message :test :_runs "other room" nil) nil {:id id})
        (resource/allocate-run! @other id nil {resource/microdollars 100M})
        (store/-transfer-resources! (:store @other)
                                    {:id (random-uuid) :kind :consume
                                     :source (kontor/account-ref id) :destination kontor/sink-account
                                     :resources {resource/microdollars 7M}})
        (is (= {resource/microdollars 7M} (budget/spend-of @other id)))
        (is (= {} (budget/spend-of room id)))
        (is (= {} (budget/spend-by-role room id))))
      (finally
        (when (some #(= id (:run/id %)) (run/active-runs other-id))
          (run/finish! id :failed {:reason :test-cleanup}))
        (governance/ungovern! conn)
        (when @other (d/close-room! @other))
        (d/close-room! room)
        (dh/release conn)))))
