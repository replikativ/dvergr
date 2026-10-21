(ns dvergr.system.room-preparation-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [datahike.connector :as connector]
            [dvergr.room.registry :as registry]
            [dvergr.system.room-preparation :as preparation]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [yggdrasil.protocols :as p]))

(defn- fixture [f]
  (let [ctx {:parent-ctx {}} systems (atom {}) rooms (atom []) events (atom [])
        conn (Object.)
        spec (fn [id] {:id id :acquire! (fn [] (swap! events conj [:acquire id]) conn)
                       :prepare! (fn [c] (swap! events conj [:prepare id]) {:id id :conn c})})]
    (with-redefs [connector/connection? #(identical? conn %)
                  registry/list-rooms #(deref rooms)
                  ygg/registered-systems #(deref systems)
                  ygg/system #(get @systems %)
                  ygg/register! (fn [system] (swap! events conj [:register (:id system)])
                                  (swap! systems assoc (:id system) system))
                  ygg/unregister! (fn [id] (swap! events conj [:unregister id]) (swap! systems dissoc id))
                  p/system-id :id
                  d/release (fn [c] (is (identical? conn c)) (swap! events conj [:release]))]
      (f {:ctx ctx :systems systems :rooms rooms :events events :conn conn :spec spec}))))

(defn- attempt [f] (try (f) nil (catch Throwable error error)))

(deftest all-prepare-before-registration-and-publish-transfers-leases
  (fixture
   (fn [{:keys [ctx spec events systems]}]
     (is (= :room (preparation/with-prepared! ctx [(spec "a") (spec "b")]
                    #(do (swap! events conj [:publish]) :room))))
     (is (= [[:acquire "a"] [:prepare "a"] [:acquire "b"] [:prepare "b"]
             [:register "a"] [:register "b"] [:publish]] @events))
     (is (= #{"a" "b"} (set (keys @systems)))))))

(deftest duplicate-connection-objects-still-own-two-leases
  (fixture
   (fn [{:keys [ctx spec events systems]}]
     (let [original ygg/register! failure (ex-info "register b failed" {})]
       (with-redefs [ygg/register! (fn [system] (original system)
                                     (when (= "b" (:id system)) (throw failure)))]
         (is (identical? failure (attempt #(preparation/with-prepared! ctx [(spec "a") (spec "b")] identity)))))
       (is (empty? @systems))
       (is (= [[:unregister "b"] [:unregister "a"] [:release] [:release]] (take-last 4 @events)))))))

(deftest throwing-callbacks-with-hidden-registration-retain-every-known-lease
  (doseq [stage [:acquire! :prepare!]]
    (fixture
     (fn [{:keys [ctx spec conn systems events]}]
       (let [failure (ex-info "callback mutated then failed" {})
             hidden {:id "hidden" :conn conn}
             broken (assoc (spec "b") stage (fn [& _] (swap! systems assoc "hidden" hidden) (throw failure)))
             error (attempt #(preparation/with-prepared! ctx [(spec "a") broken] identity))]
         (is (preparation/recovery-required? error))
         (is (identical? failure (.getCause error)))
         (is (identical? hidden (get @systems "hidden")))
         (is (not-any? #{[:release]} @events))
         (is (preparation/recovery-required? (attempt #(preparation/recover! error))))
         (is (not-any? #{[:release]} @events)))))))

(deftest hidden-room-publication-quarantines-before-release
  (fixture
   (fn [{:keys [ctx spec rooms events]}]
     (let [broken (assoc (spec "a") :prepare! (fn [_] (swap! rooms conj {:ctx ctx}) (throw (ex-info "published" {}))))
           error (attempt #(preparation/with-prepared! ctx [broken] identity))]
       (is (preparation/recovery-required? error))
       (is (not-any? #{[:release]} @events))))))

(deftest unexpected-registration-during-register-quarantines
  (fixture
   (fn [{:keys [ctx spec conn systems events]}]
     (with-redefs [ygg/register! (fn [system]
                                   (swap! systems assoc (:id system) system "foreign" {:id "foreign" :conn conn})
                                   (throw (ex-info "unexpected registration" {})))]
       (is (preparation/recovery-required? (attempt #(preparation/with-prepared! ctx [(spec "a")] identity))))
       (is (not-any? #{[:release]} @events))))))

(deftest publication-started-is-never-compensated
  (fixture
   (fn [{:keys [ctx spec events systems]}]
     (let [error (attempt #(preparation/with-prepared! ctx [(spec "a")]
                             (fn [] (throw (ex-info "uncertain publish" {})))))]
       (is (preparation/recovery-required? error))
       (is (contains? @systems "a"))
       (is (not-any? #{[:release]} @events))))))

(deftest successful-registration-return-must-install-the-mandatory-adapter
  (fixture
   (fn [{:keys [ctx spec events]}]
     (let [published (atom false)]
       (with-redefs [ygg/register! (constantly nil)]
         (is (preparation/recovery-required?
              (attempt #(preparation/with-prepared! ctx [(spec "a")] (fn [] (reset! published true)))))))
       (is (false? @published))
       (is (not-any? #{[:release]} @events))))))
