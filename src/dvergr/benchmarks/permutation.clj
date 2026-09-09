(ns dvergr.benchmarks.permutation
  "Offline source-repair fixture over a pinned Spindel namespace.
   The regression is seeded; upstream is not broken. Checks are public and
   deterministic, not held-out or a tamper-resistant training reward service."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.benchmarks.coding-workspace :as workspace]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.ns.io :as sandbox-io]
            [dvergr.substrate.geschichte :as g]
            [hasch.core :as hasch]
            [muschel.fs :as fs]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.engine.core :as ec]))

(def source-path "/src/org/replikativ/spindel/incremental/permutation.cljc")
(def test-path "/test/permutation_repair_test.clj")
(def original-source (slurp (io/resource "benchmarks/permutation/permutation.cljc")))
(def seeded-source
  (str ";; Benchmark copy: binary composition direction deliberately reversed.\n"
       (-> original-source
           (str/replace "qi  (apply-perm q i)" "qi  (apply-perm p i)")
           (str/replace "pqi (apply-perm p qi)" "pqi (apply-perm q qi)"))))

(defn- permutations [xs]
  (if (empty? xs) [[]]
      (for [x xs p (permutations (remove #{x} xs))] (vec (cons x p)))))

(def inputs
  (mapv #(into {} (remove (fn [[a b]] (= a b))) (map-indexed vector %))
        (permutations (range 4))))

(defn- composition [ps]
  (into {} (keep (fn [i]
                   (let [j (reduce (fn [j p] (get p j j)) i (reverse ps))]
                     (when (not= i j) [i j]))))
        (range 4)))

(def expected
  {:binary (mapv #(composition %) (for [p inputs q inputs] [p q]))
   :nullary {}
   :unary inputs
   :ternary (mapv #(composition [% {0 1, 1 0} {1 2, 2 1}]) inputs)
   :arrange [:b :c :a :d]})

(def check-expression
  ;; Do not require the target: that could load the correct host library when
  ;; submitted source is empty. Resolve only definitions loaded from the file.
  (str/replace
   (str
    "(let [ps '" (pr-str inputs) "] "
    "{:binary (vec (for [a ps b ps] (p/compose a b))) "
    ":nullary (p/compose) :unary (mapv p/compose ps) "
    ":ternary (mapv #(p/compose % {0 1, 1 0} {1 2, 2 1}) ps) "
    ":arrange (p/arrange (p/rotation 0 2) [:a :b :c :d])})")
   "p/" "org.replikativ.spindel.incremental.permutation/"))

(def task
  (str "Repair a seeded composition regression in " source-path ". "
       "The documented convention is (compose p q)(i) = p(q(i)). Preserve the "
       "public API, sparse canonical representation, and zero/unary/variadic compose. "
       "Reproduce a non-commuting counterexample, edit the saved SOURCE, and write/run "
       "clojure.test regressions at " test-path ". Require the source with :reload; "
       "load your test file with (load-string (slurp path)). Do not reload clojure.test. "
       "Use clojure_eval. No live web is needed; HTTP is an offline 404 fixture. "
       "A fresh interpreter checks saved source, not REPL definitions or your final reply. "
       "Tests cover all 576 ordered pairs on four positions, identity, unary/variadic "
       "composition, and vector arrangement. Finish with your counterexample and test summary. "
       "This experimental copy does not imply that upstream Spindel is broken."))

(def manifest
  {:fixture/version 1
   :source/repository "https://github.com/replikativ/spindel"
   :source/commit "6b87946a58a7c1bbcbf023f2ff4c657e7a6970a0"
   :source/path "src/org/replikativ/spindel/incremental/permutation.cljc"
   :source/hash (hasch/uuid original-source)
   :candidate/hash (hasch/uuid seeded-source)
   :runtime/profile :dvergr-sci-closed-uri-v1
   ;; This names the required surface, not an automatically detected binary
   ;; lock. Record actual dependency/checkout identity with each launch.
   :checks/version 1 :checks/hash (hasch/uuid [check-expression expected])
   :capture/paths [source-path test-path] :capture/max-file-bytes 32768
   :verification/timeout-ms 3000})

(def basis (hasch/uuid [manifest task]))
(def setup-ref {:setup/id :coding/permutation :setup/version 1 :setup/basis basis})
(def verifier-ref {:verifier/id :coding/permutation :verifier/version 1 :verifier/basis basis})
(def offline-id (hasch/uuid [:coding/offline-http-v1]))

(defn- offline! []
  (sandbox-io/install-http-fixture!
   {:id offline-id :env {}
    :transport (fn [_] {:status 404 :headers {} :body "Offline coding fixture"})}))

(defn definition []
  (environment/make-environment
   {:id :coding/permutation :task task
    :verifier {:id :coding/permutation :version 1 :basis basis}
    :world {:isolation :ctx :settlement :discard :setup setup-ref}
    :limits {:timeout-ms 180000 :cancel-timeout-ms 10000}
    :metadata manifest}))

(defn world-setup []
  (evaluation/make-world-setup
   {:id (:setup/id setup-ref) :version 1 :basis basis
    :prepare
    (fn [{:keys [room]}]
      (binding [ec/*execution-context* (:ctx room)]
        (offline!)
        (let [filesystem (g/filesystem)]
          (when-not filesystem
            (throw (ex-info "Coding fixture needs a registered Geschichte workspace" {})))
          (doseq [[path text] [[source-path seeded-source]
                               [test-path ";; Write regression tests here.\n"]]]
            (when-not (fs/write-string! filesystem path text false)
              (throw (ex-info "Cannot seed coding fixture" {:path path}))))))
      {:fixture/basis basis})}))

(defn check-source
  "Check saved text in a fresh context, never the candidate's interpreter.
   A bounded SCI eval covers source loading and the public reference cases.
   This is a development benchmark, not an adversarial reward boundary."
  [source]
  (if-not (and (string? source) (<= 1 (alength (.getBytes ^String source "UTF-8")) 32768))
    {:source? false}
    (let [runtime (ctx/create-execution-context)]
      (try
        (binding [ec/*execution-context* runtime] (offline!))
        (let [interpreter (sandbox/fork-for-session runtime)
              _ (sandbox/setup-agent-namespaces! interpreter runtime)
              result (sandbox/eval-code
                      interpreter
                      (str "(load-string " (pr-str source) ") " check-expression)
                      :execution-context runtime :timeout-ms 3000)]
          (into {:evaluated? (true? (:success result))}
                (map (fn [[k value]]
                       [k (and (true? (:success result)) (= value (get (:value result) k)))]))
                expected))
        (finally (ctx/close-context! runtime))))))

(defn evaluator []
  (evaluation/make-evaluator
   {:id (:verifier/id verifier-ref) :version 1 :basis basis
    :capture (fn [{:keys [world/room]}]
               (workspace/capture room [source-path test-path] 32768))
    :observe (fn [{:keys [default execution/evidence result]}]
               (assoc default :artifacts evidence :fixture/basis basis
                      :completed? (= :completed (:run/status result))))
    :verify (fn [_ evidence]
              (let [file (get-in evidence [:artifacts :files source-path])
                    checks (assoc (check-source (when (= :ok (:status file)) (:source file)))
                                  :completed? (true? (:completed? evidence)))]
                {:checks checks :reward (if (every? true? (vals checks)) 1.0 0.0)}))}))
