(ns dvergr.system.rooms-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as dh]
            [datahike.tx-preds :as tx-preds]
            [dvergr.chat.schema :as chat-schema]
            [dvergr.resource :as resource]
            [dvergr.room.store.datahike :as datahike-store]
            [dvergr.substrate.datahike :as substrate-datahike]
            [dvergr.system.db :as system-db]
            [dvergr.system.rooms :as rooms]
            [kontor.governance :as governance]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]))

(deftest resource-kernel-installs-on-provision-and-reconnect
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :keep-history? true
             :schema-flexibility :write}
        slug "resource-bootstrap"
        room-id (keyword slug)
        chat-id (random-uuid)]
    (dh/create-database cfg)
    (try
      (doseq [_ [:provision :reconnect]]
        (let [conn (dh/connect cfg)]
          (try
            (chat-schema/ensure-full-schema! conn)
            (when-not (dh/entity @conn [:chat/id chat-id])
              (dh/transact conn
                           [(merge (chat-schema/create-chat-entity
                                    {:id chat-id :title slug})
                                   {:room/slug slug :room/type :internal})]))
            (#'rooms/ensure-msgs-resources! conn)
            (is (= {} (resource/balance
                       {:id room-id :store (datahike-store/make conn)})))
            (finally
              (governance/ungovern! conn)
              (dh/release conn)))))
      (finally
        (dh/delete-database cfg)))))

(deftest failed-mandatory-hydration-withholds-the-room-context
  (let [root (context/create-execution-context)
        room-id (random-uuid)
        registrations (atom [])]
    (rooms/clear-room-ctxs!)
    (try
      (with-redefs-fn
        {#'system-db/all-rooms (fn [] [{:room/id room-id}])
         #'system-db/systems-for-room
         (fn [_] [{:system {:system/type :kb :system/scope "kb"}}
                  {:system {:system/type :msgs :system/scope "broken"}}
                  {:system {:system/type :repo :system/scope "repo"}}])
         #'rooms/register-system-into-current!
         (fn [{:system/keys [type]}]
           (swap! registrations conj type)
           (when (= :msgs type)
             (throw (ex-info "governance unavailable" {}))))}
        #(binding [ec/*execution-context* root]
           (rooms/hydrate-rooms!)))
      (is (= [:msgs] @registrations)
          "mandatory accounting is established before any optional system")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"hydration failed"
           (rooms/room-ctx-for room-id)))
      (finally
        (rooms/clear-room-ctxs!)
        (context/close-context! root)))))

(deftest failed-context-registration-preserves-store-global-governance
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :keep-history? true
             :schema-flexibility :write}
        slug "shared-resource-governance"
        chat-id (random-uuid)]
    (dh/create-database cfg)
    (let [conn (dh/connect cfg)
          released (atom [])]
      (try
        (chat-schema/ensure-full-schema! conn)
        (dh/transact conn
                     [(merge (chat-schema/create-chat-entity
                              {:id chat-id :title slug})
                             {:room/slug slug :room/type :internal})])
        (#'rooms/ensure-msgs-resources! conn)
        (let [store-id (get-in cfg [:store :id])
              installed (tx-preds/tx-pred-for store-id)]
          (is (ifn? installed))
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"Messages resource kernel failed"
               (with-redefs [substrate-datahike/provision!
                             (fn [{:keys [register?]}]
                               (if (false? register?)
                                 conn
                                 (throw (ex-info "registration failed" {}))))
                             dh/release #(swap! released conj %)]
                 (#'rooms/register-system-into-current!
                  {:system/type :msgs :system/scope "shared"}))))
          (is (= [conn] @released)
              "the failed registration releases its attempted connection")
          (is (identical? installed (tx-preds/tx-pred-for store-id))
              "a failed new context cannot ungovern an existing shared store"))
        (finally
          (governance/ungovern! conn)
          (dh/release conn)
          (dh/delete-database cfg))))))
