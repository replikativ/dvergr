(ns dvergr.resource.authority-test
  "spindel's resource authority over dvergr's ledger: a scope's forks are
   granted, return and escrow real, conserved Kontor budgets."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [dvergr.agent.program :as program]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.agent.world :as run-world]
            [dvergr.chat.agent :as chat-agent]
            [dvergr.chat.context :as chat-context]
            [dvergr.chat.schema :as chat-schema]
            [dvergr.model.providers :as providers]
            [dvergr.model.gateway :as gateway]
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

(deftest repeated-host-installation-keeps-both-writer-governors
  (let [[room conn] (ledger-room)
        chat-id (dh/q '[:find ?id . :where [_ :chat/id ?id]] @conn)
        wallet (kontor/account-ref (resource/room-wallet-id (:id room)))]
    (try
      (dotimes [_ 2]
        (is (identical? conn (resource/install-connection! conn (:id room) chat-id)))
        (testing "Kontor still rejects a raw overdraft"
          (let [tx (kontor/transfer-tx-data
                    {:id (random-uuid) :kind :consume
                     :source wallet :destination kontor/sink-account
                     :resources {resource/microdollars 1M}
                     :effective-date (java.util.Date.)})]
            (is (thrown? Exception (dh/transact conn tx)))
            (is (= {} (resource/balance room wallet)))))
        (testing "reinstalling Kontor did not replace Attempt protection"
          (let [id (random-uuid)]
            (is (thrown? Exception (dh/transact conn [{:attempt/id id}])))
            (is (nil? (dh/q '[:find ?e . :in $ ?id :where [?e :attempt/id ?id]] @conn id))))))
      (finally
        (kontor-governance/ungovern! conn)
        (d/close-room! room)))))

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

(deftest an-unfunded-descendant-cannot-spend-or-return-its-ancestors-wallet
  (let [[room conn] (ledger-room)
        root (context/create-execution-context)
        auth (authority/authority room)
        scope (open-scope auth)]
    (try
      (resource/mint! room {:id (random-uuid) :resources {resource/microdollars 10M}})
      (let [unfunded (:child-ctx (cps #(world-scope/fork! scope root nil %1 %2)))]
        (binding [ec/*execution-context* unfunded
                  resource/*spend-wallet* (resource/spending (resource/room-spend-wallet room) [:room-child])]
          (is (thrown? clojure.lang.ExceptionInfo (resource/charge-spend! 1M))))
        (is (thrown? clojure.lang.ExceptionInfo (authority/wallet-of room unfunded)))
        (is (= 10M (get (resource/balance room) resource/microdollars))))
      (let [parent (:child-ctx (cps #(world-scope/fork! scope root {:grant {resource/microdollars 4M}} %1 %2)))
            account (authority/wallet-of room parent)
            nested (open-scope auth)]
        (try
          (let [child (:child-ctx (cps #(world-scope/fork! nested parent nil %1 %2)))]
            (binding [ec/*execution-context* child
                      resource/*spend-wallet* (resource/spending (resource/room-spend-wallet room) [:unfunded])]
              (is (thrown? clojure.lang.ExceptionInfo (resource/charge-spend! 1M))
                  "an unfunded child must not fall back to a bound ancestor payer"))
            (is (= 4M (usd room account)))
            (close-scope nested)
            (is (= 4M (usd room account)) "child cleanup cannot refund its parent's live grant"))
          (finally (close-scope nested))))
      (finally
        (close-scope scope)
        (context/stop-context! root)
        (kontor-governance/ungovern! conn)
        (d/close-room! room)))))

(deftest unfunded-copies-do-not-inherit-a-live-wallet
  (let [[room conn] (ledger-room)
        root (context/create-execution-context)
        auth (authority/authority room)
        scope (open-scope auth)]
    (try
      (resource/mint! room {:id (random-uuid) :resources {resource/microdollars 10M}})
      (let [parent (:child-ctx (cps #(world-scope/fork! scope root {:grant {resource/microdollars 4M}} %1 %2)))
            account (authority/wallet-of room parent)
            copies (cps #(world-scope/copy! scope parent 2 {:split? false} %1 %2))]
        (doseq [{ctx :child-ctx} copies]
          (binding [ec/*execution-context* ctx]
            (is (thrown? clojure.lang.ExceptionInfo (resource/charge-spend! 1M))))
          (cps (world-scope/release! scope ctx)))
        (is (= 0M (usd room account)) "the retired source returns its own remainder after all copies settle")
        (is (= 10M (get (resource/balance room) resource/microdollars))))
      (finally
        (close-scope scope)
        (context/stop-context! root)
        (kontor-governance/ungovern! conn)
        (d/close-room! room)))))

(deftest a-run-inside-a-funded-world-selects-its-own-payer
  (let [[room conn] (ledger-room)
        auth (authority/authority room)
        run-id (random-uuid)]
    (try
      (resource/install-unit! room {:symbol resource/model-dispatches :name "admission" :precision 0})
      (resource/mint! room {:id (random-uuid) :resources {resource/microdollars 20M resource/model-dispatches 10M}})
      (let [parent (d/fork-room room {:isolation :ctx :clone-participants? false})]
        (try
          (world-scope/grant! auth (:ctx room) (:ctx parent)
                              {resource/microdollars 5M resource/model-dispatches 2M})
          (let [trigger (d/message :test :_runs "payer" nil {:role :user})]
            (d/post! room trigger)
            (run/start! room :test trigger nil {:id run-id}))
          (resource/allocate-run! room run-id nil {resource/microdollars 8M resource/model-dispatches 3M})
          (let [world (run-world/open! parent run-id :discard room)
                parent-account (authority/wallet-of room (:ctx parent))]
            (try
              (binding [ec/*execution-context* (:ctx (:work world))
                        resource/*spend-wallet* (resource/spending (resource/spend-wallet room run-id) [:nested run-id])
                        resource/*model-scope* (resource/model-scope room run-id)]
                (is (= 2M (:charged (resource/charge-spend! 2M))))
                (resource/admit-model-dispatch!))
              (is (= {resource/microdollars 6M resource/model-dispatches 2M} (resource/run-balance room run-id)))
              (is (= {resource/microdollars 5M resource/model-dispatches 2M} (resource/balance room parent-account)))
              (finally (run-world/settle! world :cancelled))))
          (finally
            (world-scope/return! auth (:ctx parent))
            (d/discard parent))))
      (finally
        (when (some #(= run-id (:run/id %)) (run/active-runs))
          (run/finish! run-id :completed))
        (kontor-governance/ungovern! conn)
        (d/close-room! room)))))

(deftest admission-only-run-does-not-fall-back-to-a-foreign-model-scope
  (let [[room conn] (ledger-room)
        parent (random-uuid)
        child (random-uuid)]
    (try
      (resource/install-unit! room {:symbol resource/model-dispatches :name "admission" :precision 0})
      (resource/mint! room {:id (random-uuid) :resources {resource/model-dispatches 3M}})
      (let [trigger (d/message :test :_runs "admission-only" nil {:role :user})]
        (d/post! room trigger)
        (doseq [id [parent child]] (run/start! room :test trigger nil {:id id})))
      (resource/allocate-run! room parent nil {resource/model-dispatches 2M})
      (resource/allocate-run! room child nil {resource/model-dispatches 1M})
      (let [world (run-world/open! room child :discard)]
        (try
          (binding [ec/*execution-context* (:ctx (:work world))
                    resource/*model-scope* (resource/model-scope room parent)]
            (resource/admit-model-dispatch!)
            (is (nil? (resource/charge-spend! 1M)) "no monetary coordinate still means recording-only"))
          (is (= {resource/model-dispatches 2M} (resource/run-balance room parent)))
          (is (= {} (resource/run-balance room child)))
          (binding [ec/*execution-context* (:ctx (:work world))
                    resource/*model-scope* nil]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"host model-call scope"
                                  (resource/admit-model-dispatch!)))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"host model-call scope"
                                  (resource/reserve-dispatch!)))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"host model-call scope"
                                  (gateway/request! {}))))
          (finally (run-world/settle! world :cancelled))))
      (finally
        (doseq [id [child parent]] (run/finish! id :completed))
        (kontor-governance/ungovern! conn)
        (d/close-room! room)))))

(deftest legacy-money-only-runs-retain-native-gateway-accounting
  (let [[room conn] (ledger-room)
        id (random-uuid)
        called (atom 0)]
    (try
      (resource/mint! room {:id (random-uuid) :resources {resource/microdollars 10M}})
      (let [trigger (d/message :test :_runs "legacy-money-only" nil {:role :user})]
        (d/post! room trigger)
        (run/start! room :test trigger nil {:id id}))
      (resource/allocate-run! room id nil {resource/microdollars 10M})
      (let [world (run-world/open! room id :discard)]
        (try
          (binding [ec/*execution-context* (:ctx (:work world))
                    resource/*model-scope* (resource/model-scope room id)
                    resource/*spend-wallet* (resource/spending (resource/spend-wallet room id) [:legacy id])
                    gateway/*request-fn* (fn [_] (swap! called inc) {:status 200})]
            (is (nil? resource/*model-scope*))
            (is (= 200 (:status (gateway/request!
                                 {:url "https://model.test/responses" :method :post
                                  :credentials (gateway/static-credentials :test {} #{"https://model.test"})}))))
            (is (= 1M (:charged (resource/charge-spend! 1M)))))
          (is (= 1 @called))
          (is (= {resource/microdollars 9M} (resource/run-balance room id)))
          (finally (run-world/settle! world :cancelled))))
      (finally
        (run/finish! id :completed)
        (kontor-governance/ungovern! conn)
        (d/close-room! room)))))

(deftest escrow-arrival-never-overwrites-an-owned-wallet
  (doseq [empty? [true false]]
    (let [[room conn] (ledger-room)
          root (context/create-execution-context)
          auth (authority/authority room)
          scope (open-scope auth)
          key (random-uuid)]
      (try
        (resource/mint! room {:id (random-uuid) :resources {resource/microdollars 10M}})
        (let [source (:child-ctx (cps #(world-scope/fork! scope root {:grant {resource/microdollars 2M}} %1 %2)))
              arrival (:child-ctx (cps #(world-scope/fork! scope root {:grant {resource/microdollars 3M}} %1 %2)))
              wallet (authority/wallet-of room arrival)]
          (when empty? (binding [ec/*execution-context* source] (resource/charge-spend! 2M)))
          (world-scope/escrow! auth source key)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unfunded world"
                                (world-scope/claim! auth key arrival)))
          (is (= wallet (authority/wallet-of room arrival)))
          (is (= 3M (usd room wallet)))
          ;; The rejected claim did not consume the escrow either.
          (let [unfunded (:child-ctx (cps #(world-scope/fork! scope root nil %1 %2)))]
            (world-scope/claim! auth key unfunded))
          (close-scope scope)
          (is (= (if empty? 8M 10M) (get (resource/balance room) resource/microdollars))))
        (finally
          (close-scope scope)
          (context/stop-context! root)
          (kontor-governance/ungovern! conn)
          (d/close-room! room))))))

(deftest committed-grants-with-lost-acknowledgements-are-reconciled
  (doseq [copy? [false true]]
    (let [[room conn] (ledger-room)
          root (context/create-execution-context)
          grants (atom 0)
          lost? (atom false)
          armed? (atom false)
          st (:store room)
          ;; Intercept the actual protocol implementation. with-redefs on the
          ;; protocol Var does not intercept compiled interface dispatch.
          fault-store (reify room-store/PResourceStore
                        (-open-resource-wallet! [_ spec] (room-store/-open-resource-wallet! st spec))
                        (-install-resource-unit! [_ spec] (room-store/-install-resource-unit! st spec))
                        (-allocate-resource-wallet! [_ wallet transfer]
                          (room-store/-allocate-resource-wallet! st wallet transfer))
                        (-resource-balance [_ account] (room-store/-resource-balance st account))
                        (-resource-receipt [_ id] (room-store/-resource-receipt st id))
                        (-transfer-resources! [_ command]
                          (let [result (room-store/-transfer-resources! st command)]
                            (when (and @armed? (= :grant (:kind command))
                                       (= (if copy? 2 1) (swap! grants inc))
                                       (compare-and-set! lost? false true))
                              (throw (ex-info "committed grant acknowledgement lost" {})))
                            result)))
          auth (authority/authority (assoc room :store fault-store))
          scope (open-scope auth)]
      (try
        (resource/mint! room {:id (random-uuid) :resources {resource/microdollars 10M}})
        (let [parent (when copy?
                       (:child-ctx (cps #(world-scope/fork! scope root {:grant {resource/microdollars 4M}} %1 %2))))
              children
              (do
                (reset! armed? true)
                (if copy?
                  (cps #(world-scope/copy! scope parent 2 {:split? true} %1 %2))
                  [(cps #(world-scope/fork! scope root {:grant {resource/microdollars 4M}} %1 %2))]))]
          (is @lost?)
          (is (= (if copy? 3 2) @grants) "the exact committed command was replayed once")
          (is (= (if copy? [2M 2M] [4M])
                 (mapv #(usd room (authority/wallet-of room (:child-ctx %))) children)))
          (close-scope scope)
          (is (= 10M (get (resource/balance room) resource/microdollars))))
        (finally
          (close-scope scope)
          (context/stop-context! root)
          (kontor-governance/ungovern! conn)
          (d/close-room! room))))))

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

(deftest model-spend-is-paid-from-the-run-s-wallet
  (let [[room conn] (ledger-room)
        team (roster/make-agent (roster/make-roster {:id :spend-team})
                                {:id :worker :tools #{}
                                 :model-policy {:provider :codex-subscription :model "codex-subscription-sol"}
                                 :program {:kind :llm :max-model-steps 4 :auto-compact? false :budget-dollars 5.0}})
        costs (atom [400000 700000])
        wallets (atom [])]
    (try
      (resource/mint! room {:id (random-uuid) :resources {resource/microdollars 10000000M}})
      (with-redefs [providers/ensure-initialized! (constantly nil)
                    ;; a model call costs what it is accounted as, here in microdollars
                    dvergr.chat.accounting/calculate-cost (fn [_type amount & _] amount)
                    chat-agent/run-agent-turn!
                    (fn [chat-ctx opts]
                      (chat-context/account-usage! chat-ctx :output-tokens (first @costs))
                      (swap! costs rest)
                      (swap! wallets conj (resource/wallet-microdollars room (:run-id opts)))
                      (chat-context/add-message! chat-ctx {:role :assistant :content "working"})
                      :continue)]
        (let [handle (binding [ec/*execution-context* (:ctx room)]
                       (program/hire! room team :worker {:task "work" :resources {resource/microdollars 1000000M}}))
              result (binding [ec/*execution-context* (:ctx room)] @handle)]
          (is (= [600000M 0M] @wallets) "each model call is paid from the wallet; the overrun takes the rest")
          (is (= :waiting (:run/status result)) "an empty wallet is an exhausted budget")
          (is (= 1100000 (get-in result [:run/metrics :usage :used]))
              "the budget records what the calls cost, not the budget it ended")
          (is (= {resource/microdollars 9000000M} (resource/balance room)) "$1 spent on models, nothing created or lost")))
      (finally
        (kontor-governance/ungovern! conn)
        (d/close-room! room)))))

(deftest a-charge-scope-debits-each-charge-once
  (let [[room conn] (ledger-room)
        left #(get (resource/balance room) resource/microdollars 0M)]
    (try
      (resource/mint! room {:id (random-uuid) :resources {resource/microdollars 1000M}})
      (let [payer (resource/room-spend-wallet room)
            step! (fn [scope costs]
                    (binding [resource/*spend-wallet* (resource/spending payer scope)]
                      (mapv resource/charge-spend! costs)))]
        (is payer "a Room wallet holding microdollars pays")
        (testing "two calls in one scope are two debits"
          (step! [:llm-step :r 0] [100 50])
          (is (= 850M (left))))
        (testing "charging the same scope again (a retried step) debits nothing more"
          (is (= [{:charged 100M :exhausted? false} {:charged 50M :exhausted? false}]
                 (step! [:llm-step :r 0] [100 50])))
          (is (= 850M (left))))
        (testing "a further call in the retried step is a new debit"
          (step! [:llm-step :r 0] [100 50 25])
          (is (= 825M (left))))
        (testing "another step is another scope"
          (step! [:llm-step :r 1] [100])
          (is (= 725M (left))))
        (testing "provider usage is priced, recorded and charged"
          (let [s (resource/spending payer [:judge :r])]
            (with-redefs [dvergr.chat.accounting/get-model-pricing (fn [_] {:input 1 :output 2})]
              (binding [resource/*spend-wallet* s]
                (resource/charge-usage! "m" {:input-tokens 10 :output-tokens 5})))
            (is (= [{:model "m" :usage {:input-tokens 10 :output-tokens 5}}] @(:calls s)))
            (is (= 705M (left)))))
        (testing "concurrent charges to one wallet take what is left, not fail"
          (let [n (left)
                charge! #(future (binding [resource/*spend-wallet*
                                           (resource/spending payer [:parallel (random-uuid)])]
                                   (resource/charge-spend! (* 2 (quot (long n) 3)))))
                results (mapv deref (doall (repeatedly 2 charge!)))]
            (is (= 0M (left)))
            (is (= n (reduce + (map :charged results))))))
        (testing "without a payer, nothing is charged"
          (binding [resource/*spend-wallet* (resource/spending nil [:x])]
            (is (nil? (resource/charge-spend! 100))))
          (is (= 0M (left)))))
      (finally
        (kontor-governance/ungovern! conn)
        (d/close-room! room)))))

(deftest a-charge-takes-what-another-writer-left
  (let [[room conn] (ledger-room)
        wallet (kontor/account-ref (resource/room-wallet-id :authority-room))
        transfer kontor/transfer!
        raced (atom false)]
    (try
      (resource/mint! room {:id (random-uuid) :resources {resource/microdollars 100M}})
      ;; between the charge's read and its debit, a grant elsewhere takes 60
      (with-redefs [kontor/transfer!
                    (fn [st spec]
                      (when (and (= :consume (:kind spec)) (compare-and-set! raced false true))
                        (transfer st {:id (random-uuid) :kind :consume :source wallet
                                      :destination kontor/sink-account
                                      :resources {resource/microdollars 60M}}))
                      (transfer st spec))]
        (binding [resource/*spend-wallet* (resource/spending (resource/room-spend-wallet room) [:race])]
          (is (= 40M (:charged (resource/charge-spend! 80))) "the provider was paid: the rest is taken")))
      (is (zero? (get (resource/balance room) resource/microdollars 0M)) "the wallet is empty, not overdrawn")
      (finally
        (kontor-governance/ungovern! conn)
        (d/close-room! room)))))

(deftest spend-follows-the-world
  ;; doc/design/speculation.md, Law 4: a model call made in a world that was
  ;; granted a share is charged to that share, not to the wallet the charge
  ;; scope names, and cannot spend more than the share
  (let [[room conn] (ledger-room)
        root (context/create-execution-context)
        room-wallet (kontor/account-ref (resource/room-wallet-id (:id room)))
        auth (authority/authority room)]
    (try
      (resource/mint! room {:id (random-uuid) :resources {resource/microdollars 10M}})
      (let [scope (open-scope auth)
            child (:child-ctx (cps #(world-scope/fork! scope root {:grant {resource/microdollars 3M}} %1 %2)))
            child-wallet (authority/wallet-of room child)
            payer (resource/room-spend-wallet room)]
        (testing "a charge in the funded world is taken from its share"
          (binding [resource/*spend-wallet* (resource/spending payer [:test :child])
                    ec/*execution-context* child]
            (is (= 2M (:charged (resource/charge-spend! 2M)))))
          (is (= 1M (usd room child-wallet)))
          (is (= 7M (usd room room-wallet)) "the Room paid only the grant"))
        (testing "the share bounds the world's spend"
          (binding [resource/*spend-wallet* (resource/spending payer [:test :child-more])
                    ec/*execution-context* child]
            (let [{:keys [charged exhausted?]} (resource/charge-spend! 5M)]
              (is (= 1M charged))
              (is exhausted?)))
          (is (= 0M (usd room child-wallet)))
          (is (= 7M (usd room room-wallet))))
        (testing "a funded world is charged where no charge scope is bound"
          (let [other (:child-ctx (cps #(world-scope/fork! scope root {:grant {resource/microdollars 2M}} %1 %2)))]
            (binding [ec/*execution-context* other]
              (is (= 1M (:charged (resource/charge-spend! 1M)))))
            (is (= 1M (usd room (authority/wallet-of room other))))
            (is (= 5M (usd room room-wallet)) "7M less the 2M granted")))
        (testing "outside a funded world the charge scope's payer pays, as before"
          (binding [resource/*spend-wallet* (resource/spending payer [:test :root])
                    ec/*execution-context* root]
            (resource/charge-spend! 1M))
          (is (= 4M (usd room room-wallet))))
        (testing "conserved: discarding returns the 1M left in the second world, 5M were spent"
          (close-scope scope)
          (is (= 5M (usd room room-wallet)))))
      (finally
        (context/stop-context! root)
        (kontor-governance/ungovern! conn)
        (d/close-room! room)))))

(deftest inference-particles-split-a-grant
  ;; doc/design/speculation.md, Laws 2 to 4 for inference: particles forked
  ;; from a funded world split its grant when SMC copies them (the
  ;; authority's `balance`), pay their spend from their share on the
  ;; executor threads that run them, and return what they leave
  (let [[room conn] (ledger-room)
        root (context/create-execution-context)
        room-wallet (kontor/account-ref (resource/room-wallet-id (:id room)))
        smc-infer @(requiring-resolve 'org.replikativ.foerster.core/smc-infer)
        spin* (requiring-resolve 'org.replikativ.spindel.core/spin)
        ;; the model below is `eval`ed: the namespaces it names must be
        ;; loaded, whichever test namespaces ran before in this JVM
        _ (require 'org.replikativ.foerster.effects 'org.replikativ.foerster.dist)
        payer (do (resource/mint! room {:id (random-uuid) :resources {resource/microdollars 100M}})
                  (resource/room-spend-wallet room))
        model (fn []
                (binding [ec/*execution-context* root]
                  (eval `(org.replikativ.spindel.core/spin
                          (let [x# (org.replikativ.foerster.effects/sample
                                    (org.replikativ.foerster.dist/normal 0.0 1.0) :id :x)]
                            (resource/charge-spend! 1M)
                            x#)))))
        measure (atom nil)]
    (try
      (binding [resource/*spend-wallet* (resource/spending payer [:test :inference])
                ec/*execution-context* root]
        (let [p (promise)]
          ((smc-infer (model) 4 {:world-policy :fork :authority (authority/authority room)
                                 :grant {resource/microdollars 8M}})
           #(deliver p [:ok %]) #(deliver p [:error %]))
          (let [[k v] (deref p 60000 [:error (ex-info "timed out" {})])]
            (if (= :error k) (throw v) (reset! measure v)))))
      (testing "the inference ran in funded particle worlds, copied at resampling"
        (is (= 4 (count ((requiring-resolve 'org.replikativ.foerster.measure/get-particles) @measure)))))
      (testing "four particles spent 1M each from their shares, the rest came back"
        (is (= 96M (usd room room-wallet))))
      (finally
        (context/stop-context! root)
        (kontor-governance/ungovern! conn)
        (d/close-room! room)))))
