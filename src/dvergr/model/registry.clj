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
  "Built-in model definitions — current-generation only.

   Hardcoded set is intentionally lean. To pick up new model releases
   without recompiling, call `(registry/refresh-from-models-dev!)`,
   which overlays current pricing/context from <https://models.dev>.

   Pricing keys:
     :input         — base input $/MTok
     :output        — output $/MTok
     :cache-read    — prompt-cache hit (0.1× input)
     :cache-write   — 5-minute prompt-cache write (1.25× input)
     :cache-write-1h— 1-hour prompt-cache write (2× input), where available

   Snapshot date: 2026-05-24. See claude.com/pricing for live prices."
  {;; ── Claude Opus 4.x ──────────────────────────────────────────────
   "claude-opus-4-7"
   {:id "claude-opus-4-7"
    :name "Claude Opus 4.7"
    :provider :anthropic
    :api-type :anthropic-messages
    :capabilities #{:tools :vision :thinking :streaming :system-prompt :cache-control}
    :context 1000000
    :max-output 8192
    :pricing {:input 5.0 :output 25.0 :cache-read 0.50 :cache-write 6.25 :cache-write-1h 10.0}
    :quirks {:thinking-budget? true}}

   "claude-opus-4-6"
   {:id "claude-opus-4-6"
    :name "Claude Opus 4.6"
    :provider :anthropic
    :api-type :anthropic-messages
    :capabilities #{:tools :vision :thinking :streaming :system-prompt :cache-control}
    :context 1000000
    :max-output 8192
    :pricing {:input 5.0 :output 25.0 :cache-read 0.50 :cache-write 6.25 :cache-write-1h 10.0}
    :quirks {:thinking-budget? true}}

   "claude-opus-4-5"
   {:id "claude-opus-4-5"
    :name "Claude Opus 4.5"
    :provider :anthropic
    :api-type :anthropic-messages
    :capabilities #{:tools :vision :thinking :streaming :system-prompt :cache-control}
    :context 200000
    :max-output 8192
    :pricing {:input 5.0 :output 25.0 :cache-read 0.50 :cache-write 6.25 :cache-write-1h 10.0}
    :quirks {:thinking-budget? true}}

   ;; ── Claude Sonnet 4.x ────────────────────────────────────────────
   "claude-sonnet-4-6"
   {:id "claude-sonnet-4-6"
    :name "Claude Sonnet 4.6"
    :provider :anthropic
    :api-type :anthropic-messages
    :capabilities #{:tools :vision :thinking :streaming :system-prompt :cache-control}
    :context 1000000
    :max-output 8192
    :pricing {:input 3.0 :output 15.0 :cache-read 0.30 :cache-write 3.75 :cache-write-1h 6.0}
    :quirks {:thinking-budget? true}}

   "claude-sonnet-4-5"
   {:id "claude-sonnet-4-5"
    :name "Claude Sonnet 4.5"
    :provider :anthropic
    :api-type :anthropic-messages
    :capabilities #{:tools :vision :thinking :streaming :system-prompt :cache-control}
    :context 200000
    :max-output 8192
    :pricing {:input 3.0 :output 15.0 :cache-read 0.30 :cache-write 3.75 :cache-write-1h 6.0}
    :quirks {:thinking-budget? true}}

   ;; ── Claude Haiku 4.x ─────────────────────────────────────────────
   "claude-haiku-4-5"
   {:id "claude-haiku-4-5"
    :name "Claude Haiku 4.5"
    :provider :anthropic
    :api-type :anthropic-messages
    :capabilities #{:tools :vision :streaming :system-prompt :cache-control}
    :context 200000
    :max-output 8192
    :pricing {:input 1.0 :output 5.0 :cache-read 0.10 :cache-write 1.25 :cache-write-1h 2.0}
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
    :quirks {}}})

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

(defonce ^:private models-loaded? (atom false))

(defn ensure-models-loaded!
  "Idempotently load models.edn into the registry. The model dropdowns (web +
   TUI) and pricing read from the registry, which otherwise holds only the
   built-in `default-models` — so without this the UI would offer the wrong
   models (the Anthropic defaults) rather than the configured Fireworks set.
   Cheap + safe to call on every dropdown render."
  []
  (when (compare-and-set! models-loaded? false true)
    (try (load-models-resource!)
         (catch Throwable _ (reset! models-loaded? false)))))

(defn reset-to-defaults!
  "Reset the registry to default models only."
  []
  (reset! registry default-models))

;; ============================================================================
;; Refresh from models.dev (opt-in, network)
;; ============================================================================

(def ^:private models-dev-url "https://models.dev/api.json")

(defn- models-dev-fetch
  "Fetch + parse models.dev/api.json. Returns nil on network failure."
  []
  (try
    (let [json-read (requiring-resolve 'jsonista.core/read-value)
          mapper-fn (requiring-resolve 'jsonista.core/object-mapper)
          mapper    (mapper-fn {:decode-key-fn true})
          s         (slurp models-dev-url)]
      (json-read s mapper))
    (catch Throwable t
      (println "models.dev fetch failed:" (.getMessage t))
      nil)))

(defn- coerce-models-dev-model
  "Translate one models.dev model entry into the dvergr registry shape."
  [provider-id mid m]
  (let [cost  (:cost m)
        limit (:limit m)
        caps  (cond-> #{:streaming}
                (:tool_call m)       (conj :tools)
                (:reasoning m)       (conj :thinking)
                (some #(= "image" %) (get-in m [:modalities :input] []))
                (conj :vision)
                true                 (conj :system-prompt)
                (some? (:cache_read cost)) (conj :cache-control))]
    {:id (name mid)
     :name (or (:name m) (name mid))
     :provider provider-id
     :api-type (case provider-id
                 :anthropic :anthropic-messages
                 :openai    :openai-chat
                 :openai-chat)
     :capabilities caps
     :context (:context limit)
     :max-output (:output limit)
     :pricing (cond-> {:input  (:input cost)
                       :output (:output cost)}
                (:cache_read cost)  (assoc :cache-read (:cache_read cost))
                (:cache_write cost) (assoc :cache-write (:cache_write cost)))}))

(defn refresh-from-models-dev!
  "Fetch <https://models.dev/api.json> and overlay all entries from the
   given providers into the registry. Default: just :anthropic.

   Hardcoded `default-models` is the offline fallback. Calling this
   updates pricing + adds any newer models that have shipped since the
   last release.

   Returns the number of models registered (or nil on network failure)."
  ([] (refresh-from-models-dev! #{:anthropic}))
  ([providers]
   (when-let [data (models-dev-fetch)]
     (let [n (atom 0)]
       (doseq [prov-key providers
               :let [prov (get data prov-key)
                     models (:models prov)]
               :when (some? prov)
               [mid m] models]
         (let [entry (coerce-models-dev-model prov-key mid m)]
           (swap! registry assoc (:id entry) entry)
           (swap! n inc)))
       @n))))

;; ============================================================================
;; Aliases (convenience names)
;; ============================================================================

(defonce ^{:doc "Atom holding model aliases."}
  aliases (atom {"sonnet" "claude-sonnet-4-6"
                 "opus"   "claude-opus-4-7"
                 "haiku"  "claude-haiku-4-5"
                 "opus-4-6" "claude-opus-4-6"
                 "opus-4-5" "claude-opus-4-5"
                 "sonnet-4-5" "claude-sonnet-4-5"
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
