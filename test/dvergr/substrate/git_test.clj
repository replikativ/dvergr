(ns dvergr.substrate.git-test
  "Regression guard for the per-repo worktrees-dir fix: two git systems in one
   composite must not share a worktrees-dir, or a fork (which branches every
   system to the SAME `main-fork-<id>` name) collides on `<dir>/<branch>` —
   the 2nd `git worktree add` fails 'already exists'."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.substrate.git :as git]
            [dvergr.substrate.paths :as paths]))

(deftest per-repo-worktrees-dir-no-collision
  (let [home  (str (java.nio.file.Files/createTempDirectory
                    "git-wt-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        saved @@#'paths/home-atom]
    (paths/set-home! home)
    (try
      (let [r1 (str home "/repo-a")
            r2 (str home "/repo-b")
            _  (git/ensure-repo! r1)
            _  (git/ensure-repo! r2)
            s1 (git/create-git-system :repo-path r1 :system-name "room-repo-aaa")
            s2 (git/create-git-system :repo-path r2 :system-name "room-repo-bbb")]
        (testing "distinct repos in one composite get distinct worktree namespaces"
          (is (not= (:worktrees-dir s1) (:worktrees-dir s2)))
          (is (re-find #"room-repo-aaa$" (:worktrees-dir s1)))
          (is (re-find #"room-repo-bbb$" (:worktrees-dir s2))))
        (testing "two forks (same branch name) land in different dirs — no collision"
          (let [b1 (str (:worktrees-dir s1) "/main-fork-X")
                b2 (str (:worktrees-dir s2) "/main-fork-X")]
            (is (not= b1 b2)))))
      (finally (paths/set-home! saved)))))
