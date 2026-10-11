(ns dvergr.agent.fields
  "The canonical agent-config FIELD model — one declarative spec that the web
   form (`dvergr.web.agents`), the TUI config view (`dvergr.tui.app`) and the
   `dvergr.ops` :agent/create|update JSON schemas all derive from.

   This is the field-level companion to `dvergr.agent.ops`: ops is the canonical
   set of agent OPERATIONS, this is the canonical set of agent FIELDS. Domain
   logic — how a field parses from text, formats for display, and what options
   it offers — lives here, NOT in any surface. A surface adds only its own widget
   chrome (a `<select>`, a terminal picker), never its own copy of the field list
   or the parse rules.

   Each field map:
     :k        agent map key == update-agent! patch key (e.g. :budget-dollars)
     :param    form/wire param name        (defaults to (name :k); :budget-dollars→\"budget\")
     :label    human label
     :type     :text | :number | :select | :textarea   (widget hint for surfaces)
     :json     JSON-schema type             (for ops/MCP introspection)
     :parse    (fn [string]) -> patch value — nil-safe; returns nil to OMIT the field
     :format   (fn [agent])  -> string      — seed text for an editor; never nil
     :options  (fn [agent])  -> [{:value :label :group}] | nil  (picker/select fields)
     :row      grouping tag — fields sharing a :row render side-by-side in the web form
     :web-only? true if the field is edited only on the web surface (e.g. the long prompt)
     :placeholder optional input placeholder"
  (:require [clojure.string :as str]
            [dvergr.model.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Shared parsers (the single home for tag/number/provider coercion)
;; ---------------------------------------------------------------------------

(defn parse-tags
  "Free-text tags ('research, prose  :ops') -> set of keywords. Tolerant of
   commas/whitespace and a leading colon. Blank -> #{} (an explicit clear)."
  [s]
  (->> (str/split (or s "") #"[,\s]+")
       (remove str/blank?)
       (map #(keyword (str/replace % #"^:" "")))
       set))

(defn- parse-double* [s]
  (when-not (str/blank? s)
    (try (Double/parseDouble (str/trim s)) (catch Exception _ nil))))

(defn- non-blank [s] (let [s (some-> s str/trim)] (when (seq s) s)))

;; ---------------------------------------------------------------------------
;; Option sources (model registry) — shared by every picker/select
;; ---------------------------------------------------------------------------

(defn provider-options
  "Distinct providers in the model registry, as {:value <keyword> :label}."
  [_]
  (registry/ensure-models-loaded!)
  (->> (registry/list-models) (keep :provider) distinct sort
       (mapv (fn [p] {:value p :label (name p)}))))

(defn model-options
  "All registered models as {:value <id> :label :group <provider>}, sorted by
   provider then id. Returns the full set; surfaces decide presentation (web
   groups by :group into optgroups, the TUI narrows to the agent's provider)."
  [_]
  (registry/ensure-models-loaded!)
  (->> (registry/list-models)
       (sort-by (juxt (comp str :provider) :id))
       (mapv (fn [m] {:value (:id m)
                      :label (or (:name m) (:id m))
                      :group (:provider m)}))))

;; ---------------------------------------------------------------------------
;; The canonical field list
;; ---------------------------------------------------------------------------

(def fields
  "Ordered agent-config fields. The single source of truth for every surface."
  [{:k :name :label "Display name" :type :text :json "string" :row 1
    :parse non-blank
    :format #(or (:name %) "")}
   {:k :provider :label "Provider" :type :text :json "string" :row 1
    :placeholder "fireworks"
    :parse (fn [s] (when-let [s (non-blank s)] (keyword s)))
    :format #(or (some-> (:provider %) name) "")
    :options provider-options}
   {:k :model :label "Model" :type :select :json "string"
    :parse non-blank
    :format #(or (:model %) "")
    :options model-options}
   {:k :tags :label "Tags / skills (space or comma separated)" :type :text :json "string" :row 2
    :parse parse-tags
    :format #(str/join " " (map name (:tags %)))}
   {:k :budget-dollars :param "budget" :label "Budget ($ / task)" :type :number :json "number" :row 2
    :parse parse-double*
    :format #(or (some-> (:budget-dollars %) str) "")}
   {:k :description :label "Description" :type :text :json "string"
    :parse (fn [s] (some-> s str/trim))
    :format #(or (:description %) "")}
   {:k :prompt :label "System prompt (persona)" :type :textarea :json "string" :web-only? true
    :parse (fn [s] s)
    :format #(or (:prompt %) "")}])

(defn param-name [f] (or (:param f) (name (:k f))))

(def by-k (into {} (map (juxt :k identity)) fields))

;; ---------------------------------------------------------------------------
;; Derivations
;; ---------------------------------------------------------------------------

(defn parse-params
  "Build an update-agent! patch from a string-keyed param map (web form / wire).
   A field contributes iff its param is present AND its :parse yields non-nil —
   so a blank box never clobbers, while an explicit empty (tags -> #{}) applies."
  [params]
  (reduce (fn [m f]
            (let [p (param-name f)]
              (if (contains? params p)
                (let [v ((:parse f) (get params p))]
                  (cond-> m (some? v) (assoc (:k f) v)))
                m)))
          {} fields))

(defn- field-entry
  "Malli :map entry for field `f` — keyed by the patch key, optional, typed from
   :json, carrying the label as its description."
  [f]
  [(:k f) {:optional true}
   [(if (= "number" (:json f)) :double :string) {:description (:label f)}]])

(defn update-schema
  "Malli schema for the :agent/update patch object — every editable field, all
   optional (a partial patch)."
  []
  (into [:map] (map field-entry) fields))

(defn create-schema
  "Malli schema for :agent/create — id (required) plus the editable fields."
  []
  (into [:map [:id [:string {:description "agent id"}]]] (map field-entry) fields))

;; ---------------------------------------------------------------------------
;; Normalization: one shape for every surface
;; ---------------------------------------------------------------------------

(defn- invalid! [k v why]
  (throw (ex-info (str "Invalid " (name k) " " (pr-str v) ": " why)
                  {:type ::invalid-field :field k :value v :dvergr.ops/status 400})))

(defn- ->tag [k x]
  (let [s (cond (keyword? x) (name x) (string? x) (str/replace (str/trim x) #"^:" "") :else nil)]
    (if (str/blank? s)
      (invalid! k x "a tag is a non-blank name")
      (keyword s))))

(defn- normalize-value
  "Field `k`'s value `v` in the typed shape the agent ops store, or nil to omit
   it. A string is parsed as the field's wire text (`:parse`); a typed value
   (keyword provider, set of keyword tags, number budget) is kept. Anything
   else is refused."
  [k v]
  (let [{:keys [parse json]} (by-k k)]
    (cond
      (nil? v) nil
      (string? v) (let [parsed (parse v)]
                    (when (and (nil? parsed) (= "number" json) (not (str/blank? v)))
                      (invalid! k v "not a number"))
                    parsed)
      (= :tags k) (if (or (set? v) (sequential? v))
                    (into #{} (map #(->tag k %)) v)
                    (invalid! k v "tags are a string or a collection of names"))
      (= :provider k) (if (keyword? v) v (invalid! k v "a provider is a name"))
      (= "number" json) (if (number? v) (double v) (invalid! k v "not a number"))
      :else (invalid! k v "expected a string"))))

(defn normalize
  "The agent field map `m` (an :agent/create body or an :agent/update patch) in
   the typed shape `dvergr.agent.ops` stores, whichever surface it came from:
   MCP, the JSON API and the REPL send wire text (`:tags \"a b\"`,
   `:provider \"openai\"`), the web form and host callers typed values
   (`#{:a :b}`, `:openai`). A field whose value parses to nothing is omitted,
   so a blank box never clobbers; a blank tag box clears the tags. Keys that
   are not fields pass through. A value of the wrong type throws (status 400)
   before anything is written."
  [m]
  (reduce-kv (fn [acc k v]
               (if (contains? by-k k)
                 (let [v' (normalize-value k v)]
                   (if (some? v') (assoc acc k v') acc))
                 (assoc acc k v)))
             {} (or m {})))
