(ns dvergr.system.profiled-room-publication-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [datahike.connector :as connector]
            [dvergr.substrate.datahike :as sdh]
            [dvergr.substrate.geschichte :as geschichte]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as db]
            [dvergr.system.room-intent :as intent]
            [dvergr.system.room-preparation :as preparation]
            [dvergr.system.rooms :as rooms]
            [dvergr.room.registry :as registry]
            [dvergr.runtime.ctx :as rctx]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [yggdrasil.adapters.datahike :as dh-adapter]
            [yggdrasil.protocols :as p]))

;; Real readiness transactions, but synthetic native resources: an uncertain
;; publication is intentionally quarantined and cannot safely be torn down.
;; These tests must not abandon live stores to exercise that fault boundary.
(defn- with-publication-fixture [f]
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :keep-history? false}]
    (d/create-database cfg)
    (let [control (d/connect cfg) connection? connector/connection?
          root {} ctx {:parent-ctx root} lease (Object.)
          systems (atom {}) contexts (atom {}) errors (atom {}) events (atom [])
          spec (fn [system]
                 {:id (str "fixture-repo-" (:system/id system))
                  :acquire! (constantly lease)
                  :prepare! (fn [conn] {:id (str "fixture-repo-" (:system/id system)) :conn conn})})]
      (try
        (d/transact control db/schema)
        (with-redefs-fn
          {#'db/get-conn (constantly control)
           #'paths/systems-dir (constantly "/virtual/publication-test")
           #'rctx/current-root (constantly root)
           #'rooms/fork-room-ctx (constantly ctx)
           #'rooms/room-ctxs contexts
           #'rooms/room-hydration-errors errors
           #'rooms/msgs-cfg (fn [scope] {:fixture/scope scope})
           #'rooms/kb-cfg (fn [scope] {:fixture/scope scope})
           #'rooms/mandatory-system-spec (fn [system & _] (spec system))
           #'rooms/ensure-msgs-resources! identity
           #'geschichte/ensure-repository! (fn [& _] nil)
           #'d/database-exists? (constantly false)
           #'sdh/connect! (constantly lease)
           #'sdh/provision! (fn [options] (:conn options))
           #'connector/connection? #(or (identical? lease %) (connection? %))
           #'registry/list-rooms (constantly [])
           #'dh-adapter/create (fn [conn options] {:id (:system-name options) :conn conn})
           #'p/system-id :id
           #'ygg/registered-systems #(deref systems)
           #'ygg/system #(get @systems %)
           #'ygg/register! (fn [system] (swap! systems assoc (:id system) system))
           #'ygg/unregister! (fn [id] (swap! events conj [:unregister id]) (swap! systems dissoc id))
           #'d/release (fn [conn] (is (identical? lease conn)) (swap! events conj :release))
           #'d/delete-database (fn [& _] (swap! events conj :delete))}
          #(binding [ec/*execution-context* root]
             (f {:control control :contexts contexts :errors errors :events events :systems systems})))
        (finally
          (d/release control)
          (d/delete-database cfg))))))

(deftest grant-change-during-publication-retains-owner-with-pending-intent
  (with-publication-fixture
    (fn [{:keys [control contexts errors events systems]}]
      (let [mark-ready! intent/mark-ready! grant-id (atom nil)
            error (with-redefs [intent/mark-ready!
                                (fn [basis]
                                  (reset! grant-id (get-in basis [:attachments 0 :grant/id]))
                                  (d/transact control [[:db/add [:grant/id (get-in basis [:attachments 0 :grant/id])]
                                                        :grant/permission :read]])
                                  (mark-ready! basis))]
                    (try (rooms/provision-profiled-room!
                          {:slug "revoked-before-ready" :repository-profile :fixture/knowledge}
                          {:fixture/knowledge {:initialize! (fn [& _]) :prepare-open! (fn [& _]) :abort-create! (fn [& _])}})
                         (catch Throwable error error)))
            room (db/room-by-slug "revoked-before-ready")]
        (is (preparation/recovery-required? error))
        (is (= :read (:grant/permission (d/entity @control [:grant/id @grant-id]))))
        (is (= :creation-intent-changed (:reason (ex-data (.getCause error)))))
        (is (identical? error (get @errors (:room/id room))))
        (is (= :pending (:room/provisioning-state room)))
        (is (empty? @contexts))
        (is (= 3 (count @systems)))
        (is (empty? @events))
        (is (thrown? clojure.lang.ExceptionInfo (rooms/recover-room-preparation! (:room/id room))))
        (is (empty? @events))))))

(deftest lost-readiness-ack-is-durable-but-does-not-publish-or-compensate
  (with-publication-fixture
    (fn [{:keys [contexts errors events systems]}]
      (let [mark-ready! intent/mark-ready! captured (atom nil)
            lost-ack (ex-info "Lost readiness acknowledgement" {})
            error (with-redefs [intent/mark-ready!
                                (fn [basis]
                                  (reset! captured basis)
                                  (mark-ready! basis)
                                  (throw lost-ack))]
                    (try (rooms/provision-profiled-room!
                          {:slug "lost-ready-ack" :repository-profile :fixture/knowledge}
                          {:fixture/knowledge {:initialize! (fn [& _]) :prepare-open! (fn [& _]) :abort-create! (fn [& _])}})
                         (catch Throwable error error)))
            room (db/room-by-slug "lost-ready-ack")]
        (is (preparation/recovery-required? error))
        (is (identical? lost-ack (.getCause error)))
        (is (identical? error (get @errors (:room/id room))))
        (is (= :ready (:room/provisioning-state room)))
        (is (empty? @contexts))
        (is (= 3 (count @systems)))
        (is (empty? @events))
        (is (= :ready (:room/provisioning-state (mark-ready! @captured))))
        (is (thrown? clojure.lang.ExceptionInfo (rooms/clear-room-ctxs!)))
        (is (empty? @events))))))
