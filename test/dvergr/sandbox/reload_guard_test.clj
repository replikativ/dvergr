(ns dvergr.sandbox.reload-guard-test
  "`:reload` must not replace a sandbox namespace with the host's.

   SCI short-circuits `:load-fn` for a namespace already in its map — \"unless
   `:reload` or `:reload-all` are used\" (SCI README) — and passes `reload` in so
   the host can act on it. dvergr's load-fn did not read that key, and because
   `sci/add-namespace!` MERGES, one line replaced a curated shim with the real
   host namespace:

     (babashka.fs/exists? \"/etc/passwd\")  ;=> Access denied   <- clamped
     (require 'babashka.fs :reload)        ;      90 publics mirrored
     (babashka.fs/exists? \"/etc/passwd\")  ;=> true            <- clamp gone

   and with `babashka.process` the same line yielded a host shell as the daemon
   user. These tests pin both halves: the shim survives a `:reload`, and
   namespaces the sandbox does NOT provide still mirror normally — the allowlist
   was never the hole."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.sandbox :as sandbox]
            [sci.core :as sci]
            [org.replikativ.spindel.engine.context :as ctx]))

(defn- eval-in [sci-ctx code]
  (try {:ok (sci/eval-string* sci-ctx code)}
       (catch Throwable e {:err (ex-message e)})))

(deftest reload-cannot-replace-a-sandbox-namespace-with-the-host
  (let [ec      (ctx/create-execution-context)
        sci-ctx (sandbox/fork-for-session ec)]
    (try
      (sandbox/setup-agent-namespaces! sci-ctx ec)

      (testing "the filesystem clamp is in force to begin with"
        (let [r (eval-in sci-ctx "(babashka.fs/exists? \"/etc/passwd\")")]
          (is (:err r) "a sensitive host path must be refused")))

      (testing ":reload of a namespace the sandbox provides is REFUSED"
        (let [r (eval-in sci-ctx "(require 'babashka.fs :reload)")]
          (is (:err r) "must not silently mirror the host namespace")
          (is (re-find #"Refusing to reload" (str (:err r)))
              "and must say why, since the namespace plainly exists")))

      (testing "so the clamp still holds afterwards — this is the regression"
        (let [r (eval-in sci-ctx "(babashka.fs/exists? \"/etc/passwd\")")]
          (is (:err r) "clamp must survive the reload attempt")))

      (testing "process execution is not reachable the same way"
        (is (:err (eval-in sci-ctx "(require 'babashka.process :reload)")))
        (let [r (eval-in sci-ctx "(babashka.process/sh \"id\")")]
          (is (:err r) "no host shell as the daemon user")))

      (finally (ctx/stop-context! ec)))))

(deftest mirroring-still-works-for-namespaces-the-sandbox-does-not-provide
  ;; The guard is "already provided", not "reload is bad" — an agent must still
  ;; be able to pull in a namespace the sandbox has no opinion about, including
  ;; with :reload. Without this, the fix would break add-libs'd dependencies.
  (let [ec      (ctx/create-execution-context)
        sci-ctx (sandbox/fork-for-session ec)]
    (try
      (sandbox/setup-agent-namespaces! sci-ctx ec)
      (testing "a plain require of an unprovided host namespace still mirrors"
        (is (nil? (:err (eval-in sci-ctx "(require '[clojure.set :as s]) (s/union #{1} #{2})")))))
      (finally (ctx/stop-context! ec)))))
