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

