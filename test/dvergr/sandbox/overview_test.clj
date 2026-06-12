(ns dvergr.sandbox.overview-test
  "The sandbox exposes a drift-free overview of its integrated namespaces."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.sandbox :as sandbox]
            [org.replikativ.spindel.engine.context :as ctx]))

(deftest sandbox-overview-reflects-injected-namespaces
  (testing "the overview lists the capability primitives intakes compose over"
    (let [ec      (ctx/create-execution-context)
          sci-ctx (sandbox/fork-for-session ec)]
      (try
        (sandbox/setup-agent-namespaces! sci-ctx ec)
        (let [data  (sandbox/ns-overview-data sci-ctx)
              by-ns (into {} (map (juxt :ns :fns)) data)]
          ;; Intakes now load as cloned-stdlib SOURCE via :load-fn (not pre-mounted),
          ;; so the overview reflects the native capability PRIMITIVES they compose over.
          ;; Borrowed surfaces are mounted under their real babashka/clojure names.
          (is (contains? by-ns "clojure.data.xml") "hardened xml under the real name")
          (is (some #{"parse-str"} (by-ns "clojure.data.xml")) "with the data.xml fns")
          (is (contains? by-ns "cheshire.core") "the real cheshire is mounted")
          (is (contains? by-ns "babashka.fs") "babashka.fs is mounted")
          (is (not (some #(= "clojure.core" (:ns %)) data)) "stock clojure nss hidden")
          (is (string? (sandbox/ns-overview-md sci-ctx))))
        (finally (ctx/stop-context! ec))))))
