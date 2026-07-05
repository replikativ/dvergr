(ns dvergr.audio.record
  "Cross-platform microphone capture for the local frontends (REPL, TUI).
   Returns WAV bytes; transcription is elsewhere (dvergr.audio.stt).

   Two backends, in priority order:

   1. DVERGR_RECORD_CMD — a shell command template that records a WAV to
      the path substituted for `%s`. The escape hatch for any platform or
      an awkward audio setup (pick your device, use ffmpeg/sox, etc.):
        pw-record --target 'alsa_input.…C920…' %s     (Linux/PipeWire)
        arecord -D hw:1,0 -f S16_LE -r 44100 %s        (Linux/ALSA)
        ffmpeg -y -f avfoundation -i ':0' %s           (macOS)
        ffmpeg -y -f dshow -i audio='Microphone' %s    (Windows)
      Stopped by terminating the process; the recorder finalizes the file.

   2. javax.sound.sampled (default) — pure Java, no external process, works
      out of the box on macOS/Windows and well-configured Linux. Pin the
      input with DVERGR_AUDIO_DEVICE (a substring of a `(devices)` name)
      when the system default is wrong. Sample rate is auto-negotiated to
      whatever the device supports — whisper resamples, so any rate is fine.

   Toggle API for reactive UIs: `start!` opens capture, `stop!` ends it and
   returns the WAV bytes (nil when nothing was captured)."
  (:require [clojure.string :as str])
  (:import [javax.sound.sampled AudioSystem AudioFormat AudioInputStream
            AudioFileFormat$Type DataLine$Info TargetDataLine]
           [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.util.concurrent TimeUnit]))

;; ── Backend 1: configurable shell command ────────────────────────────────

(defonce ^{:doc "Runtime override for the record command (see set-record-cmd!).
  Takes precedence over DVERGR_RECORD_CMD — set it from the REPL to configure
  capture without an env var / restart."}
  cmd-override (atom nil))

(defn set-record-cmd!
  "Set (or clear, with nil) the record command at runtime. `%s` in `cmd` is
   replaced with the output WAV path. Overrides DVERGR_RECORD_CMD."
  [cmd]
  (reset! cmd-override (some-> cmd str/trim not-empty)))

(defn- record-cmd []
  (or @cmd-override (some-> (System/getenv "DVERGR_RECORD_CMD") str/trim not-empty)))

(defn- start-cmd! [cmd]
  (let [tmp (java.io.File/createTempFile "voice" ".wav")
        ;; %s → the output path; split on whitespace (paths here are temp,
        ;; no spaces). Callers wanting spaces can wrap in a script.
        argv (mapv #(if (= "%s" %) (.getAbsolutePath tmp) %)
                   (str/split cmd #"\s+"))]
    {:kind :cmd :proc (.start (ProcessBuilder. ^java.util.List argv)) :file tmp}))

(defn- stop-cmd! [{:keys [^Process proc ^java.io.File file]}]
  (try
    (.destroy proc)                       ; SIGTERM — recorders finalize the WAV
    (.waitFor proc 2 TimeUnit/SECONDS)
    (let [bs (java.nio.file.Files/readAllBytes (.toPath file))]
      (when (> (alength bs) 44) bs))      ; > bare WAV header ⇒ real audio
    (catch Throwable _ nil)
    (finally (.delete file))))

;; ── Backend 2: javax.sound.sampled ───────────────────────────────────────

(def ^:private candidate-formats
  ;; 16-bit signed little-endian; first rate/channels the device accepts wins.
  ;; whisper resamples, so the exact rate doesn't matter.
  (for [[rate ch] [[16000 1] [44100 1] [48000 1] [32000 1] [44100 2] [48000 2]]]
    (AudioFormat. (float rate) 16 ch true false)))

(defn- pick-mixer
  "The mixer to capture from: the first whose name contains
   DVERGR_AUDIO_DEVICE (case-insensitive), else nil (= system default)."
  []
  (when-let [want (some-> (System/getenv "DVERGR_AUDIO_DEVICE") str/lower-case not-empty)]
    (some (fn [mi] (when (str/includes? (str/lower-case (.getName mi)) want)
                     (AudioSystem/getMixer mi)))
          (AudioSystem/getMixerInfo))))

(defn devices
  "Names of mixers that can capture. Pass one (or a substring) as
   DVERGR_AUDIO_DEVICE to pin the input device."
  []
  (->> (AudioSystem/getMixerInfo)
       (filter (fn [mi]
                 (try (some #(.isLineSupported (AudioSystem/getMixer mi)
                                               (DataLine$Info. TargetDataLine %))
                            candidate-formats)
                      (catch Throwable _ false))))
       (mapv #(.getName %))))

(defn- open-java-line
  "Open a capture line on the chosen mixer (or default), negotiating the
   first supported format. Returns [line format] or nil."
  []
  (let [mixer (pick-mixer)]
    (some (fn [fmt]
            (try
              (let [info (DataLine$Info. TargetDataLine fmt)
                    line (if mixer (.getLine mixer info) (AudioSystem/getLine info))]
                (.open ^TargetDataLine line fmt)
                [line fmt])
              (catch Throwable _ nil)))
          candidate-formats)))

(defn- start-java! []
  (when-let [[^TargetDataLine line fmt] (open-java-line)]
    (let [baos (ByteArrayOutputStream.)
          running (atom true)
          reader (doto (Thread.
                        (fn []
                          (let [buf (byte-array 4096)]
                            (while @running
                              (let [n (.read line buf 0 (alength buf))]
                                (when (pos? n) (.write baos buf 0 n)))))))
                   (.setDaemon true))]
      (.start line)
      (.start reader)
      {:kind :java :line line :fmt fmt :baos baos :running running :reader reader})))

(defn- stop-java! [{:keys [^TargetDataLine line ^AudioFormat fmt
                           ^ByteArrayOutputStream baos running ^Thread reader]}]
  (try
    (reset! running false)
    (.stop line) (.flush line)
    (when reader (.join reader 1000))
    (.close line)
    (let [pcm (.toByteArray baos)
          fsz (.getFrameSize fmt)]
      (when (and (pos? (alength pcm)) (pos? fsz))
        (let [ais (AudioInputStream. (ByteArrayInputStream. pcm) fmt (quot (alength pcm) fsz))
              out (ByteArrayOutputStream.)]
          (AudioSystem/write ais AudioFileFormat$Type/WAVE out)
          (.toByteArray out))))
    (catch Throwable _ nil)))

;; ── Public toggle API ────────────────────────────────────────────────────

(defn available?
  "True when capture is possible (a configured command, or a Java line)."
  []
  (boolean
   (or (record-cmd)
       (try (some #(AudioSystem/isLineSupported (DataLine$Info. TargetDataLine %))
                  candidate-formats)
            (catch Throwable _ false)))))

(defn start!
  "Begin capturing. Returns a handle, or nil when no backend is available
   (caller should surface that)."
  []
  (try (if-let [cmd (record-cmd)] (start-cmd! cmd) (start-java!))
       (catch Throwable _ nil)))

(defn stop!
  "Stop a `start!` handle and return WAV bytes (nil when nothing captured)."
  [handle]
  (case (:kind handle)
    :cmd  (stop-cmd! handle)
    :java (stop-java! handle)
    nil))
