(ns dvergr.model.openai-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dvergr.model.api.openai :as openai]
            [dvergr.model.chat :as model-chat]
            [dvergr.model.provider :as provider]
            [dvergr.model.providers :as providers]
            [dvergr.model.registry :as registry]
            [hato.client :as hc]
            [jsonista.core :as json])
  (:import [java.io ByteArrayInputStream]))

(def ^:private test-tool
  {:name "lookup"
   :description "Look up a value"
   :parameters {:type "object"
                :properties {:query {:type "string"}}
                :required ["query"]}})

(def ^:private gpt-capabilities
  #{:tools :vision :thinking :streaming :system-prompt :cache-control})

(def ^:private gpt-5p6-efforts
  ["none" "low" "medium" "high" "xhigh" "max"])

(def ^:private gpt-5p5-efforts
  ["none" "low" "medium" "high" "xhigh"])

(def ^:private models-dev-fetch-var
  (ns-resolve 'dvergr.model.registry 'models-dev-fetch))

(use-fixtures
  :each
  (fn [f]
    (let [before-providers @providers/providers
          before-models @registry/registry
          before-aliases @registry/aliases]
      (try
        (reset! providers/providers {})
        (registry/reset-to-defaults!)
        (f)
        (finally
          (reset! providers/providers before-providers)
          (reset! registry/registry before-models)
          (reset! registry/aliases before-aliases))))))

(defn- native-provider []
  (openai/create {:api-key "test-openai-key"} {}))

(defn- compatible-provider []
  (openai/create {:api-key "test-compatible-key"
                  :base-url "https://compatible.example.test/v1"}
                 {}))

(deftest gpt-registry-contract
  (let [expected {"gpt-5.6-sol"   {:context 1050000 :default "medium" :efforts gpt-5p6-efforts}
                  "gpt-5.6-terra" {:context 1050000 :default "medium" :efforts gpt-5p6-efforts}
                  "gpt-5.6-luna"  {:context 1050000 :default "medium" :efforts gpt-5p6-efforts}
                  "gpt-5.5"       {:context 1050000 :default "medium" :efforts gpt-5p5-efforts}
                  "gpt-5.4-mini"  {:context 400000 :default "none" :efforts gpt-5p5-efforts}}]
    (doseq [[id {:keys [context default efforts]}] expected]
      (testing id
        (let [model (registry/get-model! id)]
          (is (= id (:id model)))
          (is (= :openai (:provider model)))
          (is (= :openai-chat (:api-type model)))
          (is (= gpt-capabilities (:capabilities model)))
          (is (= context (:context model)))
          (is (= 128000 (:max-output model)))
          (is (= efforts (registry/reasoning-efforts id)))
          (is (= default (registry/default-reasoning-effort id)))
          (is (= :developer (registry/instruction-role id))))))
    (is (= {:input 4.0 :output 20.0 :cache-read 0.40 :cache-write 5.0}
           (registry/pricing-of "gpt-5.6-sol"))
        "GPT-5.6 Sol pricing must match the current documented base rate")
    (doseq [id ["gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna"]]
      (is (true? (registry/get-quirk id :chat-tools-need-effort-none?))))
    (is (nil? (registry/get-quirk "gpt-5.5" :chat-tools-need-effort-none?))
        "GPT-5.5 is verified independently and does not inherit the 5.6 restriction")))

(deftest gpt-aliases-resolve-to-selected-chat-entries
  (is (= "gpt-5.6-sol" (registry/resolve-alias "gpt-5.6"))
      "OpenAI's documented GPT-5.6 alias routes to Sol")
  (is (= "gpt-5.6-sol" (registry/resolve-alias "sol")))
  (is (= "gpt-5.6-terra" (registry/resolve-alias "terra")))
  (is (= "gpt-5.6-luna" (registry/resolve-alias "luna")))
  (is (= "gpt-5.5" (registry/resolve-alias "gpt")))
  (is (= "gpt-5.4-mini" (registry/resolve-alias "gpt-mini"))))

(deftest native-gpt-5p6-function-tool-request-is-exact-chat-json
  (let [request (provider/build-request
                 (native-provider)
                 [{:role :user :content "Use the tool"}]
                 {:model "gpt-5.6-sol"
                  :max-tokens 321
                  :system "Product policy"
                  :tools [test-tool]})
        expected-json (str "{\"model\":\"gpt-5.6-sol\","
                           "\"max_completion_tokens\":321,"
                           "\"stream\":true,"
                           "\"stream_options\":{\"include_usage\":true},"
                           "\"messages\":["
                           "{\"role\":\"developer\",\"content\":\"Product policy\"},"
                           "{\"role\":\"user\",\"content\":\"Use the tool\"}],"
                           "\"tools\":[{\"type\":\"function\",\"function\":{"
                           "\"name\":\"lookup\",\"description\":\"Look up a value\","
                           "\"parameters\":{\"type\":\"object\",\"properties\":{"
                           "\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}}}],"
                           "\"reasoning_effort\":\"none\"}")]
    (is (= "https://api.openai.com/v1/chat/completions" (:url request)))
    (is (= expected-json (json/write-value-as-string (:body request))))))

(deftest native-gpt-5p5-keeps-its-own-reasoning-contract
  (let [body (:body (provider/build-request
                     (native-provider)
                     [{:role :system :content "Product policy"}
                      {:role :user :content "Use the tool"}]
                     {:model "gpt-5.5"
                      :tools [test-tool]}))]
    (is (= "medium" (registry/default-reasoning-effort "gpt-5.5")))
    (is (= gpt-5p5-efforts (registry/reasoning-efforts "gpt-5.5")))
    (is (= "developer" (get-in body [:messages 0 :role])))
    (is (not (contains? body :reasoning_effort))
        "GPT-5.5 must not inherit the GPT-5.6 function-tool restriction")))

(deftest custom-base-url-gates-native-chat-behavior
  (let [body (:body (provider/build-request
                     (compatible-provider)
                     [{:role :system :content "Product policy"}
                      {:role :user :content "Use the tool"}]
                     {:model "gpt-5.6-sol"
                      :tools [test-tool]}))]
    (is (= "system" (get-in body [:messages 0 :role])))
    (is (not (contains? body :reasoning_effort)))))

(deftest models-dev-overlay-preserves-built-in-native-contract
  (let [overlay {:openai
                 {:models
                  {:gpt-5.6-sol
                   {:name "GPT-5.6 Sol overlay"
                    :tool_call true
                    :reasoning true
                    :reasoning_options [{:type "effort"
                                         :values ["none" "low" "medium" "high" "xhigh" "max"]}]
                    :modalities {:input ["text" "image"]}
                    :limit {:context 900000 :output 64000}
                    :cost {:input 99.0 :output 199.0}}}}}]
    (with-redefs-fn
      {models-dev-fetch-var (constantly overlay)}
      #(is (= 1 (registry/refresh-from-models-dev! #{:openai}))))
    (let [model (registry/get-model! "gpt-5.6-sol")]
      (is (= 900000 (:context model)) "overlay still refreshes limits")
      (is (= {:input 99.0 :output 199.0} (:pricing model))
          "overlay still refreshes its third-party rate fields")
      (is (= gpt-5p6-efforts (:reasoning-efforts model)))
      (is (= "medium" (:default-reasoning-effort model)))
      (is (= :developer (:instruction-role model)))
      (is (= {:chat-tools-need-effort-none? true} (:quirks model)))
      (is (= "gpt-5.6-sol" (registry/resolve-alias "gpt-5.6"))
          "aliases remain valid after overlay"))))

(defn- sse-body [events]
  (let [wire (str (str/join "" (map #(str "data: "
                                          (json/write-value-as-string %)
                                          "\n\n")
                                    events))
                  "data: [DONE]\n\n")]
    (ByteArrayInputStream. (.getBytes wire "UTF-8"))))

(deftest streamed-gpt-tool-call-assembles-and-accounts-usage
  (let [captured (atom nil)
        events [{:id "chatcmpl-test"
                 :model "gpt-5.6-sol"
                 :choices [{:index 0
                            :delta {:role "assistant"
                                    :tool_calls [{:index 0
                                                  :id "call_123"
                                                  :type "function"
                                                  :function {:name "lookup"
                                                             :arguments "{"}}]}
                            :finish_reason nil}]}
                {:id "chatcmpl-test"
                 :model "gpt-5.6-sol"
                 :choices [{:index 0
                            :delta {:tool_calls [{:index 0
                                                  :function {:arguments "\"query\":\"Toronto\"}"}}]}
                            :finish_reason nil}]}
                {:id "chatcmpl-test"
                 :model "gpt-5.6-sol"
                 :choices [{:index 0 :delta {} :finish_reason "tool_calls"}]}
                {:id "chatcmpl-test"
                 :model "gpt-5.6-sol"
                 :choices []
                 :usage {:prompt_tokens 42
                         :completion_tokens 7
                         :total_tokens 49}}]
        openai-provider (native-provider)]
    (providers/register! :openai openai-provider)
    (with-redefs [hc/request (fn [request]
                               (reset! captured request)
                               {:status 200 :headers {} :body (sse-body events)})]
      (let [response (model-chat/chat
                      [{:role :system :content "Product policy"}
                       {:role :user :content "Find Toronto"}]
                      {:provider :openai
                       :model "gpt-5.6-sol"
                       :tools [test-tool]})
            sent-body (json/read-value (:body @captured)
                                       json/keyword-keys-object-mapper)]
        (is (= "developer" (get-in sent-body [:messages 0 :role])))
        (is (= "none" (:reasoning_effort sent-body)))
        (is (= [{:id "call_123"
                 :name "lookup"
                 :input {:query "Toronto"}}]
               (:tool-calls response)))
        (is (= {:input-tokens 42 :output-tokens 7} (:usage response))
            "the final choices=[] usage frame must still be accumulated")
        (is (= :tool_calls (:stop-reason response)))
        (is (= "gpt-5.6-sol" (:model response)))
        (is (= "chatcmpl-test" (:id response)))))))

(deftest tool-result-continuation-replays-chat-tool-protocol
  (let [openai-provider (native-provider)
        api-messages (provider/format-messages
                      openai-provider
                      [{:message/role :system
                        :message/content "Product policy"}
                       {:message/role :user
                        :message/content "Find Toronto"}
                       {:message/role :assistant
                        :message/content "Calling lookup"
                        :message/tool-uses [{:tool-use/id "call_123"
                                             :tool-use/name "lookup"
                                             :tool-use/input {:query "Toronto"}}]}
                       {:message/role :tool-result
                        :message/tool-use-id "call_123"
                        :message/content "18 C"}]
                      "gpt-5.6-sol")
        body (:body (provider/build-request
                     openai-provider
                     api-messages
                     {:model "gpt-5.6-sol"
                      :tools [test-tool]}))]
    (is (= [{:role "developer" :content "Product policy"}
            {:role "user" :content "Find Toronto"}
            {:role "assistant"
             :content "Calling lookup"
             :tool_calls [{:id "call_123"
                           :type "function"
                           :function {:name "lookup"
                                      :arguments "{\"query\":\"Toronto\"}"}}]}
            {:role "tool" :tool_call_id "call_123" :content "18 C"}]
           (:messages body)))
    (is (= "none" (:reasoning_effort body)))))
