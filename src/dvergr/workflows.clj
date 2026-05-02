(ns dvergr.workflows
  "Composable workflow patterns for multi-agent coordination.

   These patterns implement the designs from AGENTIC_WORKFLOWS_DESIGN.md
   using dvergr primitives and spindel composition."
  (:require [org.replikativ.spindel.core :refer [spin]]
            [org.replikativ.spindel.core :refer [await]]
            [dvergr.agent.task :as prim]
            [dvergr.agent.prebuilt :as agents]))

;; ============================================================================
;; Pattern 1: Sequential Pipeline
;; ============================================================================

(defn research-implement-test
  "Sequential pipeline: Research → Implement → Test.

   Use when:
   - Each stage depends on previous output
   - Want to verify at each step
   - Clear linear progression

   Args:
     topic - What to research and implement

   Returns:
     Spin resolving to {:research ... :code ... :tests ...}"
  [topic & {:keys [researcher coder tester]
            :or {researcher (agents/researcher)
                 coder (agents/coder)
                 tester (agents/reviewer)}}]  ; reviewer as tester
  (spin
    (let [;; Stage 1: Research
          research (await (prim/spawn! researcher
                            (str "Research " topic)))

          ;; Stage 2: Implementation (depends on research)
          code (await (prim/spawn! coder
                        (str "Implement based on research:\n\n"
                             (prim/extract-result research))))

          ;; Stage 3: Testing (depends on code)
          tests (await (prim/spawn! tester
                         (str "Review and test implementation:\n\n"
                              (prim/extract-result code))))]

      {:research research
       :code code
       :tests tests
       :status (if (prim/successful? tests) :success :needs-work)})))

;; ============================================================================
;; Pattern 2: Parallel Fan-Out
;; ============================================================================

(defn parallel-research
  "Parallel fan-out: Multiple independent research tasks.

   Use when:
   - Tasks are independent
   - Want results fast
   - Can aggregate after

   Args:
     topics - Vector of topics to research
     opts - {:researcher agent} optional custom researcher

   Returns:
     Spin resolving to {:topics [...] :results [...] :summary ...}"
  [topics & {:keys [researcher]
             :or {researcher (agents/researcher)}}]
  (spin
    (let [;; Spawn all in parallel
          spins (mapv #(prim/spawn! researcher
                         (str "Research: " %))
                      topics)

          ;; Await all results
          results (await (prim/parallel spins))]

      {:topics topics
       :results results
       :successful (filter prim/successful? results)
       :failed (remove prim/successful? results)
       :summary (str "Completed " (count (filter prim/successful? results))
                     "/" (count results) " research tasks")})))

;; ============================================================================
;; Pattern 3: Iterative Refinement
;; ============================================================================

(defn iterative-refinement
  "Iterative refinement: Producer → Critic → Repeat until good.

   Use when:
   - Quality matters more than speed
   - Objective criteria for 'good enough'
   - Improvement possible via feedback

   Args:
     task - What to implement/produce
     max-iterations - Stop after this many iterations
     acceptable? - Predicate fn [critique-result] -> boolean
     opts - {:producer agent :critic agent}

   Returns:
     Spin resolving to {:status :success/:max-iterations
                        :output final-result
                        :iterations count}"
  [task max-iterations acceptable? & {:keys [producer critic]
                                      :or {producer (agents/coder)
                                           critic (agents/reviewer)}}]
  (spin
    (loop [iteration 0
           current-output nil
           feedback nil]

      (if (>= iteration max-iterations)
        {:status :max-iterations
         :output current-output
         :iterations iteration}

        (let [;; Producer creates/refines
              production (await (prim/spawn! producer
                                  (if feedback
                                    (str "Refine based on feedback:\n\n"
                                         feedback "\n\n"
                                         "Previous attempt:\n"
                                         current-output)
                                    (str "Initial implementation: " task))))

              ;; Critic evaluates
              critique (await (prim/spawn! critic
                                (str "Review implementation:\n\n"
                                     (prim/extract-result production))))]

          ;; Check if acceptable
          (if (acceptable? critique)
            {:status :success
             :output production
             :iterations (inc iteration)
             :final-critique critique}

            ;; Continue refining
            (recur (inc iteration)
                   (prim/extract-result production)
                   (prim/extract-result critique))))))))

;; ============================================================================
;; Pattern 4: Competitive Race
;; ============================================================================

(defn competitive-race
  "Competitive race: Multiple approaches, first to complete wins.

   Use when:
   - Time critical
   - Multiple valid approaches
   - Can validate winner quickly

   Args:
     task - What to implement
     approaches - Vector of {:agent ... :prompt ...} maps
     or
     agents - Vector of agents (will use same task prompt)

   Returns:
     Spin resolving to {:winner agent-result :approach index}"
  [task & {:keys [approaches agents]}]
  {:pre [(or approaches agents)]}
  (spin
    (let [spins (if approaches
                  ;; Custom prompt per approach
                  (mapv #(prim/spawn! (:agent %)
                           (str task "\n\nApproach: " (:prompt %)))
                        approaches)

                  ;; Same prompt for all agents
                  (mapv #(prim/spawn! % task) agents))

          ;; First to complete wins
          winner (await (prim/race spins))]

      {:winner winner
       :approach (if approaches
                   (nth approaches (.indexOf spins winner))
                   (.indexOf (vec agents) (:agent winner)))})))

;; ============================================================================
;; Helper: Check Acceptance Criteria
;; ============================================================================

(defn review-accepts?
  "Check if reviewer accepts the implementation.

   Looks for 'Approve' or 'LGTM' or no critical issues in review."
  [review-result]
  (when (prim/successful? review-result)
    (let [content (prim/extract-result review-result)
          content-lower (clojure.string/lower-case (or content ""))]
      (or (re-find #"approve" content-lower)
          (re-find #"lgtm" content-lower)
          (re-find #"ready to merge" content-lower)
          (not (re-find #"critical" content-lower))))))

;; ============================================================================
;; Workflow Combinators
;; ============================================================================

(defn then
  "Sequential composition: workflow-a then workflow-b.

   The second workflow receives the result of the first.

   Example:
     (then (research-implement-test \"JWT\")
           (fn [result]
             (review-workflow (:code result))))"
  [workflow-a workflow-b-fn]
  (spin
    (let [result-a (await workflow-a)
          result-b (await (workflow-b-fn result-a))]
      {:first result-a
       :second result-b})))

(defn and-parallel
  "Parallel composition: Run multiple workflows concurrently.

   Example:
     (and-parallel
       (parallel-research [\"JWT\" \"OAuth\"])
       (parallel-research [\"React\" \"Vue\"]))"
  [& workflows]
  (spin
    (await (prim/parallel workflows))))

(comment
  ;; Usage examples
  (require '[org.replikativ.spindel.engine.core :as rtc])

  (def rt (rtc/create-runtime))

  ;; Sequential pipeline
  (binding [rtc/*execution-context* rt]
    (def result (research-implement-test "JWT authentication")))

  ;; Parallel research
  (binding [rtc/*execution-context* rt]
    (def research (parallel-research ["JWT libs" "OAuth patterns" "SAML"])))

  ;; Iterative refinement
  (binding [rtc/*execution-context* rt]
    (def refined (iterative-refinement
                   "Implement merge strategy for pure functions"
                   3  ; max iterations
                   review-accepts?)))

  ;; Competitive race
  (binding [rtc/*execution-context* rt]
    (def winner (competitive-race
                  "Implement quick sort"
                  :approaches [{:agent (agents/coder :name "fast")
                                :prompt "Optimize for speed"}
                               {:agent (agents/coder :name "readable")
                                :prompt "Optimize for clarity"}]))))
