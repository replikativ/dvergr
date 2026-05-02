(ns examples.phase-1-2-demo
  "Demonstration of Phase 1-2: Agent primitives and group composition.

   This example shows:
   - Creating agents with make-agent
   - Sequential composition with ask!
   - Parallel composition with spawn! and parallel
   - Group creation and execution
   - Dynamic participant management
   - Admin controls (pause/resume)
   - Custom workflow functions"
  (:require [is.simm.spindel.runtime.context :as ctx]
            [is.simm.spindel.runtime.core :as rtc]
            [is.simm.spindel.spin.cps :refer [spin]]
            [is.simm.spindel.effects.await :refer [await]]
            [dvergr.agent.core :as agent]
            [dvergr.agent.primitives :as prim]
            [dvergr.group.core :as grp]
            [dvergr.group.execution :as grp-exec]))

;; ============================================================================
;; Example 1: Sequential Composition
;; ============================================================================

(comment
  ;; Create runtime
  (def rt (ctx/create-execution-context))

  ;; Create agents
  (def researcher (agent/make-agent {:name "researcher" :max-turns 5}))
  (def coder (agent/make-agent {:name "coder" :max-turns 10}))
  (def tester (agent/make-agent {:name "tester" :max-turns 5}))

  ;; Sequential workflow with plain Clojure
  (binding [rtc/*execution-context* rt]
    (def result
      (let [research (prim/ask! researcher "Research JWT best practices")
            code (prim/ask! coder (str "Implement based on: " (:result research)))
            tests (prim/ask! tester (str "Test this code: " (:result code)))]
        {:research research :code code :tests tests})))

  ;; Check result
  (:status result))

;; ============================================================================
;; Example 2: Parallel Composition
;; ============================================================================

(comment
  ;; Multiple researchers in parallel
  (binding [rtc/*execution-context* rt]
    (def perspectives
      (let [results (await (prim/parallel
                             (prim/spawn! (agent/make-agent {:name "security-expert"}) "Security considerations for JWT")
                             (prim/spawn! (agent/make-agent {:name "performance-expert"}) "Performance optimization for JWT")
                             (prim/spawn! (agent/make-agent {:name "ux-expert"}) "User experience with JWT")))]
        {:perspectives results
         :summary (str "Collected " (count results) " perspectives")})))

  ;; Check results
  (count (:perspectives perspectives)))

;; ============================================================================
;; Example 3: Race and Timeout
;; ============================================================================

(comment
  ;; Race between two implementations
  (binding [rtc/*execution-context* rt]
    (def winner
      (await (prim/race
               (prim/spawn! (agent/make-agent {:name "fast-impl"}) "Quick implementation")
               (prim/spawn! (agent/make-agent {:name "thorough-impl"}) "Thorough implementation")))))

  ;; With timeout
  (binding [rtc/*execution-context* rt]
    (def timed-result
      (await (prim/timeout
               (prim/spawn! researcher "Complex research task")
               60000  ; 60 seconds
               {:status :timeout :message "Research took too long"})))))

;; ============================================================================
;; Example 4: Basic Group (Default Parallel Execution)
;; ============================================================================

(comment
  ;; Create a group with default parallel execution
  (def research-team
    (grp/make-group {:name "research-team"
                     :participants [researcher
                                    (agent/make-agent {:name "analyst"})
                                    (agent/make-agent {:name "reviewer"})]
                     :budget 30000}))

  ;; Ask the group (all participants run in parallel)
  (binding [rtc/*execution-context* rt]
    (def team-result
      (grp-exec/ask-group! research-team "Analyze market trends for Q1")))

  ;; Check result
  (:status team-result)
  (count (:results team-result)))

;; ============================================================================
;; Example 5: Group with Custom Workflow (Sequential)
;; ============================================================================

(comment
  ;; Create a pipeline group
  (def dev-pipeline
    (grp/make-group {:name "dev-pipeline"
                     :participants [researcher coder tester]
                     :budget 50000
                     :workflow (fn [task group-ctx]
                                 ;; Sequential workflow
                                 (let [research (prim/ask! researcher task {:parent-ctx group-ctx})
                                       code (prim/ask! coder (:result research) {:parent-ctx group-ctx})
                                       tests (prim/ask! tester (:result code) {:parent-ctx group-ctx})]
                                   {:status :complete
                                    :result (:result tests)
                                    :steps [research code tests]}))}))

  ;; Execute the pipeline
  (binding [rtc/*execution-context* rt]
    (def pipeline-result
      (grp-exec/ask-group! dev-pipeline "Implement user authentication")))

  ;; Check result
  (:status pipeline-result)
  (count (:steps pipeline-result)))

;; ============================================================================
;; Example 6: Group with Helper Workflows
;; ============================================================================

(comment
  ;; Using sequential-workflow helper
  (def sequential-team
    (grp/make-group {:name "sequential-team"
                     :participants [researcher coder tester]
                     :workflow (grp-exec/sequential-workflow [researcher coder tester])}))

  ;; Using parallel-workflow helper
  (def parallel-team
    (grp/make-group {:name "parallel-team"
                     :participants [(agent/make-agent {:name "expert-1"})
                                    (agent/make-agent {:name "expert-2"})
                                    (agent/make-agent {:name "expert-3"})]
                     :workflow (grp-exec/parallel-workflow [(agent/make-agent {:name "expert-1"})
                                                             (agent/make-agent {:name "expert-2"})
                                                             (agent/make-agent {:name "expert-3"})])})))

;; ============================================================================
;; Example 7: Dynamic Participant Management
;; ============================================================================

(comment
  ;; Create group with initial participants
  (def dynamic-team
    (grp/make-group {:name "dynamic-team"
                     :participants [researcher coder]
                     :budget 60000}))

  (println "Initial participants:" (count (grp/get-participants dynamic-team)))

  ;; Add an expert mid-workflow
  (grp/add-agent! dynamic-team (agent/make-agent {:name "security-expert"}))
  (println "After adding expert:" (count (grp/get-participants dynamic-team)))

  ;; Remove underperforming agent
  (grp/remove-agent! dynamic-team "slow-agent")
  (println "After removal:" (count (grp/get-participants dynamic-team))))

;; ============================================================================
;; Example 8: Admin Controls (Pause/Resume)
;; ============================================================================

(comment
  ;; Create group
  (def monitored-team
    (grp/make-group {:name "monitored"
                     :participants [researcher coder tester]}))

  ;; Start work in background
  (binding [rtc/*execution-context* rt]
    (def work-spin (grp-exec/spawn-group! monitored-team "Complex task")))

  ;; Admin pauses for review
  (grp/pause! monitored-team)
  (println "Status:" (grp/get-status monitored-team))  ; => :paused

  ;; Resume after review
  (grp/resume! monitored-team)
  (println "Status:" (grp/get-status monitored-team))  ; => :active

  ;; Get result
  (binding [rtc/*execution-context* rt]
    @work-spin))

;; ============================================================================
;; Example 9: Nested Composition (Group using Groups)
;; ============================================================================

(comment
  ;; Create specialized sub-teams
  (def frontend-team
    (grp/make-group {:name "frontend"
                     :participants [(agent/make-agent {:name "react-dev"})
                                    (agent/make-agent {:name "ui-designer"})]
                     :workflow (grp-exec/parallel-workflow [(agent/make-agent {:name "react-dev"})
                                                             (agent/make-agent {:name "ui-designer"})])}))

  (def backend-team
    (grp/make-group {:name "backend"
                     :participants [(agent/make-agent {:name "api-dev"})
                                    (agent/make-agent {:name "db-expert"})]
                     :workflow (grp-exec/parallel-workflow [(agent/make-agent {:name "api-dev"})
                                                             (agent/make-agent {:name "db-expert"})])}))

  ;; Compose teams with plain Clojure
  (binding [rtc/*execution-context* rt]
    (def full-stack-result
      (let [feature-spec "Implement user profile page"
            [frontend backend] (await (prim/parallel
                                        (grp-exec/spawn-group! frontend-team feature-spec)
                                        (grp-exec/spawn-group! backend-team feature-spec)))]
        {:frontend frontend
         :backend backend
         :status :complete})))

  ;; Check result
  (:status full-stack-result))

;; ============================================================================
;; Example 10: Custom Complex Workflow
;; ============================================================================

(comment
  (def adaptive-team
    (grp/make-group {:name "adaptive"
                     :participants [researcher coder tester
                                    (agent/make-agent {:name "reviewer"})]
                     :budget 100000
                     :workflow (fn [task group-ctx]
                                 ;; Adaptive workflow based on complexity
                                 (let [research (prim/ask! researcher (str "Analyze complexity of: " task)
                                                           {:parent-ctx group-ctx})
                                       complexity (or (:complexity research) :medium)]

                                   (case complexity
                                     :simple
                                     ;; Just code and test
                                     (let [code (prim/ask! coder task {:parent-ctx group-ctx})
                                           tests (prim/ask! tester (:result code) {:parent-ctx group-ctx})]
                                       {:status :complete
                                        :result (:result tests)
                                        :complexity :simple})

                                     :complex
                                     ;; Parallel implementation + review
                                     (let [[impl-1 impl-2] (await (prim/parallel
                                                                    (prim/spawn! coder (str task " - approach 1") {:parent-ctx group-ctx})
                                                                    (prim/spawn! coder (str task " - approach 2") {:parent-ctx group-ctx})))
                                           review (prim/ask! (agent/make-agent {:name "reviewer"})
                                                             (str "Compare: " (:result impl-1) " vs " (:result impl-2))
                                                             {:parent-ctx group-ctx})]
                                       {:status :complete
                                        :result (:result review)
                                        :complexity :complex})

                                     ;; Default: medium complexity
                                     (let [code (prim/ask! coder task {:parent-ctx group-ctx})
                                           tests (prim/ask! tester (:result code) {:parent-ctx group-ctx})]
                                       {:status :complete
                                        :result (:result tests)
                                        :complexity :medium}))))}))

  ;; Execute adaptive workflow
  (binding [rtc/*execution-context* rt]
    (def adaptive-result
      (grp-exec/ask-group! adaptive-team "Build a distributed cache")))

  ;; Check result
  (:complexity adaptive-result))
