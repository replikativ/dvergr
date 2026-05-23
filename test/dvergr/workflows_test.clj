(ns dvergr.workflows-test
  "Tests for dvergr.workflows — patterns built on dvergr.discourse.
   All participants are `scripted` (reply-to-sender); no LLM calls."
  (:require [clojure.test :refer [deftest is testing]]
            [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as ec]
            [dvergr.discourse :as d]
            [dvergr.workflows :as wf]))

(defn- await-spin
  ([room spin-fn] (await-spin room spin-fn 2000))
  ([room spin-fn wait-ms]
   (let [p (promise)]
     (binding [ec/*execution-context* (:ctx room)]
       (sp/spawn!
         (sp/spin (deliver p (sp/await (spin-fn room))))))
     (deref p wait-ms ::timeout))))

;; ============================================================================

(deftest research-implement-test-pipeline
  (let [r (d/room :t)]
    (binding [ec/*execution-context* (:ctx r)]
      (d/join r (d/scripted :researcher ["found references X, Y, Z"]))
      (d/join r (d/scripted :coder      ["(defn solve [] :impl)"]))
      (d/join r (d/scripted :reviewer   ["lgtm"])))
    (let [result (await-spin r #(wf/research-implement-test % "auth"))]
      (is (= "found references X, Y, Z" (:content (:research result))))
      (is (= "(defn solve [] :impl)"    (:content (:code result))))
      (is (= "lgtm"                     (:content (:review result)))))))

(deftest parallel-research-fans-out
  (let [r (d/room :t)]
    (binding [ec/*execution-context* (:ctx r)]
      (d/join r (d/scripted :r1 ["X-summary"]))
      (d/join r (d/scripted :r2 ["Y-summary"]))
      (d/join r (d/scripted :r3 ["Z-summary"])))
    (let [results (await-spin r #(wf/parallel-research %
                                  [["X" :r1] ["Y" :r2] ["Z" :r3]]))]
      (is (= 3 (count results)))
      (is (= #{"X-summary" "Y-summary" "Z-summary"}
             (set (map :content results)))))))

(deftest iterative-refinement-converges
  (let [r (d/room :t)]
    (binding [ec/*execution-context* (:ctx r)]
      (d/join r (d/scripted :coder    ["draft-1" "draft-2"]))
      (d/join r (d/scripted :reviewer ["needs work" "lgtm"])))
    (let [result (await-spin r
                   #(wf/iterative-refinement % "build login form"
                       {:accept? wf/review-accepts?
                        :max-iter 3}))]
      (is (= :accepted (:result result)))
      (is (= 1 (:iterations result)))
      (is (= "draft-2" (:content (:draft result))))
      (is (= "lgtm"    (:content (:review result)))))))

(deftest competitive-race-fastest-wins
  (let [r (d/room :t)]
    (binding [ec/*execution-context* (:ctx r)]
      ;; All three reply immediately; first to be scheduled wins.
      (d/join r (d/scripted :a ["A-answer"]))
      (d/join r (d/scripted :b ["B-answer"]))
      (d/join r (d/scripted :c ["C-answer"])))
    (let [winner (await-spin r #(wf/competitive-race % [:a :b :c] "task"))]
      (is (some? winner))
      (is (#{:a :b :c} (:from winner))))))

(deftest then-sequential-composition
  (let [r (d/room :t)]
    (binding [ec/*execution-context* (:ctx r)]
      (d/join r (d/scripted :step-a ["step-a-result"]))
      (d/join r (d/scripted :step-b ["step-b-result"])))
    (let [outcome (await-spin r
                    (fn [room]
                      (wf/then (d/ask room :step-a {:content "go"})
                               (fn [_a] (d/ask room :step-b {:content "next"})))))]
      (is (= "step-a-result" (:content (:first outcome))))
      (is (= "step-b-result" (:content (:second outcome)))))))

(deftest and-parallel-runs-concurrently
  (let [r (d/room :t)]
    (binding [ec/*execution-context* (:ctx r)]
      (d/join r (d/scripted :p1 ["one"]))
      (d/join r (d/scripted :p2 ["two"])))
    (let [results (await-spin r
                    (fn [room]
                      (wf/and-parallel
                        (d/ask room :p1 {:content "?"})
                        (d/ask room :p2 {:content "?"}))))]
      (is (= 2 (count results)))
      (is (= #{"one" "two"} (set (map :content results)))))))

(deftest review-accepts?-recognises-acceptance-strings
  (testing "positive cases"
    (is (wf/review-accepts? {:content "LGTM, ship it"}))
    (is (wf/review-accepts? {:content "Approve"}))
    (is (wf/review-accepts? {:content "I approve this change"}))
    (is (wf/review-accepts? {:content "ready to merge"}))
    (is (wf/review-accepts? {:content "lgtm"})))
  (testing "negative cases"
    (is (not (wf/review-accepts? {:content "needs work"})))
    (is (not (wf/review-accepts? {:content "found a bug"})))
    (is (not (wf/review-accepts? {:content nil})))))
