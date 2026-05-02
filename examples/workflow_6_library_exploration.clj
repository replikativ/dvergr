(ns workflow-6-library-exploration
  "Workflow #6: Library Exploration

   Goal: Agent explores clojure.string library by experimentation

   Success criteria:
   - Agent can require and use clojure.string in SCI
   - Agent experiments with different functions
   - Agent builds working examples
   - stdout is captured and visible"
  (:require [dvergr.core :as r]))

(def task
  "Explore the clojure.string library. Try at least 5 different functions
   (like split, join, replace, upper-case, lower-case).

   For each function:
   1. Use clojure_eval to test it with an example
   2. Note what it does
   3. Try edge cases

   Finally, create a function called demo-all-string-fns that demonstrates
   all the functions you explored.")

(defn run-workflow
  "Run workflow #6 with specified provider/model"
  [& {:keys [provider model max-turns]
      :or {provider :fireworks
           model "accounts/fireworks/models/qwen3-coder-480b-a35b-instruct"
           max-turns 10}}]

  (println "=== Workflow #6: Library Exploration ===")
  (println "Provider:" provider)
  (println "Model:" model)
  (println "Max turns:" max-turns)
  (println)

  (let [session-id (str "workflow-6-" (System/currentTimeMillis))
        result (r/run task
                     :provider provider
                     :model model
                     :max-turns max-turns
                     :session-id session-id)]

    (println)
    (println "=== Result ===")
    (println "Status:" (:status result))
    (println "Turns used:" (:turn result))
    (println "Session:" session-id)
    (println)

    ;; Verify success criteria
    (println "=== Verification ===")

    ;; Check if agent used clojure_eval
    (let [tool-uses (filter #(= "clojure_eval" (:name %))
                           (mapcat :tool-uses (:messages result)))]
      (println "clojure_eval calls:" (count tool-uses))
      (if (>= (count tool-uses) 5)
        (println "✓ Agent experimented with multiple functions")
        (println "✗ Agent didn't experiment enough (< 5 evals)")))

    ;; Check final status
    (if (= :completed (:status result))
      (println "✓ Task completed successfully")
      (println "✗ Task did not complete:" (:status result)))

    (println)
    (println "View full transcript:")
    (println (str "Session ID: " session-id))

    result))

(comment
  ;; Run with Qwen3 Coder
  (run-workflow)

  ;; Run with Kimi K2 (advanced reasoning)
  (run-workflow
    :model "accounts/fireworks/models/kimi-k2-thinking"
    :max-turns 15)

  ;; Run with Anthropic
  (run-workflow
    :provider :anthropic
    :model "claude-sonnet-4-5-20250514"
    :max-turns 10))
