(ns dvergr.jobs-cleanup-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.agent.run :as run]
            [dvergr.discourse :as d]
            [dvergr.jobs :as jobs]
            [dvergr.room.registry :as registry]
            [dvergr.room.store :as store]
            [dvergr.room.store.memory :as memory]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]))

(defn- with-room
  ([f] (with-room identity f))
  ([decorate f]
   (let [room (d/make-room {:id (keyword (str "job-cleanup-" (random-uuid))) :store (decorate (memory/make))})]
     (try
       (binding [ec/*execution-context* (:ctx room)] (registry/register! room) (f room))
       (finally (d/close-room! room))))))

(defn- ready [room]
  (binding [ec/*execution-context* (:ctx room)] (sp/spin :done)))

(defn- active? [room id]
  (boolean (some #(= id (:run/id %)) (run/active-runs (:id room)))))

(defn- held! [id]
  ;; A watch gives a deterministic held-stage acknowledgement without a sleep
  ;; or a product-only polling API. Inspect public projection after that ACK.
  (let [state (:state (get @@#'jobs/live id))
        entered (promise) key (Object.)
        observe (fn [value] (when (= :held (:phase value)) (deliver entered true)))]
    (when-not state (throw (ex-info "Job lost its cleanup owner" {:job id})))
    (add-watch state key (fn [_ _ _ value] (observe value)))
    (try
      (observe @state)
      (when-not (true? (deref entered 5000 false)) (throw (ex-info "Held stage was not acknowledged" {:job id})))
      (jobs/recovery-state id)
      (finally (remove-watch state key)))))

(defn- recover-held! [id]
  (dotimes [_ 4]
    (when-let [expected (jobs/recovery-state id)] (jobs/recover! id expected :retry))))

(deftest terminal-job-without-cleanup-witnesses-is-not-settled-after-owner-loss
  (with-room
    (fn [room]
      (let [id (:run/id (run/start! room :test (random-uuid) nil {:kind :workflow}))]
        (run/finish! id :completed)
        (is (= :completed (:status (jobs/status id))))
        (is (false? (:settled? (jobs/status id))))))))

(deftest missing-durable-store-is-refused-before-any-job-work
  (with-room
    (fn [room]
      (let [called? (atom false)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"durable Room store"
                              (jobs/start! (assoc room :store nil) {:kind :workflow}
                                           (fn [_] (reset! called? true) (ready room)))))
        (is (false? @called?))
        (is (empty? (run/active-runs (:id room))))))))

(defn- fault-ack-store [delegate attribute fail? committed?]
  (reify store/PRoomStore
    (-store-room! [_ room-id metadata] (store/-store-room! delegate room-id metadata))
    (-load-room [_ id] (store/-load-room delegate id))
    (-delete-room! [_ id] (store/-delete-room! delegate id))
    (-list-rooms [_] (store/-list-rooms delegate))
    (-store-message! [_ room-id message] (store/-store-message! delegate room-id message))
    (-message-thread-root [_ room-id id] (store/-message-thread-root delegate room-id id))
    (-list-messages [_ room-id opts] (store/-list-messages delegate room-id opts))
    (-store-run! [_ room-id value]
      (if (and @fail? (contains? value attribute))
        (do (when committed? (store/-store-run! delegate room-id value))
            (throw (ex-info "Cleanup witness acknowledgement unavailable" {})))
        (store/-store-run! delegate room-id value)))
    (-load-run [_ room-id id] (store/-load-run delegate room-id id))
    (-list-runs [_ room-id opts] (store/-list-runs delegate room-id opts))))

(deftest cleanup-witness-write-failure-never-replays-the-successful-hook
  (doseq [stage [:core :adapter] committed? [false true]]
    (let [fail? (atom true) attr (get store/job-cleanup-attributes stage)]
      (with-room
        #(fault-ack-store % attr fail? committed?)
        (fn [room]
          (let [core (atom 0) adapter (atom 0)
                job (jobs/start! room {:kind :workflow} (fn [_] (ready room))
                                 :before-finish (fn [& _] (swap! core inc))
                                 :settled (fn [& _] (swap! adapter inc)))
                id (:id job)]
            (try
              (let [expected (held! id)]
                (is (= (if (= stage :core) :core-ack :adapter-ack) (:stage expected)))
                (is (= 1 @core))
                (is (= (if (= stage :core) 0 1) @adapter))
                (is (active? room id))
                (is (false? (:settled? (jobs/observe-settlement room id))))
                (is (= committed? (store/job-cleanup-acknowledged? (run/run room id) stage)))
                (when-not committed?
                  (is (thrown? clojure.lang.ExceptionInfo (jobs/recover! id expected :acknowledge)))
                  (is (= (:stage expected) (:stage (jobs/recovery-state id)))))
                (reset! fail? false)
                (is (:settled? (jobs/recover! id (jobs/recovery-state id) :retry)))
                (is (= [1 1] [@core @adapter]))
                (is (store/job-cleanup-acknowledged? (run/run room id) :core))
                (is (store/job-cleanup-acknowledged? (run/run room id) :adapter))
                (is (:settled? (jobs/observe-settlement room id))))
              (finally (reset! fail? false) (recover-held! id)))))))))

(deftest cleanup-witness-cache-repair-preserves-concurrent-cancellation
  (with-room
    (fn [room]
      (let [id (:run/id (run/start! room :test (random-uuid) nil {:kind :workflow}))
            state (:state (:store room)) key (Object.)]
        (try
          (add-watch state key
                     (fn [_ _ before after]
                       (let [path [:runs (store/conversation-id room) id :run/job-core-cleanup-ack]]
                         (when (and (nil? (get-in before path)) (get-in after path))
                           (run/cancel-run! id)))))
          (run/acknowledge-job-cleanup! room id :core)
          (is (store/job-cleanup-acknowledged? (run/run room id) :core))
          (is (= :cancelling (:run/status (first (filter #(= id (:run/id %)) (run/active-runs (:id room)))))))
          (finally (remove-watch state key) (run/finish! id :cancelled)))))))

(deftest job-status-uses-one-durable-snapshot-for-outcome-and-settlement
  (with-room
    (fn [room]
      (let [job (jobs/start! room {:kind :workflow} (fn [_] (ready room))) id (:id job)]
        (is (:settled? (jobs/status id 5000)))
        (let [terminal (run/run room id)
              earlier (-> terminal (assoc :run/status :running) (dissoc :run/ended-at :run/job-adapter-cleanup-ack))
              reads (atom 0)]
          (with-redefs [jobs/job-room (constantly room)
                        run/run (fn [& _] (if (= 1 (swap! reads inc)) earlier terminal))]
            (let [status (jobs/status id)]
              (is (= :running (:status status)))
              (is (false? (:settled? status)))
              (is (= 1 @reads)))))))))

(deftest failed-core-cleanup-retains-owner-and-explicit-recovery-does-not-replay-work
  (with-room
    (fn [room]
      (let [work (atom 0) core (atom 0) settled (atom 0)
            job (jobs/start! room {:kind :workflow}
                             (fn [_] (swap! work inc) (ready room))
                             :before-finish (fn [& _] (when (= 1 (swap! core inc)) (throw (ex-info "Core cleanup uncertain" {}))))
                             :settled (fn [& _] (swap! settled inc)))]
        (try
          (let [expected (held! (:id job))]
            (is (= :before-finish (:stage expected)))
            (is (true? (:cleanup-required? (jobs/status (:id job)))))
            (is (false? (:settled? (jobs/status (:id job)))))
            (is (active? room (:id job)))
            (is (zero? @settled))
            (is (:settled? (jobs/recover! (:id job) expected :retry)))
            (is (= [1 2 1] [@work @core @settled]))
            (is (not (active? room (:id job))))
            (is (thrown? clojure.lang.ExceptionInfo (jobs/recover! (:id job) expected :acknowledge))))
          (finally (recover-held! (:id job))))))))

(deftest failed-adapter-retirement-preserves-terminal-outcome-and-successful-core-ack
  (with-room
    (fn [room]
      (let [core (atom 0) adapter (atom 0)
            job (jobs/start! room {:kind :experiment} (fn [_] (ready room))
                             :before-finish (fn [& _] (swap! core inc))
                             :settled (fn [& _] (when (= 1 (swap! adapter inc)) (throw (ex-info "Adapter cleanup uncertain" {})))))]
        (try
          (let [expected (held! (:id job))
                status (jobs/status (:id job))]
            (is (= :settled (:stage expected)))
            (is (= :completed (:status status)))
            (is (false? (:settled? status)))
            (is (active? room (:id job)))
            (is (:settled? (jobs/recover! (:id job) expected :retry)))
            (is (= [1 2] [@core @adapter]))
            (is (= :completed (:status (jobs/status (:id job))))))
          (finally (recover-held! (:id job))))))))

(deftest synchronous-construction-failure-uses-the-same-held-cleanup-owner
  (with-room
    (fn [room]
      (let [id (atom nil) core (atom 0) retired (atom 0)]
        (try
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Construction failed"
                                (jobs/start! room {:kind :workflow}
                                             (fn [job] (reset! id job) (throw (ex-info "Construction failed" {})))
                                             :before-finish (fn [& _] (when (= 1 (swap! core inc)) (throw (ex-info "Core uncertain" {}))))
                                             :settled (fn [& _] (swap! retired inc)))))
          (let [expected (held! @id)]
            (is (active? room @id))
            (is (:settled? (jobs/recover! @id expected :retry)))
            (is (= :failed (:status (jobs/status @id))))
            (is (= [2 1] [@core @retired])))
          (finally (when @id (recover-held! @id))))))))

(deftest lost-terminal-write-ack-reuses-the-original-end-and-never-repeats-core-cleanup
  (with-room
    (fn [room]
      (let [native run/retain-finished! failed? (atom false) core (atom 0)
            job (with-redefs [run/retain-finished! (fn [& args]
                                                     (let [result (apply native args)]
                                                       (when (compare-and-set! failed? false true)
                                                         (throw (ex-info "Lost terminal ACK" {})))
                                                       result))]
                  (let [job (jobs/start! room {:kind :workflow} (fn [_] (ready room))
                                         :before-finish (fn [& _] (swap! core inc)))]
                    (held! (:id job)) job))]
        (try
          (let [before (jobs/status (:id job)) expected (jobs/recovery-state (:id job))]
            (is (= :persist (:stage expected)))
            (is (= :completed (:status before)))
            (is (active? room (:id job)))
            (is (:settled? (jobs/recover! (:id job) expected :retry)))
            (is (= (:finished-at before) (:finished-at (jobs/status (:id job)))))
            (is (= 1 @core)))
          (finally (recover-held! (:id job))))))))

(deftest adapter-can-close-work-context-before-control-context-releases-the-run
  (with-room
    (fn [room]
      (let [work-context (context/create-execution-context) observed (atom [])
            job (jobs/start! room {:kind :workflow :ctx work-context}
                             (fn [_] (binding [ec/*execution-context* work-context] (sp/spin :done)))
                             :before-finish (fn [& _] (swap! observed conj (identical? (:ctx room) ec/*execution-context*)))
                             :settled (fn [& _]
                                        (swap! observed conj (identical? (:ctx room) ec/*execution-context*))
                                        (context/close-context! work-context)))]
        (try
          (let [status (jobs/status (:id job) 5000)]
            (is (= :completed (:status status)))
            (is (:settled? status))
            (is (= [true true] @observed))
            (is (not (active? room (:id job)))))
          (finally (recover-held! (:id job)) (context/close-context! work-context)))))))

(defn- room-close-failure [stage]
  (with-room
    (fn [room]
      (let [attempts (atom 0)
            cleanup (fn [& _] (when (= 1 (swap! attempts inc)) (throw (ex-info "Cleanup held" {}))))
            job (jobs/start! room {:kind :workflow} (fn [_] (ready room))
                             :before-finish (when (= :before-finish stage) cleanup)
                             :settled (when (= :settled stage) cleanup))]
        (try
          (let [expected (held! (:id job))]
            (is (= :dvergr.discourse/run-drain-timeout
                   (try (d/close-room! room) nil (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))))
            (is (active? room (:id job)))
            (is (identical? room (registry/lookup (:id room))))
            (is (:settled? (jobs/recover! (:id job) expected :retry)))
            (is (nil? (d/close-room! room))))
          (finally (recover-held! (:id job))))))))

(deftest room-close-remains-recoverable-while-core-cleanup-is-held
  (room-close-failure :before-finish))

(deftest room-close-remains-recoverable-while-adapter-cleanup-is-held
  (room-close-failure :settled))

(deftest reconciled-core-cleanup-can-be-acknowledged-without-replaying-it
  (with-room
    (fn [room]
      (let [calls (atom 0)
            job (jobs/start! room {:kind :workflow} (fn [_] (ready room))
                             :before-finish (fn [& _] (swap! calls inc) (throw (ex-info "ACK was lost" {}))))]
        (try
          (let [expected (held! (:id job))]
           ;; HOST has independently reconciled the physical outcome. The
           ;; original callback intentionally remains unsafe to repeat.
            (is (:settled? (jobs/recover! (:id job) expected :acknowledge)))
            (is (= 1 @calls)))
          (finally
            (when-let [expected (jobs/recovery-state (:id job))]
              (jobs/recover! (:id job) expected :acknowledge))))))))

(deftest acknowledgement-cannot-invent-terminal-persistence
  (with-room
    (fn [room]
      (let [job (with-redefs [run/retain-finished! (fn [& _] (throw (ex-info "No terminal write" {})))]
                  (let [job (jobs/start! room {:kind :workflow} (fn [_] (ready room)))]
                    (held! (:id job)) job))]
        (try
          (let [expected (jobs/recovery-state (:id job))]
            (is (= :persist (:stage expected)))
            (is (thrown? clojure.lang.ExceptionInfo (jobs/recover! (:id job) expected :acknowledge)))
            (is (active? room (:id job)))
            (is (= :running (:status (jobs/status (:id job)))))
            (is (not= expected (jobs/recovery-state (:id job)))))
          (finally (recover-held! (:id job))))))))

(deftest acknowledgement-cannot-bypass-a-live-release-lease
  (with-room
    (fn [room]
      (let [job (with-redefs [run/release-finished! (fn [& _] (throw (ex-info "Release did not happen" {})))]
                  (let [job (jobs/start! room {:kind :workflow} (fn [_] (ready room)))]
                    (held! (:id job)) job))]
        (try
          (let [expected (jobs/recovery-state (:id job))]
            (is (= :release (:stage expected)))
            (is (thrown? clojure.lang.ExceptionInfo (jobs/recover! (:id job) expected :acknowledge)))
            (is (active? room (:id job)))
            (is (= :completed (:status (jobs/status (:id job))))))
          (finally (recover-held! (:id job))))))))

(deftest lost-release-ack-does-not-repeat-acknowledged-cleanup
  (with-room
    (fn [room]
      (let [native run/release-finished! core (atom 0) adapter (atom 0)
            job (with-redefs [run/release-finished! (fn [id] (native id) (throw (ex-info "Lost release ACK" {})))]
                  (let [job (jobs/start! room {:kind :workflow} (fn [_] (ready room))
                                         :before-finish (fn [& _] (swap! core inc))
                                         :settled (fn [& _] (swap! adapter inc)))]
                    (held! (:id job)) job))]
        (try
          (is (not (active? room (:id job))))
          (is (:settled? (jobs/recover! (:id job) (jobs/recovery-state (:id job)) :retry)))
          (is (= [1 1] [@core @adapter]))
          (finally (recover-held! (:id job))))))))

(deftest concurrent-and-stale-recovery-attempts-cannot-advance-an-owned-stage
  (with-room
    (fn [room]
      (let [calls (atom 0) entered (promise) release (promise) worker (atom nil)
            job (jobs/start! room {:kind :workflow} (fn [_] (ready room))
                             :before-finish (fn [& _]
                                              (case (swap! calls inc)
                                                1 (throw (ex-info "Original hold" {}))
                                                2 (do (deliver entered true) @release)
                                                nil)))]
        (try
          (let [expected (held! (:id job))]
            (reset! worker (future (jobs/recover! (:id job) expected :retry)))
            (is (true? (deref entered 5000 false)))
            (is (thrown? clojure.lang.ExceptionInfo (jobs/recover! (:id job) expected :acknowledge)))
            (deliver release true)
            (is (:settled? (deref @worker 5000 nil)))
            (is (= 2 @calls))
            (is (thrown? clojure.lang.ExceptionInfo (jobs/recover! (:id job) expected :retry))))
          (finally
            (deliver release true)
            (when @worker (deref @worker 5000 nil))
            (recover-held! (:id job))))))))

(deftest a-new-hold-invalidates-the-previous-token-and-public-data-excludes-private-state
  (with-room
    (fn [room]
      (let [calls (atom 0)
            job (jobs/start! room {:kind :workflow} (fn [_] (ready room))
                             :before-finish (fn [& _]
                                              (when (< (swap! calls inc) 3)
                                                (throw (ex-info "Private native error" {:private (Object.)})))))]
        (try
          (let [first-token (held! (:id job))]
            (is (= #{:stage :token} (set (keys first-token))))
            (is (false? (:settled? (jobs/recover! (:id job) first-token :retry))))
            (let [second-token (jobs/recovery-state (:id job)) status (jobs/status (:id job))]
              (is (not= first-token second-token))
              (is (not-any? #(contains? status %) [:state :outcome :token :before-finish :settled :callbacks]))
              (is (not (instance? Throwable (:error status))))
              (is (thrown? clojure.lang.ExceptionInfo (jobs/recover! (:id job) first-token :acknowledge)))
              (is (:settled? (jobs/recover! (:id job) second-token :retry)))))
          (finally (recover-held! (:id job))))))))
