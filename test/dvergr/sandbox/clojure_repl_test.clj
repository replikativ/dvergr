(ns dvergr.sandbox.clojure-repl-test
  "`clojure.repl` inside the sandbox must answer about the SANDBOX.

   Two things are being pinned here, and they are the same bug seen from both
   sides. The shim used to call clojure.core's `find-ns`/`all-ns`/`ns-publics`/
   `resolve` — the HOST JVM's — so (a) it could not see a single injected
   namespace, and (b) it happily enumerated the daemon's own vars to the agent.
   `doc`/`dir` are the first thing a Clojure-trained model reaches for, so a
   shim that exists and misbehaves sends the agent off doubting the API it is
   exploring instead of its tool."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [dvergr.sandbox.ns.dev :as ns-dev]))

(defn host-only-canary
  "A HOST var carrying a phrase — sandboxfenceword — that exists nowhere in any
   sandbox namespace. If sandbox introspection ever surfaces it, clojure.repl is
   reading the host JVM again."
  []
  :nope)

(defn- ctx-with-kb
  "A sandbox holding one injected namespace, built the way the real ones are:
   bare fns carrying :doc/:arglists as metadata on the VALUE."
  []
  (let [ctx (sci/init {:classes {'Throwable Throwable 'java.lang.Throwable Throwable}})]
    (sci/add-namespace!
     ctx 'kb
     {'attributes (with-meta (fn [_db] :ok)
                    {:doc "List every attribute in the knowledge base." :arglists '([db])})
      'page-add   (with-meta (fn [_t _b] :ok)
                    {:doc "Add a page to the knowledge base." :arglists '([title body])})})
    (ns-dev/add-clojure-repl-ns! ctx)
    ctx))

(defn- ev [ctx code] (sci/eval-string* ctx code))

(deftest doc-works-quoted-and-unquoted
  (let [ctx (ctx-with-kb)]
    (testing "the canonical UNQUOTED form — this used to hand the macro the
              resolved fn value and die casting it to a Symbol"
      (let [out (ev ctx "(clojure.repl/doc kb/attributes)")]
        (is (string? out))
        (is (str/includes? out "kb/attributes"))
        (is (str/includes? out "[db]") "arglists are shown")
        (is (str/includes? out "List every attribute in the knowledge base."))))
    (testing "the quoted form — which used to return an empty string"
      (is (= (ev ctx "(clojure.repl/doc kb/attributes)")
             (ev ctx "(clojure.repl/doc 'kb/attributes)"))))
    (testing "a bare namespace symbol documents the namespace"
      (is (str/includes? (ev ctx "(clojure.repl/doc kb)") "page-add")))))

(deftest dir-lists-sandbox-publics-with-arglists
  (let [ctx (ctx-with-kb)]
    (doseq [form ["(clojure.repl/dir kb)" "(clojure.repl/dir 'kb)"]]
      (testing form
        (let [out (ev ctx form)]
          (is (string? out))
          (is (str/includes? out "attributes"))
          (is (str/includes? out "(page-add title body)") "signature, not just the name"))))
    (testing "dir-fn keeps stdlib's data contract"
      (is (= '[attributes page-add] (ev ctx "(clojure.repl/dir-fn 'kb)"))))
    (testing "a require'd alias resolves like the namespace it names"
      (is (= (ev ctx "(clojure.repl/dir kb)")
             (ev ctx "(do (require '[kb :as k]) (clojure.repl/dir k))"))))))

(deftest introspection-never-reaches-the-host-jvm
  (let [ctx (ctx-with-kb)]
    (testing "apropos searches the SCI namespace map only"
      (is (= '[kb/page-add] (ev ctx "(clojure.repl/apropos \"page\")")))
      (is (empty? (ev ctx "(clojure.repl/apropos \"host-only-canary\")"))
          "a host var must not be enumerable from inside the sandbox"))
    (testing "find-doc matches sandbox docstrings, and only sandbox docstrings"
      (is (str/includes? (ev ctx "(clojure.repl/find-doc \"knowledge base\")") "kb/attributes"))
      (is (not (str/includes? (ev ctx "(clojure.repl/find-doc \"sandboxfenceword\")")
                              "canary"))
          "a host docstring must not be readable from inside the sandbox"))
    (testing "a host namespace is simply not there"
      (is (str/includes? (ev ctx "(clojure.repl/dir 'dvergr.sandbox.ns.dev)")
                         "No such namespace")))))

(deftest misses-are-actionable
  (let [ctx (ctx-with-kb)]
    (testing "a missing namespace names the way out instead of throwing or
              returning an empty string"
      (let [out (ev ctx "(clojure.repl/dir 'nosuchns)")]
        (is (str/includes? out "No such namespace"))
        (is (str/includes? out "(sandbox/overview)"))))
    (testing "a missing var lists what the namespace does have"
      (let [out (ev ctx "(clojure.repl/doc 'kb/nope)")]
        (is (str/includes? out "attributes"))
        (is (not= "" out))))
    (testing "find-doc with no hits says so"
      (is (str/includes? (ev ctx "(clojure.repl/find-doc \"zzznothing\")") "Nothing")))
    (testing "source explains itself rather than erroring on an unknown symbol"
      (is (str/includes? (ev ctx "(clojure.repl/source kb/attributes)") "no source text")))))

(deftest pst-reports-sandbox-frames-not-host-frames
  (let [ctx  (ctx-with-kb)
        _    (ev ctx "(defn boom [] (/ 1 0)) (defn outer [] (boom))")
        ;; The exception has to ESCAPE the eval for SCI to wrap it and attach a
        ;; callstack, so grab one that did and hand it back to pst.
        esc  (try (ev ctx "(outer)") (catch Throwable e e))
        out  ((get-in @(:env ctx) [:namespaces 'clojure.repl 'pst]) esc)
        held (ev ctx "(try (outer) (catch Throwable t (clojure.repl/pst t)))")]
    (testing "pst returns a non-empty string — .printStackTrace wrote to
              System/err, so the old one silently returned \"\" every time"
      (is (string? out))
      (is (seq out))
      (is (str/includes? out "Divide by zero")))
    (testing "the frames are the agent's own code, not the host interpreter's"
      (is (str/includes? out "user/boom"))
      (is (not (str/includes? out "dvergr.sandbox")))
      (is (not (str/includes? out "sci.impl"))))
    (testing "an exception the agent caught itself carries no callstack — pst
              says why instead of reaching for the host trace"
      (is (str/includes? held "Divide by zero"))
      (is (str/includes? held "no sandbox frames"))
      (is (not (str/includes? held "dvergr.sandbox"))))))
