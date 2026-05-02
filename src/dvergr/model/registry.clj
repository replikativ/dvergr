(ns dvergr.model.registry
  "Model registry with capabilities, pricing, and quirks.

   Separates model metadata from provider implementation logic.
   Models can be loaded from EDN for runtime configuration."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

;; ============================================================================
;; Capabilities
;; ============================================================================

(def capabilities
  "Set of known model capabilities."
  #{:tools           ; Function/tool calling
    :vision          ; Image input
    :thinking        ; Extended thinking/reasoning
    :streaming       ; SSE streaming
    :system-prompt   ; Separate system prompt field
    :cache-control   ; Prompt caching
    :json-mode})     ; Structured JSON output

;; ============================================================================
;; Default Models
;; ============================================================================

(def default-models
  "Built-in model definitions."
  {;; Anthropic Models
   "claude-sonnet-4-5-20250514"
   {:id "claude-sonnet-4-5-20250514"
    :name "Claude Sonnet 4.5"
    :provider :anthropic
    :api-type :anthropic-messages
    :capabilities #{:tools :vision :thinking :streaming :system-prompt :cache-control}
    :context 200000
    :max-output 8192
    :pricing {:input 3.0 :output 15.0 :cache-read 0.30 :cache-write 3.75}
    :quirks {:thinking-budget? true}}

   "claude-opus-4-20250514"
   {:id "claude-opus-4-20250514"
    :name "Claude Opus 4"
    :provider :anthropic
    :api-type :anthropic-messages
    :capabilities #{:tools :vision :thinking :streaming :system-prompt :cache-control}
    :context 200000
    :max-output 8192
    :pricing {:input 15.0 :output 75.0 :cache-read 1.50 :cache-write 18.75}
    :quirks {:thinking-budget? true}}

   "claude-3-5-haiku-20241022"
   {:id "claude-3-5-haiku-20241022"
    :name "Claude 3.5 Haiku"
    :provider :anthropic
    :api-type :anthropic-messages
    :capabilities #{:tools :vision :streaming :system-prompt :cache-control}
    :context 200000
    :max-output 8192
    :pricing {:input 0.80 :output 4.0 :cache-read 0.08 :cache-write 1.0}
    :quirks {}}

   ;; Claude Code CLI models (via subscription)
   "claude-code-sonnet"
   {:id "claude-code-sonnet"
    :name "Claude Sonnet (Code CLI)"
    :provider :claude-code
    :api-type :claude-code-cli
    :capabilities #{:tools :system-prompt :thinking :streaming}
    :context 200000
    :max-output 32000
    :pricing {:input 0 :output 0}  ;; subscription-based
    :quirks {}}

   "claude-code-opus"
   {:id "claude-code-opus"
    :name "Claude Opus (Code CLI)"
    :provider :claude-code
    :api-type :claude-code-cli
    :capabilities #{:tools :system-prompt :thinking :streaming}
    :context 200000
    :max-output 32000
    :pricing {:input 0 :output 0}
    :quirks {}}

   "claude-code-haiku"
   {:id "claude-code-haiku"
    :name "Claude Haiku (Code CLI)"
    :provider :claude-code
    :api-type :claude-code-cli
    :capabilities #{:tools :system-prompt :streaming}
    :context 200000
    :max-output 32000
    :pricing {:input 0 :output 0}
    :quirks {}}

   })

;; ============================================================================
;; Registry State
;; ============================================================================

(defonce ^{:doc "Atom holding the model registry map."}
  registry (atom default-models))

;; ============================================================================
;; Registry Operations
;; ============================================================================

(defn register-model!
  "Register a model definition in the registry."
  [model-def]
  (when-not (:id model-def)
    (throw (ex-info "Model definition must have :id" {:model model-def})))
  (swap! registry assoc (:id model-def) model-def)
  model-def)

(defn unregister-model!
  "Remove a model from the registry."
  [model-id]
  (swap! registry dissoc model-id))

(defn get-model
  "Get model definition by ID. Returns nil if not found."
  [model-id]
  (get @registry model-id))

(defn get-model!
  "Get model definition by ID. Throws if not found."
  [model-id]
  (or (get-model model-id)
      (throw (ex-info "Model not found in registry"
                      {:model-id model-id
                       :available (keys @registry)}))))

(defn list-models
  "List all registered models."
  []
  (vals @registry))

(defn models-for-provider
  "Get all models for a specific provider."
  [provider]
  (->> @registry
       vals
       (filter #(= provider (:provider %)))))

;; ============================================================================
;; Capability Checking
;; ============================================================================

(defn supports?
  "Check if a model supports a specific capability."
  [model-id capability]
  (if-let [model (get-model model-id)]
    (contains? (:capabilities model) capability)
    false))

(defn capabilities-of
  "Get the capabilities set for a model."
  [model-id]
  (or (:capabilities (get-model model-id)) #{}))

(defn assert-capability!
  "Assert that a model supports a capability. Throws if not."
  [model-id capability]
  (when-not (supports? model-id capability)
    (throw (ex-info (str "Model does not support " capability)
                    {:model-id model-id
                     :capability capability
                     :supported (capabilities-of model-id)}))))

;; ============================================================================
;; Quirk Access
;; ============================================================================

(defn get-quirk
  "Get a specific quirk value for a model. Returns nil if not set."
  [model-id quirk-key]
  (get-in (get-model model-id) [:quirks quirk-key]))

(defn has-quirk?
  "Check if a model has a specific quirk enabled."
  [model-id quirk-key]
  (boolean (get-quirk model-id quirk-key)))

(defn quirks-of
  "Get all quirks for a model."
  [model-id]
  (or (:quirks (get-model model-id)) {}))

;; ============================================================================
;; Provider/API Type Resolution
;; ============================================================================

(defn provider-of
  "Get the provider for a model."
  [model-id]
  (:provider (get-model model-id)))

(defn api-type-of
  "Get the API type for a model."
  [model-id]
  (:api-type (get-model model-id)))

;; ============================================================================
;; Pricing
;; ============================================================================

(defn pricing-of
  "Get pricing info for a model."
  [model-id]
  (:pricing (get-model model-id)))

(defn estimate-cost
  "Estimate cost for a request given input/output token counts.
   Returns cost in dollars."
  [model-id input-tokens output-tokens & {:keys [cache-read-tokens cache-write-tokens]
                                           :or {cache-read-tokens 0
                                                cache-write-tokens 0}}]
  (if-let [pricing (pricing-of model-id)]
    (let [{:keys [input output cache-read cache-write]
           :or {cache-read 0 cache-write 0}} pricing]
      (+ (* input (/ input-tokens 1000000.0))
         (* output (/ output-tokens 1000000.0))
         (* (or cache-read 0) (/ cache-read-tokens 1000000.0))
         (* (or cache-write 0) (/ cache-write-tokens 1000000.0))))
    0))

;; ============================================================================
;; Context/Output Limits
;; ============================================================================

(defn context-window
  "Get the context window size for a model."
  [model-id]
  (:context (get-model model-id)))

(defn max-output
  "Get the max output tokens for a model."
  [model-id]
  (:max-output (get-model model-id)))

;; ============================================================================
;; EDN Loading
;; ============================================================================

(defonce ^{:doc "Configurable defaults loaded from models.edn."}
  defaults (atom {}))

(defn get-default
  "Get a default config value (e.g., :compaction-model, :primary-model)."
  [k]
  (get @defaults k))

;; Forward declare for use in load-models-from-edn
(declare register-alias!)

(defn load-models-from-edn
  "Load model definitions, aliases, and defaults from an EDN file or resource.
   Merges with existing registry."
  [source]
  (let [data (if (string? source)
               (edn/read-string (slurp source))
               (edn/read-string (slurp (io/reader source))))
        models (:models data)
        edn-aliases (:aliases data)
        edn-defaults (:defaults data)]
    (doseq [[id model-def] models]
      (register-model! (assoc model-def :id (name id))))
    (doseq [[alias-name model-id] edn-aliases]
      (register-alias! alias-name model-id))
    (when edn-defaults
      (swap! defaults merge edn-defaults))
    (count models)))

(defn load-models-resource!
  "Load models.edn from classpath resources or fallback to file path."
  []
  (if-let [r (io/resource "models.edn")]
    (load-models-from-edn r)
    (let [f (io/file "resources/models.edn")]
      (when (.exists f)
        (load-models-from-edn (.getPath f))))))

(defn reset-to-defaults!
  "Reset the registry to default models only."
  []
  (reset! registry default-models))

;; ============================================================================
;; Aliases (convenience names)
;; ============================================================================

(defonce ^{:doc "Atom holding model aliases."}
  aliases (atom {"sonnet" "claude-sonnet-4-5-20250514"
                 "opus" "claude-opus-4-20250514"
                 "haiku" "claude-3-5-haiku-20241022"
                 "cc-sonnet" "claude-code-sonnet"
                 "cc-opus" "claude-code-opus"
                 "cc-haiku" "claude-code-haiku"}))

(defn register-alias!
  "Register an alias for a model ID."
  [alias-name model-id]
  (swap! aliases assoc alias-name model-id))

(defn resolve-alias
  "Resolve a model alias to its full ID. Returns input if not an alias."
  [model-id-or-alias]
  (get @aliases model-id-or-alias model-id-or-alias))

(defn get-model-by-alias
  "Get model definition, resolving aliases first."
  [model-id-or-alias]
  (get-model (resolve-alias model-id-or-alias)))
