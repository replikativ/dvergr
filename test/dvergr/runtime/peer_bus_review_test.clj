(ns dvergr.runtime.peer-bus-review-test
  "End-to-end tests for the peer-bus + PR-style merge-review primitives:
   - Per-room messages relay to the peer-bus tagged with origin + scope
   - fork-room emits :dvergr/fork-created
   - propose-merge! emits :dvergr/merge-proposed + a tagged chat message
   - merge-room emits :dvergr/fork-merged
   - discard emits :dvergr/fork-discarded
   - pending-proposals scans a room's log"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.runtime.bus :as bus]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.discourse :as d]
            [dvergr.intake.bash :as b]
            [dvergr.runtime.peer-bus :as peer-bus]
            [org.replikativ.spindel.engine.core :as ec]))

;; ============================================================================
;; Sandbox fixture (reuses pattern from bash_isolation_test)
;; ============================================================================

(defn- run-shell [& cmd-parts]
  (let [pb (-> (ProcessBuilder. ^java.util.List (vec cmd-parts))
               (.redirectErrorStream true))
        proc (.start pb)]
    (.waitFor proc)
    {:out (slurp (.getInputStream proc))}))

(defn- init-sandbox-repo! [path]
  (run-shell "rm" "-rf" path)
  (.mkdirs (io/file path))
  (run-shell "bash" "-c"
             (str "cd " path
                  " && git init -q -b main"
                  " && git config user.email t@e.com"
                  " && git config user.name t"
                  " && echo seed > README.md"
                  " && git add . && git commit -q -m seed")))

(def ^:dynamic *sandbox-dir* nil)
(def ^:dynamic *base-ctx* nil)

(defn- with-sandbox [test-fn]
  (let [dir (str "/tmp/dvergr-pb-" (System/nanoTime))]
    (try
      (init-sandbox-repo! dir)
      (let [ctx (daemon/create-shared-context
                 :repo-path dir
                 :worktrees-dir (str dir "/.worktrees")
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
      (Thread/sleep 50)                                ; let drain catch up
      (let [evts (peer-events-of-type :dvergr/fork-created)]
        (is (= 1 (count evts)) "exactly one fork-created event")
        (is (= (:id fork) (:dvergr/origin (first evts))))
        (is (= :parent (:dvergr/parent (first evts))))
        (is (str/starts-with? (:worktree-path (first evts))
                              (str *sandbox-dir* "/.worktrees/")))))))

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
          fork   (d/fork-room parent {:isolation :ctx})]
      (bash (:ctx fork) "echo m > m.txt && git add . && git commit -q -m wip")
      (d/merge-room parent fork)
      (Thread/sleep 50)
      (let [evts (peer-events-of-type :dvergr/fork-merged)]
        (is (= 1 (count evts)))
        (is (= (:id fork) (:dvergr/origin (first evts))))))))

(deftest discard-emits-fork-discarded
  (binding [ec/*execution-context* *base-ctx*]
    (let [parent (d/room :p *base-ctx*)
          fork   (d/fork-room parent {:isolation :ctx})]
      (d/discard fork)
      (Thread/sleep 50)
      (let [evts (peer-events-of-type :dvergr/fork-discarded)]
        (is (= 1 (count evts)))
        (is (= (:id fork) (:dvergr/origin (first evts))))))))

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
