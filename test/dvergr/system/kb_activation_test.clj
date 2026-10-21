(ns dvergr.system.kb-activation-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [datahike.connector :as connector]
            [dvergr.substrate.datahike :as substrate]
            [dvergr.system.db :as control]
            [dvergr.system.rooms :as rooms]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [yggdrasil.adapters.datahike :as adapter]
            [yggdrasil.protocols :as p]))

(defn- attempt [f] (try (f) nil (catch Throwable error error)))

(defn- fixture [f]
  (let [root {:parent-ctx nil} ctx {:parent-ctx root}
        room-id (random-uuid) system-id (random-uuid)
        scope "/tmp/native-kb-activation-fixture"
        runtime-id (rooms/kb-system-name scope)
        cfg {:store {:backend :file :path scope :id (random-uuid)} :branch :db}
        conn (atom {:config cfg}) db (atom :snapshot)
        expected {:system/id system-id :system/type :kb :system/name "Fixture" :system/scope scope}
        systems (atom {}) calls (atom [])]
    (with-redefs-fn
      {#'ec/current-execution-context (fn [] ec/*execution-context*)
       #'ec/execution-context-bound? (fn [] (some? ec/*execution-context*))
       #'rooms/room-ctx-for (constantly ctx)
       #'rooms/granted-kb-basis! (fn [& _] expected)
       #'rooms/kb-cfg (constantly cfg)
       #'rooms/registry-system-for (fn [& _] {:system/id system-id})
       #'control/get-conn (constantly db)
       #'connector/connection? #(identical? conn %)
       #'d/database-exists? (constantly true)
       #'substrate/connect (fn [_] (swap! calls conj :acquire) conn)
       #'adapter/create (fn [c opts] {:id (:system-name opts) :type :datahike :conn c})
       #'p/system-id :id #'p/system-type :type
       #'ygg/system #(get @systems %)
       #'ygg/registered-systems #(deref systems)
       #'ygg/register! (fn [system] (swap! calls conj :register) (swap! systems assoc (:id system) system))
       #'d/release (fn [_] (swap! calls conj :release))}
      #(binding [ec/*execution-context* root]
         (f {:root root :ctx ctx :room-id room-id :system-id system-id :runtime-id runtime-id
             :cfg cfg :conn conn :systems systems :calls calls})))))

(deftest activation-adds-once-and-reuses-without-acquisition
  (fixture
   (fn [{:keys [ctx room-id system-id systems calls]}]
     (is (= :attached (:status (rooms/ensure-granted-kb! room-id system-id))))
     (let [adapter (first (vals @systems))]
       (is (= :already-attached (:status (rooms/ensure-granted-kb! room-id system-id))))
       (is (identical? adapter (first (vals @systems)))))
     (is (= [:acquire :register] @calls))
     (is (identical? ctx (rooms/room-ctx-for room-id))))))

(deftest wrong-branch-adapter-type-or-runtime-alias-refuses-before-acquisition
  (doseq [kind [:branch :adapter :alias]]
    (fixture
     (fn [{:keys [room-id system-id runtime-id cfg conn systems calls]}]
       (reset! systems
               (case kind
                 :branch {runtime-id {:id runtime-id :type :datahike :conn (atom {:config (assoc cfg :branch :fork)})}}
                 :adapter {runtime-id {:id runtime-id :type :other :conn conn}}
                 :alias {"other-key" {:id "other-key" :type :datahike :conn conn}}))
       (is (some? (attempt #(rooms/ensure-granted-kb! room-id system-id))))
       (is (empty? @calls))))))

(deftest ambiguous-registry-identity-refuses-before-acquisition
  (fixture
   (fn [{:keys [room-id system-id calls]}]
     (with-redefs [rooms/registry-system-for (fn [& _] (throw (ex-info "Ambiguous registry" {})))]
       (is (some? (attempt #(rooms/ensure-granted-kb! room-id system-id))))
       (is (empty? @calls))))))

(deftest marked-pending-or-archived-room-is-not-a-live-attachment-target
  (doseq [extra [{:room/provisioning-state :pending :room/provisioning-version 1}
                 {:room/provisioning-state :ready :room/provisioning-version 2}
                 {:room/archived-at (java.util.Date.)}]]
    (let [room-id (random-uuid) system-id (random-uuid)]
      (with-redefs [d/pull (fn [_ _ lookup]
                             (if (= :room/id (first lookup)) (merge {:room/id room-id} extra)
                                 {:system/id system-id :system/type :kb :system/name "Fixture"
                                  :system/scope "/tmp/native-kb-activation-fixture"}))
                    d/q (fn [& _] [:read])]
        (is (some? (attempt #(#'rooms/granted-kb-basis! :snapshot room-id system-id))))))))
