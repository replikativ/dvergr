# Channels — Telegram

A **channel** bridges a remote messaging surface into dvergr Rooms: each remote
user gets an actor, each chat gets a per-chat Room, and agent replies flow back
out. Telegram is the reference channel; the same adapter shape
(`dvergr.adapters.core`) backs the others.

## Setup

```clojure
;; config.local.edn
{:telegram      {:token "123456:ABC-…"}   ; or env TELEGRAM_BOT_TOKEN
 :default-agent :var                       ; who answers a DM (default :var)
 :allowed-users [{:id 12345 :username "alice"}]}   ; access control (see below)
```

Create a bot with [@BotFather](https://t.me/BotFather), put its token here (or
in `TELEGRAM_BOT_TOKEN`), and start the daemon. On connect the bot registers its
slash-command menu (`setMyCommands`, derived from `dvergr.ops`).

> **One poller per token.** Telegram allows only a *single* `getUpdates` poller
> per bot token. Running two dvergr instances (or dvergr + another bot) on the
> same token produces `Conflict: terminated by other getUpdates request` and
> messages are split/lost between them. Use one instance, or a separate token
> per instance.

## Access control — the allowlist

Every inbound message is gated by `:allowed-users` **before any side effect**:
an unauthorized sender gets a polite refusal and nothing else runs — no paid
transcription, no file writes, no agent turn. Match by Telegram numeric `:id`
(stable) or `:username`. Omit `:allowed-users` to allow everyone (only sensible
for a private bot).

## What you can send

| Input | Behaviour |
|---|---|
| **Text** | posted into the chat's Room as your user-actor; the room's agent replies |
| **Voice note** | transcribed via [ASR](media.md#speech-to-text-asr) → posted as a `🎤 <transcript>` message, then handled like text; the transcript is echoed back so you see what was heard |
| **Document / photo** | persisted via the embedder's `:store-file-fn` (into the room drive at `/drive/telegram/…`) and announced with a `📎` note; agents read it at `/drive` |

Voice transcription is wired by the daemon (`telegram-caps :transcribe-fn` →
`dvergr.audio.stt/transcribe`). Document/photo storage requires an embedder that
supplies `:store-file-fn` + a drive — the standalone daemon does not persist
files yet (see [media.md](media.md#files-in--the-drive-mount)).

## Rooms & addressing

Each chat maps to a per-chat DM Room whose default participant is
`:default-agent`. A plain message addresses that agent, so it replies. You can
address others or run commands inline:

- `/agents` — list available agents
- `/<agent> do something` — route this message to a specific online agent
- **Ops commands** (`/stats`, `/fork`, `/system`, `/invite`, …) — run against
  *this* chat's room and reply directly (never posted or routed to an agent)
- The unified slash-command registry (`/plan`, `/build`, `/model`, `/skill`, …)
  — the same surface the TUI and web dispatch through

Executing **tool commands** from Telegram (e.g. `/clojure_eval <agent> …`) is
off by default — Telegram is a remote surface even with the allowlist. Opt in
per bot with `:telegram {:tool-commands? true}`.

## Internals (brief)

- **Polling** — one background long-poll loop (`getUpdates`); each update is
  dispatched **off the poll thread** so a slow message (a voice note blocking on
  download + transcription) can't stall delivery for other chats.
- **Egress** — an agent reply on a Telegram room's bus is mirrored back to the
  chat, labelled with the speaker's name (one bot, many actors), with a
  collapsed blockquote of the turn's tool activity.
- **Long messages** are chunked to Telegram's 4096-char limit at paragraph /
  line / word boundaries.

Slack, Zulip, GitHub, and mail plug in the same way — see
[configuration.md](configuration.md) for their config keys.
