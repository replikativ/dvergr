(ns dvergr.system.room-preparation
  "Private synchronous bootstrap ownership, before a Room is published.
   Not participant preparation, semantic world state or a permission system."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [datahike.connector :as connector]
            [dvergr.room.registry :as registry]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.impl.simple :as simple]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [yggdrasil.protocols :as p]))

(def ^:private owner-token (Object.))

(defn- refuse! [reason]
  (throw (ex-info "Room bootstrap preparation refused" {:type ::refused :reason reason})))

(defn- owner [error]
  (when (instance? clojure.lang.IDeref error)
    (let [value @error]
      (when (identical? owner-token (:token value)) value))))

(defn recovery-required? [error] (boolean (owner error)))

(defn- retained-error [state recover! error cleanup-error]
  (let [retained (proxy [clojure.lang.ExceptionInfo clojure.lang.IDeref]
                        ["Room bootstrap retains unresolved native ownership"
                         (assoc (select-keys @state [:phase :room/id]) :type ::recovery-required) error]
                   (deref [] {:token owner-token :recover! recover!}))]
    (when (and cleanup-error (not (identical? cleanup-error error)))
      (.addSuppressed retained cleanup-error))
    retained))

(defn retain-registration-failure
  "INTERNAL HOST ownership quarantine after a live Room constructor started.
   Its context can already hold bus pumps/admission even when registration
   threw. Retain that exact context privately; no blind reconstruction, release
   or automatic recovery is safe without joined runtime reconciliation."
  [room-id ctx error]
  (let [state (atom {:phase :room-registration-uncertain :room/id room-id :ctx ctx})
        recover! (fn []
                   (binding [ec/*execution-context* (:ctx @state)]
                     (refuse! :room-registration-needs-reconciliation)))]
    (retained-error state recover! error nil)))

(defn recover!
  "HOST ONLY: retry positively safe cleanup. An uncertain native dispatch or
   started publication cannot be blindly repeated or compensated. No new Room
   attempt may bypass this retained owner. Live handles are not in ex-data."
  [error]
  (if-let [retained (owner error)]
    ((:recover! retained))
    (refuse! :no-retained-owner)))

(defn with-prepared!
  "INTERNAL bootstrap only. ctx must be a fresh unpublished non-root context.
   Each bounded, closed host spec acquires ONE connection lease, then prepares
   its adapter without hidden acquisition/registration/asynchronous work. All
   adapters prepare before registration. publish! transfers ownership; once it
   starts, failure is uncertain and NEVER triggers compensating teardown."
  [ctx specs publish!]
  (when (simple/on-drain-thread?) (refuse! :requires-host-thread))
  (when-not (and ctx (:parent-ctx ctx) (vector? specs) (<= 1 (count specs) 32) (fn? publish!)
                 (every? (fn [spec]
                           (and (map? spec) (= #{:id :acquire! :prepare!} (set (keys spec)))
                                (string? (:id spec)) (not (str/blank? (:id spec))) (<= (count (:id spec)) 256)
                                (fn? (:acquire! spec)) (fn? (:prepare! spec)))) specs)
                 (= (count specs) (count (set (map :id specs)))))
    (refuse! :invalid-host-specs))
  (binding [ec/*execution-context* ctx]
    (when (or (seq (ygg/registered-systems))
              (some #(identical? ctx (:ctx %)) (registry/list-rooms)))
      (refuse! :context-already-published-or-populated))
    (let [state (atom {:phase :preparing :leases [] :registrations []})]
      (letfn [(audit-ownership! []
                ;; Callbacks can mutate and THEN throw. Their return path is
                ;; not evidence that the unpublished context remains private.
                (try
                  (let [actual (ygg/registered-systems)
                        expected (into {} (keep (fn [{:keys [id system state]}]
                                                  (when (#{:registering :registered} state) [id system])))
                                       (:registrations @state))]
                    (when (or (some #(identical? ctx (:ctx %)) (registry/list-rooms))
                              (some (fn [{:keys [id system state]}]
                                      (and (= :registered state) (not (identical? system (get actual id)))))
                                    (:registrations @state))
                              (some (fn [[id system]] (not (identical? system (get expected id))))
                                    actual))
                      (refuse! :unexpected-context-publication)))
                  (catch Throwable error
                    (swap! state assoc :phase :foreign-registration)
                    (throw error))))
              (cleanup! []
                (locking state
                  (when (#{:publishing :published :foreign-registration} (:phase @state))
                    (refuse! :publication-needs-reconciliation))
                  (when (or (some #(= :unregistering (:state %)) (:registrations @state))
                            (some #(= :releasing (:state %)) (:leases @state)))
                    (refuse! :native-cleanup-needs-reconciliation))
                  (when-not (= :cleaned (:phase @state))
                    (audit-ownership!)
                    (swap! state assoc :phase :cleaning)
                    (doseq [index (reverse (range (count (:registrations @state))))
                            :let [{:keys [id system] registration-state :state} (get-in @state [:registrations index])]
                            :when (#{:registering :registered} registration-state)]
                      (let [current (ygg/system id)]
                        (when (and current (not (identical? current system))) (refuse! :registration-owner-changed))
                        (swap! state assoc-in [:registrations index :state] :unregistering)
                        (ygg/unregister! id)
                        (swap! state assoc-in [:registrations index :state] :unregistered)
                        (audit-ownership!)))
                    ;; Vector positions are acquisition identities: equal/identical
                    ;; connection objects can still represent several leases.
                    (doseq [index (reverse (range (count (:leases @state))))
                            :let [lease (get-in @state [:leases index])]
                            :when (not= :released (:state lease))]
                      (swap! state assoc-in [:leases index :state] :releasing)
                      (d/release (:conn lease))
                      (swap! state assoc-in [:leases index :state] :released))
                    (swap! state assoc :phase :cleaned))
                  true))
              (assert-unpublished! []
                (audit-ownership!))
              (recover-owner! []
                (binding [ec/*execution-context* ctx]
                  (try (cleanup!)
                       (catch Throwable error
                         (throw (retained-error state recover-owner! error nil))))))]
        (try
          (doseq [{:keys [id acquire! prepare!]} specs]
            (let [conn (acquire!)]
              (when-not (connector/connection? conn) (refuse! :acquire-did-not-return-connection))
              (swap! state update :leases conj {:conn conn :state :owned})
              (assert-unpublished!)
              (let [system (prepare! conn)]
                (when-not (and (= id (p/system-id system)) (identical? conn (:conn system)))
                  (refuse! :prepared-system-mismatch))
                (swap! state update :registrations conj {:id id :system system :state :prepared})
                (assert-unpublished!))))
          (swap! state assoc :phase :registering)
          (doseq [index (range (count (:registrations @state)))
                  :let [{:keys [id system]} (get-in @state [:registrations index])]]
            (when (ygg/system id) (refuse! :registration-id-occupied))
            (swap! state assoc-in [:registrations index :state] :registering)
            (ygg/register! system)
            (swap! state assoc-in [:registrations index :state] :registered)
            (audit-ownership!))
          (swap! state assoc :phase :publishing)
          (let [result (publish!)]
            (swap! state assoc :phase :published)
            result)
          (catch Throwable error
            (if (#{:publishing :published :foreign-registration} (:phase @state))
              (throw (retained-error state recover-owner! error nil))
              (do
                (try (cleanup!)
                     (catch Throwable cleanup-error
                       (throw (retained-error state recover-owner! error cleanup-error))))
                (throw error)))))))))
