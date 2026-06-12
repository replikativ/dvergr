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
