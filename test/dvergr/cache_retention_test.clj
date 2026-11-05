(ns dvergr.cache-retention-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [datahike.api :as d]
            [dvergr.chat.schema :as schema]
            [dvergr.chat.tool-schema :as tool-schema]
            [dvergr.agent.run :as run])
  (:import [java.lang.ref WeakReference]
           [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:dynamic *workers* nil)

(defmacro test-future [& body]
  `(let [work# (future ~@body)]
     (swap! *workers* conj work#)
     work#))

(use-fixtures :each
  (fn [f]
    (binding [*workers* (atom [])]
      (try (f)
           (finally
             (doseq [work @*workers* :when (not (realized? work))]
               (future-cancel work)))))))

(deftype MutableConnection [value]
  Object
  (hashCode [_] (hash @value))
  (equals [this other]
    (or (identical? this other)
        (and (instance? MutableConnection other)
             (= @value @(.-value ^MutableConnection other))))))

(defn- await! [^CountDownLatch latch]
  (when-not (.await latch 10 TimeUnit/SECONDS)
    (throw (ex-info "Test worker did not reach its checkpoint" {}))))

(defn- eventually [pred]
  (let [deadline (+ (System/nanoTime) 5000000000)]
    (loop []
      (cond (pred) true
            (> (System/nanoTime) deadline) false
            :else (do (Thread/sleep 5) (recur))))))

(defn- outcome [f]
  (try (f) :ok (catch Throwable e e)))

(deftest full-schema-memo-uses-stable-identity
  (let [calls (atom 0) state (atom :same)
        a (MutableConnection. state) b (MutableConnection. (atom :same))]
    (with-redefs [schema/install-schema! (fn [_] (swap! calls inc))
                  schema/get-tool-registry (constantly nil)]
      (schema/ensure-full-schema! a)
      (schema/ensure-full-schema! b)
      (reset! state :changed)
      (schema/ensure-full-schema! a)
      (is (= 2 @calls)))))

(deftest full-schema-publication-waits-for-complete-installation
  (let [conn (Object.) other (Object.) entered (CountDownLatch. 1)
        release (CountDownLatch. 1) follower-started (CountDownLatch. 1)
        follower-thread (promise)]
    (with-redefs [schema/install-schema! (fn [c] (when (identical? c conn)
                                                   (.countDown entered) (await! release)))
                  schema/get-tool-registry (constantly nil)]
      (let [owner (test-future (schema/ensure-full-schema! conn))]
        (try
          (await! entered)
          (let [follower (test-future (deliver follower-thread (Thread/currentThread))
                                      (.countDown follower-started)
                                      (schema/ensure-full-schema! conn))]
            (await! follower-started)
            (is (eventually #(= java.lang.Thread$State/WAITING (.getState ^Thread @follower-thread))))
            (is (not (realized? follower)))
            (let [independent (test-future (schema/ensure-full-schema! other))]
              (is (identical? other (deref independent 5000 ::timeout))))
            (.countDown release)
            (is (identical? conn (deref follower 5000 ::timeout))))
          (finally (.countDown release) (deref owner 10000 ::timeout)))))))

(deftest failed-schema-attempt-is-shared-and-retryable
  (let [conn (Object.) failure (ex-info "install failed" {}) calls (atom 0)
        entered (CountDownLatch. 1) release (CountDownLatch. 1) follower-thread (promise)]
    (with-redefs [schema/install-schema! (fn [_] (when (= 1 (swap! calls inc))
                                                   (.countDown entered) (await! release) (throw failure)))
                  schema/get-tool-registry (constantly nil)]
      (let [owner (test-future (outcome #(schema/ensure-full-schema! conn)))]
        (try
          (await! entered)
          (let [follower (test-future (deliver follower-thread (Thread/currentThread))
                                      (outcome #(schema/ensure-full-schema! conn)))]
            (is (eventually #(and (realized? follower-thread)
                                  (= java.lang.Thread$State/WAITING (.getState ^Thread @follower-thread)))))
            (.countDown release)
            (is (identical? failure (deref owner 5000 ::timeout)))
            (is (identical? failure (deref follower 5000 ::timeout))))
          (finally (.countDown release) (deref owner 10000 ::timeout))))
      (is (identical? conn (schema/ensure-full-schema! conn)))
      (is (= 2 @calls)))))

(deftest reentrant-schema-install-fails-and-can-retry
  (let [conn (Object.)]
    (with-redefs [schema/install-schema! (fn [c] (schema/ensure-full-schema! c))
                  schema/get-tool-registry (constantly nil)]
      (is (= :dvergr.chat.schema/reentrant-ensure
             (:type (ex-data (outcome #(schema/ensure-full-schema! conn)))))))
    (with-redefs [schema/install-schema! (fn [_]) schema/get-tool-registry (constantly nil)]
      (is (identical? conn (schema/ensure-full-schema! conn))))))

(defn- tool [name version] {:name name :parameters {:type "object" :version version}})

(deftest dynamic-schema-memo-keeps-identity-and-successful-fingerprints
  (let [a (MutableConnection. (atom :same)) b (MutableConnection. (atom :same))
        calls (atom []) fail? (atom false)
        ensure! (fn [conn name version]
                  (schema/ensure-tool-use-schemas! conn [{:tool-use/name name}]
                                                   {name (tool name version)}))]
    (with-redefs [tool-schema/install-tool-schema!
                  (fn [_ t] (swap! calls conj [(:name t) (get-in t [:parameters :version])])
                    (when @fail? (throw (ex-info "bad schema" {}))))]
      (ensure! a "a" 1) (ensure! b "a" 1) (ensure! a "a" 1)
      (is (= 2 (count @calls)))
      (reset! fail? true)
      (is (instance? Throwable (outcome #(ensure! a "b" 1))))
      (reset! fail? false)
      (ensure! a "a" 1)
      (is (= 3 (count @calls)) "successful a fingerprint survived b failure")
      (ensure! a "b" 1) (ensure! a "a" 2)
      (is (= 5 (count @calls))))))

(deftest weak-identity-key-collisions-and-delayed-cleanup
  (let [a (Object.) b (Object.) ra (WeakReference. a) rb (WeakReference. b)
        ka (schema/->ConnectionKey ra 7) kb (schema/->ConnectionKey rb 7)
        replacement (#'schema/connection-key a) cache (java.util.HashMap.)]
    (.put cache ka {:key ka :installed :a})
    (.put cache kb {:key kb :installed :b})
    (is (= 2 (.size cache)))
    (.clear ra) (.clear rb)
    (is (= ka ka)) (is (not= ka kb))
    (.put cache replacement {:key replacement :installed :replacement})
    (#'schema/clean-connection-key! cache ka)
    (is (= :replacement (:installed (.get cache replacement))))
    (java.lang.ref.Reference/reachabilityFence a)
    (java.lang.ref.Reference/reachabilityFence b)))

(deftest room-lock-handoff-and-delayed-cleanup
  (let [id (str (random-uuid)) equal-id (String. id)
        lock (#'run/room-lock id)]
    (is (identical? lock (#'run/room-lock equal-id)))
    (let [entry (.get ^java.util.Map @#'run/room-locks id)]
      (.clear ^WeakReference entry)
      (let [replacement (#'run/room-lock id)]
        (#'run/clean-room-lock! id entry)
        (is (not (identical? lock replacement)))
        (is (identical? replacement (#'run/room-lock equal-id)))))
    (java.lang.ref.Reference/reachabilityFence lock)))

(defn- discarded-cache-entries []
  (with-redefs [schema/install-schema! (fn [_]) schema/get-tool-registry (constantly nil)]
    (let [conn (Object.) id (random-uuid) lock (#'run/room-lock id)
          cache @#'schema/ensured-conns]
      (schema/ensure-full-schema! conn)
      {:conn (WeakReference. conn) :lock (WeakReference. lock) :room id
       :key (:key (.get ^java.util.Map cache (#'schema/connection-key conn)))})))

(deftest discarded-connection-and-lock-leave-no-cache-history
  (let [{:keys [conn lock room key]} (discarded-cache-entries)]
    (is (eventually #(do (System/gc)
                         (and (nil? (.get ^WeakReference conn))
                              (nil? (.get ^WeakReference lock))
                              (nil? (.get ^java.util.Map @#'run/room-locks room))
                              (locking @#'schema/ensured-conns
                                (not (.containsKey ^java.util.Map @#'schema/ensured-conns key)))))))))

(deftest different-tool-waiter-rechecks-after-an-unrelated-failure
  (let [conn (Object.) other (Object.) entered (CountDownLatch. 1)
        release (CountDownLatch. 1) follower-thread (promise) installed (atom [])
        ensure! (fn [c name]
                  (schema/ensure-tool-use-schemas! c [{:tool-use/name name}] {name (tool name 1)}))]
    (with-redefs [tool-schema/install-tool-schema!
                  (fn [c t]
                    (when (and (identical? c conn) (= "a" (:name t)))
                      (.countDown entered) (await! release) (throw (ex-info "a failed" {})))
                    (swap! installed conj (:name t)))]
      (let [owner (test-future (outcome #(ensure! conn "a")))]
        (try
          (await! entered)
          (let [follower (test-future (deliver follower-thread (Thread/currentThread))
                                      (ensure! conn "b"))]
            (is (eventually #(and (realized? follower-thread)
                                  (= java.lang.Thread$State/WAITING (.getState ^Thread @follower-thread)))))
            (let [independent (test-future (ensure! other "c"))]
              (is (identical? other (deref independent 5000 ::timeout))))
            (.countDown release)
            (is (instance? Throwable (deref owner 5000 ::timeout)))
            (is (identical? conn (deref follower 5000 ::timeout)))
            (ensure! conn "b")
            (is (= ["c" "b"] @installed)))
          (finally (.countDown release) (deref owner 10000 ::timeout)))))))

(deftest room-locks-retain-canonical-exclusion-and-reentrancy
  (let [room (str (random-uuid)) other-room (str (random-uuid))
        lock (#'run/room-lock room) entered (promise)]
    (.lock (.writeLock lock))
    (try
      (let [reader (test-future (#'run/call-with-run-lock room :reader #(do (deliver entered :entered) :entered)))]
        (is (eventually #(.hasQueuedThreads lock)))
        (System/gc)
        (is (identical? lock (#'run/room-lock (String. room))))
        (is (not (realized? entered)))
        (let [independent (test-future (#'run/call-with-run-lock other-room :other (constantly :ok)))]
          (is (= :ok (deref independent 5000 ::timeout))))
        (.unlock (.writeLock lock))
        (is (= :entered (deref reader 5000 ::timeout))))
      (finally
        (when (.isWriteLockedByCurrentThread lock) (.unlock (.writeLock lock)))
        (java.lang.ref.Reference/reachabilityFence lock)))
    (is (= :ok (#'run/call-with-run-lock room :same
                                         #(#'run/call-with-run-lock room :same (constantly :ok)))))
    (is (= :dvergr.agent.run/nested-run-locks
           (:type (ex-data (outcome #(#'run/call-with-run-lock room :one
                                                               (fn [] (#'run/call-with-run-lock room :two (constantly :bad)))))))))
    (is (= :dvergr.agent.run/lock-upgrade
           (:type (ex-data (outcome #(#'run/call-with-run-lock room :one
                                                               (fn [] (#'run/call-exclusive room (constantly :bad)))))))))))

(deftest full-schema-does-not-publish-before-tool-installation
  (let [conn (Object.) core-calls (atom 0) tool-calls (atom 0)
        entered (CountDownLatch. 1) release (CountDownLatch. 1) follower-thread (promise)
        failure (ex-info "tool stage failed" {})]
    (with-redefs [schema/install-schema! (fn [_] (swap! core-calls inc))
                  schema/get-tool-registry (constantly {"tool" (tool "tool" 1)})
                  tool-schema/install-all-tool-schemas!
                  (fn [_ _] (when (= 1 (swap! tool-calls inc))
                              (.countDown entered) (await! release) (throw failure)))]
      (let [owner (test-future (outcome #(schema/ensure-full-schema! conn)))]
        (try
          (await! entered)
          (let [follower (test-future (deliver follower-thread (Thread/currentThread))
                                      (outcome #(schema/ensure-full-schema! conn)))]
            (is (eventually #(and (realized? follower-thread)
                                  (= java.lang.Thread$State/WAITING (.getState ^Thread @follower-thread)))))
            (is (not (realized? follower)))
            (.countDown release)
            (is (identical? failure (deref owner 5000 ::timeout)))
            (is (identical? failure (deref follower 5000 ::timeout))))
          (finally (.countDown release) (deref owner 10000 ::timeout))))
      (is (identical? conn (schema/ensure-full-schema! conn)))
      (is (= 2 @core-calls @tool-calls)))))

(deftest old-cleaner-cannot-remove-an-equal-live-replacement-key
  (let [conn (Object.) old (#'schema/connection-key conn)
        replacement (#'schema/connection-key conn) cache (java.util.HashMap.)]
    (.put cache replacement {:key replacement :installed :new})
    (#'schema/clean-connection-key! cache old)
    (is (= :new (:installed (.get cache replacement))))
    (java.lang.ref.Reference/reachabilityFence conn)))

(defn- held-room-fixture []
  (let [room (random-uuid) entered (CountDownLatch. 1) release (CountDownLatch. 1)
        worker (test-future (#'run/call-exclusive room (fn [] (.countDown entered) (await! release))))]
    (await! entered)
    {:room room :entry (.get ^java.util.Map @#'run/room-locks room)
     :release release :worker worker}))

(deftest acquisition-helper-alone-retains-lock-until-unlock
  (let [{:keys [room entry release worker]} (held-room-fixture)]
    (try
      (dotimes [_ 5] (System/gc))
      ;; Kaocha retains assertion arguments. Report only booleans, so the
      ;; observation itself cannot keep the lock alive for the GC assertion.
      (is (true? (some? (.get ^WeakReference entry))) "only the acquisition helper owns the lock")
      (is (true? (identical? (.get ^WeakReference entry) (#'run/room-lock room))))
      (finally (.countDown ^CountDownLatch release) (deref worker 10000 ::timeout)))
    (is (eventually #(do (System/gc)
                         (and (nil? (.get ^WeakReference entry))
                              (nil? (.get ^java.util.Map @#'run/room-locks room))))))))

(defn- connection-cache-keys [conn]
  (mapv (fn [cache] [cache (locking cache (:key (.get ^java.util.Map cache (#'schema/connection-key conn))))])
        [@#'schema/ensured-conns @#'schema/ensured-dynamic-tool-schemas]))

(defn- schema-churn-fixture []
  (let [conn (Object.) room (random-uuid) lock (#'run/room-lock room)]
    (schema/ensure-full-schema! conn)
    (schema/ensure-tool-use-schemas! conn [{:tool-use/name "churn"}] {"churn" (tool "churn" 1)})
    {:conn (WeakReference. conn) :lock (WeakReference. lock) :room room
     :entries (connection-cache-keys conn)}))

(defn- discarded? [{:keys [conn lock room entries]}]
  (and (nil? (.get ^WeakReference conn))
       (or (nil? lock) (nil? (.get ^WeakReference lock)))
       (or (nil? room) (nil? (.get ^java.util.Map @#'run/room-locks room)))
       (every? (fn [[cache key]] (locking cache (not (.containsKey ^java.util.Map cache key)))) entries)))

(deftest repeated-full-dynamic-and-room-cache-churn-is-reclaimed
  (with-redefs [schema/install-schema! (fn [_]) schema/get-tool-registry (constantly nil)
                tool-schema/install-tool-schema! (fn [_ _])]
    (let [live (Object.) live-room (random-uuid) live-lock (#'run/room-lock live-room)]
      (schema/ensure-full-schema! live)
      (schema/ensure-tool-use-schemas! live [{:tool-use/name "churn"}] {"churn" (tool "churn" 1)})
      (dotimes [_ 3]
        (let [fixtures (mapv (fn [_] (schema-churn-fixture)) (range 100))]
          (is (eventually #(do (System/gc) (every? discarded? fixtures))))))
      (doseq [[cache key] (connection-cache-keys live)]
        (is (locking cache (.containsKey ^java.util.Map cache key))))
      (is (identical? live-lock (#'run/room-lock live-room)))
      (java.lang.ref.Reference/reachabilityFence live)
      (java.lang.ref.Reference/reachabilityFence live-lock))))

(defn- released-real-connection []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write :keep-history? false}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (try
        (schema/ensure-full-schema! conn)
        (schema/ensure-tool-use-schemas!
         conn [{:tool-use/name "cache_gc"}]
         {"cache_gc" {:name "cache_gc" :parameters {:type "object" :properties {:value {:type "string"}}}}})
        {:conn (WeakReference. conn) :entries (connection-cache-keys conn)}
        (finally (d/release conn) (d/delete-database cfg))))))

(deftest released-datahike-connections-leave-both-memos
  (with-redefs [schema/get-tool-registry (constantly nil)]
    (let [fixtures (mapv (fn [_] (released-real-connection)) (range 3))]
      (is (eventually #(do (System/gc) (every? discarded? fixtures)))))))
