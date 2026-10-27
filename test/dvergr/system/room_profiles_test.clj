(ns dvergr.system.room-profiles-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [datahike.db :as value-db]
            [datahike.tx-preds :as tx-preds]
            [dvergr.chat.schema :as chat-schema]
            [dvergr.substrate.geschichte :as g]
            [dvergr.system.db :as db]
            [dvergr.system.rooms :as rooms]
            [geschichte.repo :as repo]
            [kontor.governance :as governance]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]))

(defn- fixture [f]
  (let [cfg (fn [] {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write
                    :crypto-hash? true :keep-history? false :attribute-refs? false})
        msgs-cfg (cfg) repo-cfg (cfg) root (context/create-execution-context)
        room-id (random-uuid) slug (str "profile-" room-id) chat-id (rooms/msgs-chat-id slug)
        repo-id (random-uuid) msgs-id (random-uuid)
        control (atom (d/db-with (value-db/empty-db db/schema {:schema-flexibility :write})
                                 [{:system/id repo-id :system/type :repo :system/repository-profile :fixture/knowledge}
                                  {:room/id room-id :room/slug slug :room/default-repository [:system/id repo-id]}]))]
    (rooms/clear-room-ctxs!)
    (d/create-database msgs-cfg)
    (d/create-database repo-cfg)
    (let [msgs (d/connect msgs-cfg) repository (d/connect repo-cfg)]
      (try
        (chat-schema/ensure-full-schema! msgs)
        (d/transact msgs [(merge (chat-schema/create-chat-entity {:id chat-id :title slug})
                                 {:room/slug slug :room/type :internal})])
        (repo/init! repository {:name "Existing typed repository"})
        (with-redefs-fn
          {#'db/get-conn (constantly control)
           #'db/all-rooms (fn [] [{:room/id room-id :room/slug slug}])
           #'db/systems-for-room (fn [_] [{:system {:system/id repo-id :system/type :repo :system/scope "/fixture/repo"
                                                    :system/repository-profile :fixture/knowledge}}
                                          {:system {:system/id msgs-id :system/type :msgs :system/scope "/fixture/msgs"}}])
           #'rooms/msgs-cfg (constantly msgs-cfg)
           #'g/repository-config (constantly repo-cfg)}
          #(binding [ec/*execution-context* root]
             (f {:room-id room-id :msgs msgs :repository repository :root root})))
        (finally
          (governance/ungovern! msgs)
          (binding [ec/*execution-context* root] (rooms/release-room-stores!))
          (d/release msgs)
          (d/release repository)
          (d/delete-database msgs-cfg)
          (d/delete-database repo-cfg)
          (context/close-context! root))))))

(deftest profiled-hydration-prepares-real-adapters-before-publication
  (fixture
   (fn [{:keys [room-id msgs repository]}]
     ;; Working agent ChatContexts share this store; their chat IDs are not
     ;; additional canonical Room markers and must survive cold hydration.
     (d/transact msgs (mapv (fn [_] {:chat/id (random-uuid) :chat/title "Agent context"}) (range 3)))
     (let [opens (atom 0)
           hooks {:initialize! (fn [& _] (throw (AssertionError. "Hydration cannot initialize")))
                  :abort-create! (fn [& _] (throw (AssertionError. "Hydration cannot delete")))
                  :prepare-open! (fn [conn _]
                                   (is (identical? repository conn))
                                   (is (empty? (ygg/registered-systems)))
                                   (is (some? (tx-preds/tx-pred-for (get-in @msgs [:config :store :id]))))
                                   (swap! opens inc))}]
       (rooms/hydrate-rooms! {:repository-profiles {:fixture/knowledge hooks}})
       (let [ctx (rooms/room-ctx-for room-id)]
         (is (some? ctx))
         (binding [ec/*execution-context* ctx]
           (is (= #{"room-repo-repo" "room-msgs-msgs"} (set (keys (ygg/registered-systems))))))
         (rooms/hydrate-rooms! {:repository-profiles {}})
         (is (identical? ctx (rooms/room-ctx-for room-id)))
         (is (= 1 @opens)))))))

(deftest missing-profile-policy-withholds-and-can-retry-without-reinitialization
  (fixture
   (fn [{:keys [room-id]}]
     (rooms/hydrate-rooms!)
     (is (thrown? clojure.lang.ExceptionInfo (rooms/room-ctx-for room-id)))
     (rooms/hydrate-rooms! {:repository-profiles
                            {:fixture/knowledge {:initialize! (fn [& _] (throw (AssertionError. "No reinitialization")))
                                                 :abort-create! (fn [& _] (throw (AssertionError. "No deletion")))
                                                 :prepare-open! (fn [& _])}}})
     (is (some? (rooms/room-ctx-for room-id))))))

(deftest several-permission-grants-do-not-reacquire-one-system
  (fixture
   (fn [{:keys [room-id]}]
     (let [systems-for-room db/systems-for-room opens (atom 0)]
       (with-redefs [db/systems-for-room (fn [id] (vec (mapcat #(vector % %) (systems-for-room id))))]
         (rooms/hydrate-rooms! {:repository-profiles
                                {:fixture/knowledge {:initialize! (fn [& _] (throw (AssertionError. "No init")))
                                                     :abort-create! (fn [& _] (throw (AssertionError. "No deletion")))
                                                     :prepare-open! (fn [& _] (swap! opens inc))}}})
         (is (some? (rooms/room-ctx-for room-id)))
         (is (= 1 @opens)))))))

(deftest foreign-or-ambiguous-message-identity-withholds-the-profiled-room
  (doseq [damage [:foreign :extra]]
    (fixture
     (fn [{:keys [room-id msgs]}]
       (let [row (d/q '[:find ?e . :where [?e :room/slug _]] @msgs)]
         (case damage
           :foreign (d/transact msgs [[:db/add row :room/slug "another-room"]])
           :extra (d/transact msgs [{:chat/id (random-uuid) :room/slug "extra-room" :chat/title "Foreign room"}]))
         (let [opened (atom false)]
           (rooms/hydrate-rooms! {:repository-profiles
                                  {:fixture/knowledge {:initialize! (fn [& _]) :abort-create! (fn [& _])
                                                       :prepare-open! (fn [& _] (reset! opened true))}}})
           (is (thrown? clojure.lang.ExceptionInfo (rooms/room-ctx-for room-id)))
           (is (false? @opened))))))))

(deftest ordinary-hydration-refuses-unready-or-unsupported-intents-before-opening-stores
  (fixture
   (fn [{:keys [room-id]}]
     (doseq [intent [{:room/provisioning-state :pending :room/provisioning-version 1}
                     {:room/provisioning-state :ready :room/provisioning-version 2}
                     {:room/provisioning-state :unknown :room/provisioning-version 1}]]
       (with-redefs [db/all-rooms (fn [] [(assoc intent :room/id room-id)])
                     db/systems-for-room (fn [_] (throw (AssertionError. "Unready intent cannot open stores")))]
         (rooms/hydrate-rooms!)
         (is (thrown? clojure.lang.ExceptionInfo (rooms/room-ctx-for room-id))))))))
