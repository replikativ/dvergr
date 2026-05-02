(ns dvergr.tools.approval
  "Tools for agent approval workflows - dependencies and plans.

   These tools pause agent execution and require human manager approval
   before continuing. They enable safe agent operation with human oversight."
  (:require [clojure.repl.deps :as deps]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

;; ============================================================================
;; Approval Request Storage
;; ============================================================================

(def pending-approvals
  "Atom storing pending approval requests.
   Map of request-id -> {:type :dependency/:plan
                         :request {...}
                         :status :pending/:approved/:rejected
                         :response ...}

   NOTE: This is intentionally global state for human-in-the-loop workflows.
   Managers need to see requests from all agent sessions via REPL commands
   like (list-pending-approvals) and (approve-dependency! request-id).
   If session-scoped approvals are needed, consider passing approval storage
   via the agent's ChatContext instead."
  (atom {}))

;; ============================================================================
;; Approval Cache (Always-Allow patterns)
;; ============================================================================

(def approval-cache
  "Atom storing cached approval patterns.
   Map of tool-name -> set of approved patterns.

   When a user approves with :scope :always, the pattern is cached here
   so subsequent identical requests are auto-approved without prompting."
  (atom {}))

(defn cache-approval!
  "Cache an approval pattern for future auto-approval.

   Args:
     tool-name - Tool that was approved
     pattern   - Approval pattern (e.g., command prefix, file path glob)
     scope     - :once (no caching) or :always (cache for session)"
  [tool-name pattern scope]
  (when (= :always scope)
    (swap! approval-cache update tool-name (fnil conj #{}) pattern)))

(defn check-approval-cache
  "Check if a tool invocation matches a cached approval.

   Args:
     tool-name - Tool being invoked
     input     - Tool input map

   Returns true if auto-approved, false if needs explicit approval."
  [tool-name input]
  (when-let [patterns (get @approval-cache tool-name)]
    (let [input-str (pr-str input)]
      (some (fn [pattern]
              (cond
                (string? pattern) (.startsWith input-str pattern)
                (instance? java.util.regex.Pattern pattern) (re-find pattern input-str)
                (fn? pattern) (pattern input)
                :else false))
            patterns))))

(defn clear-approval-cache!
  "Clear all cached approvals. Call on session end."
  []
  (reset! approval-cache {}))

(defn- generate-request-id []
  (str "req-" (System/currentTimeMillis) "-" (rand-int 10000)))

;; ============================================================================
;; Dependency Approval
;; ============================================================================

(defn request-dependency
  "Request a library be added to project dependencies.

   This pauses agent execution and requires manager approval.
   Once approved, the library is dynamically loaded into the REPL.

   Args:
     lib - Maven coordinate string (e.g., 'buddy/buddy-auth')
     version - Version string (e.g., '3.0.1')
     justification - Why this library is needed
     alternatives-considered - What alternatives were considered

   Returns:
     {:status :pending :request-id req-id} (agent should wait)
     {:status :approved :message ...} (after approval)
     {:status :rejected :reason ...} (after rejection)"
  [{:keys [lib version justification alternatives-considered]}]
  (let [request-id (generate-request-id)
        lib-sym (symbol lib)
        request {:type :dependency
                 :lib lib-sym
                 :version version
                 :mvn-coord {lib-sym {:mvn/version version}}
                 :justification justification
                 :alternatives-considered alternatives-considered
                 :requested-at (java.util.Date.)}]

    ;; Store request
    (swap! pending-approvals assoc request-id
           {:type :dependency
            :request request
            :status :pending
            :response nil})

    ;; Return pending status (agent will see this and should wait)
    {:status :pending
     :request-id request-id
     :message (str "Dependency request submitted: " lib " " version)
     :instructions "Your request has been submitted to the manager for approval.
Please wait for approval before continuing. You can check the status or proceed
with other work that doesn't require this dependency."}))

(defn approve-dependency!
  "Approve a dependency request and dynamically load it.

   This is called by the human manager in the REPL.

   Args:
     request-id - The request ID from pending-approvals

   Returns:
     {:success true/false :message ...}"
  [request-id]
  (if-let [approval-req (get @pending-approvals request-id)]
    (let [{:keys [mvn-coord lib]} (:request approval-req)]
      (try
        ;; Dynamically add dependency to running REPL
        (deps/add-libs mvn-coord)

        ;; Update approval status
        (swap! pending-approvals assoc-in [request-id :status] :approved)
        (swap! pending-approvals assoc-in [request-id :response]
               {:approved-at (java.util.Date.)
                :message (str "Dependency " lib " loaded successfully")})

        {:success true
         :message (str "✓ Approved and loaded: " lib)
         :lib lib
         :available-in-sci? false  ; Note: Needs SCI context update separately
         :note "Library is now available in REPL. To make it available in agent's
SCI context, you'll need to add it to the SCI namespace bindings."}

        (catch Exception e
          (swap! pending-approvals assoc-in [request-id :status] :rejected)
          (swap! pending-approvals assoc-in [request-id :response]
                 {:rejected-at (java.util.Date.)
                  :reason (.getMessage e)})
          {:success false
           :error (.getMessage e)
           :message (str "✗ Failed to load dependency: " (.getMessage e))})))
    {:success false
     :error (str "Request not found: " request-id)}))

(defn reject-dependency!
  "Reject a dependency request.

   Args:
     request-id - The request ID
     reason - Why it was rejected

   Returns:
     {:success true :message ...}"
  [request-id reason]
  (if-let [approval-req (get @pending-approvals request-id)]
    (do
      (swap! pending-approvals assoc-in [request-id :status] :rejected)
      (swap! pending-approvals assoc-in [request-id :response]
             {:rejected-at (java.util.Date.)
              :reason reason})
      {:success true
       :message (str "✗ Rejected: " reason)})
    {:success false
     :error (str "Request not found: " request-id)}))

;; ============================================================================
;; Plan Approval
;; ============================================================================

(defn request-plan-review
  "Submit implementation plan for manager review before coding.

   Use this for complex features to ensure approach is sound.

   Args:
     approach - High-level approach description
     files-to-modify - List of files that will be created/modified
     dependencies-needed - List of dependencies needed (if any)
     risks - Known risks or concerns
     alternatives - Alternative approaches considered

   Returns:
     {:status :pending :request-id req-id} (agent should wait)
     {:status :approved :feedback ...} (after approval)
     {:status :rejected :feedback ...} (after rejection)"
  [{:keys [approach files-to-modify dependencies-needed risks alternatives]}]
  (let [request-id (generate-request-id)
        request {:type :plan
                 :approach approach
                 :files-to-modify (vec files-to-modify)
                 :dependencies-needed (vec dependencies-needed)
                 :risks (or risks "None identified")
                 :alternatives (or alternatives "None considered")
                 :requested-at (java.util.Date.)}]

    ;; Store request
    (swap! pending-approvals assoc request-id
           {:type :plan
            :request request
            :status :pending
            :response nil})

    ;; Return pending status
    {:status :pending
     :request-id request-id
     :message "Implementation plan submitted for review"
     :instructions "Your plan has been submitted to the manager. Please wait for
approval and feedback before implementing. Use this time to prepare, research
APIs, or work on documentation."}))

(defn approve-plan!
  "Approve an implementation plan.

   Args:
     request-id - The request ID
     feedback - Optional feedback/guidance for the agent

   Returns:
     {:success true :message ...}"
  [request-id & {:keys [feedback]}]
  (if-let [approval-req (get @pending-approvals request-id)]
    (do
      (swap! pending-approvals assoc-in [request-id :status] :approved)
      (swap! pending-approvals assoc-in [request-id :response]
             {:approved-at (java.util.Date.)
              :feedback (or feedback "Plan approved. Proceed with implementation.")})
      {:success true
       :message "✓ Plan approved"
       :feedback (or feedback "Plan approved. Proceed with implementation.")})
    {:success false
     :error (str "Request not found: " request-id)}))

(defn reject-plan!
  "Reject an implementation plan with feedback.

   Args:
     request-id - The request ID
     feedback - Why the plan was rejected and what needs to change

   Returns:
     {:success true :message ...}"
  [request-id feedback]
  (if-let [approval-req (get @pending-approvals request-id)]
    (do
      (swap! pending-approvals assoc-in [request-id :status] :rejected)
      (swap! pending-approvals assoc-in [request-id :response]
             {:rejected-at (java.util.Date.)
              :feedback feedback})
      {:success true
       :message "✗ Plan rejected"
       :feedback feedback})
    {:success false
     :error (str "Request not found: " request-id)}))

;; ============================================================================
;; Status Queries
;; ============================================================================

(defn get-approval-status
  "Check status of an approval request.

   Args:
     request-id - The request ID

   Returns:
     {:status :pending/:approved/:rejected :response ...} or nil"
  [request-id]
  (get @pending-approvals request-id))

(defn list-pending-approvals
  "List all pending approval requests.

   Returns:
     Vector of {:request-id ... :type ... :request ...}"
  []
  (->> @pending-approvals
       (filter (fn [[_ v]] (= :pending (:status v))))
       (map (fn [[id v]]
              (assoc (:request v)
                     :request-id id
                     :status (:status v))))
       vec))

(defn clear-completed-approvals!
  "Clear all approved/rejected requests from memory.

   Keeps only pending requests."
  []
  (swap! pending-approvals
         (fn [approvals]
           (into {} (filter (fn [[_ v]] (= :pending (:status v))) approvals)))))

;; ============================================================================
;; Tool Definitions
;; ============================================================================

(def request-dependency-tool
  {:name "request_dependency"
   :description "Request a library be added to project dependencies.

Manager will review and approve. If approved, it will be available in your
next turn. Use this when you need external libraries that aren't currently
available.

Example use cases:
- Need buddy/buddy-auth for JWT token validation
- Need clj-http for making HTTP requests
- Need cheshire for advanced JSON handling"
   :parameters {:type "object"
                :properties {:lib {:type "string"
                                   :description "Maven coordinate like 'buddy/buddy-auth' or 'clj-http/clj-http'"}
                             :version {:type "string"
                                      :description "Version string like '3.0.1' or '3.12.3'"}
                             :justification {:type "string"
                                           :description "Why you need this library - be specific about what functionality you need"}
                             :alternatives_considered {:type "string"
                                                      :description "What alternatives you considered and why they won't work"}}
                :required ["lib" "version" "justification"]}
   :execute (fn [params _ctx]
              (try
                (let [result (request-dependency params)]
                  {:type (if (= :pending (:status result)) :success :error)
                   :content (:message result)
                   :metadata result})
                (catch Exception e
                  {:type :error
                   :error (str "Request dependency failed: " (.getMessage e))})))})

(def request-plan-review-tool
  {:name "request_plan_review"
   :description "Submit your implementation plan for manager review before coding.

Use this for complex features or when you're uncertain about the approach.
Manager will provide feedback or approval. This helps ensure you're on the
right track before investing time in implementation.

When to use:
- Complex features touching multiple files
- When you need architectural guidance
- When you're unsure about the best approach
- Before making significant changes to core systems"
   :parameters {:type "object"
                :properties {:approach {:type "string"
                                       :description "High-level description of your implementation approach"}
                             :files_to_modify {:type "array"
                                              :items {:type "string"}
                                              :description "List of files you'll create or modify"}
                             :dependencies_needed {:type "array"
                                                  :items {:type "string"}
                                                  :description "List of dependencies you'll need (if any)"}
                             :risks {:type "string"
                                    :description "Known risks, concerns, or unknowns"}
                             :alternatives {:type "string"
                                          :description "Other approaches you considered"}}
                :required ["approach" "files_to_modify"]}
   :execute (fn [params _ctx]
              (try
                (let [result (request-plan-review params)]
                  {:type (if (= :pending (:status result)) :success :error)
                   :content (:message result)
                   :metadata result})
                (catch Exception e
                  {:type :error
                   :error (str "Request plan review failed: " (.getMessage e))})))})
