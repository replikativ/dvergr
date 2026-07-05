(ns dvergr.audio.record
  "Microphone capture for the local frontends (REPL, TUI). Records to a
   temp WAV via pw-record (PipeWire) or arecord (ALSA) — whichever is on
   PATH. Toggle-style so a reactive UI can start on one keystroke and stop
   on the next: `start!` returns a handle, `stop!` returns the bytes.

   Transcription is NOT here — the caller feeds the bytes to
   dvergr.audio.stt/transcribe, the one shared STT path every frontend
   uses. This ns only owns getting audio off the mic."
  (:require [clojure.java.shell :as sh]))

(defn- recorder-cmd
  "The record command vector writing to `wav-path`, or nil when neither
   pw-record nor arecord is available. 16 kHz mono — what whisper expects."
  [wav-path]
  (cond
    (zero? (:exit (sh/sh "which" "pw-record")))
    ["pw-record" "--rate" "16000" "--channels" "1" wav-path]
    (zero? (:exit (sh/sh "which" "arecord")))
    ["arecord" "-q" "-f" "S16_LE" "-r" "16000" "-c" "1" wav-path]
    :else nil))

(defn available?
  "True when a supported recorder (pw-record or arecord) is on PATH."
  []
  (or (zero? (:exit (sh/sh "which" "pw-record")))
      (zero? (:exit (sh/sh "which" "arecord")))))

(defn start!
  "Begin recording to a fresh temp WAV. Returns a handle {:proc :file},
   or nil when no recorder is available (caller should surface that)."
  []
  (let [tmp (java.io.File/createTempFile "voice" ".wav")]
    (if-let [cmd (recorder-cmd (.getAbsolutePath tmp))]
      {:proc (.start (ProcessBuilder. ^java.util.List cmd)) :file tmp}
      (do (.delete tmp) nil))))

(defn stop!
  "Stop a `start!` handle and return the recorded WAV bytes (deleting the
   temp file). Returns nil on a nil handle or read failure."
  [{:keys [^Process proc ^java.io.File file]}]
  (when (and proc file)
    (try
      (.destroy proc)
      (.waitFor proc 2 java.util.concurrent.TimeUnit/SECONDS)
      (java.nio.file.Files/readAllBytes (.toPath file))
      (finally (.delete file)))))
