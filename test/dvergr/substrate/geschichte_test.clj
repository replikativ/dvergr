(ns dvergr.substrate.geschichte-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [dvergr.substrate.geschichte :as g]
            [geschichte.repo :as repo]
            [geschichte.yggdrasil :as gy]
            [muschel.fs :as fs]))

(defn- temp-dir [prefix]
  (.toFile (java.nio.file.Files/createTempDirectory
            prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- native-source-repo!
  "A one-commit native Git repository, built by the test.

   The seed path under test imports a NATIVE repository, so the fixture has to
   be one; `dvergr.substrate.git` already drives `git` exactly this way.
   Building it here rather than pointing at `../dvergr-sandbox` is the
   difference between a test that passes on a developer's machine and one that
   passes anywhere: the sibling checkout is absent in CI, `source-file` still
   resolves the path (it canonicalises without checking existence), and the
   import throws \"not a native Git repository\" — so the run fell through to
   `fallback-workspace!` and only the upstream-remote assertion noticed.

   `-c user.*` is passed per-invocation because a CI container has no global
   git identity and `commit` refuses without one."
  [^java.io.File dir]
  (let [git (fn [& args]
              ;; `shell/sh` reports a failure in its return value rather than
              ;; throwing. Unchecked, a broken fixture would surface as the
              ;; import failing and the upstream assertion going nil — the same
              ;; confusing symptom this rewrite is fixing.
              (let [{:keys [exit err]} (apply shell/sh "git" "-C" (.getPath dir) args)]
                (when-not (zero? exit)
                  (throw (ex-info "git fixture command failed"
                                  {:args args :exit exit :err err})))))]
    (git "init" "-q" "-b" "main")
    (spit (io/file dir "seed.clj") "(ns seed)\n(def answer 42)\n")
    (git "add" "-A")
    (git "-c" "user.email=test@dvergr.local" "-c" "user.name=dvergr test"
         "commit" "-q" "-m" "seed")
    (.getCanonicalPath dir)))

(defn- with-workspace
  "Open a persistent Geschichte repository seeded from `source`, hand
   `[conn filesystem]` to `f`, and always release + delete."
  [source f]
  (let [dir (temp-dir "dvergr-geschichte-")
        scope (.getPath (io/file dir "store"))
        system (g/create-system :scope scope :system-name "room-repo-test"
                                :source source)
        conn (gy/connection system)
        filesystem (g/filesystem {:system system :conn conn
                                  :id (gy/workspace-id system)
                                  :repository {:conn conn
                                               :config (:config @conn)}})]
    (try
      (f conn filesystem)
      (finally
        (d/release conn)
        (g/delete-repository! scope)))))

(deftest persistent-system-exposes-a-virtual-workspace
  (let [source (native-source-repo! (temp-dir "dvergr-git-source-"))]
    (with-workspace
      source
      (fn [conn filesystem]
        (testing "the seed's content is in the workspace"
          (is (seq (repo/files conn))))
        (testing "and it is VIRTUAL — no host path backs it"
          (is (nil? (fs/physical-path filesystem "/"))))
        (testing "the seed source is recorded as the upstream remote"
          (is (= source (get (repo/configuration conn) "remote.upstream.url"))))))))

(deftest unreachable-seed-still-yields-a-usable-workspace
  ;; `fallback-workspace!` is what kept CI green everywhere except the upstream
  ;; assertion, so pin it deliberately rather than reaching it by accident: an
  ;; unreachable seed must still leave a committed workspace, not a
  ;; half-initialised repository.
  (with-workspace
    (.getPath (io/file (temp-dir "dvergr-git-absent-") "definitely-not-a-repo"))
    (fn [conn filesystem]
      (is (seq (repo/files conn))
          "the fallback commits a starter file, so the workspace is not empty")
      (is (nil? (fs/physical-path filesystem "/")))
      (is (nil? (get (repo/configuration conn) "remote.upstream.url"))
          "nothing was imported, so there is no upstream to point at"))))
