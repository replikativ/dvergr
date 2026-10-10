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

(defn- temp-dir!
  "An absolute temp dir: the test JVM's java.io.tmpdir is relative, and a
   relative symlink target would dangle."
  [prefix]
  (.getAbsoluteFile
   (.toFile (java.nio.file.Files/createTempDirectory
             prefix (make-array java.nio.file.attribute.FileAttribute 0)))))

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
                    "(git/diff \"--staged\" \"src/../../x\")"
                    ;; pathspec magic resolves against the repository root
                    "(git/diff \":(top)outside.txt\")" "(git/diff \"--\" \":/x\")"]]
        (is (refused? eval! code) code)))
    (testing "arguments after the options are always paths"
      (is (= ["diff" "--no-ext-diff" "--no-textconv" "--staged" "--stat" "--" "src/a.clj" "HEAD"]
             (diff-argv (str dir) ["--staged" "--stat" "src/a.clj" "HEAD"])))
      (is (= ["diff" "--no-ext-diff" "--no-textconv" "--" "."] (diff-argv (str dir) []))
          "with no paths, a host diff is limited to the workspace")
      (is (= ["diff" "--no-ext-diff" "--no-textconv" "--stat" "--" "--name-only"]
             (diff-argv (str dir) ["--stat" "--" "--name-only"]))
          "after the caller's own --, a dash-led argument is a path")
      (is (= ["diff" "--"] (diff-argv nil []))
          "the virtual workspace gets the same shape without the host-only flags"))
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

(deftest physical-git-diff-leaves-out-sensitive-files
  ;; A tracked `.env` is as secret in a diff as it is to `slurp` and grep.
  (let [dir (git-repo!)
        ctx (sci/init {})]
    (spit (java.io.File. dir ".env") "TOKEN=old\n")
    (sh! dir "git" "add" ".env")
    (sh! dir "git" "-c" "commit.gpgsign=false" "commit" "-q" "-m" "env")
    (spit (java.io.File. dir ".env") "TOKEN=hunter2\n")
    (io/add-git-ns! ctx :base-path (str dir))
    (is (refused? #(sci/eval-string* ctx %) "(git/diff \".env\")"))
    (doseq [code ["(git/diff)" "(git/diff \".\")" "(git/diff \"--stat\")" "(git/diff \"--name-only\")"]]
      (let [d (sci/eval-string* ctx code)]
        (is (str/includes? d "a.clj") (str code " still shows the other change"))
        (is (not (str/includes? d "hunter2")) code)
        (is (not (str/includes? d ".env")) code)))
    (testing "a file named * is a file, not a pathspec that brings .env back"
      (spit (java.io.File. dir "*") "star-before\n")
      (sh! dir "git" "add" "--" ":(literal)*")
      (sh! dir "git" "-c" "commit.gpgsign=false" "commit" "-q" "-m" "star")
      (spit (java.io.File. dir "*") "star-after\n")
      (let [d (sci/eval-string* ctx "(git/diff)")]
        (is (str/includes? d "star-after"))
        (is (not (str/includes? d "hunter2")) d))
      (sh! dir "git" "checkout" "--" ":(literal)*"))
    (testing "a diff of only sensitive changes is empty"
      (sh! dir "git" "checkout" "--" "src/a.clj")
      (is (= "" (sci/eval-string* ctx "(git/diff)"))))
    (testing "a rename does not carry a sensitive source into the diff"
      (sh! dir "git" "checkout" "--" ".env")
      (sh! dir "git" "mv" ".env" "public.txt")
      (doseq [code ["(git/diff \"--staged\")" "(git/diff \"--staged\" \"--no-renames\")"
                    "(git/diff \"--staged\" \"--stat\")"]]
        (let [d (sci/eval-string* ctx code)]
          (is (str/includes? d "public.txt") code)
          (is (not (str/includes? d ".env")) (str code ": " d)))))))

(deftest physical-git-uses-the-workspace-worktree
  ;; `core.worktree` (set directly or through an included config file) would
  ;; point git at another directory than the one every check here is about.
  (let [dir (git-repo!)
        sibling (temp-dir! "dvergr-sibling")
        ctx (sci/init {})]
    (.mkdirs (java.io.File. sibling "src"))
    (spit (java.io.File. sibling "src/a.clj") "(ns a)\n(def SIBLING-SENTINEL 1)\n")
    (sh! dir "git" "config" "core.worktree" (str sibling))
    (io/add-git-ns! ctx :base-path (str dir))
    (let [d (sci/eval-string* ctx "(git/diff \".\")")]
      (is (str/includes? d "changed"))
      (is (not (str/includes? d "SIBLING-SENTINEL")) d))))

(deftest physical-git-add-does-not-stage-sensitive-files
  ;; `git/add "."` (or a glob) takes every file under it; a tracked `.env` and
  ;; an untracked `.env.local` must stay out, as every other file tool keeps them.
  (doseq [operand ["." "*"]]
    (let [dir (git-repo!)
          ctx (sci/init {})]
      (spit (java.io.File. dir ".env") "TOKEN=old\n")
      (sh! dir "git" "add" ".env")
      (sh! dir "git" "-c" "commit.gpgsign=false" "commit" "-q" "-m" "env")
      (spit (java.io.File. dir ".env") "TOKEN=hunter2\n")
      (spit (java.io.File. dir ".env.local") "TOKEN=hunter3\n")
      (io/add-git-ns! ctx :base-path (str dir))
      (is (= :ok (sci/eval-string* ctx (str "(git/add \"" operand "\")"))))
      (let [p (.start (doto (ProcessBuilder. ["git" "diff" "--cached" "--name-only"]) (.directory dir)))
            staged (slurp (.getInputStream p))]
        (is (str/includes? staged "src/a.clj") operand)
        (is (not (str/includes? staged ".env")) (str operand ": " staged))))))

(deftest physical-git-does-not-enter-submodules
  ;; A submodule is a repository of its own: its files, config and filters are
  ;; outside every check the host git call makes.
  (let [dir (git-repo!)
        child (java.io.File. dir "child")
        sentinel (java.io.File. (temp-dir! "dvergr-sub-sentinel") "ran")
        script (java.io.File. (temp-dir! "dvergr-sub-script") "probe.sh")
        ctx (sci/init {})]
    (spit script (str "#!/bin/sh\ntouch " sentinel "\ncat\n"))
    (.setExecutable script true)
    (.mkdirs child)
    (sh! child "git" "init" "-q")
    (sh! child "git" "config" "user.email" "t@example.com")
    (sh! child "git" "config" "user.name" "t")
    (spit (java.io.File. child ".env") "TOKEN=child-old\n")
    (spit (java.io.File. child "f.txt") "one\n")
    (sh! child "git" "add" ".")
    (sh! child "git" "-c" "commit.gpgsign=false" "commit" "-q" "-m" "child")
    (sh! dir "git" "add" "child")
    (sh! dir "git" "-c" "commit.gpgsign=false" "commit" "-q" "-m" "gitlink")
    (sh! dir "git" "config" "diff.submodule" "diff")
    (sh! child "git" "config" "filter.probe.clean" (str script))
    (spit (java.io.File. child ".gitattributes") "*.txt filter=probe\n")
    (spit (java.io.File. child ".env") "TOKEN=child-secret\n")
    (spit (java.io.File. child "f.txt") "two\n")
    (io/add-git-ns! ctx :base-path (str dir))
    (let [d (sci/eval-string* ctx "(git/diff)")]
      (is (str/includes? d "changed") "the parent's own change shows")
      (is (not (str/includes? d "child-secret")) d))
    (is (map? (sci/eval-string* ctx "(git/status)")))
    (is (not (.exists sentinel)) "no filter configured inside the submodule ran")))

(deftest physical-git-runs-no-repository-supplied-commands
  ;; In physical mode the repository's config and hooks live in the workspace.
  ;; Host git must not run an external diff, a textconv driver or a hook that
  ;; someone put there.
  (let [dir (git-repo!)
        sentinel (java.io.File. (temp-dir! "dvergr-git-sentinel") "ran")
        script (java.io.File. (temp-dir! "dvergr-git-script") "probe.sh")
        ctx (sci/init {})]
    (spit script (str "#!/bin/sh\ntouch " sentinel "\n"))
    (.setExecutable script true)
    (sh! dir "git" "config" "diff.external" (str script))
    (sh! dir "git" "config" "diff.probe.textconv" (str script))
    (sh! dir "git" "config" "filter.probe.clean" (str script))
    (sh! dir "git" "config" "filter.probe.process" (str script))
    (spit (java.io.File. dir "src/b.txt") "filtered\n")
    (spit (java.io.File. dir ".gitattributes") "*.clj diff=probe\n*.txt filter=probe\n")
    ;; the attribute sources outside the worktree: global (core.attributesFile)
    ;; is neutralised; `.git/info/attributes` is unwritable from the workspace
    (spit (java.io.File. dir "global-attributes") "*.txt filter=probe\n")
    (sh! dir "git" "config" "core.attributesFile" (str (java.io.File. dir "global-attributes")))
    ;; and `.git/info/attributes`, which git reads whatever GIT_ATTR_SOURCE
    ;; says (written here directly, as if the workspace had reached it)
    (.mkdirs (java.io.File. dir ".git/info"))
    (spit (java.io.File. dir ".git/info/attributes") "*.txt filter=probe\n*.md filter=probe\n")
    (sh! dir "git" "config" "filter.probe.required" "true")
    (spit (java.io.File. dir "src/c.md") "info attributes\n")
    (let [hook (java.io.File. dir ".git/hooks/pre-commit")]
      (.mkdirs (.getParentFile hook))
      (spit hook (str "#!/bin/sh\ntouch " sentinel "\n"))
      (.setExecutable hook true))
    (io/add-git-ns! ctx :base-path (str dir))
    (is (str/includes? (sci/eval-string* ctx "(git/diff)") "changed"))
    (is (str/includes? (sci/eval-string* ctx "(git/diff \"src/a.clj\")") "changed"))
    (sci/eval-string* ctx "(git/add \"src/a.clj\")")
    (sci/eval-string* ctx "(git/add \"src/b.txt\")")
    (is (= :ok (sci/eval-string* ctx "(git/add \"src/c.md\")"))
        "a required filter, emptied, does not fail the add")
    (is (map? (sci/eval-string* ctx "(git/status)")))
    (sci/eval-string* ctx "(git/commit \"probe\")")
    (is (not (.exists sentinel)) "no repository-supplied command ran")))

(deftest physical-git-diff-stays-in-a-nested-workspace
  ;; A workspace can be a subdirectory of a larger repository; an argument-free
  ;; diff must not show the rest of that repository.
  (let [dir (git-repo!)
        ctx (sci/init {})]
    (spit (java.io.File. dir "outside.txt") "before\n")
    (sh! dir "git" "add" "outside.txt")
    (sh! dir "git" "-c" "commit.gpgsign=false" "commit" "-q" "-m" "outside")
    (spit (java.io.File. dir "outside.txt") "after-outside\n")
    (io/add-git-ns! ctx :base-path (str (java.io.File. dir "src")))
    (let [d (sci/eval-string* ctx "(git/diff)")]
      (is (str/includes? d "changed") "changes inside the workspace show")
      (is (not (str/includes? d "after-outside")) "changes outside it do not"))
    (testing "staging cannot reach outside it either"
      (is (refused? #(sci/eval-string* ctx %) "(git/add \":(top)outside.txt\")"))
      (is (refused? #(sci/eval-string* ctx %) "(git/add \"../outside.txt\")"))
      (sci/eval-string* ctx "(git/add \".\")")
      (let [staged (with-out-str
                     (let [p (.start (doto (ProcessBuilder. ["git" "diff" "--cached" "--name-only"])
                                       (.directory dir)))]
                       (print (slurp (.getInputStream p)))))]
        (is (str/includes? staged "src/a.clj"))
        (is (not (str/includes? staged "outside.txt")) staged)))))

(deftest physical-fs-cannot-write-git-internals
  ;; Writing `.git/config` or a hook is how a workspace turns the next host git
  ;; call into command execution.
  (let [dir (git-repo!)
        ctx (sci/init {})]
    (io/add-fs-ns! ctx :base-path (str dir))
    (doseq [code ["(spit \".git/config\" \"[diff]\\n external = /bin/true\\n\")"
                  "(spit \".git/hooks/pre-commit\" \"#!/bin/sh\")"
                  "(spit \"src/../.git/HEAD\" \"x\")"
                  "(spit \".git\" \"gitdir: /tmp/elsewhere\")"]]
      (is (thrown? Exception (sci/eval-string* ctx code)) code))
    (testing "nor through a symlink to it"
      (java.nio.file.Files/createSymbolicLink
       (.toPath (java.io.File. dir "alias"))
       (.toPath (java.io.File. dir ".git"))
       (make-array java.nio.file.attribute.FileAttribute 0))
      (is (thrown-with-msg? Exception #"sensitive path"
                            (sci/eval-string* ctx "(spit \"alias/config\" \"[diff]\\n external = /bin/true\\n\")"))))
    (testing "nor by a tree copy through that symlink"
      (.mkdirs (java.io.File. dir "payload/alias"))
      (spit (java.io.File. dir "payload/alias/config") "[diff]\n external = /bin/true\n")
      (is (thrown-with-msg? Exception #"sensitive path"
                            (sci/eval-string* ctx "(babashka.fs/copy-tree \"payload\" \".\" {:replace-existing true})"))))
    (testing "and a tree copy does not read out through a symlink in its source"
      (let [outside (java.io.File. (temp-dir! "dvergr-outside") "secret.txt")]
        (spit outside "outside-secret")
        (.mkdirs (java.io.File. dir "src2"))
        (java.nio.file.Files/createSymbolicLink
         (.toPath (java.io.File. dir "src2/leak"))
         (.toPath outside)
         (make-array java.nio.file.attribute.FileAttribute 0))
        (is (thrown-with-msg? Exception #"outside sandbox"
                              (sci/eval-string* ctx "(babashka.fs/copy-tree \"src2\" \"dst2\" {:replace-existing true})")))
        (is (not (.exists (java.io.File. dir "dst2/leak"))))))
    ;; (No positive copy-tree case: babashka.fs 0.5.21's copy-tree fails on
    ;; this JDK inside its own permission handling, before and after this
    ;; check.)
    (testing "a recursive delete or a move cannot take .git along"
      (is (thrown-with-msg? Exception #"sensitive path"
                            (sci/eval-string* ctx "(babashka.fs/delete-tree \".\")")))
      (is (.exists (java.io.File. dir ".git/config")))
      (.mkdirs (java.io.File. dir "box"))
      (spit (java.io.File. dir "box/.env") "S=1")
      (is (thrown-with-msg? Exception #"sensitive path"
                            (sci/eval-string* ctx "(babashka.fs/move \"box\" \"box2\")")))
      (is (.exists (java.io.File. dir "box/.env"))))
    (testing "ordinary recursive deletes and moves still work"
      (.mkdirs (java.io.File. dir "tree/sub"))
      (spit (java.io.File. dir "tree/sub/x.txt") "x")
      (sci/eval-string* ctx "(babashka.fs/move \"tree\" \"tree2\")")
      (is (= "x" (slurp (java.io.File. dir "tree2/sub/x.txt"))))
      (sci/eval-string* ctx "(babashka.fs/delete-tree \"tree2\")")
      (is (not (.exists (java.io.File. dir "tree2")))))
    (is (not (str/includes? (slurp (java.io.File. dir ".git/config")) "/bin/true")))
    (testing "ordinary workspace writes still work"
      (is (some? (sci/eval-string* ctx "(spit \"src/b.clj\" \"(ns b)\")")))
      (is (nil? (io/sensitive-path-policy ".github/workflows/x.yml")))
      (is (nil? (io/sensitive-path-policy "vendor/lib.git.bak"))))))

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
      (testing "a file name with a line break cannot smuggle a sensitive match out"
        (let [d (java.io.File. dir ".ssh/x\ninnocent")]
          (when (try (spit d "SECRET in a newline name\n") true (catch Exception _ false))
            (let [{:keys [content]} (run {:pattern "newline name"})]
              (is (not (str/includes? content "SECRET in a newline name")) content)))))
      (testing "-i and glob still apply"
        (is (str/includes? (:content (run {:pattern "secret" :-i true :glob "*.txt"})) "src/a.txt:1:"))
        (is (= "No matches found" (:content (run {:pattern "SECRET" :glob "*.md"}))))))))
