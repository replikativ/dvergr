(ns dvergr.media.vision
  "Image → text for agents: describe/OCR an image via a vision LLM
   (serverless-first, same policy as dvergr.audio.stt). Sandbox
   surface: `vision/describe` reads the path through the chat-ctx's
   muschel FS (mounted drives included) and hands bytes here.

   `vision/extract` is the structured-data path for business documents
   (invoices, receipts, statements → JSON / datahike tx). It is
   deliberately defensive: the model is told to emit ONLY schema-shaped
   JSON and to use null rather than guess, the output is parsed
   strictly, and named critical fields can be re-verified with a second
   targeted pass — because a chatty VLM will otherwise emit a
   plausible-but-wrong number that no spell-checker catches. Callers
   doing accounting MUST still validate deterministically (totals
   reconcile, debits = credits) before transacting.

   Also the primitive the jitsi frame pipeline uses (screen-content
   descriptions → knowledge bases)."
  (:require [dvergr.model.chat :as chat]
            [clojure.string :as str]
            [jsonista.core :as json]
            [taoensso.telemere :as tel]))

(def ^:private default-model
  "Vision model (registered in models.edn; OpenAI-compatible chat endpoint
   takes image_url content parts). Kimi K2.6 is the only image-capable model
   deployed on the current Fireworks account (probed 2026-07-05) and it reads
   business documents accurately. Override with DVERGR_VISION_MODEL — e.g. a
   dedicated Qwen-VL once it's deployed (cheaper per token)."
  "accounts/fireworks/models/kimi-k2p6")

(defn- model-id [model]
  (or model (System/getenv "DVERGR_VISION_MODEL") default-model))

(defn- image-message [data-url text]
  {:role "user"
   :content [{:type "image_url" :image_url {:url data-url}}
             {:type "text" :text text}]})

(defn- ->data-url [^bytes bytes mime]
  (str "data:" (or mime "image/jpeg") ";base64,"
       (.encodeToString (java.util.Base64/getEncoder) bytes)))

(def ^:private default-prompt
  "Describe this image in detail. Transcribe any visible text verbatim.")

(defn describe
  "Describe image bytes → text, or nil on failure. Routes through
   dvergr.model.chat/chat (provider/key resolution + 429/5xx retry), sending
   the image as a base64 data-url content part.
   opts: :prompt (default: detailed description + OCR), :model, :max-tokens."
  [^bytes bytes mime & [{:keys [prompt model max-tokens]}]]
  (let [messages [(image-message (->data-url bytes mime) (or prompt default-prompt))]]
    (try
      (:content (chat/chat messages
                           {:model (model-id model)
                            :max-tokens (or max-tokens 1024)}))
      (catch Throwable t
        (tel/log! {:level :warn :id ::vision-failed :data {:error (.getMessage t)}})
        nil))))

;; ── Structured extraction ────────────────────────────────────────────────

(def ^:private json-mapper (json/object-mapper {:decode-key-fn keyword}))

(defn- strip-fence
  "Pull the JSON object out of a model reply — tolerate ```json fences and
   surrounding prose by taking the outermost {...} span."
  [s]
  (let [s (str/trim (or s ""))
        s (-> s (str/replace #"(?s)^```(?:json)?\s*" "") (str/replace #"(?s)\s*```$" ""))
        i (str/index-of s "{")
        j (str/last-index-of s "}")]
    (if (and i j (< i j)) (subs s i (inc j)) s)))

(defn- parse-json [s]
  (try (json/read-value (strip-fence s) json-mapper)
       (catch Throwable _ ::invalid)))

(defn- num-in
  "First numeric token in `s` as a double, or nil. Tolerates thousands
   separators and currency symbols: \"1,190.00 EUR\" → 1190.0."
  [s]
  (some-> (re-find #"-?\d[\d,]*(?:\.\d+)?" (str s))
          (str/replace "," "")
          (Double/parseDouble)))

(defn- values-agree?
  "Does a re-read `reread` confirm the extracted `claimed` value? Type-aware,
   so we don't raise false mismatches on cosmetic noise (a re-read of
   \"1190.00 EUR\" confirms the number 1190.0; \"INV-2026-0042.\" confirms the
   string). Numbers compare numerically (half-cent tolerance); strings
   compare on alphanumerics with containment either way."
  [claimed reread]
  (let [r (str/trim (str reread))]
    (cond
      (str/blank? r)                 (nil? claimed)
      (= "null" (str/lower-case r))  (nil? claimed)
      (number? claimed)              (when-let [rn (num-in r)]
                                       (< (Math/abs (- (double claimed) rn)) 0.005))
      :else
      (let [norm (fn [x] (-> (str x) str/lower-case (str/replace #"[^a-z0-9]" "")))
            c (norm claimed) rr (norm r)]
        (and (seq c) (or (= c rr) (str/includes? rr c) (str/includes? c rr)))))))

(defn- verify-field
  "Second targeted pass: ask ONLY for one field's verbatim value and check it
   confirms the extracted one. Defends against plausible-value hallucination
   that schema-validation can't catch. Returns a bool, or nil when the re-read
   itself failed (treated as unverified, not as mismatch)."
  [data-url model field claimed]
  (let [reread (try
                 (:content (chat/chat
                            [(image-message
                              data-url
                              (str "Look at this document and report ONLY the exact "
                                   "verbatim value of the field \"" (name field) "\". "
                                   "Reply with just the value, no label, no prose. "
                                   "If it is not present, reply exactly: NULL"))]
                            ;; Generous budget: reasoning models (e.g. Kimi)
                            ;; spend tokens thinking before the short answer —
                            ;; too small a cap yields empty content.
                            {:model model :max-tokens 512}))
                 (catch Throwable _ nil))]
    (when reread
      (boolean (values-agree? claimed reread)))))

(defn extract
  "Image bytes → structured data extracted against `schema`, defensively.

   `schema` describes the fields to pull — either a human-readable string
   (\"invoice_number, date (ISO), vendor, total (number), line_items []\") or
   an EDN map/JSON-schema which is pr-str'd into the prompt. The model is
   instructed to emit ONLY a JSON object and to use null rather than guess.

   opts:
     :schema        (required) field description, as above
     :model         vision model id (default: the registered Qwen3-VL-32B)
     :verify-fields collection of field keywords to re-verify with a second
                    targeted pass — use for the load-bearing numbers
                    (:total, :amount_due …). Each gets one extra LLM call.
     :max-tokens    output cap (default 4096 — reasoning models spend output
                    budget thinking before the JSON, so keep it generous)
     :instructions  extra guidance appended to the extraction prompt

   Returns:
     {:data     {<parsed JSON, keyword keys>}
      :verified {<field> true|false}   ; only when :verify-fields given
      :issues   [<strings>]}           ; unverified/mismatched fields
   or {:error <keyword> :raw <model text>} when no JSON could be parsed.

   NOTE: this reduces but does not eliminate hallucination. For accounting,
   still validate deterministically (totals reconcile, debits = credits,
   dates parse) before turning :data into a datahike transaction."
  [^bytes bytes mime {:keys [schema model verify-fields max-tokens instructions]}]
  (when-not schema
    (throw (ex-info "vision/extract requires :schema" {})))
  (let [mid      (model-id model)
        data-url (->data-url bytes mime)
        schema-s (if (string? schema) schema (pr-str schema))
        prompt   (str "Extract the following fields from this document and return "
                      "them as a SINGLE JSON object. Output ONLY the JSON — no prose, "
                      "no markdown fences. Transcribe values verbatim from the "
                      "document. If a field is absent or unreadable, use null — do "
                      "NOT guess or infer a plausible value.\n\nFields:\n" schema-s
                      (when instructions (str "\n\n" instructions)))
        reply    (try
                   (:content (chat/chat [(image-message data-url prompt)]
                                        {:model mid :max-tokens (or max-tokens 4096)}))
                   (catch Throwable t
                     (tel/log! {:level :warn :id ::extract-failed
                                :data {:error (.getMessage t)}})
                     nil))
        parsed   (when reply (parse-json reply))]
    (cond
      (nil? reply)        {:error :no-response}
      (= ::invalid parsed) {:error :invalid-json :raw reply}
      :else
      (let [verified (when (seq verify-fields)
                       (into {} (for [f verify-fields]
                                  [f (verify-field data-url mid f (get parsed f))])))
            issues   (for [[f ok?] verified :when (not ok?)]
                       (str "field " f " not verified against a second read"))]
        (cond-> {:data parsed}
          verified      (assoc :verified verified)
          (seq issues)  (assoc :issues (vec issues)))))))
