(ns dvergr.benchmarks.live
  "Model-backed generate functions for benchmark protocols over Dvergr's provider
   layer (Anthropic, OpenAI, Fireworks, Claude Code, Codex, ...).

   Benchmark messages (tau2's shape) are translated into Dvergr's chat message entities and then
   through each provider's own MessageFormatter, so provider quirks (tool-call
   replay, the Claude Code text protocol) are handled in exactly one place."
  (:require [dvergr.chat.agent :as chat-agent]
            [dvergr.model.chat :as chat]
            [dvergr.model.providers :as providers]
            [dvergr.model.registry :as registry]
            [dvergr.resource :as resource]))

(defn- ->entity [{:keys [role content tool-calls id]}]
  (case role
    :assistant (cond-> {:message/role :assistant :message/content (or content "")}
                 (seq tool-calls)
                 (assoc :message/tool-uses
                        (mapv (fn [{:keys [id name arguments]}]
                                {:tool-use/id id :tool-use/name name
                                 :tool-use/input arguments})
                              tool-calls)))
    :user {:message/role :user :message/content content}
    :tool {:message/role :tool-result :message/tool-use-id id
           :message/content content}))

(defn- ->tool-def [{:strs [function]}]
  (let [{:strs [name description parameters]} function]
    {:name name :description description
     :input_schema parameters :parameters parameters}))

(defn scoped
  "`generate` under a Run's model scope (the `:model-scope` a protocol is
   given): its calls spend the Run's dispatch admissions wherever they happen.
   A protocol's driver and reference candidate run on Room participant threads,
   where the protocol worker's own binding does not reach. nil scope: as is."
  [generate scope]
  (if scope
    (fn [request] (binding [resource/*model-scope* scope] (generate request)))
    generate))

(defn model-generate
  "Return a generate fn backed by `dvergr.model.chat/chat`.
   `spec` is `{:model id-or-alias :provider kw? :temperature n? :max-tokens n?
   :parallel-tool-calls bool?}`."
  [{:keys [model provider temperature max-tokens parallel-tool-calls]}]
  (providers/ensure-initialized!)
  (let [model-id (registry/resolve-alias model)
        provider (or provider (:provider (registry/get-model! model-id)))
        counter (atom 0)]
    (fn [{:keys [system messages tools]}]
      (let [api-messages (chat-agent/messages->api-format
                          (mapv ->entity messages) provider model-id)
            response (chat/chat api-messages
                                (cond-> {:model model-id :provider provider
                                         :system system}
                                  (seq tools) (assoc :tools (mapv ->tool-def tools))
                                  (some? parallel-tool-calls)
                                  (assoc :parallel-tool-calls parallel-tool-calls)
                                  temperature (assoc :temperature temperature)
                                  max-tokens (assoc :max-tokens max-tokens)))]
        {:content (:content response)
         :tool-calls (mapv (fn [{:keys [id name input]}]
                             {:id (or id (str "call_" (swap! counter inc)))
                              :name name
                              :arguments (or input {})})
                           (:tool-calls response))
         :usage (:usage response)
         :stop-reason (:stop-reason response)}))))
