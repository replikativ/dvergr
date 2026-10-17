# Media — voice, vision & files

dvergr agents work with more than text: speech in, images in, documents in.
The primitives are shared across every frontend so behaviour is uniform — the
web mic, Telegram voice notes, the REPL, and the TUI all transcribe through the
same speech-to-text path, and images/PDFs go through the same vision + document
extractors whether an agent reaches them in its sandbox or a channel feeds them
in.

## Speech-to-text (ASR)

`dvergr.audio.stt/transcribe` turns audio bytes into text. It tries a list of
backends, first success wins:

| Backend | Needs | Notes |
|---|---|---|
| `:groq` | `GROQ_API_KEY` | whisper-large-v3-turbo (serverless, fast) |
| `:openai` | a **real** `OPENAI_API_KEY` | whisper-1; `fw_`-prefixed (Fireworks) keys are skipped |
| `:native` | `DVERGR_ASR_MODEL_DIR` + Moonshine on the classpath | in-process, no python; streaming-capable |
| `:local` | `python3` + `faster_whisper` | `scripts/transcribe.py` (`DVERGR_WHISPER_SCRIPT` / `DVERGR_WHISPER_MODEL` override) |

Without `:chunk`, `transcribe` returns a string, or `nil` for a successful
blank transcript (silence / noise). Backend failures fall through to the next
backend and are logged with `:backend`, `:status` and `:message`. If every
available backend fails, it throws `ex-info` with ordered data, for example:

```clojure
{:attempts [{:backend :groq :status 429 :message "HTTP 429: rate limited"}
            {:backend :local :status 1 :message "missing faster_whisper"}]}
```

HTTP statuses and local process exit codes are numbers; other exceptions use
`:status :error`. Unconfigured backends are skipped, so no available backend
produces `{:attempts []}`. A successful blank transcript still returns `nil`
when the other backends fail.

Chunking is opt-in and requires `ffmpeg` on PATH:

```clojure
(stt/transcribe {:bytes audio-bytes :mime "audio/mp4"
                 :chunk {:max-bytes 20000000 :seconds 600}})
;; => {:text "[0.0s] first part\n[600.0s] second part"
;;     :chunks [{:start-seconds 0.0 :end-seconds 600.0 :text "first part"}
;;              {:start-seconds 600.0 :end-seconds 720.5 :text "second part"}]}
```

Audio is decoded into mono 16 kHz PCM and split into WAV uploads, each bounded
by both `:max-bytes` (including the 44-byte WAV header) and `:seconds`. Both
bounds are required; max-bytes must be an integer from 46 to 2147483647 and
seconds must be finite and at least one sample (1/16000 second). Boundaries
are seconds from the start of the decoded audio, contiguous and without overlap;
the last chunk may be shorter. Chunks are transcribed sequentially, with backend
fallback for each. Text is joined with newlines and `[<start-seconds>s]` offsets.
Silent chunks have `:text nil` and remain in `:chunks` but are omitted from the
joined text, which is `nil` when all chunks are silent. A failed chunk throws
with `:attempts` plus `:chunk {:start-seconds … :end-seconds …}`; no partial
transcript is returned. Missing ffmpeg and decoding errors throw clear
`ex-info` errors with `:tool :ffmpeg`. Temporary audio files are deleted on
success and failure. Chunking is off by default, preserving the string/nil
return shape for existing callers.

### Where you speak

| Frontend | How | Result |
|---|---|---|
| **Web** | mic button → `POST /rooms/<slug>/voice` (raw audio body) | 🎤-prefixed message in the room |
| **Telegram** | send a voice note | transcript posted to the chat's room (see [channels.md](channels.md)) |
| **REPL** | `(dvergr.clients.client/voice! room)` — records until you press Enter | posts the 🎤 transcript |
| **TUI** | `^R` to start, `^R` to stop | transcribes off the UI thread, posts like typed input |

All four produce the same `🎤 <transcript>` message flowing through the room's
normal posting path — an agent sees it exactly like typed input.

### Recording (REPL + TUI)

Getting audio off the mic is handled by `dvergr.audio.record`, with two
backends:

1. **`DVERGR_RECORD_CMD`** — a shell command template; `%s` is replaced with the
   output WAV path. The escape hatch for any platform or an awkward audio setup
   (pick a device, use ffmpeg/sox):

   ```bash
   # Linux / PipeWire — pin a specific source
   export DVERGR_RECORD_CMD="pw-record --target 'alsa_input.…' %s"
   # Linux — via ffmpeg + PulseAudio
   export DVERGR_RECORD_CMD="ffmpeg -hide_banner -loglevel error -y -f pulse -i <source> -ar 16000 -ac 1 %s"
   # macOS
   export DVERGR_RECORD_CMD="ffmpeg -y -f avfoundation -i ':0' %s"
   ```

2. **`javax.sound.sampled`** (default) — pure Java, cross-platform
   (macOS/Windows/well-configured Linux), no external process. Pin the input
   with `DVERGR_AUDIO_DEVICE` (a substring of a device name) when the system
   default is wrong; `(dvergr.audio.record/devices)` lists the capture devices.
   The sample rate is auto-negotiated — whisper resamples, so any rate works.

> **Linux note:** Java Sound's capture integration is unreliable on
> PulseAudio/PipeWire. If `^R` reports *"no audio captured"*, set
> `DVERGR_RECORD_CMD` (option 1) — it's the robust path. You can also configure
> it at runtime without a restart: `(dvergr.audio.record/set-record-cmd! "…%s")`.

## Vision — images to text and data

`dvergr.media.vision` describes and extracts from images via a vision LLM,
routed through `dvergr.model.chat/chat` (so it gets provider/key resolution and
429/5xx retry). Configure per-call `:model`, `DVERGR_VISION_MODEL`, or
`:defaults :vision-model` in models.edn, in that precedence order. The bundled
default is Fireworks `accounts/fireworks/models/kimi-k3`; K3 and
`glm-5p3-flash` accept images. The registered provider must serve the chosen
image-capable model; known routing mismatches fail before HTTP with configuration
guidance. See [provider-setup.md](provider-setup.md).

### `vision/describe` — OCR + scene description

```clojure
(vision/describe "/drive/telegram/photo.jpg")
(vision/describe path {:prompt "read the receipt total"})
```

Returns free text: a detailed description with any visible text transcribed
verbatim. In the sandbox the path is read through the chat-ctx's muschel FS, so
workspace files and mounted drives (e.g. `/drive`) both work — bytes never enter
the SCI sandbox, only the extracted text comes back. Failed or empty descriptions
throw. Provider failures include `:status` (HTTP status), `:message` (the provider
message), and `:model` in ex-data, including when retries are exhausted.

### `vision/extract` — structured data (invoices, receipts → JSON)

For business documents where you want fields, not prose:

```clojure
(vision/extract "/drive/inbox/invoice.jpg"
  {:schema "invoice_number, date (ISO), vendor, subtotal (number),
            vat (number), total (number), currency"
   :verify-fields [:total :invoice_number]})
;; => {:data {:vendor "ACME Corporation" :date "2026-07-03"
;;            :total 1190.0 :currency "EUR" …}
;;     :verified {:total true :invoice_number true}}
```

It is deliberately defensive against the way a chatty VLM will invent a
plausible-but-wrong number:

- **Schema-constrained** — the model is told to emit *only* JSON and to use
  `null` rather than guess an absent field.
- **Strict parse**, tolerant of code fences / surrounding prose.
- **Per-field re-verification** (`:verify-fields`) — each listed field is
  re-read with a second targeted pass and compared (numbers numerically,
  strings on alphanumerics); mismatches land in `:issues`.

`extract` returns `{:data … :verified … :issues …}` or `{:error …}`. It
**reduces, not eliminates** hallucination — for accounting, still validate
deterministically (totals reconcile, debits = credits, dates parse) before
turning `:data` into a transaction.

## Documents — `doc/extract-text`

```clojure
(doc/extract-text "/drive/telegram/report.pdf")   ; PDF / text → string
```

Extracts plain text from PDFs and text formats through the same muschel-FS path
as vision, so mounted drives work.

## Files in — the `/drive` mount

Channels that receive files (Telegram documents/photos) persist them so agents
can read them at `/drive` in their shell (`ls`, `cat`, `grep`) and via the media
fns above. The daemon installs a built-in content-addressed drive
(`dvergr.drive.*`): each room's drive is mounted at `/drive` in its shell
(`muschel.fs.mount`), and uploads land there through
`dvergr.drive.integration/store-upload!` (Telegram files under `/drive/telegram/`). Blobs
go to a filestore under `.dvergr/blobs` unless `:blob-store` names another konserve store;
see [tools-and-sandbox.md](tools-and-sandbox.md#the-drive-mount).
