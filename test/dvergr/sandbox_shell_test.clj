(ns dvergr.sandbox-shell-test
  "Tests for sandbox security features: resource limits, path safety, audit log,
   HTTP domain policy, and the fs/proc/git namespaces.

   Exercises the agent-facing SCI API rather than implementation internals."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [sci.core :as sci]
            [dvergr.sandbox :as sandbox]
            [babashka.fs :as fs]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:dynamic *tmp-dir* nil)
(def ^:dynamic *sci-ctx* nil)

(defn with-tmp-dir [f]
  (let [tmp (str (fs/create-temp-dir))]
    (binding [*tmp-dir* tmp]
      (try (f) (finally (fs/delete-tree tmp))))))

(defn- fresh-ctx []
  (sandbox/create-base-ctx))

(defn with-fs-ctx [f]
  (let [ctx (fresh-ctx)]
    (sandbox/add-fs-ns! ctx :base-path *tmp-dir*)
    (binding [*sci-ctx* ctx]
      (f))))

(defn with-proc-ctx [f]
  (let [ctx (fresh-ctx)]
    (sandbox/add-proc-ns! ctx :allow #{"echo" "ls" "false"} :base-path *tmp-dir*)
    (binding [*sci-ctx* ctx]
      (f))))

(defn with-git-ctx [f]
  ;; Init a real git repo in tmp-dir for git tests
  (let [ctx (fresh-ctx)]
    (.waitFor (.start (doto (ProcessBuilder. ["git" "init" *tmp-dir*])
                        (.inheritIO))))
    (.waitFor (.start (doto (ProcessBuilder. ["git" "config" "user.email" "test@test.com"])
                        (.directory (java.io.File. *tmp-dir*))
                        (.inheritIO))))
    (.waitFor (.start (doto (ProcessBuilder. ["git" "config" "user.name" "Test"])
                        (.directory (java.io.File. *tmp-dir*))
                        (.inheritIO))))
    (sandbox/add-git-ns! ctx :base-path *tmp-dir*)
    (binding [*sci-ctx* ctx]
      (f))))

(defn eval! [code]
  (sci/eval-string* *sci-ctx* code))

;; ---------------------------------------------------------------------------
;; add-fs-ns! tests
;; ---------------------------------------------------------------------------

(deftest test-fs-write-read
  (with-tmp-dir
    (fn []
      (with-fs-ctx
        (fn []
          (testing "write and read a file"
            (eval! "(require '[fs])")
            (eval! "(fs/write \"hello.txt\" \"hello world\")")
            (is (= "hello world" (eval! "(fs/read \"hello.txt\")"))))

          (testing "exists? returns true after write"
            (is (true? (eval! "(fs/exists? \"hello.txt\")"))))

          (testing "exists? returns false for missing file"
            (is (false? (eval! "(fs/exists? \"nope.txt\")"))))

          (testing "write creates parent directories"
            (eval! "(fs/write \"deep/nested/file.txt\" \"content\")")
            (is (= "content" (eval! "(fs/read \"deep/nested/file.txt\")")))))))))

(deftest test-fs-ls
  (with-tmp-dir
    (fn []
      (with-fs-ctx
        (fn []
          (eval! "(require '[fs])")
          (eval! "(fs/write \"a.clj\" \"(ns a)\")")
          (eval! "(fs/write \"b.txt\" \"text\")")

          (testing "ls returns vector of maps"
            (let [result (eval! "(fs/ls \".\")")]
              (is (vector? result))
              (is (every? map? result))
              (is (every? :name result))
              (is (every? :type result))))

          (testing "ls with pattern filters results"
            (let [result (eval! "(fs/ls \".\" \"*.clj\")")]
              (is (= 1 (count result)))
              (is (= "a.clj" (:name (first result))))))

          (testing "ls entries have expected keys"
            (let [entry (first (eval! "(fs/ls \".\" \"*.clj\")"))]
              (is (= :file (:type entry)))
              (is (number? (:size entry)))
              (is (string? (:modified entry))))))))))

(deftest test-fs-glob
  (with-tmp-dir
    (fn []
      (with-fs-ctx
        (fn []
          (eval! "(require '[fs])")
          (eval! "(fs/mkdir \"src\")")
          (eval! "(fs/write \"src/a.clj\" \"(ns a)\")")
          (eval! "(fs/write \"src/b.clj\" \"(ns b)\")")
          (eval! "(fs/write \"src/c.txt\" \"text\")")

          (testing "glob returns matching paths"
            (let [result (eval! "(fs/glob \"**/*.clj\")")]
              (is (= 2 (count result)))
              (is (every? #(str/ends-with? % ".clj") result)))))))))

(deftest test-fs-mkdir-delete-move-copy
  (with-tmp-dir
    (fn []
      (with-fs-ctx
        (fn []
          (eval! "(require '[fs])")

          (testing "mkdir creates directory"
            (eval! "(fs/mkdir \"mydir\")")
            (is (true? (eval! "(fs/exists? \"mydir\")"))))

          (testing "delete removes file"
            (eval! "(fs/write \"tmp.txt\" \"x\")")
            (is (true? (eval! "(fs/exists? \"tmp.txt\")")))
            (eval! "(fs/delete \"tmp.txt\")")
            (is (false? (eval! "(fs/exists? \"tmp.txt\")"))))

          (testing "move renames file"
            (eval! "(fs/write \"old.txt\" \"content\")")
            (eval! "(fs/move \"old.txt\" \"new.txt\")")
            (is (false? (eval! "(fs/exists? \"old.txt\")")))
            (is (true?  (eval! "(fs/exists? \"new.txt\")"))))

          (testing "copy duplicates file"
            (eval! "(fs/write \"src.txt\" \"data\")")
            (eval! "(fs/copy \"src.txt\" \"dst.txt\")")
            (is (= "data" (eval! "(fs/read \"dst.txt\")")))
            (is (true? (eval! "(fs/exists? \"src.txt\")")))))))))

(deftest test-fs-stat
  (with-tmp-dir
    (fn []
      (with-fs-ctx
        (fn []
          (eval! "(require '[fs])")
          (eval! "(fs/write \"file.txt\" \"hello\")")

          (testing "stat returns structured map"
            (let [s (eval! "(fs/stat \"file.txt\")")]
              (is (= :file (:type s)))
              (is (= 5 (:size s)))
              (is (string? (:modified s))))))))))

;; ---------------------------------------------------------------------------
;; add-proc-ns! tests
;; ---------------------------------------------------------------------------

(deftest test-proc-allowlist
  (with-tmp-dir
    (fn []
      (with-proc-ctx
        (fn []
          (eval! "(require '[proc])")

          (testing "allowed command runs successfully"
            (let [result (eval! "(proc/run \"echo\" \"hello\")")]
              (is (= 0 (:exit result)))
              (is (str/includes? (:out result) "hello"))))

          (testing "blocked command throws ex-info"
            (is (thrown-with-msg?
                  clojure.lang.ExceptionInfo
                  #"not in allowlist"
                  (eval! "(proc/run \"cat\" \"anything\")"))))

          (testing "run! throws on non-zero exit"
            (is (thrown-with-msg?
                  clojure.lang.ExceptionInfo
                  #"exited with"
                  (eval! "(proc/run! \"false\")"))))

          (testing "run returns exit code"
            (let [result (eval! "(proc/run \"false\")")]
              (is (= 1 (:exit result))))))))))

(deftest test-proc-lines
  (with-tmp-dir
    (fn []
      (with-proc-ctx
        (fn []
          (eval! "(require '[proc])")

          (testing "lines splits stdout into a vector of strings"
            (let [result (eval! "(proc/lines \"echo\" \"line1\nline2\")")]
              (is (vector? result))
              (is (>= (count result) 1)))))))))

(deftest test-proc-extra-env
  (with-tmp-dir
    (fn []
      ;; Need a context that allows printenv
      (let [ctx (fresh-ctx)]
        (sandbox/add-proc-ns! ctx :allow #{"printenv"} :base-path *tmp-dir*)
        (binding [*sci-ctx* ctx]
          (eval! "(require '[proc])")
          (testing "extra-env is passed to subprocess"
            (let [result (eval! "(proc/run {:extra-env {\"MY_VAR\" \"testval\"}} \"printenv\" \"MY_VAR\")")]
              (is (= 0 (:exit result)))
              (is (str/includes? (:out result) "testval")))))))))

;; ---------------------------------------------------------------------------
;; add-git-ns! tests
;; ---------------------------------------------------------------------------

(deftest test-git-status
  (with-tmp-dir
    (fn []
      (with-git-ctx
        (fn []
          (eval! "(require '[git])")

          (testing "status on empty repo returns map with branch"
            (let [s (eval! "(git/status)")]
              (is (map? s))
              (is (string? (:branch s)))
              (is (vector? (:staged s)))
              (is (vector? (:unstaged s)))
              (is (vector? (:untracked s)))))

          (testing "new file appears in :untracked"
            (spit (str *tmp-dir* "/new.clj") "(ns new)")
            (let [s (eval! "(git/status)")]
              (is (some #(= "new.clj" %) (:untracked s))))))))))

(deftest test-git-add-and-status
  (with-tmp-dir
    (fn []
      (with-git-ctx
        (fn []
          (eval! "(require '[git])")
          (spit (str *tmp-dir* "/foo.clj") "(ns foo)")

          (testing "add stages the file"
            (eval! "(git/add \"foo.clj\")")
            (let [s (eval! "(git/status)")]
              (is (some #(= "foo.clj" %) (:staged s))))))))))

(deftest test-git-commit-and-log
  (with-tmp-dir
    (fn []
      (with-git-ctx
        (fn []
          (eval! "(require '[git])")
          (spit (str *tmp-dir* "/foo.clj") "(ns foo)")
          (eval! "(git/add \"foo.clj\")")
          (eval! "(git/commit \"Initial commit\")")

          (testing "log returns vector of commit maps"
            (let [log (eval! "(git/log {:n 1})")]
              (is (vector? log))
              (is (= 1 (count log)))
              (let [entry (first log)]
                (is (string? (:hash entry)))
                (is (= "Initial commit" (:message entry)))
                (is (string? (:author entry)))
                (is (string? (:date entry))))))

          (testing "status is clean after commit"
            (let [s (eval! "(git/status)")]
              (is (empty? (:staged s)))
              (is (empty? (:unstaged s)))
              (is (empty? (:untracked s))))))))))

(deftest test-git-diff
  (with-tmp-dir
    (fn []
      (with-git-ctx
        (fn []
          (eval! "(require '[git])")
          ;; Commit initial file
          (spit (str *tmp-dir* "/foo.clj") "(ns foo)")
          (eval! "(git/add \"foo.clj\")")
          (eval! "(git/commit \"Initial\")")
          ;; Modify it
          (spit (str *tmp-dir* "/foo.clj") "(ns foo)\n(def x 1)")

          (testing "diff returns non-empty string when there are changes"
            (let [d (eval! "(git/diff)")]
              (is (string? d))
              (is (str/includes? d "foo.clj")))))))))

;; ---------------------------------------------------------------------------
;; Resource limits
;; ---------------------------------------------------------------------------

;; Op-count and wall-time caps in make-resource-limits were dropped:
;; they fired on legitimately long-running iterations (large folds,
;; multi-step research) as easily as actual infinite loops, while
;; per-token accounting + the eval-code outer-fence timeout already
;; bound runaway evals at the right granularity. See dvergr.chat.accounting
;; for the live budget surface. Tests covering those behaviours intentionally
;; removed.

(deftest test-resource-limits-disabled
  (testing "passing nil resource-limits creates an unrestricted context"
    (let [ctx (sandbox/create-base-ctx :resource-limits nil)]
      (is (= 5050 (sci/eval-string* ctx "(reduce + (range 101))"))))))

;; ---------------------------------------------------------------------------
;; eval-code timeout
;; ---------------------------------------------------------------------------

(deftest test-eval-code-timeout-fires
  (testing "eval-code :timeout-ms stops an infinite loop via watchdog interrupt"
    ;; Use a huge op limit so only the wall-clock watchdog can stop it.
    ;; (loop [] (recur)) fires interrupt-fn on every loop iteration, picking up the
    ;; Thread.interrupt() signal from the watchdog. No stack overflow risk.
    (let [ctx (sandbox/create-base-ctx :resource-limits
                                       (sandbox/make-resource-limits
                                         :max-ops   999999999
                                         :max-ms    60000
                                         :max-bytes (* 4 1024 1024 1024)))
          r   (sandbox/eval-code ctx "(loop [] (recur))" :timeout-ms 500)]
      (is (false? (:success r)))
      (is (re-find #"(?i)wall time|timed out|Resource limit"
                   (get-in r [:error :message]))))))

(deftest test-eval-code-timeout-completes-before-deadline
  (testing "fast eval completes normally when timeout is generous"
    (let [ctx (sandbox/create-base-ctx)
          r   (sandbox/eval-code ctx "(+ 1 2)" :timeout-ms 5000)]
      (is (true? (:success r)))
      (is (= 3 (:value r))))))

(deftest test-eval-code-timeout-no-spurious-interrupt
  (testing "subsequent evals are not interrupted after a timed-out eval"
    ;; After a timed-out eval the watchdog cancels cleanly; the next eval on a
    ;; fresh context must not inherit a stale interrupt flag.
    (let [ctx (sandbox/create-base-ctx :resource-limits
                                       (sandbox/make-resource-limits
                                         :max-ops   999999999
                                         :max-ms    60000
                                         :max-bytes (* 4 1024 1024 1024)))]
      (sandbox/eval-code ctx "(loop [] (recur))" :timeout-ms 200)
      (let [r (sandbox/eval-code ctx "(+ 10 20)")]
        (is (true? (:success r)))
        (is (= 30 (:value r)))))))

;; ---------------------------------------------------------------------------
;; Path canonicalization and sensitive-path policy
;; ---------------------------------------------------------------------------

(deftest test-fs-path-traversal-blocked
  (with-tmp-dir
    (fn []
      (let [ctx (sandbox/create-base-ctx)]
        (sandbox/add-fs-ns! ctx :base-path *tmp-dir*)
        (sci/eval-string* ctx "(require '[fs])")

        ;; Both sensitive-path and sandbox-escape policies throw ExceptionInfo.
        ;; We test that the access is blocked, not which specific guard fires first.
        (testing ".. traversal is blocked"
          (is (thrown? clojure.lang.ExceptionInfo
                (sci/eval-string* ctx "(fs/read \"../../etc/passwd\")"))))

        (testing "absolute path outside base is blocked"
          (is (thrown? clojure.lang.ExceptionInfo
                (sci/eval-string* ctx "(fs/read \"/etc/passwd\")"))))

        (testing "nested traversal is blocked"
          (is (thrown? clojure.lang.ExceptionInfo
                (sci/eval-string* ctx "(fs/read \"subdir/../../etc/shadow\")"))))))))

(deftest test-fs-sensitive-path-policy
  (with-tmp-dir
    (fn []
      (let [ctx (sandbox/create-base-ctx)]
        (sandbox/add-fs-ns! ctx :base-path *tmp-dir*)
        (sci/eval-string* ctx "(require '[fs])")

        (testing ".ssh path pattern is blocked"
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"sensitive path"
                (sci/eval-string* ctx "(fs/read \".ssh/id_rsa\")"))))

        (testing ".env file pattern is blocked"
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"sensitive path"
                (sci/eval-string* ctx "(fs/read \".env\")"))))))))

(deftest test-fs-legitimate-ops-still-work
  (with-tmp-dir
    (fn []
      (let [ctx (sandbox/create-base-ctx)]
        (sandbox/add-fs-ns! ctx :base-path *tmp-dir*)
        (sci/eval-string* ctx "(require '[fs])")

        (testing "write and read within sandbox works"
          (sci/eval-string* ctx "(fs/write \"safe.txt\" \"content\")")
          (is (= "content" (sci/eval-string* ctx "(fs/read \"safe.txt\")"))))

        (testing "subdirectory paths work"
          (sci/eval-string* ctx "(fs/write \"sub/file.txt\" \"hello\")")
          (is (= "hello" (sci/eval-string* ctx "(fs/read \"sub/file.txt\")"))))))))

;; ---------------------------------------------------------------------------
;; Audit log
;; ---------------------------------------------------------------------------

(deftest test-audit-log-fs-ops
  (with-tmp-dir
    (fn []
      (let [ctx (sandbox/create-base-ctx)
            log (sandbox/make-audit-log)]
        (sandbox/add-fs-ns! ctx :base-path *tmp-dir* :audit-log log)
        (sci/eval-string* ctx "(require '[fs])")
        (sci/eval-string* ctx "(fs/write \"x.txt\" \"hi\")")
        (sci/eval-string* ctx "(fs/read \"x.txt\")")
        (sci/eval-string* ctx "(fs/ls \".\")")

        (testing "all IO ops appear in the log"
          (let [ops (mapv :op @log)]
            (is (= [:fs/write :fs/read :fs/ls] ops))))

        (testing "log entries have timestamp and data"
          (let [e (first @log)]
            (is (integer? (:t e)))
            (is (map? (:data e)))
            (is (string? (get-in e [:data :path])))))))))

(deftest test-audit-log-git-write-ops
  (with-tmp-dir
    (fn []
      (let [ctx (sandbox/create-base-ctx)
            log (sandbox/make-audit-log)]
        ;; init git repo
        (doseq [cmd [["git" "init" *tmp-dir*]
                     ["git" "-C" *tmp-dir* "config" "user.email" "t@t.com"]
                     ["git" "-C" *tmp-dir* "config" "user.name" "T"]]]
          (.waitFor (.start (ProcessBuilder. (java.util.ArrayList. cmd)))))
        (sandbox/add-git-ns! ctx :base-path *tmp-dir* :audit-log log)
        (sci/eval-string* ctx "(require '[git])")
        (spit (str *tmp-dir* "/f.clj") "(ns f)")
        (sci/eval-string* ctx "(git/add \"f.clj\")")
        (sci/eval-string* ctx "(git/commit \"init\")")

        (testing "git write ops are logged"
          (let [ops (mapv :op @log)]
            (is (= [:git/add :git/commit] ops))))

        (testing "git/add log includes paths"
          (is (= ["f.clj"] (get-in (first @log) [:data :paths]))))

        (testing "git/commit log includes message"
          (is (= "init" (get-in (second @log) [:data :message]))))))))

;; ---------------------------------------------------------------------------
;; HTTP domain allowlist
;; ---------------------------------------------------------------------------

(deftest test-http-domain-allowlist
  (let [ctx (sandbox/create-base-ctx)
        log (sandbox/make-audit-log)]
    (sandbox/add-http-ns! ctx
                          :audit-log log
                          :allowed-domains #{"https://api.github.com"})
    (sci/eval-string* ctx "(require '[http])")

    (testing "request to unauthorized domain throws policy error"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"unauthorized domain"
            (sci/eval-string* ctx "(http/get \"https://attacker.com/steal\")"))))

    (testing "blocked requests still appear in audit log"
      ;; The audit entry is written before the policy check throws,
      ;; so even blocked requests are visible for forensics.
      (let [events (filter #(str/includes? (str (get-in % [:data :url])) "attacker")
                           @log)]
        (is (= 1 (count events)))
        (is (= :http/request (:op (first events))))))))

(deftest test-http-audit-allowed-request
  (testing "allowed requests appear in audit log"
    (let [ctx (sandbox/create-base-ctx)
          log (sandbox/make-audit-log)]
      (sandbox/add-http-ns! ctx
                            :audit-log log
                            :allowed-domains #{"https://api.github.com"})
      (sci/eval-string* ctx "(require '[http])")
      ;; Attempt a request to the allowed domain; network may or may not succeed —
      ;; we only care that the audit entry was written before any error.
      (try (sci/eval-string* ctx "(http/get \"https://api.github.com/\")")
           (catch Exception _))
      (let [events (filter #(str/includes? (str (get-in % [:data :url])) "api.github.com")
                           @log)]
        (is (= 1 (count events)))))))

(deftest test-http-open-when-no-allowlist
  (testing "empty allowlist permits all domains (no policy exception)"
    (let [ctx (sandbox/create-base-ctx)]
      (sandbox/add-http-ns! ctx :allowed-domains #{})
      (sci/eval-string* ctx "(require '[http])")
      ;; Attempt to a localhost port that refuses connections — we get a network
      ;; exception, NOT a policy violation.  Confirm the thrown exception is not
      ;; an "unauthorized domain" ExceptionInfo.
      (try
        (sci/eval-string* ctx "(http/get \"http://localhost:1\")")
        (catch clojure.lang.ExceptionInfo e
          (is (not (re-find #"unauthorized domain" (or (.getMessage e) "")))))
        (catch Exception _
          ;; Any other exception (ConnectException, etc.) is fine — not a policy block
          :ok)))))  ; any other exception = not a policy block
