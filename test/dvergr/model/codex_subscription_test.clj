(ns dvergr.model.codex-subscription-test
  (:require [clojure.test :refer [deftest is testing]]
            [dvergr.model.api.codex-subscription :as codex]
            [dvergr.model.provider :as provider]
            [dvergr.model.providers :as providers]
            [jsonista.core :as json]))

(defn- json-line [value]
  (json/write-value-as-string value))

(defn- successful-lines [response usage]
  [(json-line {:type "thread.started" :thread_id "thread-test"})
   (json-line {:type "turn.started"})
   (json-line {:type "item.completed"
               :item {:id "item-1"
                      :type "agent_message"
                      :text (json/write-value-as-string response)}})
   (json-line {:type "turn.completed" :usage usage})])

(deftest subscription-provider-translates-structured-tool-call
  (let [captured (atom nil)
        streamed (atom [])
        runner (fn [command prompt]
                 (reset! captured {:command command :prompt prompt})
                 {:exit-code 0
                  :stderr ""
                  :lines (successful-lines
                          {:content "I will inspect that."
                           :tool_calls [{:id "call-1"
                                         :name "lookup"
                                         :input "{\"query\":\"fork semantics\"}"}]}
                          {:input_tokens 40
                           :cached_input_tokens 12
                           :cache_write_input_tokens 3
                           :output_tokens 9
                           :reasoning_output_tokens 4})})
        codex-provider (codex/create-cli {:runner runner})
        response (provider/direct-chat
                  codex-provider
                  [{:role :system :content "Be precise"}
                   {:role :user :content "Research this"}]
                  {:model "codex-subscription-sol"
                   :effort :high
                   :tools [{:name "lookup"
                            :description "Look something up"
                            :parameters {:type "object"
                                         :properties {:query {:type "string"}}}}]
                   :on-text #(swap! streamed conj %)})]
    (is (= "I will inspect that." (:content response)))
    (is (= [{:id "call-1" :name "lookup" :input {:query "fork semantics"}}]
           (:tool-calls response)))
    (is (= :tool-use (:stop-reason response)))
    (is (= {:input-tokens 40
            :output-tokens 9
            :cache-read-tokens 12
            :cache-write-tokens 3
            :reasoning-output-tokens 4}
           (:usage response)))
    (is (= ["I will inspect that."] @streamed))
    (is (some #{"gpt-5.6-sol"} (get-in @captured [:command])))
    (is (some #{"model_reasoning_effort=\"high\""} (get-in @captured [:command])))
    (is (some #{"--ignore-user-config"} (get-in @captured [:command])))
    (is (some #{"read-only"} (get-in @captured [:command])))
    (is (some #{"web_search=\"disabled\""} (get-in @captured [:command])))
    (is (some #{"multi_agent"} (get-in @captured [:command])))
    (is (re-find #"Dvergr owns the conversation" (get-in @captured [:prompt])))
    (is (re-find #"Be precise" (get-in @captured [:prompt])))
    (is (re-find #"lookup" (get-in @captured [:prompt])))))

(deftest generic-subscription-model-follows-cli-default
  (let [command (atom nil)
        codex-provider
        (codex/create-cli
         {:runner (fn [cmd _]
                    (reset! command cmd)
                    {:exit-code 0
                     :stderr ""
                     :lines (successful-lines
                             {:content "ok" :tool_calls []}
                             {:input_tokens 1 :output_tokens 1})})})]
    (provider/direct-chat codex-provider
                          [{:role :user :content "hello"}]
                          {:model "codex-subscription"})
    (is (not (some #{"--model"} @command)))))

(deftest native-codex-effects-fail-closed
  (let [codex-provider
        (codex/create-cli
         {:runner (fn [_ _]
                    {:exit-code 0
                     :stderr ""
                     :lines [(json-line {:type "thread.started" :thread_id "t"})
                             (json-line {:type "item.started"
                                         :item {:id "native-1"
                                                :type "command_execution"
                                                :command "pwd"
                                                :status "in_progress"}})
                             (json-line {:type "turn.completed"
                                         :usage {:input_tokens 1 :output_tokens 1}})]})})]
    (try
      (provider/direct-chat codex-provider
                            [{:role :user :content "hello"}]
                            {:model "codex-subscription"})
      (is false "native effects must reject the response")
      (catch clojure.lang.ExceptionInfo error
        (is (= ["command_execution"] (:native-effects (ex-data error))))))))

(deftest availability-requires-chatgpt-login
  (testing "ChatGPT login registers"
    (is (some? (codex/create-cli-if-available {:login-probe (constantly true)}))))
  (testing "missing or API-key-only login does not register"
    (is (nil? (codex/create-cli-if-available {:login-probe (constantly false)})))))

(deftest cli-compatibility-provider-can-be-the-default
  (let [original @providers/providers]
    (try
      (reset! providers/providers {:codex-subscription-cli ::available})
      (with-redefs [providers/ensure-initialized! (constantly nil)]
        (is (= {:provider :codex-subscription-cli
                :model "codex-subscription-cli"}
               (providers/default-spec))))
      (finally
        (reset! providers/providers original)))))

(deftest durable-tool-history-is-replayed-to-ephemeral-codex
  (let [codex-provider (codex/create {:credentials ::fake})
        formatted (provider/format-messages
                   codex-provider
                   [{:message/role :assistant
                     :message/content "I will look that up."
                     :message/tool-uses
                     [{:tool-use/id "call-1"
                       :tool-use/name "lookup"
                       :tool-use/input {:db/id 42 :lookup/query "fork semantics"}}]}
                    {:message/role :tool-result
                     :message/tool-use-id "call-1"
                     :message/content "Copy-on-write forks share immutable history."}]
                   "codex-subscription")]
    (is (= "message" (get-in formatted [0 :type])))
    (is (= "assistant" (get-in formatted [0 :role])))
    (is (= "function_call" (get-in formatted [1 :type])))
    (is (= "lookup" (get-in formatted [1 :name])))
    (is (re-find #"fork semantics" (get-in formatted [1 :arguments])))
    (is (= {:type "function_call_output"
            :call_id "call-1"
            :output "Copy-on-write forks share immutable history."}
           (nth formatted 2)))))

(deftest native-provider-builds-responses-lite-request
  (let [credentials ::fake
        codex-provider (codex/create {:credentials credentials})
        request (provider/build-request
                 codex-provider
                 [{:role :system :content "Own the runtime semantics."}
                  {:role :user :content "Create a nested room."}]
                 {:model "codex-subscription-sol"
                  :effort :high
                  :tools [{:name "create_room"
                           :description "Create a nested room"
                           :parameters {:type "object"
                                        :properties {:title {:type "string"}}
                                        :required ["title"]}}]})
        body (:body request)]
    (is (= "https://chatgpt.com/backend-api/codex/responses" (:url request)))
    (is (= credentials (:credentials request)))
    (is (= "dvergr" (get-in request [:headers "originator"])))
    (is (= "true" (get-in request [:headers
                                   "x-openai-internal-codex-responses-lite"])))
    (is (= "gpt-5.6-sol" (:model body)))
    (is (nil? (:instructions body)))
    (is (nil? (:tools body)))
    (is (= "additional_tools" (get-in body [:input 0 :type])))
    (is (= "functions" (get-in body [:input 0 :tools 0 :name])))
    (is (= "create_room" (get-in body [:input 0 :tools 0 :tools 0 :name])))
    (is (= "developer" (get-in body [:input 1 :role])))
    (is (= "Own the runtime semantics."
           (get-in body [:input 1 :content 0 :text])))
    (is (= "Create a nested room."
           (get-in body [:input 2 :content 0 :text])))
    (is (= {:effort "high" :context "all_turns"} (:reasoning body)))))

(deftest native-provider-accumulates-text-tools-and-usage
  (let [codex-provider (codex/create {:credentials ::fake})
        model-def {:id "codex-subscription-sol"}
        events [{:type "response.output_text.delta" :delta "I will "}
                {:type "response.output_text.delta" :delta "create it."}
                {:type "response.output_item.done"
                 :item {:type "function_call"
                        :call_id "call-room"
                        :name "create_room"
                        :arguments "{\"title\":\"Child\"}"}}
                {:type "response.completed"
                 :response {:id "resp-1"
                            :model "gpt-5.6-sol"
                            :usage {:input_tokens 71
                                    :output_tokens 12
                                    :input_tokens_details {:cached_tokens 31}
                                    :output_tokens_details {:reasoning_tokens 4}}}}]
        final-state (reduce (fn [state event]
                              (provider/accumulate-event codex-provider state
                                                         (:type event) event model-def))
                            (provider/create-accumulator codex-provider model-def)
                            events)
        response (provider/extract-response codex-provider final-state)]
    (is (= "I will create it." (:content response)))
    (is (= [{:id "call-room"
             :name "create_room"
             :input {:title "Child"}}]
           (:tool-calls response)))
    (is (= :tool-use (:stop-reason response)))
    (is (= {:input-tokens 71
            :output-tokens 12
            :cache-read-tokens 31
            :reasoning-output-tokens 4}
           (:usage response)))))
