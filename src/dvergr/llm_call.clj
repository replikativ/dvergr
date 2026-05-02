(ns dvergr.llm-call
  "Cheap one-shot LLM call tool for summarization and extraction.

   Exposes a single `llm_call` tool that makes a cheap synchronous LLM call
   without spawning a new agent turn. Useful for:
   - Condensing long web pages / transcripts before they enter context
   - Quick extraction / classification from search results
   - Summarizing content in the middle of an agent sweep

   Also exports `cheap-llm-call` for use from SCI (the 'llm namespace).

   Budget: tokens are accounted to the calling agent's chat-ctx when
   :chat-ctx is present in the tool execution context."
  (:require [dvergr.model.chat :as model-chat]
            [dvergr.chat.context :as chat-ctx]
            [dvergr.tools :as tools]
            [clojure.string :as str]))

(def ^:private default-model
  "Default cheap model for llm_call. Override with LLM_CALL_MODEL env var."
  (or (System/getenv "LLM_CALL_MODEL")
      "accounts/fireworks/models/minimax-m2p5"))

(defn cheap-llm-call
  "Make a cheap one-shot LLM call synchronously.
   Returns {:text :usage :model} on success, {:error} on failure.

   opts:
   - :model      — model ID override (default: minimax-m2p5)
   - :max-tokens — max output tokens (default: 500)
   - :system     — optional system prompt string"
  [prompt content opts]
  (try
    (let [model      (or (:model opts) default-model)
          max-tokens (or (:max-tokens opts) 500)
          system     (:system opts)
          full-prompt (if (seq content)
                        (str prompt "\n\n" content)
                        prompt)
          ;; Prepend system as first message (model.chat doesn't take separate :system)
          api-msgs   (if system
                       (into [{:role "system" :content system}]
                             [{:role "user" :content full-prompt}])
                       [{:role "user" :content full-prompt}])
          result     (model-chat/chat api-msgs {:model model :max-tokens max-tokens})]
      {:text  (or (:content result) "")
       :usage (:usage result)
       :model model})
    (catch Exception e
      {:error (.getMessage e)})))

(defn- account-llm-call! [chat-ctx result]
  (when (and chat-ctx (:usage result) (not (:error result)))
    (let [{:keys [input-tokens output-tokens input output]} (:usage result)
          m (:model result)
          in  (or input-tokens input 0)
          out (or output-tokens output 0)]
      (when (pos? in)
        (chat-ctx/account-tokens! chat-ctx :input-tokens in {:model m}))
      (when (pos? out)
        (chat-ctx/account-tokens! chat-ctx :output-tokens out {:model m})))))

(tools/register!
  {:name        "llm_call"
   :description "Make a cheap one-shot LLM call for summarization, extraction, or classification. Does NOT start a new agent turn — just returns text. Use to condense long content (transcripts, web pages, search results) before it enters your context window. Deducts from the calling agent's budget."
   :parameters  {:type       "object"
                 :properties {:prompt     {:type        "string"
                                           :description "Instruction for the LLM (e.g. 'Summarize the key points relevant to Replikativ', 'Extract technical claims', 'Score relevance 1-5 and explain')"}
                              :content    {:type        "string"
                                           :description "Content to process. Appended after the prompt."}
                              :max_tokens {:type        "integer"
                                           :description "Max output tokens (default 500, max 2000)"}
                              :model      {:type        "string"
                                           :description "Model override. Leave blank for default cheap model."}}
                 :required   ["prompt"]}
   :execute     (fn [{:keys [prompt content max_tokens model]} {:keys [chat-ctx] :as _ctx}]
                  (when (and chat-ctx (chat-ctx/budget-exceeded? chat-ctx))
                    (throw (ex-info "Budget exceeded — llm_call refused"
                                    {:type :budget-exceeded})))
                  (let [result (cheap-llm-call
                                 prompt content
                                 {:model      model
                                  :max-tokens (min (or max_tokens 500) 2000)})]
                    (account-llm-call! chat-ctx result)
                    (if (:error result)
                      {:type :error :error (:error result)}
                      {:type :success :content (:text result)})))})
