(ns dvergr.sandbox.extension-cleanup-test
  (:require [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [dvergr.agent.room-context :as room-context]
            [dvergr.chat.context :as chat]
            [dvergr.discourse :as discourse]
            [dvergr.room.store.memory :as memory]
            [dvergr.runtime.preparation :as preparation]
            [dvergr.sandbox.extensions :as extensions]
            [dvergr.system.rooms :as rooms]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]
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

(defn- throwing-clone! [room]
  (discourse/join room
                  (discourse/participant
                   {:id :agent :on-message (fn [_ _] nil)
                    :factory (fn [_] (throw (ex-info "Clone factory refused" {:clone-failure true})))})))

(deftest failed-cloning-releases-child-projection-before-discard-off-both-monitors
  (with-registry
    (fn []
      (with-room
        (fn [_ room]
          (let [child (atom nil) events (atom []) parent-interpreter (atom nil)
                project room-context/fork-ctx! discard ygg/discard-fork!]
            (extensions/register! :test/a {:version 1 :install (fn [& _])
                                           :release (fn [interpreter]
                                                      (when-not (identical? interpreter @parent-interpreter)
                                                        (swap! events conj :release)
                                                        (is (not (Thread/holdsLock (:meta room))))
                                                        (is (not (Thread/holdsLock (:meta @child))))))})
            (let [parent (room-context/ensure-ctx! room :agent {:sandbox #{:test/a}})]
              (reset! parent-interpreter (chat/sci-context parent))
              (throwing-clone! room)
              (with-redefs [room-context/fork-ctx! (fn [parent fork agent]
                                                     (reset! child fork) (project parent fork agent))
                            ygg/discard-fork! (fn [handle & [opts]]
                                                (swap! events conj :discard) (discard handle (or opts {})))]
                (is (:clone-failure
                     (try (discourse/fork-room room {:isolation :ctx}) nil
                          (catch Exception error (ex-data error))))))
              (is (= [:release :discard] @events))
              (is (nil? (room-context/lookup (:id @child) :agent)))
              (is (identical? @parent-interpreter (chat/sci-context parent))))))))))

(deftest failed-child-release-retains-native-substrate-until-parent-drain-retries
  (with-registry
    (fn []
      (with-room
        (fn [_ room]
          (let [child (atom nil) fail? (atom true) events (atom []) parent-interpreter (atom nil)
                project room-context/fork-ctx! discard ygg/discard-fork!]
            (try
              (extensions/register! :test/a {:version 1 :install (fn [& _])
                                             :release (fn [interpreter]
                                                        (when-not (identical? interpreter @parent-interpreter)
                                                          (swap! events conj :release)
                                                          (when @fail? (throw (ex-info "Child cleanup refused" {})))))})
              (let [parent (room-context/ensure-ctx! room :agent {:sandbox #{:test/a}})]
                (reset! parent-interpreter (chat/sci-context parent))
                (throwing-clone! room)
                (with-redefs [room-context/fork-ctx! (fn [parent fork agent]
                                                       (reset! child fork) (project parent fork agent))
                              ygg/discard-fork! (fn [handle & [opts]]
                                                  (swap! events conj :discard) (discard handle (or opts {})))]
                  (let [error (try (discourse/fork-room room {:isolation :ctx}) nil
                                   (catch Exception error error))
                        handle (discourse/fork-handle @child)]
                    (is (:clone-failure (ex-data error)))
                    (is (= 1 (count (.getSuppressed ^Throwable error))))
                    (is (= [:release] @events))
                    (is (some? (room-context/lookup (:id @child) :agent)))
                    (is (= :open (:status (ygg/fork-disposition handle))))
                    (reset! fail? false)
                    (preparation/drain-room! room)
                    (is (= [:release :release :discard] @events))
                    (is (nil? (room-context/lookup (:id @child) :agent)))
                    (is (= :discarded (:status (ygg/fork-disposition handle))))))
                (is (identical? @parent-interpreter (chat/sci-context parent))))
              (finally (reset! fail? false)))))))))

(deftest failed-native-discard-does-not-replay-acknowledged-child-cleanup
  (with-registry
    (fn []
      (with-room
        (fn [_ room]
          (let [fail? (atom true) releases (atom 0) discards (atom 0) parent-interpreter (atom nil)
                discard ygg/discard-fork!]
            (try
              (extensions/register! :test/a {:version 1 :install (fn [& _])
                                             :release (fn [interpreter]
                                                        (when-not (identical? interpreter @parent-interpreter)
                                                          (swap! releases inc)))})
              (reset! parent-interpreter
                      (chat/sci-context (room-context/ensure-ctx! room :agent {:sandbox #{:test/a}})))
              (throwing-clone! room)
              (with-redefs [ygg/discard-fork! (fn [handle & [opts]]
                                                (swap! discards inc)
                                                (when @fail? (throw (ex-info "Discard refused before mutation" {})))
                                                (discard handle (or opts {})))]
                (is (thrown? Exception (discourse/fork-room room {:isolation :ctx})))
                (is (= 1 @releases @discards))
                (reset! fail? false)
                (preparation/drain-room! room)
                (is (= 1 @releases))
                (is (= 2 @discards)))
              (finally (reset! fail? false)))))))))

(deftest failed-projection-rebind-is-retained-before-installation
  (with-registry
    (fn []
      (with-room
        (fn [runtime room]
          (let [child (atom nil) installs (atom 0) releases (atom 0)
                project room-context/fork-ctx!]
            (extensions/register! :test/a {:version 1
                                           :install (fn [_ selected]
                                                      (when-not (identical? selected runtime)
                                                        (swap! installs inc)
                                                        (throw (ex-info "Child installation refused" {:child-install true}))))
                                           :release (fn [_] (swap! releases inc))})
            (room-context/ensure-ctx! room :agent {:sandbox #{:test/a}})
            (throwing-clone! room)
            (with-redefs [room-context/fork-ctx! (fn [parent fork agent]
                                                   (reset! child fork) (project parent fork agent))]
              (is (:child-install
                   (try (discourse/fork-room room {:isolation :ctx}) nil
                        (catch Exception error (ex-data error))))))
            (is (= 1 @installs @releases))
            (is (nil? (room-context/lookup (:id @child) :agent)))))))))

(deftest cloning-on-the-drain-refuses-before-fork-acquisition
  (with-room
    (fn [_ room]
      (let [result (promise) forks (atom 0)]
        (with-redefs [ygg/fork! (fn [& _] (swap! forks inc))]
          (sp/spawn! (sp/spin
                      (sp/await (sp/sleep 1))
                      (deliver result
                               (try (discourse/fork-room room) nil
                                    (catch Exception error (:type (ex-data error)))))))
          (is (= ::preparation/blocking-cleanup (deref result 5000 ::timeout)))
          (is (zero? @forks)))))))

(deftest unpublished-cleanup-refuses-a-registered-owner-before-release
  (with-room
    (fn [_ room]
      (let [working (room-context/ensure-ctx! room :agent {:sandbox #{}})
            interpreter (chat/sci-context working)]
        (is (= ::room-context/published-construction
               (try (room-context/drop-unpublished-room! room room) nil
                    (catch Exception error (:type (ex-data error))))))
        (is (identical? working (room-context/lookup (:id room) :agent)))
        (is (identical? interpreter (chat/sci-context working)))))))

(deftest incomplete-native-discard-is-retained-without-blind-replay
  (with-registry
    (fn []
      (with-room
        (fn [_ room]
          (let [fail? (atom true) incomplete? (atom false) discards (atom 0)
                discard ygg/discard-fork! disposition ygg/fork-disposition]
            (try
              (room-context/ensure-ctx! room :agent {:sandbox #{}})
              (throwing-clone! room)
              (with-redefs [ygg/discard-fork! (fn [handle & [opts]]
                                                (swap! discards inc)
                                                (when @fail? (throw (ex-info "Ambiguous disposal" {})))
                                                (discard handle (or opts {})))
                            ygg/fork-disposition (fn [handle]
                                                   (if @incomplete? {:status :incomplete} (disposition handle)))]
                (is (thrown? Exception (discourse/fork-room room {:isolation :ctx})))
                (reset! fail? false)
                (reset! incomplete? true)
                (is (thrown? Exception (preparation/drain-room! room)))
                (is (= 1 @discards))
                (is (seq (get @(:meta room) preparation/leases-key)))
                (reset! incomplete? false)
                (preparation/drain-room! room)
                (is (= 2 @discards)))
              (finally (reset! fail? false) (reset! incomplete? false)))))))))

(deftest failed-grant-deletion-retains-only-unacknowledged-scopes
  (with-registry
    (fn []
      (with-room
        (fn [_ room]
          (let [fail? (atom true) deleted (atom []) discards (atom 0)
                read-state ec/get-state discard ygg/discard-fork!
                pending [{:scope :first-store} {:scope :second-store}]]
            (try
              (room-context/ensure-ctx! room :agent {:sandbox #{}})
              (throwing-clone! room)
              (with-redefs [ec/get-state (fn [& args]
                                           (if (= [:dvergr/pending-grants] (first args))
                                             pending (apply read-state args)))
                            ygg/discard-fork! (fn [handle & [opts]]
                                                (swap! discards inc) (discard handle (or opts {})))
                            rooms/drop-fork-grant! (fn [{:keys [scope]}]
                                                     (swap! deleted conj scope)
                                                     (when (and (= scope :second-store) @fail?)
                                                       (throw (ex-info "Deletion refused" {}))))]
                (is (thrown? Exception (discourse/fork-room room {:isolation :ctx})))
                (is (= [:first-store :second-store] @deleted))
                (reset! fail? false)
                (preparation/drain-room! room)
                (is (= [:first-store :second-store :second-store] @deleted))
                (is (= 1 @discards)))
              (finally (reset! fail? false)))))))))
