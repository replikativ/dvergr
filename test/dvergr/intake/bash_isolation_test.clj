(ns dvergr.intake.bash-isolation-test
  "End-to-end isolation tests for the agent's bash surface.

   Verifies that `dvergr.intake.bash` correctly threads the workspace
   path through yggdrasil's fork-context machinery, so a fork-context
   gives the bash session an isolated git worktree, and merge / discard
   plumbing works as advertised."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.daemon :as daemon]
            [dvergr.git :as dgit]
            [dvergr.intake.bash :as b]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [yggdrasil.protocols :as yp]))

;; ============================================================================
;; Sandbox repo helpers
;; ============================================================================

(defn- run-shell [& cmd-parts]
  (let [pb (-> (ProcessBuilder. ^java.util.List (vec cmd-parts))
               (.redirectErrorStream true))
        proc (.start pb)
        exit (.waitFor proc)]
    {:exit exit
     :out (slurp (.getInputStream proc))}))

(defn- init-sandbox-repo!
  "Create a one-commit git repo at `path`. Idempotent — wipes any
   existing dir at `path` first."
  [path]
  (run-shell "rm" "-rf" path)
  (.mkdirs (io/file path))
  (let [script (str "cd " path
                    " && git init -q -b main"
                    " && git config user.email test@example.com"
                    " && git config user.name test"
                    " && echo seed > README.md"
                    " && git add . && git commit -q -m seed")
        r (run-shell "bash" "-c" script)]
    (when (not= 0 (:exit r))
      (throw (ex-info "sandbox-init failed" r)))))

(defn- chat-on
  "Build a thin chat-ctx whose :spindel-ctx is the given execution
   context. Enough for `intake.bash` — the fancier fields
   (messages/budget signals) aren't read by the bash surface."
  [spindel-ctx]
  {:spindel-ctx spindel-ctx :chat-id (random-uuid) :title "test"})

(defn- bash [chat-ctx cmd]
  (let [r (b/run chat-ctx cmd)]
    {:exit (:exit r)
     :stdout (str/trim (or (:stdout r) ""))
     :stderr (str/trim (or (:stderr r) ""))
     :cwd (:cwd r)}))

;; A unique sandbox per test run so tests can run in parallel and
;; nothing leaks between runs.
(def ^:dynamic *sandbox-dir* nil)
(def ^:dynamic *base-ctx* nil)

(defn- with-sandbox [test-fn]
  (let [dir (str "/tmp/dvergr-bash-iso-" (System/nanoTime))]
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
;; Tests
;; ============================================================================

(deftest workspace-resolves-to-registered-git-worktree
  ;; Without a fork, bash in a chat-ctx attached to base-ctx should run
  ;; with workspace = the registered git repo path.
  (let [chat (chat-on *base-ctx*)]
    (is (= *sandbox-dir*
           (binding [ec/*execution-context* *base-ctx*]
             (dgit/current-worktree-path))))
    (is (= "main" (:stdout (bash chat "git branch --show-current"))))
    (is (= "README.md" (:stdout (bash chat "ls"))))))

(deftest fork-gives-bash-a-fresh-worktree-on-a-fresh-branch
  (let [parent (chat-on *base-ctx*)
        fork   (binding [ec/*execution-context* *base-ctx*] (ygg/fork!))
        child  (chat-on (:child-ctx fork))
        parent-branch (:stdout (bash parent "git branch --show-current"))
        child-branch  (:stdout (bash child  "git branch --show-current"))
        child-cwd     (:cwd (bash child "pwd"))]
    (testing "parent stays on main"
      (is (= "main" parent-branch)))
    (testing "fork's bash is on a different, fork-named branch"
      (is (not= parent-branch child-branch))
      (is (str/starts-with? child-branch "main-fork-")))
    (testing "fork's bash workspace is a worktree under the parent's repo"
      (is (str/starts-with? child-cwd (str *sandbox-dir* "/.worktrees/"))))))

(deftest writes-in-fork-do-not-leak-to-parent
  (let [parent (chat-on *base-ctx*)
        fork   (binding [ec/*execution-context* *base-ctx*] (ygg/fork!))
        child  (chat-on (:child-ctx fork))]
    ;; Fork writes a file
    (is (= 0 (:exit (bash child "echo hello > side.txt"))))
    (is (= "hello" (:stdout (bash child "cat side.txt"))))
    ;; Parent can't see it
    (let [r (bash parent "cat side.txt")]
      (is (not= 0 (:exit r)))
      (is (re-find #"No such file" (:stderr r))))))

(deftest writes-in-parent-do-not-leak-to-fork
  (let [parent (chat-on *base-ctx*)
        fork   (binding [ec/*execution-context* *base-ctx*] (ygg/fork!))
        child  (chat-on (:child-ctx fork))]
    (is (= 0 (:exit (bash parent "echo p > parent.txt"))))
    (let [r (bash child "cat parent.txt")]
      (is (not= 0 (:exit r))))))

(deftest merge-fork-propagates-commits-to-parent
  (let [parent (chat-on *base-ctx*)
        fork   (binding [ec/*execution-context* *base-ctx*] (ygg/fork!))
        child  (chat-on (:child-ctx fork))]
    ;; Fork writes + commits
    (is (= 0 (:exit (bash child "echo merged > m.txt && git add . && git commit -m wip"))))
    ;; Before merge: parent doesn't see m.txt
    (is (not (re-find #"m.txt" (:stdout (bash parent "ls")))))
    ;; Merge from parent's context
    (binding [ec/*execution-context* *base-ctx*]
      (ygg/merge-fork! fork))
    ;; After merge: parent sees m.txt
    (is (= "merged" (:stdout (bash parent "cat m.txt"))))))

(deftest discard-fork-drops-the-worktree
  (let [fork  (binding [ec/*execution-context* *base-ctx*] (ygg/fork!))
        child (chat-on (:child-ctx fork))
        wt    (binding [ec/*execution-context* (:child-ctx fork)]
                (dgit/current-worktree-path))]
    (is (= 0 (:exit (bash child "echo throwaway > t.txt && git add . && git commit -m wip"))))
    (is (.exists (io/file wt)))
    (binding [ec/*execution-context* *base-ctx*]
      (ygg/discard-fork! fork))
    (is (not (.exists (io/file wt)))
        "discard-fork! must remove the worktree directory from disk")))

(deftest container-refuses-paths-outside-worktree
  ;; Even though the parent's underlying FS is a real DiskFS, paths
  ;; outside the sandbox root are rejected.
  (let [parent (chat-on *base-ctx*)]
    (let [r (bash parent "cat /etc/passwd")]
      (is (not= 0 (:exit r)))
      (is (re-find #"No such file" (:stderr r))))))
