(ns dvergr.sandbox.security-test
  "Tests for the sandbox security boundary — the policies hardened in the
   2026-06 security audit. (Replaces the original sandbox_shell_test.clj, which
   tested the pre-refactor fs/proc API and was stale since the project's first
   commit.)"
  (:require [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [dvergr.sandbox.ns.io :as io]
            [dvergr.intake.bash :as bash]))

(deftest sensitive-path-policy-blocks-secrets
  (testing "known-sensitive OS paths are rejected"
    (doseq [p ["/etc/passwd" "/etc/shadow" "/home/u/.ssh/id_rsa" "/app/.env"
               "/x/.aws/credentials" "/proc/self/environ"]]
      (is (thrown? Exception (io/sensitive-path-policy p)) p)))
  (testing "ordinary workspace paths pass"
    (doseq [p ["src/core.clj" "/tmp/work/notes.md" "dvergr/intake/hn.clj"]]
      (is (nil? (io/sensitive-path-policy p)) p))))

(deftest ssrf-guard-blocks-internal
  (testing "internal / loopback / metadata / non-http rejected"
    (doseq [u ["file:///etc/passwd" "http://127.0.0.1:8080/x" "http://localhost/x"
               "http://169.254.169.254/latest/meta-data/" "http://192.168.1.1/"
               "http://10.0.0.5/" "ftp://example.com/x"]]
      (is (thrown? Exception (io/ssrf-guard! u)) u))))

(deftest domain-policy-is-anchored
  (testing "a look-alike subdomain of an allowed origin is NOT allowed"
    (let [check (io/make-domain-policy #{"https://api.github.com"})]
      (is (nil? (check "https://api.github.com/repos")))      ; allowed origin + path
      (is (thrown? Exception (check "https://api.github.com.attacker.com/x"))))))

(deftest fs-path-clamp-blocks-escape
  (testing "babashka.fs slurp/spit cannot escape the base-path"
    (let [dir (str (System/getProperty "java.io.tmpdir") "/dvergr-sec-" (hash (str *ns*)))]
      (.mkdirs (java.io.File. dir))
      (let [ctx (sci/init {})]
        (io/add-fs-ns! ctx :base-path dir)
        (is (thrown? Exception
                     (sci/eval-string* ctx "(slurp \"../../../../etc/passwd\")")))
        (is (thrown? Exception
                     (sci/eval-string* ctx "(slurp \"/etc/passwd\")")))
        (is (thrown? Exception
                     (sci/eval-string* ctx "(spit \"../../escape.txt\" \"x\")")))))))

(deftest env-get-has-no-host-env-access
  (testing "env/get returns ONLY per-agent config — never the host process env"
    (let [ctx (sci/init {})]
      (io/add-env-ns! ctx :user-config (atom {"GRANTED" "ok"}))
      ;; daemon secrets AND ordinary host vars (PATH/HOME are always set) are invisible
      (doseq [v ["OPENAI_API_KEY" "AWS_SECRET_ACCESS_KEY" "DATABASE_URL" "PATH" "HOME"]]
        (is (nil? (sci/eval-string* ctx (str "(env/get \"" v "\")"))) v))
      ;; only explicitly granted config keys are visible
      (is (= "ok" (sci/eval-string* ctx "(env/get \"GRANTED\")")))
      (is (= ["GRANTED"] (sci/eval-string* ctx "(env/keys)"))))))

(deftest git-policy-allowlist
  (testing "local git subcommands allowed, network/escape ones are not"
    (is (contains? bash/git-local-subcommands "status"))
    (is (contains? bash/git-local-subcommands "commit"))
    (is (contains? bash/git-local-subcommands "diff"))
    (doseq [forbidden ["clone" "push" "fetch" "pull" "remote" "submodule" "config"]]
      (is (not (contains? bash/git-local-subcommands forbidden)) forbidden))))
