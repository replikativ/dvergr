(ns dvergr.analysis.reporting
  "Unified reporting interface for analyzing agent activity, budgets, and performance.

   Each report function returns data (for programmatic use) and has a
   corresponding print- function for human-readable REPL output."
  (:require [dvergr.analysis.queries :as q]
            [dvergr.chat.accounting :as acct]
            [clojure.string :as str]))

;; ============================================================================
;; Formatting Helpers
;; ============================================================================

(defn- format-microdollars
  "Format microdollars as dollars with appropriate precision."
  [microdollars]
  (let [dollars (/ microdollars 1000000.0)]
    (cond
      (>= dollars 1.0) (format "$%.2f" dollars)
      (>= dollars 0.01) (format "$%.4f" dollars)
      :else (format "$%.6f" dollars))))

(defn- format-percent
  "Format percentage with 1 decimal place."
  [pct]
  (format "%.1f%%" pct))

(defn- format-timestamp
  "Format timestamp for display."
  [ts]
  (if ts
    (str ts)
    "N/A"))

(defn- table-row
  "Format a table row with padding."
  [col-widths values]
  (str "  "
       (str/join " | "
                 (map (fn [width val]
                        (let [s (str val)]
                          (if (< (count s) width)
                            (str s (apply str (repeat (- width (count s)) " ")))
                            s)))
                      col-widths
                      values))))

(defn- separator
  "Create a separator line for tables."
  [col-widths]
  (str "  "
       (str/join "-+-"
                 (map #(apply str (repeat % "-")) col-widths))))

;; ============================================================================
;; Budget Analysis Report
;; ============================================================================

(defn budget-analysis-report
  "Analyze budget usage for a chat.

   Args:
     db - Datahike db value
     chat-id - Chat UUID

   Returns:
     Map with:
       :summary - Overall budget status
       :by-resource-type - Cost breakdown by type
       :by-model - Cost breakdown by model
       :timeline - Spending over time
       :warnings - Budget warnings if any"
  [db chat-id]
  (let [summary (q/summarize-chat db chat-id)
        budget (:budget summary)
        cost-breakdown (:cost-breakdown summary)
        cost-by-model (q/get-cost-by-model db chat-id)
        ledger (q/get-ledger-entries db chat-id)
        warnings (cond-> []
                   (>= (:percent-used budget) 90.0)
                   (conj {:level :critical
                          :message (str "Budget 90%+ used: "
                                       (format-percent (:percent-used budget)))})

                   (>= (:percent-used budget) 75.0)
                   (conj {:level :warning
                          :message (str "Budget 75%+ used: "
                                       (format-percent (:percent-used budget)))}))]
    {:summary budget
     :by-resource-type cost-breakdown
     :by-model cost-by-model
     :timeline (map #(select-keys % [:ledger/timestamp
                                      :ledger/resource-type
                                      :ledger/cost-microdollars])
                    ledger)
     :warnings warnings}))

(defn print-budget-analysis-report
  "Print human-readable budget analysis report.

   Args:
     db - Datahike db value
     chat-id - Chat UUID"
  [db chat-id]
  (let [report (budget-analysis-report db chat-id)
        {:keys [summary by-resource-type by-model warnings]} report]
    (println "\n" (apply str (repeat 60 "=")) "\n")
    (println " BUDGET ANALYSIS REPORT\n")
    (println (apply str (repeat 60 "=")) "\n")

    ;; Summary
    (println "Summary:")
    (println (str "  Total Budget:  " (format-microdollars (:total summary))))
    (println (str "  Used:          " (format-microdollars (:used summary))
                  " (" (format-percent (:percent-used summary)) ")"))
    (println (str "  Remaining:     " (format-microdollars (:remaining summary))))
    (println)

    ;; Warnings
    (when (seq warnings)
      (println "⚠️  Warnings:")
      (doseq [w warnings]
        (println (str "  " (case (:level w)
                             :critical "🔴 CRITICAL: "
                             :warning  "🟡 WARNING:  ") (:message w))))
      (println))

    ;; Cost breakdown by resource type
    (when (seq by-resource-type)
      (println "Cost by Resource Type:")
      (let [sorted (sort-by val > by-resource-type)]
        (doseq [[rt cost] sorted]
          (println (str "  " (name rt) ": " (format-microdollars cost)))))
      (println))

    ;; Cost breakdown by model
    (when (seq by-model)
      (println "Cost by Model:")
      (let [sorted (sort-by val > by-model)]
        (doseq [[model cost] sorted]
          (println (str "  " model ": " (format-microdollars cost)))))
      (println))

    (println (apply str (repeat 60 "=")) "\n")))

;; ============================================================================
;; Tool Effectiveness Report
;; ============================================================================

(defn tool-effectiveness-report
  "Analyze tool usage and effectiveness.

   Args:
     db - Datahike db value
     chat-id - Chat UUID

   Returns:
     Map with:
       :overall - Overall statistics
       :by-tool - Per-tool success rates
       :failures - Failed tool calls with details"
  [db chat-id]
  (let [stats (q/get-tool-usage-stats db chat-id)
        failures (q/get-failed-tool-calls db chat-id)
        by-tool (reduce (fn [acc [tool-name _count]]
                          (let [all-calls (filter #(= (:tool-call/name %) tool-name)
                                                 (q/get-tool-calls db chat-id))
                                successes (count (filter #(= (:tool-call/status %) :success)
                                                        all-calls))
                                errors (count (filter #(= (:tool-call/status %) :error)
                                                     all-calls))
                                total (count all-calls)]
                            (assoc acc tool-name
                                   {:total total
                                    :success successes
                                    :error errors
                                    :success-rate (if (pos? total)
                                                   (* 100.0 (/ successes total))
                                                   0.0)})))
                        {}
                        (:by-name stats))]
    {:overall stats
     :by-tool by-tool
     :failures failures}))

(defn print-tool-effectiveness-report
  "Print human-readable tool effectiveness report.

   Args:
     db - Datahike db value
     chat-id - Chat UUID"
  [db chat-id]
  (let [report (tool-effectiveness-report db chat-id)
        {:keys [overall by-tool failures]} report]
    (println "\n" (apply str (repeat 60 "=")) "\n")
    (println " TOOL EFFECTIVENESS REPORT\n")
    (println (apply str (repeat 60 "=")) "\n")

    ;; Overall stats
    (println "Overall:")
    (println (str "  Total Tool Calls: " (:total-calls overall)))
    (println (str "  Success Rate:     " (format-percent (* 100.0 (- 1.0 (:error-rate overall))))))
    (println)

    ;; By tool
    (when (seq by-tool)
      (println "By Tool:")
      (println (separator [20 8 8 8 12]))
      (println (table-row [20 8 8 8 12]
                          ["Tool" "Total" "Success" "Error" "Success %"]))
      (println (separator [20 8 8 8 12]))
      (doseq [[tool-name stats] (sort-by (comp :total val) > by-tool)]
        (println (table-row [20 8 8 8 12]
                            [(name tool-name)
                             (:total stats)
                             (:success stats)
                             (:error stats)
                             (format-percent (:success-rate stats))])))
      (println))

    ;; Failures
    (when (seq failures)
      (println (str "Recent Failures (" (count failures) " total):"))
      (doseq [[tool-name error timestamp] (take 5 failures)]
        (println (str "  " tool-name ": " error))
        (println (str "    at " (format-timestamp timestamp))))
      (when (> (count failures) 5)
        (println (str "  ... and " (- (count failures) 5) " more")))
      (println))

    (println (apply str (repeat 60 "=")) "\n")))

;; ============================================================================
;; Agent Performance Report
;; ============================================================================

(defn agent-performance-report
  "Analyze agent performance (messages, turns, efficiency).

   Args:
     db - Datahike db value
     chat-id - Chat UUID

   Returns:
     Map with:
       :messages - Message statistics
       :efficiency - Token/cost efficiency metrics
       :timeline - Activity over time"
  [db chat-id]
  (let [summary (q/summarize-chat db chat-id)
        messages (q/get-chat-messages db chat-id)
        user-msgs (filter #(= (:message/role %) :user) messages)
        assistant-msgs (filter #(= (:message/role %) :assistant) messages)
        turns (count user-msgs)
        avg-tokens-per-turn (if (pos? turns)
                              (/ (double (get-in summary [:messages :total-tokens])) turns)
                              0.0)
        cost-per-turn (if (pos? turns)
                        (/ (double (get-in summary [:budget :used])) turns)
                        0.0)
        timeline (q/get-activity-timeline db chat-id)]
    {:messages {:total (get-in summary [:messages :total])
                :by-role (get-in summary [:messages :by-role])
                :turns turns
                :avg-tokens-per-turn avg-tokens-per-turn}
     :efficiency {:cost-per-turn cost-per-turn
                  :total-cost (get-in summary [:budget :used])
                  :tokens-per-dollar (if (pos? (get-in summary [:budget :used]))
                                       (/ (double (get-in summary [:messages :total-tokens]))
                                          (/ (get-in summary [:budget :used]) 1000000.0))
                                       0.0)}
     :tools (get-in summary [:tools])
     :timeline (map #(select-keys % [:type :timestamp]) timeline)}))

(defn print-agent-performance-report
  "Print human-readable agent performance report.

   Args:
     db - Datahike db value
     chat-id - Chat UUID"
  [db chat-id]
  (let [report (agent-performance-report db chat-id)
        {:keys [messages efficiency tools]} report]
    (println "\n" (apply str (repeat 60 "=")) "\n")
    (println " AGENT PERFORMANCE REPORT\n")
    (println (apply str (repeat 60 "=")) "\n")

    ;; Messages
    (println "Messages:")
    (println (str "  Total:        " (:total messages)))
    (println (str "  User:         " (get-in messages [:by-role :user] 0)))
    (println (str "  Assistant:    " (get-in messages [:by-role :assistant] 0)))
    (println (str "  System:       " (get-in messages [:by-role :system] 0)))
    (println (str "  Turns:        " (:turns messages)))
    (println (str "  Avg Tokens:   " (format "%.0f" (:avg-tokens-per-turn messages))))
    (println)

    ;; Efficiency
    (println "Efficiency:")
    (println (str "  Total Cost:        " (format-microdollars (:total-cost efficiency))))
    (println (str "  Cost per Turn:     " (format-microdollars (:cost-per-turn efficiency))))
    (println (str "  Tokens per Dollar: " (format "%.0f" (:tokens-per-dollar efficiency))))
    (println)

    ;; Tools
    (println "Tools:")
    (println (str "  Total Calls:  " (:total-calls tools)))
    (println (str "  Error Rate:   " (format-percent (* 100.0 (:error-rate tools)))))
    (println)

    (println (apply str (repeat 60 "=")) "\n")))

;; ============================================================================
;; Conversation Summary Report
;; ============================================================================

(defn conversation-summary-report
  "High-level summary of a conversation.

   Args:
     db - Datahike db value
     chat-id - Chat UUID

   Returns:
     Map with overview of the conversation"
  [db chat-id]
  (let [summary (q/summarize-chat db chat-id)
        recent (q/get-recent-messages db chat-id 5)
        entities (q/get-entities db chat-id)
        top-entities (take 10 (sort-by second > entities))]
    {:overview summary
     :recent-messages (map #(select-keys % [:message/role :message/timestamp])
                          recent)
     :top-entities top-entities}))

(defn print-conversation-summary-report
  "Print human-readable conversation summary.

   Args:
     db - Datahike db value
     chat-id - Chat UUID"
  [db chat-id]
  (let [report (conversation-summary-report db chat-id)
        {:keys [overview top-entities]} report]
    (println "\n" (apply str (repeat 60 "=")) "\n")
    (println " CONVERSATION SUMMARY\n")
    (println (apply str (repeat 60 "=")) "\n")

    (println (str "Messages: " (get-in overview [:messages :total])))
    (println (str "Tokens:   " (get-in overview [:messages :total-tokens])))
    (println (str "Cost:     " (format-microdollars (get-in overview [:budget :used]))))
    (println (str "Budget:   " (format-percent (get-in overview [:budget :percent-used])) " used"))
    (println (str "Tools:    " (get-in overview [:tools :total-calls]) " calls"))
    (println)

    (when (seq top-entities)
      (println "Top Entities Mentioned:")
      (doseq [[entity count] (take 5 top-entities)]
        (println (str "  " entity ": " count " times")))
      (println))

    (println (apply str (repeat 60 "=")) "\n")))

;; ============================================================================
;; Export Functions
;; ============================================================================

(defn export-report
  "Export a report to a specified format.

   Args:
     report - Report data (map)
     format - :edn, :json, or :csv

   Returns:
     String representation in the specified format"
  [report format]
  (case format
    :edn (pr-str report)
    :json (try
            (require '[clojure.data.json :as json])
            ((resolve 'clojure.data.json/write-str) report)
            (catch Exception e
              (str "Error: clojure.data.json not available: " (.getMessage e))))
    :csv (str "CSV export not yet implemented\n"
              "Report keys: " (keys report))
    (str "Unknown format: " format)))

;; ============================================================================
;; Convenience Function
;; ============================================================================

(defn full-analysis
  "Run all reports for a chat and print to REPL.

   Args:
     db - Datahike db value
     chat-id - Chat UUID"
  [db chat-id]
  (print-conversation-summary-report db chat-id)
  (print-budget-analysis-report db chat-id)
  (print-tool-effectiveness-report db chat-id)
  (print-agent-performance-report db chat-id))
