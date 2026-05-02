(ns dvergr.group.execution
  "Group execution logic - running workflows and coordinating agents."
  (:require [org.replikativ.spindel.engine.core :as rtc]
            [org.replikativ.spindel.core :refer [spin]]
            [org.replikativ.spindel.core :refer [await]]
            [dvergr.chat.context :as chat-ctx]
            [dvergr.group.core :as group]
            [dvergr.agent.task :as prim]))

;; ============================================================================
;; Default Workflows
;; ============================================================================

(defn default-parallel-workflow
  "Default workflow: run all participants in parallel. Returns a Spin.

   This is used when no workflow or structure is specified."
  [task group-ctx participants]
  (if (empty? participants)
    ;; Return immediate spin for empty case
    (spin {:status :complete
           :result "No participants in group"
           :results []})
    ;; Return spin that awaits parallel execution
    (spin
      (let [;; Spawn all participants in parallel
            spawn-results (mapv #(prim/spawn! % task {:parent-ctx group-ctx})
                                participants)
            ;; Await all results
            results (await (apply prim/parallel spawn-results))]
        {:status :complete
         :result (str "Completed with " (count results) " participants")
         :results results}))))

;; ============================================================================
;; Group Execution
;; ============================================================================

(defn- run-group-workflow-spin
  "Execute a group's workflow. Returns a Spin.

   This wraps the workflow execution in a spin so groups can be
   composed with ask!/spawn! like regular agents."
  [group task {:keys [on-message]}]
  (spin
    (let [participants (group/get-participants group)
          group-ctx (group/get-context group)
          workflow (:workflow group)]

      ;; Add task to group's shared context
      (chat-ctx/add-message! group-ctx
                             {:role :user
                              :content task})

      (when on-message
        (on-message {:role :user :content task}))

      (cond
        ;; Check if group is active
        (not (group/active? group))
        {:status (group/get-status group)
         :result (str "Group is " (name (group/get-status group)))}

        ;; Check budget
        (chat-ctx/budget-exceeded? group-ctx)
        (do
          (group/complete! group)
          {:status :budget-exceeded
           :result "Group budget exceeded"})

        ;; Custom workflow provided - workflows return spins
        workflow
        (let [result (await (workflow task group-ctx))]
          (group/complete! group)
          result)

        ;; No workflow - use default parallel execution
        :else
        (let [result (await (default-parallel-workflow task group-ctx participants))]
          (group/complete! group)
          result)))))

;; ============================================================================
;; Public API (makes groups work with ask!/spawn!)
;; ============================================================================

(defn ask-group!
  "Ask a group to perform a task. Returns a Spin.

   Use @(ask-group! ...) at REPL to block, or (await (ask-group! ...)) inside spin.

   Example:
     @(ask-group! dev-team \"Implement user authentication\")"
  ([group task] (ask-group! group task {}))
  ([group task opts]
   (run-group-workflow-spin group task opts)))

(defn spawn-group!
  "Start a group on a task. Returns a Spin immediately.

   This allows groups to be used in parallel composition.

   Example:
     (binding [rtc/*execution-context* rt]
       (def results
         (await (prim/parallel
                  (spawn-group! team-a \"Implement feature A\")
                  (spawn-group! team-b \"Implement feature B\")))))"
  ([group task] (spawn-group! group task {}))
  ([group task opts]
   (run-group-workflow-spin group task opts)))

;; ============================================================================
;; Workflow Helpers
;; ============================================================================

(defn sequential-workflow
  "Helper: Create a sequential workflow from a list of agents. Returns a Spin.

   Each agent runs after the previous one completes, receiving
   the previous result as input.

   Example:
     (make-group {:name \"pipeline\"
                  :participants [researcher coder tester]
                  :workflow (sequential-workflow [researcher coder tester])})"
  [agents]
  (fn [task group-ctx]
    ;; Return a spin that sequences through agents
    (spin
      (loop [[agent & rest] agents
             current-task task
             results []]
        (if-not agent
          {:status :complete
           :result (str "Completed " (count results) " steps")
           :results results}
          (let [result (await (prim/ask! agent current-task {:parent-ctx group-ctx}))
                next-task (or (:result result) current-task)]
            (recur rest next-task (conj results result))))))))

(defn parallel-workflow
  "Helper: Create a parallel workflow from a list of agents. Returns a Spin.

   All agents run concurrently on the same task.

   Example:
     (make-group {:name \"experts\"
                  :participants [expert-a expert-b expert-c]
                  :workflow (parallel-workflow [expert-a expert-b expert-c])})"
  [agents]
  (fn [task group-ctx]
    ;; default-parallel-workflow already returns a spin
    (default-parallel-workflow task group-ctx agents)))
