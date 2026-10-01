(ns dvergr.benchmarks.bird-provider-test
  "BIRD on the generic evaluation path, with scripted candidates, on every
   engine. Skipped without the BIRD dev set (see `dvergr.benchmarks.bird.core`)."
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.benchmarks.bird.core :as bird]
            [dvergr.benchmarks.bird.provider :as provider]
            [dvergr.discourse :as d]
            [dvergr.room.store.memory :as memory]
            [dvergr.test-support :as support]
            [org.replikativ.spindel.engine.core :as ec]))

(def ^:private answers
  ;; question 747: "What is the total number of superheroes without full name?"
  {:sqlite "SELECT COUNT(id) FROM superhero WHERE full_name IS NULL"
   :pg-datahike "SELECT COUNT(id) FROM superhero WHERE full_name IS NULL"
   :datalog "(count (q '[:find ?e :where [?e :superhero/db-row-exists true] (not [?e :superhero/full_name _])]))"
   ;; a plain Datalog query, no Clojure around it
   :datalog-plain "[:find (count ?e) :where [?e :superhero/db-row-exists true] (not [?e :superhero/full_name _])]"})

(defn- scripted [steps seen]
  (fn [_question]
    (let [left (atom steps)]
      (fn [request]
        (swap! seen conj request)
        (let [[tool q] (first @left)]
          (swap! left rest)
          (if tool
            {:content "" :tool-calls [{:id (str "c" (count @seen)) :name tool :arguments {:query q}}]}
            {:content "I give up." :tool-calls []}))))))

(defn- evaluate! [room engine steps seen]
  (let [q (some #(when (= 747 (:question-id %)) %) (bird/questions))
        caps (provider/capabilities [q] {:agent-generate (scripted steps seen)})
        env (provider/environment-def q caps {:timeout-ms 120000})
        team (provider/candidate-roster [{:id engine :model "claude-code-sonnet" :engine engine}])]
    (binding [ec/*execution-context* (:ctx room)]
      (:attempt-receipt
       @(evaluation/evaluate room team engine env (:evaluator caps) {:protocol (:protocol caps)})))))

(deftest every-engine-answers-and-is-graded-by-its-rows
  (if-not (bird/available?)
    (support/skip! "every-engine-answers-and-is-graded-by-its-rows: no BIRD dev set")
    (let [room (d/make-room {:id :bird/provider-test :store (memory/make)})]
      (try
        (doseq [engine [:sqlite :pg-datahike :datalog]]
          (testing (name engine)
            (let [seen (atom [])
                  receipt (evaluate! room engine [["query" (answers engine)] ["submit" (answers engine)]] seen)]
              (is (= 1.0 (:attempt/reward receipt)) (pr-str (:attempt/checks receipt)))
              (is (= {:submitted true :runs true :correct true} (:attempt/checks receipt)))
              (testing "the query tool showed the rows"
                (is (re-find #"1 row" (:content (last (:messages (second @seen)))))))
              (testing "the schema is the engine's"
                (is (re-find (case engine :datalog #":superhero/full_name" :pg-datahike #"PostgreSQL" :sqlite #"CREATE TABLE")
                             (:content (first (:messages (first @seen))))))))))
        (testing "a plain Datalog query runs as is"
          (is (= {:submitted true :runs true :correct true}
                 (:attempt/checks (evaluate! room :datalog [["submit" (answers :datalog-plain)]] (atom []))))))
        (testing "a wrong answer scores zero; no submission is not a pass"
          (is (= {:submitted true :runs true :correct false}
                 (:attempt/checks (evaluate! room :sqlite [["submit" "SELECT COUNT(id) FROM superhero"]] (atom [])))))
          (is (false? (get-in (evaluate! room :datalog [] (atom [])) [:attempt/checks :submitted]))))
        (finally (d/close-room! room))))))

(deftest a-final-reply-that-is-a-query-is-its-submission
  (is (= "SELECT 1" (provider/reply-query :sqlite "```sql\nSELECT 1\n```")))
  (is (= "[:find ?x :where [?e :t/a ?x]]" (provider/reply-query :datalog "[:find ?x :where [?e :t/a ?x]]")))
  (is (nil? (provider/reply-query :sqlite "The answer is 5.")) "prose is not a submission")
  (is (nil? (provider/reply-query :datalog "SELECT 1")) "another engine's language is not either"))

(deftest an-exact-ratio-grades-as-its-nearest-double
  ;; Clojure's (double 519/203) is 2.556650246305419; SQLite's 519.0 / 203
  (is (= 2.5566502463054186 (bird/ratio->double 519/203)))
  (is (= 164.82142857142858 (bird/ratio->double (/ 4615 28))))
  (is (bird/same-result? [[519/203]] [[2.5566502463054186]]))
  (is (= 3.3333333333333335E21 (bird/ratio->double (/ (bigint 10000000000000000000001) 3)))
      "beyond 2^53"))

(deftest a-vector-that-is-not-a-query-is-an-expression
  (if-not (bird/available?)
    (support/skip! "a-vector-that-is-not-a-query-is-an-expression: no BIRD dev set")
    (do
      (is (= [[1]] (:rows (provider/run-query :datalog "superhero" "[(let [x 1] [x])]" {}))))
      (is (= [[2.5566502463054186]]
             (:rows (provider/run-query :datalog "superhero" "[[(double 519/203)]]" {})))
          "the sandbox's double is the nearest one"))))

(deftest aggregates-count-every-row-of-the-join
  (is (= '[:find (sum ?s) :with ?e :where [?e :budget/category "Food"] [?e :budget/spent ?s]]
         (provider/with-rows '[:find (sum ?s) :where [?e :budget/category "Food"] [?e :budget/spent ?s]]))
      "equal amounts are not one value")
  (is (= '[:find ?el (count ?a) :with ?m :where [?a :atom/molecule_id ?mid] [?a :atom/element ?el] [?m :molecule/molecule_id ?mid]]
         (provider/with-rows '[:find ?el (count ?a) :where [?a :atom/molecule_id ?mid] [?a :atom/element ?el] [?m :molecule/molecule_id ?mid]])))
  (is (= '{:find [(avg ?h)] :where [[?s :t/h ?h]] :with [?s]} (provider/with-rows '{:find [(avg ?h)] :where [[?s :t/h ?h]]})))
  (testing "left as they are"
    (doseq [q '[[:find (count ?e) :where [?e :t/a ?x]]
                [:find ?x :where [?e :t/a ?x]]
                [:find (sum ?s) :with ?e :where [?e :t/s ?s]]
                [:find (count-distinct ?x) :where [?e :t/a ?x]]]]
      (is (= q (provider/with-rows q))))))

(deftest top-k-by-a-column-the-answer-does-not-show
  (if-not (bird/available?)
    (support/skip! "top-k-by-a-column-the-answer-does-not-show: no BIRD dev set")
    (let [tallest (fn [q] (:rows (provider/run-query :datalog "superhero" q {})))]
      (is (= [["Surtur"]] (tallest "{:find [?n] :where [[?e :superhero/superhero_name ?n] [?e :superhero/height_cm ?h]] :order-by [?h :desc ?n :asc] :limit 1}"))
          "ordered by a variable :find does not return; a map query is a plain query")
      (is (= [["Surtur"]] (tallest "[:find ?n :where [?e :superhero/superhero_name ?n] [?e :superhero/height_cm ?h] :order-by ?h :desc ?n :asc :limit 1]"))
          "the vector form with :order-by")
      (is (= [["Surtur"]] (tallest "(q '{:find [?n] :where [[?e :superhero/superhero_name ?n] [?e :superhero/height_cm ?h]] :order-by [?h :desc ?n :asc] :limit 1})"))
          "through q in an expression")
      (is (= 1 (count (tallest "{:find [?a (count ?e)] :where [[?e :superhero/alignment_id ?a]] :order-by [1 :desc] :limit 1}")))
          "ordered by an aggregate's column"))))

(deftest a-map-written-as-vector-clauses-is-that-vector
  (is (= '[:find ?n :where [?e :t/n ?n] :order-by ?n :desc :limit 1]
         (provider/read-query "{:find ?n :where [?e :t/n ?n] :order-by ?n :desc :limit 1}")))
  (is (= '{:find [?n] :where [[?e :t/n ?n]]} (provider/read-query "{:find [?n] :where [[?e :t/n ?n]]}")))
  (is (nil? (provider/read-query "[(let [x 1] [x])]")) "an expression")
  (is (nil? (provider/read-query "{:a 1 :b}")) "not a query"))

(deftest a-submitted-query-that-fails-comes-back
  (if-not (bird/available?)
    (support/skip! "a-submitted-query-that-fails-comes-back: no BIRD dev set")
    (let [room (d/make-room {:id :bird/provider-test-2 :store (memory/make)})]
      (try
        (let [seen (atom [])
              receipt (evaluate! room :sqlite [["submit" "SELECT nope FROM superhero"]
                                               ["submit" (answers :sqlite)]] seen)]
          (is (= {:submitted true :runs true :correct true} (:attempt/checks receipt)))
          (is (re-find #"Not submitted: the query fails" (:content (last (:messages (second @seen)))))))
        (finally (d/close-room! room))))))

(deftest ordering-in-the-vector-form
  (if-not (bird/available?)
    (support/skip! "ordering-in-the-vector-form: no BIRD dev set")
    (is (= 1 (count (:rows (provider/run-query :datalog "superhero" "[:find ?a (count ?e) :where [?e :superhero/alignment_id ?a] :order-by 1 :desc :limit 1]" {})))))))

(deftest an-answer-instead-of-a-submission-gets-one-reminder
  (if-not (bird/available?)
    (support/skip! "an-answer-instead-of-a-submission-gets-one-reminder: no BIRD dev set")
    (let [room (d/make-room {:id :bird/provider-test-3 :store (memory/make)})
          replies (fn [contents]
                    (fn [_question]
                      (let [left (atom contents)]
                        (fn [_request]
                          (let [c (first @left)] (swap! left rest)
                               (if (vector? c)
                                 {:content "" :tool-calls [{:id "s" :name "submit" :arguments {:query (second c)}}]}
                                 {:content (str c) :tool-calls []}))))))
          run (fn [contents]
                (let [q (some #(when (= 747 (:question-id %)) %) (bird/questions))
                      caps (provider/capabilities [q] {:agent-generate (replies contents)})
                      env (provider/environment-def q caps {:timeout-ms 120000})
                      team (provider/candidate-roster [{:id :sqlite :model "claude-code-sonnet" :engine :sqlite}])]
                  (binding [ec/*execution-context* (:ctx room)]
                    (:attempt-receipt @(evaluation/evaluate room team :sqlite env (:evaluator caps) {:protocol (:protocol caps)})))))]
      (try
        (is (= {:submitted true :runs true :correct true}
               (:attempt/checks (run ["42" ["submit" (answers :sqlite)]])))
            "the value, then the query after the reminder")
        (is (false? (get-in (run ["42" "still 42"]) [:attempt/checks :submitted])) "one reminder only")
        (finally (d/close-room! room))))))

(deftest a-query-cannot-reach-the-host
  (if-not (bird/available?)
    (support/skip! "a-query-cannot-reach-the-host: no BIRD dev set")
    (doseq [q ["[:find ?x :where [(slurp \"/etc/hostname\") ?x]]"
               "(q '[:find ?x :where [(slurp \"/etc/hostname\") ?x]])"
               "[:find ?x :where [(clojure.java.shell/sh \"true\") ?x]]"]]
      (is (re-find #"Unknown function" (str (:error (provider/run-query :datalog "superhero" q {})))) q))))

(deftest a-plain-query-may-hold-a-regex
  (if-not (bird/available?)
    (support/skip! "a-plain-query-may-hold-a-regex: no BIRD dev set")
    (is (= [[66]] (:rows (provider/run-query :datalog "superhero" "[:find (count ?e) :where [?e :superhero/superhero_name ?n] [(re-find #\"(?i)man\" ?n)]]" {}))))))

(deftest nested-calls-desugar-into-fresh-variables
  (is (= '[:find (count ?e) :where [?e :t/h ?h] [?e :t/w ?w] [(/ ?w ?h) ?__1] [(> ?__1 0.5)]]
         (provider/desugar-nested '[:find (count ?e) :where [?e :t/h ?h] [?e :t/w ?w] [(> (/ ?w ?h) 0.5)]])))
  (is (= '[:find ?r :where [?e :t/a ?a] [(+ ?a 1) ?__1] [(* 100 ?__1) ?r]]
         (provider/desugar-nested '[:find ?r :where [?e :t/a ?a] [(* 100 (+ ?a 1)) ?r]]))
      "a binding clause; innermost first")
  (testing "fresh variables stay in their scope"
    (is (= '[:find ?e :where [?e :t/d ?d] (or-join [?d] (and [(subs ?d 0 4) ?__1] [(= ?__1 "1991")]) [(= ?d "x")])]
           (provider/desugar-nested '[:find ?e :where [?e :t/d ?d] (or [(= (subs ?d 0 4) "1991")] [(= ?d "x")])])))
    (is (= '[:find ?e :where [?e :t/d ?d] (not-join [?d] [(subs ?d 0 4) ?__1] [(= ?__1 "1991")])]
           (provider/desugar-nested '[:find ?e :where [?e :t/d ?d] (not [(= (subs ?d 0 4) "1991")])]))))
  (testing "data stays data; a subquery literal is a query of its own"
    (is (= '[:find ?x :where [?e :t/a ?a] [(contains? (quote #{1 2}) ?a)]]
           (provider/desugar-nested '[:find ?x :where [?e :t/a ?a] [(contains? (quote #{1 2}) ?a)]])))
    (is (= '[:find ?n :where [(q [:find (max ?h) :where [_ :t/h ?h] [(* ?h 2) ?__1] [(> ?__1 3)]] $) [[?n]]]]
           (provider/desugar-nested '[:find ?n :where [(q [:find (max ?h) :where [_ :t/h ?h] [(> (* ?h 2) 3)]] $) [[?n]]]]))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be nested"
                        (provider/desugar-nested '[:find ?x :where [?e :t/a ?a] [(= (and (pos? ?a) true) true)]]))
      "and/or/if would lose their laziness"))

(deftest a-nested-candidate-answers-as-the-flat-query-does
  (if-not (bird/available?)
    (support/skip! "a-nested-candidate-answers-as-the-flat-query-does: no BIRD dev set")
    (let [nested "[:find (count ?e) :where [?e :superhero/height_cm ?h] [?e :superhero/weight_kg ?w] [(pos? ?h)] [(> (/ ?w ?h) 0.5)]]"
          flat "[:find (count ?e) :where [?e :superhero/height_cm ?h] [?e :superhero/weight_kg ?w] [(pos? ?h)] [(/ ?w ?h) ?r] [(> ?r 0.5)]]"]
      (is (= (:rows (provider/run-query :datalog "superhero" flat {}))
             (:rows (provider/run-query :datalog "superhero" nested {:nested? true}))))
      (is (:error (provider/run-query :datalog "superhero" nested {})) "without the flag, Datahike's rule stands"))))

(deftest constants-and-if-in-clauses
  (if-not (bird/available?)
    (support/skip! "constants-and-if-in-clauses: no BIRD dev set")
    (let [run #(:rows (provider/run-query :datalog "superhero" % {}))]
      (is (= (run "[:find (count ?e) :where [?e :superhero/superhero_name ?n] [(subs ?n 0 1) ?x] [(= ?x \"A\")]]")
             (run "[:find (count ?e) :where [?e :superhero/superhero_name ?n] [(subs ?n 0 1) \"A\"]]"))
          "a constant in the binding is that equality (Datahike 0.8.1902)")
      (is (= (run "[:find (count ?e) :where [?e :superhero/height_cm ?h] [(> ?h 200)]]")
             (run "[:find (count ?e) :where [?e :superhero/height_cm ?h] [(> ?h 200) ?t] [(if ?t \"tall\" \"short\") \"tall\"]]"))
          "if on a bound condition"))))

(deftest q-in-an-expression-may-name-the-db
  (if-not (bird/available?)
    (support/skip! "q-in-an-expression-may-name-the-db: no BIRD dev set")
    (is (= [[69]] (:rows (provider/run-query :datalog "superhero" "(q '[:find (count ?e) :where [?e :superhero/height_cm ?h] [(> ?h 200)]] $)" {}))))))

(deftest a-candidate-query-may-only-read
  ;; the databases are shared by every cell (and SQLite's read-only mode
  ;; still let ATTACH open or create any file the process can reach)
  (is (nil? (provider/read-only-sql "SELECT name FROM t WHERE note = 'insert; drop' -- why")))
  (is (nil? (provider/read-only-sql "WITH a AS (SELECT 1) SELECT * FROM a;")))
  (doseq [q ["ATTACH '/tmp/x.db' AS x" "SELECT 1; ATTACH '/tmp/x.db' AS x"
             "WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d" "PRAGMA table_info(t)"
             "SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE" "INSERT INTO t VALUES (1)" ""]]
    (is (string? (provider/read-only-sql q)) q))
  (testing "every gold query reads"
    (if-not (bird/available?)
      (support/skip! "a-candidate-query-may-only-read: no BIRD data")
      (is (empty? (keep #(provider/read-only-sql (:sql %)) (bird/questions)))))))

(deftest a-sqlite-connection-cannot-attach
  (if-not (bird/available?)
    (support/skip! "a-sqlite-connection-cannot-attach: no BIRD data")
    (let [f (java.io.File/createTempFile "dvergr-attach" ".db")]
      (.delete f)
      (with-open [c (bird/connect (bird/root) "superhero")]
        (is (thrown? java.sql.SQLException
                     (bird/execute c (str "ATTACH DATABASE '" f "' AS x")))))
      (is (not (.exists f)) "no file was created"))))
