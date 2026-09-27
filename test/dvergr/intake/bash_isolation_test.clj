(ns dvergr.intake.bash-isolation-test
  "End-to-end validation of the fully virtual Geschichte sandbox."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as dh]
            [dvergr.intake.bash :as b]
            [dvergr.orchestration.daemon :as daemon]
            [dvergr.substrate.geschichte :as g]
            [muschel.fs :as mfs]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.yggdrasil :as ygg]))

(defn- chat-on [spindel-ctx]
  {:spindel-ctx spindel-ctx :chat-id (random-uuid) :title "test"})

(defn- bash [chat-ctx command]
  (let [result (b/run chat-ctx command)]
    {:exit (:exit result)
     :stdout (str/trim (or (:stdout result) ""))
     :stderr (str/trim (or (:stderr result) ""))
     :cwd (:cwd result)}))

(def ^:dynamic *scope* nil)
(def ^:dynamic *base-ctx* nil)

(defn- with-sandbox [test-fn]
  (let [parent (.toFile (java.nio.file.Files/createTempDirectory
                         "dvergr-virtual-"
                         (make-array java.nio.file.attribute.FileAttribute 0)))
        scope (.getPath (io/file parent "store"))
        ctx (daemon/create-shared-context :repo-path scope
                                          :with-git? true
                                          :with-datahike? false)]
    (try
      (binding [*scope* scope *base-ctx* ctx] (test-fn))
      (finally
        (when-let [system (binding [ec/*execution-context* ctx]
                            (g/current-system))]
          (dh/release (g/gy-connection system)))
        (try (g/delete-repository! scope) (catch Throwable _))))))

(use-fixtures :each with-sandbox)

(deftest room-shell-is-a-virtual-geschichte-root
  (let [chat (chat-on *base-ctx*)
        workspace (binding [ec/*execution-context* *base-ctx*]
                    (g/current-workspace))]
    (is (some? workspace))
    (is (= "main" (:stdout (bash chat "git branch --show-current"))))
    (is (nil? (mfs/physical-path (g/filesystem workspace) "/")))
    (is (re-find #"AGENTS.md|README" (:stdout (bash chat "ls"))))))

(deftest fork-has-an-independent-virtual-workspace-on-the-same-logical-branch
  (let [parent (chat-on *base-ctx*)
        fork (binding [ec/*execution-context* *base-ctx*] (ygg/fork!))
        child (chat-on (:child-ctx fork))
        parent-id (binding [ec/*execution-context* *base-ctx*]
                    (:id (g/current-workspace)))
        child-id (binding [ec/*execution-context* (:child-ctx fork)]
                   (:id (g/current-workspace)))]
    (is (= "main" (:stdout (bash parent "git branch --show-current"))))
    (is (= "main" (:stdout (bash child "git branch --show-current"))))
    (is (not= parent-id child-id))
    (is (= "/" (:cwd (bash child "pwd"))))))

(deftest writes-in-fork-do-not-leak-to-parent
  (let [parent (chat-on *base-ctx*)
        fork (binding [ec/*execution-context* *base-ctx*] (ygg/fork!))
        child (chat-on (:child-ctx fork))]
    (is (= 0 (:exit (bash child "echo hello > side.txt"))))
    (is (= "hello" (:stdout (bash child "cat side.txt"))))
    (is (not= 0 (:exit (bash parent "cat side.txt"))))))

(deftest parent-writes-after-fork-are-not-visible-in-frozen-child
  (let [parent (chat-on *base-ctx*)
        fork (binding [ec/*execution-context* *base-ctx*] (ygg/fork!))
        child (chat-on (:child-ctx fork))]
    (is (= 0 (:exit (bash parent "echo parent > parent.txt"))))
    (is (not= 0 (:exit (bash child "cat parent.txt"))))))

(deftest merge-publishes-child-commit-to-parent
  (let [parent (chat-on *base-ctx*)
        fork (binding [ec/*execution-context* *base-ctx*] (ygg/fork!))
        child (chat-on (:child-ctx fork))]
    (is (= 0 (:exit
              (bash child "echo merged > merged.txt && git add . && git commit -m wip"))))
    (is (not= 0 (:exit (bash parent "cat merged.txt"))))
    (binding [ec/*execution-context* *base-ctx*] (ygg/merge-fork! fork))
    (is (= "merged" (:stdout (bash parent "cat merged.txt"))))))

(deftest discard-removes-the-datahike-workspace
  (let [fork (binding [ec/*execution-context* *base-ctx*] (ygg/fork!))
        child (chat-on (:child-ctx fork))
        branch (binding [ec/*execution-context* (:child-ctx fork)]
                 (second (:id (g/current-workspace))))
        parent-conn (binding [ec/*execution-context* *base-ctx*]
                      (:conn (g/current-workspace)))]
    (is (= 0 (:exit
              (bash child "echo throwaway > t.txt && git add . && git commit -m wip"))))
    (is (contains? (set (dh/branches parent-conn)) branch))
    (binding [ec/*execution-context* *base-ctx*] (ygg/discard-fork! fork))
    (is (not (contains? (set (dh/branches parent-conn)) branch)))))

(deftest virtual-root-refuses-host-paths
  (let [result (bash (chat-on *base-ctx*) "cat /etc/passwd")]
    (is (not= 0 (:exit result)))
    (is (re-find #"No such file" (:stderr result)))))

(deftest output-is-bounded-as-it-is-produced
  (let [chat (chat-on *base-ctx*)
        r (b/run chat "for i in $(seq 1 20000); do echo line-$i-of-some-output; done" :max-out 1000)]
    (testing "the agent sees the start, marked as cut"
      (is (str/starts-with? (:stdout r) "line-1-of-some-output"))
      (is (str/ends-with? (:stdout r) "[...truncated]"))
      (is (< (count (:stdout r)) 1100))
      (is (true? (:truncated? r))))
    (testing "the rest was counted, not kept"
      (is (< 400000 (get-in r [:output-bytes :stdout]))))
    (testing "small output is whole"
      (let [s (b/run chat "echo hi")]
        (is (= "hi\n" (:stdout s)))
        (is (false? (:truncated? s)))))))
(deftest the-shell-has-no-network
  (let [chat (chat-on *base-ctx*)]
    (doseq [cmd ["curl -s https://example.com" "sh -c 'curl -s https://example.com'"
                 "echo https://example.com | xargs curl -s" "wget -q -O - https://example.com"]]
      (let [r (b/run chat cmd)]
        (is (not (re-find #"(?i)example domain" (str (:stdout r)))) cmd)
        (is (not (zero? (:exit r))) cmd)))))

(deftest jailed-programs-work-on-the-virtual-worktree
  (if-not (zero? (:exit (clojure.java.shell/sh "sh" "-c" "command -v bwrap && test -x /usr/bin/python3")))
    (println "SKIP jailed-programs: no bwrap or /usr/bin/python3")
    (let [chat (chat-on *base-ctx*)
          mirror (.toFile (java.nio.file.Files/createTempDirectory "dvergr-jail-" (make-array java.nio.file.attribute.FileAttribute 0)))
          ws (binding [ec/*execution-context* *base-ctx*] (g/current-workspace))
          h (b/make-host {:workspace ws :jail {:mirror mirror :commands ["python3"] :tasks-max 64}})
          run #(b/run chat % :host h)]
      (run "echo 'print(open(\"in.txt\").read().upper())' > up.py; echo hello > in.txt; echo gone > old.txt")
      (testing "a jailed program reads the worktree and its writes and deletes come back"
        (let [r (run "python3 -c \"import os; open('out.txt','w').write(open('in.txt').read()*2); os.remove('old.txt')\"")]
          (is (zero? (:exit r)) (pr-str r)))
        (is (= "hello\nhello\n" (:stdout (run "cat out.txt"))))
        (is (not (zero? (:exit (run "cat old.txt")))) "deleted in the jail, deleted in the worktree")
        (is (= "HELLO\n\n" (:stdout (run "python3 up.py")))))
      (testing "the jail has no network and no home"
        (let [r (run "python3 -c \"import urllib.request; urllib.request.urlopen('https://example.com', timeout=5)\"")]
          (is (not (zero? (:exit r)))))
        (let [r (run (str "python3 -c \"import os; print(os.path.exists('" (System/getProperty "user.home") "'))\""))]
          (is (= "False\n" (:stdout r)) (pr-str r))))
      (testing "a command not on the jail's list is still refused"
        (is (not (zero? (:exit (run "node -e 1")))))))))
