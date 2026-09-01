(ns dvergr.agent.run-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.run :as run]
            [dvergr.chat.context :as chat-ctx]
            [dvergr.discourse :as d]
            [dvergr.room.store :as store]
            [dvergr.room.store.memory :as memory]))

(defn- run-room [id]
  (let [st (memory/make)]
    [(d/make-room {:id id :store st}) st]))

(defn- live-ctx []
  (chat-ctx/create-chat-context {:budget-dollars 0.01 :with-sci? false}))

(defn- controlled-run-store [delegate receipts?]
  (reify store/PRoomStore
    (-store-room! [_ room-id metadata]
      (store/-store-room! delegate room-id metadata))
    (-load-room [_ id-or-slug]
      (store/-load-room delegate id-or-slug))
    (-delete-room! [_ room-id]
      (store/-delete-room! delegate room-id))
    (-list-rooms [_]
      (store/-list-rooms delegate))
    (-store-message! [_ room-id message]
      (store/-store-message! delegate room-id message))
    (-message-thread-root [_ room-id message-id]
      (store/-message-thread-root delegate room-id message-id))
    (-list-messages [_ room-id opts]
      (store/-list-messages delegate room-id opts))
    (-store-run! [_ room-id run]
      (when @receipts?
        (store/-store-run! delegate room-id run)))
    (-load-run [_ room-id run-id]
      (store/-load-run delegate room-id run-id))
    (-list-runs [_ room-id opts]
      (store/-list-runs delegate room-id opts))))

(deftest lifecycle-is-durable-observable-and-handle-free
  (let [[room st] (run-room :run-lifecycle)
        trigger (d/message :alice :researcher "investigate")
        cctx (live-ctx)
        events (atom [])
        watch-key (random-uuid)]
    (try
      (run/watch-runs! watch-key #(swap! events conj %))
      (let [started (run/start! room :researcher trigger cctx
                                {:id (random-uuid)
                                 :now (java.util.Date. 1000)})
            run-id (:run/id started)]
        (is (= :runs/snapshot (:type (first @events))))
        (is (= :run/started (:type (second @events))))
        (is (= [started] (run/active-runs :run-lifecycle)))
        (is (not-any? #(contains? started %)
                      [:chat-ctx :sci-ctx :spindel-ctx :cancel!]))
        (is (= started (store/-load-run st :run-lifecycle run-id)))
        (let [finished (run/finish! run-id :completed
                                    {:now (java.util.Date. 2000)})]
          (is (= :completed (:run/status finished)))
          (is (= (java.util.Date. 2000) (:run/ended-at finished)))
          (is (empty? (run/active-runs :run-lifecycle)))
          (is (= finished (store/-load-run st :run-lifecycle run-id)))
          (is (= :run/finished (:type (last @events))))))
      (finally
        (run/unwatch-runs! watch-key)
        (d/close-room! room)))))

(deftest targeted-cancel-does-not-touch-a-peer-run
  (let [[room _st] (run-room :targeted-cancel)
        a-ctx (live-ctx)
        b-ctx (live-ctx)
        a (run/start! room :a (d/message :alice :a "a") a-ctx)
        b (run/start! room :b (d/message :alice :b "b") b-ctx)]
    (try
      (is (true? (run/cancel-run! (:run/id a))))
      (is (= :active (chat-ctx/get-status a-ctx))
          "targeted cancellation does not poison the reusable ChatContext")
      (is (= :active (chat-ctx/get-status b-ctx)))
      (is (= :cancelling (:run/status
                          (first (filter #(= (:run/id a) (:run/id %))
                                         (run/active-runs :targeted-cancel))))))
      (is (false? (run/cancel-run! (random-uuid))))
      (finally
        (run/finish! (:run/id a) :cancelled)
        (run/finish! (:run/id b) :completed)
        (d/close-room! room)))))

(deftest room-wide-cancel-and-old-turn-api-remain-compatible
  (testing "room cancellation signals every run but no other room"
    (let [a (live-ctx)
          b (live-ctx)
          other (live-ctx)
          a-id (run/register-live! :same-room :a a)
          b-id (run/register-live! :same-room :b b)
          other-id (run/register-live! :other-room :c other)]
      (try
        (is (= 2 (run/cancel-room-runs! :same-room)))
        (is (run/cancel-requested? a-id))
        (is (run/cancel-requested? b-id))
        (is (= :active (chat-ctx/get-status a)))
        (is (= :active (chat-ctx/get-status b)))
        (is (= :active (chat-ctx/get-status other)))
        (finally
          (run/unregister-live! a-id)
          (run/unregister-live! b-id)
          (run/unregister-live! other-id))))))

(deftest precise-unregister-does-not-remove-a-newer-run
  (let [old-id (run/register-live! :aba :agent (live-ctx))
        new-id (run/register-live! :aba :agent (live-ctx))]
    (try
      (run/unregister-live! old-id)
      (is (= [new-id] (mapv :run/id (run/active-runs :aba))))
      (finally
        (run/unregister-live! new-id)))))

(deftest parent-is-explicit-structure-not-message-causality
  (let [[room _st] (run-room :explicit-parent)
        causal-run-id (random-uuid)
        structural-parent-id (random-uuid)
        trigger (assoc (d/message :upstream :agent "handoff")
                       :metadata {:run-id causal-run-id})
        causal-only (run/start! room :agent trigger (live-ctx))
        explicit (run/start! room :agent trigger (live-ctx)
                             {:parent structural-parent-id})]
    (try
      (is (nil? (:run/parent causal-only))
          "the triggering message carries causality without becoming containment")
      (is (= structural-parent-id (:run/parent explicit)))
      (finally
        (run/finish! (:run/id causal-only) :completed)
        (run/finish! (:run/id explicit) :completed)
        (d/close-room! room)))))

(deftest resolved-run-inputs-are-durable-causality-not-structure
  (let [[room st] (run-room :explicit-causality)
        parent (run/start! room :orchestrator (random-uuid) (live-ctx))
        child (run/start! room :specialist (random-uuid) (live-ctx)
                          {:parent (:run/id parent)})]
    (try
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"has not resolved"
           (run/record-cause! (:run/id parent) (:run/id child))))
      (run/finish! (:run/id child) :completed)
      (let [updated (run/record-cause! (:run/id parent) (:run/id child))]
        (is (= #{(:run/id child)} (:run/caused-by updated)))
        (is (= #{(:run/id child)}
               (:run/caused-by
                (store/-load-run st :explicit-causality (:run/id parent)))))
        (is (= (:run/id parent) (:run/parent child))
            "containment remains on the child, causality on the consumer"))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"not durable"
           (run/record-cause! (:run/id parent) (random-uuid))))
      (finally
        (run/finish! (:run/id parent) :completed)
        (d/close-room! room)))))

(deftest storeless-runs-cannot-claim-durable-causality
  (let [room (d/make-room {:id :storeless-causality})
        parent (run/start! room :orchestrator (random-uuid) (live-ctx))]
    (try
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"requires a Room store"
           (run/record-cause! (:run/id parent) (random-uuid))))
      (is (nil? (:run/caused-by
                 (first (run/active-runs :storeless-causality)))))
      (finally
        (run/finish! (:run/id parent) :completed)
        (d/close-room! room)))))

(deftest provenance-cannot-override-core-run-identity
  (let [[room _st] (run-room :provenance-boundary)
        trigger (d/message :alice :agent "work")]
    (try
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"may not override causal identity"
           (run/start! room :agent trigger (live-ctx)
                       {:provenance {:run/room :other-room}})))
      (is (empty? (run/active-runs (:id room))))
      (finally
        (d/close-room! room)))))

(deftest closing-admission-is-a-run-start-fence
  (let [[room _st] (run-room :admission-fence)
        trigger (d/message :alice :agent "too late")]
    (try
      (is (empty? (run/close-room-admission! room)))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"admission is closed"
           (run/start! room :agent trigger (live-ctx))))
      (is (empty? (run/active-runs (:id room))))
      (finally
        (d/close-room! room)))))

(deftest recovery-reopens-only-the-exact-room-fence
  (let [[room _st] (run-room :admission-recovery)
        meta (:meta room)
        old-token (random-uuid)
        newer-token (random-uuid)
        trigger (d/message :alice :agent "work")]
    (try
      (run/close-room-admission! room)
      (swap! meta assoc :test/fence {:token newer-token})
      (is (nil?
           (run/recover-room-admission!
            room
            (fn []
              (locking meta
                (when (= old-token (get-in @meta [:test/fence :token]))
                  (swap! meta dissoc :test/fence)
                  true))))))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"admission is closed"
           (run/start! room :agent trigger (live-ctx)))
          "stale recovery cannot reopen admission beneath a newer fence")
      (is (true?
           (run/recover-room-admission!
            room
            (fn []
              (locking meta
                (when (= newer-token (get-in @meta [:test/fence :token]))
                  (swap! meta dissoc :test/fence)
                  true))))))
      (let [started (run/start! room :agent trigger (live-ctx))]
        (is (nil? (:test/fence @meta)))
        (run/finish! (:run/id started) :completed))
      (finally
        (d/close-room! room)))))

(deftest lifecycle-publication-requires-durable-receipts
  (let [base (memory/make)
        receipts? (atom false)
        st (controlled-run-store base receipts?)
        room (d/make-room {:id :durable-admission :store st})
        ctx (live-ctx)
        trigger (d/message :alice :agent "work")
        events (atom [])
        watch-key (random-uuid)]
    (try
      (run/watch-runs! watch-key #(swap! events conj %))
      (is (thrown? clojure.lang.ExceptionInfo
                   (run/start! room :agent trigger ctx)))
      (is (empty? (run/active-runs :durable-admission)))
      (is (= [:runs/snapshot] (mapv :type @events))
          "a non-durable start is never published")
      (reset! receipts? true)
      (let [started (run/start! room :agent trigger ctx)
            run-id (:run/id started)]
        (reset! receipts? false)
        (is (thrown? clojure.lang.ExceptionInfo
                     (run/finish! run-id :completed)))
        (is (= :running (:run/status (store/-load-run st :durable-admission run-id))))
        (is (= [run-id] (mapv :run/id (run/active-runs :durable-admission)))
            "a failed terminal write retains a retryable live projection")
        (is (not-any? #(= :run/finished (:type %)) @events))
        (reset! receipts? true)
        (run/finish! run-id :completed)
        (is (= :run/finished (:type (last @events)))))
      (finally
        (run/unwatch-runs! watch-key)
        (d/close-room! room)))))

(deftest retained-finish-keeps-the-drain-lease-until-publication
  (let [[room st] (run-room :retained-finish)
        started (run/start! room :agent (d/message :alice :agent "work") nil)
        run-id (:run/id started)
        cancelled (atom 0)]
    (try
      (run/register-cancel-hook! run-id :probe #(swap! cancelled inc))
      (let [finished (run/retain-finished! run-id :completed)]
        (is (= :completed (:run/status finished)))
        (is (= :completed (:run/status (store/-load-run st :retained-finish run-id))))
        (is (= [run-id] (mapv :run/id (run/active-runs :retained-finish)))
            "durable completion still owns its execution lease")
        (is (true? (run/cancel-run! run-id)))
        (is (zero? @cancelled)
            "teardown waits for publication instead of cancelling a finisher")
        (is (= :completed (:run/status (first (run/active-runs :retained-finish)))))
        (is (= finished (run/release-finished! run-id)))
        (is (empty? (run/active-runs :retained-finish))))
      (finally
        (run/finish! run-id :failed)
        (d/close-room! room)))))
