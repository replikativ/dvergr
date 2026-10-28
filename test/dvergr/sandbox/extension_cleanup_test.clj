(ns dvergr.sandbox.extension-cleanup-test
  (:require [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [dvergr.agent.room-context :as room-context]
            [dvergr.chat.context :as chat]
            [dvergr.discourse :as discourse]
            [dvergr.room.store.memory :as memory]
            [dvergr.runtime.preparation :as preparation]
            [dvergr.sandbox.extensions :as extensions]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.sci.world :as sci-world]))

(defn- with-registry [f]
  (with-redefs-fn {#'extensions/registry (atom {})} f))

(defn- with-room [f]
  (let [runtime (context/create-execution-context)]
    (try
      (binding [ec/*execution-context* runtime]
        (let [room (discourse/make-room {:id (keyword (str "cleanup-" (random-uuid)))
                                         :ctx runtime :store (memory/make)})]
          (try (f runtime room)
               (finally (discourse/close-room! room)))))
      (finally (context/close-context! runtime)))))

(deftest extension-release-keeps-per-id-acks-and-original-functions
  (with-registry
    (fn []
      (let [calls (atom []) fail? (atom true)
            interpreter {:env (atom {})}]
        (extensions/register! :test/a {:version 1 :install (fn [& _])
                                       :release (fn [_]
                                                  (swap! calls conj :a)
                                                  (when @fail? (throw (ex-info "Release A failed" {}))))})
        (extensions/register! :test/b {:version 1 :install (fn [& _])
                                       :release (fn [_] (swap! calls conj :b))})
        (let [owner (chat/extension-owner nil interpreter (extensions/snapshot #{:test/a :test/b}))]
          (is (thrown-with-msg? Exception #"Release A failed" (chat/release-extension-owner! owner)))
          (is (= #{:test/a} @(:pending owner)))
          ;; A new registry definition cannot release an older acquisition.
          (extensions/register! :test/a {:version 2 :install (fn [& _])
                                         :release (fn [_] (swap! calls conj :replacement))})
          (reset! fail? false)
          (chat/release-extension-owner! owner)
          (chat/release-extension-owner! owner)
          (is (= [:b :a :a] @calls))
          (is (empty? @(:pending owner))))))))

(deftest extension-plan-keeps-installer-and-destructor-paired
  (with-registry
    (fn []
      (let [calls (atom [])]
        (extensions/register! :test/a {:version 1
                                       :install (fn [& _] (swap! calls conj :install-original))
                                       :release (fn [_] (swap! calls conj :release-original))})
        (let [owner (chat/extension-owner nil {:env (atom {})} (extensions/snapshot #{:test/a}))]
          (extensions/register! :test/a {:version 2
                                         :install (fn [& _] (swap! calls conj :install-replacement))
                                         :release (fn [_] (swap! calls conj :release-replacement))})
          (chat/install-extension-owner! owner)
          (chat/release-extension-owner! owner)
          (is (= [:install-original :release-original] @calls)))))))

(deftest failed-extension-cleanup-keeps-the-selected-interpreter
  (with-registry
    (fn []
      (let [runtime (context/create-execution-context)
            ref (sci-world/create! runtime {:spindel-primitives? false})
            interpreter (sci-world/context-in runtime ref)
            fail? (atom true)]
        (try
          (extensions/register! :test/a {:version 1 :install (fn [& _])
                                         :release (fn [_] (when @fail? (throw (ex-info "Release failed" {}))))})
          (let [owner (chat/extension-owner runtime interpreter (extensions/snapshot #{:test/a}))
                cctx {:spindel-ctx runtime :sci-component ref :extension-owners [owner]}]
            (is (thrown-with-msg? Exception #"Release failed" (chat/release-sci-in! cctx runtime)))
            (is (identical? interpreter (sci-world/context-in runtime ref)))
            (is (= #{:test/a} @(:pending owner)))
            (reset! fail? false)
            (chat/release-sci-in! cctx runtime)
            (is (thrown-with-msg? Exception #"not available" (sci-world/context-in runtime ref))))
          (finally
            (sci/retire! interpreter)
            (context/close-context! runtime)))))))

(deftest synchronous-cache-rebuild-releases-outside-the-room-monitor
  (with-registry
    (fn []
      (with-room
        (fn [_ room]
          (let [observed (atom [])]
            (extensions/register! :test/a {:version 1 :install (fn [& _])
                                           :release (fn [_]
                                                      (swap! observed conj
                                                             {:locked? (Thread/holdsLock (:meta room))
                                                              :retained? (boolean (seq (get @(:meta room) preparation/leases-key)))}))})
            (let [old (room-context/ensure-ctx! room :agent {:sandbox #{:test/a}})
                  replacement (room-context/ensure-ctx! room :agent {:sandbox #{}})]
              (is (not (identical? old replacement)))
              (is (= [{:locked? false :retained? true}] @observed)))))))))

(deftest failed-cache-release-retains-the-exact-old-entry-for-retry
  (with-registry
    (fn []
      (with-room
        (fn [_ room]
          (let [fail? (atom true) calls (atom [])]
            (try
              (extensions/register! :test/a {:version 1 :install (fn [& _])
                                             :release (fn [interpreter]
                                                        (swap! calls conj interpreter)
                                                        (when @fail? (throw (ex-info "Cleanup refused" {}))))})
              (let [old (room-context/ensure-ctx! room :agent {:sandbox #{:test/a}})
                    old-interpreter (chat/sci-context old)]
                (is (thrown? Exception (room-context/ensure-ctx! room :agent {:sandbox #{}})))
                (is (identical? old-interpreter (chat/sci-context old)))
                (is (= ::preparation/not-quiescent
                       (try (room-context/ensure-ctx! room :agent {:sandbox #{}}) nil
                            (catch Exception error (:type (ex-data error))))))
                (reset! fail? false)
                (preparation/drain-agent! room :agent)
                (is (= 2 (count @calls)))
                (is (every? #(identical? old-interpreter %) @calls))
                (is (some? (room-context/ensure-ctx! room :agent {:sandbox #{}}))))
              (finally (reset! fail? false)))))))))

(deftest failed-construction-keeps-original-error-and-retains-failed-cleanup
  (with-registry
    (fn []
      (with-room
        (fn [_ room]
          (let [fail? (atom true)]
            (try
              (extensions/register! :test/a {:version 1
                                             :install (fn [& _] (throw (ex-info "Original setup failure" {:setup true})))
                                             :release (fn [_] (when @fail? (throw (ex-info "Cleanup failure" {}))))})
              (let [error (try (room-context/ensure-ctx! room :agent {:sandbox #{:test/a}}) nil
                               (catch Exception error error))]
                (is (:setup (ex-data error)))
                (is (= 1 (count (.getSuppressed ^Throwable error))))
                (is (seq (get @(:meta room) preparation/leases-key)))
                (reset! fail? false)
                (preparation/drain-agent! room :agent))
              (finally (reset! fail? false)))))))))

(deftest detached-agent-cleanup-can-be-retried-by-drain
  (with-registry
    (fn []
      (with-room
        (fn [_ room]
          (let [fail? (atom true) calls (atom [])]
            (try
              (extensions/register! :test/a {:version 1 :install (fn [& _])
                                             :release (fn [interpreter]
                                                        (is (not (Thread/holdsLock (:meta room))))
                                                        (swap! calls conj interpreter)
                                                        (when @fail? (throw (ex-info "Drop cleanup refused" {}))))})
              (let [old (room-context/ensure-ctx! room :agent {:sandbox #{:test/a}})
                    interpreter (chat/sci-context old)]
                (is (thrown? Exception (room-context/drop-ctx! room :agent)))
                (is (nil? (room-context/lookup (:id room) :agent)))
                (is (identical? interpreter (chat/sci-context old)))
                (is (= ::preparation/not-quiescent
                       (try (room-context/ensure-ctx! room :agent {:sandbox #{}}) nil
                            (catch Exception error (:type (ex-data error))))))
                (reset! fail? false)
                (preparation/drain-agent! room :agent)
                (is (= 2 (count @calls)))
                (is (every? #(identical? interpreter %) @calls))
                (is (some? (room-context/ensure-ctx! room :agent {:sandbox #{}}))))
              (finally (reset! fail? false)))))))))

(deftest published-constructor-transfers-its-provisional-owner-before-unlocking
  (with-registry
    (fn []
      (with-room
        (fn [_ room]
          (let [entered (promise) resume (promise)
                original @#'room-context/finish-cleanups!
                delayed? (atom false)]
            (with-redefs-fn
              {#'room-context/finish-cleanups!
               (fn [leases]
                 (when (compare-and-set! delayed? false true)
                   (deliver entered true)
                   (when-not (deref resume 5000 false)
                     (throw (ex-info "Test cleanup wait timed out" {}))))
                 (original leases))}
              (fn []
                (let [first-result (future (room-context/ensure-ctx! room :agent {:sandbox #{}}))]
                  (try
                    (is (true? (deref entered 5000 false)))
                    (let [second-result (try (room-context/ensure-ctx! room :agent {:sandbox #{}})
                                             (catch Exception error error))]
                      (is (not (instance? Throwable second-result)))
                      (deliver resume true)
                      (is (identical? second-result (deref first-result 5000 nil))))
                    (finally
                      (deliver resume true)
                      (deref first-result 5000 nil))))))))))))

(deftest cleanup-capacity-is-reserved-before-interpreter-allocation
  (with-registry
    (fn []
      (with-room
        (fn [_ room]
          (let [constructed (atom 0) released (atom 0)
                original (var-get (requiring-resolve 'dvergr.agent.turn/new-working-ctx))]
            (with-redefs-fn
              {#'preparation/retain-room-cleanup! (fn [& _] (throw (ex-info "No cleanup capacity" {:no-capacity true})))
               (requiring-resolve 'dvergr.agent.turn/new-working-ctx)
               (fn [opts] (swap! constructed inc) (original opts))
               #'chat/release-sci-in! (fn [& _] (swap! released inc))}
              (fn []
                (is (:no-capacity
                     (try (room-context/ensure-ctx! room :agent {:sandbox #{}}) nil
                          (catch Exception e (ex-data e)))))
                (is (zero? @constructed))
                (is (zero? @released))))))))))
