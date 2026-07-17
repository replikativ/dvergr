(ns dvergr.chat.tool-schema
  "Automatic Datahike schema generation from tool JSON Schema definitions.

   Each tool's :parameters JSON Schema is translated to Datahike attributes,
   enabling structured storage and queryability of tool inputs.

   Schema naming convention:
   - Tool input entity: :tool-input.{tool-name}/{param-name}
   - Nested objects: :tool-input.{tool-name}.{path}/{param-name}

   Example:
     Tool 'budget' with params {:operation :model :input_tokens}
     Generates:
       :tool-input.budget/operation  (string)
       :tool-input.budget/model      (string)
       :tool-input.budget/input-tokens (long)

   Nested example:
     Tool 'clj_kondo' with params {:lint :config {:linters {...}}}
     Generates:
       :tool-input.clj-kondo/lint    (many strings)
       :tool-input.clj-kondo/config  (ref to component)
       :tool-input.clj-kondo.config/linters (ref to component)
       ..."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [datahike.api :as d]))

;; ============================================================================
;; Name Conversion
;; ============================================================================

(defn- snake->kebab
  "Convert snake_case to kebab-case."
  [s]
  (str/replace s #"_" "-"))

(defn- tool-name->ns-part
  "Convert tool name to namespace-safe form."
  [tool-name]
  (snake->kebab tool-name))

(defn- make-attr-ident
  "Create attribute ident for a tool parameter.

   Args:
     tool-name - Tool name (e.g., 'budget')
     path      - Path segments (e.g., [] or ['config' 'linters'])
     param     - Parameter name (e.g., 'operation')

   Returns:
     Keyword like :tool-input.budget/operation
     or :tool-input.clj-kondo.config/linters"
  [tool-name path param]
  (let [ns-parts (concat ["tool-input" (tool-name->ns-part tool-name)]
                         (map snake->kebab path))
        ns-str (str/join "." ns-parts)]
    (keyword ns-str (snake->kebab param))))

(defn- make-entity-marker
  "Create marker attribute for an entity type.
   Used to identify what kind of tool-input entity this is."
  [tool-name path]
  (let [ns-parts (concat ["tool-input" (tool-name->ns-part tool-name)]
                         (map snake->kebab path))
        ns-str (str/join "." ns-parts)]
    (keyword ns-str "entity-type")))

;; ============================================================================
;; JSON Schema → Datahike Type Mapping
;; ============================================================================

(defn- json-type->datahike
  "Map JSON Schema type to Datahike value type."
  [json-type]
  (case json-type
    "string" :db.type/string
    "integer" :db.type/long
    "number" :db.type/double
    "boolean" :db.type/boolean
    "object" :db.type/ref  ;; nested objects become refs
    "array" nil  ;; handled specially via cardinality
    ;; Default to string for unknown types
    :db.type/string))

(defn- array-item-type
  "Get the Datahike type for array items."
  [items-schema]
  (if items-schema
    (json-type->datahike (get items-schema :type "string"))
    :db.type/string))

;; ============================================================================
;; Schema Generation
;; ============================================================================

;; Forward declaration for mutual recursion
(declare generate-object-schema)

(defn- generate-attr-schema
  "Generate Datahike schema for a single parameter.

   Returns a vector of schema maps (may be multiple for nested objects)."
  [tool-name path param-name param-schema]
  (let [json-type (get param-schema :type "string")
        ident (make-attr-ident tool-name path param-name)
        description (get param-schema :description "")]

    (case json-type
      ;; Primitive types
      ("string" "integer" "number" "boolean")
      [{:db/ident ident
        :db/valueType (json-type->datahike json-type)
        :db/cardinality :db.cardinality/one
        :db/doc description}]

      ;; Arrays
      "array"
      (let [items (get param-schema :items {})
            items-type (get items :type "string")]
        (if (= items-type "object")
          ;; Array of objects - ref with cardinality many
          (let [nested-path (conj (vec path) param-name)
                nested-schema (generate-object-schema tool-name nested-path items)]
            (into [{:db/ident ident
                    :db/valueType :db.type/ref
                    :db/cardinality :db.cardinality/many
                    :db/isComponent true
                    :db/doc description}]
                  nested-schema))
          ;; Array of primitives
          [{:db/ident ident
            :db/valueType (array-item-type items)
            :db/cardinality :db.cardinality/many
            :db/doc description}]))

      ;; Nested objects
      "object"
      (let [nested-path (conj (vec path) param-name)
            nested-schema (generate-object-schema tool-name nested-path param-schema)]
        (into [{:db/ident ident
                :db/valueType :db.type/ref
                :db/cardinality :db.cardinality/one
                :db/isComponent true
                :db/doc description}]
              nested-schema))

      ;; Default: treat as string
      [{:db/ident ident
        :db/valueType :db.type/string
        :db/cardinality :db.cardinality/one
        :db/doc description}])))

(defn- generate-object-schema
  "Generate schema for an object type (root or nested).

   Args:
     tool-name - Tool name
     path      - Path to this object ([] for root)
     schema    - JSON Schema for the object

   Returns vector of Datahike schema maps."
  [tool-name path schema]
  (let [properties (get schema :properties {})
        ;; Add entity type marker for nested objects (helps with queries)
        marker-attr (when (seq path)
                      {:db/ident (make-entity-marker tool-name path)
                       :db/valueType :db.type/keyword
                       :db/cardinality :db.cardinality/one
                       :db/doc "Entity type marker"})]
    (into (if marker-attr [marker-attr] [])
          (mapcat (fn [[param-name param-schema]]
                    (generate-attr-schema tool-name path (name param-name) param-schema))
                  properties))))

(defn generate-tool-schema
  "Generate complete Datahike schema for a tool's input parameters.

   Args:
     tool-def - Tool definition map with :name and :parameters

   Returns:
     Vector of Datahike schema attribute maps."
  [{:keys [name parameters]}]
  (when (and name parameters (= "object" (:type parameters)))
    (generate-object-schema name [] parameters)))

;; ============================================================================
;; Schema Installation
;; ============================================================================

(defn install-tool-schema!
  "Install schema for a tool into a Datahike connection.

   Args:
     conn     - Datahike connection
     tool-def - Tool definition map

   Returns:
     Number of attributes installed.

   Note: This function is idempotent - Datahike handles duplicate
   schema installations gracefully. We removed the global tracking
   because each chat context has its own separate Datahike database."
  [conn tool-def]
  (when-let [schema (generate-tool-schema tool-def)]
    (when (seq schema)
      (try
        (d/transact conn schema)
        (count schema)
        (catch Exception e
          ;; Schema might already exist - that's fine
          (when-not (re-find #"already exists|already defined" (.getMessage e))
            (throw e))
          (count schema))))))

;; Defined below; referenced by install-all-tool-schemas! (resolved at call time).
(declare raw-input-schema)

(defn install-all-tool-schemas!
  "Install schemas for all tools in a registry, plus the raw-EDN fallback
   attributes.

   Args:
     conn     - Datahike connection
     registry - Map of tool-name -> tool-def

   Returns:
     Map of tool-name -> count of attributes installed."
  [conn registry]
  ;; Always install the raw-EDN fallback attributes. serialize-tool-use degrades
  ;; a tool input it cannot type into :tool-input.raw/*, so persistence stays
  ;; total — these MUST exist before any such write can land.
  (try
    (d/transact conn raw-input-schema)
    (catch Exception e
      (when-not (re-find #"already exists|already defined" (.getMessage e))
        (throw e))))
  (->> registry
       (map (fn [[tool-name tool-def]]
              [tool-name (install-tool-schema! conn tool-def)]))
       (filter (fn [[_ count]] count))
       (into {})))

;; ============================================================================
;; Input Conversion (Map → Entity)
;; ============================================================================

;; Forward declaration for mutual recursion
(declare convert-input)

(defn- parse-embedded-coll
  "An LLM sometimes emits an array/object arg as a STRING holding its JSON/EDN
   form (e.g. tags as \"[\\\"a\\\" \\\"b\\\"]\"). Parse it back to a collection;
   nil if the string is not a collection literal. Never iterates a string into
   characters."
  [s]
  (let [t (str/trim s)]
    (when (or (str/starts-with? t "[") (str/starts-with? t "{"))
      (try (let [v (edn/read-string t)]
             (when (coll? v) v))
           (catch Exception _ nil)))))

(defn- coerce-to-seq
  "Normalize an array-valued arg into a sequence WITHOUT exploding a string
   into characters — the char-explosion bug (`(vec \"[…]\")` → chars) that
   detonated at transact time. Accepts a real sequence, a JSON/EDN-string
   array, or a lone scalar (a one-element array)."
  [value]
  (cond
    (sequential? value) value
    (nil? value)        []
    (string? value)     (or (parse-embedded-coll value) [value])
    (coll? value)       (vec value)
    :else               [value]))

(defn- convert-value
  "Convert a value for Datahike storage based on schema type. TOTAL over
   malformed model output: returns a value that satisfies the attribute's
   declared Datahike type, or throws `::uncoercible` so the caller
   (serialize-tool-use) degrades to persisting the tool-use without structured
   input — never a value that passes construction but fails the message
   transact (the char-exploded-array storm)."
  [tool-name path param-name value param-schema]
  (let [json-type (get param-schema :type "string")]
    (case json-type
      ;; Primitives - explicit coercion for Datahike type safety
      "string"
      (str value)

      "integer"  ;; LLMs may return numeric args as strings
      (cond
        (integer? value) (long value)
        (number? value)  (long value)
        (string? value)  (try (Long/parseLong (str/trim value))
                              (catch Exception _
                                (throw (ex-info "uncoercible integer"
                                                {::uncoercible true :param param-name :value value}))))
        :else (throw (ex-info "uncoercible integer"
                              {::uncoercible true :param param-name :value value})))

      "number"
      (cond
        (number? value)  (double value)
        (string? value)  (try (Double/parseDouble (str/trim value))
                              (catch Exception _
                                (throw (ex-info "uncoercible number"
                                                {::uncoercible true :param param-name :value value}))))
        :else (throw (ex-info "uncoercible number"
                              {::uncoercible true :param param-name :value value})))

      "boolean"  ;; (boolean \"false\") is truthy — coerce the string honestly
      (cond
        (boolean? value) value
        (string? value)  (contains? #{"true" "1" "yes" "y" "t"} (str/lower-case (str/trim value)))
        :else            (boolean value))

      ;; Arrays
      "array"
      (let [items (get param-schema :items {})
            items-type (get items :type "string")
            xs (coerce-to-seq value)]
        (if (= items-type "object")
          ;; Array of objects - convert each
          (mapv #(convert-input tool-name
                                (conj (vec path) param-name)
                                %
                                items)
                xs)
          ;; Array of primitives - coerce EACH element to the declared item
          ;; type (never `(vec value)`, which char-explodes a string arg)
          (mapv #(convert-value tool-name path param-name % items) xs)))

      ;; Nested objects
      "object"
      (convert-input tool-name
                     (conj (vec path) param-name)
                     value
                     param-schema)

      ;; Default: stringify
      (str value))))

(defn convert-input
  "Convert a tool input map to Datahike entity format.

   Args:
     tool-name - Tool name
     path      - Current path ([] for root)
     input     - Input map from LLM
     schema    - JSON Schema for this level

   Returns:
     Map suitable for Datahike transact."
  [tool-name path input schema]
  (let [properties (get schema :properties {})
        ;; Add entity marker for nested objects
        base (if (seq path)
               {(make-entity-marker tool-name path)
                (keyword (str/join "." (map snake->kebab path)))}
               {})]
    (reduce-kv
     (fn [entity param-name value]
       (let [;; Look up schema — try keyword and string forms; nil means unknown param
             param-schema (or (get properties (keyword param-name))
                              (get properties (name param-name)))]
          ;; Silently skip parameters not defined in the tool's JSON schema.
          ;; This prevents Datahike errors when the LLM uses aliases or typos
          ;; (e.g. :days instead of :days_back).
         (if (nil? param-schema)
           entity
           (let [attr-ident (make-attr-ident tool-name path (name param-name))
                 converted (convert-value tool-name path (name param-name)
                                          value param-schema)]
             (assoc entity attr-ident converted)))))
     base
     input)))

(defn tool-input->entity
  "Convert a tool use input to a Datahike entity.

   Args:
     tool-def - Tool definition (with :name and :parameters)
     input    - Input map from LLM

   Returns:
     Map suitable for Datahike transact, or nil if no schema."
  [tool-def input]
  (when-let [params (:parameters tool-def)]
    (when (= "object" (:type params))
      (convert-input (:name tool-def) [] input params))))

;; ============================================================================
;; Entity → Map Conversion (for retrieval)
;; ============================================================================

(defn entity->input
  "Convert a Datahike entity back to an input map.

   Args:
     entity    - Datahike entity (from d/entity or d/pull)
     tool-name - Tool name
     path      - Current path

   Returns:
     Original input map structure."
  [entity tool-name path]
  (let [expected-ns (str/join "." (concat ["tool-input" (tool-name->ns-part tool-name)]
                                          (map snake->kebab path)))]
    (reduce-kv
     (fn [m attr value]
       (let [attr-ns (when (keyword? attr) (namespace attr))
             attr-name (when (keyword? attr) (name attr))]
         (cond
            ;; Skip db/id and entity-type markers
           (or (= :db/id attr)
               (= "entity-type" attr-name))
           m

            ;; Attribute belongs to this level
           (= expected-ns attr-ns)
           (let [param-name (str/replace attr-name #"-" "_")]
             (assoc m param-name
                    (cond
                       ;; Ref to nested entity
                      (map? value)
                      (entity->input value tool-name (conj (vec path) attr-name))

                       ;; Collection of refs
                      (and (coll? value) (map? (first value)))
                      (mapv #(entity->input % tool-name (conj (vec path) attr-name)) value)

                       ;; Primitive or collection of primitives
                      :else value)))

           :else m)))
     {}
     entity)))

;; ============================================================================
;; Raw Input Fallback (for unknown tools)
;; ============================================================================

(def raw-input-schema
  "Schema for storing raw tool inputs when tool definition is not available.
   This allows graceful degradation - we store the EDN and can parse later."
  [{:db/ident :tool-input.raw/content
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Raw EDN string of tool input"}
   {:db/ident :tool-input.raw/tool-name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Name of the tool this input was for"}])

(defn raw-input->entity
  "Create a raw input entity for when tool definition is not available.

   Args:
     tool-name - Name of the tool
     input     - Input map (will be serialized to EDN)

   Returns:
     Map suitable for Datahike transact."
  [tool-name input]
  {:tool-input.raw/tool-name tool-name
   :tool-input.raw/content (pr-str input)})

(defn- strip-ns-keys
  "Strip namespace prefixes from map keys (datahike stores tool-input params
   as ns-qualified attrs; the API wants the bare param names)."
  [m]
  (when (map? m)
    (into {} (map (fn [[k v]] [(if (keyword? k) (keyword (name k)) k) v]) m))))

(defn input-entity->args
  "The inverse of persisting a tool input: reconstruct a tool_use's argument
   map for API replay from its stored :tool-use/input entity. A raw-EDN
   fallback entity (`raw-input->entity`, written when the input couldn't be
   typed) round-trips via its stored EDN; a structured entity has its datahike
   namespace prefixes stripped. Always returns a map — never nil (`null`
   arguments are a hard 400 on OpenAI-compatible endpoints, and once replayed
   from durable history they 400 EVERY later turn). This is the ONE args
   reconstruction all provider MessageFormatters share."
  [input]
  (or (when (map? input)
        (if-let [raw (get input :tool-input.raw/content)]
          (try (let [v (edn/read-string raw)] (when (map? v) v))
               (catch Exception _ nil))
          (-> input (dissoc :db/id) strip-ns-keys)))
      {}))

;; ============================================================================
;; Utility
;; ============================================================================

;; NOTE: reset-installed-schemas! removed - no longer needed since we don't
;; track schema installation globally anymore. Each database gets schemas
;; installed independently, and Datahike handles duplicate installations.

(comment
  ;; Example usage

  ;; Define a tool
  (def budget-tool
    {:name "budget"
     :parameters {:type "object"
                  :properties {:operation {:type "string"
                                           :description "Operation to perform"}
                               :model {:type "string"}
                               :input_tokens {:type "integer"}}}})

  ;; Generate schema
  (generate-tool-schema budget-tool)
  ;; => [{:db/ident :tool-input.budget/operation, :db/valueType :db.type/string, ...}
  ;;     {:db/ident :tool-input.budget/model, :db/valueType :db.type/string, ...}
  ;;     {:db/ident :tool-input.budget/input-tokens, :db/valueType :db.type/long, ...}]

  ;; Convert input to entity
  (tool-input->entity budget-tool {:operation "check" :input_tokens 5000})
  ;; => {:tool-input.budget/operation "check"
  ;;     :tool-input.budget/input-tokens 5000}

  ;; Nested object example
  (def kondo-tool
    {:name "clj_kondo"
     :parameters {:type "object"
                  :properties {:lint {:type "array"
                                      :items {:type "string"}}
                               :config {:type "object"
                                        :properties {:output {:type "object"
                                                              :properties {:format {:type "string"}}}}}}}})

  (generate-tool-schema kondo-tool)
  ;; Generates nested schema for config and config.output

  (tool-input->entity kondo-tool
                      {:lint ["src/"]
                       :config {:output {:format "json"}}})
  ;; => {:tool-input.clj-kondo/lint ["src/"]
  ;;     :tool-input.clj-kondo/config
  ;;       {:tool-input.clj-kondo.config/entity-type :config
  ;;        :tool-input.clj-kondo.config/output
  ;;          {:tool-input.clj-kondo.config.output/entity-type :config.output
  ;;           :tool-input.clj-kondo.config.output/format "json"}}}
  )
