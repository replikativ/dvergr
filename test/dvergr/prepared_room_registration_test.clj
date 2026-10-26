(ns dvergr.prepared-room-registration-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as dh]
            [datahike.db :as value-db]
            [dvergr.agent.run :as run]
            [dvergr.chat.schema :as schema]
            [dvergr.discourse :as discourse]
            [dvergr.room.registry :as registry]
            [dvergr.room.store :as store]
            [dvergr.room.store.memory :as memory]
            [dvergr.rooms :as rooms]
            [dvergr.system.db :as db]
            [dvergr.system.room-preparation :as preparation]
            [dvergr.system.rooms :as systems]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]))

;; Immutable Datahike values, no native store leases. Real Room constructors,
;; registry hooks, bus pumps and Run admission live in this test-owned root.
;; Closing that root is explicit runtime reconciliation, not compensating store
;; release through a quarantined Room. If it fails, retain its exact ownership.
(defonce ^:private held-root (atom nil))

(defn- fixture [f]
  (when @held-root (throw (ex-info "Previous test root needs joined shutdown" {})))
  (let [id (random-uuid) slug (str "prepared-" id) repo (random-uuid)
        chat-id (systems/msgs-chat-id slug)
        control (atom (dh/db-with (value-db/empty-db db/schema {:schema-flexibility :write})
                                  [{:system/id repo :system/type :repo :system/repository-profile :fixture/knowledge}
                                   {:room/id id :room/slug slug :room/name "Prepared fixture" :room/type :project
                                    :room/default-repository [:system/id repo]
                                    :room/provisioning-version 1 :room/provisioning-state :ready}]))
        messages (atom (dh/db-with (value-db/empty-db schema/full-schema {:schema-flexibility :write})
                                   [{:chat/id chat-id :chat/title "Prepared fixture" :room/slug slug :room/type :internal}]))
        room-store (assoc (memory/make) :conn messages)
        root (context/create-execution-context) ctx (context/fork-context root)
        contexts (atom {id {:ctx ctx :slug slug :profile :fixture/knowledge}})
        errors (atom {}) makes (atom 0) make! discourse/make-room]
    (with-redefs-fn
      {#'db/get-conn (constantly control)
       #'systems/room-ctxs contexts #'systems/room-hydration-errors errors
       #'systems/room-msgs-conn (constantly messages)
       #'rooms/room-store (constantly room-store)
       #'discourse/make-room (fn [options] (swap! makes inc) (make! options))}
      #(binding [ec/*execution-context* root]
         (try
           (f {:root root :ctx ctx :id id :slug slug :control control :messages messages
               :errors errors :contexts contexts :makes makes :make! discourse/make-room})
           (finally
             (try (context/close-context! root)
                  (catch Throwable error
                    (swap! held-root assoc :root root :ctx ctx :contexts contexts :errors errors
                           :primary-close-error error)
                    (throw error)))))))))

(defn- attempt [f] (try (f) nil (catch Throwable error error)))

(deftest one-targeted-registration-reuses-the-canonical-live-room-without-control-writes
  (fixture
   (fn [{:keys [id ctx control makes]}]
     (let [before @control room (rooms/register-prepared-room! id)]
       (is (identical? ctx (:ctx room)))
       (is (identical? room (registry/lookup (:id room))))
       (is (identical? room (rooms/register-prepared-room! id)))
       (is (= 1 @makes))
       (is (= before @control))))))

(deftest unready-profile-archive-child-and-ambiguous-messages-refuse-before-construction
  (fixture
   (fn [{:keys [id ctx control messages makes]}]
     (let [before @control]
       (doseq [tx [[[:db/retract [:room/id id] :room/provisioning-state :ready]
                    [:db/retract [:room/id id] :room/provisioning-version 1]]
                   [[:db/add [:room/id id] :room/provisioning-state :pending]]
                   [[:db/add [:room/id id] :room/provisioning-version 2]]
                   [[:db/add [:room/id id] :room/archived-at (java.util.Date.)]]]]
         (reset! control (dh/db-with before tx))
         (is (= ::rooms/not-ready (:type (ex-data (attempt #(rooms/register-prepared-room! id)))))))
       (reset! control before))
     (is (= ::rooms/requires-host-root
            (:type (ex-data (binding [ec/*execution-context* ctx]
                              (attempt #(rooms/register-prepared-room! id)))))))
     (swap! messages dh/db-with [{:chat/id (random-uuid) :room/slug "foreign-room" :room/type :internal}])
     (is (= ::rooms/invalid-messages (:type (ex-data (attempt #(rooms/register-prepared-room! id))))))
     (is (zero? @makes)))))

(deftest agent-amplification-is-bounded-before-pulling-metadata-or-joining
  (fixture
   (fn [{:keys [id control makes]}]
     (swap! control dh/db-with [{:room/id id :room/agent-ids (mapv #(keyword (str "agent-" %)) (range 257))}])
     (with-redefs [dh/pull (fn [& _] (throw (AssertionError. "Must refuse before metadata pull")))]
       (is (= ::rooms/too-many-agents (:type (ex-data (attempt #(rooms/register-prepared-room! id)))))))
     (is (zero? @makes)))))

(deftest ordinary-agent-chat-contexts-do-not-masquerade-as-room-identities
  (fixture
   (fn [{:keys [id slug messages makes]}]
     (swap! messages dh/db-with (mapv (fn [_] {:chat/id (random-uuid) :chat/title "Agent working context"}) (range 3)))
     (is (true? (systems/canonical-messages-identity? @messages slug)))
     (is (some? (rooms/register-prepared-room! id)))
     (is (= 1 @makes)))))

(deftest canonical-message-marker-and-expected-chat-must-be-the-same-unique-entity
  (fixture
   (fn [{:keys [slug messages]}]
     (let [db @messages expected (systems/msgs-chat-id slug)]
       (doseq [tx [[[:db/add [:chat/id expected] :room/slug "foreign"]]
                   [[:db/retract [:chat/id expected] :chat/id expected]]
                   [[:db/retract [:chat/id expected] :room/slug slug]]
                   [{:chat/id (random-uuid) :room/slug "second-room"}]]]
         (is (false? (systems/canonical-messages-identity? (dh/db-with db tx) slug))))
       (let [room-eid (:e (first (dh/datoms db :aevt :room/slug)))
             detached (dh/db-with db [[:db/retract room-eid :chat/id expected]])
             other (dh/db-with detached [{:chat/id expected :chat/title "Expected chat on another entity"}])
             chat-eid (:e (first (dh/datoms other :avet :chat/id expected)))]
         (is (not= room-eid chat-eid))
         (is (false? (systems/canonical-messages-identity? other slug))))))))

(deftest marker-free-unprofiled-room-can-register-without-legacy-provisioning
  (fixture
   (fn [{:keys [id control makes]}]
     (let [repo (dh/q '[:find ?repo . :in $ ?id :where [?room :room/id ?id]
                        [?room :room/default-repository ?repo]] @control id)]
       (swap! control dh/db-with [[:db/retract [:room/id id] :room/provisioning-state :ready]
                                  [:db/retract [:room/id id] :room/provisioning-version 1]
                                  [:db/retract repo :system/repository-profile :fixture/knowledge]])
       (let [before @control]
         (is (some? (rooms/register-prepared-room! id)))
         (is (= before @control))
         (is (= 1 @makes)))))))

(deftest foreign-prepared-context-and-registered-incarnation-are-never-replaced
  (fixture
   (fn [{:keys [id root ctx contexts errors makes]}]
     (let [foreign-root (context/create-execution-context)]
       (try
         (swap! contexts assoc-in [id :ctx] foreign-root)
         (is (= ::rooms/not-prepared
                (:type (ex-data (attempt #(rooms/register-prepared-room! id))))))
         (is (zero? @makes))
         (is (empty? @errors))
         (finally
           (swap! contexts assoc-in [id :ctx] ctx)
           (try (context/close-context! foreign-root)
                (catch Throwable error
                  (swap! held-root assoc :foreign-root foreign-root :foreign-close-error error
                         :root root :ctx ctx :contexts contexts :errors errors)
                  (throw error))))))
     (let [room (rooms/register-prepared-room! id)
           foreign (assoc room :ctx (ec/current-execution-context))]
       ;; Other lifecycle hooks correctly refuse replacing a live incarnation.
       ;; Inject only the observed lookup value to exercise this HOST boundary;
       ;; do not disable those hooks or corrupt the actual runtime registry.
       (with-redefs [registry/lookup (constantly foreign)]
         (is (= ::rooms/foreign-registration
                (:type (ex-data (attempt #(rooms/register-prepared-room! id)))))))
       (is (identical? room (registry/lookup (:id room))))
       (is (= 1 @makes))
       (is (empty? @errors))))))

(deftest pre-register-failure-retains-started-pumps-and-refuses-all-implicit-recovery
  (fixture
   (fn [{:keys [id slug ctx makes errors]}]
     (let [failure (ex-info "Pre-register hook rejected" {}) hook (random-uuid)]
       (registry/add-pre-register-hook! hook #(when (= slug (:slug %)) (throw failure)))
       (try
         (let [error (attempt #(rooms/register-prepared-room! id))]
           (is (preparation/recovery-required? error))
           (is (identical? failure (.getCause error)))
           (is (identical? error (get @errors id)))
           (is (= :closed (binding [ec/*execution-context* ctx]
                            (ec/get-state [:dvergr/run-admissions (store/slug->room-id slug)]))))
           (is (nil? (registry/lookup (store/slug->room-id slug))))
           (is (some? (attempt #(rooms/register-prepared-room! id))))
           (is (= 1 @makes))
           (is (thrown? clojure.lang.ExceptionInfo (systems/clear-room-ctxs!)))
           (is (thrown? clojure.lang.ExceptionInfo (systems/release-room-stores!)))
           (is (thrown? clojure.lang.ExceptionInfo (systems/recover-room-preparation! id))))
         (finally (registry/add-pre-register-hook! hook (constantly nil))))))))

(deftest failure-after-publication-retains-the-room-but-closes-new-run-admission
  (fixture
   (fn [{:keys [id slug makes make! errors]}]
     (let [failure (ex-info "Lost constructor acknowledgement after registration" {})
           error (with-redefs [discourse/make-room (fn [options] (make! options) (throw failure))]
                   (attempt #(rooms/register-prepared-room! id)))
           room (registry/lookup (store/slug->room-id slug))]
       (is (some? room))
       (is (preparation/recovery-required? error))
       (is (identical? failure (.getCause error)))
       (is (identical? error (get @errors id)))
       (is (some? (attempt #(rooms/register-prepared-room! id))))
       (is (= 1 @makes))
       (is (= ::run/room-admission-closed
              (:type (ex-data (attempt #(run/start! room :worker (random-uuid) nil))))))
       (is (thrown? clojure.lang.ExceptionInfo (systems/release-room-stores!)))))))

(deftest failed-admission-fence-never-replaces-the-retained-constructor-owner
  (fixture
   (fn [{:keys [id make! errors]}]
     (let [failure (ex-info "Constructor failed" {}) fence-error (ex-info "Fence failed" {})
           error (with-redefs [discourse/make-room (fn [options] (make! options) (throw failure))
                               run/close-room-admission! (fn [_] (throw fence-error))]
                   (attempt #(rooms/register-prepared-room! id)))]
       (is (preparation/recovery-required? error))
       (is (identical? failure (.getCause error)))
       (is (identical? error (get @errors id)))
       (is (some #(identical? fence-error %) (.getSuppressed error)))))))
