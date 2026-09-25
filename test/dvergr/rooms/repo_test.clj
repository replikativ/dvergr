(ns dvergr.rooms.repo-test
  "A client's own code in a room and back: import a local Git checkout with its
   history, change it in the room, export a patch real Git applies."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.catalog.workspace :as ws]
            [dvergr.discourse :as d]
            [dvergr.room.store.memory :as memory]
            [dvergr.rooms.repo :as repo]
            [muschel.fs :as mfs]))

(defn- git! [dir & args]
  (let [{:keys [exit err] :as r} (apply sh/sh "git" (concat args [:dir dir]))]
    (when-not (zero? exit) (throw (ex-info (str "git " (first args) ": " err) {})))
    r))

(defn- source-repo []
  (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "dvergr-repo-test-" (random-uuid)))]
    (.mkdirs dir)
    (git! dir "init" "-q" "-b" "main")
    (git! dir "config" "user.email" "test@example.invalid")
    (git! dir "config" "user.name" "Test")
    (spit (io/file dir "README.md") "# Project\n")
    (.mkdirs (io/file dir "src"))
    (spit (io/file dir "src" "core.clj") "(ns core)\n\n(defn f [] 1)\n")
    (git! dir "add" ".")
    (git! dir "commit" "-q" "-m" "Initial")
    dir))

(deftest a-checkout-goes-into-a-room-and-its-changes-come-back-as-a-patch
  (let [src (source-repo)
        room (d/make-room {:id :repo-roundtrip :store (memory/make)})]
    (try
      (let [imp (repo/import! room (.getPath src))]
        (is (= 2 (:files imp)))
        (is (string? (:commit imp))))
      (testing "the files are in the room's workspace"
        (is (= "(ns core)\n\n(defn f [] 1)\n" (get (ws/read-tree room "/src") "/src/core.clj"))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already has history"
                            (repo/import! room (.getPath src)))
          "an import never lands on existing history")
      (let [fs (ws/room-fs room)]
        (mfs/write-string! fs "/src/core.clj" "(ns core)\n\n(defn f [] 2)\n" false)
        (mfs/write-string! fs "/NOTES.md" "Changed in the room.\n" false))
      (let [{:keys [patch files]} (repo/export room)
            patch-file (io/file src "room.patch")]
        (is (= #{{:status "A" :path "NOTES.md"} {:status "M" :path "src/core.clj"}} (set files)))
        (spit patch-file patch)
        (testing "real Git applies the patch to the source checkout"
          (git! src "apply" "room.patch")
          (is (= "(ns core)\n\n(defn f [] 2)\n" (slurp (io/file src "src" "core.clj"))))
          (is (= "Changed in the room.\n" (slurp (io/file src "NOTES.md"))))))
      (finally
        (d/close-room! room)
        (sh/sh "rm" "-rf" (.getPath src))))))

(deftest export-needs-an-import-and-import-a-real-source
  (let [room (d/make-room {:id :repo-errors :store (memory/make)})]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not imported" (repo/export room)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Not a local Git checkout or a remote URL"
                            (repo/import! room "no/such/place")))
      (finally (d/close-room! room)))))
