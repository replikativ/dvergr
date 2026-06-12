(ns dvergr.chat.accounting
  "Resource accounting with numéraire conversion.

   Tracks resource consumption in natural units (tokens, API calls, compute time)
   and converts to microdollars (1 USD = 1,000,000 μ$) as the numéraire.

   The numéraire is the reference commodity that makes all resources commensurable.
   Prices (conversion factors) are pulled from the model registry or static defaults.

   Key concepts:
   - Natural units: tokens, requests, milliseconds
   - Microdollars: common unit of account (avoids floating-point issues)
   - Pricing: conversion factors from natural units to microdollars
   - Budget: allocation of microdollars to a context
   - Ledger: immutable record of all transactions

   Threshold alerts follow logarithmic spacing: 50%, 75%, 90%, 95%"
  (:require [dvergr.model.registry :as registry]
            [datahike.api :as d]
            [clojure.string :as str]))

;; ============================================================================
;; Constants
;; ============================================================================

(def ^:const MICRODOLLARS-PER-DOLLAR 1000000)

;; Logarithmic thresholds for alerts (% used)
(def budget-thresholds
  [{:pct 0.50 :level :info     :message "Budget: 50% used"}
   {:pct 0.75 :level :notice   :message "Budget: 75% used, consider wrapping up"}
   {:pct 0.90 :level :warning  :message "Budget: 90% used, complete current task"}
   {:pct 0.95 :level :critical :message "Budget: 95% used, finishing..."}])

;; ============================================================================
;; Pricing Registry (Static Fallback)
;; ============================================================================

(def static-pricing
  "Static pricing for non-LLM resources (in microdollars per unit).
   LLM pricing comes from model registry."
  {;; Web search
   :web-search       3000      ; $0.003/request (Brave)
   :web-fetch        1000      ; $0.001/request

   ;; Tool overhead (estimated compute)
   :tool-invoke      100       ; $0.0001/call
   :clj-kondo        50        ; $0.00005/run
   :shell            200       ; $0.0002/call (includes process overhead)

   ;; Compute time
   :compute-ms       0         ; Free (already paid in LLM calls)

   ;; Budget transfers (pass-through: 1 microdollar per microdollar)
   :budget-allocation 1
   :budget-return     1
   :sub-chat-allocation 1
   :sub-chat-return   1})

;; ============================================================================
;; Pricing Functions
;; ============================================================================

(defn get-model-pricing
  "Get pricing for a model in microdollars per token.
   Returns {:input μ$/token :output μ$/token :cache-read :cache-write}"
  [model-id]
  (when-let [pricing (registry/pricing-of model-id)]
    ;; Registry has $/M tokens, convert to μ$/token
    (reduce-kv
     (fn [m k v]
       (assoc m k (long (* v MICRODOLLARS-PER-DOLLAR (/ 1 1000000.0)))))
     {}
     pricing)))

(defn get-static-pricing
  "Get static pricing for non-LLM resources."
  [resource-type]
  (get static-pricing resource-type 0))

(defn calculate-cost
  "Calculate cost in microdollars for a resource usage.

   Args:
     resource-type - Keyword (:token-input :token-output :web-search etc.)
     amount        - Amount in natural units
     opts          - {:model model-id} for token-based resources

   Returns: Cost in microdollars (long)"
  [resource-type amount {:keys [model]}]
  (case resource-type
    ;; Token-based resources need model pricing
    (:token-input :input-tokens)
    (if-let [pricing (get-model-pricing model)]
      (* amount (:input pricing 1))
      ;; Fallback: assume $1/M tokens = 1 μ$/token
      amount)

    (:token-output :output-tokens)
    (if-let [pricing (get-model-pricing model)]
      (* amount (:output pricing 1))
      amount)

    :token-cache-read
    (if-let [pricing (get-model-pricing model)]
      (* amount (:cache-read pricing 0))
      0)

    :token-cache-write
    (if-let [pricing (get-model-pricing model)]
      (* amount (:cache-write pricing 0))
      0)

    ;; Static pricing for everything else
    (long (* amount (get-static-pricing resource-type)))))

;; ============================================================================
;; Budget Status
;; ============================================================================

(defn budget-status
  "Calculate budget status from raw values.

   Args:
     total-microdollars - Total budget allocated
     used-microdollars  - Budget consumed

   Returns:
     {:total :used :remaining :pct-used :pct-remaining}"
  [total-microdollars used-microdollars]
  (let [remaining (- total-microdollars used-microdollars)
        pct-used (if (pos? total-microdollars)
                   (/ (double used-microdollars) total-microdollars)
                   0.0)
        pct-remaining (- 1.0 pct-used)]
    {:total total-microdollars
     :used used-microdollars
     :remaining remaining
     :pct-used pct-used
     :pct-remaining pct-remaining}))

(defn check-thresholds
  "Check which thresholds have been crossed.

   Returns the highest threshold crossed, or nil if none."
  [pct-used crossed-thresholds]
  (->> budget-thresholds
       (filter #(>= pct-used (:pct %)))
       (remove #(contains? crossed-thresholds (:pct %)))
       (last)))

(defn format-budget
  "Format budget for display.

   Args:
     status - Budget status map from budget-status

   Returns: Formatted string"
  [{:keys [total used remaining pct-used]}]
  (let [fmt-dollars (fn [micros]
                      (format "$%.4f" (/ micros (double MICRODOLLARS-PER-DOLLAR))))]
    (str "Budget Status:\n"
         "  Total:     " (fmt-dollars total) "\n"
         "  Used:      " (fmt-dollars used) "\n"
         "  Remaining: " (fmt-dollars remaining)
         " (" (int (* 100 (- 1.0 pct-used))) "%)")))

(defn format-detailed-budget
  "Format detailed budget including breakdown by resource.

   Args:
     status      - Budget status map
     by-resource - Map of resource-type -> microdollars used

   Returns: Formatted string"
  [{:keys [total used remaining pct-used] :as status} by-resource]
  (let [fmt-dollars (fn [micros]
                      (format "$%.6f" (/ micros (double MICRODOLLARS-PER-DOLLAR))))
        resource-lines (->> by-resource
                            (sort-by val >)
                            (take 5)
                            (map (fn [[k v]]
                                   (str "  " (name k) ": " (fmt-dollars v)))))]
    (str (format-budget status)
         (when (seq resource-lines)
           (str "\n\nTop Resources:\n" (str/join "\n" resource-lines))))))

;; ============================================================================
;; Cost Estimation (Pre-execution)
;; ============================================================================

(defn estimate-chat-cost
  "Estimate cost of a chat request before execution.

   Args:
     model-id      - Model to use
     input-tokens  - Estimated input tokens
     output-tokens - Estimated output tokens (use historical avg, default 500)
     opts          - {:tools? :web-search? :thinking-budget}

   Returns:
     {:estimated-microdollars N :breakdown {...}}"
  [model-id input-tokens output-tokens & {:keys [tools? web-search? thinking-budget]
                                          :or {output-tokens 500}}]
  (let [input-cost (calculate-cost :token-input input-tokens {:model model-id})
        output-cost (calculate-cost :token-output output-tokens {:model model-id})
        tool-overhead (if tools? 50000 0)  ; ~$0.05 buffer for tool calls
        search-cost (if web-search? 10000 0)  ; ~$0.01 for web search
        thinking-cost (if thinking-budget
                        (calculate-cost :token-output thinking-budget {:model model-id})
                        0)
        total (+ input-cost output-cost tool-overhead search-cost thinking-cost)]
    {:estimated-microdollars total
     :breakdown {:input input-cost
                 :output output-cost
                 :tool-overhead tool-overhead
                 :search search-cost
                 :thinking thinking-cost}}))

(defn can-afford?
  "Check if a context can afford an estimated cost.

   Args:
     remaining-microdollars - Available budget
     estimated-cost         - Estimated cost in microdollars

   Returns: boolean"
  [remaining-microdollars estimated-cost]
  (>= remaining-microdollars estimated-cost))

;; ============================================================================
;; Ledger Operations
;; ============================================================================

(defn record-usage!
  "Record resource consumption in the ledger.

   Args:
     conn          - Datahike connection
     chat-id       - UUID of the chat/context
     resource-type - Resource type keyword
     amount        - Amount in natural units
     opts          - {:model :provider :tool :metadata}

   Returns:
     {:ledger-id uuid :cost-microdollars N}"
  [conn chat-id resource-type amount & {:keys [model provider tool metadata]}]
  (let [cost (calculate-cost resource-type amount {:model model})
        ledger-id (random-uuid)
        entry (cond-> {:ledger/id ledger-id
                       :ledger/context [:chat/id chat-id]
                       :ledger/timestamp (java.util.Date.)
                       :ledger/resource resource-type
                       :ledger/amount (long amount)
                       :ledger/cost-microdollars (long cost)}
                model (assoc :ledger/model model)
                provider (assoc :ledger/provider provider)
                tool (assoc :ledger/tool tool)
                metadata (assoc :ledger/metadata (pr-str metadata)))]
    (d/transact conn [entry])
    {:ledger-id ledger-id
     :cost-microdollars cost}))

(defn get-usage-breakdown
  "Get usage breakdown by resource type.

   Args:
     conn    - Datahike connection
     chat-id - UUID of the chat/context

   Returns: Map of resource-type -> total-microdollars"
  [conn chat-id]
  (let [results (d/q '[:find ?resource (sum ?cost)
                       :in $ ?cid
                       :where
                       [?l :ledger/context ?c]
                       [?c :chat/id ?cid]
                       [?l :ledger/resource ?resource]
                       [?l :ledger/cost-microdollars ?cost]]
                     @conn chat-id)]
    (into {} results)))

(defn get-total-cost
  "Get total cost for a chat/context.

   Args:
     conn    - Datahike connection
     chat-id - UUID of the chat/context

   Returns: Total cost in microdollars"
  [conn chat-id]
  (or (d/q '[:find (sum ?cost) .
             :in $ ?cid
             :where
             [?l :ledger/context ?c]
             [?c :chat/id ?cid]
             [?l :ledger/cost-microdollars ?cost]]
           @conn chat-id)
      0))

;; ============================================================================
;; Budget Tool Response Builder
;; ============================================================================

(defn build-budget-response
  "Build a budget tool response with all relevant information.

   Args:
     budget-signal - Current budget signal value {:total :used :by-type}
     context-info  - Optional {:current-tokens :token-limit :compaction-threshold}

   Returns: Formatted response map"
  [{:keys [total used by-type]} context-info]
  (let [status (budget-status total used)
        ;; Calculate which constraints are tight
        tight-constraints (cond-> []
                            (> (:pct-used status) 0.75) (conj :budget)
                            (and context-info
                                 (> (/ (:current-tokens context-info 0)
                                       (:token-limit context-info 1))
                                    0.70))
                            (conj :context))

        fmt-dollars (fn [micros]
                      (format "$%.4f" (/ micros (double MICRODOLLARS-PER-DOLLAR))))

        resource-breakdown (->> by-type
                                (map (fn [[k v]]
                                       (let [cost (calculate-cost k v {})]
                                         [k {:amount v :cost cost}])))
                                (into {}))]

    {:budget {:microdollars {:total total
                             :used used
                             :remaining (:remaining status)}
              :by-type resource-breakdown
              :pct-used (:pct-used status)}

     :context (when context-info
                {:tokens {:current (:current-tokens context-info)
                          :limit (:token-limit context-info)
                          :until-compaction (- (* (:token-limit context-info)
                                                  (:compaction-threshold context-info 0.7))
                                               (:current-tokens context-info))}})

     :constraints {:tight tight-constraints}

     :formatted (str "Budget Status:\n"
                     "  Monetary: " (fmt-dollars (:remaining status))
                     " / " (fmt-dollars total)
                     " remaining (" (int (* 100 (:pct-remaining status))) "%)\n"
                     "  Tokens:   " (get-in by-type [:input-tokens] 0) " input, "
                     (get-in by-type [:output-tokens] 0) " output\n"
                     (when context-info
                       (str "\nContext:\n"
                            "  Current:   " (:current-tokens context-info) "/"
                            (:token-limit context-info) " tokens\n"))
                     (when (seq tight-constraints)
                       (str "\nConstraints:\n"
                            (str/join "\n"
                                      (map #(str "  ⚠ " (name %) " approaching limit")
                                           tight-constraints)))))}))

(comment
  ;; Example usage

  ;; Get pricing for a model
  (get-model-pricing "claude-sonnet-4-5")
  ;; => {:input 3 :output 15 :cache-read 0 :cache-write 3}

  ;; Calculate cost
  (calculate-cost :token-input 1000 {:model "claude-sonnet-4-5"})
  ;; => 3000 (μ$, i.e. $0.003)

  ;; Estimate chat cost
  (estimate-chat-cost "claude-sonnet-4-5" 5000 2000 :tools? true)
  ;; => {:estimated-microdollars N :breakdown {...}}

  ;; Format budget
  (format-budget (budget-status 5000000 1234567))
  ;; => "Budget Status:\n  Total: $5.0000\n  Used: $1.2346\n  Remaining: $3.7654 (75%)"
  )
