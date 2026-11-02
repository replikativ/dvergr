(ns dvergr.catalog.checker-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.catalog.checker :as checker]
            [dvergr.catalog.room :as catalog]
            [dvergr.sandbox :as sandbox]
            [org.replikativ.spindel.engine.impl.simple :as simple]
            [sci.core :as sci])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(def answer "(ns c) (defn check [_] {:checks {:ok? true} :reward 1})")

(defn- exception [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

(deftest checks-run-on-the-calling-thread-without-dropping-bounded-extensions
  (let [current (Thread/currentThread)
        seen (atom nil)
        create sandbox/create-base-ctx]
    (with-redefs [sandbox/create-base-ctx (fn [& opts]
                                            (reset! seen (Thread/currentThread))
                                            (apply create opts))]
      (is (= {:checks {:ok? true} :reward 1.0 :metrics {:score 0.7} :note "explanation"}
             (checker/run-checker
              "(ns c) (defn check [_] {:checks {:ok? true} :reward 1 :metrics {:score 0.7} :note \"explanation\"})" {})))
      (is (identical? current @seen))))
  (is (= {:checks {} :reward 0.0}
         (checker/run-checker "(ns c) (defn check [_] {:checks {} :reward 0})" {})))
  (is (thrown? Exception (checker/run-checker "(ns c) (defn check [_] {:checks {} :reward 0})" {} {:min-checks 1}))))

(deftest missing-functions-retain-their-distinct-contracts
  (is (= :missing-check (:reason (ex-data (exception #(checker/run-checker "(ns c)" {}))))))
  (is (nil? (checker/judge-requests "(ns c)" {} 1)))
  (is (= [] (checker/judge-requests "(ns c) (defn judge-requests [_] nil)" {} 1)))
  (is (= :dvergr.catalog.room/no-check
         (:type (ex-data (exception #(catalog/run-checker "(ns c)" {})))))))

(deftest malformed-and-lazy-verdicts-are-refused-without-realization
  (doseq [value ["nil" "[]" "{:checks (repeat true) :reward 1}"
                 "{:checks {:ok? 1} :reward 1}"
                 "{:checks {:ok? true} :reward ##NaN}"
                 "{:checks {:ok? true} :reward ##Inf}"
                 "{:checks {:ok? true} :reward -1}"
                 "{:checks {:ok? true} :reward 2}"
                 "{:checks {:ok? true} :reward 1 :extra (repeat 1)}"
                 "{:checks {:ok? true} :reward 1 :extra (fn [] nil)}"]]
    (is (thrown? Exception (checker/run-checker (str "(ns c) (defn check [_] " value ")") {})) value))
  (is (thrown? Exception (checker/run-checker answer {} {:max-check-key-length 1})))
  (is (thrown? Exception (checker/run-checker answer {} {:max-output-nodes 2})))
  (is (thrown? Exception (checker/run-checker answer {} {:max-output-bytes 1})))
  (is (thrown? Exception (checker/run-checker answer {} {:max-depth 1}))))

(deftest encoded-output-budget-includes-numbers-empty-values-and-escaping
  (doseq [extra ["(bigdec (str \"0.\" (apply str (repeat 1000 \"1\"))))"
                 "(vec (repeat 100 nil))"
                 "(apply str (repeat 30 \"\\n\"))"]]
    (is (thrown? Exception
                 (checker/run-checker
                  (str "(ns c) (defn check [_] {:checks {:ok? true} :reward 1 :extra " extra "})")
                  {} {:max-output-bytes 128}))))
  (is (thrown? Exception
               (checker/run-checker
                "(ns c) (defn check [_] {:checks {:ok? true} :reward 1 :extra (bigdec (str \"0.\" (apply str (repeat 1000 \"1\"))))})"
                {} {:max-numeric-digits 32})))
  (is (thrown? Exception
               (checker/run-checker "(ns c) (defn check [_] {:checks {:ok? true} :reward 1 :extra 1E-100001M})" {}))))

(deftest normalized-reward-is-charged-at-its-output-size
  (is (thrown? Exception
               (checker/run-checker "(ns c) (defn check [_] {:checks {} :reward 1/3})" {}
                                    {:max-output-bytes 30}))))

(deftest hidden-metadata-cannot-retain-unbounded-values-or-interpreters
  (let [value (checker/run-checker
               "(ns c) (defn check [_] (with-meta {:checks {:ok? true} :reward 1 :extra [(with-meta ['x] {:closure (fn [] nil)})]} {:hidden (repeat 1)}))" {})]
    (is (nil? (meta value)))
    (is (nil? (meta (get-in value [:extra 0]))))
    (is (= [['x]] (:extra value)))))

(deftest source-cap-is-utf8-bytes-before-context-creation
  (let [created (atom 0)]
    (with-redefs [sandbox/create-base-ctx (fn [& _] (swap! created inc))]
      (is (= :source (:reason (ex-data (exception #(checker/run-checker "ééé" {} {:max-source-bytes 4}))))))
      (is (zero? @created)))))

(deftest deadline-covers-source-evaluation-and-remains-sticky
  (doseq [source ["(ns c) (loop [] (recur))"
                  "(ns c) (defn check [_] (loop [] (recur)))"
                  "(ns c) (defn check [_] (try (loop [] (recur)) (catch Exception _ {:checks {:ok? true} :reward 1})))"]]
    (is (thrown? Exception (checker/run-checker source {} {:timeout-ms 20}))))
  (is (thrown? Exception (checker/run-checker answer {} {:deadline-nanos (dec (System/nanoTime))}))))

(deftest shared-calibration-guard-keeps-the-callers-allocation-extent
  (let [scopes (atom 0) checks (atom 0)
        guard #(swap! checks inc)]
    (with-redefs [sandbox/call-with-allocation-scope (fn [_] (swap! scopes inc))]
      (dotimes [_ 2] (is (= 1.0 (:reward (checker/run-checker answer {} {:allocation-check guard})))))
      (is (zero? @scopes))
      (is (> @checks 2))))
  (let [allocated? (atom false)
        create sandbox/create-base-ctx]
    (with-redefs [sandbox/create-base-ctx
                  (fn [& opts]
                    (let [ctx (apply create opts)]
                      (sci/add-namespace! ctx 'host {'allocate! #(reset! allocated? true)})
                      ctx))]
      (is (thrown? Exception
                   (checker/run-checker
                    "(ns c) (defn check [_] (try (host/allocate!) (loop [] (recur)) (catch Exception _ {:checks {:ok? true} :reward 1})))" {}
                    {:allocation-check #(when @allocated? (throw (ex-info "shared allocation exhausted" {})))}))))))

(deftest cancellation-cannot-return-before-a-host-operation-exits
  (let [entered (CountDownLatch. 1) interrupted (CountDownLatch. 1)
        release (CountDownLatch. 1) exited (CountDownLatch. 1)
        result (promise) worker-thread (atom nil)
        create sandbox/create-base-ctx]
    (with-redefs [sandbox/create-base-ctx
                  (fn [& opts]
                    (let [ctx (apply create opts)]
                      (sci/add-namespace!
                       ctx 'host
                       {'wait! (fn []
                                 (reset! worker-thread (Thread/currentThread))
                                 (.countDown entered)
                                 ;; A deliberately noncooperative host operation:
                                 ;; cancellation is observed but cannot release it.
                                 (loop []
                                   (when-not (try (.await release) true
                                                  (catch InterruptedException _
                                                    (.countDown interrupted) false))
                                     (recur)))
                                 (.interrupt (Thread/currentThread))
                                 true)})
                      ctx))]
      (let [worker (Thread.
                    (fn []
                      (try
                        (deliver result (checker/run-checker
                                         "(ns c) (defn check [_] (host/wait!) {:checks {:ok? true} :reward 1})" {}))
                        (catch Throwable e (deliver result e))
                        (finally (.countDown exited)))))]
        (.start worker)
        (try
          (is (.await entered 5 TimeUnit/SECONDS))
          (is (identical? worker @worker-thread) "checker does not move work to a detached future")
          (.interrupt worker)
          (is (.await interrupted 5 TimeUnit/SECONDS))
          (is (not (realized? result)) "cancellation is not physical return")
          (is (= 1 (.getCount exited)))
          (finally (.countDown release) (.join worker 5000)))
        (is (zero? (.getCount exited)))
        (is (instance? Throwable (deref result 1000 ::missing)))))))

(deftest checker-entry-refuses-the-engine-drain
  (with-redefs [simple/on-drain-thread? (constantly true)]
    (is (= :engine-drain (:reason (ex-data (exception #(checker/run-checker answer {}))))))))

(deftest judge-requests-are-bounded-before-truncation-or-formatting
  (is (= [{:id "a" :prompt "A?"}]
         (checker/judge-requests
          "(ns c) (defn judge-requests [_] [{:id \"a\" :prompt \"A?\"} {:id \"b\" :prompt \"B?\"}])" {} 1)))
  (doseq [value ["(repeat {:id \"a\" :prompt \"A?\"})"
                 "[{:id \"a\" :prompt \"A?\"} {:id \"a\" :prompt \"B?\"}]"
                 "[{:id \"a\" :prompt \"A?\"} {:id \"b\" :prompt 42}]"
                 "[{:id \"a\" :prompt \"A?\" :extra (repeat 1)}]"]]
    (is (thrown? Exception (checker/judge-requests (str "(ns c) (defn judge-requests [_] " value ")") {} 1))))
  (is (thrown? Exception
               (checker/judge-requests "(ns c) (defn judge-requests [_] [{:id \"a\" :prompt \"éé\"}])" {} 1
                                       {:max-judge-prompt-bytes 3})))
  (is (thrown? Exception
               (checker/judge-requests "(ns c) (defn judge-requests [_] [{:id \"a\" :prompt \"A?\"} {:id \"b\" :prompt \"B?\"}])" {} 1
                                       {:max-judge-requests 1}))))

(deftest promotion-and-verifier-identity-include-the-execution-policy
  (let [bundle {:name "x" :id (random-uuid) :definition {} :checker answer}]
    (is (= checker/policy-id (get-in (catalog/evaluator bundle {}) [:ref :verifier/basis :checker-policy])))
    (with-redefs-fn {#'catalog/promotions (constantly {(str (:id bundle)) {:at (java.util.Date.)}})}
      #(is (false? (catalog/promoted? (:id bundle)))))
    (with-redefs-fn {#'catalog/promotions (constantly {(str (:id bundle)) {:checker-policy checker/policy-id}})}
      #(is (true? (catalog/promoted? (:id bundle)))))))
