(ns dvergr.rooms.subtree-cas-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.async :as async]
            [datahike.api :as dh]
            [dvergr.rooms.files :as files]
            [dvergr.rooms.file-tx :as file-tx]
            [dvergr.rooms.publication :as publication]
            [dvergr.rooms.subtree :as subtree]
            [dvergr.rooms.targeted-files-test :as fixture]
            [dvergr.substrate.geschichte :as workspace]
            [geschichte.query :as query]
            [geschichte.repo :as repo])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:private prefix "/workflows/example/")

(defn- with-target [order f]
  (@#'fixture/with-repositories
   (if order ["room-repo-owned" "room-repo-other"] ["room-repo-other" "room-repo-owned"])
   (fn [room repositories]
     (let [owned (get repositories "room-repo-owned")]
       (doseq [[path text] {"main.clj" "main" "helper.clj" "helper"}]
         (repo/write! (:conn owned) (str (subs prefix 1) path) (.getBytes text "UTF-8")))
       (repo/stage-all! (:conn owned))
       (repo/commit! (:conn owned) {:message "Seed complete workflow"})
       (f room owned (get repositories "room-repo-other"))))))

(defn- condition [conn]
  {:prefix prefix :entries (subtree/prefix-tree! @conn prefix false)})

(defn- snapshot [conn] (@#'fixture/snapshot conn))
(defn- attempt [f] (try {:result (f)} (catch Exception error {:error error})))

(defn- deliver-report! [reply report transform]
  (try
    (let [payload (if (or (nil? report) (instance? Throwable report)) report (transform report))]
      (if (nil? payload) (async/close! reply) (async/put! reply payload)))
    (catch Throwable callback-error (async/put! reply callback-error))))

(defn- join! [^Thread thread]
  (loop [interrupted? false]
    (let [done? (try (.join thread) true (catch InterruptedException _ false))]
      (if done? (when interrupted? (.interrupt (Thread/currentThread))) (recur true)))))

(deftest native-entry-datoms-normalize-bounded-integer-dto-scalars-to-longs
  (let [value {:content (random-uuid) :size (int 7) :mode (int 33188)}
        expected {:prefix prefix :entries {"main.clj" value}}]
    (is (instance? Integer (:size value)))
    (is (instance? Integer (:mode value)))
    (is (= expected (subtree/validate! expected)))
    (with-redefs [dh/entity (fn [& _] nil)]
      (doseq [stage? [false true]]
        (let [entry (first (@#'subtree/entry-tx ::immutable-db "workflows/example/main.clj" value stage?))
              size (get entry (if stage? :geschichte.stage/size :geschichte.work/size))
              mode (get entry (if stage? :geschichte.stage/mode :geschichte.work/mode))]
          (is (= 7 size))
          (is (= 33188 mode))
          (is (instance? Long size))
          (is (instance? Long mode)))))))

(deftest exact-published-index-and-original-ack-ignore-selection-and-later-edits
  (doseq [order [true false]]
    (with-target order
      (fn [room {:keys [system conn]} other]
        (let [opened (condition conn) selection (workspace/selected-system-id)
              other-before (snapshot (:conn other))
              result (files/write-to! room system {"workflows/example/main.clj" {:text "updated"}}
                                      {:expected-subtree opened})
              commit (parse-uuid (:commit result))
              index (repo/tree-at conn commit)
              expected (condition conn)]
          (is (true? (:applied? result)))
          (is (uuid? commit))
          (is (= expected (:subtree result)))
          (is (= (get index "workflows/example/main.clj") (get-in result [:subtree :entries "main.clj"])))
          (is (= (get index "workflows/example/helper.clj") (get-in result [:subtree :entries "helper.clj"])))
          (repo/write! conn "workflows/example/helper.clj" (.getBytes "later draft" "UTF-8"))
          (is (= expected (:subtree result)) "Original ACK is not a later live worktree read")
          (is (not= (condition conn) (:subtree result)))
          (is (= selection (workspace/selected-system-id)))
          (is (= other-before (snapshot (:conn other)))))))))

(deftest untouched-dirty-untracked-and-deleted-helpers-are-not-silently-staged
  (doseq [mode [:dirty :untracked :deleted]]
    (with-target true
      (fn [room {:keys [system conn]} _]
        (let [helper "workflows/example/helper.clj"
              original-index (repo/index-tree conn) head (query/refs @conn)]
          (case mode
            :dirty (repo/write! conn helper (.getBytes "unsubmitted draft" "UTF-8"))
            :untracked (repo/write! conn "workflows/example/new-helper.clj" (.getBytes "unsubmitted new helper" "UTF-8"))
            :deleted (repo/remove! conn helper))
          (let [opened (condition conn)
                answer (attempt #(files/write-to! room system {"workflows/example/main.clj" {:text "updated"}}
                                                  {:expected-subtree opened}))]
            (is (:error answer))
            (is (nil? (:result answer)))
            (is (= head (query/refs @conn)))
            (is (= original-index (repo/index-tree conn)))
            (is (= (:entries opened) (:entries (condition conn))))))))))

(deftest explicitly-submitting-a-dirty-helper-is-not-implicit-staging
  (with-target true
    (fn [room {:keys [system conn]} _]
      (repo/write! conn "workflows/example/helper.clj" (.getBytes "explicit helper" "UTF-8"))
      (let [result (files/write-to! room system {"workflows/example/main.clj" {:text "updated"}
                                                 "workflows/example/helper.clj" {:text "explicit helper"}}
                                    {:expected-subtree (condition conn)})]
        (is (:applied? result))
        (is (= (:entries (:subtree result)) (subtree/prefix-tree! @conn prefix true)))))))

(deftest real-phantoms-before-preparation-and-after-preparation-refuse
  (doseq [when? [:after-write :after-prepare :writer]]
    (with-target true
      (fn [room {:keys [system conn]} _]
        (let [opened (condition conn) head (query/refs @conn)
              prepare repo/prepare-commit transact dh/transact! injected? (atom false)
              inject! #(when (compare-and-set! injected? false true)
                         (repo/write! conn "workflows/example/phantom.clj" (.getBytes "raw phantom" "UTF-8")))
              answer
              (with-redefs [repo/prepare-commit
                            (fn [& args]
                              (let [prepared (apply prepare args)]
                                (when (and (= :after-prepare when?) (identical? conn (first args)))
                                  (inject!)) prepared))
                            dh/transact!
                            (fn [c tx & args]
                              (when (and (= :writer when?)
                                         (identical? publication/prepared-write-tx (second (first tx))))
                                (inject!))
                              (apply transact c tx args))]
                (attempt #(files/write-to!
                           room system {"workflows/example/main.clj" {:text "updated"}}
                           {:expected-subtree opened
                            :boundary (constantly {:handlers [(fn [effect next]
                                                                (let [result (next effect)]
                                                                  (when (and (= :after-write when?) (= :fs/write (:effect effect)))
                                                                    (inject!)) result))]})})))]
          (is @injected?)
          (is (:error answer))
          (is (nil? (:result answer)))
          (is (= head (query/refs @conn)))
          (is (contains? (query/worktree @conn) "workflows/example/phantom.clj"))
          (when (not= :writer when?)
            (is (= (get-in opened [:entries "main.clj"])
                   (get (query/worktree @conn) "workflows/example/main.clj")))))))))

(deftest noop-needs-an-actual-transaction-and-a-writer-phantom-still-refuses
  (doseq [phantom? [false true]]
    (with-target true
      (fn [room {:keys [system conn]} _]
        (let [opened (condition conn) transact dh/transact! calls (atom 0)
              answer (with-redefs [dh/transact!
                                   (fn [c tx & args]
                                     (when (identical? publication/prepared-write-tx (second (first tx)))
                                       (swap! calls inc)
                                       (when phantom?
                                         (repo/write! conn "workflows/example/phantom.clj" (.getBytes "phantom" "UTF-8"))))
                                     (apply transact c tx args))]
                       (attempt #(files/write-to! room system {"workflows/example/main.clj" {:text "main"}}
                                                  {:expected-subtree opened})))]
          (is (= 1 @calls))
          (if phantom?
            (is (:error answer))
            (do (is (true? (get-in answer [:result :applied?])))
                (is (nil? (get-in answer [:result :commit])))
                (is (= opened (get-in answer [:result :subtree]))))))))))

(deftest malformed-worktree-and-index-path-rows-never-disappear-from-cas
  (with-target true
    (fn [_ {:keys [conn]} _]
      (let [db @conn expected (condition conn)
            work (:e (first (dh/index-range db {:attrid :geschichte.work/path :start "workflows/" :end "workflows0"})))
            stage (:e (first (dh/index-range db {:attrid :geschichte.stage/path :start "workflows/" :end "workflows0"})))]
        (doseq [tx [[[:db/retract work :geschichte.work/mode]]
                    [[:db/retract work :geschichte.work/size]]
                    [[:db/retract work :geschichte.work/content]]
                    [[:db/retract stage :geschichte.stage/state]]
                    [[:db/add stage :geschichte.stage/state :unknown]]
                    [[:db/retract stage :geschichte.stage/size]]
                    [[:db/retract stage :geschichte.stage/content]]
                    [{:geschichte.work/path "workflows/example/incomplete.clj"}]
                    [{:geschichte.stage/path "workflows/example/incomplete.clj"}]]]
          (let [corrupt (:db-after (dh/with db tx))]
            (is (thrown? Exception (subtree/assert-after! corrupt expected)))))))))

(deftest after-image-bounds-and-batch-containment-refuse-before-mutation
  (with-target true
    (fn [room {:keys [system conn]} _]
      (let [opened (condition conn) before (snapshot conn)]
        (doseq [[batch options] [[{"workflows/elsewhere/no.clj" {:text "escape"}} {}]
                                 [{"workflows/example/new.clj" {:text "new"}} {:limit :files}]
                                 [{"workflows/example/new.clj" {:text "xx"}} {:limit :bytes}]]]
          (let [call #(files/write-to! room system batch {:expected-subtree opened})]
            (case (:limit options)
              :files (with-redefs [subtree/max-entries 2] (is (thrown? Exception (call))))
              :bytes (with-redefs [subtree/max-total-bytes 11] (is (thrown? Exception (call))))
              (is (thrown? Exception (call))))
            (is (= before (snapshot conn)))))))))

(deftest original-report-loss-missing-or-stale-never-acknowledges-conditional-write
  (doseq [phase [:stage :commit] mode [:lost :missing-before :missing-after :stale-after] noop? [false true]]
    (with-target true
      (fn [room {:keys [system conn]} _]
        (let [opened (condition conn) transact dh/transact! calls (atom 0) transactions (atom [])
              selected (if (= phase :stage) subtree/stage-tx publication/prepared-write-tx)
              answer
              (with-redefs [dh/transact!
                            (fn [c tx & args]
                              (swap! transactions conj (second (first tx)))
                              (if-not (identical? selected (second (first tx)))
                                (apply transact c tx args)
                                (let [reply (async/promise-chan)]
                                  (swap! calls inc)
                                  (async/take! (apply transact c tx args)
                                               (fn [report]
                                                 ;; Fault only a successful original ACK.
                                                 (deliver-report! reply report
                                                                  #(case mode
                                                                     :lost (ex-info "Lost real original ACK" {})
                                                                     :missing-before (dissoc % :db-before)
                                                                     :missing-after (dissoc % :db-after)
                                                                     :stale-after (assoc % :db-after (:db-before %))))))
                                  reply)))]
                (attempt #(files/write-to! room system {"workflows/example/main.clj" {:text (if noop? "main" "updated")}}
                                           {:expected-subtree opened})))]
          (is (= 1 @calls))
          (is (:error answer))
          (is (publication/uncertain? (:error answer)))
          (is (nil? (:result answer)))
          (is (not-any? #(identical? publication/undo-subtree-tx %) @transactions))
          (when (= phase :stage)
            (is (not-any? #(identical? publication/prepared-write-tx %) @transactions)))
          ;; Inspection is diagnostic ONLY: native never reconstructed success
          ;; from this live state or restored after dispatched uncertainty.
          (is (= (if noop? "main" "updated") (String. ^bytes (repo/read conn "workflows/example/main.clj") "UTF-8"))))))))

(deftest created-directory-cleanup-observes-undo-datoms-in-the-same-writer
  (with-target true
    (fn [room {:keys [system conn]} _]
      (let [opened (condition conn) prepare repo/prepare-commit transact dh/transact!
            caller-thread (Thread/currentThread)
            with-db dh/with cleanup file-tx/remove-empty-dirs-tx
            undo-submissions (atom 0) persistent-projections (atom 0)
            physical-cleanups (atom []) foreign (atom nil)
            result
            (with-redefs [repo/prepare-commit
                          (fn [& args]
                            (if (identical? conn (first args))
                              (do (repo/write! conn "workflows/example/new/foreign/keep.md"
                                               (.getBytes "Concurrent sibling" "UTF-8"))
                                  ;; Raw repo/write! writes only the file row;
                                  ;; explicit directories must be seeded too.
                                  (dh/transact conn [{:geschichte.work-dir/path "workflows/example/new/foreign"}
                                                     {:geschichte.work-dir/path "workflows/example/new/foreign-empty"}])
                                  (is (some? (:db/id (dh/entity @conn [:geschichte.work-dir/path "workflows/example/new/foreign"]))))
                                  (reset! foreign (get (query/worktree @conn) "workflows/example/new/foreign/keep.md"))
                                  (throw (ex-info "Predispatch failure after stage ACK" {})))
                              (apply prepare args)))
                          dh/transact!
                          (fn [c tx & args]
                            (when (identical? publication/undo-subtree-tx (second (first tx)))
                              (swap! undo-submissions inc))
                            (apply transact c tx args))
                          dh/with
                          (fn [db tx & args]
                            (when (.editable ^org.replikativ.persistent_sorted_set.PersistentSortedSet (:eavt db))
                              (throw (AssertionError. "Never nest d/with on the transient writer DB")))
                            (swap! persistent-projections inc)
                            (apply with-db db tx args))
                          file-tx/remove-empty-dirs-tx
                          (fn [db dirs]
                            (when (and (not (identical? caller-thread (Thread/currentThread)))
                                       (.editable ^org.replikativ.persistent_sorted_set.PersistentSortedSet (:eavt db)))
                              (swap! physical-cleanups conj
                                     {:created? (boolean (:db/id (dh/entity db [:geschichte.work/path "workflows/example/new/deeper/created.clj"])))
                                      :foreign? (boolean (:db/id (dh/entity db [:geschichte.work/path "workflows/example/new/foreign/keep.md"])))}))
                            (cleanup db dirs))]
              (attempt #(files/write-to! room system
                                         {"workflows/example/new/deeper/created.clj" {:text "Native draft"}}
                                         {:expected-subtree opened})))]
        (is (:error result))
        (is (not (publication/uncertain? (:error result))))
        (is (= 1 @undo-submissions (count @physical-cleanups)))
        (is (= [{:created? false :foreign? true}] @physical-cleanups)
            "Fixed nested cleanup sees the actual post-undo writer state")
        (is (pos? @persistent-projections) "ACK checks may project original persistent db-before")
        (is (nil? (:db/id (dh/entity @conn [:geschichte.work/path "workflows/example/new/deeper/created.clj"]))))
        (is (nil? (:db/id (dh/entity @conn [:geschichte.work-dir/path "workflows/example/new/deeper"]))))
        (is (some? (:db/id (dh/entity @conn [:geschichte.work-dir/path "workflows/example/new"]))))
        (is (some? (:db/id (dh/entity @conn [:geschichte.work-dir/path "workflows/example/new/foreign"]))))
        (is (some? (:db/id (dh/entity @conn [:geschichte.work-dir/path "workflows/example/new/foreign-empty"]))))
        (is (= @foreign (get (query/worktree @conn) "workflows/example/new/foreign/keep.md")))
        (is (= "Concurrent sibling" (String. ^bytes (repo/read conn "workflows/example/new/foreign/keep.md") "UTF-8")))))))

(deftest lost-undo-ack-cannot-be-hidden-by-outer-effect-handler
  (with-target true
    (fn [room {:keys [system conn]} _]
      (let [opened (condition conn) prepare repo/prepare-commit transact dh/transact!
            calls (atom []) swallowed (atom 0)
            result
            (with-redefs [repo/prepare-commit
                          (fn [& args]
                            (if (identical? conn (first args))
                              (throw (ex-info "Predispatch preparation failure" {}))
                              (apply prepare args)))
                          dh/transact!
                          (fn [c tx & args]
                            (let [f (second (first tx))]
                              (swap! calls conj f)
                              (if (identical? publication/undo-subtree-tx f)
                                (let [reply (async/promise-chan)]
                                  (async/take! (apply transact c tx args)
                                               (fn [report]
                                                 (deliver-report! reply report
                                                                  (fn [_] (ex-info "Lost actual undo ACK" {})))))
                                  reply)
                                (apply transact c tx args))))]
              (files/write-to-completion!
               room system {"workflows/example/main.clj" {:text "updated"}}
               {:expected-subtree opened
                :boundary (constantly {:handlers [(fn [effect next]
                                                    (if (= :git/commit (:effect effect))
                                                      (try (next effect)
                                                           (catch Throwable _ (swap! swallowed inc) {:applied? false}))
                                                      (next effect)))]})}))]
        (is (= :pending (:completion result)))
        (is (instance? Throwable (:error result)))
        (is (publication/uncertain? (:error result)))
        (is (not (true? (get-in result [:result :applied?]))))
        (is (= 1 @swallowed))
        (is (= 1 (count (filter #(identical? subtree/stage-tx %) @calls))))
        (is (= 1 (count (filter #(identical? publication/undo-subtree-tx %) @calls))))
        (is (not-any? #(identical? publication/prepared-write-tx %) @calls))
        ;; This diagnostic is not used to infer completion or retry authority.
        (is (= opened (condition conn)))))))

(deftest cleanup-never-crosses-a-logical-checkout-even-with-identical-files
  (with-target true
    (fn [room {:keys [system conn]} _]
      (let [opened (condition conn) prepare repo/prepare-commit transact dh/transact!
            foreign (atom nil) calls (atom [])
            result
            (with-redefs [repo/prepare-commit
                          (fn [& args]
                            (if (identical? conn (first args))
                              (do (repo/branch! conn "foreign-ref")
                                  (repo/checkout! conn "foreign-ref" {:force? true})
                                  (reset! foreign (snapshot conn))
                                  (throw (ex-info "Stopped after foreign checkout" {})))
                              (apply prepare args)))
                          dh/transact!
                          (fn [c tx & args]
                            (swap! calls conj (second (first tx)))
                            (apply transact c tx args))]
              (files/write-to-completion! room system {"workflows/example/main.clj" {:text "updated"}}
                                          {:expected-subtree opened}))]
        (is @foreign)
        (is (= :pending (:completion result)))
        (is (= @foreign (snapshot conn)))
        (is (not-any? #(identical? publication/undo-subtree-tx %) @calls))
        (is (not-any? #(identical? publication/prepared-write-tx %) @calls))))))

(deftest stage-writer-refuses-a-checkout-after-its-pure-preflight
  (with-target true
    (fn [room {:keys [system conn]} _]
      (let [opened (condition conn) transact dh/transact! switched? (atom false)
            foreign (atom nil) calls (atom [])
            result
            (with-redefs [dh/transact!
                          (fn [c tx & args]
                            (let [f (second (first tx))]
                              (swap! calls conj f)
                              (when (and (identical? subtree/stage-tx f)
                                         (compare-and-set! switched? false true))
                                (repo/branch! conn "racing-ref")
                                (repo/checkout! conn "racing-ref" {:force? true})
                                (reset! foreign (snapshot conn)))
                              (apply transact c tx args)))]
              (files/write-to-completion! room system {"workflows/example/main.clj" {:text "updated"}}
                                          {:expected-subtree opened}))]
        (is @switched?)
        (is (= :pending (:completion result)))
        (is (= @foreign (snapshot conn)))
        (is (not-any? #(identical? publication/undo-subtree-tx %) @calls))
        (is (not-any? #(identical? publication/prepared-write-tx %) @calls))))))

(deftest inclusive-range-successor-is-not-a-workflow-path-or-an-extra-row
  (with-target true
    (fn [_ {:keys [conn]} _]
      (let [opened (condition conn)]
        (repo/write! conn "workflows/example0" (.getBytes "unrelated successor" "UTF-8"))
        (repo/stage! conn ["workflows/example0"])
        (with-redefs [subtree/max-entries 2 subtree/max-index-rows 2]
          (doseq [stage? [false true]]
            (is (= (:entries opened) (subtree/prefix-tree! @conn prefix stage?)))))
        (repo/write! conn "workflows/example/third.clj" (.getBytes "bounded overflow" "UTF-8"))
        (repo/stage! conn ["workflows/example/third.clj"])
        (with-redefs [subtree/max-entries 2 subtree/max-index-rows 2]
          (doseq [stage? [false true]]
            (is (thrown? Exception (subtree/prefix-tree! @conn prefix stage?)))))))))

(deftest stage-and-undo-reject-different-physical-config-before-any-tx-data
  (with-target true
    (fn [_ {:keys [conn]} _]
      (let [db @conn opened (condition conn)
            basis {:plan (repo/prepare-commit (atom db) {:message "Identity proof"})
                   :before opened :index-prefix (:entries opened) :stage-rows {}}
            paths ["/workflows/example/main.clj"]]
        (doseq [foreign [(assoc-in db [:config :store :id] (random-uuid))
                         (assoc-in db [:config :store :backend] :foreign)
                         (assoc-in db [:config :branch] :foreign)]]
          (is (thrown? Exception (subtree/stage-tx foreign basis opened paths)))
          (is (thrown? Exception (publication/undo-subtree-tx foreign basis opened paths true paths []))))))))

(deftest durable-dedup-precedes-fresh-subtree-checks-after-later-human-edits
  (with-target true
    (fn [room {:keys [system conn]} _]
      (let [opened (condition conn) journal (atom [])
            options {:expected-subtree opened :publication (@#'fixture/publication-options conn journal)}
            batch {"workflows/example/main.clj" {:text "updated"}}
            first-result (files/write-to! room system batch options)]
        (is (:applied? first-result))
        (is (= (condition conn) (:subtree first-result)))
        (repo/write! conn "workflows/example/helper.clj" (.getBytes "later human draft" "UTF-8"))
        (let [before (snapshot conn)
              recovered (files/write-to! room system batch options)]
          (is (:recovered? recovered))
          (is (= (:publication first-result) (:publication recovered)))
          (is (not (contains? recovered :subtree)) "Historical receipt is NOT a new current-source projection")
          (is (= 1 (count @journal)))
          (is (= before (snapshot conn))))))))

(deftest create-delete-all-and-explicit-or-preserved-modes-use-the-same-contract
  (with-target true
    (fn [room {:keys [system conn]} _]
      (repo/write! conn "workflows/example/main.clj" (.getBytes "main" "UTF-8") {:mode 33261})
      (repo/stage-all! conn) (repo/commit! conn {:message "Executable original"})
      (let [preserved (files/write-to! room system {"workflows/example/main.clj" {:text "executable"}}
                                       {:expected-subtree (condition conn)})
            explicit (files/write-to! room system {"workflows/example/main.clj" {:text "regular"}}
                                      {:expected-subtree (condition conn) :file-modes {"/workflows/example/main.clj" 33188}})]
        (is (= 33261 (get-in preserved [:subtree :entries "main.clj" :mode])))
        (is (= 33188 (get-in explicit [:subtree :entries "main.clj" :mode]))))
      (let [deleted (files/write-to! room system {"workflows/example/main.clj" nil "workflows/example/helper.clj" nil}
                                     {:expected-subtree (condition conn)})]
        (is (= {:prefix prefix :entries {}} (:subtree deleted)))
        (is (empty? (subtree/prefix-tree! @conn prefix true))))
      (let [created (files/write-to! room system {"workflows/new/main.clj" {:text "new"}}
                                     {:expected-subtree {:prefix "/workflows/new/" :entries {}}})]
        (is (:applied? created))
        (is (= #{"main.clj"} (set (keys (get-in created [:subtree :entries])))))))))

(deftest a-stale-before-image-refuses-before-any-canonical-mutation
  (with-target true
    (fn [room {:keys [system conn]} _]
      (let [opened (condition conn)]
        (repo/write! conn "workflows/example/helper.clj" (.getBytes "changed before call" "UTF-8"))
        (let [before (snapshot conn)
              answer (files/write-to-completion! room system {"workflows/example/main.clj" {:text "never written"}}
                                                 {:expected-subtree opened})]
          (is (= :not-started (:completion answer)))
          (is (instance? Throwable (:error answer)))
          (is (= before (snapshot conn))))))))

(deftest raw-outside-prefix-work-is-not-staged-or-committed
  (with-target true
    (fn [room {:keys [system conn]} _]
      (let [opened (condition conn) once (atom false)
            result (files/write-to! room system {"workflows/example/main.clj" {:text "updated"}}
                                    {:expected-subtree opened
                                     :boundary (constantly
                                                {:handlers [(fn [effect next]
                                                              (let [result (next effect)]
                                                                (when (and (= :fs/write (:effect effect))
                                                                           (compare-and-set! once false true))
                                                                  (repo/write! conn "outside.txt" (.getBytes "raw outside" "UTF-8")))
                                                                result))]})})]
        (is (:applied? result))
        (is (contains? (query/worktree @conn) "outside.txt"))
        (is (not (contains? (repo/tree-at conn (parse-uuid (:commit result))) "outside.txt")))))))

(deftest interruption-waits-for-physical-ack-and-restores-flag-after-validation
  (doseq [phase [:stage :commit]]
    (with-target true
      (fn [room {:keys [system conn]} _]
        (let [opened (condition conn) transact dh/transact!
              selected (if (= phase :stage) subtree/stage-tx publication/prepared-write-tx)
              transactions (atom [])
              committed (CountDownLatch. 1) release (CountDownLatch. 1)
              relay (atom nil) calls (atom 0) answer (promise)
              worker (Thread. ^Runnable
                      (bound-fn []
                        (try
                          (let [result (files/write-to! room system
                                                        {"workflows/example/main.clj" {:text "updated"}}
                                                        {:expected-subtree opened})]
                            (deliver answer {:result result :interrupted? (.isInterrupted (Thread/currentThread))}))
                          (catch Throwable error (deliver answer {:error error :interrupted? (.isInterrupted (Thread/currentThread))})))))]
          (with-redefs [dh/transact!
                        (fn [c tx & args]
                          (swap! transactions conj (second (first tx)))
                          (if-not (identical? selected (second (first tx)))
                            (apply transact c tx args)
                            (let [native (apply transact c tx args) reply (async/promise-chan)
                                  t (Thread. ^Runnable
                                     (fn []
                                       (try
                                         (let [report (async/<!! native)]
                                           (.countDown committed)
                                           (.await release)
                                           (async/>!! reply report))
                                         (catch Throwable error (async/>!! reply error))
                                         (finally (.countDown committed)))))]
                              (swap! calls inc) (reset! relay t) (.start t) reply)))]
            (.start worker)
            (try
              (is (.await committed 10 TimeUnit/SECONDS))
              (.interrupt worker)
              (is (.isAlive worker) "Interruption is not physical acknowledgement")
              (.countDown release)
              (join! worker)
              (when-let [t @relay] (join! t))
              (let [value (deref answer 1000 ::missing-after-physical-exit)]
                (is (true? (:interrupted? value)))
                (if (= phase :stage)
                  (do (is (publication/uncertain? (:error value)))
                      (is (nil? (:result value)))
                      (is (not-any? #(identical? publication/prepared-write-tx %) @transactions)))
                  (do (is (nil? (:error value)))
                      (is (true? (get-in value [:result :applied?])))
                      (is (= (condition conn) (get-in value [:result :subtree]))))))
              (is (not-any? #(identical? publication/undo-subtree-tx %) @transactions))
              (is (= 1 @calls))
              (finally (.countDown release) (join! worker) (when-let [t @relay] (join! t))))))))))

(deftest sealed-subtree-skip-closes-saved-forwarding-before-normal-return
  (with-target true
    (fn [room {:keys [system conn]} _]
      (let [opened (condition conn) saved (atom nil) before (snapshot conn)
            closed (files/write-to-completion!
                    room system {"workflows/example/main.clj" {:text "never written"}}
                    {:expected-subtree opened
                     :boundary (constantly {:handlers [(fn [effect next]
                                                         (reset! saved #(next effect))
                                                         {:applied? true :subtree opened})]})})]
        (is (= :not-started (:completion closed)))
        (is (false? (get-in closed [:result :applied?])))
        (is (= before (snapshot conn)))
        (is (= ::files/closed-forwarding
               (try (@saved) nil (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))))
        (is (= before (snapshot conn)))))))

(deftest sealed-private-positive-subtree-ack-wins-after-outer-throw-including-noop
  (doseq [noop? [false true]]
    (with-target true
      (fn [room {:keys [system conn]} _]
        (let [opened (condition conn) saved (atom nil)
              closed (files/write-to-completion!
                      room system {"workflows/example/main.clj" {:text (if noop? "main" "updated")}}
                      {:expected-subtree opened
                       :boundary (constantly {:handlers [(fn [effect next]
                                                           (when (= :git/commit (:effect effect))
                                                             (reset! saved #(next effect)))
                                                           (let [result (next effect)]
                                                             (when (= :git/commit (:effect effect))
                                                               (throw (ex-info "After real conditional acknowledgement" {})))
                                                             result))]})})
              after (snapshot conn)]
          (is (= :returned (:completion closed)))
          (is (instance? Throwable (:error closed)))
          (is (true? (get-in closed [:result :applied?])))
          (is (= (condition conn) (get-in closed [:result :subtree])))
          (is (= noop? (nil? (get-in closed [:result :commit]))))
          (is (= ::files/closed-forwarding
                 (try (@saved) nil (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))))
          (is (= after (snapshot conn))))))))

(defn- stage-entry! [conn entry]
  (dh/transact conn [{:geschichte.stage/path "workflows/example/main.clj"
                      :geschichte.stage/state :present
                      :geschichte.stage/content [:geschichte.content/id (:content entry)]
                      :geschichte.stage/size (:size entry) :geschichte.stage/mode (:mode entry)}]))

(deftest raw-index-before-stage-is-neither-adopted-as-original-nor-undone-without-ack
  (doseq [foreign-kind [:same-after :different]]
    (with-target true
      (fn [room {:keys [system conn]} _]
        (let [opened (condition conn) foreign (atom nil) calls (atom 0) transact dh/transact!
              answer
              (with-redefs [dh/transact!
                            (fn [c tx & args]
                              (when (identical? subtree/stage-tx (second (first tx))) (swap! calls inc))
                              (apply transact c tx args))]
                (attempt #(files/write-to!
                           room system {"workflows/example/main.clj" {:text "updated"}}
                           {:expected-subtree opened
                            :boundary (constantly
                                       {:handlers [(fn [effect next]
                                                     (let [result (next effect)]
                                                       (when (= :fs/write (:effect effect))
                                                         (let [entry (if (= :same-after foreign-kind)
                                                                       (get (query/worktree @conn) "workflows/example/main.clj")
                                                                       (assoc (get-in opened [:entries "helper.clj"]) :mode 33261))]
                                                           (reset! foreign entry)
                                                           (stage-entry! conn entry))) result))]})})))]
          (is (:error answer))
          (is (zero? @calls) "Pure stage CAS refusal means no original native stage ACK")
          (is (= @foreign (get (repo/index-tree conn) "workflows/example/main.clj")))
          (is (= (get-in opened [:entries "main.clj"])
                 (get (query/worktree @conn) "workflows/example/main.clj"))))))))

(deftest initial-no-staged-proof-does-not-capture-a-racing-foreign-index
  (with-target true
    (fn [room {:keys [system conn]} _]
      (let [opened (condition conn) prepare repo/prepare-commit injected? (atom false)
            foreign (assoc (get-in opened [:entries "helper.clj"]) :mode 33261)
            answer (with-redefs [repo/prepare-commit
                                 (fn [& args]
                                   (let [plan (apply prepare args)]
                                     (when (and (not (identical? conn (first args)))
                                                (compare-and-set! injected? false true))
                                       (stage-entry! conn foreign)) plan))]
                     (attempt #(files/write-to! room system {"workflows/example/main.clj" {:text "updated"}}
                                                {:expected-subtree opened})))]
        (is @injected?)
        (is (:error answer))
        (is (= foreign (get (repo/index-tree conn) "workflows/example/main.clj")))))))

(deftest undo-preserves-raw-index-after-stage-ack-and-worktree-mode-or-content-races
  (doseq [mode [:index-mode :work-mode :work-content]]
    (with-target true
      (fn [room {:keys [system conn]} _]
        (let [opened (condition conn) prepare repo/prepare-commit foreign (atom nil)
              change! (fn []
                        (let [native (get (query/worktree @conn) "workflows/example/main.clj")
                              entry (case mode
                                      :index-mode (assoc native :mode 33261)
                                      :work-mode (assoc native :mode 33261)
                                      :work-content (get-in opened [:entries "helper.clj"]))]
                          (reset! foreign entry)
                          (if (= :index-mode mode) (stage-entry! conn entry)
                              (dh/transact conn [{:geschichte.work/path "workflows/example/main.clj"
                                                  :geschichte.work/content [:geschichte.content/id (:content entry)]
                                                  :geschichte.work/size (:size entry) :geschichte.work/mode (:mode entry)}]))))
              answer
              (with-redefs [repo/prepare-commit
                            (fn [& args]
                              (when (and (= :index-mode mode) (identical? conn (first args))) (change!))
                              (apply prepare args))]
                (attempt #(files/write-to!
                           room system {"workflows/example/main.clj" {:text "updated"}}
                           {:expected-subtree opened
                            :boundary (constantly {:handlers [(fn [effect next]
                                                                (let [result (next effect)]
                                                                  (when (and (not= :index-mode mode) (= :fs/write (:effect effect)))
                                                                    (change!)) result))]})})))]
          (is (:error answer))
          (is @foreign)
          (is (= @foreign (get (if (= :index-mode mode) (repo/index-tree conn) (query/worktree @conn))
                               "workflows/example/main.clj"))))))))
