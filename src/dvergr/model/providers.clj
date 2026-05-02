(ns dvergr.model.providers
  "Provider registry for managing LLM provider instances.

   Providers are registered at startup and looked up by keyword ID."
  (:require [dvergr.model.registry :as registry]
            [dvergr.model.api.anthropic :as anthropic]
            [dvergr.model.api.openai :as openai]
            [dvergr.model.api.claude-code :as claude-code]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; Provider Registry
;; ============================================================================

(defonce ^{:doc "Atom holding registered provider instances."}
  providers (atom {}))

(defn register!
  "Register a provider instance.

   Args:
     provider-key - Keyword identifier (e.g. :anthropic)
     provider     - Provider instance implementing LLMProvider protocol"
  [provider-key provider]
  (swap! providers assoc provider-key provider)
  provider)

(defn unregister!
  "Remove a provider from the registry."
  [provider-key]
  (swap! providers dissoc provider-key))

(defn get-provider
  "Get a provider instance by key. Returns nil if not found."
  [provider-key]
  (get @providers provider-key))

(defn get-provider!
  "Get a provider instance by key. Throws if not found."
  [provider-key]
  (or (get-provider provider-key)
      (throw (ex-info "Provider not registered"
                      {:provider provider-key
                       :available (keys @providers)}))))

(defn list-providers
  "List all registered provider keys."
  []
  (keys @providers))

(defn registered?
  "Check if a provider is registered."
  [provider-key]
  (contains? @providers provider-key))

;; ============================================================================
;; Provider Resolution
;; ============================================================================

(defn provider-for-model
  "Get the provider instance for a model ID.
   Looks up the model's provider in the registry and returns the provider instance."
  [model-id]
  (let [model-def (registry/get-model model-id)
        provider-key (:provider model-def)]
    (get-provider provider-key)))

(defn provider-for-model!
  "Get the provider instance for a model ID. Throws if not found."
  [model-id]
  (or (provider-for-model model-id)
      (let [model-def (registry/get-model model-id)]
        (throw (ex-info "Provider for model not registered"
                        {:model-id model-id
                         :provider (:provider model-def)
                         :available (keys @providers)})))))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-defaults!
  "Initialize default providers from environment variables.
   Only registers providers that have API keys available."
  []
  ;; Anthropic
  (when-let [provider (anthropic/create-if-available {})]
    (register! :anthropic provider)
    (tel/log! {:id :providers/registered :data {:provider :anthropic}} "Registered provider"))

  ;; OpenAI
  (when-let [provider (openai/create-if-available {})]
    (register! :openai provider)
    (tel/log! {:id :providers/registered :data {:provider :openai}} "Registered provider"))

  ;; Fireworks
  (when-let [provider (openai/create-fireworks-if-available {})]
    (register! :fireworks provider)
    (tel/log! {:id :providers/registered :data {:provider :fireworks}} "Registered provider"))

  ;; Claude Code CLI
  (when-let [provider (claude-code/create-if-available {})]
    (register! :claude-code provider)
    (tel/log! {:id :providers/registered :data {:provider :claude-code}} "Registered provider"))

  ;; Load extra models from resources/models.edn
  (try
    (when-let [n (registry/load-models-resource!)]
      (tel/log! {:id :providers/models-loaded :data {:count n}} "Loaded models"))
    (catch Exception e
      (tel/log! {:level :warn :id :providers/models-load-failed :data {:error (.getMessage e)}}
                "Failed to load models.edn")))

  (tel/log! {:id :providers/init-complete :data {:count (count @providers)}} "Providers ready"))

(defn clear-all!
  "Clear all registered providers."
  []
  (clojure.core/reset! providers {}))

(defn ensure-initialized!
  "Ensure providers are initialized. Safe to call multiple times."
  []
  (when (empty? @providers)
    (init-defaults!)))

;; ============================================================================
;; Custom Provider Registration
;; ============================================================================

(defn register-anthropic!
  "Register an Anthropic provider with custom config."
  [config]
  (register! :anthropic (anthropic/create config)))

(defn register-openai!
  "Register an OpenAI provider with custom config."
  [config]
  (register! :openai (openai/create config)))

(defn register-fireworks!
  "Register a Fireworks provider with custom config."
  [config]
  (register! :fireworks (openai/create-fireworks config)))

(defn register-claude-code!
  "Register a Claude Code CLI provider."
  [config]
  (register! :claude-code (claude-code/create config)))

(defn register-openai-compatible!
  "Register a custom OpenAI-compatible provider.

   Args:
     provider-key - Keyword identifier for this provider
     config       - Config map with :api-key, :base-url, etc."
  [provider-key config]
  (register! provider-key (openai/create (assoc config :provider-id provider-key))))
