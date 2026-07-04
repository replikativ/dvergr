(ns dvergr.media.vision
  "Image → text for agents: describe/OCR an image via a vision LLM
   (serverless-first, same policy as dvergr.audio.stt). Sandbox
   surface: `vision/describe` reads the path through the chat-ctx's
   muschel FS (mounted drives included) and hands bytes here.

   Also the primitive the jitsi frame pipeline uses (screen-content
   descriptions → knowledge bases)."
  (:require [hato.client :as hc]
            [jsonista.core :as json]
            [taoensso.telemere :as tel]))

(def ^:private default-model
  "Fireworks vision model (their OpenAI-compatible chat endpoint takes
   image_url content parts). Override with DVERGR_VISION_MODEL."
  "accounts/fireworks/models/qwen2p5-vl-32b-instruct")

(defn- fireworks-key []
  (try
    (get-in ((requiring-resolve 'dvergr.model.providers/get-provider) :fireworks)
            [:config :api-key])
    (catch Throwable _ nil)))

(def ^:private default-prompt
  "Describe this image in detail. Transcribe any visible text verbatim.")

(defn describe
  "Describe image bytes → text, or nil on failure.
   opts: :prompt (default: detailed description + OCR), :model, :max-tokens."
  [^bytes bytes mime & [{:keys [prompt model max-tokens]}]]
  (when-let [key (fireworks-key)]
    (let [b64 (.encodeToString (java.util.Base64/getEncoder) bytes)
          data-url (str "data:" (or mime "image/jpeg") ";base64," b64)
          body {:model (or model
                           (System/getenv "DVERGR_VISION_MODEL")
                           default-model)
                :max_tokens (or max-tokens 1024)
                :messages [{:role "user"
                            :content [{:type "image_url"
                                       :image_url {:url data-url}}
                                      {:type "text"
                                       :text (or prompt default-prompt)}]}]}
          resp (hc/post "https://api.fireworks.ai/inference/v1/chat/completions"
                        {:headers {"Authorization" (str "Bearer " key)}
                         :content-type :json
                         :body (json/write-value-as-string body)
                         :as :string
                         :timeout 120000
                         :throw-exceptions false})]
      (if (= 200 (:status resp))
        (-> (json/read-value (:body resp) json/keyword-keys-object-mapper)
            :choices first :message :content)
        (do (tel/log! {:level :warn :id ::vision-failed
                       :data {:status (:status resp)
                              :body (subs (str (:body resp)) 0
                                          (min 300 (count (str (:body resp)))))}})
            nil)))))
