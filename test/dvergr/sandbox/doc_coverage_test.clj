(ns dvergr.sandbox.doc-coverage-test
  "Every fn dvergr injects into the sandbox must document itself.

   An agent discovers its vocabulary through `(clojure.repl/doc …)`, `dir`,
   `apropos`, `find-doc` and `(sandbox/doc 'ns)`, all of which read metadata off
   the injected value. Borrowed namespaces arrive via `sci/copy-var` and keep
   their metadata; dvergr's OWN vocabulary is injected as bare closures, which
   carry none — so those tools used to answer \"arity unknown — injected as a
   bare fn / no docstring\" for ~75 vars, and `(find-doc \"knowledge\")` found
   nothing despite KB search being a core capability.

   This test is the ratchet. It fails when a NEW undocumented fn is added to an
   agent-facing namespace, naming the offenders, so the vocabulary cannot
   silently rot back into being undiscoverable. Attach docs with
   `dvergr.sandbox.ns.doc/with-docs` (or `from-var` to harvest them from the
   wrapped host var).

   Scope note: only fns are required to document themselves. Injected VALUES
   (`*kb*`, `*room*` — datahike conns, possibly nil in a room-less ctx) have no
   signature to state and are described in the `ns-guide` overview instead."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.sandbox :as sandbox]
            [org.replikativ.spindel.engine.context :as ctx]))

(def ^:private agent-facing-namespaces
  "dvergr's own vocabulary — the namespaces an agent is pointed at that we
   inject ourselves (so metadata is our job, not `sci/copy-var`'s)."
  '#{dvergr.room dvergr.agents dvergr.actors dvergr.skills dvergr.tasks
     dvergr.scheduler dvergr.codec dvergr.mail git env llm sandbox})

(defn- undocumented-fns
  "Seq of \"ns/sym\" for every injected FN missing :doc or :arglists."
  [sci-ctx]
  (let [nss (:namespaces @(:env sci-ctx))]
    (for [[ns-sym vars] nss
          :when (contains? agent-facing-namespaces ns-sym)
          [sym v] vars
          :when (symbol? sym)
          :when (ifn? v)                       ; values (conns) are exempt — see ns doc
          :let  [m (meta v)]
          :when (or (nil? (:doc m)) (nil? (:arglists m)))]
      (str ns-sym "/" sym))))

(deftest every-injected-fn-documents-itself
  (let [ec      (ctx/create-execution-context)
        sci-ctx (sandbox/fork-for-session ec)]
    (try
      (sandbox/setup-agent-namespaces! sci-ctx ec)
      (testing "no agent-facing fn is left without :doc + :arglists"
        (let [missing (sort (undocumented-fns sci-ctx))]
          (is (empty? missing)
              (str "These injected fns carry no :doc/:arglists, so "
                   "(clojure.repl/doc …) and (find-doc …) answer nothing for "
                   "them. Document them with dvergr.sandbox.ns.doc/with-docs:\n  "
                   (str/join "\n  " missing)))))
      (finally (ctx/stop-context! ec)))))

(deftest documentation-is-actually-reachable-from-inside
  (let [ec      (ctx/create-execution-context)
        sci-ctx (sandbox/fork-for-session ec)]
    (try
      (sandbox/setup-agent-namespaces! sci-ctx ec)
      (testing "the metadata reaches the tools an agent actually uses"
        ;; Guards the WIRING, not just the data: metadata is useless if the
        ;; discovery path can't see it. dvergr.scheduler/create is documented.
        (let [doc-out (sandbox/eval-code
                       sci-ctx "(clojure.repl/doc dvergr.scheduler/create)")]
          (is (:success doc-out))
          (is (not (re-find #"arity unknown" (str (:value doc-out))))
              "a documented fn must not render as the hollow fallback")
          (is (not (re-find #"no docstring" (str (:value doc-out))))
              "…nor claim it has no docstring")))
      (finally (ctx/stop-context! ec)))))
