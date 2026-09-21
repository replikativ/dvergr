(ns dvergr.sandbox.schema-coverage-test
  "Every fn dvergr injects into an agent-facing sandbox namespace carries a
   malli function schema that compiles, so `(sandbox/doc 'ns)` states what
   each fn takes and returns and agent code can validate against it.

   The ratchet mirrors `doc-coverage-test`: attach schemas as the third
   element of a `dvergr.sandbox.ns.doc/with-docs` entry."
  (:require [dvergr.test-support :as support]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.workspace]
            [malli.core :as m]
            [org.replikativ.spindel.engine.context :as ctx]))

(def ^:private agent-facing-namespaces
  '#{dvergr.room dvergr.agent dvergr.agents dvergr.actors dvergr.skills dvergr.tasks
     dvergr.scheduler dvergr.codec dvergr.mail git env llm sandbox spindel.work})

(defn- injected-fns [sci-ctx]
  (for [[ns-sym vars] (:namespaces @(:env sci-ctx))
        :when (contains? agent-facing-namespaces ns-sym)
        [sym v] vars
        :when (symbol? sym)
        :when (and (ifn? v) (not (map? v)) (not (set? v)) (not (keyword? v)))
        :when (not (:macro (meta v)))]
    [(str ns-sym "/" sym) (:malli/schema (meta v))]))

(deftest every-injected-fn-has-a-malli-schema
  (let [ec (ctx/create-execution-context)
        sci-ctx (sandbox/fork-for-session ec)]
    (try
      (sandbox/setup-agent-namespaces! sci-ctx ec)
      (let [fns (injected-fns sci-ctx)
            missing (sort (keep (fn [[n s]] (when-not s n)) fns))
            broken (sort (keep (fn [[n s]]
                                 (when s
                                   (try (m/function-schema s) nil
                                        (catch Exception e (str n ": " (ex-message e))))))
                               fns))]
        (is (seq fns))
        (testing "every agent-facing fn carries :malli/schema"
          (is (empty? missing) (str "Add a schema (third with-docs element) for:\n  "
                                    (str/join "\n  " missing))))
        (testing "every schema compiles"
          (is (empty? broken) (str/join "\n  " broken))))
      (finally (ctx/stop-context! ec)))))

(deftest sandbox-stdlib-schemas-compile-in-sci
  ;; The stdlib's own bb tests compile its schemas under babashka; this checks
  ;; them where agents run them (SCI, whose `inst?`/`ifn?` malli rejects).
  (let [root (java.io.File. "../dvergr-sandbox")]
    (if-not (.exists (java.io.File. root "dvergr/intake/schema.clj"))
      (support/skip! "sandbox-stdlib-schemas-compile-in-sci: no ../dvergr-sandbox checkout with schemas")
      (let [ec (ctx/create-execution-context)
            sci-ctx (sandbox/fork-for-session ec)
            nss (->> (concat (.listFiles (java.io.File. root "dvergr/intake"))
                             (.listFiles (java.io.File. root "dvergr/mail")))
                     (map #(str (.relativize (.toPath root) (.toPath ^java.io.File %))))
                     (filter #(str/ends-with? % ".clj"))
                     (map #(-> % (str/replace #"\.clj$" "") (str/replace "/" ".") (str/replace "_" "-")))
                     sort)
            code (str "(require " (str/join " " (map #(str "'" %) nss)) ")"
                      "(vec (for [n '" (pr-str (mapv symbol nss)) " [s v] (ns-publics n)"
                      " :when (fn? @v) :let [sch (:malli/schema (meta v))]"
                      " :when (or (nil? sch) (try (malli.core/function-schema sch) false"
                      " (catch Exception _ true)))] (str n \"/\" s)))")]
        (try
          (sandbox/setup-agent-namespaces! sci-ctx ec)
          (binding [dvergr.sandbox.workspace/*workspace-roots* [(.getCanonicalPath root)]]
            (let [r (sandbox/eval-code sci-ctx code :execution-context ec)]
              (is (:success r) (str (get-in r [:error :message])))
              (is (= [] (:value r)) "every public stdlib fn has a schema that compiles in SCI")))
          (finally (ctx/stop-context! ec)))))))
