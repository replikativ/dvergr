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
