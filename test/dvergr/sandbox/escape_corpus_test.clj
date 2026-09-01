(ns dvergr.sandbox.escape-corpus-test
  "A permanent, executable attack corpus for the SCI sandbox.

   Every entry is an escape that was actually ATTEMPTED against a live context
   built exactly the way production builds it — `fork-for-session` +
   `setup-agent-namespaces!`, then `eval-code` with the execution context bound
   the way `dvergr.agent.process/->process` binds it around a real
   `clojure_eval`. That binding is not incidental: without it the deps
   `:load-fn` throws \"No execution context bound\" on `ec/get-state`, so a mirror
   attack looks refused when it is only mis-fenced by the harness. The escapes
   below only reproduce with the context bound, so `eval-in` binds it. A corpus
   that forgot this would report false safety — which is the exact failure mode
   this whole exercise exists to prevent.
   Every test here asserts that an escape is BLOCKED and that the capability it
   was closed alongside still works — a closed hole plus the feature it was
   closed WITHOUT breaking. These are the regressions we protect: if one goes
   red, either a clamp was removed or a feature was.

   The three interop/mirror escapes below (reflection→Runtime, clojure.java.shell
   mirror, clojure.java.io mirror) were CONFIRMED-OPEN on sci 0.13.52 with
   `:allow :all`; they are now closed by the sci 0.15.56 bump + `lock-interop!`
   + the mirror allowlist, and each docstring records which fix closed it. They
   were once tagged `^:security-open` and skipped while open; that tag is gone
   now that they pass, so they run as ordinary green guards."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.sandbox :as sandbox]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.engine.context :as ctx]
            [dvergr.sandbox.ns.io :as io]))

(defn- with-sandbox
  "Build a production-faithful sandbox and run `(f sci-ctx ec)`, tearing the
   context down after. `setup-agent-namespaces!` wires the full agent surface."
  [f]
  (let [ec      (ctx/create-execution-context)
        sci-ctx (sandbox/fork-for-session ec)]
    (try
      (sandbox/setup-agent-namespaces! sci-ctx ec)
      (f sci-ctx ec)
      (finally (ctx/stop-context! ec)))))

(defn- eval-in
  "Evaluate `code` the way production does: execution context bound (as
   `->process` binds it around every `clojure_eval`), returning
   `{:ok value}` or `{:err message}`. The binding is load-bearing — see the ns
   docstring."
  [sci-ctx ec code]
  (binding [rtc/*execution-context* ec]
    (let [r (sandbox/eval-code sci-ctx code)]
      (if (:success r)
        {:ok (:value r)}
        {:err (get-in r [:error :message])}))))

;; ===========================================================================
;; GROUP 1 — green guards: closed holes + the capability kept intact
;; ===========================================================================

(deftest sensitive-path-policy-covers-every-branch
  ;; The pattern was wrapped across three lines; a Clojure regex literal is not
  ;; whitespace-insensitive, so `/etc/sudoers` and `~/.gcloud/` silently
  ;; required a leading newline+spaces and were never blocked. Flattened now —
  ;; pin EVERY branch so a re-wrap can't quietly kill one again.
  (testing "every listed sensitive path throws"
    (doseq [p ["/home/u/.ssh/id_rsa" "/home/u/.gnupg/secring" "/etc/shadow"
               "/etc/passwd" "/etc/sudoers" "/proc/self/environ" "/sys/kernel"
               "/home/u/.aws/credentials" "/home/u/.azure/x" "/home/u/.gcloud/creds"
               "/run/secrets/x" "/app/.env" "/app/.env.local"]]
      (is (thrown? Exception (io/sensitive-path-policy p)) p)))
  (testing "ordinary workspace paths still pass"
    (doseq [p ["src/core.clj" "notes/todo.md" "deps.edn"]]
      (is (nil? (io/sensitive-path-policy p)) p))))

(deftest deps-mirror-cannot-leak-daemon-secrets
  ;; `dvergr.substrate.config` holds the live telegram/github tokens. The deps
  ;; `:load-fn` mirrors any host namespace not on the denylist; the denylist had
  ;; only the stale top-level `^dvergr\.config`, which stopped matching once
  ;; config moved under `dvergr.substrate.*`. So `(require
  ;; 'dvergr.substrate.config)` mirrored it and `(…/telegram-token)` returned
  ;; the real bot token. Fixed by denylisting the whole `dvergr.substrate`
  ;; subtree. (Defense-in-depth only — reflection still reaches the host env;
  ;; see reflection-interop below.)
  (with-sandbox
    (fn [sci ec]
      (testing "the secret-holding config namespace no longer mirrors"
        (let [r (eval-in sci ec "(require '[dvergr.substrate.config :as cfg]) (cfg/telegram-token)")]
          (is (:err r) "config mirror must be refused")
          (is (str/includes? (str (:err r)) "Could not find namespace")
              "SCI's standard not-found — the ns is denied, not present")))
      (testing "the rest of the substrate subtree is denied too"
        (is (:err (eval-in sci ec "(require '[dvergr.substrate.git]) :loaded"))))
      (testing "and the already-curated daemon internals stay denied"
        (is (:err (eval-in sci ec "(require '[dvergr.daemon]) :loaded")))
        (is (:err (eval-in sci ec "(require '[dvergr.tools]) :loaded")))))))

(deftest legitimate-capabilities-still-work
  ;; The guard is \"don't mirror the dangerous host ns\", NOT \"break require\".
  ;; If closing a hole broke these, the fix would be wrong — so pin the feature.
  (with-sandbox
    (fn [sci ec]
      (testing "a host stdlib namespace the sandbox has no opinion on still mirrors"
        (is (= #{1 2} (:ok (eval-in sci ec "(require '[clojure.set :as s]) (s/union #{1} #{2})")))))
      (testing "the agent's own room-facing surface is present"
        (is (= true (:ok (eval-in sci ec "(some? dvergr.room/kb-search)")))))
      (testing "in-jail file access works"
        (is (= true
               (:ok (eval-in sci ec
                             "(let [p (str \".capability-probe-\" (random-uuid))]
                                (try
                                  (spit p \"ok\")
                                  (babashka.fs/exists? p)
                                  (finally
                                    (babashka.fs/delete-if-exists p))))")))))
      (testing "but escapes past the jail are refused"
        (is (:err (eval-in sci ec "(babashka.fs/exists? \"/etc/passwd\")")))
        (is (:err (eval-in sci ec "(slurp \"/etc/passwd\")")))))))

(deftest registered-class-interop-must-keep-working
  ;; Blast-radius guard for the sci 0.13.52 → 0.15.56 bump (ADR 0007, task #55).
  ;; The bump makes instance interop on an UNREGISTERED class throw — that is how
  ;; it kills the reflection escape. The flip side (the migration risk the bump
  ;; must not regress): interop on a REGISTERED base-classes type must still
  ;; work. If the bump breaks this, the fix is to WIDEN base-classes, never to
  ;; loosen to :allow :all. Must stay green after the bump.
  (with-sandbox
    (fn [sci ec]
      (is (= 0 (:ok (eval-in sci ec "(.getTime (java.util.Date. 0))")))
          "interop on a registered class (java.util.Date) must keep working")
      (is (= "AB" (:ok (eval-in sci ec "(.toUpperCase \"ab\")")))
          "interop on a registered class (String) must keep working"))))

;; ===========================================================================
;; GROUP 2 — the interop / mirror escapes, now CLOSED
;;
;; These were confirmed-open on sci 0.13.52 with `:allow :all` and were once
;; tagged `^:security-open` (asserting the blocked behaviour while it still
;; failed). Each is now closed, by a DIFFERENT fix — recorded per test — so they
;; run as ordinary green guards:
;;
;;   - reflection-interop  → sci 0.13.52 → 0.15.56 (ADR 0007, \"instance-member
;;                           control\") PLUS `dvergr.sandbox/lock-interop!`, which
;;                           strips spindel's `:allow :all` from the agent ctx so
;;                           the per-class gate actually engages. The bump alone
;;                           is not enough: under `:allow :all` the gate is off.
;;   - host-*-namespace    → the deps :load-fn MIRROR is a positive allowlist
;;                           (deny-by-default) — clojure.java.shell / .io are not
;;                           allowlisted, so they never mirror. A mirrored host
;;                           FUNCTION runs as compiled host code the interop gate
;;                           never sees, so the allowlist — not the bump — is the
;;                           fix here (though host-io is also closed by the bump,
;;                           since `.readLine` is interop on an unregistered
;;                           java.io.BufferedReader).
;; ===========================================================================

(deftest reflection-interop-must-not-reach-the-host
  ;; MECHANISM: on sci 0.13.52 the `:classes` allowlist gates only class-SYMBOL
  ;; resolution; JVM instance interop is NOT gated (allowed-instance-method-
  ;; invocation is cljs-only in sci/impl/evaluator.cljc). From any app object an
  ;; agent navigates .getClass → .getClassLoader → (.loadClass \"java.lang.Runtime\")
  ;; → reflect getRuntime/exec, forking a real host process as the daemon user.
  ;;
  ;; EXECUTED on 0.13.52 (with :allow :all): returned Process[pid=…]. CLOSED by
  ;; the first `.getClass` on the unregistered clojure.lang.Atom throws
  ;; \"Method getClass on class clojure.lang.Atom not allowed!\" and this goes green.
  (with-sandbox
    (fn [sci ec]
      (let [r (eval-in sci ec
                       "(let [cl (-> (atom 1) .getClass .getClassLoader)
                              rt (.loadClass cl \"java.lang.Runtime\")
                              classCls (.loadClass cl \"java.lang.Class\")
                              objCls (.loadClass cl \"java.lang.Object\")
                              strCls (.loadClass cl \"java.lang.String\")
                              getRt (.getMethod rt \"getRuntime\" (make-array classCls 0))
                              inst  (.invoke getRt nil (make-array objCls 0))
                              execM (.getMethod rt \"exec\" (doto (make-array classCls 1) (aset 0 strCls)))]
                          (str (.invoke execM inst (doto (make-array objCls 1) (aset 0 \"id\")))))")]
        (is (:err r)
            "reflective navigation to java.lang.Runtime must be blocked")
        (is (not (str/includes? (str (:ok r)) "Process"))
            "no host process may be forked from inside the sandbox")
        ;; Pins that the block is sci's instance-member gate (ADR 0007), not
        ;; some incidental failure — the message must say "not allowed".
        (when (:err r)
          (is (str/includes? (str (:err r)) "not allowed")
              "the block must be sci's instance-member gate (ADR 0007)"))))))

(defn- with-shell-sandbox
  "Like `with-sandbox`, but ALSO wires the muschel-backed shell.

   `babashka.process` is registered by `add-bash-ns!`, which production calls
   from `dvergr.agent.turn` per chat-context — NOT from `setup-agent-namespaces!`.
   So the plain harness has no `babashka.process` at all, and a test written on
   it cannot tell \"the shell is denied\" from \"the shell was never mounted\".
   That blind spot is why the corpus asserted the mirror is CLOSED without ever
   asserting the capability it was closed alongside still WORKS — half of this
   corpus's stated contract, untested. `add-bash-ns!` needs only `:spindel-ctx`
   off the chat-ctx for session/host creation, so a stub suffices."
  [f]
  (let [ec      (ctx/create-execution-context)
        sci-ctx (sandbox/fork-for-session ec)]
    (try
      (sandbox/setup-agent-namespaces! sci-ctx ec)
      (io/add-bash-ns! sci-ctx {:spindel-ctx ec})
      (f sci-ctx ec)
      (finally (ctx/stop-context! ec)))))

(deftest muschel-shell-still-works-while-the-mirror-stays-denied
  ;; THE CAPABILITY HALF. `babashka.process` was added to the hard denylist to
  ;; close `(require 'babashka.process :reload)` → host shell. The sandbox's OWN
  ;; muschel-backed `babashka.process/shell` must survive that: the denylist
  ;; governs the MIRROR path, while a registered namespace resolves from the ctx
  ;; and never consults it. If this goes red, the escape was closed by breaking
  ;; the feature — exactly the trade this corpus exists to prevent.
  (with-shell-sandbox
    (fn [sci ec]
      (testing "the agent-facing shell runs"
        (let [r (eval-in sci ec
                         "(require '[babashka.process :as p])
                          (:out (p/shell \"echo corpus-shell-ok\"))")]
          (is (:ok r) (str "p/shell must work: " (pr-str (:err r))))
          (is (str/includes? (str (:ok r)) "corpus-shell-ok")
              "…and actually return the command's stdout")))

      (testing ":reload of it is STILL refused, now that it is registered"
        ;; With the ns registered the refusal comes from the `:reload` guard
        ;; rather than a bare \"could not find namespace\" — the guard is the
        ;; part that matters, since a merge would replace the jailed shim.
        (let [r (eval-in sci ec "(require 'babashka.process :reload)")]
          (is (:err r) "must not mirror the host namespace over the shim")
          (is (str/includes? (str (:err r)) "Refusing to reload")
              "and must say why")))

      (testing "the shim still works AFTER the reload attempt"
        (let [r (eval-in sci ec
                         "(:out (babashka.process/shell \"echo still-here\"))")]
          (is (str/includes? (str (:ok r)) "still-here")
              "the reload attempt must not have damaged the shim"))))))

(deftest host-shell-namespace-must-not-mirror
  ;; MECHANISM: the deps :load-fn mirror — `clojure.java.shell` is not on the
  ;; denylist, so `(require 'clojure.java.shell)` mirrors the host ns and
  ;; `(sh/sh \"id\")` runs a host shell outside the muschel jail. `sh` returns a
  ;; plain map (no interop on the result), so the sci 0.14 interop gate does NOT
  ;; close this — only a mirror ALLOWLIST does.
  ;;
  ;; EXECUTED on 0.13.52 (denylist mirror): {:exit 0 :out \"uid=1000(…)…\"}
  (with-sandbox
    (fn [sci ec]
      (let [r (eval-in sci ec "(require '[clojure.java.shell :as sh]) (:out (sh/sh \"id\"))")]
        (is (:err r) "clojure.java.shell must not mirror into the sandbox")
        (is (not (str/includes? (str (:ok r)) "uid="))
            "no host shell as the daemon user")))))

(deftest host-io-namespace-must-not-mirror
  ;; MECHANISM: `clojure.java.io` mirrors the same way; its host `reader`/`file`
  ;; return real host handles that read any path — the babashka.fs / slurp clamp
  ;; is bypassed. (Closed by EITHER the mirror allowlist OR — since consuming the
  ;; reader needs `.readLine` interop on an unregistered java.io.BufferedReader —
  ;; the sci 0.14 bump; whichever lands first.)
  ;;
  ;; EXECUTED on 0.13.52 (denylist mirror): read \"root:x:0:0:…\" from /etc/passwd
  (with-sandbox
    (fn [sci ec]
      (let [r (eval-in sci ec
                       "(require '[clojure.java.io :as jio]) (with-open [r (jio/reader \"/etc/passwd\")] (.readLine r))")]
        (is (:err r) "clojure.java.io must not mirror a host reader")
        (is (not (str/includes? (str (:ok r)) "root:"))
            "no unclamped host filesystem read")))))
