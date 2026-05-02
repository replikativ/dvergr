(ns workflow-2-iterative-development
  "Workflow #2: Iterative Code Development

   Goal: Agent develops and tests code in SCI before writing to file

   Success criteria:
   - Agent uses clojure_eval to develop and test code
   - Agent iterates based on test results
   - Agent writes final working code to file
   - Code gets auto-indexed"
  (:require [dvergr.core :as r]
            [dvergr.session :as session]
            [dvergr.storage :as storage]
            [clojure.java.io :as io]))

(def task
  "Create a function called validate-email that checks if a string is a valid email address.

   Process:
   1. Use clojure_eval to develop the function iteratively
   2. Test it with these cases:
      - \"user@example.com\" -> should return true
      - \"invalid.email\" -> should return false
      - \"user@\" -> should return false
      - \"@example.com\" -> should return false
      - \"user@example\" -> should return true (simple domain ok)

   3. Once your tests pass, write the final function to test-data/validators.clj
      with a proper docstring

   4. Use code_query to verify the function was indexed")

(defn run-workflow
  "Run workflow #2 with specified provider/model"
  [& {:keys [provider model max-turns]
      :or {provider :fireworks
           model "accounts/fireworks/models/qwen3-coder-480b-a35b-instruct"
           max-turns 15}}]

  (println "=== Workflow #2: Iterative Code Development ===")
  (println "Provider:" provider)
  (println "Model:" model)
  (println "Max turns:" max-turns)
  (println)

  (let [session-id (str "workflow-2-" (System/currentTimeMillis))
        output-file "test-data/validators.clj"
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

    ;; Check if agent used clojure_eval for testing
    (let [tool-uses (mapcat :tool-uses (:messages result))
          eval-uses (filter #(= "clojure_eval" (:name %)) tool-uses)
          write-uses (filter #(= "write_file" (:name %)) tool-uses)
          code-query-uses (filter #(= "code_query" (:name %)) tool-uses)]

      (println "clojure_eval calls:" (count eval-uses))
      (if (>= (count eval-uses) 3)
        (println "✓ Agent tested code in SCI")
        (println "✗ Agent didn't test enough (< 3 evals)"))

      (println "write_file calls:" (count write-uses))
      (if (>= (count write-uses) 1)
        (println "✓ Agent wrote final code to file")
        (println "✗ Agent didn't write code to file"))

      (println "code_query calls:" (count code-query-uses))
      (if (>= (count code-query-uses) 1)
        (println "✓ Agent verified code was indexed")
        (println "✗ Agent didn't verify indexing")))

    ;; Check if file exists
    (if (.exists (io/file output-file))
      (do
        (println "✓ Output file exists:" output-file)
        ;; Check if function is in datahike
        (when-let [conn (session/get-db-connection session-id)]
          (let [found (storage/find-function conn 'validate-email)]
            (if found
              (println "✓ Function indexed in datahike:" (:code/name found))
              (println "✗ Function not found in datahike")))))
      (println "✗ Output file not created"))

    ;; Check final status
    (if (= :completed (:status result))
      (println "✓ Task completed successfully")
      (println "✗ Task did not complete:" (:status result)))

    (println)
    (println "To clean up:")
    (println (str "rm " output-file))

    result))

(comment
  ;; Run with Qwen3 Coder
  (run-workflow)

  ;; Run with more turns for complex iteration
  (run-workflow
    :max-turns 20)

  ;; Clean up
  (io/delete-file "test-data/validators.clj")
  )
