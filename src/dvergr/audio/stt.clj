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
                 python) when on the classpath + DVERGR_ASR_MODEL_DIR set.
                 NOTE: Moonshine is streaming-native (stream-init/
                 stream-push!) — the designated engine for live
                 transcription later.
   - :local      python faster-whisper via scripts/transcribe.py
                 (cwd-relative; DVERGR_WHISPER_SCRIPT overrides)

   The sibling `dvergr.audio.tts` (voice OUT for call participation)
   lands with the jitsi voice-bot work."
  (:require [dvergr.model.provider-errors :as provider-errors]
            [hato.client :as hc]
            [jsonista.core :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [taoensso.telemere :as tel])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(defonce ^:private native-model (atom nil))

(defn- transcribe-native [^bytes bytes ext]
  (when-let [model-dir (System/getenv "DVERGR_ASR_MODEL_DIR")]
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
                                        "base"))]
        (if (zero? exit)
          (str/trim out)
          (throw (ex-info (str/trim err) {:status exit}))))
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
      (try
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
            (throw (ex-info (str "HTTP " (:status resp) ": " (:body resp))
                            {:status (:status resp)}))))
        (catch Exception e
          (throw (ex-info (provider-errors/sanitize-message (.getMessage e) [key])
                          (select-keys (ex-data e) [:status]))))))))

(defn- transcribe-one [bytes mime ext]
  (loop [[backend & remaining] backends, attempts [], silence? false]
    (if backend
      (let [result (try
                     {:text (try-backend backend bytes mime ext)}
                     (catch Exception e
                       {:failure {:backend (:name backend)
                                  :status (or (:status (ex-data e)) :error)
                                  :message (provider-errors/sanitize-message (or (.getMessage e) (str (class e))))}}))]
        (if-let [failure (:failure result)]
          (do (tel/log! {:level :warn :id ::backend-failed :data failure})
              (recur remaining (conj attempts failure) silence?))
          (let [text (:text result)]
            (if (str/blank? text)
              (recur remaining attempts (or silence? (some? text)))
              text))))
      (when-not silence?
        (throw (ex-info "Speech-to-text failed for every available backend"
                        {:attempts attempts}))))))

(defn- wav-bytes [^bytes pcm]
  (let [size (alength pcm)
        header (doto (java.nio.ByteBuffer/allocate 44)
                 (.order java.nio.ByteOrder/LITTLE_ENDIAN)
                 (.put (.getBytes "RIFF" "US-ASCII"))
                 (.putInt (+ 36 size))
                 (.put (.getBytes "WAVEfmt " "US-ASCII"))
                 (.putInt 16) (.putShort (short 1)) (.putShort (short 1))
                 (.putInt 16000) (.putInt 32000)
                 (.putShort (short 2)) (.putShort (short 16))
                 (.put (.getBytes "data" "US-ASCII")) (.putInt size))
        out (byte-array (+ 44 size))]
    (System/arraycopy (.array header) 0 out 0 44)
    (System/arraycopy pcm 0 out 44 size)
    out))

(defn- chunk-directory []
  (try
    (Files/createTempDirectory "stt-"
                               (into-array FileAttribute
                                           [(PosixFilePermissions/asFileAttribute
                                             (PosixFilePermissions/fromString "rwx------"))]))
    (catch UnsupportedOperationException _
      (Files/createTempDirectory "stt-" (make-array FileAttribute 0)))))

(defn- chunk-file [^Path directory filename]
  (Files/createFile (.resolve directory filename) (make-array FileAttribute 0)))

(defn- delete-chunk-path! [^Path path]
  (Files/deleteIfExists path))

(defn- cleanup-chunks! [^Path directory]
  (doseq [path [(.resolve directory "input") (.resolve directory "output.raw") directory]]
    (try
      (delete-chunk-path! path)
      (catch Exception e
        (tel/log! {:level :warn :id ::cleanup-failed
                   :data {:path (str path) :message (provider-errors/sanitize-message (.getMessage e))}})))))

(defn- transcribe-chunks [bytes _ext {:keys [max-bytes seconds]}]
  (when-not (and (integer? max-bytes) (<= 46 max-bytes Integer/MAX_VALUE)
                 (number? seconds) (pos? seconds) (Double/isFinite (double seconds))
                 (>= seconds (/ 1.0 16000)))
    (throw (ex-info "Chunking needs :max-bytes >= 46 and positive :seconds (at least one sample)"
                    {:chunk {:max-bytes max-bytes :seconds seconds}})))
  (let [available? (try (zero? (:exit (shell/sh "ffmpeg" "-version")))
                        (catch java.io.IOException _ false))]
    (when-not available?
      (throw (ex-info "Audio chunking requires ffmpeg on PATH" {:tool :ffmpeg}))))
  (let [directory (chunk-directory)]
    (try
      (let [input (.toFile (chunk-file directory "input"))
            output (.toFile (chunk-file directory "output.raw"))
            samples (long (min (quot (- max-bytes 44) 2) (Math/floor (* 16000.0 seconds))))]
        (java.nio.file.Files/write (.toPath input) bytes
                                   (make-array java.nio.file.OpenOption 0))
        (let [{:keys [exit err]} (shell/sh "ffmpeg" "-nostdin" "-hide_banner" "-loglevel" "error"
                                           "-y" "-i" (.getAbsolutePath input) "-vn"
                                           "-ac" "1" "-ar" "16000" "-f" "s16le"
                                           (.getAbsolutePath output))]
          (when-not (zero? exit)
            (throw (ex-info (provider-errors/sanitize-message (str "ffmpeg audio decoding failed: " (str/trim err)))
                            {:tool :ffmpeg :status exit}))))
        (with-open [stream (java.io.FileInputStream. output)]
          (loop [offset 0, chunks []]
            (let [pcm (.readNBytes stream (int (* 2 samples)))]
              (if (zero? (alength pcm))
                {:text (when-let [texts (seq (keep (fn [{:keys [start-seconds text]}]
                                                     (when text
                                                       (str "[" start-seconds "s] " text))) chunks))]
                         (str/join "\n" texts))
                 :chunks chunks}
                (let [end (+ offset (quot (alength pcm) 2))
                      boundary {:start-seconds (/ offset 16000.0)
                                :end-seconds (/ end 16000.0)}
                      text (try (transcribe-one (wav-bytes pcm) "audio/wav" "wav")
                                (catch clojure.lang.ExceptionInfo e
                                  (throw (ex-info (.getMessage e)
                                                  (assoc (ex-data e) :chunk boundary) e))))]
                  (recur end (conj chunks (assoc boundary :text text)))))))))
      (finally
        (cleanup-chunks! directory)))))

(defn transcribe
  "Transcribe audio bytes to text, or nil for silence. Backend failures throw
   ex-info with ordered :attempts. Optional :chunk {:max-bytes n :seconds s}
   returns {:text offset-prefixed-text :chunks [{:start-seconds :end-seconds :text}]}.
   Chunking requires ffmpeg; bounds include the WAV header."
  [{:keys [bytes mime chunk]}]
  (let [ext (cond (and mime (str/includes? mime "ogg")) "ogg"
                  (and mime (str/includes? mime "webm")) "webm"
                  (and mime (str/includes? mime "mp4")) "m4a"
                  (and mime (str/includes? mime "mpeg")) "mp3"
                  (and mime (str/includes? mime "wav")) "wav"
                  :else "ogg")]
    (if chunk
      (transcribe-chunks bytes ext chunk)
      (transcribe-one bytes mime ext))))
