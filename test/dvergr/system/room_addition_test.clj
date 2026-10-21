(ns dvergr.system.room-addition-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [datahike.connector :as connector]
            [dvergr.system.room-preparation :as preparation]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [yggdrasil.protocols :as p]))

(defn- attempt [f] (try (f) nil (catch Throwable error error)))

(defn- fixture [f]
  (let [ctx {:parent-ctx {}} conn (Object.) previous (Object.)
        systems (atom {"existing" previous}) events (atom [])
        spec {:id "added" :acquire! #(do (swap! events conj :acquire) conn)
              :prepare! (fn [c] (swap! events conj :prepare) {:id "added" :conn c})}]
    (with-redefs [connector/connection? #(identical? conn %)
                  ygg/registered-systems #(deref systems)
                  ygg/register! (fn [system] (swap! events conj :register)
                                  (swap! systems assoc (:id system) system))
                  ygg/unregister! (fn [& _] (throw (AssertionError. "Never unregister live additions")))
                  p/system-id :id
                  d/release (fn [c] (is (identical? conn c)) (swap! events conj :release))]
      (f {:ctx ctx :conn conn :previous previous :systems systems :events events :spec spec}))))

(deftest additive-publication-preserves-existing-adapters
  (fixture
   (fn [{:keys [ctx previous systems events spec]}]
     (let [result (preparation/with-addition! ctx spec #(swap! events conj :current))]
       (is (identical? result (get @systems "added")))
       (is (identical? previous (get @systems "existing")))
       (is (= [:current :acquire :prepare :current :register] @events))))))

(deftest authority-or-preparation-failure-before-dispatch-releases-only-new-lease
  (doseq [stage [:prepare :current]]
    (fixture
     (fn [{:keys [ctx systems events spec]}]
       (let [calls (atom 0)
             failure (ex-info "No longer admitted" {})
             current! (fn [] (when (and (= stage :current) (= 2 (swap! calls inc))) (throw failure)))
             spec (cond-> spec (= stage :prepare) (assoc :prepare! (fn [_] (throw failure))))
             error (attempt #(preparation/with-addition! ctx spec current!))]
         (is (identical? failure error))
         (is (= #{"existing"} (set (keys @systems))))
         (is (= 1 (count (filter #{:release} @events))))
         (is (not-any? #{:register} @events)))))))

(deftest acquisition-uncertainty-is-retained-not-retried
  (fixture
   (fn [{:keys [ctx spec events]}]
     (let [failure (ex-info "Acquisition outcome unknown" {})
           broken (assoc spec :acquire! #(do (swap! events conj :acquire) (throw failure)))
           error (attempt #(preparation/with-addition! ctx broken (constantly true)))]
       (is (preparation/recovery-required? error))
       (is (identical? failure (.getCause error)))
       (is (preparation/recovery-required? (attempt #(preparation/recover! error))))
       (is (= [:acquire] @events))))))

(deftest registration-can-be-observed-before-throwing-and-is-never-compensated
  (fixture
   (fn [{:keys [ctx systems spec events]}]
     (let [register ygg/register!]
       (with-redefs [ygg/register! (fn [system] (register system) (throw (ex-info "Registration uncertain" {})))]
         (let [error (attempt #(preparation/with-addition! ctx spec (constantly true)))]
           (is (preparation/recovery-required? error))
           (is (contains? @systems "added"))
           (is (preparation/recovery-required? (attempt #(preparation/recover! error))))
           (is (not-any? #{:release} @events))))))))

(deftest uncertain-release-is-not-repeated-by-recovery
  (fixture
   (fn [{:keys [ctx spec events]}]
     (with-redefs [d/release (fn [_] (swap! events conj :release) (throw (ex-info "Unknown release outcome" {})))]
       (let [broken (assoc spec :prepare! (fn [_] (throw (ex-info "Preparation failed" {}))))
             error (attempt #(preparation/with-addition! ctx broken (constantly true)))]
         (is (preparation/recovery-required? error))
         (is (preparation/recovery-required? (attempt #(preparation/recover! error))))
         (is (= 1 (count (filter #{:release} @events)))))))))

(deftest occupied-or-mutated-native-registration-is-not-replaced
  (fixture
   (fn [{:keys [ctx systems spec events]}]
     (is (some? (attempt #(preparation/with-addition! ctx (assoc spec :id "existing") (constantly true)))))
     (is (empty? @events))
     (let [prepare (:prepare! spec)
           broken (assoc spec :prepare! (fn [conn] (swap! systems assoc "foreign" (Object.)) (prepare conn)))
           error (attempt #(preparation/with-addition! ctx broken (constantly true)))]
       (is (preparation/recovery-required? error))
       (is (not-any? #{:release :register} @events))))))
