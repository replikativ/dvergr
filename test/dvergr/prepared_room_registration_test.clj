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
            [dvergr.substrate.repository-profile :as profile]
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

(defn- legacy! [control id]
  (swap! control dh/db-with [[:db/retract [:room/id id] :room/provisioning-state :ready]
                             [:db/retract [:room/id id] :room/provisioning-version 1]]))

(defn- host-policy [validator]
  {:repository-profiles
   {:fixture/knowledge {:initialize! (fn [& _]) :prepare-open! (fn [& _]) :abort-create! (fn [& _])
                        :legacy-room-ready? validator}}})

(deftest profile-registration-extension-is-closed-and-optional
  (let [hooks (get-in (host-policy (constantly true)) [:repository-profiles :fixture/knowledge])]
    (is (= hooks (profile/resolve! {:fixture/knowledge hooks} :fixture/knowledge)))
    (is (= (dissoc hooks :legacy-room-ready?)
           (profile/resolve! {:fixture/knowledge (dissoc hooks :legacy-room-ready?)} :fixture/knowledge)))
    (doseq [invalid [(assoc hooks :allow-legacy? true) (assoc hooks :legacy-room-ready? true)
                     (dissoc hooks :prepare-open!)]]
      (is (thrown? clojure.lang.ExceptionInfo (profile/resolve! {:fixture/knowledge invalid} :fixture/knowledge))))))

(deftest legacy-profile-attestation-preserves-flags-and-allows-later-source-edits
  (fixture
   (fn [{:keys [id control messages makes]}]
     (legacy! control id)
     (let [before @control repository (atom @messages) seen (atom [])
           options (host-policy (fn [db room-id repository-db]
                                  (swap! seen conj [db room-id repository-db])
                                  (and (identical? before db) (= id room-id) (identical? @repository repository-db))))]
       (with-redefs [systems/default-workspace (fn [& _] {:conn repository})]
         (let [room (rooms/register-prepared-room! id options)]
           (is (= 1 @makes (count @seen)))
           (is (= before @control) "Attestation does not stamp historical provisioning fields")
           (swap! repository dh/db-with [{:chat/id (random-uuid) :chat/title "Legitimate later source edit"}])
           (is (identical? room (rooms/register-prepared-room! id options)))
           (is (= 1 @makes))
           (is (= 2 (count @seen)))))))))

(deftest legacy-attestation-refuses-missing-nonboolean-stale-and-foreign-inputs
  (fixture
   (fn [{:keys [id control messages makes ctx]}]
     (legacy! control id)
     (let [before @control repository (atom @messages)]
       (with-redefs [systems/default-workspace (fn [& _] {:conn repository})]
         (doseq [options [{} (host-policy (constantly nil)) (host-policy (constantly false))
                          (host-policy (constantly :ready))]]
           (is (= ::rooms/not-ready (:type (ex-data (attempt #(rooms/register-prepared-room! id options)))))))
         (with-redefs [systems/default-workspace (fn [& _] (throw (AssertionError. "No source read for foreign root")))]
           (is (= ::rooms/requires-host-root
                  (:type (ex-data (binding [ec/*execution-context* ctx]
                                    (attempt #(rooms/register-prepared-room! id (host-policy (constantly true))))))))))
         (with-redefs [systems/default-workspace (fn [& _] {:conn repository})]
           (let [options (host-policy (fn [& _] (swap! control dh/db-with [{:room/id id :room/name "Changed while reading"}]) true))]
             (is (= ::rooms/not-ready (:type (ex-data (attempt #(rooms/register-prepared-room! id options)))))))
           (reset! control before))
         (let [calls (atom 0)]
           (with-redefs [systems/default-workspace (fn [& _] {:conn (if (= 1 (swap! calls inc)) repository (atom @repository))})]
             (is (= ::rooms/not-ready (:type (ex-data (attempt #(rooms/register-prepared-room! id (host-policy (constantly true))))))))))
         (with-redefs [systems/default-workspace (fn [& _] {:conn repository})]
           (is (= ::rooms/not-ready
                  (:type (ex-data
                          (attempt #(rooms/register-prepared-room!
                                     id (host-policy (fn [& _]
                                                       (swap! repository dh/db-with [{:chat/id (random-uuid) :chat/title "Changed during validation"}])
                                                       true)))))))))
         (let [seen (atom 0) options (host-policy (fn [& _] (swap! seen inc) true))]
           (doseq [tx [[[:db/add [:room/id id] :room/provisioning-state :pending]]
                       [[:db/add [:room/id id] :room/provisioning-version 1]]
                       [[:db/add [:room/id id] :room/archived-at (java.util.Date.)]]]]
             (reset! control (dh/db-with before tx))
             (is (= ::rooms/not-ready (:type (ex-data (attempt #(rooms/register-prepared-room! id options)))))))
           (is (zero? @seen)))
         (is (zero? @makes)))))))

(deftest eager-hydration-obeys-the-same-legacy-profile-attestation
  (fixture
   (fn [{:keys [id root ctx control messages makes]}]
     (legacy! control id)
     (let [repository (atom @messages) before @control]
       (with-redefs [db/all-rooms (fn [] [(dh/pull @control '[*] [:room/id id])])
                     db/room-by-slug (fn [_] (dh/pull @control '[*] [:room/id id]))
                     systems/provision-room! (fn [_] {:room-ctx ctx})
                     systems/default-workspace (fn [& _] {:conn repository})
                     run/reconcile-orphaned-runs! (fn [_] [])
                     systems/msgs-conn-for-slug (fn [_] nil)]
         (rooms/hydrate-registry! root)
         (is (zero? @makes) "Missing host attestation leaves a profiled legacy Room unloaded")
         (rooms/hydrate-registry! root (host-policy (constantly true)))
         (is (= 1 @makes))
         (is (= before @control)))))))

(deftest eager-attestation-cannot-construct-on-a-foreign-preparation-context
  (fixture
   (fn [{:keys [id root control makes]}]
     (legacy! control id)
     (with-redefs [db/all-rooms (fn [] [(dh/pull @control '[*] [:room/id id])])
                   db/room-by-slug (fn [_] (dh/pull @control '[*] [:room/id id]))
                   systems/provision-room! (fn [_] {:room-ctx root})
                   systems/default-workspace (fn [& _] (throw (AssertionError. "No foreign source IO")))
                   rooms/room-store (fn [& _] (throw (AssertionError. "No foreign messages IO")))]
       (rooms/hydrate-registry! root (host-policy (fn [& _] (throw (AssertionError. "No foreign attestation")))))
       (is (zero? @makes))))))

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
