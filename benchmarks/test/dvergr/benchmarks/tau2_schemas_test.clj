(ns dvergr.benchmarks.tau2-schemas-test
  "The curated tau2 result types match every result the verified
   transcription produces on the gold trajectories and a seeded fuzz corpus,
   every JSON-returning tool has one, and the REPL candidate sees them."
  (:require [dvergr.test-support :as support]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.benchmarks.tau2.core :as t2]
            [dvergr.benchmarks.tau2.episode :as ep]
            [dvergr.benchmarks.tau2.equivalence :as eqv]
            [dvergr.benchmarks.pyjson :as pj]
            [dvergr.benchmarks.tau2.schemas :as schemas]
            [malli.core :as m]
            [malli.error :as me]))

(def ^:private checkout?
  (.exists (io/file t2/default-root "data/tau2/domains/retail/db.json")))

(defn- results
  "tool -> [[parsed-or-nil text] ...] for every successful call of `corpus`."
  [dom corpus]
  (let [w0 ((:initial-world dom) (first (vals (:tasks dom))))]
    (reduce (fn [acc {:strs [calls]}]
              (first
               (reduce (fn [[acc w] {:strs [name arguments requestor]}]
                         (let [{w' :world :keys [content error]}
                               ((:respond dom) w (keyword (or requestor "assistant")) name arguments)
                               parsed (when-not error (try (pj/parse content) (catch Exception _ nil)))]
                           [(cond-> acc (not error) (update name (fnil conj []) parsed)) w']))
                       [acc w0] calls)))
            {} corpus)))

(deftest retail-results-match-the-curated-types
  (if-not checkout?
    (support/skip! "retail-results-match-the-curated-types: no ../tau2-bench checkout")
    (let [dom (t2/load-domain "retail")
          w0 ((:initial-world dom) (first (vals (:tasks dom))))
          db (if (and (map? w0) (contains? w0 :db)) (:db w0) w0)
          by-tool (results dom (into (eqv/gold-corpus (vals (:tasks dom)))
                                     (eqv/retail-fuzz-corpus db 7 300)))
          opts {:registry (merge (m/default-schemas) (schemas/registry "retail"))}]
      (doseq [[tool rs] by-tool
              :let [json (filter coll? rs)
                    t (schemas/returns "retail" tool)]]
        (testing tool
          (if (seq json)
            (do (is (some? t) "a JSON-returning tool has a result type")
                (when t
                  (let [bad (remove (m/validator t opts) json)]
                    (is (empty? bad)
                        (some-> (first bad) (as-> x (m/explain t x opts)) me/humanize pr-str))
                    (is (pos? (count json))))))
            (is (nil? t) "plain-text tools have none"))))
      (testing "the types are not vacuous"
        (is (not (m/validate :tau2.retail/product
                             {"name" "x" "product_id" "1" "variants" [{"item_id" "1"}]} opts))
            "variants must be an id-keyed map, not a vector")))))

(deftest repl-prompt-and-docs-carry-the-types
  (if-not checkout?
    (support/skip! "repl-prompt-and-docs-carry-the-types: no ../tau2-bench checkout")
    (let [dom (t2/load-domain "retail")
          prompt (fn [g] (ep/agent-system-prompt dom {:agent/metadata {:conversation/action-space :repl
                                                                       :conversation/repl-guidance g}}))]
      (testing "the default names each result type and points at the runtime helpers"
        (is (str/includes? (prompt :shape) "returns (after tau2/parse): :tau2.retail/order"))
        (is (str/includes? (prompt :shape) "(tau2/types)"))
        (is (not (str/includes? (prompt :shape) ":tau2.retail/product [:map [\"name\" :string]"))
            "the full type section is only in :typed (it lowered accuracy in probes)"))
      (testing ":typed adds the full type section"
        (is (str/includes? (prompt :typed) ":tau2.retail/product [:map [\"name\" :string]"))))))
