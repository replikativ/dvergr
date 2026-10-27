(ns dvergr.adapters.live-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.discourse-support :as t]
            [dvergr.discourse :as discourse]
            [dvergr.participant :as participant]
            [dvergr.store :as store]
            [org.replikativ.spindel.effects.await :refer [await]]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.spin.combinators :as timers]
            [org.replikativ.spindel.world.scope :as scope]
            [dvergr.test-support :as base]
            [dvergr.room :as room]
            [dvergr.world :as world]
            [is.simm.partial-cps.sequence :as aseq]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.pubsub.mult :as mult]
            [org.replikativ.spindel.pubsub.buffer :as buffer]
            [org.replikativ.spindel.spin.sync :as sync]))

(defn mult-completion! [iterations]
  (base/with-installation
    (fn [i]
      (let [root (world/create! i (base/assembly))]
        (binding [ec/*execution-context* (:context root)]
          (dotimes [_ iterations]
            (let [mailbox (sync/mailbox) end (Object.)
                  m (mult/mult (room/finite-source mailbox end))
                  blocked (mult/tap m (buffer/sliding-buffer 1))
                  unread (mult/tap m (buffer/sliding-buffer 1))
                  _ (mult/start! m)
                  pump (mult/mult-pump m)]
              (sync/post! mailbox :one)
              (sync/post! mailbox :two)
              (sync/post! mailbox end)
              (mult/untap m blocked)
              (is (nil? (t/await! pump)))
              (is (true? (mult/mult-closed? m)))
              (is (true? (mult/tap-closed? unread)))
              (is (= :two (first (t/await! (aseq/anext unread)))))
              (is (nil? (t/await! (aseq/anext unread)))))))))))

(deftest d2-d3-mult-completes-with-unread-and-saturated-sliding-taps
  "D2/D3 adapter prerequisite: exhaustion joins the running pump, including concurrent untap and saturated unread sliding buffers."
  (mult-completion! 32))

(defn eventually [f]
  (loop [n 200]
    (if (f) true
        (if (zero? n) false (do (Thread/sleep 5) (recur (dec n)))))))

(defn request [target deadline]
  {:id (random-uuid) :target target :deadline deadline
   :message (assoc (t/message) :type :conversation/request :recipients {:participants #{target}})})

(deftest d2-d3-room-topology-publishes-and-physically-closes
  "D2/D3, finding 1: room owns a mailbox and running mult; post reaches every addressed tap; closure joins its exhausted pump."
  (t/with-room
    (fn [i r v]
      (let [a (t/author i r) target (random-uuid) calls (atom [])
            s (participant/join! i v (t/author i r target)
                                 {:handler (fn [_ e _] (swap! calls conj (:message/id e)) nil)})
            o (discourse/observe! i v a {:broadcast? true})
            msg (t/message)]
        (is (= :offered (:live-publication (discourse/post! i v a msg))))
        (is (= (:id msg) (:message/id (first (t/await! (aseq/anext (:stream o)))))))
        (is (eventually #(= [(:id msg)] @calls)))
        (is (= :existing (:persistence (discourse/post! i v a msg))))
        (is (= {:status :closed} (t/await! (participant/leave! i s))))
        (is (= {:status :closed} (t/await! (room/close! i r))))
        (is (mult/mult-closed? (:mult r)))
        (is (empty? @(get-in r [:owner :spins])))
        (is (empty? @(get-in r [:owner :observers])))
        (is (identical? (room/close! i r) (room/close! i r)))))))

(deftest d3-host-cancel-notifies-and-cleans-registration
  "D3, finding 5: ordinary host cancellation commits once, wakes the original result and cleans deadline ownership without a selected context."
  (t/with-room
    (fn [i r v]
      (let [target (random-uuid) a (t/author i r)
            s (participant/join! i v (t/author i r target) {:handler (fn [& _] nil)})
            req (request target (+ (System/currentTimeMillis) 60000))
            asked (discourse/ask i v a req)
            winner (binding [ec/*execution-context* nil] (discourse/cancel! i (:owner asked) :host))]
        (is (= {:status :cancelled :reason :host} winner))
        (is (= winner (t/await! (:result asked))))
        (is (= winner (discourse/cancel! i (:owner asked) :again)))
        (let [prepare room/prepare!]
          (with-redefs [room/prepare! (fn [& args]
                                        (let [launch (apply prepare args)]
                                          (fn []
                                            (is (= winner (try (discourse/cancel! i (:owner asked) :during-retry)
                                                               (catch Exception e (ex-data e)))))
                                            (launch))))]
            (is (= winner (t/await! (:result (discourse/ask i v a req)))))))
        (is (empty? @(get-in r [:owner :asks])))
        (is (= {:status :closed} (t/await! (participant/leave! i s))))))))

(deftest d3-leave-and-close-retry-storage-cleanup
  "D3, finding 6: a failed session-ending write fences admission but later leave and close retry cleanup; recovery consumes ending evidence."
  (t/with-room
    (fn [i r v]
      (let [target (random-uuid) a (t/author i r)
            s (participant/join! i v (t/author i r target) {:handler (fn [& _] nil)})
            asked (discourse/ask i v a (request target (+ (System/currentTimeMillis) 60000)))
            original store/end-session!]
        (with-redefs [store/end-session! (fn [& _] (throw (ex-info "offline" {:dvergr/error :storage-failure})))]
          (is (= :storage-failure (base/error-code #(room/close! i r)))))
        (is (= :closed (base/error-code #(discourse/post! i v a (t/message)))))
        ;; First persist ending evidence, then simulate failure of its terminal write.
        (with-redefs [store/terminal! (fn [& _] (throw (ex-info "offline" {:dvergr/error :storage-failure})))]
          (is (= :storage-failure (base/error-code #(participant/leave! i s)))))
        (is (store/session-ended? (:control i) (:id s)))
        (is (= [{:status :leave :reason :participant-left}] (:reconciled (discourse/recover! i r))))
        (is (= {:status :leave :reason :participant-left} (t/await! (:result asked))))
        (is (= {:status :closed} (t/await! (room/close! i r))))))))

(deftest d3-timeout-storage-failure-keeps-progress-and-live-recovery-notifies
  "D3, finding 7: failed timeout commits retry; recovery of an active overdue ask uses notification and timer cleanup."
  (t/with-room
    (fn [i r v]
      (let [target (random-uuid) a (t/author i r)
            s (participant/join! i v (t/author i r target) {:handler (fn [& _] nil)})
            attempted (promise) original store/terminal! req (request target (+ (System/currentTimeMillis) 50))
            asked (with-redefs [store/terminal! (fn [& args]
                                                  (if (= :timeout (:status (last args)))
                                                    (do (deliver attempted true)
                                                        (throw (ex-info "offline" {:dvergr/error :storage-failure})))
                                                    (apply original args)))]
                    (let [asked (discourse/ask i v a req)]
                      (is (= true (deref attempted 5000 :timeout)))
                      (is (= :pending (:status (discourse/resolution (:control i) (:id asked)))))
                      (is (identical? (:result asked) (:result (discourse/ask i v a req))))
                      asked))]
        (is (= {:status :timeout} (t/await! (:result asked))))
        (is (empty? @(get-in r [:owner :asks])))
        (let [asked (discourse/ask i v a (request target (+ (System/currentTimeMillis) 60000)))]
          (with-redefs [org.replikativ.spindel.engine.impl.delayed/current-time (fn [_] Long/MAX_VALUE)]
            (discourse/recover! i r))
          (is (= {:status :timeout} (t/await! (:result asked))))
          (is (empty? @(get-in r [:owner :asks]))))
        (t/await! (participant/leave! i s))))))

(deftest d3-async-handler-failure-is-a-durable-error-reply
  "D3, finding 8: failure after a parked await produces a sanitized durable reply rather than a leave outcome."
  (t/with-room
    (fn [i r v]
      (let [target (random-uuid) a (t/author i r) gate (sync/deferred)
            s (participant/join! i v (t/author i r target)
                                 {:handler (fn [& _] (spin (await gate) (throw (Exception. "secret"))))})
            asked (discourse/ask i v a (request target (+ (System/currentTimeMillis) 60000)))]
        (is (eventually #(some? @(:child s))))
        (sync/deliver! gate true)
        (let [outcome (t/await! (:result asked))
              reply (room/work! i v :discourse/read {:command :message :id (:message/id outcome)})]
          (is (= :reply (:status outcome)))
          (is (= :conversation/error (:type reply)))
          (is (= {:error :handler-failed} (:body reply)))
          (is (= (:id asked) (:ask/id reply))))
        (t/await! (participant/leave! i s))))))

(defn- signalled-buffer [b signal]
  (reify buffer/PBuffer
    (full? [_] (buffer/full? b))
    (add! [this item] (buffer/add! b item) (deliver signal true) this)
    (remove! [_] (buffer/remove! b))
    (close-buf! [_] (buffer/close-buf! b))
    buffer/IUnblocking
    clojure.lang.Counted
    (count [_] (count b))))

(deftest d2-handler-starts-outside-installation-lock-with-buffered-delivery
  "D2, finding 9: force a buffered envelope before consumer startup; the handler can synchronously coordinate with another thread needing the lifecycle lock."
  (t/with-room
    (fn [i r v]
      (let [a (t/author i r) called (promise) prepare room/prepare! first? (atom true)
            buffered (promise) sliding buffer/sliding-buffer]
        (let [s (with-redefs [buffer/sliding-buffer (fn [n] (signalled-buffer (sliding n) buffered))
                              room/prepare! (fn [& args]
                                              (let [launch (apply prepare args)]
                                                (if (compare-and-set! first? true false)
                                                  (fn []
                                                    (discourse/post! i v a (t/message))
                                                    (is (= true (deref buffered 5000 :timeout)))
                                                    (launch)) launch)))]
                  (participant/join! i v (t/author i r)
                                     {:handler (fn [& _]
                                                 (let [unlocked (future (locking (:lock i) true))]
                                                   (deliver called [(Thread/holdsLock (:lock i))
                                                                    (deref unlocked 2000 :blocked)])) nil)}))]
          (is (= [false true] (deref called 5000 :timeout)))
          (t/await! (participant/leave! i s)))))))

(deftest d3-async-cleanup-retains-physical-ownership
  "D3, finding 2: cancellation of a returned Spin cannot complete leave, scope quiescence or world closure before its nested body cleanup exits."
  (t/with-room
    (fn [i r v]
      (let [a (t/author i r) entered (promise) cleanup (promise) release (promise) exited (promise)
            gate (sync/deferred)
            s (participant/join! i v (t/author i r)
                                 {:handler (fn [& _]
                                             (spin (await (spin (try (deliver entered true) (await gate)
                                                                     (finally (deliver cleanup true) @release
                                                                              (deliver exited true)))))))})
            closed (promise)]
        (discourse/post! i v a (t/message))
        (is (= true (deref entered 5000 :timeout)))
        ((:completion s) #(deliver closed %) #(deliver closed %))
        (let [leaving (future (participant/leave! i s))]
          (is (= true (deref cleanup 5000 :timeout)))
          (is (not (realized? exited)))
          (is (= :waiting (deref closed 50 :waiting)))
          (is (= :world-busy (base/error-code #(world/close! i (:world r)))))
          (let [q (promise)]
            ((scope/await-quiescence (get-in r [:owner :scope])) #(deliver q true) #(deliver q %))
            (is (= :waiting (deref q 50 :waiting))))
          (deliver release true)
          (is (not= :timeout (deref leaving 5000 :timeout)))
          (is (= {:status :closed} (deref closed 5000 :timeout)))
          (is (realized? exited)))))))

(deftest ^:pending-upstream d3-concurrent-nested-cleanup-precedes-closure
  "D3 requires Spindel: cancellation unwinding keeps the owner live, Part 4 T7/T11. A cancelled running descendant needs a public join before leave/close may report closed."
  (t/with-room
    (fn [i r v]
      (let [resume (promise) entered (promise) cleanup (promise) release (promise) exited (promise)
            gate (fn [resolve _] (deliver resume resolve))
            s (participant/join! i v (t/author i r)
                                 {:handler (fn [& _]
                                             (spin (await (spin (try (deliver entered true) (await gate)
                                                                     (finally (deliver cleanup true) @release
                                                                              (deliver exited true)))))))})
            left (promise) closed (promise)]
        (try
          (discourse/post! i v (t/author i r) (t/message))
          (is (= true (deref entered 5000 :timeout)))
          (let [callback (deref resume 5000 nil)
                resuming (future (callback true))]
            (is (= true (deref cleanup 5000 :timeout)))
            (is (not (realized? exited)))
            ((participant/leave! i s) #(deliver left %) #(deliver left %))
            ((room/close! i r) #(deliver closed %) #(deliver closed %))
            (is (= :waiting (deref left 100 :waiting)))
            (is (= :waiting (deref closed 100 :waiting)))
            (deliver release true)
            (is (not= :timeout (deref resuming 5000 :timeout)))
            (is (= true (deref exited 5000 :timeout)))
            (is (= {:status :closed} (deref left 5000 :timeout)))
            (is (= {:status :closed} (deref closed 5000 :timeout))))
          (finally (deliver release true)))))))

(deftest d2-d3-request-cannot-cross-session-incarnations
  "D2/D3: an ask paused after registration cannot enter a replacement handler or claim its seen set; durable leave wins."
  (t/with-room
    (fn [i r v]
      (let [target (random-uuid) a (t/author i r)
            s (participant/join! i v (t/author i r target) {:handler (fn [& _] nil)})
            registered (promise) publish (promise) prepare room/prepare!
            req (request target (+ (System/currentTimeMillis) 60000))
            calls (atom []) replacement (atom nil)]
        (try
          (let [asking (with-redefs [room/prepare! (fn [& args]
                                                     (let [launch (apply prepare args)]
                                                       (fn []
                                                         (deliver registered true)
                                                         @publish
                                                         (launch))))]
                         (let [asking (future (discourse/ask i v a req))]
                           (is (= true (deref registered 5000 :timeout)))
                           (t/await! (participant/leave! i s))
                           ;; Restore prepare! before joining the replacement.
                           (with-redefs [room/prepare! prepare]
                             (reset! replacement
                                     (participant/join! i v (t/author i r target)
                                                        {:handler (fn [_ e _] (swap! calls conj (:message/id e)) nil)})))
                           (deliver publish true)
                           (deref asking 5000 nil)))
                envelope (get-in asking [:post :envelope])
                marker (assoc (t/message) :ask/id (:id req) :reply-to (get-in req [:message :id])
                              :recipients {:participants #{target}})]
            (is (some? asking))
            (is (= {:status :leave :reason :participant-left} (t/await! (:result asking))))
            (is (= :leave (:status (discourse/resolution (:control i) (:id req)))))
            (is (= :not-offered (get-in asking [:post :live-publication])))
            ;; A delayed transport copy must also be fenced at consumption.
            (sync/post! (:mailbox r) envelope)
            (discourse/post! i v a marker)
            (is (eventually #(some #{(:id marker)} @calls)))
            (is (= [(:id marker)] @calls))
            (is (not (contains? @(:seen @replacement) (get-in req [:message :id])))))
          (finally
            (deliver publish true)
            (when @replacement (t/await! (participant/leave! i @replacement)))))))))

(deftest d3-owned-spawn-keeps-scope-busy-while-body-is-parked
  "D3/A2: explicit public activity lease prevents premature scope quiescence while a spawned body is parked."
  (t/with-room
    (fn [i r _]
      (let [gate (sync/deferred) entered (promise) q (promise)]
        (room/start! i r (spin (deliver entered true) (await gate)) (fn [_]) (fn [_]))
        (is (= true (deref entered 5000 :timeout)))
        (let [closing (room/close! i r)]
          (closing #(deliver q %) #(deliver q %))
          (is (= :waiting (deref q 50 :waiting)))
          (is (= :world-busy (base/error-code #(world/close! i (:world r)))))
          (sync/deliver! gate true)
          (is (= {:status :closed} (deref q 5000 :timeout))))))))

(deftest d3-noncooperating-handler-keeps-room-closure-incomplete
  "D3: a returned body doing noncooperative host work retains its lease until actual exit."
  (t/with-room
    (fn [i r v]
      (let [entered (promise) release (promise) exited (promise)
            _ (participant/join! i v (t/author i r)
                                 {:handler (fn [& _] (spin (deliver entered true) @release (deliver exited true) nil))})]
        (try
          (discourse/post! i v (t/author i r) (t/message))
          (is (= true (deref entered 5000 :timeout)))
          (let [closed (promise) closing (room/close! i r)]
            (closing #(deliver closed %) #(deliver closed %))
            (is (= :waiting (deref closed 50 :waiting)))
            (is (= :world-busy (base/error-code #(world/close! i (:world r)))))
            (deliver release true)
            (is (= {:status :closed} (deref closed 5000 :timeout)))
            (is (realized? exited)))
          (finally (deliver release true)))))))

(deftest d3-suppression-missing-target-late-replies-and-selected-world-refusals
  "D3/W1: missing target leaves, explicit suppression times out, late replies stay in history, and a reply never switches an unrelated or missing caller world."
  (t/with-room
    (fn [i r v]
      (let [a (t/author i r) target (random-uuid) obligation (promise)
            s (participant/join! i v (t/author i r target)
                                 {:handler (fn [context _ o] (when o (participant/suppress! i (:session context) o)
                                                                   (deliver obligation o)) nil)})
            asked (discourse/ask i v a (request target (+ (System/currentTimeMillis) 100)))
            o (deref obligation 5000 nil)]
        (is (some? o))
        (is (= {:status :timeout} (t/await! (:result asked))))
        (let [unrelated (world/create! i (base/assembly))]
          (doseq [[ctx code] [[nil :no-selected-world] [(:context unrelated) :unrelated-lineage]]]
            (binding [ec/*execution-context* ctx]
              (is (= code (base/error-code #(participant/reply! i s o (t/message))))))))
        (is (= :invalid-reply (base/error-code #(participant/reply! i s o (assoc (t/message) :reply-to (random-uuid))))))
        (let [reply (participant/reply! i s o (t/message))]
          (is (= :inserted (:persistence reply)))
          (is (= :timeout (:status (discourse/resolution (:control i) (:id asked)))))
          (is (= 2 (count (:messages (discourse/history i v {}))))))
        (let [missing (discourse/ask i v a (request (random-uuid) (+ (System/currentTimeMillis) 60000)))]
          (is (= {:status :leave :reason :missing-participant} (t/await! (:result missing)))))
        (t/await! (participant/leave! i s))
        (is (= :closed (base/error-code #(participant/reply! i s o (t/message)))))))))

(deftest d2-multicast-matching-and-raw-history-replay
  "D2: two addressed participants both receive, a third tag subscriber cannot bypass recipients, and raw history replay never enters handlers."
  (t/with-room
    (fn [i r v]
      (let [targets (vec (repeatedly 3 random-uuid)) calls (atom [0 0 0])
            sessions (mapv (fn [n target]
                             (participant/join! i v (t/author i r target)
                                                {:handler (fn [& _] (swap! calls update n inc) nil)}))
                           (range 3) targets)
            a (t/author i r)
            observer (discourse/observe! i v a {:broadcast? true})]
        (doseq [s sessions] (participant/subscribe! i s {:tags #{:test}}))
        (discourse/post! i v a (assoc (t/message) :recipients {:participants (set (take 2 targets))}))
        (is (eventually #(= [1 1 0] @calls)))
        (let [message (t/message)]
          (discourse/post! i v a message)
          (is (= (:id message) (:message/id (first (t/await! (aseq/anext (:stream observer)))))))
          (is (eventually #(= [2 2 1] @calls)))
          (let [one (discourse/history i v {:limit 1}) replay (discourse/history i v {:limit 1})]
            (is (= one replay))
            (is (= 2 (count (:messages (discourse/history i v {})))))))
        (doseq [s sessions] (t/await! (participant/leave! i s)))
        (is (= [2 2 1] @calls))
        (is (= {:status :closed} (t/await! (binding [ec/*execution-context* nil]
                                             (discourse/unobserve! i observer)))))))))

(deftest d3-decide-retains-validated-binding-version
  "D3: decide resolves one projection/system binding and snapshots its version; editing the decision cannot redirect an installed ask."
  (t/with-room
    (fn [i r v]
      (let [target (random-uuid) a (t/author i r) decision (random-uuid)
            s (participant/join! i v (t/author i r target) {:handler (fn [& _] nil)})]
        (room/work! i v :discourse/write {:command :decision :record {:decision/id decision :projection/id (:projection/id v)
                                                                      :participant/id target :version 0}})
        (let [h (participant/decision-handle i v decision)
              asked (participant/decide i v a h (dissoc (request target (+ (System/currentTimeMillis) 60000)) :target))]
          (participant/set-decider! i h a {:expected-version 0 :participant-id (random-uuid)})
          (let [intent (:ask/intent (store/ask-record (:control i) (:id asked)))]
            (is (= target (:target intent)))
            (is (= (:id s) (:session intent)))
            (is (= {:decision/id decision :version 0} (:decision intent))))
          (let [branch (discourse/branch! i v {:id (random-uuid)})]
            (is (= :operation-denied
                   (base/error-code #(participant/decide i branch a (assoc h :projection/id (:projection/id branch))
                                                         (dissoc (request target (+ (System/currentTimeMillis) 60000)) :target)))))))
        (t/await! (participant/leave! i s))))))

(deftest d2-d3-consumer-storage-failure-ends-the-incarnation
  "D2/D3: a failed obligation read ends the consumer's session instead of leaving open membership without a delivery body."
  (t/with-room
    (fn [i r v]
      (let [s (participant/join! i v (t/author i r) {:handler (fn [& _] nil)})]
        (with-redefs [store/ask-record (fn [& _] (throw (ex-info "offline" {:dvergr/error :storage-failure})))]
          (discourse/post! i v (t/author i r) (assoc (t/message) :ask/id (random-uuid)))
          (is (= {:status :closed} (t/await! (:completion s)))))
        (is @(:closed s))
        (is (store/session-ended? (:control i) (:id s)))
        (is (empty? @(get-in r [:owner :sessions])))))))

(deftest d3-immediate-reply-and-deadline-eligibility-before-timeout-commit
  "D3: registration precedes immediate reply; a reply after deadline eligibility still wins before the timeout control commit."
  (t/with-room
    (fn [i r v]
      (let [a (t/author i r) target (random-uuid)
            s (participant/join! i v (t/author i r target) {:handler (fn [& _] (t/message))})
            asked (discourse/ask i v a (request target (+ (System/currentTimeMillis) 60000)))]
        (is (= :reply (:status (t/await! (:result asked)))))
        (t/await! (participant/leave! i s)))
      (let [a (t/author i r) target (random-uuid) obligation (promise) timer-gate (sync/deferred)
            s (participant/join! i v (t/author i r target) {:handler (fn [_ _ o] (when o (deliver obligation o)) nil)})
            asked (with-redefs [timers/sleep (fn [_] timer-gate)]
                    (let [asked (discourse/ask i v a (request target (dec (System/currentTimeMillis))))]
                      (is (not= :timeout (deref obligation 5000 :timeout)))
                      (let [reply (participant/reply! i s @obligation (t/message))]
                        (is (= {:status :reply :message/id (get-in reply [:envelope :message/id])}
                               (t/await! (:result asked))))) asked))]
        (sync/deliver! timer-gate true)
        (is (= :reply (:status (discourse/resolution (:control i) (:id asked)))))
        (t/await! (participant/leave! i s))))))
