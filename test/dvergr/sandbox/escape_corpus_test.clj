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
            [clojure.repl.deps]
            [clojure.test :refer [deftest is testing]]
            [dvergr.discourse :as d]
            [dvergr.room.registry :as rreg]
            [dvergr.room.store :as rstore]
            [dvergr.room.store.memory :as mem]
            [dvergr.substrate.paths :as paths]
            [dvergr.system.db :as sdb]
            [datahike.api :as dh]
            [dvergr.orchestration.skills :as skills]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.deps :as deps]
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

(deftest room-ops-hand-out-data-not-live-rooms
  ;; `dvergr.room/get` (list, children, fork!, forks, root, …) returned the live
  ;; Room record: code replaced a room's participants (`reset!` on its atom),
  ;; edited its meta and held its execution context, store and bus — for every
  ;; room `list` showed. CONFIRMED-OPEN before the ops returned data views.
  ;; create! provisions a room and its stores under the state root: a
  ;; temporary one, so nothing outlives the test
  (let [prev (paths/home)]
    (try
      (paths/set-home! (str (System/getProperty "java.io.tmpdir") "/dvergr-escape-" (random-uuid)))
      (sdb/reset-conn!)
      (with-sandbox
        (fn [sci-ctx ec]
          (let [room (binding [rtc/*execution-context* ec]
                       (d/make-room {:id :escape-room :ctx ec :store (mem/make)}))
                created (str "escape-created-" (random-uuid))]
            (try
              (testing "a room is plain data"
                (is (= {:ok {:id :escape-room :slug "escape-room" :title "escape-room" :parent-id nil :fork? false}}
                       (eval-in sci-ctx ec "(dvergr.room/get :escape-room)")))
                (is (= {:ok true}
                       (eval-in sci-ctx ec "(every? #(not-any? % [:participants :meta :ctx :store :bus :fork-handle]) (dvergr.room/list))"))))
              (testing "its atoms are out of reach"
                (eval-in sci-ctx ec "(let [r (dvergr.room/get :escape-room)] (reset! (:participants r) {:intruder 1}))")
                (eval-in sci-ctx ec "(dvergr.room/list :where (fn [r] (when-let [p (:participants r)] (reset! p {:intruder 1})) true))")
                (is (not (contains? @(:participants room) :intruder))))
              (testing "the view still addresses the room"
                (is (= {:ok []} (eval-in sci-ctx ec "(dvergr.room/participants (dvergr.room/get :escape-room))"))))
              (testing "every op that returns rooms returns data"
                (let [r (eval-in sci-ctx ec
                                 (str "(let [f (dvergr.room/fork! :escape-room)
                                        g (dvergr.room/fork! :escape-room)]
                                    {:fork! f
                                     :children (dvergr.room/children :escape-room)
                                     :forks (dvergr.room/forks)
                                     :merge! (dvergr.room/merge! :escape-room f)
                                     :discard! (dvergr.room/discard! g)
                                     :create! (dvergr.room/create! {:slug \"" created "\"})})"))
                      plain? (fn plain? [x]
                               (cond (map? x) (every? (fn [[k v]] (and (plain? k) (plain? v))) x)
                                     (coll? x) (every? plain? x)
                                     :else (or (nil? x) (keyword? x) (string? x) (number? x)
                                               (boolean? x) (uuid? x) (inst? x) (symbol? x))))]
                  (is (map? (:ok r)) (pr-str r))
                  (doseq [[op v] (:ok r)]
                    (is (plain? v) (str op " returned " (pr-str v))))
                  (is (= (:id room) (get-in r [:ok :merge! :id])))
                  (is (get-in r [:ok :create! :room :id]))
                  (testing "root: the :daemon room, else the discourse root"
                    (binding [rtc/*execution-context* ec]
                      (rtc/swap-state! [:dvergr/discourse-root] (constantly room)))
                    (let [r (eval-in sci-ctx ec "(dvergr.room/root)")]
                      (is (= :escape-room (get-in r [:ok :id])) (pr-str r))
                      (is (plain? (:ok r))))
                    (let [daemon (binding [rtc/*execution-context* ec]
                                   (d/make-room {:id :daemon :ctx ec :store (mem/make)}))]
                      (try
                        (let [r (eval-in sci-ctx ec "(dvergr.room/root)")]
                          (is (= :daemon (get-in r [:ok :id])) (pr-str r))
                          (is (plain? (:ok r))))
                        (finally (d/close-room! daemon)))))))
              (finally
                (binding [rtc/*execution-context* ec]
                  (some-> (rreg/lookup (rstore/slug->room-id created)) d/close-room!))
                (d/close-room! room))))))
      (finally
        (paths/set-home! prev)
        (sdb/reset-conn!)))))

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

;; ===========================================================================
;; GROUP 3 — add-libs provenance
;;
;; `org.clojure/*` and `hato/*` coords auto-approve. An approved add-libs used
;; to record the coord's artifact id AND its group's last segment as namespace
;; PREFIXES — before the host load ran. `org.clojure/clojure` therefore opened
;; `^clojure(\..*)?` even though the host load added nothing (the lib is
;; already on the classpath) or failed outright, and `clojure.main/main "-e"`
;; is host eval as the daemon user. Provenance is now recorded after a
;; successful load, per namespace, for the namespaces the new jars newly
;; provide; host eval and raw HTTP namespaces are hard-denied regardless.
;; ===========================================================================

(defn- reaches?
  "Did `code` evaluate to `:reached` (i.e. the escape got through)?"
  [sci ec code]
  (= :reached (:ok (eval-in sci ec code))))

(defn- approve-every-dep!
  "Install an approve-everything deps policy on `ec` — as if a manager
   approved each request. Nothing auto-approves by default, and an unapproved
   request would park waiting for a human."
  [ec]
  (binding [rtc/*execution-context* ec]
    (deps/install-policy! (fn [_ _] :approve))))

(deftest add-libs-cannot-open-host-eval-or-raw-http
  ;; The host add-libs returns nil for libs the basis already has (both of
  ;; these are deps of the daemon); stubbed to that so the test does not depend
  ;; on how the JVM was launched.
  (with-redefs [clojure.repl.deps/add-libs (fn [_] nil)]
    (with-sandbox
      (fn [sci ec]
        (approve-every-dep! ec)
        (testing "an approved coord already on the classpath opens nothing"
          (let [r (eval-in sci ec "(require '[clojure.repl.deps :as deps])
                                 (deps/add-libs '{org.clojure/clojure {:mvn/version \"1.12.5\"}})")]
            (is (= {:status :loaded :provenance []}
                   (select-keys (:ok r) [:status :provenance]))
                (str "the request itself succeeds: " (pr-str r))))
          (is (not (reaches? sci ec "(require 'clojure.main) :reached"))
              "clojure.main/main \"-e\" is host eval")
          (is (not (reaches? sci ec "(require 'clojure.core.server) :reached"))
              "clojure.core.server starts a host socket REPL")
          (is (not (reaches? sci ec "(require 'clojure.instant) :reached"))
              "the group segment `clojure` is not a namespace grant"))
        (testing "hato's coord does not mirror the raw HTTP client"
          (let [r (eval-in sci ec "(clojure.repl.deps/add-libs '{hato/hato {:mvn/version \"1.0.0\"}})")]
            (is (= :loaded (get-in r [:ok :status])) (pr-str r)))
          (is (not (reaches? sci ec "(require 'hato.client) :reached"))
              "hato.client bypasses the SSRF guard and the domain policy"))))))

(deftest a-failed-add-libs-grants-nothing
  (with-redefs [clojure.repl.deps/add-libs
                (fn [_] (throw (ex-info "could not resolve" {})))]
    (with-sandbox
      (fn [sci ec]
        (approve-every-dep! ec)
        (let [r (eval-in sci ec "(try (clojure.repl.deps/add-libs '{org.clojure/clojure {:mvn/version \"9.9.9\"}})
                                      :loaded
                                      (catch Exception _ :failed))")]
          (is (= :failed (:ok r)) "the host failure reaches the agent"))
        (is (not (reaches? sci ec "(require 'clojure.main) :reached"))
            "catching the failure must not leave a grant behind")
        (is (not (reaches? sci ec "(require 'clojure.instant) :reached")))))))

(deftest raw-xml-parser-stays-out-while-the-hardened-one-works
  ;; `clojure.data.xml` in the sandbox is our hardened parser; the real
  ;; library's subnamespaces (reachable through `^clojure.data`) resolve
  ;; external entities.
  (with-sandbox
    (fn [sci ec]
      (is (= "x" (:ok (eval-in sci ec "(require '[clojure.data.xml :as xml])
                                        (first (:content (xml/parse-str \"<a>x</a>\")))"))))
      (is (not (reaches? sci ec "(require 'clojure.data.xml.jvm.parse) :reached")))
      (is (not (reaches? sci ec "(require 'clojure.data.xml.impl) :reached"))))))

(deftest host-vars-and-the-execution-context-stay-out-of-reach
  ;; MECHANISM: spindel's SCI class allowlist left `clojure.lang.Var` and
  ;; `clojure.lang.Namespace` open, so static members on them reached host vars
  ;; (and with them host functions) and the host's thread bindings, which hold
  ;; the live execution context. CLOSED by spindel 0.1.113: both classes are
  ;; registered closed, and SCI code gets an opaque name for the current world
  ;; instead of the ExecutionContext.
  (with-sandbox
    (fn [sci ec]
      (testing "host vars cannot be looked up"
        (is (:err (eval-in sci ec "(clojure.lang.Var/find 'clojure.core/eval)")))
        (is (:err (eval-in sci ec "(clojure.lang.Namespace/find 'clojure.core)"))))
      (testing "the host's thread bindings cannot be read"
        (is (:err (eval-in sci ec "(clojure.lang.Var/getThreadBindings)"))))
      (testing "the current world is a name, not the host's context"
        (let [r (eval-in sci ec "(str (type (org.replikativ.spindel.engine.core/current-execution-context)))")]
          (is (= "class org.replikativ.spindel.sci.macro.CurrentWorld" (:ok r)) (pr-str r)))
        (is (not (instance? org.replikativ.spindel.engine.context.ExecutionContext
                            (:ok (eval-in sci ec "org.replikativ.spindel.engine.core/*execution-context*"))))))
      (testing "spins still run in the bound world"
        (let [r (eval-in sci ec "(require '[org.replikativ.spindel.spin.cps :refer [spin]]) @(spin (+ 1 2))")]
          (is (= 3 (:ok r)) (pr-str r)))))))

(deftest database-handles-stay-with-the-host
  ;; A Datahike database value carries its store, writer and config, and a
  ;; connection holds them too; sandbox code that held either could act on
  ;; the store past the write guards and the effect boundary. It gets handles:
  ;; databases as SandboxDb and connections as WorldConnection, which only the
  ;; mirrored Datahike API takes back.
  (let [cfg {:store {:backend :memory :id (random-uuid)} :keep-history? true :schema-flexibility :read}
        _ (dh/create-database cfg)
        kb (dh/connect cfg)
        other-cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :read}
        _ (dh/create-database other-cfg)
        other (dh/connect other-cfg)
        _ (dh/transact other [{:title "other mailbox"}])
        mailbox (atom kb)
        ec (ctx/create-execution-context)
        sci-ctx (sandbox/fork-for-session ec)]
    (try
      (with-redefs [ygg/system (fn [nm] (when (= "mail" nm) {:conn @mailbox}))
                    skills/load-all (fn [& _] {"probe" {:name "probe" :body "b" :provides [:probe] :storage {:fs :host-fs}}})]
        (sandbox/setup-agent-namespaces! sci-ctx ec :kb-conn kb)
        (let [ev #(eval-in sci-ctx ec (str "(require '[datahike.api :as d]) " %))]
          (testing "a database is a handle"
            (is (= {:ok "class dvergr.sandbox.ns.datahike.SandboxDb"}
                   (ev "(str (type @dvergr.room/*kb*))")))
            (is (= {:ok [false nil nil]}
                   (ev "(let [db @dvergr.room/*kb*] [(map? db) (:store db) (:config db)])"))))
          (testing "the mirrored API takes it back"
            (is (= {:ok #{["note"]}}
                   (ev "(d/transact dvergr.room/*kb* [{:title \"note\"}])
                        (d/q '[:find ?t :where [_ :title ?t]] @dvergr.room/*kb*)")))
            (is (= {:ok #{["note"]}}
                   (ev "(d/q {:query '[:find ?t :where [_ :title ?t]] :args [@dvergr.room/*kb*]})"))))
          (testing "databases come back as handles from every call"
            (is (= {:ok [true true true true true]}
                   (ev "(let [sealed? #(= \"class dvergr.sandbox.ns.datahike.SandboxDb\" (str (type %)))
                              r (d/transact dvergr.room/*kb* [{:title \"b\"}])]
                          [(sealed? (:db-after r))
                           (sealed? (:db-after (d/with @dvergr.room/*kb* [{:title \"c\"}])))
                           (sealed? (d/db dvergr.room/*kb*))
                           (sealed? (d/as-of @dvergr.room/*kb* (java.util.Date.)))
                           (sealed? (:db-after @(d/transact! dvergr.room/*kb* [{:title \"d\"}])))])"))))
          (testing "functions Datahike calls back see handles"
            (is (= {:ok #{[true]}}
                   (ev "(d/q '[:find ?m :in $ ?f :where [_ :title ?t] [(?f ?t) ?m]] @dvergr.room/*kb* string?)")))
            (is (= {:ok false}
                   (ev "(let [seen (atom nil)]
                          (d/q '[:find ?t :where [_ :title ?t]]
                               (d/filter @dvergr.room/*kb* (fn [db _] (reset! seen (map? db)) true)))
                          @seen)"))))
          (testing "a scratch database's connection is a handle"
            (is (= {:ok ["class dvergr.sandbox.ns.datahike.WorldConnection" nil #{["s"]}]}
                   (ev "(d/create-database {:store {:backend :mem :id \"scratch\"} :schema-flexibility :read})
                        (let [c (d/connect {:store {:backend :mem :id \"scratch\"}})]
                          (d/transact c [{:title \"s\"}])
                          [(str (type c)) (:wrapped-atom c)
                           (d/q '[:find ?t :where [_ :title ?t]] @c)])"))))
          (testing "the mailbox is a handle"
            (is (= {:ok ["class dvergr.sandbox.ns.datahike.WorldConnection"
                         "class dvergr.sandbox.ns.datahike.WorldConnection"]}
                   (ev "[(str (type (dvergr.mail/inbox))) (str (type dvergr.mail/*inbox*))]"))))
          (testing "a database is unsealed only where a query declares a source"
            (let [r (ev "(let [c (d/q '[:find ?c . :in ?c :where [(identity ?c) ?x]] dvergr.room/*kb*)]
                           [(str (type c)) (:wrapped-atom c)])")]
              (is (or (:err r) (= ["class dvergr.sandbox.ns.datahike.WorldConnection" nil] (:ok r)))
                  (pr-str r)))
            (doseq [[what code]
                    [["identity on the source"
                      "(d/q '[:find ?db :where [(identity $) ?db]] @dvergr.room/*kb*)"]
                     ["a lookup on the source"
                      "(d/q '[:find ?s . :where [(get $ :store) ?s]] @dvergr.room/*kb*)"]
                     ["a keyword input applied to the source"
                      "(d/q '[:find ?s . :in $ ?f :where [(?f $) ?s]] @dvergr.room/*kb* :store)"]
                     ["a sandbox fn applied to the source"
                      "(d/q '[:find ?m :in $ ?f :where [(?f $) ?m]] @dvergr.room/*kb* identity)"]
                     ["a named source"
                      "(d/q '[:find ?db :in $src :where [(identity $src) ?db]] @dvergr.room/*kb*)"]
                     ["a rule that uses the source"
                      "(d/q '[:find ?s :in $ % :where (leak ?s)] @dvergr.room/*kb* '[[(leak ?s) [(identity $) ?s]]])"]
                     ["the query-map form"
                      "(d/q {:query '[:find ?db :where [(identity $) ?db]] :args [@dvergr.room/*kb*]})"]
                     ["a call in vector form, from a tuple input"
                      "(d/q '[:find ?ok . :in $ [?f] :where [[?f $] ?ok]] @dvergr.room/*kb* [(fn [db] true)])"]
                     ["a vector call inside a rule"
                      "(d/q '[:find ?s :in $ % :where (leak ?s)] @dvergr.room/*kb* '[[(leak ?s) [[identity $] ?s]]])"]
                     ["a lookup's default argument"
                      "(d/q '[:find ?raw . :where [(get-else $ 999999999 :missing $) ?raw]] @dvergr.room/*kb*)"]
                     ["a namespaced name for a lookup"
                      "(d/q '[:find ?ok . :in $ ?f :where [(evil/pull $) ?ok]] @dvergr.room/*kb* identity)"]]]
              (testing what
                (is (re-find #"takes a database only" (str (:err (ev code)))) (pr-str (ev code)))))
            (testing "an input cannot stand in for a lookup's name"
              (is (re-find #"binds a variable"
                           (str (:err (ev "(d/q '[:find ?ok . :in $ pull :where [(pull $) ?ok]] @dvergr.room/*kb* identity)"))))))
            (testing "callables in inputs see sealed values, at any depth and as vars"
              (is (= {:ok #{[false]}}
                     (ev "(d/q '[:find ?m :in $ [?f ...] :where [_ :title ?t] [(?f ?t) ?m]] @dvergr.room/*kb* [map?])")))
              (is (= {:ok #{[true]}}
                     (ev "(defn probe-string [x] (string? x))
                          (d/q '[:find ?m :in $ ?f :where [_ :title ?t] [(?f ?t) ?m]] @dvergr.room/*kb* #'probe-string)")))))
          (testing "Datahike's own lookups still take the source"
            (is (= {:ok #{["note" "none"]}}
                   (ev "(d/q '[:find ?t ?x :where [?e :title \"note\"] [?e :title ?t] [(get-else $ ?e :missing \"none\") ?x]] @dvergr.room/*kb*)"))))
          (testing "transaction functions are refused, in every form"
            (doseq [code ["(d/with @dvergr.room/*kb* [[:db.fn/call (fn [db] [])]])"
                          "(d/with @dvergr.room/*kb* [{:db/ident :escape :db/fn (fn [db] [])} [:escape]])"
                          "(d/transact dvergr.room/*kb* [[:escape]])"
                          "(d/with @dvergr.room/*kb* [[:db/add -1 :db/ident :db.evil/leak] [:db/add -1 :db/fn (fn [raw] [])] [:db.evil/leak]])"
                          "(d/with @dvergr.room/*kb* [[:db/add -1 :db/ident :db.evil/leak] [:db/add -1 :db/fn 'clojure.core/identity] [:db.evil/leak]])"
                          "(d/transact dvergr.room/*kb* {:tx-data [{:title \"m\"}] :tx-meta {:hook (fn [db] db)}})"
                          "(d/transact dvergr.room/*kb* [[:db.fn/call (fn [db] [])]])"
                          "(d/transact dvergr.room/*kb* {:tx-data [[:db.fn/call (fn [db] [])]]})"
                          "(d/transact! dvergr.room/*kb* {:tx-data [(list :db.fn/call (fn [db] []))]})"]]
              (is (re-find #"Transaction functions|functions in it" (str (:err (ev code)))) code))
            (testing "a scratch database's initial transaction is checked before Datahike runs it"
              (is (= {:ok [true false]}
                     (ev "(let [ran (atom false)
                                r (try (d/create-database {:store {:backend :mem :id \"init\"}
                                                           :initial-tx [[:db.fn/call (fn [raw] (reset! ran true) [])]]})
                                       (catch Exception e :refused))]
                            [(= :refused r) @ran])")))
              (is (re-find #"collection of transaction forms"
                           (str (:err (ev "(d/create-database {:store {:backend :mem :id \"path\"} :initial-tx \"/etc/hosts\"})"))))))
            (testing "a scratch database's config cannot override Datahike's writer"
              (is (= {:ok false}
                     (ev "(let [ran (atom false)]
                            (d/create-database {:store {:backend :mem :id \"writer\"} :schema-flexibility :read
                                                :writer {:backend :self :write-fn-map {'transact! (fn [& _] (reset! ran true))}}})
                            (let [c (d/connect {:store {:backend :mem :id \"writer\"}})]
                              (d/transact c [{:title \"w\"}])
                              @ran))")))))
          (testing "an asynchronous failure carries no host object"
            (ev "(d/transact dvergr.room/*kb* [{:db/id 77 :title \"gone\"}])
                 (d/transact dvergr.room/*kb* [[:db/retract 77 :title \"gone\"]])")
            (doseq [code ["(try @(d/transact! dvergr.room/*kb* [[:db.history.purge/before nil]]) :no-error
                           (catch Exception e [(nil? (ex-cause e)) (str (ex-data e))]))"
                          "(try (deref (d/transact! dvergr.room/*kb* [[:db.history.purge/before nil]]) 5000 :timeout) :no-error
                           (catch Exception e [(nil? (ex-cause e)) (str (ex-data e))]))"]]
              (let [r (ev code)]
                ;; the transaction fails (purge before nil), and the error is clean
                (is (and (vector? (:ok r)) (true? (first (:ok r)))
                         (not (re-find #"Connection|datahike\.db\." (second (:ok r)))))
                    (pr-str r)))))
          (testing "an error carries no cause holding what Datahike was given"
            (is (= {:ok [true false]}
                   (ev "(try (d/pull @dvergr.room/*kb* '[*] @dvergr.room/*kb*)
                          (catch Exception e
                            [(nil? (ex-cause e))
                             (boolean (some #(re-find #\"^class datahike\\.(db|connector)\\.\" (str (type %)))
                                            (tree-seq coll? seq (ex-data e))))]))"))))
          (testing "nested results are sealed: an entity's database, tuples, maps"
            (is (= {:ok ["class dvergr.sandbox.ns.datahike.SandboxDb" true]}
                   (ev "(let [e (ffirst (d/q '[:find ?e :where [?e :title \"note\"]] @dvergr.room/*kb*))
                              ent (d/entity @dvergr.room/*kb* e)]
                          [(str (type (d/entity-db ent)))
                           (every? #(= \"class dvergr.sandbox.ns.datahike.SandboxDb\" (str (type %)))
                                   ((juxt :db-before :db-after) (d/with @dvergr.room/*kb* [{:title \"w\"}])))])"))))
          (testing "an entity yields attribute values, never its database"
            (is (= {:ok [false false false]}
                   (ev "(let [e (d/entity @dvergr.room/*kb* (ffirst (d/q '[:find ?e :where [?e :title \"note\"]] @dvergr.room/*kb*)))
                              raw? #(boolean (re-find #\"^class datahike\\.(db|connector)\\.\" (str (type %))))
                              reach (concat [(:db e) (get e :db) (:store e)] (vals e) (keys e) (map val (seq e))
                                            (tree-seq coll? seq (d/pull @dvergr.room/*kb* '[*] (:db/id e))))]
                          [(boolean (some raw? reach)) (boolean (some #(re-find #\"Atom|Connection|Store\" (str (type %))) reach))
                           (boolean (some raw? (map :v (d/datoms @dvergr.room/*kb* :eavt))))])"))))
          (testing "a mailbox handle follows the selected world"
            (is (= {:ok #{["note"]}}
                   (ev "(def kept (dvergr.mail/inbox))
                        (d/q '[:find ?t :where [_ :title ?t] [(= ?t \"note\")]] @kept)")))
            (reset! mailbox other)
            (is (= {:ok #{["other mailbox"]}}
                   (ev "(d/q '[:find ?t :where [_ :title ?t]] @kept)")))
            (reset! mailbox kb))
          (testing "skill definitions are data"
            (is (= {:ok [nil "b"]}
                   (ev "(let [s (get (dvergr.skills/all) \"probe\")] [(:storage s) (:body s)])")))
            (is (= {:ok [nil]}
                   (ev "(mapv :storage (dvergr.skills/find :probe))"))))))
      (finally
        (ctx/stop-context! ec)
        (dh/release kb)
        (dh/release other)))))

(deftest query-inputs-callables-see-handles-at-any-depth
  ;; Defence in depth behind the source rule: whatever Datahike hands a
  ;; callable from an input (tuple, collection, relation, a var), it gets
  ;; as a handle. Invoked here with a raw database, as Datahike would if a
  ;; query found a way to pass one.
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :read}
        _ (dh/create-database cfg)
        conn (dh/connect cfg)
        raw @conn
        query-args @#'dvergr.sandbox.ns.datahike/query-args
        seen (atom [])
        probe (fn [x] (swap! seen conj (str (type x))) true)
        sealed "class dvergr.sandbox.ns.datahike.SandboxDb"]
    (try
      (let [[_ tuple coll rel v] (query-args '[:find ?x :in $ [?a] [?b ...] [[?c]] ?d :where [?x :t ?a]]
                                             [raw [probe] [probe] [[probe]] (intern 'dvergr.sandbox.escape-corpus-test 'query-probe probe)])]
        ((first tuple) raw)
        ((first coll) raw)
        ((ffirst rel) raw)
        (v raw)
        (is (= [sealed sealed sealed sealed] @seen)))
      (finally (dh/release conn)))))
