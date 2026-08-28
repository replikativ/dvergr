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
