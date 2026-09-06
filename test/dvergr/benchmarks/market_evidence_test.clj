(ns dvergr.benchmarks.market-evidence-test
  (:require [clojure.test :refer [deftest is]]
            [dvergr.benchmarks.market-evidence :as evidence]
            [dvergr.agent.environment :as environment]
            [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.roster :as roster]
            [dvergr.agent.run :as run]
            [dvergr.discourse :as d]
            [dvergr.room.store :as store]
            [dvergr.room.store.memory :as memory]
            [org.replikativ.spindel.engine.core :as ec]))

(def fixture-answer
  {:room-boundaries {:label :supported :source :authorization
                     :quote "Room and knowledge base boundaries can deny access by default."}
   :fine-permissions {:label :contradicted :source :authorization
                      :quote "More precise access rules remain incomplete for rows, fields, objects, and acting users."}
   :replacement {:label :contradicted :source :testing
                 :quote "Use Simmis beside production systems of record because this coverage does not support replacing them."}
   :connector {:label :contradicted :source :integrations
               :quote "A conceptual workflow may name a permitted source even when a polished connector for that source has not shipped."}
   :source {:label :supported :source :installation
            :quote "The source is available, but the supported public installation path and operator documentation have not yet shipped."}
   :install {:label :contradicted :source :installation
             :quote "The source is available, but the supported public installation path and operator documentation have not yet shipped."}
   :simulation {:label :contradicted :source :simulation
                :quote "A standalone simulation product has not shipped."}
   :demand {:label :unknown :source nil :quote nil}
   :competitor {:label :unknown :source nil :quote nil}})

(deftest exact-evidence-and-document-scope
  (let [score (evidence/score fixture-answer)]
    (is (= 1.0 (:reward score)))
    (is (every? true? (vals (:checks score))))
    (is (= fixture-answer (evidence/parse-answer (pr-str fixture-answer)))))
  (doseq [answer [(assoc-in fixture-answer [:demand :label] :supported)
                  (assoc-in fixture-answer [:source :source] :testing)
                  (assoc-in fixture-answer [:source :quote] "Source available to everyone.")
                  (assoc-in fixture-answer [:source :quote] "The source is available")
                  ;; A real quotation from the correct source but irrelevant to
                  ;; this particular claim must not earn evidence credit.
                  (assoc-in fixture-answer [:fine-permissions :quote]
                            "Room and knowledge base boundaries can deny access by default.")
                  (assoc-in fixture-answer [:competitor :quote] "No evidence")]]
    (is (< (:reward (evidence/score answer)) 1.0))))

(deftest strict-format-and-completeness
  (doseq [answer [nil {} (dissoc fixture-answer :source)
                  (assoc fixture-answer :extra {:label :unknown :source nil :quote nil})
                  (assoc-in fixture-answer [:source :extra] true)]]
    (is (zero? (:reward (evidence/score answer)))))
  (doseq [text [nil "" "{} {}" "{} :dvergr.benchmarks.market-evidence/eof {}"
                "```edn\n{}\n```" "#=(+ 1 2)"
                "{:a 1 :a 2}" (apply str (repeat 65537 "x"))]]
    (is (nil? (evidence/parse-answer text)))))

(deftest environment-is-content-addressed-and-excludes-the-answer-key
  (let [definition (evidence/definition)]
    (is (= definition (environment/validate-environment definition)))
    (is (= :discard (get-in definition [:environment/world :settlement])))
    (is (= evidence/task (:environment/task definition)))
    (is (= evidence/fixture-id (get-in definition [:environment/metadata :fixture-id])))
    (is (= (evaluation/evaluator-ref (evidence/evaluator))
           (:environment/verifier definition)))))

(deftest scripted-certification-persists-and-cleans-up
  (doseq [[reply reward] [[(pr-str fixture-answer) 1.0] ["{}" 0.0]]]
    (let [room (d/make-room {:id (keyword (str "market-evidence-" (random-uuid)))
                             :store (memory/make)})
          team (roster/make-agent (roster/make-roster)
                                  {:id :fixture
                                   :program {:kind :scripted :reply reply}})]
      (try
        (binding [ec/*execution-context* (:ctx room)]
          (let [result @(evaluation/evaluate
                         room team :fixture
                         (evidence/definition)
                         (evidence/evaluator))
                receipt (:attempt-receipt result)]
            (is (= :completed (:attempt/status receipt)))
            (is (= reward (:attempt/reward receipt)))
            (is (= (:attempt result)
                   (store/-load-attempt (:store room) (:id room) (:run/id result))))
            (is (empty? (run/active-runs (:id room))))))
        (finally
          (evaluation/await-cleanups! room)
          (d/close-room! room))))))
