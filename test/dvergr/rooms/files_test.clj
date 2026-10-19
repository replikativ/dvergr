(ns dvergr.rooms.files-test
  "A room's workspace files from outside the room: listed, read as text or
   base64 in chunks, and written as one commit of exactly the named paths,
   through the effect boundary (a read-only world refuses and receipts it)."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [dvergr.catalog.workspace :as ws]
            [dvergr.discourse :as d]
            [dvergr.effects :as effects]
            [dvergr.intake.bash :as bash]
            [dvergr.mcp.surface :as surface]
            [dvergr.room.store.memory :as memory]
            [dvergr.rooms.files :as files]
            [dvergr.rooms.forks :as forks]
            [dvergr.rooms.publication :as publication]
            [dvergr.rooms.file-tx :as file-tx]
            [dvergr.substrate.geschichte :as gs]
            [dvergr.tools :as tools]
            [geschichte.repo :as repo]
            [muschel.fs :as mfs]
            [org.replikativ.spindel.engine.core :as ec])
  (:import [java.util Base64]))

(defn- room []
  (let [r (d/make-room {:id (keyword (str "files-test-" (random-uuid))) :store (memory/make)})]
    (ws/ensure-workspace! r)
    r))

(defn- conn [r] (binding [ec/*execution-context* (:ctx r)] (:conn (gs/current-workspace))))
(defn- commits [r] (count (repo/log (conn r) {:limit 1000})))
(defn- b64 [^bytes b] (.encodeToString (Base64/getEncoder) b))
(defn- unb64 [^String s] (.decode (Base64/getDecoder) s))

(defn- publication-options [r journal]
  (let [plan (repo/prepare-commit (conn r) {:message "Identify publication target"})]
    {:identity {:publication/id (random-uuid) :publication/intent (random-uuid)
                :publication/actor (random-uuid) :publication/manifest (random-uuid)
                :publication/repository (:repository plan)
                :publication/store (get-in plan [:workspace :store :id])
                :publication/backend (get-in plan [:workspace :store :backend])
                :publication/branch (get-in plan [:workspace :branch])
                :publication/ref (:ref plan)}
     :authorize! (constantly true)
     :before-dispatch! #(swap! journal conj %)}))

(deftest durable-publication-recovers-after-lost-ack-and-later-human-edit
  (let [r (room)
        journal (atom [])]
    (try
      (let [options (publication-options r journal)
            native-publish! publication/publish!
            error (with-redefs [publication/publish!
                                (fn [& args]
                                  (apply native-publish! args)
                                  (throw (ex-info "lost after commit"
                                                  {::publication/uncertain? true})))]
                    (try (files/write! r {"wiki/brief.md" {:text "accepted"}}
                                       {:publication options
                                        :expected-worktree {"/wiki/brief.md" nil}})
                         nil
                         (catch Exception error error)))
            committed (str (:geschichte.commit/id (repo/head-commit (conn r))))]
        (is (publication/uncertain? error))
        (is (= "accepted" (:text (files/read-file r "wiki/brief.md" {}))))
        (is (= 1 (count @journal)))
        (files/write! r {"wiki/brief.md" {:text "later human edit"}} {})
        (let [count-before (commits r)
              recovered (files/write! r {"wiki/brief.md" {:text "accepted"}}
                                      {:publication options
                                       :expected-worktree {"/wiki/brief.md" nil}})]
          (is (:recovered? recovered))
          (is (:applied? recovered))
          (is (= committed (:commit recovered)))
          (is (= count-before (commits r)))
          (is (= "later human edit" (:text (files/read-file r "wiki/brief.md" {}))))
          (is (= 1 (count @journal)))))
      (finally (d/close-room! r)))))

(deftest skipped-publication-does-not-produce-a-receipt-or-journal-dispatch
  (let [r (room)
        journal (atom [])]
    (try
      (let [options (publication-options r journal)
            result (files/write! r {"wiki/skipped.md" {:text "not written"}}
                                 {:publication options
                                  :boundary (constantly {:handlers [(fn [_ _] :skipped)]})})]
        (is (false? (:applied? result)))
        (is (nil? (:publication result)))
        (is (empty? @journal))
        (is (nil? (publication/recover @(conn r) (:identity options))))
        (is (not (mfs/exists? (ws/room-fs r) "/wiki/skipped.md"))))
      (finally (d/close-room! r)))))

(deftest failed-prepublication-journal-does-not-commit-the-files
  (let [r (room)
        journal (atom [])]
    (try
      (let [options (assoc (publication-options r journal)
                           :before-dispatch! (fn [_] (throw (ex-info "journal unavailable" {}))))
            before (commits r)]
        (is (thrown? Exception
                     (files/write! r {"wiki/not-committed.md" {:text "refused"}}
                                   {:publication options})))
        (is (= before (commits r)))
        (is (nil? (publication/recover @(conn r) (:identity options))))
        (is (not (mfs/exists? (ws/room-fs r) "/wiki/not-committed.md"))))
      (finally (d/close-room! r)))))

(deftest repeated-effect-forwarding-publishes-only-once
  (let [r (room)
        journal (atom [])]
    (try
      (let [options (publication-options r journal)
            before (commits r)
            result (files/write! r {"wiki/once.md" {:text "one"}}
                                 {:publication options
                                  :boundary (constantly {:handlers [(fn [effect next]
                                                                      (next effect)
                                                                      (when (= :git/commit (:effect effect))
                                                                        (next effect)))]})})]
        (is (:applied? result))
        (is (= 1 (count @journal)))
        (is (= (inc before) (commits r)))
        (is (= "one" (:text (files/read-file r "wiki/once.md" {}))))
        (is (= (:commit result) (str (:publication/commit (:publication result))))))
      (finally (d/close-room! r)))))

(deftest postpublication-handler-failure-preserves-recovery-evidence
  (let [r (room)
        journal (atom [])]
    (try
      (let [options (publication-options r journal)
            error (try (files/write! r {"wiki/committed.md" {:text "keep"}}
                                     {:publication options
                                      :boundary (constantly {:handlers [(fn [effect next]
                                                                          (next effect)
                                                                          (when (= :git/commit (:effect effect))
                                                                            (throw (ex-info "handler failed" {}))))]})})
                       nil
                       (catch Exception error error))]
        (is (publication/uncertain? error))
        (is (= "keep" (:text (files/read-file r "wiki/committed.md" {}))))
        (is (some? (publication/recover @(conn r) (:identity options)))))
      (finally (d/close-room! r)))))

(deftest unrelated-files-staged-during-preparation-do-not-enter-the-publication
  (let [r (room)
        journal (atom [])]
    (try
      (let [options (publication-options r journal)
            prepare publication/prepare
            before (commits r)]
        (with-redefs [publication/prepare
                      (fn [conn options paths]
                        (repo/write! conn "unrelated.md" (.getBytes "someone else's work" "UTF-8"))
                        (repo/stage! conn ["unrelated.md"])
                        (prepare conn options paths))]
          (is (thrown? Exception
                       (files/write! r {"wiki/ours.md" {:text "refuse"}} {:publication options}))))
        (is (= before (commits r)))
        (is (empty? @journal))
        (is (nil? (publication/recover @(conn r) (:identity options))))
        (is (= "someone else's work" (:text (files/read-file r "unrelated.md" {}))))
        (is (contains? (repo/index-tree (conn r)) "unrelated.md")))
      (finally (d/close-room! r)))))

(deftest publication-authorization-must-return-literal-true
  (let [r (room)
        journal (atom [])]
    (try
      (doseq [answer [false nil (ex-info "denied as a value" {}) (async/promise-chan)]]
        (let [options (assoc (publication-options r journal) :authorize! (constantly answer))]
          (is (thrown? Exception
                       (files/write! r {"wiki/denied.md" {:text "no"}} {:publication options})))
          (is (not (mfs/exists? (ws/room-fs r) "/wiki/denied.md")))))
      (is (empty? @journal))
      (finally (d/close-room! r)))))

(deftest deletion-cleanup-and-receipt-are-one-transaction
  (let [r (room)
        journal (atom [])]
    (try
      (files/write! r {"a/b/remove.md" {:text "gone"} "a/keep.md" {:text "keep"}} {})
      (let [options (publication-options r journal)
            result (files/write! r {"a/b/remove.md" nil} {:publication options})]
        (is (:publication result))
        (is (not (mfs/exists? (ws/room-fs r) "/a/b")))
        (is (mfs/exists? (ws/room-fs r) "/a"))
        (is (= "keep" (:text (files/read-file r "a/keep.md" {}))))
        ;; Recovery must short circuit before ANY cleanup after later edits.
        (files/write! r {"a/b/later.md" {:text "later"}} {})
        (let [again (with-redefs [file-tx/remove-empty-dirs-tx
                                  (fn [& _] (throw (ex-info "must not run on recovery" {})))]
                      (files/write! r {"a/b/remove.md" nil} {:publication options}))]
          (is (:recovered? again))
          (is (= (:commit result) (:commit again)))
          (is (= "later" (:text (files/read-file r "a/b/later.md" {}))))))
      (finally (d/close-room! r)))))

(deftest interrupted-deletion-does-not-start-a-second-postpublication-write
  (let [r (room)
        journal (atom [])]
    (try
      (files/write! r {"delete/only.md" {:text "gone"}} {})
      (let [publishing? (atom false)
            options (assoc (publication-options r journal)
                           :before-dispatch! (fn [receipt]
                                               (swap! journal conj receipt)
                                               (reset! publishing? true)))
            original-async dh/transact!
            original-sync dh/transact
            submissions (atom 0)
            dispatched (promise)
            acknowledgement (async/promise-chan)
            completed (promise)
            thread (Thread. (fn []
                              (deliver completed
                                       (try {:result (files/write! r {"delete/only.md" nil} {:publication options})
                                             :interrupted? (.isInterrupted (Thread/currentThread))}
                                            (catch Throwable error {:error error})))))]
        (with-redefs [dh/transact!
                      (fn [& args]
                        (if @publishing?
                          (do (swap! submissions inc)
                              ;; The native write really finishes. Only its
                              ;; acknowledgement is held for the interruption.
                              (deliver dispatched @(apply original-async args))
                              acknowledgement)
                          (apply original-async args)))
                      dh/transact
                      (fn [& args]
                        (when @publishing?
                          (throw (ex-info "Unexpected postpublication write" {})))
                        (apply original-sync args))]
          (.start thread)
          (let [report (deref dispatched 10000 ::timeout)]
            (is (map? report))
            (.interrupt thread)
            (is (not (realized? completed)))
            (async/>!! acknowledgement report))
          (let [answer (deref completed 10000 ::timeout)]
            (is (map? answer))
            (is (nil? (:error answer)))
            (is (:interrupted? answer))
            (is (get-in answer [:result :publication])))
          (.join thread 10000)
          (is (not (.isAlive thread)))
          (is (= 1 @submissions)))
        (is (not (mfs/exists? (ws/room-fs r) "/delete"))))
      (finally (d/close-room! r)))))

(deftest opened-worktree-preconditions-are-checked-inside-the-batch-snapshot
  (let [r (room)]
    (try
      (files/write! r {"a.md" {:text "opened"}} {})
      (let [opened {"/a.md" (get (repo/worktree (conn r)) "a.md")
                    "/new.md" nil}
            read-worktree repo/worktree
            raced? (atom false)
            commit-count (commits r)]
        ;; Inject an external writer immediately before the canonical batch
        ;; captures its before snapshot. A caller-side lock/check alone would
        ;; miss this edit and let the batch adopt it as its expected base.
        (with-redefs [repo/worktree (fn [c]
                                      (when (compare-and-set! raced? false true)
                                        (repo/write! c "a.md" (.getBytes "agent edit" "UTF-8")))
                                      (read-worktree c))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"changed since"
                                (files/write! r {"a.md" {:text "stale human"}
                                                 "new.md" {:text "must not appear"}}
                                              {:expected-worktree opened}))))
        (is @raced?)
        (is (= commit-count (commits r)))
        (is (= "agent edit" (:text (files/read-file r "a.md" {}))))
        (is (not (mfs/exists? (ws/room-fs r) "/new.md")))
        (let [now {"/a.md" (get (repo/worktree (conn r)) "a.md")}]
          (is (string? (:commit (files/write! r {"a.md" {:text "fresh human"}}
                                              {:expected-worktree now}))))))
      (finally (d/close-room! r)))))

(deftest a-forwarding-handler-cannot-replace-the-publication-receipt
  (let [r (room)]
    (try
      (let [boundary (constantly {:handlers [(fn [effect next] (next effect) :transformed-answer)]})
            result (files/write! r {"source.clj" {:text "(def x 1)"}} {:boundary boundary})]
        (is (:applied? result))
        (is (string? (:commit result)))
        (is (= (:commit result) (str (:geschichte.commit/id (repo/head-commit (conn r))))))
        (is (= (get (repo/tree (conn r) :head) "source.clj") (get-in result [:written 0 :entry]))))
      (finally (d/close-room! r)))))

(deftest expected-absence-and-all-path-coverage-are-not-optional-when-supplied
  (let [r (room)]
    (try
      (is (string? (:commit (files/write! r {"new.md" {:text "first"}}
                                          {:expected-worktree {"/new.md" nil}}))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (files/write! r {"new.md" {:text "clobber"}} {:expected-worktree {"/new.md" nil}})))
      (doseq [expected [nil {} {"new.md" nil} {"/new.md" nil "/extra.md" nil}
                        {"/new.md" {:content "not-a-uuid" :size 0 :mode 33188}}]]
        (is (thrown? clojure.lang.ExceptionInfo
                     (files/write! r {"new.md" {:text "invalid"}} {:expected-worktree expected}))))
      (is (= "first" (:text (files/read-file r "new.md" {}))))
      (finally (d/close-room! r)))))

(deftest applied-receipts-preserve-modes-and-do-not-certify-intercepted-writes
  (let [r (room)]
    (try
      (let [result (files/write! r {"script.sh" {:text "echo one\n"}}
                                 {:file-modes {"/script.sh" 33261}})
            opened (:entry (first (:written result)))]
        (is (:applied? result))
        (is (= 33261 (:mode opened)))
        (let [saved (files/write! r {"script.sh" {:text "echo two\n"}}
                                  {:expected-worktree {"/script.sh" opened}})]
          (is (= 33261 (get-in saved [:written 0 :entry :mode])))
          (is (= (get-in saved [:written 0 :entry]) (get (repo/tree (conn r) :head) "script.sh")))))
      (let [boundary (constantly {:handlers [(fn [_effect _next] nil)]})
            skipped (files/write! r {"skipped.md" {:text "not applied"}} {:boundary boundary})]
        (is (false? (:applied? skipped)))
        (is (empty? (:written skipped)))
        (is (not (mfs/exists? (ws/room-fs r) "/skipped.md"))))
      (finally (d/close-room! r)))))

(def ^:private xlsx-ish
  "Bytes that are not UTF-8 text: a zip header and invalid sequences."
  (byte-array (map unchecked-byte [0x50 0x4b 0x03 0x04 0x00 0xff 0xfe 0x80 0x81 0x00 0x10])))

(deftest a-batch-is-one-commit-and-reads-back
  (let [r (room)
        before (commits r)
        res (files/write! r {"data/cases.csv" {:text "no;account\n1;4930\n"}
                             "/data/book.xlsx" {"base64" (b64 xlsx-ish)}}
                          {:message "Add the cases"})]
    (testing "one commit, with the message, naming both files"
      (is (string? (:commit res)))
      (is (= (inc before) (commits r)))
      (is (= "Add the cases" (:geschichte.commit/message (first (repo/log (conn r) {:limit 1})))))
      (is (= (:commit res) (str (:geschichte.commit/id (repo/head-commit (conn r))))))
      (is (= ["/data/book.xlsx" "/data/cases.csv"] (mapv :path (:written res))))
      (is (every? :content-id (:written res))))
    (testing "listed with kind, size and content id"
      (let [{:keys [entries]} (files/list-dir r "/data")]
        (is (= #{"book.xlsx" "cases.csv"} (set (map :name entries))))
        (is (every? #(= "file" (:kind %)) entries))
        (is (= (count xlsx-ish) (:size (first (filter #(= "book.xlsx" (:name %)) entries)))))
        (is (= (set (map :content-id (:written res))) (set (map :content-id entries)))))
      (is (some #(and (= "data" (:name %)) (= "dir" (:kind %))) (:entries (files/list-dir r nil)))))
    (testing "text as text, binary as base64 with a media type"
      (let [t (files/read-file r "data/cases.csv" {})
            b (files/read-file r "/data/book.xlsx" {})]
        (is (= "no;account\n1;4930\n" (:text t)))
        (is (= "text/csv" (:media-type t)))
        (is (nil? (:base64 t)))
        (is (= (seq xlsx-ish) (seq (unb64 (:base64 b)))))
        (is (= "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" (:media-type b)))
        (is (nil? (:next-offset b)))))
    (testing "the same batch again commits nothing"
      (let [again (files/write! r {"data/cases.csv" {:text "no;account\n1;4930\n"}} {})]
        (is (nil? (:commit again)))
        (is (= (inc before) (commits r)))))
    (testing "null removes a file, committed"
      (let [rm (files/write! r {"data/book.xlsx" nil "data/never-there.txt" nil} {})]
        (is (= ["/data/book.xlsx"] (:removed rm)))
        (is (string? (:commit rm)))
        (is (= ["cases.csv"] (mapv :name (:entries (files/list-dir r "/data")))))
        (is (nil? (get (repo/tree (conn r) :head) "data/book.xlsx")) "gone from HEAD")))))

(deftest only-the-named-paths-are-committed
  (let [r (room)
        fs (ws/room-fs r)]
    (is (mfs/write-string! fs "/scratch.md" "an agent's draft" false))
    (files/write! r {"notes.md" {:text "mine"}} {})
    (is (contains? (repo/tree (conn r) :head) "notes.md"))
    (is (not (contains? (repo/tree (conn r) :head) "scratch.md")) "someone else's change is not swept in")
    (is (some #(str/includes? % "scratch.md") (forks/workspace-changes r)))))

(deftest paths-stay-in-the-workspace
  (let [r (room)
        before (commits r)
        refused? (fn [path]
                   (try (files/write! r {"ok.txt" {:text "fine"} path {:text "x"}} {}) false
                        (catch clojure.lang.ExceptionInfo e (= ::files/invalid (:type (ex-data e))))))]
    (doseq [p ["../escape.txt" "a/../../b" "/a/../b" ".git/config" "sub/.git/hooks/pre-commit"
               "" "/" "a\\b" ".env"]]
      (is (refused? p) (pr-str p)))
    (is (= before (commits r)) "a refused batch writes nothing")
    (is (not (mfs/exists? (ws/room-fs r) "/ok.txt")))
    (testing "content is {text} or {base64}, not both, not something else"
      (doseq [v [{:text "a" :base64 "YQ=="} {:base64 "%%%"} 42 {:other "x"}]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"text|base64" (files/write! r {"f.txt" v} {})))))
    (testing "a directory is not overwritten or removed"
      (files/write! r {"dir/a.txt" {:text "a"}} {})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"directory" (files/write! r {"dir" {:text "x"}} {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"directory" (files/write! r {"dir" nil} {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is a file" (files/write! r {"dir/a.txt/b" {:text "x"}} {}))))
    (testing "reads refuse the same paths"
      (is (thrown? clojure.lang.ExceptionInfo (files/read-file r "../x" {})))
      (is (thrown? clojure.lang.ExceptionInfo (files/list-dir r ".git"))))))

(deftest a-read-only-world-refuses-and-receipts-the-write
  (let [r (room)
        sink (effects/make-sink)
        before (commits r)]
    (effects/install-world! (:ctx r) [[:read-only]])
    (effects/set-world-sink! (:ctx r) sink)
    (let [e (try (files/write! r {"data/cases.csv" {:text "x"}} {}) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :effect/denied (:type (ex-data e))) (pr-str (ex-data e)))
      (is (re-find #"read-only" (ex-message e))))
    (is (some #(and (= :git/commit (:effect %)) (= :denied (:decision %)) (= :read-only (:by %))
                    (= "Write data/cases.csv" (get-in % [:resource :message])))
              @sink)
        "the denial is receipted in the world's sink, before anything is written")
    (is (= before (commits r)))
    (is (not (mfs/exists? (ws/room-fs r) "/data/cases.csv")))))

(deftest a-write-refused-midway-leaves-the-batch-paths-as-they-were
  (let [r (room)]
    (files/write! r {"a.txt" {:text "old"}} {})
    (let [n (atom 0)
          boundary (constantly {:handlers [(fn [effect next]
                                             (if (and (= :fs/write (:effect effect)) (< 1 (swap! n inc)))
                                               (effects/deny! effect :quota "full")
                                               (next effect)))]})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (files/write! r {"a.txt" {:text "new"} "b.txt" {:text "b"}} {:boundary boundary})))
      (is (= "old" (:text (files/read-file r "a.txt" {}))) "the first write is undone")
      (is (not (mfs/exists? (ws/room-fs r) "/b.txt")))
      (is (empty? (forks/workspace-changes r))))))

(deftest a-large-file-reads-in-chunks-on-character-boundaries
  (let [r (room)
        text (apply str (repeat (quot (* 3 files/max-read-bytes) 7) "Grüße\n"))
        _ (files/write! r {"big.txt" {:text text}} {})
        chunks (loop [offset 0 acc []]
                 (let [{:keys [next-offset] :as c} (files/read-file r "big.txt" {:offset offset})]
                   (is (<= (:length c) files/max-read-bytes))
                   (if next-offset (recur next-offset (conj acc c)) (conj acc c))))]
    (is (< 2 (count chunks)))
    (is (= text (apply str (map :text chunks))) "no character is split")
    (is (= 10 (:length (files/read-file r "big.txt" {:offset 0 :length 10}))))
    (is (thrown? clojure.lang.ExceptionInfo (files/read-file r "big.txt" {:offset (* 10 files/max-read-bytes)})))))

(defn- worktree-text [r p] (some-> (mfs/read-file (ws/room-fs r) p) str))

(deftest a-refused-batch-leaves-other-peoples-files-alone
  ;; an untracked draft and an unstaged edit, both named by the batch
  (let [r (room)]
    (files/write! r {"a.txt" {:text "committed"}} {})
    (mfs/write-string! (ws/room-fs r) "/draft.txt" "untracked draft" false)
    (mfs/write-string! (ws/room-fs r) "/a.txt" "unstaged edit" false)
    (effects/install-world! (:ctx r) [[:read-only]])
    (is (thrown? clojure.lang.ExceptionInfo
                 (files/write! r {"a.txt" {:text "x"} "draft.txt" {:text "y"} "z.txt" nil} {})))
    (is (= "unstaged edit" (worktree-text r "/a.txt")))
    (is (= "untracked draft" (worktree-text r "/draft.txt")))))

(deftest the-commit-is-decided-before-anything-is-written
  (let [r (room)
        before (commits r)
        boundary (constantly {:handlers [(fn [effect next]
                                           (if (= :git/commit (:effect effect))
                                             (effects/deny! effect :admission "no commits")
                                             (next effect)))]})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no commits"
                          (files/write! r {"a.txt" {:text "a"}} {:boundary boundary})))
    (is (nil? (worktree-text r "/a.txt")))
    (is (= before (commits r)))))

(deftest repeated-forwarding-executes-one-physical-batch
  (let [r (room)]
    (try
      (files/write! r {"a.txt" {:text "before"}} {})
      (let [before (commits r) forwarded (atom [])
            boundary (constantly {:handlers [(fn [effect next]
                                               (if (= :git/commit (:effect effect))
                                                 (let [a (next effect) b (next effect)]
                                                   (swap! forwarded conj a b) b)
                                                 (next effect)))]})
            result (files/write! r {"a.txt" {:text "after"}} {:boundary boundary})]
        (is (:applied? result))
        (is (= 2 (count @forwarded)))
        (is (= (first @forwarded) (second @forwarded)))
        (is (= (inc before) (commits r)))
        (is (= "after" (worktree-text r "/a.txt")))
        (is (empty? (forks/workspace-changes r))))
      (finally (d/close-room! r)))))

(deftest a-failure-after-staging-undoes-index-and-files
  (let [r (room)]
    (files/write! r {"a.txt" {:text "old"} "gone.txt" {:text "keep me"}} {})
    (with-redefs [repo/commit! (fn [& _] (throw (ex-info "boom" {})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
                            (files/write! r {"a.txt" {:text "new"} "b/c.txt" {:text "c"} "gone.txt" nil} {}))))
    (is (= "old" (worktree-text r "/a.txt")))
    (is (= "keep me" (worktree-text r "/gone.txt")))
    (is (not (mfs/exists? (ws/room-fs r) "/b")) "the directory it made is gone too")
    (is (empty? (forks/workspace-changes r)) "nothing staged, nothing dirty")))

(deftest a-staged-version-of-a-batch-path-refuses-the-batch
  ;; HEAD, index and worktree each hold another version: all are kept
  (let [r (room)]
    (files/write! r {"a.txt" {:text "head"}} {})
    (mfs/write-string! (ws/room-fs r) "/a.txt" "index" false)
    (binding [ec/*execution-context* (:ctx r)] (gs/execute-git ["add" "a.txt"]))
    (mfs/write-string! (ws/room-fs r) "/a.txt" "worktree" false)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"staged changes"
                          (files/write! r {"a.txt" {:text "mine"}} {})))
    (is (= "worktree" (worktree-text r "/a.txt")))
    (is (= "index" (String. ^bytes (repo/read-entry (conn r) (get (repo/tree (conn r) :index) "a.txt")) "UTF-8")))
    (is (= "head" (String. ^bytes (repo/read-entry (conn r) (get (repo/tree (conn r) :head) "a.txt")) "UTF-8")))))

(deftest symbolic-links-are-not-followed
  (let [r (room)
        fs (ws/room-fs r)]
    (mfs/write-string! fs "/.env" "SECRET=1" false)
    (mfs/symlink fs ".env" "/public.txt")
    (mfs/symlink fs "public.txt" "/chained.txt")
    (doseq [p ["public.txt" "chained.txt"]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"symbolic link" (files/read-file r p {})) p)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"symbolic link" (files/write! r {p {:text "x"}} {})) p)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"symbolic link" (files/write! r {p nil} {})) p))
    (is (thrown? clojure.lang.ExceptionInfo (files/read-file r ".env" {})))
    (is (= "SECRET=1" (worktree-text r "/.env")))))

(deftest a-shell-git-waits-for-a-batch
  ;; the room shell's git builtin takes the same lock as a room_write batch
  (let [r (room)
        ws (binding [ec/*execution-context* (:ctx r)] (gs/current-workspace))
        host (bash/make-host {:workspace ws})
        git (get (:builtins host) "git")
        fs (:fs host)]
    (mfs/write-string! fs "/scratch.md" "x" false)
    (let [f (locking (gs/workspace-lock (:conn ws))
              (let [f (future (git ["git" "add" "scratch.md"] fs {:cwd "/"}))]
                (Thread/sleep 300)
                (is (not (realized? f)) "blocked while the batch holds the repository")
                f))]
      (is (= 0 (:exit (deref f 10000 {:exit :timeout}))))
      (is (= ["scratch.md"] (:staged (repo/status (conn r))))))))

(deftest staged-changes-of-others-refuse-the-batch
  (let [r (room)]
    (mfs/write-string! (ws/room-fs r) "/scratch.md" "staged by an agent" false)
    (binding [ec/*execution-context* (:ctx r)] (gs/execute-git ["add" "scratch.md"]))
    (let [e (try (files/write! r {"notes.md" {:text "mine"}} {}) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= ::files/staged-changes (:type (ex-data e))) (pr-str (ex-data e))))
    (is (not (mfs/exists? (ws/room-fs r) "/notes.md")))
    (is (not (contains? (repo/tree (conn r) :head) "scratch.md")))))

(deftest a-concurrent-writer-fails-the-batch-and-keeps-its-change
  (let [r (room)
        write! tools/workspace-write!]
    (with-redefs [tools/workspace-write! (fn [ctx p c & opts]
                                           (let [res (apply write! ctx p c opts)]
                                             ;; an agent writes the same path right after
                                             (mfs/write-string! (ws/room-fs r) p "the agent's" false)
                                             res))]
      (let [e (try (files/write! r {"a.txt" {:text "mine"}} {}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :dvergr.rooms.forks/concurrent-change (:type (ex-data e))) (pr-str (ex-data e)))))
    (is (= "the agent's" (worktree-text r "/a.txt")) "not overwritten by the undo")
    (is (not (contains? (repo/tree (conn r) :head) "a.txt")) "nothing committed")))

(deftest text-reads-whole-characters
  (let [r (room)]
    (files/write! r {"e.txt" {:text "éé"}} {})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"shorter than the character"
                          (files/read-file r "e.txt" {:length 1})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"inside a character"
                          (files/read-file r "e.txt" {:offset 1})))
    (is (= {:text "é" :next-offset 2} (select-keys (files/read-file r "e.txt" {:length 3}) [:text :next-offset])))
    (is (= "é" (:text (files/read-file r "e.txt" {:offset 2}))))))

(deftest a-full-chunk-fits-an-mcp-result
  (let [r (room)
        n files/max-read-bytes]
    (files/write! r {"quotes.txt" {:text (apply str (repeat n "\""))}
                     "ctl.txt" {:text (apply str (repeat n "\u0001"))}
                     "bin.dat" {:base64 (b64 (byte-array n (unchecked-byte 0xff)))}} {})
    (testing "control characters read as base64, not six-fold escaped text"
      (is (:base64 (files/read-file r "ctl.txt" {}))))
    (doseq [p ["quotes.txt" "ctl.txt" "bin.dat"]]
      (is (:structuredContent (surface/data-result (files/read-file r p {})))
          (str p ": the whole chunk, not a cut result")))))

(deftest a-large-directory-lists-in-pages
  (let [r (room)]
    (doseq [part (partition-all 150 (range 205))]   ; at most 200 paths per batch
      (files/write! r (into {} (for [i part] [(format "many/f%03d.txt" i) {:text (str i)}])) {}))
    (let [a (files/list-dir r "/many" {})
          b (files/list-dir r "/many" {:offset (:next-offset a)})]
      (is (= [205 200 200] [(:total a) (count (:entries a)) (:next-offset a)]))
      (is (= 5 (count (:entries b))))
      (is (nil? (:next-offset b)))
      (is (= 205 (count (distinct (map :path (concat (:entries a) (:entries b))))))))))

(deftest a-page-of-long-names-fits-an-mcp-result
  (let [r (room)
        names (for [i (range 200)] (str (apply str (repeat 190 "n")) (format "%03d.txt" i)))]
    (files/write! r (into {} (for [n names] [(str "long/" n) {:text "x"}])) {})
    (let [pages (loop [offset 0 acc []]
                  (let [pg (files/list-dir r "/long" {:offset offset})]
                    (is (:structuredContent (surface/data-result pg)) "a whole page, not a cut result")
                    (if-let [n (:next-offset pg)] (recur n (conj acc pg)) (conj acc pg))))]
      (is (< 1 (count pages)))
      (is (= (set names) (set (mapcat #(map :name (:entries %)) pages)))))))

(deftest blank-segments-are-refused-as-geschichte-would-drop-them
  (let [r (room)]
    (mfs/write-string! (ws/room-fs r) "/.env" "SECRET=1" false)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"blank" (files/read-file r ".env/ " {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"blank" (files/write! r {"a/ /b" {:text "x"}} {})))
    (is (not (mfs/exists? (ws/room-fs r) "/a")))))

(deftest a-batch-cannot-write-a-file-and-beneath-it
  (let [r (room)
        before (commits r)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"also writes /a as a file"
                          (files/write! r {"a" {:text "x"} "a/b" {:text "y"}} {})))
    (is (not (mfs/exists? (ws/room-fs r) "/a")))
    (is (= before (commits r)))))

(deftest the-undo-keeps-a-write-made-while-it-runs
  (let [r (room)
        restore-paths! repo/restore-paths!]
    (files/write! r {"a.txt" {:text "old"}} {})
    (with-redefs [repo/commit! (fn [& _] (throw (ex-info "boom" {})))
                  ;; the undo's first step; an agent writes right after it
                  repo/restore-paths! (fn [& args]
                                        (let [res (apply restore-paths! args)]
                                          (mfs/write-string! (ws/room-fs r) "/a.txt" "the agent's" false)
                                          (mfs/write-string! (ws/room-fs r) "/new.txt" "the agent's too" false)
                                          (mfs/write-string! (ws/room-fs r) "/d/agent.txt" "beneath" false)
                                          res))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (files/write! r {"a.txt" {:text "mine"} "new.txt" {:text "mine"} "d/e/mine.txt" {:text "m"}} {}))))
    (is (= "the agent's" (worktree-text r "/a.txt")))
    (is (= "the agent's too" (worktree-text r "/new.txt")) "not deleted as the batch's own")
    (is (= "beneath" (worktree-text r "/d/agent.txt")) "a directory the batch made stays while it holds a file")
    (is (not (mfs/exists? (ws/room-fs r) "/d/e")) "an empty one it made is gone")))

(deftest a-file-named-like-an-option-commits-only-itself
  (let [r (room)]
    (mfs/write-string! (ws/room-fs r) "/scratch.txt" "a draft" false)
    (is (string? (:commit (files/write! r {"-A" {:text "x"}} {}))))
    (is (contains? (repo/tree (conn r) :head) "-A"))
    (is (not (contains? (repo/tree (conn r) :head) "scratch.txt")) "only the named path")
    (is (some #(str/includes? % "scratch.txt") (forks/workspace-changes r)))))

(deftest a-read-reads-what-it-checked
  (let [r (room)
        read-entry repo/read-entry]
    (files/write! r {"public.txt" {:text "public"}} {})
    (mfs/write-string! (ws/room-fs r) "/.env" "SECRET=1" false)
    (with-redefs [repo/read-entry (fn [conn entry]
                                    ;; the path becomes a link to .env before the bytes are read
                                    (mfs/delete (ws/room-fs r) "/public.txt")
                                    (mfs/symlink (ws/room-fs r) ".env" "/public.txt")
                                    (read-entry conn entry))]
      (is (= "public" (:text (files/read-file r "public.txt" {})))))))

(deftest a-file-changed-before-the-batch-writes-it-fails-the-batch
  ;; compare-and-write at the gate: the batch's snapshot saw "old"
  (let [r (room)
        write! tools/workspace-write!]
    (files/write! r {"a.txt" {:text "old"}} {})
    (with-redefs [tools/workspace-write! (fn [ctx p c & opts]
                                           (when (= "/a.txt" p)
                                             (mfs/write-string! (ws/room-fs r) "/a.txt" "the agent's" false))
                                           (apply write! ctx p c opts))]
      (let [e (try (files/write! r {"a.txt" {:text "mine"} "b.txt" {:text "b"}} {}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :dvergr.tools/changed-underneath (:type (ex-data e))) (pr-str (ex-data e)))))
    (is (= "the agent's" (worktree-text r "/a.txt")) "the agent's edit is kept")
    (is (not (mfs/exists? (ws/room-fs r) "/b.txt")) "the batch is undone")
    (is (= "old" (String. ^bytes (repo/read-entry (conn r) (get (repo/tree (conn r) :head) "a.txt")) "UTF-8")))
    (testing "a plain write has no check"
      (is (tools/workspace-write! {:filesystem (ws/room-fs r) :cwd "/"} "/a.txt" "plain")))))

(deftest a-removed-file-is-not-restored-over-a-new-directory
  (let [r (room)
        restore-paths! repo/restore-paths!]
    (files/write! r {"a" {:text "a file"}} {})
    (with-redefs [repo/commit! (fn [& _] (throw (ex-info "boom" {})))
                  repo/restore-paths! (fn [& args]
                                        (let [res (apply restore-paths! args)]
                                          ;; an agent makes a directory where the file was
                                          (mfs/write-string! (ws/room-fs r) "/a/agent.txt" "mine" false)
                                          res))]
      (let [e (try (files/write! r {"a" nil} {}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= ["/a"] (:conflicted (ex-data e))) (pr-str (ex-data e)))
        (is (re-find #"not undone" (ex-message e)))))
    (is (= :dir (:type (mfs/stat (ws/room-fs r) "/a"))))
    (is (= "mine" (worktree-text r "/a/agent.txt")))))

(deftest a-directory-made-before-the-batch-writes-is-not-written-over
  (let [r (room)
        head #(set (keys (repo/tree (conn r) :head)))
        before (head)
        write! tools/workspace-write!]
    (with-redefs [tools/workspace-write! (fn [ctx p c & opts]
                                           (when (= "/a" p)
                                             (mfs/write-string! (ws/room-fs r) "/a/agent.txt" "mine" false))
                                           (apply write! ctx p c opts))]
      (let [e (try (files/write! r {"a" {:text "mine"}} {}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :dvergr.tools/changed-underneath (:type (ex-data e))) (pr-str (ex-data e)))
        (is (re-find #"is a directory" (ex-message e)))))
    (is (= before (head)) "nothing committed")
    (is (= :dir (:type (mfs/stat (ws/room-fs r) "/a"))))
    (is (= "mine" (worktree-text r "/a/agent.txt")))))

(deftest a-link-with-the-same-text-is-not-the-batch-s-file
  ;; a link's content is its target: `a.txt -> target` hashes as the text "target"
  (let [r (room)
        head #(set (keys (repo/tree (conn r) :head)))
        before (head)
        write! tools/workspace-write!]
    (with-redefs [tools/workspace-write! (fn [ctx p c & opts]
                                           (let [res (apply write! ctx p c opts)]
                                             (mfs/delete (ws/room-fs r) "/a.txt")
                                             (mfs/symlink (ws/room-fs r) "target" "/a.txt")
                                             res))]
      (let [e (try (files/write! r {"a.txt" {:text "target"}} {}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :dvergr.rooms.forks/concurrent-change (:type (ex-data e))) (pr-str (ex-data e)))))
    (is (= before (head)) "nothing committed")
    (is (= :symlink (:type (mfs/stat (ws/room-fs r) "/a.txt"))) "the other writer's link is kept")))

(deftest a-removal-does-not-take-a-link-with-the-file-s-text
  (let [r (room)
        delete! tools/workspace-delete!]
    (files/write! r {"a.txt" {:text "target"}} {})
    (let [head-before (repo/head-commit (conn r))]
      (with-redefs [tools/workspace-delete! (fn [ctx p & opts]
                                              (mfs/delete (ws/room-fs r) "/a.txt")
                                              (mfs/symlink (ws/room-fs r) "target" "/a.txt")
                                              (apply delete! ctx p opts))]
        (let [e (try (files/write! r {"a.txt" nil} {}) nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (= :dvergr.tools/changed-underneath (:type (ex-data e))) (pr-str (ex-data e)))))
      (is (= :symlink (:type (mfs/stat (ws/room-fs r) "/a.txt"))) "the link is kept")
      (is (= (:geschichte.commit/id head-before) (:geschichte.commit/id (repo/head-commit (conn r))))
          "nothing committed"))))

(deftest a-removed-file-is-not-restored-beneath-a-new-file
  (let [r (room)
        restore-paths! repo/restore-paths!]
    (files/write! r {"a/b.txt" {:text "b"}} {})
    (with-redefs [repo/commit! (fn [& _] (throw (ex-info "boom" {})))
                  repo/restore-paths! (fn [& args]
                                        (let [res (apply restore-paths! args)
                                              fs (ws/room-fs r)]
                                          ;; the directory the removal emptied becomes a file
                                          (mfs/delete fs "/a")
                                          (mfs/write-string! fs "/a" "the agent's" false)
                                          res))]
      (let [e (try (files/write! r {"a/b.txt" nil} {}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= ["/a/b.txt"] (:conflicted (ex-data e))) (pr-str (ex-data e)))))
    (is (= :file (:type (mfs/stat (ws/room-fs r) "/a"))))
    (is (= "the agent's" (worktree-text r "/a")))
    (is (nil? (get (repo/worktree (conn r)) "a/b.txt")) "not put back beneath a file")))
