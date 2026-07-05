(ns dvergr.audio.stt
  "Speech-to-text for agent channels (voice notes today; live-call
   transcript ingest and a web mic ride the same fn). Backend policy:
   SERVERLESS for services (pay-per-minute, no ops), NATIVE/local for
   sovereignty and dev — embedders must always be ABLE to run their own.

   Backends, first success wins:
   - :groq       whisper-large-v3-turbo (GROQ_API_KEY)
   - :openai     whisper-1 with a REAL key (fw_-prefixed LLM-compat
                 keys are skipped)
   - :native     in-process Moonshine v2 (pretrained-rstr on raster, no
                 python) when on the classpath + DVERGR_ASR_MODEL_DIR
                 (or SIMMIS_ASR_MODEL_DIR) set. NOTE: Moonshine is
                 streaming-native (stream-init/stream-push!) — the
                 designated engine for live transcription later.
   - :local      python faster-whisper via scripts/transcribe.py
                 (cwd-relative; DVERGR_WHISPER_SCRIPT overrides)

   The sibling `dvergr.audio.tts` (voice OUT for call participation)
   lands with the jitsi voice-bot work."
  (:require [hato.client :as hc]
            [jsonista.core :as json]
            [clojure.java.shell]
            [clojure.string :as str]
            [taoensso.telemere :as tel]))

(defonce ^:private native-model (atom nil))

(defn- transcribe-native [^bytes bytes ext]
  (when-let [model-dir (or (System/getenv "DVERGR_ASR_MODEL_DIR")
                           (System/getenv "SIMMIS_ASR_MODEL_DIR"))]
    (when-let [transcribe-fn (try (requiring-resolve 'pretrained.asr.moonshine/transcribe)
                                  (catch Throwable _ nil))]
      (let [tmp (java.io.File/createTempFile "voice" (str "." ext))]
        (try
          (java.nio.file.Files/write (.toPath tmp) bytes
                                     (make-array java.nio.file.OpenOption 0))
          (let [load-fn (requiring-resolve 'pretrained.asr.moonshine/load-model)
                model (or @native-model
                          (reset! native-model (load-fn model-dir)))]
            (transcribe-fn model (.getAbsolutePath tmp)))
          (catch Throwable t
            (tel/log! {:level :debug :id ::native-asr-failed
                       :data {:error (.getMessage t)}})
            nil)
          (finally (.delete tmp)))))))

(defn- transcribe-local [^bytes bytes ext]
  (let [script (or (System/getenv "DVERGR_WHISPER_SCRIPT") "scripts/transcribe.py")
        tmp (java.io.File/createTempFile "voice" (str "." ext))]
    (try
      (java.nio.file.Files/write (.toPath tmp) bytes
                                 (make-array java.nio.file.OpenOption 0))
      (let [{:keys [exit out err]} (clojure.java.shell/sh
                                    "python3" script
                                    (.getAbsolutePath tmp)
                                    (or (System/getenv "DVERGR_WHISPER_MODEL")
                                        (System/getenv "SIMMIS_WHISPER_MODEL")
                                        "base"))]
        (if (zero? exit)
          (let [t (str/trim out)] (when (seq t) t))
          (do (tel/log! {:level :debug :id ::local-whisper-failed
                         :data {:exit exit
                                :err (subs (str err) 0 (min 200 (count (str err))))}})
              nil)))
      (finally (.delete tmp)))))

(def ^:private backends
  [{:name :groq
    :url "https://api.groq.com/openai/v1/audio/transcriptions"
    :model "whisper-large-v3-turbo"
    :key-fn #(System/getenv "GROQ_API_KEY")}
   {:name :openai
    :url "https://api.openai.com/v1/audio/transcriptions"
    :model "whisper-1"
    :key-fn #(let [k (System/getenv "OPENAI_API_KEY")]
               (when (and k (not (str/starts-with? k "fw_"))) k))}
   {:name :native :native? true}
   {:name :local :local? true}])

(defn- try-backend [{:keys [name url model key-fn local? native?]} bytes mime ext]
  (cond
    native? (transcribe-native bytes ext)
    local? (transcribe-local bytes ext)
    :else
    (when-let [key (key-fn)]
      (let [resp (hc/post url
                          {:headers {"Authorization" (str "Bearer " key)}
                           :multipart [{:name "file" :content bytes
                                        :file-name (str "voice." ext)
                                        :content-type (or mime "audio/ogg")}
                                       {:name "model" :content model}]
                           :as :string
                           :timeout 60000
                           :throw-exceptions false})]
        (if (= 200 (:status resp))
          (:text (json/read-value (:body resp) json/keyword-keys-object-mapper))
          (do (tel/log! {:level :debug :id ::backend-failed
                         :data {:backend name :status (:status resp)}})
              nil))))))

(defn transcribe
  "Transcribe audio bytes → text (nil when all backends fail). `mime`
   guides the filename hint; whisper sniffs the container anyway."
  [{:keys [bytes mime]}]
  (try
    (let [ext (cond (and mime (.contains ^String mime "ogg")) "ogg"
                    (and mime (.contains ^String mime "webm")) "webm"
                    (and mime (.contains ^String mime "mp4")) "m4a"
                    (and mime (.contains ^String mime "mpeg")) "mp3"
                    (and mime (.contains ^String mime "wav")) "wav"
                    :else "ogg")
          ;; Whisper returns "" for silence/noise, and "" is truthy — coerce
          ;; blank → nil so `some` falls through to the next backend and, if
          ;; all are blank, the no-speech (nil) path is reached. A bare
          ;; "🎤 " message must never be posted.
          text (some #(let [t (try-backend % bytes mime ext)]
                        (when-not (str/blank? t) t))
                     backends)]
      (if text
        (do (tel/log! {:level :info :id ::transcribed
                       :data {:chars (count text)}})
            text)
        (do (tel/log! {:level :warn :id ::transcription-failed
                       :data {:backends (mapv :name backends)}})
            nil)))
    (catch Exception e
      (tel/log! {:level :warn :id ::transcription-error
                 :data {:error (.getMessage e)}})
      nil)))
