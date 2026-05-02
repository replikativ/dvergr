(ns dvergr.sci.clojure-test
  "Adapter for babashka's clojure.test implementation to work in dvergr's SCI contexts.

   We've vendored babashka's clojure.test (EPL licensed) and adapted it
   to work without babashka's infrastructure."
  (:require [dvergr.sci.impl.clojure-test :as t]
            [sci.core :as sci]))

;; Create the test namespace object
(def tns t/tns)

;; Helper to create SCI vars
(defn new-var [var-sym f]
  (sci/new-var var-sym f {:ns tns}))

;; Base namespace map — everything except the high-level runners that need ctx.
;; Combine with ctx-aware-test-namespace (below) for full functionality.
(def clojure-test-namespace
  "clojure.test namespace map for SCI contexts.

   The high-level runners (run-tests, test-ns, test-all-vars, run-all-tests)
   use dummy stubs here — use ctx-aware-test-namespace to get working versions."
  {:obj tns
   ;; Dynamic vars
   '*load-tests*             t/load-tests
   '*stack-trace-depth*      t/stack-trace-depth
   '*report-counters*        t/report-counters
   '*initial-report-counters* t/initial-report-counters
   '*testing-vars*           t/testing-vars
   '*testing-contexts*       t/testing-contexts
   '*test-out*               t/test-out

   ;; Utilities
   'testing-vars-str    (sci/copy-var t/testing-vars-str tns)
   'testing-contexts-str (sci/copy-var t/testing-contexts-str tns)
   'inc-report-counter  (sci/copy-var t/inc-report-counter tns)
   'report              t/report
   'do-report           (sci/copy-var t/do-report tns)

   ;; Assertion utilities
   'function?           (sci/copy-var t/function? tns)
   'assert-predicate    (sci/copy-var t/assert-predicate tns)
   'assert-any          (sci/copy-var t/assert-any tns)

   ;; Assertion methods
   'assert-expr         (sci/copy-var t/assert-expr tns)
   'try-expr            (sci/copy-var t/try-expr tns)

   ;; Assertion macros
   'is                  (sci/copy-var t/is tns)
   'are                 (sci/copy-var t/are tns)
   'testing             (sci/copy-var t/testing tns)

   ;; Defining tests
   'with-test           (sci/copy-var t/with-test tns)
   'deftest             (sci/copy-var t/deftest tns)
   'deftest-            (sci/copy-var t/deftest- tns)
   'set-test            (sci/copy-var t/set-test tns)

   ;; Fixtures
   'use-fixtures        (sci/copy-var t/use-fixtures tns)
   'compose-fixtures    (sci/copy-var t/compose-fixtures tns)
   'join-fixtures       (sci/copy-var t/join-fixtures tns)

   ;; Low-level runners (don't need ctx)
   'test-var            t/test-var
   'test-vars           (sci/copy-var t/test-vars tns)
   'run-test-var        (sci/copy-var t/run-test-var tns)
   'run-test            (sci/copy-var t/run-test tns)
   'successful?         (sci/copy-var t/successful? tns)
   'with-test-out       (sci/copy-var t/with-test-out tns)})

(defn ctx-aware-test-namespace
  "Build a clojure.test namespace map that closes over the real SCI ctx.

   The high-level runners (run-tests, test-ns, test-all-vars, run-all-tests) need
   the real SCI context to enumerate vars via sci-ns-interns*. Passing a dummy ctx
   causes them to find 0 tests. Call this after (sci/init ...) with the real ctx.

   Usage:
     (sci/merge-opts ctx {:namespaces {'clojure.test (ctx-aware-test-namespace ctx)}})"
  [ctx]
  (merge clojure-test-namespace
         {'test-all-vars (new-var 'test-all-vars
                                  (fn [ns-obj] (t/test-all-vars ctx ns-obj)))
          'test-ns       (new-var 'test-ns
                                  (fn [ns-sym] (t/test-ns ctx ns-sym)))
          'run-tests     (new-var 'run-tests
                                  (fn [& namespaces] (apply t/run-tests ctx namespaces)))
          'run-all-tests (new-var 'run-all-tests
                                  (fn
                                    ([] (t/run-all-tests ctx))
                                    ([re] (t/run-all-tests ctx re))))}))
