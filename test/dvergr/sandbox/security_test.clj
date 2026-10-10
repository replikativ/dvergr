(ns dvergr.sandbox.security-test
  "Tests for the sandbox security boundary — the policies hardened in the
   2026-06 security audit. (Replaces the original sandbox_shell_test.clj, which
   tested the pre-refactor fs/proc API and was stale since the project's first
   commit.)"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [dvergr.sandbox.ns.io :as io]
            [dvergr.intake.bash :as bash]
            [dvergr.tools :as tools]
            [muschel.fs :as mfs]
            [muschel.fs.geschichte :as gfs]))

(deftest structured-tools-use-the-virtual-workspace
  (let [{:keys [conn close!] :as repository}
        (gfs/memory-repository! {:name "structured-tools"})
        filesystem (gfs/make-root repository)
        ctx (tools/make-context {:cwd "/" :filesystem filesystem})]
    (try
      (is (= :success (:type (tools/execute "write_file"
                                            {:path "src/demo.clj"
                                             :content "(ns demo)\n(defn answer [] 41)\n"}
                                            ctx))))
      (is (= :success (:type (tools/execute "edit_file"
                                            {:path "src/demo.clj"
                                             :old_string "41" :new_string "42"}
                                            ctx))))
      (is (= :success (:type (tools/execute "clojure_edit"
                                            {:file_path "src/demo.clj"
                                             :form_type "defn" :form_name "answer"
                                             :operation "replace"
                                             :new_source "(defn answer [] :virtual)"}
                                            ctx))))
      (is (str/includes? (:content (tools/execute "read_file"
                                                  {:path "src/demo.clj"} ctx))
                         ":virtual"))
      (is (= :file (:type (mfs/stat filesystem "/src/demo.clj"))))
      (is (= :error (:type (tools/execute "read_file"
                                          {:path "../../etc/passwd"} ctx))))
      (finally (close!)))))

(deftest sci-files-and-git-share-a-virtual-geschichte-workspace
  (let [{:keys [conn close!] :as repository}
        (gfs/memory-repository! {:name "sci-virtual"})
        workspace {:conn conn :id [(get-in @conn [:config :store :id]) :db]
                   :repository repository}
        filesystem (gfs/make-root repository)
        ctx (sci/init {})]
    (try
      (io/add-fs-ns! ctx :filesystem filesystem)
      (io/add-git-ns! ctx :workspace workspace)
      (is (= "src/demo.clj"
             (sci/eval-string* ctx
                               "(spit \"src/demo.clj\" \"(ns demo)\\n\")")))
      (is (= "(ns demo)\n" (sci/eval-string* ctx "(slurp \"src/demo.clj\")")))
      (is (= ["src/demo.clj"]
             (sci/eval-string* ctx
                               "(require '[babashka.fs :as fs]) (fs/glob \"**/*.clj\")")))
      (is (= ["src/demo.clj"]
             (:untracked (sci/eval-string* ctx "(git/status)"))))
      (is (= :ok (sci/eval-string* ctx "(git/add \".\")")))
      (is (string? (sci/eval-string* ctx "(git/commit \"SCI virtual\")")))
      (finally (close!)))))

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

(deftest ssrf-guard-blocks-unique-local-and-carrier-grade-nat
  ;; Neither range is "site local" to java.net.InetAddress: fc00::/7 is IPv6's
  ;; private range (RFC 4193) and 100.64.0.0/10 is shared address space (RFC
  ;; 6598) that cloud VPCs and k8s pod networks use for internal services.
  (doseq [u ["http://[fd00::1]/" "http://[fc00::1]/" "http://[fdff:ffff::1]/x"
             "http://100.64.0.1/" "http://100.100.100.200/latest/meta-data/"
             "http://100.127.255.254/"]]
    (is (thrown? Exception (io/ssrf-guard! u)) u))
  (testing "neighbouring public ranges still pass"
    (doseq [u ["http://100.63.255.255/" "http://100.128.0.1/" "http://[2001:4860:4860::8888]/"
               "http://[fe00::1]/"]]
      (is (nil? (io/ssrf-guard! u)) u))))

(defn- temp-dir! [prefix]
  (.toFile (java.nio.file.Files/createTempDirectory
            prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- sh! [dir & args]
  (let [p (.start (doto (ProcessBuilder. ^java.util.List (vec args))
                    (.directory dir)
                    (.redirectErrorStream true)))]
    (slurp (.getInputStream p))
    (.waitFor p)))

(defn- git-repo! []
  (let [dir (temp-dir! "dvergr-git-diff")]
    (sh! dir "git" "init" "-q")
    (sh! dir "git" "config" "user.email" "t@example.com")
    (sh! dir "git" "config" "user.name" "t")
    (.mkdirs (java.io.File. dir "src"))
    (spit (java.io.File. dir "src/a.clj") "(ns a)\n")
    (sh! dir "git" "add" ".")
    (sh! dir "git" "-c" "commit.gpgsign=false" "commit" "-q" "-m" "init")
    (spit (java.io.File. dir "src/a.clj") "(ns a)\n(def changed 1)\n")
    dir))

(defn- diff-argv [& args]
  ;; resolved at run time so the suite compiles against a tree without it
  (apply (requiring-resolve 'dvergr.sandbox.ns.io/git-diff-argv) args))

(defn- refused?
  "Did `code` throw the sandbox's own git-argument refusal — not merely some
   git failure? A `--no-index` diff exits 1 on differences, so a bare
   `thrown?` passes while the exception carries the host file's content."
  [eval! code]
  (try (eval! code) false
       (catch Exception e
         (boolean (some #(= :dvergr/git-arg-refused (:type (ex-data %)))
                        (take-while some? (iterate ex-cause e)))))))

(deftest physical-git-diff-is-confined-to-the-workspace
  ;; `git/diff` passed its arguments straight to host git: `--no-index` diffs
  ;; any two host files (the exit-1 exception carried the content), and
  ;; `--output=<file>` writes anywhere the daemon user can.
  (let [dir (git-repo!)
        ctx (sci/init {})
        eval! #(sci/eval-string* ctx %)
        out (java.io.File. (temp-dir! "dvergr-git-out") "diff-proof")]
    (io/add-git-ns! ctx :base-path (str dir))
    (testing "options outside the allowlist are refused"
      (doseq [code ["(git/diff \"--no-index\" \"/dev/null\" \"/etc/hostname\")"
                    (str "(git/diff \"--output=" out "\")")
                    "(git/diff \"--output\" \"/tmp/x\")"
                    "(git/diff \"--ext-diff\")"
                    "(git/diff \"-O/etc/passwd\")"]]
        (is (refused? eval! code) code))
      (is (not (.exists out)) "nothing was written outside the workspace"))
    (testing "paths outside the workspace are refused"
      (doseq [code ["(git/diff \"/etc/passwd\")" "(git/diff \"../../etc/passwd\")"
                    "(git/diff \"--staged\" \"src/../../x\")"]]
        (is (refused? eval! code) code)))
    (testing "arguments after the options are always paths"
      (is (= ["diff" "--staged" "--stat" "--" "src/a.clj" "HEAD"]
             (diff-argv (str dir) ["--staged" "--stat" "src/a.clj" "HEAD"])))
      (is (= ["diff" "--"] (diff-argv (str dir) []))))
    (testing "ordinary diffs keep working"
      (is (str/includes? (sci/eval-string* ctx "(git/diff)") "changed"))
      (is (str/includes? (sci/eval-string* ctx "(git/diff \"src/a.clj\")") "changed"))
      (is (str/includes? (sci/eval-string* ctx "(git/diff \"--stat\")") "src/a.clj"))
      (is (= "" (sci/eval-string* ctx "(git/diff \"--staged\")")))
      (is (str/includes? (sci/eval-string* ctx "(git/diff \"-U0\" \"src\")") "changed")))
    (testing "git/log's :n is a count, not an option"
      (is (refused? eval! (str "(git/log {:n \"-output=" out "\"})")))
      (is (not (.exists out)))
      (is (= 1 (count (sci/eval-string* ctx "(git/log {:n 1})")))))))

(deftest physical-grep-is-confined
  ;; The host-grep branch of the `grep` tool (no virtual filesystem) passed the
  ;; pattern where grep reads options, and searched dot-files like `.env`
  ;; that every other file tool refuses.
  (let [dir (temp-dir! "dvergr-grep")]
    (.mkdirs (java.io.File. dir "src"))
    (.mkdirs (java.io.File. dir ".ssh"))
    (spit (java.io.File. dir "src/a.txt") "SECRET is mentioned here\nuse --help for help\n")
    (spit (java.io.File. dir ".env") "SECRET=hunter2\n")
    (spit (java.io.File. dir ".env.local") "SECRET=hunter3\n")
    (spit (java.io.File. dir ".ssh/id_rsa") "SECRET key\n")
    (let [run (fn [input] (tools/execute "grep" input {:cwd (str dir)}))]
      (testing "sensitive files are not searched"
        (let [{:keys [content]} (run {:pattern "SECRET"})]
          (is (str/includes? content "src/a.txt:1:SECRET is mentioned here"))
          (is (not (str/includes? content "hunter")) content)
          (is (not (str/includes? content "id_rsa")) content)))
      (testing "the pattern is a pattern, never an option"
        (let [{:keys [content]} (run {:pattern "--help"})]
          (is (str/includes? content "src/a.txt:2:use --help for help") content)
          (is (not (str/includes? content "Usage")) content)))
      (testing "-i and glob still apply"
        (is (str/includes? (:content (run {:pattern "secret" :-i true :glob "*.txt"})) "src/a.txt:1:"))
        (is (= "No matches found" (:content (run {:pattern "SECRET" :glob "*.md"}))))))))
