(ns dvergr.trial.test-isolation
  "Test script for validating deep spindel + SCI integration.

   Run this at the REPL to verify:
   1. Spindel context forking isolates datahike
   2. SCI contexts are isolated from each other
   3. Deep integration: SCI uses forked context transparently
   4. Merge/discard work correctly

   Key architecture:
   - ctx/fork-context creates forked spindel context
   - agent-sci/create-agent-execution-context creates SCI with forked context bound
   - *execution-context* in SCI = forked runtime (transparent to agent code)
   - @ydb in SCI resolves to forked datahike branch"
  (:require [dvergr.trial.app :as app]
            [dvergr.sandbox :as sandbox]
            [dvergr.agent.sci :as agent-sci]
            [dvergr.agent.config :as agent-core]
            [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.yggdrasil :as ygg]
            [datahike.api :as dh]))

;; ============================================================================
;; Test 1: Spindel Context Isolation
;; ============================================================================

(defn test-spindel-isolation
  "Verify that forked contexts isolate datahike changes."
  []
  (println "\n=== Test 1: Spindel Context Isolation ===\n")

  ;; Setup
  (app/init!)
  (app/create-note! "Parent note" [:test])
  (println "Parent has" (count (app/list-notes)) "note(s)")

  ;; Create forked context
  (let [forked-ctx (ctx/fork-context rtc/*execution-context*)]

    ;; In fork: add a note
    (binding [rtc/*execution-context* forked-ctx]
      (app/create-note! "Forked note" [:test :fork])
      (println "Fork has" (count (app/list-notes)) "note(s)"))

    ;; Back in parent: should NOT see fork's note
    (let [parent-count (count (app/list-notes))]
      (println "Parent still has" parent-count "note(s)")

      (if (= parent-count 1)
        (println "✓ PASS: Fork is isolated from parent")
        (println "✗ FAIL: Parent saw fork's changes!"))))

  (app/stop!))

;; ============================================================================
;; Test 2: SCI Isolation
;; ============================================================================

(defn test-sci-isolation
  "Verify that SCI contexts are isolated from each other."
  []
  (println "\n=== Test 2: SCI Isolation ===\n")

  (let [ctx-a (sandbox/fork-for-session)
        ctx-b (sandbox/fork-for-session)]

    ;; Define x in context A
    (sandbox/eval-code ctx-a "(def x 42)")
    (let [result-a (sandbox/eval-code ctx-a "x")]
      (println "Context A: x =" (:value result-a)))

    ;; Context B should NOT see x
    (let [result-b (sandbox/eval-code ctx-b "x")]
      (if (:success result-b)
        (println "✗ FAIL: Context B saw x =" (:value result-b))
        (println "✓ PASS: Context B cannot see x (isolated)")))

    ;; Define different x in B
    (sandbox/eval-code ctx-b "(def x 99)")
    (let [result-a (sandbox/eval-code ctx-a "x")
          result-b (sandbox/eval-code ctx-b "x")]
      (println "Context A: x =" (:value result-a))
      (println "Context B: x =" (:value result-b))

      (if (and (= 42 (:value result-a))
               (= 99 (:value result-b)))
        (println "✓ PASS: Each context has its own x")
        (println "✗ FAIL: Contexts are not isolated!")))))

;; ============================================================================
;; Test 3: Deep Integration - SCI uses forked context transparently
;; ============================================================================

(defn test-deep-integration
  "Test that SCI automatically uses forked datahike via bound *execution-context*."
  []
  (println "\n=== Test 3: Deep Integration ===\n")

  ;; Setup app
  (app/init!)
  (app/create-note! "Original note" [:original])
  (println "Parent has" (count (app/list-notes)) "note(s)")

  ;; Create agent environment using deep integration
  (let [env (app/create-agent-env)]

    (println "Created agent environment with fork:" (:fork-id env))

    ;; Run code in SCI - should see parent's note
    (let [result (app/run-in-env env "(count (list-notes))")]
      (println "Agent sees" (:value result) "note(s) initially")

      (if (= 1 (:value result))
        (println "✓ PASS: Agent sees parent's data")
        (println "✗ FAIL: Agent didn't see parent's data")))

    ;; Agent creates note - in forked context
    (app/run-in-env env "(create-note! \"Agent note\" [:agent])")

    ;; Agent sees its own note
    (let [result (app/run-in-env env "(count (list-notes))")]
      (println "Agent now sees" (:value result) "note(s)")

      (if (= 2 (:value result))
        (println "✓ PASS: Agent sees its own note")
        (println "✗ FAIL: Agent didn't see its note")))

    ;; Parent should still have 1 note
    (let [parent-count (count (app/list-notes))]
      (println "Parent still has" parent-count "note(s)")

      (if (= 1 parent-count)
        (println "✓ PASS: Parent is isolated from agent")
        (println "✗ FAIL: Parent saw agent's changes!")))

    ;; Discard the environment (don't merge)
    (app/reject! env))

  (app/stop!))

;; ============================================================================
;; Test 4: Merge Workflow
;; ============================================================================

(defn test-merge-workflow
  "Test that merging brings agent's changes to parent."
  []
  (println "\n=== Test 4: Merge Workflow ===\n")

  (app/init!)
  (app/create-note! "Existing note" [:existing])
  (println "Parent has" (count (app/list-notes)) "note(s)")

  (let [env (app/create-agent-env)]

    ;; Agent creates a note
    (app/run-in-env env "(create-note! \"Agent's creation\" [:agent :test])")

    ;; Verify isolation
    (println "Before merge:")
    (println "  Agent sees:" (:value (app/run-in-env env "(count (list-notes))")) "note(s)")
    (println "  Parent sees:" (count (app/list-notes)) "note(s)")

    ;; Merge
    (println "\nMerging agent's work to parent...")
    (app/approve! env)

    ;; Parent should now see agent's note
    (let [parent-count (count (app/list-notes))]
      (println "After merge, parent has" parent-count "note(s)")

      (if (= 2 parent-count)
        (println "✓ PASS: Merge brought agent's note to parent")
        (println "✗ FAIL: Merge didn't work"))))

  (app/stop!))

;; ============================================================================
;; Test 5: Full Lifecycle via observe!
;; ============================================================================

(defn test-observe-lifecycle
  "Test the full observe! → review → approve/reject lifecycle."
  []
  (println "\n=== Test 5: Observe Lifecycle ===\n")

  (app/init!)

  ;; Create notes and activity to trigger patterns
  (app/create-note! "Clojure notes" [:clojure])
  (dotimes [_ 6] (app/search! "Clojure"))
  (app/search! "Clojure")
  (app/search! "Clojure")

  (println "Activity created. Detecting patterns...")

  ;; Observe
  (let [envs (app/observe!)]

    (if (empty? envs)
      (println "✗ FAIL: No patterns detected (expected at least 1)")
      (do
        (println "✓ PASS: Created" (count envs) "environment(s)")

        ;; Test first environment
        (let [env (first envs)]
          (println "\nTesting first environment...")

          ;; Run code in environment
          (let [result (app/run-in-env env "(list-notes)")]
            (println "Agent can query:" (if (:success result) "✓" "✗")))

          ;; Create something in the fork
          (app/run-in-env env "(create-note! \"From observe test\" [:test])")

          ;; Verify isolation
          (let [agent-count (:value (app/run-in-env env "(count (list-notes))"))
                parent-count (count (app/list-notes))]
            (println "Agent sees:" agent-count ", Parent sees:" parent-count)

            (if (> agent-count parent-count)
              (println "✓ PASS: Fork is isolated")
              (println "✗ FAIL: Fork not isolated")))

          ;; Reject this one (cleanup)
          (app/reject! env))

        ;; Reject remaining envs
        (doseq [env (rest envs)]
          (app/reject! env)))))

  (app/stop!))

;; ============================================================================
;; Test 6: Spin/Await in SCI
;; ============================================================================

(defn test-spin-in-sci
  "Test that spin/await work in the SCI environment."
  []
  (println "\n=== Test 6: Spin/Await in SCI ===\n")

  (app/init!)
  (app/create-note! "Test note" [:test])

  (let [env (app/create-agent-env)]

    ;; Try running a spin
    (let [result (app/run-spin-in-env env
                   "(spin
                      (let [notes (list-notes)]
                        {:count (count notes)
                         :first-content (:note/content (first notes))}))")
          _ (println "Spin result:" result)]

      (if (:success result)
        (do
          (println "✓ PASS: Spin executed in SCI")
          (println "  Result value:" (:value result)))
        (do
          (println "✗ FAIL: Spin failed in SCI")
          (println "  Error:" (get-in result [:error :message])))))

    (app/reject! env))

  (app/stop!))

;; ============================================================================
;; Run All Tests
;; ============================================================================

(defn run-all-tests
  "Run all isolation and integration tests."
  []
  (println "\n" (apply str (repeat 60 "=")) "\n")
  (println "DEEP INTEGRATION TEST SUITE")
  (println "\n" (apply str (repeat 60 "=")) "\n")

  (test-spindel-isolation)
  (test-sci-isolation)
  (test-deep-integration)
  (test-merge-workflow)
  (test-observe-lifecycle)
  (test-spin-in-sci)

  (println "\n" (apply str (repeat 60 "=")) "\n")
  (println "ALL TESTS COMPLETE")
  (println "\n" (apply str (repeat 60 "=")) "\n"))

;; ============================================================================
;; REPL Usage
;; ============================================================================

(comment
  ;; Run all tests
  (run-all-tests)

  ;; Or run individual tests
  (test-spindel-isolation)
  (test-sci-isolation)
  (test-deep-integration)
  (test-merge-workflow)
  (test-observe-lifecycle)
  (test-spin-in-sci)

  ;; Manual exploration of deep integration
  (app/init!)

  ;; Create agent environment
  (def env (app/create-agent-env))

  ;; The magic: SCI has *execution-context* = forked runtime
  ;; So all code naturally uses forked datahike

  ;; Agent queries (uses forked @ydb)
  (app/run-in-env env "(list-notes)")

  ;; Agent creates (in forked branch)
  (app/run-in-env env "(create-note! \"From SCI\" [:sci])")

  ;; Agent sees its note
  (app/run-in-env env "(count (list-notes))")

  ;; Parent doesn't see it
  (count (app/list-notes))

  ;; Merge or discard
  (app/approve! env)  ;; or (app/reject! env)

  ;; After merge, parent sees it
  (count (app/list-notes))

  (app/stop!))
