(ns dvergr.preflight
  "Pre-flight checks before running expensive LLM experiments.

   Quick validation (<5 seconds, <$0.01) that core systems work:
   - Tool registry
   - Budget accounting
   - Schema generation
   - Message injection
   - Optional: cheap LLM smoke test"
  (:require [dvergr.tools :as tools]
            [dvergr.chat.context :as ctx]
            [dvergr.chat.accounting :as acct]
            [dvergr.chat.schema :as schema]
            [dvergr.chat.tool-schema :as tool-schema]
            [dvergr.model.registry :as model-registry]
            [clojure.string :as str]))

(defn check-tool-registry
  "Verify critical tools are registered."
  []
  (println "  Checking tool registry...")
  (let [critical-tools ["budget" "signal_complete" "clojure_eval"
                        "read_file" "write_file" "glob" "grep"]
        missing (remove #(get @tools/registry %) critical-tools)]
    (if (empty? missing)
      (do (println "    ✓ All critical tools registered (" (count @tools/registry) "total)")
          true)
      (do (println "    ✗ Missing tools:" missing)
          false))))

(defn check-budget-accounting
  "Verify budget accounting and threshold detection."
  []
  (println "  Checking budget accounting...")
  (try
    (let [chat (ctx/create-chat-context {:budget-dollars 0.01 :with-sci? false})]
      ;; Test basic accounting
      (ctx/account-usage! chat :input-tokens 1000
                         :model "claude-sonnet-4-5")
      (let [budget (ctx/get-budget chat)]
        (when (not= 3000 (:used budget))
          (throw (ex-info "Budget calculation incorrect" {:expected 3000 :actual (:used budget)}))))

      ;; Test threshold crossing
      (let [result (ctx/account-usage! chat :input-tokens 667
                                      :model "claude-sonnet-4-5")]
        (when (not (:threshold-crossed? result))
          (throw (ex-info "Threshold not detected" {:result result})))
        (when (not= :info (:threshold-level result))
          (throw (ex-info "Wrong threshold level" {:expected :info :actual (:threshold-level result)}))))

      (println "    ✓ Budget accounting works correctly")
      true)
    (catch Exception e
      (println "    ✗ Budget accounting failed:" (.getMessage e))
      false)))

(defn check-tool-schema
  "Verify tool schema generation."
  []
  (println "  Checking tool schema generation...")
  (try
    ;; NOTE: Removed reset-installed-schemas! - no longer needed
    (let [budget-tool (get @tools/registry "budget")]
      (when-not budget-tool
        (throw (ex-info "Budget tool not found" {})))

      ;; Generate schema
      (let [schema (tool-schema/generate-tool-schema budget-tool)]
        (when (empty? schema)
          (throw (ex-info "Schema generation returned empty" {})))

        ;; Check for expected attributes
        (when-not (some #(= :tool-input.budget/operation (:db/ident %)) schema)
          (throw (ex-info "Expected attribute not found in schema" {:schema schema})))

        ;; Test entity conversion
        (let [input {:operation "check"}
              entity (tool-schema/tool-input->entity budget-tool input)]
          (when-not (= "check" (:tool-input.budget/operation entity))
            (throw (ex-info "Entity conversion incorrect" {:entity entity})))))

      (println "    ✓ Tool schema generation works")
      true)
    (catch Exception e
      (println "    ✗ Tool schema generation failed:" (.getMessage e))
      false)))

(defn check-message-injection
  "Verify budget warning messages are injected."
  []
  (println "  Checking message injection...")
  (try
    (let [chat (ctx/create-chat-context {:budget-dollars 0.01 :with-sci? false})]
      ;; Cross 50% threshold
      (let [result (ctx/account-tokens! chat :input-tokens 1667
                                       {:model "claude-sonnet-4-5"})]
        (when-not (:threshold-crossed? result)
          (throw (ex-info "Threshold not crossed" {:result result})))

        ;; Manually inject message (like agent.clj does)
        (ctx/add-message! chat
                          {:role :system
                           :content (str "⚠️ BUDGET ALERT: " (:threshold-message result))
                           :important? true})

        ;; Verify message was added
        (let [messages (ctx/get-messages chat)]
          (when (not= 1 (count messages))
            (throw (ex-info "Message not added" {:count (count messages)})))

          (let [msg (first messages)]
            (when (not= :system (:message/role msg))
              (throw (ex-info "Wrong message role" {:role (:message/role msg)})))
            (when-not (str/includes? (:message/content msg) "BUDGET ALERT")
              (throw (ex-info "Message doesn't contain alert" {:content (:message/content msg)})))
            (when-not (:message/important? msg)
              (throw (ex-info "Message not marked important" {:msg msg}))))))

      (println "    ✓ Message injection works")
      true)
    (catch Exception e
      (println "    ✗ Message injection failed:" (.getMessage e))
      false)))

(defn check-model-registry
  "Verify model registry has expected models."
  []
  (println "  Checking model registry...")
  (let [critical-models ["claude-sonnet-4-5"
                         "accounts/fireworks/models/kimi-k2p5"
                         "accounts/fireworks/models/qwen3-coder-480b-a35b-instruct"]
        missing (remove model-registry/get-model critical-models)]
    (if (empty? missing)
      (do (println "    ✓ All critical models registered")
          true)
      (do (println "    ✗ Missing models:" missing)
          false))))

(defn check-database-creation
  "Verify datahike database creation with tool schemas."
  []
  (println "  Checking database creation...")
  (try
    (let [cfg {:store {:backend :memory :id (random-uuid)}}
          conn (schema/create-chat-db! cfg)
          db @conn]
      ;; Check tool schemas were installed
      (let [tool-attrs (datahike.api/q '[:find [?ident ...]
                                         :where
                                         [?e :db/ident ?ident]
                                         [(clojure.string/starts-with? (str ?ident) ":tool-input")]]
                                       db)]
        (when (< (count tool-attrs) 10)
          (throw (ex-info "Too few tool schemas installed"
                         {:count (count tool-attrs)}))))

      (println "    ✓ Database creation works")
      (datahike.api/release conn)
      true)
    (catch Exception e
      (println "    ✗ Database creation failed:" (.getMessage e))
      false)))

(defn run-checks
  "Run all pre-flight checks.

   Returns true if all pass, false otherwise."
  []
  (println "\n" (apply str (repeat 60 "=")) "\n")
  (println "  RATATOSK PRE-FLIGHT CHECKS\n")
  (println (apply str (repeat 60 "=")) "\n")

  (let [checks [["Tool Registry" check-tool-registry]
                ["Model Registry" check-model-registry]
                ["Budget Accounting" check-budget-accounting]
                ["Tool Schema Generation" check-tool-schema]
                ["Message Injection" check-message-injection]
                ["Database Creation" check-database-creation]]
        results (mapv (fn [[name check-fn]]
                        (println)
                        (println (str "✈ " name ":"))
                        (let [result (check-fn)]
                          [name result]))
                      checks)
        all-passed? (every? second results)
        failed (filter (complement second) results)]

    (println "\n" (apply str (repeat 60 "=")) "\n")
    (if all-passed?
      (do (println "  ✓ ALL CHECKS PASSED - Ready for experiments\n")
          (println (apply str (repeat 60 "=")))
          true)
      (do (println "  ✗ SOME CHECKS FAILED:")
          (doseq [[name _] failed]
            (println "    -" name))
          (println "\n  Fix issues before running expensive experiments.\n")
          (println (apply str (repeat 60 "=")))
          false))))

(defn quick-check
  "Run just the fast critical checks (no LLM)."
  []
  (and (check-tool-registry)
       (check-model-registry)
       (check-budget-accounting)
       (check-tool-schema)
       (check-message-injection)))

(comment
  ;; Run pre-flight checks
  (require '[dvergr.preflight :as preflight] :reload)
  (preflight/run-checks)

  ;; Quick check (faster)
  (preflight/quick-check))
