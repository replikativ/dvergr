(ns dvergr.runtime.preparation-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.discourse :as d]
            [dvergr.room.store :as store]
            [dvergr.room.store.memory :as memory]
            [dvergr.runtime.preparation :as preparation]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.impl.simple :as simple]))

(deftest physical-drain-ownership-is-not-an-inherited-spin-binding
  (let [room (d/make-room {:id :preparation-drain-thread})
        host-work (promise)
        refused (promise)
        releases (atom 0)
        entries {:agent {:resource :exact-owner}}]
    (try
      (binding [ec/*execution-context* (:ctx room)]
        (sp/spawn!
         (sp/spin
          (sp/await (sp/sleep 1))
          (deliver host-work
                   (future
                     {:spin ec/*spin-id*
                      :drain? (simple/on-drain-thread?)
                      :lease (preparation/retain-room-cleanup!
                              room entries (fn [_] (swap! releases inc)))}))
          (deliver refused
                   (try (preparation/retain-room-cleanup!
                         room entries (fn [_] (swap! releases inc)))
                        ::unexpected
                        (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))))))
      (is (= ::preparation/blocking-cleanup (deref refused 3000 ::timeout)))
      (let [task (deref host-work 3000 nil)
            result (when task (deref task 3000 ::timeout))]
        (is (some? (:spin result)) "the native future inherited its caller's Spin id")
        (is (false? (:drain? result)) "but it does not own the engine's physical drain")
        (is (= entries @(:resource (:lease result))))
        (is (zero? @releases) "real-drain refusal did not detach or release resources")
        (binding [ec/*spin-id* (:spin result)]
          (preparation/drain-room! room))
        (is (= 1 @releases)))
      (finally (d/close-room! room)))))

(defn- participant! [room id]
  (let [participant {:id id :participant/incarnation (random-uuid) :active (atom nil)}]
    (swap! (:participants room) assoc id participant)
    participant))

(defn- callbacks [finished?]
  {:cancel! (constantly nil) :join! (fn [_] nil) :finished? #(deref finished?)})

(deftest bounded-preparation-and-exact-participant-incarnation
  (let [room (d/make-room {:id :preparation-lease-bounds})
        finished? (atom false)]
    (try
      (let [participant (participant! room :agent)
            lease (preparation/admit! room participant (callbacks finished?))]
        (is (= :accepted (preparation/with-current! lease (constantly :accepted))))
        (is (= ::preparation/capacity-exhausted
               (try (preparation/admit! room participant (callbacks finished?)) nil
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (participant! room :agent)
        (is (= ::preparation/stale-preparation
               (try (preparation/with-current! lease (constantly :wrong)) nil
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (doseq [i (range 63)]
          (preparation/admit! room (participant! room (keyword (str "worker-" i)))
                              (callbacks finished?)))
        (is (= ::preparation/capacity-exhausted
               (try (preparation/admit! room (participant! room :overflow) (callbacks finished?)) nil
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (reset! finished? true)
        (preparation/assert-quiescent! room)
        (is (empty? (get @(:meta room) preparation/leases-key))))
      (finally
        (reset! finished? true)
        (reset! (:participants room) {})
        (d/close-room! room)))))

(deftest live-preparation-handles-are-not-persisted-as-room-metadata
  (let [backing (memory/make)
        room (d/make-room {:id :preparation-private-metadata :store backing})
        finished? (atom false)]
    (try
      (preparation/admit! room (participant! room :agent) (callbacks finished?))
      (is (not (contains? (d/metadata-update! room assoc :user-preference :compact)
                          preparation/leases-key)))
      (let [saved (:meta (store/-load-room backing (:id room)))]
        (is (= :compact (:user-preference saved)))
        (is (not (contains? saved preparation/leases-key)))
        (is (seq (get @(:meta room) preparation/leases-key)))
        (d/metadata-update! room (constantly {:semantic :replacement}))
        (is (seq (get @(:meta room) preparation/leases-key))
            "semantic metadata replacement cannot erase live native ownership"))
      (finally
        (reset! finished? true)
        (reset! (:participants room) {})
        (d/close-room! room)))))

(deftest cleanup-leases-are-never-participant-cancellation-targets
  (let [room (d/make-room {:id :native-cleanup-lease})]
    (try
      (let [participant (participant! room :agent)
            lease (preparation/retain-cleanup! room :agent :owned-resource)]
        (is (= :cleanup (:kind lease)))
        (is (= :owned-resource (:resource lease)))
        (preparation/cancel-participant! room participant)
        (is (= :pending @(:state lease)))
        (is (= ::preparation/not-quiescent
               (try (preparation/assert-quiescent! room) nil
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (preparation/complete-cleanup! lease nil)
        (is (empty? (get @(:meta room) preparation/leases-key))))
      (finally
        (reset! (:participants room) {})
        (d/close-room! room)))))

(deftest logical-handoff-does-not-forget-the-physical-lease
  (let [room (d/make-room {:id :preparation-logical-handoff})
        physical? (atom false)
        logical? (atom false)]
    (try
      (let [participant (participant! room :agent)
            callback (assoc (callbacks physical?) :admission-ready? #(deref logical?))
            first-lease (preparation/admit! room participant callback)]
        (reset! logical? true)
        (let [next-lease (preparation/admit! room participant callback)]
          (is (not= (:id first-lease) (:id next-lease)))
          (is (= 2 (count (get @(:meta room) preparation/leases-key))))
          (is (= ::preparation/not-quiescent
                 (try (preparation/assert-quiescent! room) nil
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
          (is (= ::preparation/drain-failed
                 (try (preparation/drain-room! room) nil
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
          (is (= 2 (count (get @(:meta room) preparation/leases-key))))))
      (finally
        (reset! physical? true)
        (reset! (:participants room) {})
        (d/close-room! room)))))

(deftest failed-physical-drain-retains-owner-for-explicit-retry
  (let [room (d/make-room {:id :preparation-drain-retry})
        finished? (atom false)
        cancelled (atom 0)
        joined (atom 0)]
    (try
      (let [participant (participant! room :agent)
            lease (preparation/admit!
                   room participant
                   {:cancel! #(swap! cancelled inc)
                    :finished? #(deref finished?)
                    :join! (fn [_]
                             (swap! joined inc)
                             (when-not @finished? (throw (ex-info "still live" {}))))})]
        (preparation/cancel-participant! room participant)
        (swap! (:participants room) dissoc :agent)
        (is (= ::preparation/drain-failed
               (try (preparation/drain-room! room) nil
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (is (identical? lease (get-in @(:meta room) [preparation/leases-key (:id lease)])))
        (reset! finished? true)
        (preparation/drain-room! room)
        (is (= 2 @joined))
        (is (= 3 @cancelled))
        (is (empty? (get @(:meta room) preparation/leases-key))))
      (finally
        (reset! finished? true)
        (reset! (:participants room) {})
        (d/close-room! room)))))

(deftest room-cleanup-retention-is-canonical-and-capacity-bounded
  (let [room (d/make-room {:id :room-cleanup-capacity})
        finished? (atom false)
        released (atom 0)]
    (try
      (is (= ::preparation/admission-closed
             (try (preparation/retain-room-cleanup!
                   (assoc room :incarnation (random-uuid)) {:entry :resource}
                   (fn [_] (swap! released inc)))
                  nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
      (doseq [i (range 64)]
        (preparation/admit! room (participant! room (keyword (str "held-" i)))
                            (callbacks finished?)))
      (is (= ::preparation/capacity-exhausted
             (try (preparation/retain-room-cleanup!
                   room {:entry :resource} (fn [_] (swap! released inc)))
                  nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
      (is (= 64 (count (get @(:meta room) preparation/leases-key))))
      (is (zero? @released))
      (finally
        (reset! finished? true)
        (reset! (:participants room) {})
        (d/close-room! room)))))
