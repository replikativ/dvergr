(ns dvergr.contract-test
  "Contract tests — one assertion per claim the system (and the agent prompt)
   makes about its own capabilities, so prompt and infra can't drift apart
   silently. Each failure here means a promise the harness stopped keeping.
   Grow this file whenever a prompt-vs-infra mismatch is fixed."
  (:require [clojure.test :refer [deftest testing is]]
            [dvergr.tools :as tools]
            [dvergr.scheduler.cron :as cron]
            [dvergr.sandbox.ns.io :as sio]
            [sci.core :as sci]))

;; ── Tool grant (the default must not be the `#{:clojure_eval}` poverty trap) ──

(deftest tool-profiles-resolve
  (testing "named profiles resolve to non-empty tool maps"
    (doseq [p [:dev-toolbelt :knowledge-worker :readonly]]
      (is (seq (tools/normalize-tools p)) (str p " resolves to some tools"))))
  (testing ":dev-toolbelt is a real coding toolbelt, not bare clojure_eval"
    (let [tset (set (keys (tools/normalize-tools :dev-toolbelt)))]
      (is (contains? tset "clojure_eval"))
      (is (contains? tset "read_file"))
      (is (contains? tset "write_file"))
      (is (contains? tset "shell"))
      (is (> (count tset) 1) "more than the single clojure_eval")))
  (testing ":knowledge-worker inspects + programs, no file mutation"
    (let [tset (set (keys (tools/normalize-tools :knowledge-worker)))]
      (is (contains? tset "read_file"))
      (is (contains? tset "clojure_eval"))
      (is (not (contains? tset "write_file")))))
  (testing "explicit tool sets and no-tools still work (backward compat)"
    (is (= #{"read_file" "shell"} (set (keys (tools/normalize-tools #{:read-file :shell})))))
    (is (= {} (tools/normalize-tools #{})) "empty set ⇒ no tools")
    (is (= :not-a-profile (tools/normalize-tools :not-a-profile)) "unknown keyword passes through")))

;; ── Scheduler cron: :n / interval validation is strict (no silent wrong cadence) ──

(deftest cron-rejects-bad-specs
  (let [now (java.util.Date.)]
    (testing ":n must be a positive integer"
      (is (thrown? Exception (cron/spec->attrs {:every :hour :n 0} now)))
      (is (thrown? Exception (cron/spec->attrs {:every :hour :n -2} now))))
    (testing ":n can't combine with a calendar anchor"
      (is (thrown? Exception (cron/spec->attrs {:every :week :n 2 :on :mon} now))))
    (testing "raw interval must be positive"
      (is (thrown? Exception (cron/spec->attrs {:interval-ms 0} now))))
    (testing "valid specs still produce the right kind"
      (is (= :interval (:schedule/kind (cron/spec->attrs {:every :hour :n 4} now))))
      (is (= :recurring (:schedule/kind (cron/spec->attrs {:every :day :at "07:00" :on :mon} now)))))))

;; ── Sandbox fs: paths agents see are workspace-relative, never the host path ──

(deftest sandbox-fs-paths-are-workspace-relative
  (let [base (.toFile (java.nio.file.Files/createTempDirectory
                       "contract" (make-array java.nio.file.attribute.FileAttribute 0)))
        _    (.mkdirs (java.io.File. base "src"))
        _    (spit (java.io.File. base "src/a.txt") "hi")
        ctx  (sci/init {})
        _    (sio/add-fs-ns! ctx :base-path (.getAbsolutePath base))
        run  #(sci/eval-string* ctx %)]
    (testing "listings are workspace-relative, not the real .dvergr/systems/<uuid> path"
      (is (= ["src/a.txt"] (run "(babashka.fs/list-dir \"src\")")))
      (is (= "src" (run "(babashka.fs/parent \"src/a.txt\")"))))
    (testing "fs/parent of the root never leaks the host path (returns nil)"
      (is (nil? (run "(babashka.fs/parent \".\")"))))
    (testing "a relative path round-trips back through slurp"
      (is (= "hi" (run "(slurp (first (babashka.fs/list-dir \"src\")))"))))))

;; ── Media: the capabilities the agent prompt references exist ──

(deftest media-capabilities-exist
  (testing "vision + document extraction fns the prompt/docs promise are present"
    (is (some? (requiring-resolve 'dvergr.media.vision/describe)))
    (is (some? (requiring-resolve 'dvergr.media.vision/extract)))
    (is (some? (requiring-resolve 'dvergr.media.doc/extract-text)))))
