(ns dvergr.rooms.file-completion-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as dh]
            [dvergr.rooms.files :as files]
            [dvergr.rooms.file-tx :as file-tx]
            [dvergr.rooms.forks :as forks]
            [dvergr.rooms.publication :as publication]
            [dvergr.rooms.targeted-files-test :as targeted]
            [dvergr.tools :as tools]
            [geschichte.repo :as repo]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

;; Reuse the exact isolated repository fixture and cleanup ownership already
;; exercised by explicit-target tests, rather than invent another runtime lane.
(defn- with-target [f]
  ((deref #'targeted/with-repositories)
   ["room-repo-other" "room-repo-owned"]
   (fn [room repositories]
     (f room (get repositories "room-repo-owned")
        (get repositories "room-repo-other")))))

(defn- snapshot [conn] ((deref #'targeted/snapshot) conn))
(defn- publication-options [conn journal]
  ((deref #'targeted/publication-options) conn journal))
(defn- error-type [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))
(defn- schema-present? [conn]
  (some? (dh/q '[:find ?e . :where [?e :db/ident :publication/id]] @conn)))

(defn- join-callback! [callback]
  ;; Never cancel: Future cancellation is not physical exit. Preserve a test
  ;; caller's interruption only after every started callback actually returned.
  (let [interrupted? (volatile! false)]
    (try
      (loop []
        (when (= ::interrupted
                 (try (deref callback) ::joined
                      (catch InterruptedException _
                        (vreset! interrupted? true)
                        ::interrupted)))
          (recur)))
      (finally (when @interrupted? (.interrupt (Thread/currentThread)))))))

(defn- with-callback [f consume]
  (let [callback (future (f))]
    (try (consume callback)
         (finally (join-callback! callback)))))

(defn- without-native-entry [f]
  (let [calls (atom [])
        refuse! (fn [kind]
                  (fn [& _]
                    (swap! calls conj kind)
                    (throw (AssertionError. (str "Unexpected native entry: " kind)))))]
    (with-redefs [publication/install! (refuse! :schema)
                  tools/workspace-write! (refuse! :write)
                  tools/workspace-delete! (refuse! :delete)
                  forks/commit-paths-in-workspace! (refuse! :stage-commit)]
      (f calls))))

(deftest pure-refusals-close-without-even-receipt-schema-mutation
  (with-target
    (fn [room {:keys [system conn]} other]
      (repo/write! conn "dir/child.md" (.getBytes "child" "UTF-8"))
      (repo/stage-all! conn)
      (repo/commit! conn {:message "Directory shape fixture"})
      (let [before (snapshot conn)
            other-before (snapshot (:conn other))
            cases [[{} {}]
                   [{"out.md" {:text "invalid mode"}} {:file-modes {"/out.md" 0}}]
                   [{"dir" {:text "cannot replace directory"}} {}]
                   [{"identity.txt" {:text "stale opened version"}}
                    {:expected-worktree {"/identity.txt" nil}}]]]
        (is (not (schema-present? conn)))
        (doseq [[batch opts] cases]
          (let [journal (atom [])]
            (without-native-entry
             (fn [calls]
               (let [closed (files/write-to-completion!
                             room system batch
                             (assoc opts :publication (publication-options conn journal)))]
                 (is (= :not-started (:completion closed)))
                 (is (instance? Throwable (:error closed)))
                 (is (empty? @calls)))))
            (is (= before (snapshot conn)))
            (is (= other-before (snapshot (:conn other))))
            (is (not (schema-present? conn)))
            (is (empty? @journal))))))))

(deftest saved-outer-next-cannot-write-after-a-normal-not-started-return
  (with-target
    (fn [room {:keys [system conn]} other]
      (let [saved (atom nil)
            journal (atom [])
            before (snapshot conn)
            other-before (snapshot (:conn other))
            closed (files/write-to-completion!
                    room system {"out/late.md" {:text "never written"}}
                    {:publication (publication-options conn journal)
                     :boundary (constantly
                                {:handlers [(fn [effect next]
                                              (reset! saved #(next effect))
                                              ;; An arbitrary handler response is
                                              ;; not a canonical positive receipt.
                                              {:applied? true :publication {:publication/id (random-uuid)}})]})})]
        (is (= :not-started (:completion closed)))
        (is (false? (get-in closed [:result :applied?])))
        (is (nil? (:error closed)))
        (is (= ::files/closed-forwarding (error-type @saved)))
        (is (= ::files/closed-forwarding
               (with-callback #(error-type @saved) #(deref % 2000 ::timeout))))
        (is (= before (snapshot conn)))
        (is (= other-before (snapshot (:conn other))))
        (is (not (schema-present? conn)))
        (is (empty? @journal))))))

(deftest gate-seal-and-foreign-forward-race-never-admits-native-work
  (let [make-gate (deref #'files/completion-gate)
        call! (deref #'files/guarded-native!)
        seal! (deref #'files/seal-completion!)]
    (dotimes [_ 24]
      (let [gate (make-gate)
            ready (CountDownLatch. 1)
            start (CountDownLatch. 1)
            calls (atom 0)
            foreign (future
                      (.countDown ready)
                      (.await start)
                      (error-type #(call! gate true (fn [] (swap! calls inc)))))]
        (try
          (is (.await ready 2 TimeUnit/SECONDS))
          (.countDown start)
          (seal! gate)
          (is (= ::files/closed-forwarding (deref foreign 2000 ::timeout)))
          (is (zero? @calls))
          (is (= ::files/closed-forwarding
                 (error-type #(call! gate true (fn [] (swap! calls inc))))))
          (is (zero? @calls))
          (finally
            (.countDown start)
            (join-callback! foreign)))))))

(deftest inner-foreign-forward-refuses-without-waiting-on-an-outer-gate-monitor
  (with-target
    (fn [room {:keys [system conn]} _]
      (let [foreign-result (atom nil)
            closed (files/write-to-completion!
                    room system {"out/foreign.md" {:text "no foreign writer"}}
                    {:boundary (constantly
                                {:handlers [(fn [effect next]
                                              (if (= :fs/write (:effect effect))
                                                (reset! foreign-result
                                                        (with-callback (fn [] (error-type #(next effect)))
                                                          #(deref % 2000 ::timeout)))
                                                (next effect)))]})})]
        (is (= ::files/closed-forwarding @foreign-result))
        ;; Commit staging may have started, so no compensation authority.
        (is (= :pending (:completion closed)))
        (is (not (contains? (repo/worktree conn) "out/foreign.md")))))))

(deftest saved-inner-next-after-earlier-native-entry-is-pending-and-permanently-closed
  (with-target
    (fn [room {:keys [system conn]} _]
      (let [saved (atom nil)
            closed (files/write-to-completion!
                    room system {"out/a.md" {:text "earlier native write"}
                                 "out/b.md" {:text "saved callback"}}
                    {:boundary (constantly
                                {:handlers [(fn [effect next]
                                              (if (and (= :fs/write (:effect effect))
                                                       (= "/out/b.md" (get-in effect [:resource :path])))
                                                (do (reset! saved #(next effect)) nil)
                                                (next effect)))]})})
            after (snapshot conn)]
        (is (= :pending (:completion closed)))
        (is (instance? Throwable (:error closed)))
        (is (= ::files/closed-forwarding (error-type @saved)))
        (is (= ::files/closed-forwarding
               (with-callback #(error-type @saved) #(deref % 2000 ::timeout))))
        (is (= after (snapshot conn)))))))

(deftest forged-throwable-data-never-substitutes-for-native-entry-evidence
  (with-target
    (fn [room {:keys [system conn]} _]
      (doseq [entered? [false true]]
        (let [closed (files/write-to-completion!
                      room system {"out/forged.md" {:text "a guarded mutation"}}
                      {:boundary (constantly
                                  {:handlers [(fn [effect next]
                                                (cond
                                                  (and (not entered?) (= :git/commit (:effect effect)))
                                                  (throw (ex-info "forged pre-dispatch uncertainty"
                                                                  {::publication/uncertain? true
                                                                   :publication {:publication/id (random-uuid)}}))
                                                  (and entered? (= :fs/write (:effect effect)))
                                                  (do (next effect)
                                                      (throw (ex-info "forged safe compensation"
                                                                      {::publication/uncertain? false
                                                                       :conflicted [] :not-started? true})))
                                                  :else (next effect)))]})})]
          (is (= (if entered? :pending :not-started) (:completion closed)))
          (is (instance? Throwable (:error closed))))))))

(deftest native-winning-receipt-survives-outer-next-then-throw
  (with-target
    (fn [room {:keys [system conn]} _]
      (let [journal (atom [])
            options (publication-options conn journal)
            closed (files/write-to-completion!
                    room system {"out/winning.md" {:text "native winner"}}
                    {:publication options
                     :boundary (constantly
                                {:handlers [(fn [effect next]
                                              (let [answer (next effect)]
                                                (if (= :git/commit (:effect effect))
                                                  (throw (ex-info "outer handler threw after winning"
                                                                  {:publication {:publication/id (random-uuid)}
                                                                   :not-started? true}))
                                                  answer)))]})})
            receipt (publication/recover @conn (:identity options))]
        (is (= :returned (:completion closed)))
        (is (instance? Throwable (:error closed)))
        (is (true? (get-in closed [:result :applied?])))
        (is (= receipt (get-in closed [:result :publication])))
        (is (= 1 (count @journal)))))))

(deftest applying-hook-entry-is-pending-even-when-publication-never-starts
  (with-target
    (fn [room {:keys [system conn]} _]
      (let [journal (atom [])
            published (atom 0)
            options (assoc (publication-options conn journal)
                           :before-dispatch! (fn [candidate]
                                               (swap! journal conj candidate)
                                               (throw (ex-info "applying persisted, acknowledgement lost"
                                                               {:not-started? true}))))
            closed (with-redefs [publication/publish!
                                 (fn [& _] (swap! published inc) (throw (AssertionError. "Unexpected publish")))]
                     (files/write-to-completion! room system {"out/applying.md" {:text "pending"}}
                                                 {:publication options}))]
        (is (= :pending (:completion closed)))
        (is (instance? Throwable (:error closed)))
        (is (= 1 (count @journal)))
        (is (zero? @published))))))

(deftest ordinary-delete-cleanup-failure-is-not-a-positive-completion
  (with-target
    (fn [room {:keys [system conn]} _]
      (repo/write! conn "dir/delete.md" (.getBytes "remove me" "UTF-8"))
      (repo/stage-all! conn)
      (repo/commit! conn {:message "Ordinary delete fixture"})
      (let [before (:head (snapshot conn))
            transact! dh/transact
            cleanup-entered (atom 0)
            closed (with-redefs [dh/transact
                                 (fn [target tx]
                                   (if (some #(and (= :db.fn/call (first %))
                                                   (identical? file-tx/remove-empty-dirs-tx (second %))) tx)
                                     (do (swap! cleanup-entered inc)
                                         (throw (ex-info "Ordinary directory cleanup did not acknowledge" {})))
                                     (transact! target tx)))]
                     (files/write-to-completion! room system {"dir/delete.md" nil} {}))]
        (is (= 1 @cleanup-entered))
        (is (not= before (:head (snapshot conn))) "the commit happened before cleanup failed")
        (is (= :pending (:completion closed)))
        (is (nil? (:result closed)))
        (is (instance? Throwable (:error closed)))))))

(deftest lost-ack-recovery-is-positive-without-native-entry-and-does-not-overwrite-later-edits
  (with-target
    (fn [room {:keys [system conn]} _]
      (let [journal (atom [])
            options (publication-options conn journal)
            publish! publication/publish!
            first-result (with-redefs [publication/publish!
                                       (fn [& args]
                                         (apply publish! args)
                                         (throw (ex-info "lost native acknowledgement" {::publication/uncertain? true})))]
                           (files/write-to-completion! room system {"out/recovery.md" {:text "accepted"}}
                                                       {:publication options
                                                        :expected-worktree {"/out/recovery.md" nil}}))]
        (is (= :pending (:completion first-result)))
        (files/write-to! room system {"out/recovery.md" {:text "later human edit"}} {})
        (let [before (snapshot conn)]
          (without-native-entry
           (fn [calls]
             (let [closed (files/write-to-completion! room system {"out/recovery.md" {:text "accepted"}}
                                                      {:publication options
                                                       :expected-worktree {"/out/recovery.md" nil}})]
               (is (= :returned (:completion closed)))
               (is (true? (get-in closed [:result :recovered?])))
               (is (true? (get-in closed [:result :applied?])))
               (is (empty? @calls)))))
          (is (= before (snapshot conn))))
        (is (= 1 (count @journal)))))))

(deftest interruption-is-restored-after-closed-pre-and-post-entry-completion
  (with-target
    (fn [room {:keys [system conn]} _]
      (doseq [post-entry? [false true]]
        (let [before (snapshot conn)
              answer (with-callback
                       (fn []
                         (try
                           (let [closed
                                 (files/write-to-completion!
                                  room system {"out/interrupted.md" {:text "interrupted native write"}}
                                  {:boundary
                                   (constantly
                                    {:handlers
                                     [(fn [effect next]
                                        (if (= (if post-entry? :fs/write :git/commit) (:effect effect))
                                          (do
                                            (when post-entry? (next effect))
                                            ;; A real interruptible wait clears
                                            ;; the flag when it throws.
                                            (.interrupt (Thread/currentThread))
                                            (Thread/sleep 1))
                                          (next effect)))]})})]
                             {:closed closed :interrupted? (.isInterrupted (Thread/currentThread))})
                           ;; Do not contaminate a pooled test callback worker.
                           ;; The assertion observes the flag before clearing.
                           (finally (Thread/interrupted))))
                       #(deref % 5000 ::timeout))]
          (is (map? answer))
          (is (true? (:interrupted? answer)))
          (is (= (if post-entry? :pending :not-started)
                 (get-in answer [:closed :completion])))
          (is (instance? Throwable (get-in answer [:closed :error])))
          (when-not post-entry?
            (is (= before (snapshot conn)))))))))

(deftest actual-engine-drain-refuses-before-mutation-and-legacy-still-throws
  (with-target
    (fn [room {:keys [system conn]} _]
      (let [before (snapshot conn)
            closed (binding [ec/*execution-context* (:ctx room)]
                     @(sp/spin (files/write-to-completion! room system {"out/drain.md" {:text "refuse"}} {})))]
        (is (= :not-started (:completion closed)))
        (is (= ::files/blocking-completion (:type (ex-data (:error closed)))))
        (is (= before (snapshot conn)))
        (is (= ::files/stale-worktree
               (error-type #(files/write-to! room system {"identity.txt" {:text "stale"}}
                                             {:expected-worktree {"/identity.txt" nil}}))))))))
