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

(declare refused?)

(deftest virtual-workspace-keeps-sensitive-files-out-of-grep-and-staging
  ;; The virtual (Geschichte) backend gets the same sensitive-file treatment as
  ;; the physical one: grep does not search a `.env`, `git/add "."` leaves it.
  (let [{:keys [conn close!] :as repository} (gfs/memory-repository! {:name "virtual-sensitive"})
        workspace {:conn conn :id [(get-in @conn [:config :store :id]) :db]
                   :repository repository}
        filesystem (gfs/make-root repository)
        tctx (tools/make-context {:cwd "/" :filesystem filesystem})
        ctx (sci/init {})]
    (try
      (io/add-fs-ns! ctx :filesystem filesystem)
      (io/add-git-ns! ctx :workspace workspace)
      ;; seeded host-side: no agent route writes a sensitive path
      (mfs/write-string! filesystem "/.env" "REVIEW_SECRET=123\n" false)
      (mfs/write-string! filesystem "/-A" "dash-led name\n" false)
      (doseq [[tool input] [["read_file" {:path ".env"}]
                            ["write_file" {:path ".env" :content "x"}]
                            ["edit_file" {:path ".env" :old_string "123" :new_string "456"}]]]
        (is (= :error (:type (tools/execute tool input tctx))) tool))
      (sci/eval-string* ctx "(spit \"src/x.clj\" \"(ns x) ; REVIEW_SECRET mention\")")
      (let [{:keys [content]} (tools/execute "grep" {:pattern "REVIEW_SECRET"} tctx)]
        (is (str/includes? content "src/x.clj") content)
        (is (not (str/includes? content "123")) content))
      (is (refused? #(sci/eval-string* ctx %) "(git/add \"-A\")")
          "geschichte reads -A anywhere in argv")
      (is (= :ok (sci/eval-string* ctx "(git/add \"./src/../src/x.clj\")")))
      (let [{:keys [staged]} (sci/eval-string* ctx "(git/status)")]
        (is (= ["src/x.clj"] staged) "a normalised path stages that file, and only it"))
      (is (= :ok (sci/eval-string* ctx "(git/add \".\")")))
      (let [{:keys [staged untracked]} (sci/eval-string* ctx "(git/status)")]
        (is (some #{"src/x.clj"} staged) (pr-str staged))
        (is (not (some #{".env"} staged)) (pr-str staged)))
      (is (refused? #(sci/eval-string* ctx %) "(git/add \"*\")"))
      (finally (close!)))))

(deftest virtual-staging-and-tree-ops-are-confined
  (let [{:keys [conn close!] :as repository} (gfs/memory-repository! {:name "virtual-confined"})
        workspace {:conn conn :id [(get-in @conn [:config :store :id]) :db]
                   :repository repository}
        filesystem (gfs/make-root repository)
        ctx (sci/init {})
        staged #(:staged (sci/eval-string* ctx "(git/status)"))]
    (try
      (io/add-fs-ns! ctx :filesystem filesystem)
      (io/add-git-ns! ctx :workspace workspace)
      (sci/eval-string* ctx "(spit \"src/x.clj\" \"(ns x)\")")
      (testing "a normalised operand stages its file when nothing is sensitive"
        (is (= :ok (sci/eval-string* ctx "(git/add \"./src/../src/x.clj\")")))
        (is (= ["src/x.clj"] (staged))))
      (testing "a file name with a line break cannot forge a status record"
        ;; seeded host-side
        (mfs/mkdir filesystem "/dir")
        (mfs/write-string! filesystem "/dir/.env" "S=1\n" false)
        (mfs/write-string! filesystem "/bait" "b\n" false)
        (mfs/write-string! filesystem "/bait\n?? dir" "b\n" false)
        (is (= :ok (sci/eval-string* ctx "(git/add \".\")")))
        (is (not (some #{"dir/.env"} (staged))) (pr-str (staged))))
      (testing "a recursive copy, move or delete cannot take a sensitive file along"
        (mfs/mkdir filesystem "/.ssh")
        (mfs/write-string! filesystem "/.ssh/key" "SECRET\n" false)
        (doseq [code ["(babashka.fs/copy-tree \".ssh\" \"public\")"
                      "(babashka.fs/move \".ssh\" \"public2\")"
                      "(babashka.fs/delete-tree \".ssh\")"
                      "(babashka.fs/copy-tree \"dir\" \"dir2\")"]]
          (is (thrown-with-msg? Exception #"sensitive path" (sci/eval-string* ctx code)) code))
        (is (thrown? Exception (sci/eval-string* ctx "(slurp \"public/key\")")))
        (is (= "SECRET\n" (mfs/read-file filesystem "/.ssh/key")) "still there"))
      (testing "ordinary tree ops still work"
        (sci/eval-string* ctx "(spit \"tree/sub/y.txt\" \"y\")")
        (sci/eval-string* ctx "(babashka.fs/copy-tree \"tree\" \"tree2\")")
        (is (= "y" (sci/eval-string* ctx "(slurp \"tree2/sub/y.txt\")")))
        (sci/eval-string* ctx "(babashka.fs/delete-tree \"tree2\")")
        (is (false? (sci/eval-string* ctx "(babashka.fs/exists? \"tree2\")"))))
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

(defn- refused?
  "Did `code` throw the sandbox's own git-argument refusal — not merely some
   git failure? A `--no-index` diff exits 1 on differences, so a bare
   `thrown?` passes while the exception carries the host file's content."
  [eval! code]
  (try (eval! code) false
       (catch Exception e
         (boolean (some #(= :dvergr/git-arg-refused (:type (ex-data %)))
                        (take-while some? (iterate ex-cause e)))))))

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
    (testing "nor a move put a file where a sensitive one would be"
      (.mkdirs (java.io.File. dir "keys"))
      (spit (java.io.File. dir "keys/authorized_keys") "ssh-ed25519 AAAA attacker")
      (is (thrown-with-msg? Exception #"sensitive path"
                            (sci/eval-string* ctx "(babashka.fs/move \"keys\" \".ssh\")")))
      (is (not (.exists (java.io.File. dir ".ssh/authorized_keys")))))
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

(deftest agents-get-no-host-git
  ;; Without a room (Geschichte) workspace there is no git for agents: host
  ;; git runs repository-configured commands (hooks, filters, diff drivers,
  ;; transports, gc hooks) from a repository the agent can write to.
  (let [dir (git-repo!)
        ctx (sci/init {})]
    (io/add-git-ns! ctx :base-path (str dir))
    (doseq [code ["(git/status)" "(git/log {:n 1})" "(git/diff)"
                  "(git/diff \"--no-index\" \"/dev/null\" \"/etc/hostname\")"
                  "(git/add \"src/a.clj\")" "(git/commit \"x\")"]]
      (let [r (try (sci/eval-string* ctx code) (catch Exception e e))]
        (is (instance? Exception r) code)
        (is (some #(= :no-room-workspace (:reason (ex-data %)))
                  (take-while some? (iterate ex-cause r)))
            code)))))

(deftest virtual-git-diff-arguments-are-confined
  (is (= ["diff" "--staged" "--stat" "--" "src/a.clj" "HEAD"]
         (io/git-diff-argv ["--staged" "--stat" "src/a.clj" "HEAD"])))
  (is (= ["diff" "--stat" "--" "--name-only"] (io/git-diff-argv ["--stat" "--" "--name-only"]))
      "after the caller's own --, a dash-led argument is a path")
  (doseq [args [["--no-index" "/dev/null" "/etc/hostname"] ["--output=/tmp/x"] ["--ext-diff"]
                [":(top)x"] ["../../etc/passwd"] [".env"]]]
    (is (thrown? Exception (io/git-diff-argv args)) (pr-str args))))

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
