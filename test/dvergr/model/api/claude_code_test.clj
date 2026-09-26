(ns dvergr.model.api.claude-code-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dvergr.model.api.claude-code :as claude-code])
  (:import [java.util.concurrent CancellationException]))

(def ^:private result-json
  (str "{\"type\":\"result\",\"result\":\"transport ok\","
       "\"session_id\":\"session-1\",\"total_cost_usd\":0.01,"
       "\"usage\":{\"input_tokens\":3,\"output_tokens\":2},"
       "\"modelUsage\":{\"claude-test\":{}}}"))

(defn- run-with-command [command opts]
  (with-redefs-fn
    {#'claude-code/build-command (constantly command)}
    #(@#'claude-code/run-claude-streaming
      [{:role :user :content "test prompt"}]
      opts)))

(deftest cli-transport-does-not-leak-claude-codes-native-harness
  (let [command (#'claude-code/build-command
                 {:model "claude-code-sonnet"
                  :system "Dvergr owns the tool protocol."})
        option-value (fn [option]
                       (second (drop-while #(not= option %) command)))]
    (testing "subscription auth remains usable but customizations and tools do not"
      (is (some #{"--safe-mode"} command))
      (is (some #{"--disable-slash-commands"} command))
      (is (= "" (option-value "--tools")))
      (is (= "{\"mcpServers\":{}}" (option-value "--mcp-config"))))
    (testing "older CLI versions receive an explicit native-tool denylist"
      (let [denied (set (str/split (option-value "--disallowedTools") #" "))]
        (is (every? denied
                    ["Bash" "Read" "Edit" "Write" "Glob" "Grep" "Task"
                     "WebFetch" "WebSearch" "ToolSearch" "Skill" "Workflow"
                     "ListAgents" "ReportFindings" "ScheduleWakeup"]))))))

(deftest cli-transport-drains-stderr-concurrently
  (testing "stderr larger than a pipe buffer cannot block the result on stdout"
    (let [command ["sh" "-c"
                   (str "cat >/dev/null\n"
                        "head -c 1048576 /dev/zero >&2\n"
                        "printf '%s\\n' '" result-json "'")]
          result (deref (future (run-with-command command {})) 5000 ::timeout)]
      (is (not= ::timeout result))
      (is (= "transport ok" (:content result)))
      (is (= {:input-tokens 3
              :output-tokens 2
              :cache-read-tokens 0
              :cache-creation-tokens 0
              :raw-input-tokens 3}
             (:usage result))))))

(deftest cli-transport-cancellation-destroys-subprocess
  (testing ":cancel? terminates an in-flight subscription CLI call"
    (let [cancelled? (atom false)
          started (promise)
          start-process @#'claude-code/start-process
          {:keys [process outcome]}
          (with-redefs-fn
            {#'claude-code/build-command (constantly ["sh" "-c" "cat >/dev/null; exec sleep 30"])
             #'claude-code/start-process (fn [cmd]
                                           (let [process (start-process cmd)]
                                             (deliver started process)
                                             process))}
            (fn []
              (let [call (future
                           (try
                             {:value (@#'claude-code/run-claude-streaming
                                      [{:role :user :content "test prompt"}]
                                      {:cancel? #(deref cancelled?)})}
                             (catch Throwable t
                               {:throwable t})))
                    process (deref started 1000 ::not-started)]
                (reset! cancelled? true)
                {:process process
                 :outcome (deref call 5000 ::timeout)})))]
      (is (not= ::not-started process))
      (let [{:keys [throwable]} outcome]
        (is (not= ::timeout outcome))
        (is (instance? CancellationException throwable))
        (is (false? (.isAlive ^Process process)))))))

(deftest cli-transport-cleans-up-after-stream-callback-failure
  (testing "all failure paths terminate the subprocess and release its streams"
    (let [stream-json (str "{\"type\":\"stream_event\","
                           "\"event\":{\"type\":\"content_block_delta\","
                           "\"delta\":{\"text\":\"partial\"}}}")
          command ["sh" "-c"
                   (str "cat >/dev/null\n"
                        "printf '%s\\n' '" stream-json "'\n"
                        "exec sleep 30")]
          process-ref (atom nil)
          start-process @#'claude-code/start-process
          outcome
          (with-redefs-fn
            {#'claude-code/build-command (constantly command)
             #'claude-code/start-process (fn [cmd]
                                           (let [process (start-process cmd)]
                                             (reset! process-ref process)
                                             process))}
            (fn []
              (try
                {:value (@#'claude-code/run-claude-streaming
                         [{:role :user :content "test prompt"}]
                         {:on-text (fn [_]
                                     (throw (ex-info "callback failed" {})))})}
                (catch Throwable t
                  {:throwable t}))))
          process @process-ref
          {:keys [throwable]} outcome]
      (is (not= ::not-started process))
      (is (not= ::timeout outcome))
      (is (= "callback failed" (ex-message throwable)))
      (is (false? (.isAlive ^Process process))))))

(deftest unwrapped-tool-calls-are-recovered-only-when-unambiguous
  (let [parse #(@#'claude-code/parse-tool-calls %1 #{"cancel_pending_order"})
        call "{\"name\":\"cancel_pending_order\",\"input\":{\"order_id\":\"#W1\",\"reason\":\"ordered by mistake\"}}"]
    (testing "a response that is solely one offered call becomes a tool call"
      (let [{:keys [text tool-calls]} (parse call)]
        (is (= "" text))
        (is (= [["cancel_pending_order" {:order_id "#W1" :reason "ordered by mistake"}]]
               (mapv (juxt :name :input) tool-calls))))
      (is (= "cancel_pending_order"
             (-> (parse (str "```json\n" call "\n```")) :tool-calls first :name))))
    (testing "everything else stays text"
      (doseq [text [(str "Here is the call: " call)
                    "{\"name\":\"delete_everything\",\"input\":{}}"
                    "{\"name\":\"cancel_pending_order\",\"input\":{},\"extra\":1}"
                    "{\"name\":\"cancel_pending_order\",\"input\":\"x\"}"
                    "{\"order_id\":\"#W1\"}"
                    "{not json}"]]
        (is (nil? (:tool-calls (parse text))) text)))
    (testing "wrapped calls keep precedence"
      (is (= 1 (count (:tool-calls (parse (str "<tool_use>\n" call "\n</tool_use>")))))))))

(defn- emit-lines [& lines]
  ["sh" "-c" (apply str "cat >/dev/null\n"
                    (map #(str "printf '%s\\n' '" % "'\n") lines))])

(deftest usage-limits-are-tracked-and-error-results-are-not-replies
  (let [rate-limits @#'claude-code/rate-limits
        resets (+ 3600 (quot (System/currentTimeMillis) 1000))
        event (fn [status]
                (str "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"" status "\","
                     "\"resetsAt\":" resets ",\"rateLimitType\":\"seven_day\",\"utilization\":0.99,"
                     "\"unifiedWindows\":{\"five_hour\":{\"utilization\":0.1,\"resetsAt\":" resets "},"
                     "\"seven_day\":{\"utilization\":0.99,\"resetsAt\":" resets "}}}}"))]
    (try
      (testing "a reply records the latest usage report"
        (reset! rate-limits nil)
        (is (= "transport ok" (:content (run-with-command (emit-lines (event "allowed_warning") result-json) {}))))
        (is (= :allowed_warning (:status (claude-code/rate-limit-status))))
        (is (not (claude-code/usage-limited?)))
        (is (zero? (claude-code/usage-wait-ms 0.995)) "below the threshold: no pause")
        (is (< 3600000 (claude-code/usage-wait-ms 0.97)) "a window over the threshold pauses until it resets"))
      (testing "an error result is thrown, classified as a usage limit when rejected"
        (reset! rate-limits nil)
        (let [e (try (run-with-command
                      (emit-lines (event "rejected")
                                  "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"result\":\"You have hit your limit\"}")
                      {})
                     nil
                     (catch Exception e e))]
          (is (claude-code/usage-limit-error? e))
          (is (claude-code/usage-limited?))
          (is (= (* 1000 resets) (:resets-at-ms (ex-data e))))))
      (testing "other error results are errors, not usage limits"
        (reset! rate-limits nil)
        (let [e (try (run-with-command
                      (emit-lines "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"result\":\"Invalid API key\"}")
                      {})
                     nil
                     (catch Exception e e))]
          (is (some? e))
          (is (not (claude-code/usage-limit-error? e)))))
      (finally (reset! rate-limits nil)))))

(deftest native-invoke-calls-are-parsed
  ;; What claude-code-haiku emitted on a wiki task, instead of <tool_use> JSON
  ;; (2026-09-26): nothing was parsed, no tool ran, the Attempt scored 0.
  (let [parse #(@#'claude-code/parse-tool-calls %1 #{"shell" "read_file" "write_file"})
        haiku (str "<function_calls>\n<invoke name=\"shell\">\n<parameter name=\"command\">ls -la /docs/</parameter>\n"
                   "</invoke>\n</function_calls>\n<function_calls>\n<invoke name=\"read_file\">\n"
                   "<parameter name=\"path\">/docs/blog-history.md</parameter>\n</invoke>\n</tool_use>\n\n"
                   "Let me check the rest.")]
    (testing "every offered call, in order, and the prose around them as text"
      (let [{:keys [text tool-calls]} (parse haiku)]
        (is (= [["shell" {:command "ls -la /docs/"}] ["read_file" {:path "/docs/blog-history.md"}]]
               (mapv (juxt :name :input) tool-calls)))
        (is (= "Let me check the rest." text))))
    (testing "multi-line values keep their lines, JSON values are parsed, writes dedupe by path"
      (let [{:keys [tool-calls]}
            (parse (str "<function_calls><invoke name=\"write_file\"><parameter name=\"path\">/wiki/a.md</parameter>"
                        "<parameter name=\"content\">\n# A\n\nfirst\n</parameter></invoke>"
                        "<invoke name=\"write_file\"><parameter name=\"path\">/wiki/a.md</parameter>"
                        "<parameter name=\"content\">\n# A\n\nsecond\n</parameter></invoke>"
                        "<invoke name=\"shell\"><parameter name=\"command\">[\"ls\", \"-la\"]</parameter></invoke>"
                        "</function_calls>"))]
        (is (= #{["write_file" {:path "/wiki/a.md" :content "# A\n\nsecond"}]
                 ["shell" {:command ["ls" "-la"]}]}
               (set (map (juxt :name :input) tool-calls))))))
    (testing "an unoffered tool is never called"
      (is (nil? (:tool-calls (parse "<function_calls><invoke name=\"delete_everything\"></invoke></function_calls>")))))
    (testing "<tool_use> JSON keeps precedence"
      (is (= ["read_file"]
             (mapv :name (:tool-calls (parse (str "<tool_use>{\"name\":\"read_file\",\"input\":{\"path\":\"/x\"}}</tool_use>"
                                                  "<invoke name=\"shell\"><parameter name=\"command\">ls</parameter></invoke>")))))))))
