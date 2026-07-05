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

A blank result (silence / noise) is treated as *no speech* — the caller gets
`nil`, not an empty message.

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
429/5xx retry). The default model is set in `resources/models.edn` and
overridable with `DVERGR_VISION_MODEL` — see
[provider-setup.md](provider-setup.md).

### `vision/describe` — OCR + scene description

```clojure
(vision/describe "/drive/telegram/photo.jpg")
(vision/describe path {:prompt "read the receipt total"})
```

Returns free text: a detailed description with any visible text transcribed
verbatim. In the sandbox the path is read through the chat-ctx's muschel FS, so
worktree files and mounted drives (e.g. `/drive`) both work — bytes never enter
the SCI sandbox, only the extracted text comes back.

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
fns above. The drive is provided by the embedder through the `:store-file-fn`
cap and the shell `:mounts` hook (`muschel.fs.mount`, mounted at `/drive`) — see
[tools-and-sandbox.md](tools-and-sandbox.md#the-drive-mount). A built-in
content-addressed drive for the standalone daemon is on the roadmap; until then
document storage is an embedder capability.
