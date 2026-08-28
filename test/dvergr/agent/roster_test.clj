(ns dvergr.agent.roster-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.agent.roster :as roster]))

(def analyst-spec
  {:id :analyst
   :skills #{:research :analysis}
   :prompt "Investigate carefully"
   :program {:kind :scripted :reply "evidence"}})

(deftest roster-construction-is-pure-portable-data
  (let [empty-roster (roster/make-roster {:id :team
                                          :scope {:tokens 1000}
                                          :defaults {:tools #{:room/query}}})
        populated (roster/make-agent empty-roster analyst-spec)
        analyst (roster/agent populated :analyst)]
    (is (empty? (roster/agents empty-roster)) "the input value is unchanged")
    (is (= :analyst (:agent/id analyst)))
    (is (= 1 (:agent/version analyst)))
    (is (= #{:room/query} (:agent/tools analyst)))
    (is (roster/data-value? populated))
    (is (= populated (roster/make-agent populated analyst-spec))
        "adding the same definition is idempotent")))

(deftest runtime-state-is-rejected
  (is (thrown? clojure.lang.ExceptionInfo
               (roster/make-agent (roster/make-roster)
                                  (assoc analyst-spec :metadata {:callback identity}))))
  (is (thrown? clojure.lang.ExceptionInfo
               (roster/make-roster {:scope {:counter (atom 0)}})))
  (is (thrown? clojure.lang.ExceptionInfo
               (roster/make-roster {:metadata {:mutable-date (java.util.Date.)}}))))

(deftest conflicting-definitions-require-explicit-revision
  (let [r1 (roster/make-agent (roster/make-roster) analyst-spec)
        ref (roster/agent-ref (roster/agent r1 :analyst))
        r2 (roster/revise-agent r1 :analyst {:prompt "Challenge assumptions"})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (roster/make-agent r1 (assoc analyst-spec :prompt "different"))))
    (is (= 2 (:agent/version (roster/agent r2 :analyst))))
    (is (= "Challenge assumptions" (:agent/prompt (roster/agent r2 :analyst))))
    (is (thrown? clojure.lang.ExceptionInfo (roster/agent r2 ref))
        "a Run cannot silently resolve an old ref to revised behavior")))

(deftest deterministic-selection-composes-over-roster-data
  (let [r (-> (roster/make-roster)
              (roster/make-agent analyst-spec)
              (roster/make-agent {:id :lawyer
                                  :skills #{:research :legal}
                                  :program {:kind :scripted :reply "opinion"}})
              (roster/make-agent {:id :reviewer
                                  :skills #{:review}
                                  :status :paused
                                  :program {:kind :scripted :reply "review"}}))]
    (is (= [:analyst :lawyer]
           (mapv :agent/id (roster/select-agents r {:skill :research}))))
    (is (= [:lawyer]
           (mapv :agent/id (roster/select-agents r {:skills #{:research :legal}}))))
    (is (= [:reviewer]
           (mapv :agent/id (roster/select-agents r {:status :paused}))))))

(deftest caller-keys-override-defaults-across-key-spellings
  (let [r (-> (roster/make-roster
               {:defaults {:agent/tools #{:read}
                           :agent/prompt "default"}})
              (roster/make-agent {:id :writer
                                  :tools #{:write}
                                  :prompt "specific"}))
        a (roster/agent r :writer)]
    (is (= #{:write} (:agent/tools a)))
    (is (= "specific" (:agent/prompt a)))))

(deftest revisions-preserve-map-key-identity-and-increment-exactly-once
  (let [r (roster/make-agent (roster/make-roster) analyst-spec)]
    (doseq [patch [{:id :other} {:agent/id :other}
                   {:version 99} {:agent/version 99}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (roster/revise-agent r :analyst patch))))
    (let [revised (roster/revise-agent r :analyst {:prompt "new"})]
      (is (= :analyst (:agent/id (get-in revised [:roster/agents :analyst]))))
      (is (= 2 (:agent/version (get-in revised [:roster/agents :analyst])))))))

(deftest malformed-and-unknown-agent-fields-fail-loudly
  (let [r (roster/make-roster)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (roster/make-roster {:scpoe {:tokens 10}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (roster/make-agent r (assoc analyst-spec :porgram {}))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (roster/make-agent r (assoc analyst-spec :skills "research"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (roster/make-agent r (assoc analyst-spec :status "ready"))))))
