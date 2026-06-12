# Provider & model setup

dvergr talks to LLMs through pluggable **providers**, each resolved from a
**model registry**. To run anything you need (1) at least one provider's API key
and (2) a model in the registry pointing at that provider.

## Supported providers

| Provider key  | API type            | Default endpoint                          | Source |
|---------------|---------------------|-------------------------------------------|--------|
| `:anthropic`  | `:anthropic-messages` | `https://api.anthropic.com/v1`          | `model/api/anthropic.clj` |
| `:openai`     | `:openai-chat`      | `https://api.openai.com/v1`               | `model/api/openai.clj` |
| `:fireworks`  | `:openai-chat`      | `https://api.fireworks.ai/inference/v1`   | `model/api/openai.clj` (OpenAI-compatible) |
| `:claude-code`| `:claude-code-cli`  | local `claude -p` CLI (subscription)      | `model/api/claude_code.clj` |

Providers self-register at startup (`providers/init-defaults!`) **only when
their key is available** — so a missing key just means that provider is absent,
not an error. If *nothing* registers, you get a loud boot warning.

## API keys (env vars)

Keys are read from environment variables (or passed in a config map to the
`register-*!` fns):

```bash
export ANTHROPIC_API_KEY=sk-ant-…     # enables :anthropic
export OPENAI_API_KEY=sk-…            # enables :openai
export FIREWORKS_API_KEY=fw-…         # enables :fireworks
# :fireworks also accepts OPENAI_API_KEY as a fallback
# override the Fireworks/OpenAI base URL with OPENAI_BASE_URL
```

- **Fireworks** prefers `FIREWORKS_API_KEY`, then falls back to `OPENAI_API_KEY`;
  base URL falls back to `OPENAI_BASE_URL` before the Fireworks default. This is
  the default provider dvergr ships with.
- **Claude Code** has **no API key** — it auto-detects by running `claude --version`.
  If the CLI exits 0, `:claude-code` registers and bills against your Claude
  subscription (model pricing is `0`).

> Provider keys are *not* in `config.local.edn` — they come from the env. Other
> secrets (Telegram, GitHub, …) live in config; see `doc/configuration.md`.

## The model registry — `resources/models.edn`

Edit this file to add/change models without touching source. It's loaded at
startup and is also re-loadable via `(registry/load-models-resource!)`. Shape:

```clojure
{:models
 {"accounts/fireworks/models/minimax-m2p7"     ; map key = the model :id
  {:name "MiniMax M2.7"
   :provider :fireworks                         ; must match a provider key
   :api-type :openai-chat
   :capabilities #{:tools :streaming}           ; :tools :vision :thinking :streaming
                                                ; :system-prompt :cache-control :json-mode
   :context 196608                              ; context window (tokens)
   :max-output 8192
   :pricing {:input 0.30 :cache-read 0.03 :output 1.20}  ; $/MTok
   :quirks {}}}                                 ; e.g. :default-top-p, :tool-id-in-every-chunk?

 :aliases  {"minimax" "accounts/fireworks/models/minimax-m2p7"}  ; short names

 :defaults {:primary-model    "accounts/fireworks/models/minimax-m2p7"
            :compaction-model "accounts/fireworks/models/minimax-m2p7"
            :summary-model    "accounts/fireworks/models/gpt-oss-20b"}}
```

**To add a model:** add an entry under `:models` keyed by its provider-side id,
set `:provider` to a registered provider, list `:capabilities`, and (optionally)
an alias. EDN entries merge over the built-in `default-models` (current Claude
Opus/Sonnet/Haiku + Claude-Code models, defined in `model/registry.clj`).

`(registry/refresh-from-models-dev! #{:anthropic …})` is an opt-in, network call
that overlays live pricing/context from <https://models.dev>.

## How a model is selected

Per request (`model/chat.clj`), resolution is:

1. `:model` opt is **required** (an id or an alias). Aliases resolve first via
   `registry/resolve-alias`.
2. The id is looked up with `registry/get-model!` — **throws** if absent.
3. Provider = `(:provider opts)` if given, else `(:provider model-def)` from the
   registry entry, then `providers/get-provider!` (throws if that provider isn't
   registered, i.e. no key).
4. Capabilities are checked — e.g. requesting tools on a non-`:tools` model throws.
   `:thinking` and quirk defaults (like Kimi's `top_p`) are applied automatically.

Agents carry `:provider` / `:model` in their config (`config.local.edn :agents`,
materialised into Datahike actor rows). The registry's `:defaults`
(`:primary-model`, `:compaction-model`, `:summary-model`) are read via
`registry/get-default` for system-level model choices.

```clojure
:agents {:var {:provider :fireworks
               :model    "accounts/fireworks/models/minimax-m2p7"
               :tags     #{:secretary}}}
```
