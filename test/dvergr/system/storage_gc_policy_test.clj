(ns dvergr.system.storage-gc-policy-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as sdb]
            [dvergr.system.rooms :as rooms]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [yggdrasil.gc :as gc]
            [yggdrasil.protocols :as yp])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- fresh-policy [f]
  ;; Emulate independent fresh HOST processes; production has no reset API.
  (with-redefs-fn {#'rooms/storage-gc-homes (atom #{})
                   #'rooms/storage-gc-admission (atom {:ever-admitted? false :prohibited-by nil})
                   #'paths/home (constantly "/test/storage-policy")}
    f))

(defn- outcome [f]
  (try (f) :ok (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(deftest prohibition-is-idempotent-permanent-and-process-wide
  (fresh-policy
   (fn []
     (is (true? (rooms/prohibit-storage-gc! :test/retained-snapshots)))
     (is (true? (rooms/prohibit-storage-gc! :test/retained-snapshots)))
     (is (= ::rooms/storage-gc-prohibited
            (outcome #(rooms/prohibit-storage-gc! :test/different-policy))))
     (is (= ::rooms/invalid-gc-prohibition (outcome #(rooms/prohibit-storage-gc! :unqualified))))
     (is (false? (rooms/enable-storage-gc! false)))
     (is (= ::rooms/storage-gc-prohibited (outcome #(rooms/enable-storage-gc! true))))
     (with-redefs [paths/home (constantly "/another/home")]
       (is (= ::rooms/storage-gc-prohibited (outcome #(rooms/enable-storage-gc! true))))))))

(deftest previously-enabled-but-unused-is-distinct-from-an-admitted-collector
  (fresh-policy
   (fn []
     (rooms/enable-storage-gc! true)
     (is (= ::rooms/storage-gc-already-enabled
            (outcome #(rooms/prohibit-storage-gc! :test/retained-snapshots))))
     (rooms/enable-storage-gc! false)
     (is (true? (rooms/prohibit-storage-gc! :test/retained-snapshots))))))

(deftest both-collector-entrances-refuse-before-looking-up-or-constructing-work
  (fresh-policy
   (fn []
     (rooms/prohibit-storage-gc! :test/retained-snapshots)
     (let [calls (atom 0)
           forbidden (fn [& _] (swap! calls inc) (throw (AssertionError. "Collector boundary crossed")))]
       (with-redefs [sdb/get-conn forbidden sdb/all-rooms forbidden ygg/registered-systems forbidden
                     d/gc-storage forbidden gc/gc-system! forbidden]
         (doseq [opts [{} {:dry-run? true}]]
           (is (= ::rooms/storage-gc-prohibited (outcome #(rooms/gc-stores! opts))))
           (is (= ::rooms/storage-gc-prohibited (outcome #(rooms/gc-room! :test-room opts)))))
         (is (zero? @calls)))))))

(deftest even-empty-or-failed-dry-run-admission-prevents-late-prohibition
  (doseq [all-rooms [(constantly []) (fn [] (throw (ex-info "Collector setup failed" {})))]]
    (fresh-policy
     (fn []
       (with-redefs [sdb/all-rooms all-rooms]
         (outcome #(rooms/gc-stores! {:dry-run? true})))
       (rooms/enable-storage-gc! false)
       (is (= ::rooms/storage-gc-already-admitted
              (outcome #(rooms/prohibit-storage-gc! :test/retained-snapshots))))))))

(deftest a-timed-out-result-is-not-evidence-that-no-collector-was-admitted
  (fresh-policy
   (fn []
     (rooms/enable-storage-gc! true)
     (with-redefs [sdb/get-conn (constantly nil) sdb/all-rooms (constantly [])
                   d/gc-storage (fn [& _] (doto (promise) (deliver :dvergr.system.rooms/timeout)))]
       (is (= "timed out" (get-in (rooms/gc-stores!) [:system-db :error]))))
     (rooms/enable-storage-gc! false)
     (is (= ::rooms/storage-gc-already-admitted
            (outcome #(rooms/prohibit-storage-gc! :test/retained-snapshots)))))))

(deftest started-background-collection-prevents-installation-before-and-after-completion
  (fresh-policy
   (fn []
     (let [finish (atom nil)
           system (reify yp/GarbageCollectable
                    (gc-roots [_] #{})
                    (gc-sweep! [_ _] nil)
                    (gc-sweep! [_ _ _] nil))]
       (with-redefs-fn
         {#'rooms/system-write-check (fn [_] (constantly true))
          #'ygg/registered-systems (fn [] [[:test-system system]])
          #'gc/gc-system! (fn [& _] (fn [ok _] (reset! finish ok)))}
         (fn []
           (is (= {:test-system :started} (rooms/gc-room! :test-room {:dry-run? true})))
           (try
             (is (= ::rooms/storage-gc-already-admitted
                    (outcome #(rooms/prohibit-storage-gc! :test/retained-snapshots))))
             (finally (when-let [done @finish] (done {:reclaimed 0}))))))
       (is (= ::rooms/storage-gc-already-admitted
              (outcome #(rooms/prohibit-storage-gc! :test/retained-snapshots))))))))

(defn- race [left right]
  (let [start (CountDownLatch. 1)
        done (CountDownLatch. 2)
        run (fn [f] (future (.await start)
                            (try (outcome f) (finally (.countDown done)))))
        a (run left) b (run right)]
    (.countDown start)
    (when-not (.await done 5 TimeUnit/SECONDS)
      (throw (ex-info "GC admission race did not finish" {})))
    [@a @b]))

(deftest installation-versus-enable-has-one-winning-order
  (dotimes [_ 20]
    (fresh-policy
     (fn []
       (is (contains? #{[:ok ::rooms/storage-gc-prohibited]
                        [::rooms/storage-gc-already-enabled :ok]}
                      (race #(rooms/prohibit-storage-gc! :test/retained-snapshots)
                            #(rooms/enable-storage-gc! true))))))))

(deftest installation-versus-collection-has-one-winning-order
  (dotimes [_ 20]
    (fresh-policy
     (fn []
       (with-redefs [sdb/all-rooms (constantly [])]
         (is (contains? #{[:ok ::rooms/storage-gc-prohibited]
                          [::rooms/storage-gc-already-admitted :ok]}
                        (race #(rooms/prohibit-storage-gc! :test/retained-snapshots)
                              #(rooms/gc-stores! {:dry-run? true})))))))))
