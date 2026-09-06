(ns dvergr.agent.market-bench
  "Opt-in live model entry point; frozen fixture and verifier are shared with CI.
   Caller owns the Room and can inspect the ordinary Run, Attempt and Episode."
  (:require [dvergr.agent.evaluation :as evaluation]
            [dvergr.agent.roster :as roster]
            [dvergr.benchmarks.market-evidence :as evidence]))

(defn candidate
  [provider model]
  (roster/make-agent
   (roster/make-roster {:id :market-evidence-candidates})
   {:id :analyst
    :prompt "Follow the supplied evidence contract. Distinguish documented facts from unsupported claims."
    ;; This isolates source-faithfulness from acquisition and tool-discovery.
    ;; In particular the candidate cannot inspect the host verifier's answer key.
    :tools #{}
    :model-policy {:provider provider :model model}
    :program {:kind :llm :budget-dollars 0.5 :auto-compact? false
              ;; Provider-loop fuse, not a conversational turn budget.
              :max-model-steps 2}}))

(defn evaluate
  "Return an ordinary lazy evaluation Spin. Bind the caller's Spindel context
   when running it. No execution or provider spending occurs at construction."
  [room provider model]
  (evaluation/evaluate room (candidate provider model) :analyst
                       (evidence/definition) (evidence/evaluator)))
