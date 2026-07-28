(ns dvergr.sandbox.test-output-test
  "clojure.test reporting must reach the AGENT, not the server console.

   `*test-out*` was initialised to the bare `*out*` of whatever thread loaded
   `dvergr.sci.impl.clojure-test` — the host JVM's stdout. Every reporting fn
   goes through `with-test-out-internal`, which binds `*out*` to `@*test-out*`,
   so an agent running tests in the sandbox got a correct summary and NOTHING
   else:

     (run-tests)  ;=> {:test 3 :pass 2 :fail 1}   <- one failed... which one?
     (is (= 5 (+ 2 2)))  ;=> stdout \"\"          <- expected/actual went to
                                                     the server console

   With no way to see WHICH assertion failed or why, agents reasonably concluded
   the runner was broken and went looking for another one (kaocha), which the
   mirror allowlist rightly denies — so they were left with no working path to
   a capability that in fact worked. These tests pin that the detail lands in
   the sandbox's captured stdout, and that both explicit escape hatches
   (`with-out-str`, `binding *test-out*`) still behave."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.sandbox :as sandbox]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.engine.context :as ctx]))

(defn- with-sandbox [f]
  (let [ec      (ctx/create-execution-context)
        sci-ctx (sandbox/fork-for-session ec)]
    (try
      (sandbox/setup-agent-namespaces! sci-ctx ec)
      (f sci-ctx ec)
      (finally (ctx/stop-context! ec)))))

(defn- eval-in
  "Evaluate the way production does — execution context bound — returning the
   whole result map so a test can assert on `:stdout` as well as `:value`."
  [sci-ctx ec code]
  (binding [rtc/*execution-context* ec]
    (sandbox/eval-code sci-ctx code)))

(deftest failing-assertion-detail-reaches-the-sandbox-stdout
  (with-sandbox
    (fn [sci-ctx ec]
      (testing "a failing `is` reports expected/actual into the agent's stdout"
        (let [r (eval-in sci-ctx ec
                         "(do (require '[clojure.test :refer [is]])
                              (is (= 5 (+ 2 2)))
                              :done)")]
          (is (:success r))
          (is (re-find #"FAIL" (:stdout r))
              "the FAIL banner must reach the agent, not the server console")
          (is (re-find #"expected: \(= 5 \(\+ 2 2\)\)" (:stdout r))
              "…including the expected form")
          (is (re-find #"actual: \(not \(= 5 4\)\)" (:stdout r))
              "…and the actual value, which is the whole point"))))))

(deftest run-tests-names-the-failing-test
  (with-sandbox
    (fn [sci-ctx ec]
      (testing "run-tests still returns its summary AND says which test failed"
        (let [r (eval-in sci-ctx ec
                         "(do (require '[clojure.test :refer [deftest is]])
                              (deftest t-pass (is (= 4 (+ 2 2))))
                              (deftest t-fail (is (= 5 (+ 2 2))))
                              (clojure.test/run-tests))")]
          (is (:success r))
          (is (= {:test 2 :pass 1 :fail 1 :error 0 :type :summary} (:value r))
              "the summary map is unchanged")
          (is (re-find #"FAIL in \(t-fail\)" (:stdout r))
              "the failing test is NAMED — without this an agent cannot act")
          (is (not (re-find #"FAIL in \(t-pass\)" (:stdout r)))
              "and the passing one is not reported as failing"))))))

(deftest explicit-capture-still-works
  (with-sandbox
    (fn [sci-ctx ec]
      ;; NB: `clojure.test/is` is fully qualified below on purpose. SCI analyses
      ;; a whole form before evaluating it, so a `(require … :refer [is])` INSIDE
      ;; the same `with-out-str`/`let` body has not run yet when `is` is
      ;; resolved — "Unable to resolve symbol: is" at analysis time. Qualifying
      ;; sidesteps that; clojure.test is always present in the sandbox anyway.
      (testing "with-out-str captures the report instead of the eval stdout"
        (let [r (eval-in sci-ctx ec
                         "(with-out-str (clojure.test/is (= 5 (+ 2 2))))")]
          (is (:success r) (str "eval failed: " (pr-str (:error r))))
          (is (re-find #"actual: \(not \(= 5 4\)\)" (:value r))
              "the agent can capture the report as a value")
          (is (= "" (:stdout r))
              "…and then it must NOT also leak into the eval's stdout")))

      (testing "an explicit *test-out* binding still wins over the default"
        (let [r (eval-in sci-ctx ec
                         "(let [sw (java.io.StringWriter.)]
                            (binding [clojure.test/*test-out* sw]
                              (clojure.test/is (= 5 (+ 2 2))))
                            (str sw))")]
          (is (:success r) (str "eval failed: " (pr-str (:error r))))
          (is (re-find #"actual: \(not \(= 5 4\)\)" (:value r))
              "redirecting *test-out* by hand must still route the report")
          (is (= "" (:stdout r))
              "…and nothing escapes to the default sink"))))))

(deftest passing-tests-stay-quiet
  (with-sandbox
    (fn [sci-ctx ec]
      (testing "no FAIL noise when everything passes"
        (let [r (eval-in sci-ctx ec
                         "(do (require '[clojure.test :refer [deftest is]])
                              (deftest t-ok (is (= 4 (+ 2 2))))
                              (clojure.test/run-tests))")]
          (is (:success r))
          (is (= 0 (:fail (:value r))))
          (is (not (re-find #"FAIL" (:stdout r)))))))))
