(ns dvergr.sandbox.mirror-policy-test
  "The host-namespace mirror policy is a trust boundary: agents read untrusted
   input (mail, web, chat), so anything reachable from `(require …)` inside the
   sandbox is reachable by prompt injection.

   These tests pin the boundary's POLARITY. The policy used to be a denylist,
   which meant every namespace nobody thought to name was reachable — and
   `ensure-mirrored!` copies every public var of whatever it mirrors, so that
   included the host's credentials and live database connections."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.repl.deps]
            [sci.core :as sci]
            [dvergr.sandbox.deps :as deps]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.engine.core :as rtc]))

(defmacro with-ctx
  "Policy WRITES go through the spindel execution context (so a forked room
   carries its own policy), which needs one bound. Reads deliberately do not —
   they fall back to the built-in defaults, which deny."
  [& body]
  `(binding [rtc/*execution-context* (ctx/create-execution-context)]
     ~@body))

(deftest denies-by-default
  (testing "a namespace nobody enumerated is NOT mirrorable"
    ;; The regression that matters: this must fail closed, not open.
    (is (not (deps/namespace-mirrorable? 'com.example.nobody.thought.of.this)))
    (is (not (deps/namespace-mirrorable? 'some.new.dvergr.subsystem)))))

(deftest denies-credential-bearing-namespaces
  (testing "host config / auth namespaces are unreachable"
    ;; dvergr.substrate.config exposes github-token / telegram-token;
    ;; is.simm.runtimes.auth-config exposes the JWT signing secret as a public var.
    (doseq [ns- '[dvergr.substrate.config
                  is.simm.runtimes.auth-config
                  is.simm.model.access]]
      (is (not (deps/namespace-mirrorable? ns-)) (str ns- " must not be mirrorable")))))

(deftest denies-cross-tenant-data-access
  (testing "system DB and room registries are unreachable"
    ;; Reaching these yields conns to OTHER rooms and to the shared system DB,
    ;; which is a cross-tenant boundary, not just a dvergr-internals boundary.
    (doseq [ns- '[is.simm.model.system-db
                  dvergr.system.db
                  dvergr.system.rooms
                  dvergr.room.registry]]
      (is (not (deps/namespace-mirrorable? ns-)) (str ns- " must not be mirrorable")))))

(deftest denies-governance-and-substrate
  (testing "datahike.tx-preds stays unreachable"
    ;; The accounting governor is a per-store tx-predicate enforced inside
    ;; datahike's writer, which is what makes it hold even for a raw d/transact
    ;; on a conn the agent legitimately owns. Mirroring this namespace exposes
    ;; unregister-tx-pred! — one call and that guarantee is gone.
    (is (not (deps/namespace-mirrorable? 'datahike.tx-preds))))
  (testing "raw datahike/konserve/kabel stay unreachable"
    ;; dvergr.sandbox.ns.datahike injects the data-ops while keeping
    ;; create/connect/delete room-guarded; mirroring the raw API would undo it.
    (doseq [ns- '[datahike.api datahike.connector konserve.core kabel.peer]]
      (is (not (deps/namespace-mirrorable? ns-)) (str ns- " must not be mirrorable"))))
  (testing "sci's own internals stay unreachable"
    (is (not (deps/namespace-mirrorable? 'sci.core))))
  (testing "host filesystem/process access stays unreachable"
    ;; The agent gets muschel's virtual FS; clojure.java.io would bypass it.
    (doseq [ns- '[clojure.java.io clojure.java.shell]]
      (is (not (deps/namespace-mirrorable? ns-)) (str ns- " must not be mirrorable")))))

(deftest allows-the-curated-library-surface
  (testing "pure data/format libraries remain available"
    (doseq [ns- '[cheshire.core clojure.data.xml clojure.zip clojure.test babashka.fs]]
      (is (deps/namespace-mirrorable? ns-) (str ns- " should stay mirrorable")))))

(deftest add-libs-provenance-widens-but-not-past-the-hard-denylist
  (with-ctx
    (testing "an approved add-libs makes the namespaces it loaded requirable"
      (deps/allow-added-lib-namespaces! '[clojure.data.csv])
      (is (deps/namespace-mirrorable? 'clojure.data.csv)))
    (testing "provenance is per namespace, never a prefix of one"
      ;; Recording `my.lib.core` must not open `my.lib.core.impl` or `my.lib`.
      (deps/allow-added-lib-namespaces! '[my.lib.core])
      (is (deps/namespace-mirrorable? 'my.lib.core))
      (is (not (deps/namespace-mirrorable? 'my.lib.core.impl)))
      (is (not (deps/namespace-mirrorable? 'my.lib))))
    (testing "provenance cannot be used to reach the host application"
      (deps/allow-added-lib-namespaces! '[is.simm.model.system-db datahike.tx-preds])
      (is (not (deps/namespace-mirrorable? 'is.simm.model.system-db)))
      (is (not (deps/namespace-mirrorable? 'datahike.tx-preds))))))

(deftest host-eval-and-raw-http-are-hard-denied
  ;; `org.clojure/*` and `hato/*` coords auto-approve, and provenance used to
  ;; open the coord's group segment as a namespace prefix: `org.clojure/x`
  ;; opened `^clojure(\..*)?`, which mirrored `clojure.main/main` — and
  ;; `(clojure.main/main "-e" "...")` is host eval as the daemon user.
  ;; `clojure.core.server` starts a host socket REPL; hato / http-kit are raw
  ;; HTTP clients that bypass the sandbox's SSRF guard and domain policy.
  (with-ctx
    (deps/allow-added-lib-namespaces! '[clojure.main clojure.core.server
                                        hato.client hato.middleware
                                        org.httpkit.client org.httpkit.server])
    (deps/set-namespace-allowlist! [".*"])
    (doseq [ns- '[clojure.main clojure.core.server hato.client hato.middleware
                  org.httpkit.client org.httpkit.server]]
      (is (not (deps/namespace-mirrorable? ns-)) (str ns- " must never be mirrorable")))))

(defn- probe-lib-dir!
  "A directory laid out like a library jar: one namespace of its own, and one
   file that collides with a namespace the host already provides."
  []
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "dvergr-probe-lib" (make-array java.nio.file.attribute.FileAttribute 0)))
        own (java.io.File. dir "dvergr_probe_lib/core.clj")
        shadow (java.io.File. dir "clojure/string.clj")]
    (.mkdirs (.getParentFile own))
    (spit own "(ns dvergr-probe-lib.core)\n(defn answer [] 42)\n")
    (.mkdirs (.getParentFile shadow))
    (spit shadow "(ns clojure.string)\n")
    ;; A `.cljc` beside a namespace the host has as `.clj`/AOT but has not
    ;; loaded: `require` would load the host's file, not this one.
    (spit (java.io.File. dir "clojure/inspector.cljc") "(ns clojure.inspector)\n")
    dir))

(deftest auto-approval-requires-a-maven-source
  ;; The allowlist names libraries; a `:local/root` or `:git/url` spec loads
  ;; whatever code sits there under that name, so it is not auto-approved.
  (with-ctx
    (is (= :approve (deps/allowlist-policy 'org.clojure/data.csv {:spec {:mvn/version "1.1.0"}})))
    (is (= :approve (deps/allowlist-policy 'org.clojure/data.csv {})) "vector form = RELEASE")
    (doseq [spec [{:local/root "/tmp/evil"}
                  {:git/url "https://example.com/evil.git" :git/sha "abc"}
                  {:mvn/version "1.1.0" :local/root "/tmp/evil"}
                  {:mvn/version "1.1.0" :mvn/repos {"evil" {:url "https://example.com"}}}]]
      (is (= :ask-human (deps/allowlist-policy 'org.clojure/data.csv {:spec spec})) (pr-str spec))))
  (testing "add-libs! hands the policy the requested spec"
    (with-ctx
      (let [seen (atom nil)]
        (deps/install-policy! (fn [coord ctx] (reset! seen [coord ctx]) {:deny "probe"}))
        (is (thrown? Exception (deps/add-libs! nil '{org.clojure/probe {:local/root "/tmp/evil"}})))
        (is (= '[org.clojure/probe {:spec {:local/root "/tmp/evil"}}] @seen))))))

(deftest add-libs-records-provenance-only-after-a-successful-load
  (testing "a failed host load records nothing"
    (with-ctx
      (with-redefs [clojure.repl.deps/add-libs
                    (fn [_] (throw (ex-info "resolution failed" {})))]
        (is (thrown? Exception
                     (deps/add-libs! nil '{org.clojure/data.csv {:mvn/version "1.1.0"}}))))
      (is (not (deps/namespace-mirrorable? 'clojure.main)))
      (is (not (deps/namespace-mirrorable? 'clojure.instant)))))
  (testing "a coord already on the classpath adds no jar, so records nothing"
    ;; The host add-libs returns nil for libs the basis already has.
    (with-ctx
      (with-redefs [clojure.repl.deps/add-libs (fn [_] nil)]
        (deps/add-libs! nil '{org.clojure/clojure {:mvn/version "1.12.5"}}))
      (is (not (deps/namespace-mirrorable? 'clojure.main)))
      (is (not (deps/namespace-mirrorable? 'clojure.instant)))))
  (testing "a real new library: exactly the namespaces its jar newly provides"
    (with-ctx
      (deps/install-policy! (fn [_ _] :approve))
      (let [dir (probe-lib-dir!)
            loader (clojure.lang.DynamicClassLoader.
                    (.getContextClassLoader (Thread/currentThread)))]
        (with-bindings {clojure.lang.Compiler/LOADER loader}
          (with-redefs-fn {#'clojure.repl.deps/add-libs
                           (fn [_] (.addURL loader (.toURL (.toURI dir))) '[probe/lib])
                           ;; resolved at run time so the suite compiles before the fix
                           (resolve 'dvergr.sandbox.deps/lib-paths)
                           (fn [lib] (when (= 'probe/lib lib) [(str dir)]))}
            #(let [r (deps/add-libs! nil '{probe/lib {:mvn/version "1.0"}})]
               (is (= :loaded (:status r)))
               (is (deps/namespace-mirrorable? 'dvergr-probe-lib.core)
                   "the library's own namespace is requirable")
               (is (not (deps/namespace-mirrorable? 'dvergr-probe-lib))
                   "but not a prefix of it")
               (is (not (deps/namespace-mirrorable? 'clojure.instant))
                   "a group segment opens nothing")
               (is (contains? (set (:provenance r)) 'dvergr-probe-lib.core))
               (is (not (contains? (set (:provenance r)) 'clojure.string))
                   "a file shadowing a namespace the host already provides is not newly defined")
               (testing "and the agent can require and call it"
                 (let [sci-ctx (sci/init {})]
                   (is (deps/ensure-mirrored! sci-ctx 'dvergr-probe-lib.core))
                   (is (= 42 (sci/eval-string* sci-ctx "(dvergr-probe-lib.core/answer)"))))))))))))

(deftest caller-allowlist-cannot-widen-past-the-hard-denylist
  (with-ctx
    (testing "set-namespace-allowlist! is bounded by the hard denylist"
      (deps/set-namespace-allowlist! [".*"])            ; maximally permissive
      (is (not (deps/namespace-mirrorable? 'is.simm.runtimes.auth-config)))
      (is (not (deps/namespace-mirrorable? 'datahike.tx-preds)))
      (is (not (deps/namespace-mirrorable? 'sci.core)))
      ;; ...and the curated surface still resolves under the wide allowlist
      (is (deps/namespace-mirrorable? 'cheshire.core)))))
