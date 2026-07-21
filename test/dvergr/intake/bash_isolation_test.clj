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
