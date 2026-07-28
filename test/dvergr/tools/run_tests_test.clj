(ns dvergr.tools.run-tests-test
  "`run_tests` must run the agent's OWN tests, in the agent's OWN sandbox.

   It used to `ns-resolve` kaocha.api and run it in the HOST jvm. Three things
   were wrong with that, all measured: kaocha lives only in dvergr's `:test`
   alias, so at runtime the resolve threw \"Could not locate kaocha/api\" and the
   tool ALWAYS failed; `:cwd` was passed as a kaocha config key, which chdirs
   nothing, so the advertised \"runs in your forked worktree in isolation\" was
   untrue; and had it worked it would have loaded and executed host test
   namespaces from the daemon's classpath on agent request — outside the sandbox
   entirely.

   The sandbox already carries a ctx-aware clojure.test whose runners enumerate
   vars in THIS context, so running there is both correct and actually isolated.
   These tests pin that it finds the session's tests, reports pass and fail
   honestly, and surfaces the expected/actual detail an agent needs to act."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [dvergr.sandbox :as sandbox]
            [dvergr.tools :as tools]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.engine.context :as ctx]))

(defn- run-tests-tool []
  (first (filter #(= "run_tests" (:name %)) (vals @@#'tools/registry))))

(defn- with-sandbox [f]
  (let [ec      (ctx/create-execution-context)
        sci-ctx (sandbox/fork-for-session ec)]
    (try
      (sandbox/setup-agent-namespaces! sci-ctx ec)
      (f sci-ctx ec)
      (finally (ctx/stop-context! ec)))))

(defn- define! [sci-ctx ec code]
  (binding [rtc/*execution-context* ec]
    (let [r (sandbox/eval-code sci-ctx code)]
      (when-not (:success r)
        (throw (ex-info (str "setup eval failed: " (get-in r [:error :message])) {})))
      r)))

(defn- invoke [sci-ctx ec args]
  (binding [rtc/*execution-context* ec]
    ((:execute (run-tests-tool)) args {:sci-ctx sci-ctx})))

(deftest runs-the-sessions-own-passing-tests
  (with-sandbox
    (fn [sci-ctx ec]
      (define! sci-ctx ec
        "(require '[clojure.test :refer [deftest is]])
         (deftest arithmetic-holds (is (= 4 (+ 2 2))))")
      (let [r (invoke sci-ctx ec {})]
        (is (= :success (:type r)))
        (is (pos? (:passed (:metadata r)))
            "must actually FIND the test defined in this session")
        (is (zero? (:failed (:metadata r))))
        (is (str/includes? (str (:content r)) "All tests passed"))))))

(deftest a-failure-is-reported-with-its-detail
  (with-sandbox
    (fn [sci-ctx ec]
      (define! sci-ctx ec
        "(require '[clojure.test :refer [deftest is]])
         (deftest arithmetic-is-broken (is (= 5 (+ 2 2))))")
      (let [r (invoke sci-ctx ec {})]
        (is (= :error (:type r)) "a failing suite must not report success")
        (is (= 1 (:failed (:metadata r))))
        (is (= 1 (:exit-code (:metadata r))))
        (testing "and the agent is told WHICH assertion failed and why"
          (is (str/includes? (str (:content r)) "arithmetic-is-broken"))
          (is (str/includes? (str (:content r)) "expected"))
          (is (str/includes? (str (:content r)) "actual")))))))

(deftest no-sandbox-context-is-a-clean-error
  (testing "rather than a confusing failure deep inside a runner"
    (let [r ((:execute (run-tests-tool)) {} {})]
      (is (= :error (:type r)))
      (is (str/includes? (str (:error r)) "No sandbox context")))))
