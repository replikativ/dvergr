(ns dvergr.resource.authority-test
  "spindel's resource authority over dvergr's ledger: a scope's forks are
   granted, return and escrow real, conserved Kontor budgets."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [dvergr.agent.program :as program]
            [dvergr.agent.roster :as roster]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.chat.context :as chat-context]
            [dvergr.chat.schema :as chat-schema]
            [dvergr.model.providers :as providers]
            [dvergr.discourse :as d]
            [dvergr.resource :as resource]
            [dvergr.resource.authority :as authority]
            [dvergr.room.store :as room-store]
            [dvergr.room.store.datahike :as datahike-store]
            [kontor.governance :as kontor-governance]
            [kontor.resource :as kontor]
            [org.replikativ.spindel.effects.savepoint :as savepoint]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.world.scope :as world-scope]))

(defn- ledger-room []
  (let [id :authority-room
        cfg {:store {:backend :memory :id (random-uuid)} :keep-history? true :schema-flexibility :write}
        chat-id (random-uuid)]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)]
      (chat-schema/ensure-full-schema! conn)
      (dh/transact conn [(merge (chat-schema/create-chat-entity {:id chat-id :title "authority"})
                                {:room/slug (room-store/room-id->slug id) :room/type :internal})])
      (resource/install-connection! conn id chat-id)
      [(d/make-room {:id id :store (datahike-store/make conn)}) conn])))

(defn- cps [op]
  (let [p (promise)]
    (op #(deliver p [:ok %]) #(deliver p [:error %]))
    (let [[k v] (deref p 10000 [:error (ex-info "timed out" {})])]
      (if (= :ok k) v (throw v)))))

(defn- open-scope
  "A scope held open (an activity, as a savepoint session holds one) so it
   can fork more than once."
  [auth]
  (let [scope (world-scope/create {:authority auth :fork-opts {:systems :none}})]
    (swap! scope assoc ::lease (world-scope/begin-activity! scope :test))
    scope))

(defn- close-scope [scope]
  (world-scope/end-activity! scope (::lease @scope))
  (cps (world-scope/discard! scope)))

(defn- usd [room account]
  (get (room-store/-resource-balance (:store room) account) resource/microdollars 0M))

(deftest a-scope-fork-moves-ledger-budget
  (let [[room conn] (ledger-room)
        root (context/create-execution-context)
        room-wallet (kontor/account-ref (resource/room-wallet-id (:id room)))
        auth (authority/authority room)]
    (try
      (resource/mint! room {:id (random-uuid) :resources {resource/microdollars 10M}})
      (let [scope (open-scope auth)
            child (:child-ctx (cps #(world-scope/fork! scope root {:grant {resource/microdollars 3M}} %1 %2)))
            child-wallet (authority/wallet-of room child)]
        (testing "a grant moves budget from the source's wallet into the child's"
          (is (= 3M (usd room child-wallet)))
          (is (= 7M (usd room room-wallet))))
        (testing "a grant the source cannot afford fails the fork"
          (let [other (open-scope auth)]
            (is (thrown? Exception (cps #(world-scope/fork! other root {:grant {resource/microdollars 100M}} %1 %2))))
            (close-scope other))
          (is (= 7M (usd room room-wallet))))
        (testing "discarding the scope returns what its worlds have left"
          (close-scope scope)
          (is (= 10M (usd room room-wallet)))
          (is (= 0M (usd room child-wallet)))))
      (testing "escrow carries a world's budget out, and one claim brings it back"
        (let [scope (open-scope auth)
              leaving (:child-ctx (cps #(world-scope/fork! scope root {:grant {resource/microdollars 2M}} %1 %2)))
              arriving (:child-ctx (cps #(world-scope/fork! scope root nil %1 %2)))
              key (random-uuid)]
          (world-scope/escrow! auth leaving key)
          (is (= 0M (usd room (authority/wallet-of room leaving))) "nothing left to spend where it left")
          (world-scope/claim! auth key arriving)
          (is (= 2M (usd room (authority/wallet-of room arriving))))
          (is (thrown-with-msg? Exception #"claimed already" (world-scope/claim! auth key arriving)))
          (is (= 8M (usd room room-wallet)) "conserved: 8M in the Room, 2M with the arrived world")
          (close-scope scope)
          (is (= 10M (usd room room-wallet)))))
      (finally
        (context/stop-context! root)
        (kontor-governance/ungovern! conn)
        (d/close-room! room)))))

(deftest a-run-world-s-forks-spend-the-run-s-budget
  (let [[room conn] (ledger-room)
        team (roster/make-agent (roster/make-roster {:id :authority-team})
                                {:id :worker :tools #{}
                                 :model-policy {:provider :codex-subscription :model "codex-subscription-sol"}
                                 :program {:kind :llm :max-model-steps 2 :auto-compact? false}})
        seen (promise)]
    (try
      (resource/mint! room {:id (random-uuid) :resources {resource/microdollars 10M}})
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    chat-agent/run-agent-turn!
                    (fn [chat-ctx opts]
                      ;; inside the Run: fork its world through its savepoint session,
                      ;; granting 1M, then release the fork
                      (let [world (chat-context/selected-execution-context chat-ctx)
                            run-id (:run-id opts)
                            scope (:scope (savepoint/session world))
                            before (usd room (kontor/account-ref (resource/run-wallet-id run-id)))
                            child (:child-ctx (cps #(world-scope/fork! scope world {:grant {resource/microdollars 1M}} %1 %2)))
                            during (usd room (kontor/account-ref (resource/run-wallet-id run-id)))]
                        (cps (world-scope/release! scope child))
                        (deliver seen {:before before :during during
                                       :child (usd room (authority/wallet-of room child))
                                       :after (usd room (kontor/account-ref (resource/run-wallet-id run-id)))}))
                      (chat-context/add-message! chat-ctx {:role :assistant :content "done"})
                      :complete)]
        (let [handle (binding [ec/*execution-context* (:ctx room)]
                       (program/hire! room team :worker {:task "work" :resources {resource/microdollars 4M}}))]
          (binding [ec/*execution-context* (:ctx room)] @handle)
          (let [{:keys [before during child after]} (deref seen 10000 {})]
            (is (= 4M before))
            (is (= 3M during) "the fork's grant came out of the Run's wallet")
            (is (= 0M child) "and what it left went back when it was released")
            (is (= 4M after)))
          (is (= {resource/microdollars 10M} (resource/balance room)) "everything home once the Run ended")))
      (finally
        (kontor-governance/ungovern! conn)
        (d/close-room! room)))))
