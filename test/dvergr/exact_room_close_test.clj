(ns dvergr.exact-room-close-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.run :as run]
            [dvergr.discourse :as discourse]
            [dvergr.room.registry :as registry]
            [dvergr.room.store.memory :as memory]
            [dvergr.runtime.ctx :as runtime]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- failure [f] (try (f) nil (catch Throwable error error)))

(defn- fixture [f]
  (let [root (context/create-execution-context) owned (atom [])
        make! (fn [id]
                (let [room (discourse/make-room {:id id :ctx root :store (memory/make)})]
                  (swap! owned conj room) room))]
    (binding [ec/*execution-context* root]
      (try (f root make!)
           (finally
             (doseq [room (reverse @owned)
                     :when (or (identical? room (registry/lookup (:id room)))
                               (get-in @(:meta room) [:dvergr/fork-transfer-state :registry-removed?]))]
               (discourse/close-room! room {:close-context? false}))
             ;; Stale, explicitly unregistered test handles have no id authority;
             ;; their remaining native computation belongs to this test root.
             (context/close-context! root))))))

(deftest stale-close-cannot-fence-a-replacement-before-reporting-staleness
  (fixture
   (fn [_ make!]
     (let [id (keyword (str "stale-" (random-uuid))) old (make! id)]
       (registry/unregister! id)
       (let [replacement (make! id) metadata @(:meta old)
             path (#'run/admission-path id)
             error (failure #(discourse/close-room! old {:close-context? false}))]
         (is (= ::registry/stale-incarnation (:type (ex-data error))))
         (is (= :open (ec/get-state path)))
         (is (identical? replacement (registry/lookup id)))
         (is (= metadata @(:meta old))))))))

(deftest entire-close-reserves-only-its-slot-without-holding-the-global-monitor
  (fixture
   (fn [_ make!]
     (let [id (keyword (str "closing-" (random-uuid))) room (make! id)
           entered (promise) release (promise) drain @#'discourse/drain-room-listeners!]
       (with-redefs-fn
         {#'discourse/drain-room-listeners!
          (fn [& args]
            (when (identical? room (first args))
              (deliver entered true)
              (when (= ::timeout (deref release 5000 ::timeout)) (throw (ex-info "Test latch timed out" {}))))
            (apply drain args))}
         (fn []
           (let [closing (future (discourse/close-room! room {:close-context? false}))]
             (try
               (is (true? (deref entered 5000 ::timeout)))
               (is (= ::registry/lifecycle-in-progress
                      (:type (ex-data (failure #(registry/register! (assoc room :incarnation (random-uuid))))))))
               (is (= ::registry/lifecycle-in-progress
                      (:type (ex-data (failure #(registry/unregister! id))))))
               (let [other (make! (keyword (str "unrelated-" (random-uuid))))]
                 (is (identical? other (registry/lookup (:id other))))
                 (is (nil? (discourse/close-room! other {:close-context? false}))))
               (finally (deliver release true)))
             (is (nil? (deref closing 5000 ::timeout)))
             (is (nil? (registry/lookup id))))))))))

(deftest close-capability-cannot-escape-its-thread-root-or-dynamic-extent
  (fixture
   (fn [_ make!]
     (let [room (make! (keyword (str "extent-" (random-uuid))))
           expired (registry/with-close-reservation!
                     room :registered
                     (fn [cap]
                       (is (= ::registry/invalid-close-extent
                              (:type (ex-data @(future (failure (:unregister! cap)))))))
                       (let [foreign (context/create-execution-context)]
                         (try
                           (binding [ec/*execution-context* foreign]
                             (is (= ::registry/lifecycle-reservation-lost
                                    (:type (ex-data (failure (:unregister! cap)))))))
                           (finally (context/close-context! foreign))))
                       cap))]
       (is (= ::registry/invalid-close-extent (:type (ex-data (failure (:unregister! expired))))))
       (is (= ::registry/invalid-close-extent (:type (ex-data (failure (:removed? expired))))))
       (is (identical? room (registry/lookup (:id room))))))))

(deftest context-failure-retries-only-the-recorded-post-removal-phase
  (fixture
   (fn [root make!]
     (let [room (make! (keyword (str "context-retry-" (random-uuid))))
           close! context/close-context! calls (atom 0)]
       (with-redefs [context/close-context!
                     (fn [ctx]
                       (if (= 1 (swap! calls inc)) (throw (ex-info "Context close failed" {})) (close! ctx)))]
         (is (some? (failure #(discourse/close-room! room))))
         (is (nil? (registry/lookup (:id room))))
         (is (true? (get-in @(:meta room) [:dvergr/fork-transfer-state :registry-removed?])))
         (is (= :recovery-required (get-in @(:meta room) [:dvergr/fork-transfer-state :state])))
         (let [drain @#'discourse/drain-room-listeners!]
           (with-redefs-fn {#'discourse/drain-room-listeners!
                            (fn [& args]
                              (if (identical? room (first args))
                                (throw (AssertionError. "Never repeat id-based teardown"))
                                (apply drain args)))}
             #(is (nil? (discourse/close-room! room)))))
         (is (= 2 @calls))
         (is (= {:state :closed :operation :close} (:dvergr/fork-transfer-state @(:meta room)))))))))

(deftest removed-close-retry-never-adopts-a-replacement
  (fixture
   (fn [_ make!]
     (let [id (keyword (str "replaced-recovery-" (random-uuid))) old (make! id)]
       (with-redefs [context/close-context! (fn [_] (throw (ex-info "Context close failed" {})))]
         (is (some? (failure #(discourse/close-room! old)))))
       (let [replacement (make! id) before @(:meta replacement)
             error (failure #(discourse/close-room! old))]
         (is (= ::registry/stale-incarnation (:type (ex-data error))))
         (is (= before @(:meta replacement)))
         (is (= :open (ec/get-state (#'run/admission-path id))))
         (is (identical? replacement (registry/lookup id)))
         (discourse/close-room! replacement {:close-context? false}))
       ;; The fixture's finalizer can finish old only after the slot is empty.
       ))))

(deftest committed-registry-removal-with-lost-ack-retains-its-context-close-obligation
  (fixture
   (fn [_ make!]
     (let [id (keyword (str "lost-removal-" (random-uuid))) room (make! id)
           swap! runtime/shared-swap-state! lost? (atom false)]
       (with-redefs [runtime/shared-swap-state!
                     (fn [path f & args]
                       (let [result (apply swap! path f args)]
                         (when (and (= path [:dvergr/rooms]) (nil? (registry/lookup id))
                                    (compare-and-set! lost? false true))
                           (throw (ex-info "Removal committed; ACK lost" {})))
                         result))]
         (is (some? (failure #(discourse/close-room! room {:close-context? false})))))
       (is @lost?)
       (is (true? (get-in @(:meta room) [:dvergr/fork-transfer-state :registry-removed?])))
       (is (nil? (discourse/close-room! room {:close-context? false})))
       ;; Post-removal hooks ran despite the lost ACK, so the old sandbox-work
       ;; lifecycle does not block a fresh incarnation after recovery.
       (let [replacement (make! id)] (is (identical? replacement (registry/lookup id))))))))

(deftest unconfirmed-removal-cannot-close-context-or-report-success
  (fixture
   (fn [_ make!]
     (let [id (keyword (str "unconfirmed-" (random-uuid))) room (make! id)
           publish! runtime/shared-swap-state! skipped? (atom false) context-closes (atom 0)
           error (with-redefs [runtime/shared-swap-state!
                               (fn [path f & args]
                                 (if (and (= path [:dvergr/rooms]) (compare-and-set! skipped? false true))
                                   (runtime/shared-get-state path)
                                   (apply publish! path f args)))
                               context/close-context! (fn [_] (swap! context-closes inc)
                                                        (throw (AssertionError. "Unconfirmed Room must retain its context")))]
                   (failure #(discourse/close-room! room)))]
       (is (= ::registry/removal-unconfirmed (:type (ex-data error))))
       (is (zero? @context-closes))
       (is (identical? room (registry/lookup id)))
       (is (not (get-in @(:meta room) [:dvergr/fork-transfer-state :registry-removed?])))
       (is (nil? (discourse/close-room! room {:close-context? false})))))))

(deftest failed-drain-releases-reservation-and-keeps-the-original-recovery-owner
  (fixture
   (fn [_ make!]
     (let [room (make! (keyword (str "failed-drain-" (random-uuid))))
           drain @#'discourse/drain-room-listeners!]
       (with-redefs-fn {#'discourse/drain-room-listeners!
                        (fn [& args] (apply drain args) (throw (ex-info "Drain ACK failed" {})))}
         #(is (some? (failure (fn [] (discourse/close-room! room {:close-context? false}))))))
       (is (identical? room (registry/lookup (:id room))))
       (is (= :recovery-required (get-in @(:meta room) [:dvergr/fork-transfer-state :state])))
       (is (nil? (discourse/close-room! room {:close-context? false})))))))

(deftest post-removal-retry-reserves-the-empty-slot-until-context-ack
  (fixture
   (fn [_ make!]
     (let [id (keyword (str "retry-slot-" (random-uuid))) room (make! id)
           entered (promise) release (promise) close! context/close-context!]
       (with-redefs [context/close-context! (fn [_] (throw (ex-info "Initial context close failed" {})))]
         (is (some? (failure #(discourse/close-room! room)))))
       (with-redefs [context/close-context!
                     (fn [ctx]
                       (deliver entered true)
                       (when (= ::timeout (deref release 5000 ::timeout)) (throw (ex-info "Test latch timed out" {})))
                       (close! ctx))]
         (let [retry (future (discourse/close-room! room))]
           (try
             (is (true? (deref entered 5000 ::timeout)))
             ;; This runs AFTER assert-context-unshared! but BEFORE context
             ;; close: the reservation, not a prior sharing check, fences it.
             (is (= ::registry/lifecycle-in-progress
                    (:type (ex-data (failure #(registry/register! (assoc room :incarnation (random-uuid))))))))
             (is (= ::registry/lifecycle-in-progress
                    (:type (ex-data (failure #(discourse/close-room! room))))))
             (finally (deliver release true)))
           (is (nil? (deref retry 5000 ::timeout)))))
       (is (= :closed (get-in @(:meta room) [:dvergr/fork-transfer-state :state])))))))
