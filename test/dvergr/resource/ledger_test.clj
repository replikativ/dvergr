(ns dvergr.resource.ledger-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [datahike.tx-preds :as tx-preds]
            [dvergr.resource.ledger :as ledger]
            [dvergr.room.store.governance :as composition]
            [kontor.governance :as governance]
            [kontor.resource :as kontor]))

(defn- with-ledger [f]
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :keep-history? true :schema-flexibility :write}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (try
        (kontor/install! conn)
        (d/transact conn [{:db/ident :run/id :db/valueType :db.type/uuid
                           :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}])
        (ledger/ensure-schema! conn)
        (ledger/govern! conn)
        (kontor/install-unit! conn {:symbol "microUSD" :precision 0})
        (f conn)
        (finally
          (governance/ungovern! conn)
          (d/release conn))))))

(deftest purpose-is-atomic-immutable-and-checked-on-replay
  (with-ledger
    (fn [conn]
      (let [id (random-uuid) spec {:id id :purpose :world}]
        (ledger/open-account! conn spec)
        (is (= (kontor/account-ref id) (ledger/open-account! conn spec)))
        (is (thrown? Exception (ledger/open-account! conn (assoc spec :purpose :hold))))
        (is (thrown? Exception (ledger/assert-wallet! conn {:id id}))))
      (let [id (random-uuid)]
        (kontor/open-account! conn {:id id})
        (is (thrown? Exception
                     (d/transact conn [{:db/id (kontor/account-ref id)
                                        :dvergr.resource-account/purpose :world}])))
        (is (thrown? Exception (ledger/open-account! conn {:id id :purpose :world}))))
      (let [id (random-uuid)]
        (is (thrown? Exception (ledger/assert-wallet! conn {:id id :purpose :world})))
        (is (nil? (d/entity @conn (kontor/account-ref id)))))
      (doseq [tx [[{:dvergr.resource-account/purpose :world}]
                  (mapv #(assoc % :dvergr.resource-account/purpose :invalid)
                        (kontor/open-account-tx-data {:id (random-uuid)}))]]
        (is (thrown? Exception (d/transact conn tx))))
      (is (thrown? Exception
                   (ledger/open-account! conn {:id (random-uuid) :purpose :run})))
      (let [id (random-uuid)]
        (ledger/open-account! conn {:id id :purpose :world})
        (is (thrown? Exception (d/transact conn [{:db/id (kontor/account-ref id)
                                                  :dvergr.resource-account/purpose :hold}])))
        (is (thrown? Exception (d/transact conn [[:db/retract (kontor/account-ref id)
                                                  :dvergr.resource-account/purpose :world]])))))))

(deftest conflicting-concurrent-openings-have-one-classification
  (with-ledger
    (fn [conn]
      (let [id (random-uuid)
            ready (java.util.concurrent.CountDownLatch. 2)
            start (promise)
            open (fn [purpose]
                   (future
                     (.countDown ready)
                     @start
                     (try
                       (ledger/open-account! conn {:id id :purpose purpose})
                       [:ok purpose]
                       (catch Exception _ [:conflict purpose]))))
            a (open :world)
            b (open :hold)]
        (is (.await ready 5 java.util.concurrent.TimeUnit/SECONDS))
        (deliver start true)
        (let [results [(deref a 10000 :timeout) (deref b 10000 :timeout)]
              winner (second (first (filter #(= :ok (first %)) results)))]
          (is (= {:ok 1 :conflict 1} (frequencies (map first results))))
          (is (= winner (:dvergr.resource-account/purpose
                         (d/pull @conn [:dvergr.resource-account/purpose] (kontor/account-ref id))))))))))

(deftest view-is-pinned-and-balances-match-kontor
  (with-ledger
    (fn [conn]
      (kontor/install-unit! conn {:symbol "dispatch" :precision 0})
      (let [a (random-uuid) b (random-uuid)
            _ (ledger/open-account! conn {:id a :purpose :room})
            _ (ledger/open-account! conn {:id b :purpose :world})
            send! #(kontor/transfer! conn (assoc % :id (random-uuid)))
            check! (fn []
                     (let [view (ledger/read-view conn)]
                       (doseq [id [a b kontor/source-id kontor/sink-id]]
                         (is (= (kontor/balance conn (kontor/account-ref id))
                                (get-in view [:accounts id :balance]))))))]
        (send! {:kind :mint :source kontor/source-account :destination (kontor/account-ref a)
                :resources {"microUSD" 100M "dispatch" 10M}})
        (check!)
        (send! {:kind :grant :source (kontor/account-ref a) :destination (kontor/account-ref b)
                :resources {"microUSD" 30M "dispatch" 3M}})
        (check!)
        (let [before (ledger/read-view conn)]
          (send! {:kind :consume :source (kontor/account-ref b) :destination kontor/sink-account
                  :resources {"microUSD" 7M "dispatch" 1M}})
          (check!)
          (is (= {"microUSD" 30M "dispatch" 3M} (get-in before [:accounts b :balance])))
          (is (= 2 (count (:transfers before)))))
        (send! {:kind :return :source (kontor/account-ref b) :destination (kontor/account-ref a)
                :resources {"microUSD" 23M "dispatch" 2M}})
        (check!)))))

(deftest named-governors-do-not-accumulate-wrappers
  (with-ledger
    (fn [conn]
      (let [calls (atom [])
            a (fn [_] (swap! calls conj :a))
            b (fn [_] (swap! calls conj :b))
            assert-once! (fn [expected]
                           (reset! calls [])
                           ((tx-preds/tx-pred-for (get-in @conn [:config :store :id]))
                            {:db-before @conn :db-after @conn :tx-data [] :datahike/tx-ops []})
                           (is (= (frequencies expected) (frequencies @calls))))]
        (composition/compose! conn ::a a)
        (composition/compose! conn ::b b)
        (composition/compose! conn ::a a)
        (assert-once! [:a :b])
        (kontor/install! conn)
        (composition/compose! conn ::a a)
        (assert-once! [:a :b])
        (composition/compose! conn ::a (fn [_] (swap! calls conj :new-a)))
        (assert-once! [:new-a :b])))))
