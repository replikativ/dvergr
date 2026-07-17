(ns dvergr.model.quirks
  "Provider-specific message quirks, shared across the provider implementations
   and the agent turn loop so the workaround lives in exactly one place.

   Currently: Kimi K2 tool-ID rewriting. Kimi K2 (via the OpenAI-compatible API)
   requires tool-call IDs in the format `functions.{func_name}:{idx}` with a
   global counter — it rejects the opaque IDs other providers use. We rewrite
   both the assistant tool-use IDs and the matching tool-result `tool-use-id`s
   on the internal (datahike-shaped) message vector before formatting for the
   wire.

   Also: Kimi-thinking tool-token leak. Kimi K2.6 is a reasoning model whose
   tool calls are wrapped in special tokens (`<|tool_calls_section_begin|>`,
   `<|tool_call_argument_begin|>`, `<|tool_calls_section_end|>`, …). The
   OpenAI-compatible endpoint is meant to parse these into structured `tool_calls`,
   but on Fireworks the thinking-mode parser sometimes fails to separate them from
   the prose and leaks the RAW delimiters (and a `Tool calls: [...]` echo) into the
   assistant `content`. Fireworks has acknowledged this and is working with Moonshot
   on a fix; until then `strip-kimi-tool-tokens` removes exactly those leaked tokens
   — gated to Kimi models only (the `:kimi-tool-id-format?` quirk), nothing else."
  (:require [clojure.string :as str]))

(defn strip-kimi-tool-tokens
  "Remove ONLY Kimi K2's leaked tool-call control tokens from assistant text
   `content` — the `<|tool_call…|>`/`<|tool_calls_section…|>` delimiters (and the
   span between them, which is serialized tool-call args, not prose) plus a leading
   `Tool calls: [...]` echo. Deliberately surgical: it touches nothing but these
   documented Kimi tokens, so a clean reply passes through untouched. Apply ONLY
   for Kimi models. nil → nil."
  [content]
  (when content
    (-> (str content)
        ;; A leading `Tool calls: ["foo"]N` echo Kimi sometimes prepends.
        (str/replace #"(?m)^[ \t]*Tool calls:[ \t]*\[[^\]\n]*\][ \t]*\d*" "")
        ;; A leaked section: first delimiter → its section-end (or end-of-string
        ;; if the close didn't make it). Non-greedy, so prose after a closed
        ;; section is preserved.
        (str/replace #"(?s)<\|tool_call[^|]*\|>.*?(?:<\|tool_calls_section_end\|>|\z)" "")
        ;; Any remaining isolated delimiter token.
        (str/replace #"<\|tool_call[^|]*\|>" "")
        str/trim)))

(defn build-kimi-tool-id-mapping
  "Build a mapping from original tool IDs to Kimi-format IDs.
   Kimi K2 requires IDs in format: functions.{func_name}:{idx}
   where idx is a global counter starting at 0."
  [messages]
  (let [counter (atom 0)
        mapping (atom {})]
    (doseq [msg messages]
      (when-let [tool-uses (:message/tool-uses msg)]
        (doseq [tu tool-uses]
          (let [orig-id (:tool-use/id tu)
                func-name (:tool-use/name tu)
                kimi-id (str "functions." func-name ":" @counter)]
            (swap! mapping assoc orig-id kimi-id)
            (swap! counter inc)))))
    @mapping))

(defn rewrite-kimi-tool-ids
  "Rewrite tool IDs in messages to Kimi K2 format.
   Returns messages with IDs transformed to functions.{name}:{idx} format."
  [messages]
  (let [id-mapping (build-kimi-tool-id-mapping messages)]
    (mapv (fn [msg]
            (cond-> msg
              ;; Rewrite tool-use IDs in assistant messages
              (:message/tool-uses msg)
              (update :message/tool-uses
                      (fn [tool-uses]
                        (mapv (fn [tu]
                                (if-let [new-id (get id-mapping (:tool-use/id tu))]
                                  (assoc tu :tool-use/id new-id)
                                  tu))
                              tool-uses)))
              ;; Rewrite tool-use-id in tool-result messages
              (:message/tool-use-id msg)
              (update :message/tool-use-id
                      (fn [orig-id]
                        (get id-mapping orig-id orig-id)))))
          messages)))

;; ============================================================================
;; GLM tool-call leak (Fireworks)
;; ============================================================================
;; GLM (Z.ai) models were trained to emit tool calls in an XML-style envelope:
;;   <tool_call>{name} <arg_key>{k}</arg_key> <arg_value>{v}</arg_value> … </tool_call>
;; Fireworks' OpenAI-compatible endpoint is meant to parse that into structured
;; `tool_calls`, but — exactly like the Kimi leak above — it intermittently
;; fails and dumps the RAW envelope into the assistant `content` with an empty
;; `tool_calls` array (often prefixed with a `Tool calls: ["name"]` echo). The
;; tool then never executes and the turn dies on a garbage message (this is what
;; stranded Vár's intake run). GLM 5.2 runs the structured path more reliably,
;; but the leak is intermittent, so we keep this defense gated on the
;; `:glm-tool-format?` quirk. Two operations:
;;   parse-glm-tool-calls — RECOVER the call(s) into executable tool_use blocks
;;   strip-glm-tool-tokens — SCRUB the leaked envelope from displayed content

(def ^:private glm-arg-pair-re
  ;; <arg_key>K</arg_key> … <arg_value>V</arg_value>  (V may span lines / hold code)
  #"(?s)<arg_key>(.*?)</arg_key>\s*<arg_value>(.*?)</arg_value>")

(defn- glm-arg->value
  "GLM arg values are strings on the wire; coerce a JSON scalar (number/bool/
   null/quoted-string) but leave anything else (code, prose) as the raw string."
  [^String v]
  (let [t (str/trim v)]
    (if (re-matches #"-?\d+(\.\d+)?|true|false|null|\".*\"" t)
      (try ((requiring-resolve 'jsonista.core/read-value) t) (catch Exception _ v))
      v)))

(defn- envelope->args
  "The one canonical reader of GLM's `<arg_key>K</arg_key><arg_value>V</arg_value>`
   envelope: parse every complete pair out of a string into a `{keyword value}`
   map (values JSON-scalar-coerced via `glm-arg->value`); `{}` when the envelope
   isn't present. The sanitize/parse paths all share this instead of each
   re-walking `glm-arg-pair-re`."
  [s]
  (into {} (map (fn [[_ k v]] [(keyword (str/trim k)) (glm-arg->value v)])
                (re-seq glm-arg-pair-re (str s)))))

(defn clean-tool-name
  "Strip a leaked GLM envelope (or any stray non-identifier tail) off a tool
   name: `clojure_eval<arg_key>code…` → `clojure_eval`. A real tool name is an
   identifier — cut at the first char that can't be part of one. Returns the
   trimmed head, or the input unchanged when it is already clean/blank."
  [name]
  (if (string? name)
    (let [head (re-find #"^[A-Za-z0-9_.:-]+" (str/trim name))]
      (or head name))
    name))

(defn sanitize-tool-call
  "Make a tool-call map API-safe before it is persisted OR replayed: strip any
   leaked envelope off the name, and coerce a nil/blank argument map to `{}` —
   `null` arguments and a non-identifier function name are each a hard 400 on
   OpenAI-compatible endpoints, and once such a call lands in durable history
   EVERY later turn 400s (a single bad emission bricks the conversation). Works
   on the agent's `{:id :name :input}` shape; leaves a clean call untouched.
   When the name carried an inline `<arg_key>…<arg_value>…` payload and no
   structured input survived, recover the args from it."
  [{:keys [name input] :as call}]
  (let [envelope-pairs (when (and (string? name) (str/includes? name "<arg_key>"))
                         (envelope->args name))
        input' (cond
                 (map? input)     input
                 (seq envelope-pairs) envelope-pairs
                 :else            {})]
    (assoc call :name (clean-tool-name name) :input input')))

(defn parse-glm-tool-calls
  "Extract tool call(s) leaked into `content` as GLM's <arg_key>/<arg_value>
   envelope. Returns [{:name str :input {kw val}} …] or nil when the pattern
   isn't present. Handles both the `<tool_call>NAME …</tool_call>` form and the
   Fireworks `Tool calls: [\"NAME\"] <arg_key>…` echo form. A single leaked
   block (our observed case) yields one call; the arg pairs between successive
   names are attributed to the preceding name."
  [content]
  (when (and content (re-find #"<arg_key>" (str content)))
    (let [s (str content)
          ;; Names appear either as <tool_call>NAME or in a Tool calls: ["A" "B"] echo.
          names (or (seq (map second (re-seq #"<tool_call>\s*([A-Za-z0-9_.-]+)" s)))
                    (some->> (re-find #"Tool calls:\s*\[([^\]]*)\]" s)
                             second
                             (re-seq #"\"([^\"]+)\"")
                             (map second)
                             seq))
          args (envelope->args s)]
      (when (and (seq names) (seq args))
        ;; Single-call is the common leak; when multiple names appear we give
        ;; every parsed pair to the first (safe: our tools take one arg-map).
        ;; clean-tool-name strips the envelope from the Fireworks echo form
        ;; `Tool calls: ["clojure_eval<arg_key>…"]`, whose quoted name captures
        ;; the whole leaked payload.
        [{:name (clean-tool-name (first names))
          :input args}]))))

(defn sanitize-glm-structured-call
  "GLM also leaks its envelope INTO the structured tool_call NAME field:
   `clojure_eval<arg_key>code</arg_key><arg_value>…` (often truncated
   mid-value when the emission was cut). Left alone, the call fails as
   an unknown tool AND the invalid name poisons the history — the next
   turn 400s. Split off the real name; parse complete arg pairs into
   :input; an unterminated trailing <arg_value> contributes its partial
   text so the tool fails informatively instead of the turn dying."
  [{:keys [name input] :as call}]
  (if (and name (str/includes? (str name) "<arg_key>"))
    (let [n (str name)
          real-name (str/trim (subs n 0 (str/index-of n "<arg_key>")))
          args (envelope->args n)
          ;; An unterminated trailing <arg_value> yields no complete pair; keep
          ;; its partial (raw, uncoerced) text so the tool fails informatively
          ;; instead of the turn dying.
          partial-pair (when (empty? args)
                         (when-let [[_ k v] (re-find #"(?s)<arg_key>(.*?)</arg_key>\s*<arg_value>(.*)\z" n)]
                           {(keyword (str/trim k)) v}))]
      (assoc call
             :name real-name
             :input (merge args partial-pair input)))
    call))

(defn strip-glm-tool-tokens
  "Remove GLM's leaked tool-call envelope from assistant text `content` — the
   `Tool calls: [...]` echo plus every `<tool_call>…`, `<arg_key>…</arg_key>`,
   `<arg_value>…</arg_value>` span — leaving genuine prose intact. Apply ONLY
   for GLM models (the `:glm-tool-format?` quirk). nil → nil."
  [content]
  (when content
    (-> (str content)
        (str/replace #"(?m)^[ \t]*Tool calls:[ \t]*\[[^\]\n]*\][ \t]*\d*" "")
        (str/replace #"(?s)<tool_call>.*?</tool_call>" "")
        (str/replace glm-arg-pair-re "")
        ;; any stray unpaired tags
        (str/replace #"(?s)</?(tool_call|arg_key|arg_value)>" "")
        str/trim
        (as-> c (when (seq c) c)))))

;; ---------------------------------------------------------------------------
;; Code fumbled into the PROSE channel
;;
;; The leak above has a sibling that no recovery can catch: the model emits its
;; code as plain content with NO envelope and stop_reason=stop. Nothing marks it
;; as a tool call, so the agent loop reads "no tool calls" as "the agent has
;; finished speaking", posts the fragment to the room, and ends the turn — the
;; agent appears to spill code and quit mid-task. (Observed 2026-07-13: GLM
;; miscounted a brace, its recovered call failed to parse, and it then re-emitted
;; the repaired code as a message beginning with a stray `)`.)
;;
;; Detection is deliberately CONSERVATIVE. A reply may legitimately contain code
;; — explaining a snippet is normal — so we flag only content that cannot be a
;; deliberate message to a human:
;;   - it opens with a CLOSING delimiter, or
;;   - it closes more delimiters than it opens (a truncated / continued block), or
;;   - it still carries a tool-call envelope after scrubbing.
;; Balanced code in a reply is left alone.
;; ---------------------------------------------------------------------------

(defn- delimiter-balance
  "Openers minus closers, ignoring delimiters inside strings, character literals
   and line comments — those are text, not structure."
  [^String s]
  (loop [i 0, depth 0, in-str? false, in-cmt? false, escaped? false]
    (if (>= i (count s))
      depth
      (let [c (.charAt s i)]
        (cond
          escaped?  (recur (inc i) depth in-str? in-cmt? false)
          in-str?   (case c
                      \\ (recur (inc i) depth true in-cmt? true)
                      \" (recur (inc i) depth false in-cmt? false)
                      (recur (inc i) depth true in-cmt? false))
          in-cmt?   (recur (inc i) depth in-str? (not= c \newline) false)
          :else     (case c
                      \" (recur (inc i) depth true false false)
                      \; (recur (inc i) depth false true false)
                      (\( \[ \{) (recur (inc i) (inc depth) false false false)
                      (\) \] \}) (recur (inc i) (dec depth) false false false)
                      (recur (inc i) depth false false false)))))))

(def ^:private emoticon-re
  ;; :) ;) :-) =( 8] … — a smiley's paren is punctuation, not structure. Without
  ;; this, "Sounds good :)" balances to -1 and reads as truncated code.
  #"[:;=8][-o^]?[)\](\[]")

(defn code-fragment?
  "True when assistant `content` is code fumbled into the prose channel rather
   than a message meant for a human. See the note above — conservative by
   design: balanced code inside a reply is NOT a fragment, and neither is prose
   that merely contains delimiters."
  [content]
  (let [s (str/trim (str content))
        ;; balance over prose-stripped text: emoticons are not delimiters
        structural (str/replace s emoticon-re "")]
    (boolean
     (and (seq s)
          (or (contains? #{\) \] \}} (first s))
               ;; more closers than openers — a truncated or continued block.
               ;; Requires an opener to exist at all, so stray punctuation in
               ;; prose can't trip it.
              (and (re-find #"[(\[{]" structural)
                   (neg? (delimiter-balance structural)))
              (re-find #"<arg_key>|<tool_call>" s))))))
