(ns dvergr.sandbox.sequence-boundary-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [dvergr.sandbox :as sandbox]
            [dvergr.sandbox.ns.datahike :as database]
            [org.replikativ.spindel.engine.context :as context]
            [org.replikativ.spindel.engine.core :as ec]
            [sci.core :as sci]
            [sci.fork :as fork]))

(defn- with-interpreter [f]
  (let [runtime (context/create-execution-context)
        interpreter (sandbox/fork-for-session runtime)]
    (try
      (binding [ec/*execution-context* runtime] (f runtime interpreter))
      (finally
        (sci/retire! interpreter)
        (context/close-context! runtime)))))

(deftest regex-results-are-immutable-sequences-across-forks
  (with-interpreter
    (fn [_ interpreter]
      (is (= [true nil [["a1" "a" "1"] ["b2" "b" "2"]]]
             (sci/eval-string* interpreter
                               "(def matches (re-seq #\"([a-z])(\\d)\" \"a1 b2\"))
                                [(seq? matches) (re-seq #\"z\" \"a1\") (mapv vec matches)]")))
      (let [child (sci/fork interpreter)]
        (try
          (is (= ["a1" "b2"] (sci/eval-string* child "(mapv first matches)")))
          (is (= ["a1" "b2"] (sci/eval-string* interpreter "(mapv first matches)")))
          (finally (sci/retire! child)))))))

(deftest unknown-host-lazy-producers-still-refuse
  (with-interpreter
    (fn [_ interpreter]
      (sci/add-namespace! interpreter 'foreign {'values (fn [] (map identity (range 3)))})
      (is (thrown-with-msg? Exception #"Foreign lazy or consuming sequence"
                            (sci/eval-string* interpreter "(doall (map inc (foreign/values)))"))))))

(deftest retired-session-imports-do-not-poison-the-next-session
  (dotimes [_ 3]
    (with-interpreter
      (fn [_ interpreter]
        (is (= 42 (sci/eval-string* interpreter "(parse-long \"42\")")))))))

(deftest native-test-bindings-and-child-roots-stay-selected-after-retirement
  (dotimes [_ 2]
    (with-interpreter
      (fn [_ interpreter]
        (is (false? (sci/eval-string* interpreter
                                      "(require '[clojure.test :as t])
                                      (binding [t/*load-tests* false] t/*load-tests*)")))
        (let [child (sci/fork interpreter)]
          (try
            (is (false? (sci/eval-string* child
                                          "(alter-var-root #'clojure.test/*load-tests* (constantly false))")))
            (is (true? (sci/eval-string* interpreter "clojure.test/*load-tests*")))
            (finally (sci/retire! child))))
        (is (= {:test 1 :pass 1 :fail 0 :error 0}
               (select-keys
                (sci/eval-string* interpreter
                                  "(ns lifetime-check (:require [clojure.test :as t]))
                                   (t/deftest healthy (t/is (= 2 (+ 1 1))))
                                   (t/run-tests 'lifetime-check)")
                [:test :pass :fail :error])))))))

(deftest retired-user-vars-remain-retired
  (let [owned (atom nil)]
    (with-interpreter
      (fn [_ interpreter]
        (sci/eval-string* interpreter "(def owned 42)")
        (reset! owned (sci/resolve interpreter 'owned))))
    (is (thrown-with-msg? Exception #"retired" (.getRawRoot ^sci.lang.Var @owned)))))

(deftest generic-sealed-consuming-sequences-do-not-claim-replay
  (let [sealed (database/seal (map identity [1 2 3]))]
    (is (= [1 2 3] (vec sealed)))
    (is (thrown-with-msg? Exception #"no immutable replay source" (fork/fork-value sealed)))))

(deftest delayed-sequence-errors-carry-no-native-cause-or-data
  (let [native (Object.)
        sealed (database/seal (lazy-seq (throw (ex-info "Backend refused" {:raw native}))))
        error (try (first sealed) nil (catch Exception error error))]
    (is (= ::database/sequence-failed (:type (ex-data error))))
    (is (nil? (:raw (ex-data error))))
    (is (nil? (ex-cause error)))))

(deftest delayed-interruption-keeps-the-native-cancellation-signal
  (try
    (let [native-error (InterruptedException. "private backend detail")
          sealed (database/seal (lazy-seq (throw native-error)))
          error (try (first sealed) nil (catch Exception error error))
          interrupted? (.isInterrupted (Thread/currentThread))
          _ (Thread/interrupted)]
      (is (instance? InterruptedException error))
      (is (not (identical? native-error error)))
      (is (= "Sandbox Datahike sequence interrupted" (ex-message error)))
      (is interrupted?)
      (is (nil? (ex-cause error))))
    (finally (Thread/interrupted))))

(deftest datoms-stay-streaming-and-replay-from-the-immutable-snapshot
  (let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write}
        _ (d/create-database cfg)
        connection (d/connect cfg)
        native-datoms d/datoms
        reads (atom 0)]
    (try
      (d/transact connection [{:db/ident :test/n :db/valueType :db.type/long
                               :db/cardinality :db.cardinality/one}])
      (d/transact connection (mapv #(hash-map :test/n %) (range 2000)))
      (with-interpreter
        (fn [runtime interpreter]
          ;; Mount while instrumented: the mirror captures the actual function.
          (with-redefs [d/datoms (fn [& args]
                                   (map (fn [datom] (swap! reads inc) datom)
                                        (apply native-datoms args)))]
            (database/add-datahike-ns! interpreter nil runtime))
          (sci/add-namespace! interpreter 'fixture {'db (sci/new-var 'db (database/seal @connection))})
          (sci/eval-string* interpreter "(require '[datahike.api :as d]) (def rows (d/datoms fixture/db :eavt))")
          (is (zero? @reads) "creating the view does not traverse its source")
          (let [expected (sci/eval-string* interpreter "(mapv :v (take 3 rows))")
                expected-tail (sci/eval-string* interpreter "(def tail (nthnext rows 2)) (mapv :v (take 3 tail))")
                total (count (native-datoms @connection :eavt))
                before-fork @reads
                child (sci/fork interpreter)]
            (try
              (is (< before-fork total) "reading a prefix does not materialize the DB")
              (is (= before-fork @reads) "forking does not traverse the DB")
              (is (= expected (sci/eval-string* child "(mapv :v (take 3 rows))")))
              (let [after-prefix @reads]
                (is (< (- after-prefix before-fork) total))
                (is (= expected-tail (sci/eval-string* child "(mapv :v (take 3 tail))")))
                (is (< (- @reads after-prefix) total)))
              (is (> @reads before-fork) "the child reads an independent replay cursor")
              (is (= expected (sci/eval-string* interpreter "(mapv :v (take 3 rows))")))
              (finally (sci/retire! child))))
          (is (seq (sci/eval-string* interpreter
                                     "(def filtered-rows
                                       (d/datoms (d/filter fixture/db (fn [_ _] true)) :eavt))
                                     (mapv :v (take 3 filtered-rows))")))
          (is (thrown-with-msg? Exception #"no immutable replay source"
                                (let [child (sci/fork interpreter)]
                                  (try (sci/eval-string* child "(vec (take 500 filtered-rows))")
                                       (finally (sci/retire! child))))))))
      (finally (d/release connection) (d/delete-database cfg)))))
