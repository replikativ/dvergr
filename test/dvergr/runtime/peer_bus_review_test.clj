(ns dvergr.runtime.peer-bus-review-test
  "End-to-end tests for the peer-bus + PR-style merge-review primitives:
   - Per-room messages relay to the peer-bus tagged with origin + scope
   - fork-room emits :dvergr/fork-created
   - propose-merge! emits :dvergr/merge-proposed + a tagged chat message
   - merge-room emits :dvergr/fork-merged
   - discard emits :dvergr/fork-discarded
   - pending-proposals scans a room's log"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.agent.run :as agent-run]
            [dvergr.runtime.bus :as bus]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.discourse :as d]
            [dvergr.intake.bash :as b]
            [dvergr.substrate.geschichte :as geschichte]
            [dvergr.room.registry :as registry]
            [dvergr.runtime.peer-bus :as peer-bus]
            [dvergr.sandbox.work :as sandbox-work]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]))

;; ============================================================================
;; Sandbox fixture (reuses pattern from bash_isolation_test)
;; ============================================================================

(defn- run-shell [& cmd-parts]
  (let [pb (-> (ProcessBuilder. ^java.util.List (vec cmd-parts))
               (.redirectErrorStream true))
        proc (.start pb)]
    (.waitFor proc)
    {:out (slurp (.getInputStream proc))}))

(def ^:dynamic *sandbox-dir* nil)
(def ^:dynamic *base-ctx* nil)

(defn- with-sandbox [test-fn]
  (let [dir (str "/tmp/dvergr-pb-" (System/nanoTime))]
    (try
      (let [ctx (daemon/create-shared-context
                 :repo-path (str dir "/repository")
                 :with-git? true
                 :with-datahike? false)]
        (binding [*sandbox-dir* dir
                  *base-ctx* ctx]
          (test-fn)))
      (finally
        (run-shell "rm" "-rf" dir)))))

(use-fixtures :each with-sandbox)

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- peer-events-of-type [tag]
  (binding [ec/*execution-context* *base-ctx*]
    (->> (peer-bus/log)
         (filter #(= tag (:type %)))
         vec)))

(defn- chat [ctx]
  {:spindel-ctx ctx :chat-id (random-uuid) :title "t"})

(defn- bash [ctx-or-chat cmd]
  (b/run (if (:spindel-ctx ctx-or-chat)
           ctx-or-chat
           (chat ctx-or-chat))
         cmd))

;; ============================================================================
;; Tests
;; ============================================================================

(deftest peer-bus-is-registered-on-daemon-init
  (binding [ec/*execution-context* *base-ctx*]
    (is (some? (peer-bus/current)))))

(deftest fork-room-emits-fork-created-event
  (binding [ec/*execution-context* *base-ctx*]
    (let [parent (d/room :parent *base-ctx*)
          fork   (d/fork-room parent {:isolation :ctx})]
      (is (= {:fork/purpose :workroom
              :fork/owner (:id fork)
              :fork/status :open}
             (select-keys (d/fork-descriptor fork)
                          [:fork/purpose :fork/owner :fork/status]))
          "the Room projects the canonical world descriptor")
      (Thread/sleep 50)                                ; let drain catch up
      (let [evts (peer-events-of-type :dvergr/fork-created)]
        (is (= 1 (count evts)) "exactly one fork-created event")
        (is (= (:id fork) (:dvergr/origin (first evts))))
        (is (= :parent (:dvergr/parent (first evts))))
        (is (vector? (:workspace-id (first evts))))))))

(deftest propose-merge!-emits-event-and-tagged-message
  (binding [ec/*execution-context* *base-ctx*]
    (let [parent (d/room :p *base-ctx*)
          fork   (d/fork-room parent {:isolation :ctx})
          _      (bash (:ctx fork)
                       "echo added > side.txt && git add . && git commit -q -m wip")
          prop   (d/propose-merge! fork :note "Adds side.txt")]
      (Thread/sleep 100)
      (testing "proposal payload includes a diff"
        (is (string? (get-in prop [:diff :branch])))
        (is (re-find #"side.txt" (get-in prop [:diff :stat]))))
      (testing "peer-bus saw :dvergr/merge-proposed"
        (let [evts (peer-events-of-type :dvergr/merge-proposed)]
          (is (= 1 (count evts)))
          (is (= (:id fork) (:dvergr/origin (first evts))))))
      (testing "fork's log carries a :dvergr/proposal message"
        (is (= 1 (count (d/pending-proposals fork))))))))

(deftest merge-room-emits-fork-merged
  (binding [ec/*execution-context* *base-ctx*]
    (let [parent (d/room :p *base-ctx*)
          fork   (d/fork-room parent {:isolation :ctx})
          handle (d/fork-handle fork)]
      (bash (:ctx fork) "echo m > m.txt && git add . && git commit -q -m wip")
      (d/merge-room parent fork)
      (is (= :merged (:status (ygg/fork-disposition handle))))
      (Thread/sleep 50)
      (let [evts (peer-events-of-type :dvergr/fork-merged)]
        (is (= 1 (count evts)))
        (is (= (:id fork) (:dvergr/origin (first evts))))))))

(deftest discard-emits-fork-discarded
  (binding [ec/*execution-context* *base-ctx*]
    (let [parent (d/room :p *base-ctx*)
          fork   (d/fork-room parent {:isolation :ctx})
          handle (d/fork-handle fork)]
      (d/discard fork)
      (is (= :discarded (:status (ygg/fork-disposition handle))))
      (Thread/sleep 50)
      (let [evts (peer-events-of-type :dvergr/fork-discarded)]
        (is (= 1 (count evts)))
        (is (= (:id fork) (:dvergr/origin (first evts))))))))

(deftest fork-transfer-is-durability-first-and-affine
  (binding [ec/*execution-context* *base-ctx*]
    (let [parent (d/room :transfer-parent *base-ctx*)
          fork (d/fork-room parent {:isolation :ctx})
          original (d/fork-handle fork)
          owner (random-uuid)
          prepared (atom nil)
          sealed-post-error (atom nil)
          admitted-run (agent-run/start!
                        fork :worker (d/message :human :worker "work") nil)
          admitted-run-id (:run/id admitted-run)
          _cancel-hook (agent-run/register-cancel-hook!
                        admitted-run-id :test-finish
                        #(agent-run/finish! admitted-run-id :cancelled))
          listener (d/on-each-message fork (fn [_]))]
      (bash (:ctx fork) "echo adopted > adopted.txt && git add . && git commit -q -m adopted")
      (let [{:fork/keys [handle descriptor receipt] :as transfer}
            (d/transfer-fork! fork owner
                              {:prepare! (fn [prospective]
                                           (reset! prepared prospective)
                                           (reset! sealed-post-error
                                                   (try
                                                     (d/post! fork
                                                              (d/message
                                                               :worker nil "too late"
                                                               nil {:run-id admitted-run-id}))
                                                     nil
                                                     (catch clojure.lang.ExceptionInfo error
                                                       error)))
                                           {:proposal owner})
                               :abort! (fn [_])})]
        (is (= @prepared descriptor))
        (is (= {:proposal owner} receipt))
        (is (= owner (:fork/owner descriptor)))
        (is (= (:id fork) (:dvergr/room-id descriptor)))
        (is (= (:id parent) (:dvergr/parent-room-id descriptor)))
        (is (= ::d/fork-transfer-in-progress
               (:type (ex-data @sealed-post-error)))
            "durable preparation starts after admitted Run posting is sealed")
        (is (not (ygg/open-fork? original))
            "the Run/Room capability is stale after transfer")
        (is (ygg/open-fork? handle))
        (is (nil? (registry/lookup (:id fork)))
            "raw Room merge/discard controls disappear")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"transferred"
                              (ygg/discard-fork! original)))
        (is (= ::d/fork-transfer-in-progress
               (:type (ex-data (try
                                 (d/post! fork (d/message :human nil "late"))
                                 (catch clojure.lang.ExceptionInfo error error))))))
        (is (= ::d/fork-transfer-in-progress
               (:type (ex-data (try
                                 (binding [ec/*execution-context* (:ctx fork)]
                                   @(d/ask fork :nobody {:content "late"}))
                                 (catch clojure.lang.ExceptionInfo error error))))))
        (ygg/merge-fork! handle)
        (d/release-transferred-fork! transfer)
        (is (= "adopted\n" (:stdout (bash (:ctx parent) "cat adopted.txt"))))
        (Thread/sleep 50)
        (let [events (peer-events-of-type :dvergr/fork-transferred)]
          (is (= 1 (count events)))
          (is (= owner (:fork/owner (first events)))))))))

(deftest transferred-world-partitions-into-exhaustive-affine-authority
  (binding [ec/*execution-context* *base-ctx*]
    ;; The fixture has one Geschichte system. A second independent repository
    ;; gives this world two real settlement scopes without a mock substrate.
    (ygg/register! (geschichte/create-system
                    :scope (str *sandbox-dir* "/second-repository")
                    :system-name :second-repository))
    (let [parent (d/room :partition-transfer-parent *base-ctx*)
          fork (d/fork-room parent {:isolation :ctx})
          transfer (d/transfer-fork! fork :proposal
                                     {:prepare! (constantly :adoption-receipt)
                                      :abort! (fn [_])})
          systems (vec (keys (get-in transfer [:fork/descriptor :fork/systems])))
          prepared (atom nil)
          committed (atom nil)
          partitioned
          (d/partition-transferred-fork!
           transfer
           [{:systems #{(first systems)} :owner :reviewer-a :purpose :component}
            {:systems #{(second systems)} :owner :reviewer-b :purpose :component}]
           {:prepare! (fn [plan]
                        (reset! prepared plan)
                        :partition-receipt)
            :abort! (fn [_])
            :commit! (fn [receipt descriptors]
                       (reset! committed [receipt descriptors]))})
          parts (:fork/partitions partitioned)
          settlement-commits (atom [])]
      (is (= 2 (count systems)))
      (is (= 2 (count parts)))
      (is (= :partitioned
             (:fork/status (:fork/descriptor partitioned))))
      (is (= systems
             (->> @prepared :fork/descriptor :fork/systems keys vec)))
      (is (= :partition-receipt (first @committed)))
      (is (= (set systems)
             (->> (second @committed)
                  (mapcat (comp keys :fork/systems))
                  set)))
      (is (every? #(= (:id fork)
                      (get-in % [:fork/descriptor :dvergr/room-id]))
                  parts))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"has not settled"
                            (d/release-transferred-fork! partitioned)))
      (let [lifecycle {:prepare! (fn [plan] (:fork/operation plan))
                       :abort! (fn [_])
                       :commit! (fn [receipt terminal]
                                  (swap! settlement-commits conj
                                         [receipt (:fork/descriptor terminal)]))}
            settled [(d/settle-transferred-fork! (first parts) :merge lifecycle)
                     (d/settle-transferred-fork! (second parts) :discard lifecycle)]
            settled-tree (assoc partitioned :fork/partitions settled)]
        (is (= 2 (count @settlement-commits)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"incomplete"
                              (d/release-transferred-fork!
                               (assoc settled-tree :fork/partitions
                                      [(first settled)]))))
        (let [forged (assoc (first settled)
                            :fork/descriptor (:fork/descriptor (second settled)))
              error (try
                      (d/release-transferred-fork!
                       (assoc settled-tree :fork/partitions
                              [(first settled) forged]))
                      (catch clojure.lang.ExceptionInfo error error))]
          (is (= ::d/transferred-fork-capability-mismatch
                 (:type (ex-data error)))))
        (is (nil? (d/release-transferred-fork! settled-tree)))))))

(deftest partition-commit-failure-returns-every-live-capability
  (binding [ec/*execution-context* *base-ctx*]
    (ygg/register! (geschichte/create-system
                    :scope (str *sandbox-dir* "/commit-failure-repository")
                    :system-name :commit-failure-repository))
    (let [parent (d/room :partition-commit-parent *base-ctx*)
          fork (d/fork-room parent {:isolation :ctx})
          transfer (d/transfer-fork! fork :proposal
                                     {:prepare! (constantly :adoption-receipt)
                                      :abort! (fn [_])})
          systems (vec (keys (get-in transfer [:fork/descriptor :fork/systems])))
          partitioned
          (d/partition-transferred-fork!
           transfer
           [{:systems #{(first systems)} :owner :a}
            {:systems #{(second systems)} :owner :b}]
           {:prepare! (constantly :partition-receipt)
            :abort! (fn [_])
            :commit! (fn [_ _] (throw (ex-info "store unavailable" {})))})
          parts (:fork/partitions partitioned)]
      (is (= :failed (get-in partitioned [:fork/partition-commit :status])))
      (is (= "store unavailable"
             (ex-message (get-in partitioned [:fork/partition-commit :error]))))
      (is (= 2 (count parts)))
      (is (every? (comp ygg/open-fork? :fork/handle) parts))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not durably committed"
                            (d/release-transferred-fork! partitioned)))
      (let [partitioned (d/retry-partition-commit! partitioned (fn [_ _] :ok))
            first-settled
            (d/settle-transferred-fork!
             (first (:fork/partitions partitioned)) :discard
             {:prepare! (constantly :first-receipt)
              :abort! (fn [_])
              :commit! (fn [_ _] (throw (ex-info "terminal store unavailable" {})))})
            second-settled
            (d/settle-transferred-fork!
             (second (:fork/partitions partitioned)) :discard
             {:prepare! (constantly :second-receipt)
              :abort! (fn [_])
              :commit! (fn [_ _] :ok)})
            failed-tree (assoc partitioned :fork/partitions
                               [first-settled second-settled])]
        (is (= :commit-failed (get-in first-settled [:fork/settlement :status])))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not durably committed"
                              (d/release-transferred-fork! failed-tree)))
        (let [recovered (d/retry-settlement-commit! first-settled (fn [_ _] :ok))
              recovered-tree (assoc failed-tree :fork/partitions
                                    [recovered second-settled])]
          (d/release-transferred-fork! recovered-tree))))))

(deftest governed-settlement-prepares-exactly-one-concurrent-decision
  (binding [ec/*execution-context* *base-ctx*]
    (let [parent (d/room :governed-settlement-parent *base-ctx*)
          fork (d/fork-room parent {:isolation :ctx})
          transfer (d/transfer-fork! fork :governance
                                     {:prepare! (constantly :adoption-receipt)
                                      :abort! (fn [_])})
          ready (java.util.concurrent.CountDownLatch. 2)
          start (java.util.concurrent.CountDownLatch. 1)
          prepares (atom [])
          lifecycle (fn [decision]
                      {:prepare! (fn [_]
                                   (swap! prepares conj decision)
                                   decision)
                       :abort! (fn [_])
                       :commit! (fn [_ _] :ok)})
          decide (fn [decision]
                   (future
                     (.countDown ready)
                     (.await start)
                     (try
                       (d/settle-transferred-fork!
                        transfer decision (lifecycle decision))
                       (catch clojure.lang.ExceptionInfo error error))))
          merge-result (decide :merge)
          discard-result (decide :discard)]
      (.await ready)
      (.countDown start)
      (let [results [@merge-result @discard-result]
            winner (first (filter map? results))
            loser (first (filter #(instance? clojure.lang.ExceptionInfo %) results))]
        (is (= 1 (count @prepares)))
        (is (map? winner))
        (is (= ::d/transferred-fork-not-open (:type (ex-data loser))))
        (is (= :committed (get-in winner [:fork/settlement :status])))
        (d/release-transferred-fork! winner)))))

(deftest whole-world-governance-retries-only-portable-durable-commit
  (binding [ec/*execution-context* *base-ctx*]
    (let [parent (d/room :whole-world-governance-parent *base-ctx*)
          fork (d/fork-room parent {:isolation :ctx})
          transfer (d/transfer-fork! fork :governance
                                     {:prepare! (constantly :adoption-receipt)
                                      :abort! (fn [_])})
          settled (d/settle-transferred-fork!
                   transfer :discard
                   {:prepare! (constantly :settlement-receipt)
                    :abort! (fn [_])
                    :commit! (fn [_ _]
                               (throw (ex-info "terminal store unavailable" {})))})]
      (is (= :commit-failed (get-in settled [:fork/settlement :status])))
      (is (= #{:fork/operation :fork/descriptor}
             (set (keys (get-in settled [:fork/settlement :commit-value]))))
          "the retry value contains no live Spindel settlement payload")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not durably committed"
                            (d/release-transferred-fork! settled)))
      (let [recovered (d/retry-settlement-commit! settled (fn [_ _] :ok))]
        (d/release-transferred-fork! recovered)))))

(deftest failed-settlement-compensation-retains-recovery-receipt
  (binding [ec/*execution-context* *base-ctx*]
    (let [parent (d/room :settlement-abort-parent *base-ctx*)
          fork (d/fork-room parent {:isolation :ctx})
          transfer (d/transfer-fork! fork :governance
                                     {:prepare! (constantly :adoption-receipt)
                                      :abort! (fn [_])})
          result (with-redefs [ygg/merge-fork! (fn [& _]
                                                 (throw (ex-info "preflight failed" {})))]
                   (d/settle-transferred-fork!
                    transfer :merge
                    {:prepare! (constantly :settlement-receipt)
                     :abort! (fn [_]
                               (throw (ex-info "abort store unavailable" {})))
                     :commit! (fn [_ _] :ok)}))]
      (is (= :abort-failed (get-in result [:fork/settlement :status])))
      (is (= :settlement-receipt (get-in result [:fork/settlement :receipt])))
      (is (ygg/open-fork? (:fork/handle result)))
      (let [recovered (d/retry-settlement-abort! result (fn [_] :ok))
            discarded (d/settle-transferred-fork!
                       recovered :discard
                       {:prepare! (constantly :discard-receipt)
                        :abort! (fn [_])
                        :commit! (fn [_ _] :ok)})]
        (is (nil? (:fork/settlement recovered)))
        (d/release-transferred-fork! discarded)))))

(deftest failed-transfer-preparation-preserves-the-review-world
  (binding [ec/*execution-context* *base-ctx*]
    (let [parent (d/room :failed-transfer-parent *base-ctx*)
          fork (d/fork-room parent {:isolation :ctx})
          original (d/fork-handle fork)
          relayed (promise)
          _listener (d/on-each-message fork (fn [_] (deliver relayed true)))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"prepare failed"
                            (d/transfer-fork! fork (random-uuid)
                                              {:prepare! (fn [_]
                                                           (throw (ex-info "prepare failed" {})))
                                               :abort! (fn [_])})))
      (is (ygg/open-fork? original))
      (is (identical? fork (registry/lookup (:id fork))))
      (is (= :open (binding [ec/*execution-context* (:ctx fork)]
                     (ec/get-state [:dvergr/run-admissions (:id fork)])))
          "failed preparation reopens Run admission")
      (d/post! fork (d/message :human nil "listener restored"))
      (is (= true (deref relayed 2000 ::timeout))
          "recoverable transfer failure reinstalls Room-owned listeners")
      (d/discard fork))))

(deftest transfer-waits-for-an-active-listener-callback
  (binding [ec/*execution-context* *base-ctx*]
    (let [parent (d/room :transfer-listener-parent *base-ctx*)
          fork (d/fork-room parent {:isolation :ctx})
          entered (promise)
          release (promise)
          _listener (d/on-each-message
                     fork
                     (fn [_]
                       (deliver entered true)
                       @release))]
      (d/post! fork (d/message :human nil "block relay"))
      (is (= true (deref entered 2000 ::timeout)))
      (let [transfer-future
            (future
              (binding [ec/*execution-context* *base-ctx*]
                (d/transfer-fork! fork :owner
                                  {:prepare! (fn [_] :row)
                                   :abort! (fn [_])})))]
        (Thread/sleep 100)
        (is (not (realized? transfer-future))
            "authority cannot move while callback code is still executing")
        (deliver release true)
        (let [transfer (deref transfer-future 5000 ::timeout)]
          (is (map? transfer))
          (ygg/discard-fork! (:fork/handle transfer))
          (d/release-transferred-fork! transfer))))))

(deftest transfer-requires-compensation-before-fencing-the-room
  (binding [ec/*execution-context* *base-ctx*]
    (let [parent (d/room :transfer-contract-parent *base-ctx*)
          fork (d/fork-room parent {:isolation :ctx})
          error (try
                  (d/transfer-fork! fork :owner {:prepare! identity})
                  (catch clojure.lang.ExceptionInfo error error))]
      (is (= ::d/durable-abort-required (:type (ex-data error))))
      (is (ygg/open-fork? (d/fork-handle fork)))
      (is (identical? fork (registry/lookup (:id fork))))
      (d/discard fork))))

(deftest transfer-fence-rejects-participants-and-children-during-prepare
  (binding [ec/*execution-context* *base-ctx*]
    (let [parent (d/room :transfer-fence-parent *base-ctx*)
          fork (d/fork-room parent {:isolation :ctx})
          join-error (atom nil)
          child-error (atom nil)
          post-error (atom nil)
          ask-error (atom nil)
          listener-error (atom nil)
          transfer
          (d/transfer-fork!
           fork :governance
           {:prepare!
            (fn [_]
              (reset! join-error
                      (try
                        (d/join fork (d/participant {:id :late
                                                     :on-message (fn [_ _] nil)}))
                        (catch clojure.lang.ExceptionInfo error error)))
              (reset! child-error
                      (try
                        (d/fork-room fork {:isolation :ctx})
                        (catch clojure.lang.ExceptionInfo error error)))
              (reset! post-error
                      (try
                        (d/post! fork (d/message :human nil "late"))
                        (catch clojure.lang.ExceptionInfo error error)))
              (reset! ask-error
                      (try
                        (binding [ec/*execution-context* (:ctx fork)]
                          @(d/ask fork :nobody {:content "late"}))
                        (catch clojure.lang.ExceptionInfo error error)))
              (reset! listener-error
                      (try
                        (d/on-each-message fork (fn [_]))
                        (catch clojure.lang.ExceptionInfo error error)))
              :prepared)
            :abort! (fn [_])})]
      (is (= ::d/fork-transfer-in-progress
             (:type (ex-data @join-error))))
      (is (= ::d/fork-transfer-in-progress
             (:type (ex-data @child-error))))
      (is (= ::d/fork-transfer-in-progress
             (:type (ex-data @post-error))))
      (is (= ::d/fork-transfer-in-progress
             (:type (ex-data @ask-error))))
      (is (= ::d/fork-transfer-in-progress
             (:type (ex-data @listener-error))))
      (ygg/discard-fork! (:fork/handle transfer))
      (d/release-transferred-fork! transfer))))

(deftest failed-compensation-keeps-the-world-fenced-for-recovery
  (binding [ec/*execution-context* *base-ctx*]
    (let [parent (d/room :transfer-recovery-parent *base-ctx*)
          fork (d/fork-room parent {:isolation :ctx})
          original (d/fork-handle fork)
          error (try
                  (d/transfer-fork!
                   fork :owner
                   {:prepare! (fn [_]
                                (ygg/transfer-fork! original :winner)
                                :prepared)
                    :abort! (fn [_]
                              (throw (ex-info "durable rollback failed" {})))})
                  (catch clojure.lang.ExceptionInfo error error))]
      (is (= ::d/fork-transfer-recovery-required (:type (ex-data error))))
      (is (= :closed (binding [ec/*execution-context* (:ctx fork)]
                       (ec/get-state [:dvergr/run-admissions (:id fork)]))))
      (is (= :recovery-required
             (get-in @(:meta fork) [:dvergr/fork-transfer-state :state])))
      ;; The test deliberately lost the winner handle; this verifies fencing,
      ;; not cleanup of a simulated external claimant.
      (registry/unregister! (:id fork)))))

(deftest transfer-race-aborts-the-durable-preparation
  (binding [ec/*execution-context* *base-ctx*]
    (let [parent (d/room :transfer-race-parent *base-ctx*)
          fork (d/fork-room parent {:isolation :ctx})
          original (d/fork-handle fork)
          winner (atom nil)
          aborted (atom nil)
          result (try
                   (d/transfer-fork!
                    fork (random-uuid)
                    {:prepare!
                     (fn [_]
                       ;; Models another claimant winning after our durable
                       ;; prepare but before our affine CAS.
                       (reset! winner (ygg/transfer-fork! original :winner))
                       :prepared-row)
                     :abort! #(reset! aborted %)})
                   (catch clojure.lang.ExceptionInfo error error))]
      (is (= :prepared-row @aborted))
      (is (= :winner (:fork/owner (ygg/fork-descriptor @winner))))
      (is (ygg/open-fork? @winner))
      (is (nil? (registry/lookup (:id fork)))
          "a stale claimant is detached from executable Room lookup")
      (is (= :closed (binding [ec/*execution-context* (:ctx fork)]
                       (ec/get-state [:dvergr/run-admissions (:id fork)]))))
      (ygg/discard-fork! @winner)
      (d/release-transferred-fork! {:fork/handle @winner
                                    :fork/room-id (:id fork)
                                    :fork/descriptor (assoc (ygg/fork-descriptor @winner)
                                                            :dvergr/room-id (:id fork))})
      (is (= ::ygg/stale-fork-handle (:type (ex-data result)))))))

(deftest parent-transfer-requires-children-to-settle-first
  (binding [ec/*execution-context* *base-ctx*]
    (let [root (d/room :transfer-tree-root *base-ctx*)
          parent (d/fork-room root {:isolation :ctx})
          child (binding [ec/*execution-context* (:ctx parent)]
                  (d/fork-room parent {:isolation :ctx}))
          prepared? (atom false)
          error (try
                  (d/transfer-fork! parent (random-uuid)
                                    {:prepare! #(do (reset! prepared? true) %)
                                     :abort! (fn [_])})
                  (catch clojure.lang.ExceptionInfo error error))]
      (is (= ::d/fork-has-open-children (:type (ex-data error))))
      (is (false? @prepared?)
          "structural ownership is checked before durable proposal creation")
      (is (ygg/open-fork? (d/fork-handle parent)))
      (is (ygg/open-fork? (d/fork-handle child)))
      (binding [ec/*execution-context* (:ctx parent)]
        (d/discard child))
      (d/discard parent))))

(deftest transferred-child-remains-structurally-owned-until-settlement
  (binding [ec/*execution-context* *base-ctx*]
    (let [root (d/room :transfer-adopted-tree-root *base-ctx*)
          parent (d/fork-room root {:isolation :ctx})
          child (binding [ec/*execution-context* (:ctx parent)]
                  (d/fork-room parent {:isolation :ctx}))
          child-transfer
          (d/transfer-fork! child :child-owner
                            {:prepare! (fn [_] :child-row)
                             :abort! (fn [_])})
          blocked (try
                    (d/transfer-fork! parent :parent-owner
                                      {:prepare! (fn [_] :parent-row)
                                       :abort! (fn [_])})
                    (catch clojure.lang.ExceptionInfo error error))]
      (is (= ::d/fork-has-open-children (:type (ex-data blocked))))
      (let [stale (:fork/handle child-transfer)
            successor (ygg/transfer-fork! stale :successor)]
        (is (= ::d/transferred-fork-not-settled
               (:type (ex-data (try
                                 (d/release-transferred-fork! child-transfer)
                                 (catch clojure.lang.ExceptionInfo error error))))))
        (ygg/discard-fork! successor)
        (is (= ::d/transferred-fork-identity-mismatch
               (:type (ex-data
                       (try
                         (d/release-transferred-fork!
                          (assoc child-transfer
                                 :fork/handle successor
                                 :fork/room-id (:id parent)))
                         (catch clojure.lang.ExceptionInfo error error))))))
        (d/release-transferred-fork! (assoc child-transfer :fork/handle successor)))
      (let [parent-transfer
            (d/transfer-fork! parent :parent-owner
                              {:prepare! (fn [_] :parent-row)
                               :abort! (fn [_])})]
        (ygg/discard-fork! (:fork/handle parent-transfer))
        (d/release-transferred-fork! parent-transfer)))))

(deftest transferred-ancestry-requires-a-terminal-authority-state
  (binding [ec/*execution-context* *base-ctx*]
    (let [root (d/room :transfer-terminal-root *base-ctx*)
          fork (d/fork-room root {:isolation :ctx})
          transfer (d/transfer-fork! fork :owner
                                     {:prepare! (fn [_] :row)
                                      :abort! (fn [_])})
          handle (:fork/handle transfer)
          authority (:authority handle)
          open-state @authority]
      (doseq [status [:settling :incomplete]]
        (swap! authority assoc :status status)
        (is (= ::d/transferred-fork-not-settled
               (:type (ex-data (try
                                 (d/release-transferred-fork! transfer)
                                 (catch clojure.lang.ExceptionInfo error error)))))))
      (reset! authority open-state)
      (ygg/discard-fork! handle)
      (d/release-transferred-fork! transfer))))

(deftest failed-merge-reopens-listener-admission
  (binding [ec/*execution-context* *base-ctx*]
    (let [parent (d/room :failed-merge-listener-parent *base-ctx*)
          fork (d/fork-room parent {:isolation :ctx})
          relayed (promise)
          _listener (d/on-each-message fork (fn [_] (deliver relayed true)))
          error (try
                  (with-redefs [ygg/merge-fork! (fn [& _]
                                                  (throw (ex-info "preflight conflict" {})))]
                    (d/merge-room parent fork))
                  (catch clojure.lang.ExceptionInfo error error))]
      (is (= "preflight conflict" (ex-message error)))
      (is (ygg/open-fork? (d/fork-handle fork)))
      (let [controller (sandbox-work/create!
                        (:id fork) (:incarnation fork) (:ctx fork) nil :serial {}
                        (fn [value] (sp/spin value)))]
        (is (some? controller)
            "failed settlement reopens the exact SCI work-admission generation")
        (binding [ec/*execution-context* (:ctx fork)]
          (sandbox-work/close! controller)))
      (d/post! fork (d/message :human nil "relay after failed merge"))
      (is (= true (deref relayed 2000 ::timeout)))
      (d/discard fork))))

(deftest concurrent-merge-has-one-integration-owner
  (binding [ec/*execution-context* *base-ctx*]
    (let [parent (d/room :single-merge-owner-parent *base-ctx*)
          fork (d/fork-room parent {:isolation :none :clone-participants? false})
          entered (promise)
          release (promise)
          _listener (d/on-each-message
                     fork
                     (fn [_]
                       (deliver entered true)
                       @release))]
      (d/post! fork (d/message :human nil "merge exactly once"))
      (is (= true (deref entered 2000 ::timeout)))
      (let [first-merge (future
                          (binding [ec/*execution-context* *base-ctx*]
                            (d/merge-room parent fork)))
            _ (Thread/sleep 50)
            contender (try
                        (d/merge-room parent fork)
                        (catch clojure.lang.ExceptionInfo error error))]
        (is (= ::d/room-lifecycle-in-progress (:type (ex-data contender))))
        (deliver release true)
        (is (identical? parent (deref first-merge 5000 ::timeout)))
        (is (= 1 (count (filter #(= "merge exactly once" (:content %))
                                (d/log parent)))))))))

(deftest room-messages-relay-to-peer-bus-with-scope-tag
  (binding [ec/*execution-context* *base-ctx*]
    (let [parent (d/room :pp *base-ctx*)
          fork   (d/fork-room parent {:isolation :ctx})]
      (bus/post! (:bus parent) {:type :test/ping :from :tester :body "parent"})
      (bus/post! (:bus fork)   {:type :test/ping :from :tester :body "fork"})
      (Thread/sleep 100)
      (let [pings (binding [ec/*execution-context* *base-ctx*]
                    (->> (peer-bus/log)
                         (filter #(= :test/ping (:type %)))
                         vec))
            parent-ping (first (filter #(= :pp (:dvergr/origin %)) pings))
            fork-ping   (first (filter #(= (:id fork) (:dvergr/origin %)) pings))]
        (is (= 2 (count pings)))
        (testing "parent's relay is tagged :scope :room"
          (is (= :room (:dvergr/scope parent-ping))))
        (testing "fork's relay is tagged :scope :fork"
          (is (= :fork (:dvergr/scope fork-ping))))))))
