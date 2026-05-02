(ns dvergr.agent.code-reviewer
  "Code review agent - hybrid static analysis + optional LLM review.

   Two-stage review process:
   1. Static Analysis (clj-kondo) - Fast, deterministic linting
   2. LLM Review (optional) - Deeper semantic analysis

   Use this post-hoc after agent implementations to catch issues."
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [dvergr.model.chat :as model-chat]))

;; ============================================================================
;; Static Analysis (clj-kondo)
;; ============================================================================

(defn run-clj-kondo
  "Run clj-kondo on a file or directory.

   Args:
     path - File or directory path to lint
     config - Optional clj-kondo config map

   Returns:
     Map with :findings (vector of issue maps) and :summary"
  ([path] (run-clj-kondo path nil))
  ([path config]
   (try
     (let [;; Prepare clj-kondo command
           cmd (cond-> ["clojure" "-M:kondo" "--lint" path "--config" "{:output {:format :edn}}"]
                 config (concat ["--config" (pr-str config)]))
           ;; Run clj-kondo
           result (apply shell/sh cmd)
           ;; Parse EDN output
           output (:out result)]
       (if (str/blank? output)
         {:findings []
          :summary {:error 0 :warning 0 :info 0}
          :raw-output output}
         (try
           (let [data (edn/read-string output)]
             {:findings (:findings data)
              :summary (:summary data)
              :raw-output output})
           (catch Exception e
             {:error (str "Failed to parse clj-kondo output: " (.getMessage e))
              :raw-output output}))))
     (catch Exception e
       {:error (str "Failed to run clj-kondo: " (.getMessage e))}))))

(defn filter-findings
  "Filter clj-kondo findings by level or type.

   Args:
     findings - Vector of finding maps from clj-kondo
     options - Map with optional :level (e.g., :error) or :type filters

   Returns:
     Filtered vector of findings"
  [findings {:keys [level type]}]
  (cond->> findings
    level (filter #(= (:level %) level))
    type (filter #(= (:type %) type))))

(defn findings-by-severity
  "Group findings by severity level.

   Args:
     findings - Vector of finding maps

   Returns:
     Map of {:error [...] :warning [...] :info [...]}"
  [findings]
  (group-by :level findings))

;; ============================================================================
;; Issue Classification
;; ============================================================================

(def ^:private critical-issues
  "Issue types that should block merging."
  #{:unresolved-symbol
    :unresolved-var
    :invalid-arity
    :type-mismatch})

(def ^:private style-issues
  "Issue types that are style/convention related."
  #{:unused-binding
    :unused-private-var
    :missing-docstring
    :redundant-do})

(defn classify-findings
  "Classify findings into categories.

   Args:
     findings - Vector of finding maps

   Returns:
     Map with :critical, :bugs, :style, :other"
  [findings]
  (let [by-type (group-by :type findings)]
    {:critical (filter #(contains? critical-issues (:type %)) findings)
     :bugs (filter #(and (= (:level %) :error)
                        (not (contains? critical-issues (:type %))))
                   findings)
     :style (filter #(contains? style-issues (:type %)) findings)
     :other (filter #(and (not (contains? critical-issues (:type %)))
                         (not (contains? style-issues (:type %)))
                         (not= (:level %) :error))
                    findings)}))

;; ============================================================================
;; LLM Review (Optional Deep Analysis)
;; ============================================================================

(def ^:private code-review-prompt
  "You are a code reviewer analyzing Clojure code.

Review the code for:
1. Semantic issues (logic errors, edge cases)
2. Performance problems
3. Security vulnerabilities
4. Maintainability concerns
5. Best practice violations

Focus on issues that static analysis tools like clj-kondo might miss.

Respond in EDN format:
{:issues [{:severity :critical/:high/:medium/:low
           :category :logic/:performance/:security/:style
           :description \"Brief description\"
           :suggestion \"How to fix\"}]
 :overall-assessment \"Brief overall assessment\"}

If no issues found, return {:issues [] :overall-assessment \"Code looks good\"}.")

(defn llm-review
  "Perform LLM-based code review.

   Args:
     code - Code string to review
     options - Map with:
               :model - LLM model (default: qwen3-coder)
               :include-kondo - Include clj-kondo results in context

   Returns:
     Map with :issues and :overall-assessment"
  ([code] (llm-review code {}))
  ([code {:keys [model include-kondo kondo-findings]
          :or {model "accounts/fireworks/models/qwen3-coder-480b-a35b-instruct"}}]
   (try
     (let [;; Build prompt with optional clj-kondo context
           user-prompt (str "Review this Clojure code:\n\n"
                           "```clojure\n"
                           code
                           "\n```\n"
                           (when (and include-kondo kondo-findings)
                             (str "\nStatic analysis (clj-kondo) found:\n"
                                  (pr-str kondo-findings)
                                  "\n")))
           ;; Call LLM
           response (model-chat/chat
                      [{:role "system" :content code-review-prompt}
                       {:role "user" :content user-prompt}]
                      {:model model
                       :max-tokens 2000})
           ;; Parse response
           content (:content response)
           ;; Try to extract EDN
           parsed (try
                    (-> content
                        (str/replace #"```edn\s*" "")
                        (str/replace #"```clojure\s*" "")
                        (str/replace #"```\s*" "")
                        str/trim
                        edn/read-string)
                    (catch Exception e
                      {:issues []
                       :overall-assessment (str "Failed to parse LLM response: " content)
                       :parse-error (.getMessage e)}))]
       parsed)
     (catch Exception e
       {:error (str "LLM review failed: " (.getMessage e))}))))

;; ============================================================================
;; Combined Review
;; ============================================================================

(defn review-code
  "Perform comprehensive code review (static + optional LLM).

   Args:
     path - File or directory path
     options - Map with:
               :llm-review? - Run LLM review (default false)
               :model - LLM model for review
               :max-file-size - Max file size for LLM review (bytes, default 50000)

   Returns:
     Map with:
       :static-analysis - clj-kondo results
       :llm-review - LLM review results (if enabled)
       :classification - Classified findings
       :recommendations - What to do next"
  ([path] (review-code path {}))
  ([path {:keys [llm-review? model max-file-size]
          :or {llm-review? false
               max-file-size 50000}
          :as options}]
   (let [;; Run static analysis
         kondo-result (run-clj-kondo path)
         findings (:findings kondo-result)
         classified (classify-findings findings)

         ;; Optionally run LLM review
         llm-result (when (and llm-review?
                              (.isFile (io/file path))
                              (< (.length (io/file path)) max-file-size))
                      (let [code (slurp path)]
                        (llm-review code (assoc options
                                                :include-kondo true
                                                :kondo-findings findings))))

         ;; Generate recommendations
         has-critical (seq (:critical classified))
         has-bugs (seq (:bugs classified))
         has-llm-critical (and llm-result
                              (seq (filter #(= (:severity %) :critical)
                                          (:issues llm-result))))

         recommendation (cond
                          (or has-critical has-llm-critical)
                          {:action :block
                           :message "Critical issues found - do not merge"}

                          has-bugs
                          {:action :review-required
                           :message "Bugs found - review and fix before merging"}

                          (> (count findings) 10)
                          {:action :cleanup-recommended
                           :message (str (count findings) " style/minor issues - cleanup recommended")}

                          (seq findings)
                          {:action :approve-with-notes
                           :message (str (count findings) " minor issues - acceptable")}

                          :else
                          {:action :approve
                           :message "No issues found - looks good!"})]

     {:static-analysis kondo-result
      :llm-review llm-result
      :classification classified
      :recommendation recommendation})))

;; ============================================================================
;; Reporting
;; ============================================================================

(defn print-review-report
  "Print human-readable code review report.

   Args:
     review - Review result from review-code"
  [review]
  (let [{:keys [static-analysis llm-review classification recommendation]} review]
    (println "\n" (apply str (repeat 60 "=")) "\n")
    (println " CODE REVIEW REPORT\n")
    (println (apply str (repeat 60 "=")) "\n")

    ;; Summary
    (let [summary (:summary static-analysis)]
      (println "Static Analysis (clj-kondo):")
      (println (str "  Errors:   " (:error summary 0)))
      (println (str "  Warnings: " (:warning summary 0)))
      (println (str "  Info:     " (:info summary 0)))
      (println))

    ;; Critical issues
    (when (seq (:critical classification))
      (println "CRITICAL ISSUES:")
      (doseq [issue (take 5 (:critical classification))]
        (println (str "  " (:type issue) " at " (:filename issue) ":" (:row issue)))
        (println (str "    " (:message issue))))
      (println))

    ;; Bugs
    (when (seq (:bugs classification))
      (println "BUGS:")
      (doseq [bug (take 5 (:bugs classification))]
        (println (str "  " (:type bug) " at " (:filename bug) ":" (:row bug)))
        (println (str "    " (:message bug))))
      (println))

    ;; LLM review
    (when llm-review
      (println "LLM Review:")
      (println (str "  Assessment: " (:overall-assessment llm-review)))
      (when (seq (:issues llm-review))
        (println "  Issues:")
        (doseq [issue (take 3 (:issues llm-review))]
          (println (str "    " (name (:severity issue)) ": " (:description issue)))
          (when (:suggestion issue)
            (println (str "      -> " (:suggestion issue))))))
      (println))

    ;; Recommendation
    (println "RECOMMENDATION:")
    (println (str "  " (case (:action recommendation)
                         :block "BLOCK - "
                         :review-required "REVIEW REQUIRED - "
                         :cleanup-recommended "CLEANUP RECOMMENDED - "
                         :approve-with-notes "APPROVE WITH NOTES - "
                         :approve "APPROVE - "
                         "")
                 (:message recommendation)))
    (println)

    (println (apply str (repeat 60 "=")) "\n")))

;; ============================================================================
;; Quick Checks
;; ============================================================================

(defn quick-check
  "Quick pass/fail check for code.

   Args:
     path - File or directory path

   Returns:
     Boolean - true if no critical issues, false otherwise"
  [path]
  (let [review (review-code path {:llm-review? false})]
    (= (:action (:recommendation review)) :approve)))

(defn suggest-improvements
  "Get specific improvement suggestions for code.

   Args:
     path - File or directory path

   Returns:
     Vector of improvement maps with :issue, :fix, :priority"
  [path]
  (let [review (review-code path {:llm-review? false})
        findings (:findings (:static-analysis review))]
    (map (fn [finding]
           {:issue (:message finding)
            :location (str (:filename finding) ":" (:row finding) ":" (:col finding))
            :type (:type finding)
            :priority (case (:level finding)
                       :error :high
                       :warning :medium
                       :info :low)})
         findings)))
