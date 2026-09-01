# Provider & model setup

dvergr talks to LLMs through pluggable **providers**, each resolved from a
**model registry**. To run anything you need (1) an API credential or supported
subscription login and (2) a model in the registry pointing at it. Provider
credentials are injected by the trusted in-process gateway only after the
configured upstream origin has been validated. Credential-free local providers
use the same boundary with an explicitly unauthenticated, origin-confined
capability.

## Supported providers

| Provider key  | API type            | Default endpoint                          | Source |
|---------------|---------------------|-------------------------------------------|--------|
| `:anthropic`  | `:anthropic-messages` | `https://api.anthropic.com/v1`          | `model/api/anthropic.clj` |
| `:openai`     | `:openai-chat`      | `https://api.openai.com/v1`               | `model/api/openai.clj` |
| `:fireworks`  | `:openai-chat`      | `https://api.fireworks.ai/inference/v1`   | `model/api/openai.clj` (OpenAI-compatible) |
| `:claude-code`| `:claude-code-cli`  | local `claude -p` CLI (subscription)      | `model/api/claude_code.clj` |
| `:codex-subscription` | `:openai-responses` | native ChatGPT Codex Responses transport | `model/api/codex_subscription.clj` |
| `:codex-subscription-cli` | `:codex-cli` | isolated `codex exec` compatibility path | `model/api/codex_subscription.clj` |

Providers self-register at startup (`providers/init-defaults!`) **only when
their key is available** — so a missing key just means that provider is absent,
not an error. If *nothing* registers, you get a loud boot warning.

## Verify a provider from the REPL

Start dvergr with the environment and CLI login you intend to use already in
place, then inspect the registry before creating an agent:

```clojure
(require '[dvergr.model.chat :as chat]
         '[dvergr.model.providers :as providers]
         '[dvergr.model.registry :as registry])

(providers/ensure-initialized!)
(vec (providers/list-providers))
(providers/default-spec)                    ; selection for an unpinned agent
(mapv :id (registry/models-for-provider :codex-subscription))

;; Exercise only the model transport, without constructing a room:
(chat/quick-chat "Reply with DVERGR_OK" "codex")
```

Provider discovery is cached for the life of the process. Restart the REPL after
changing an environment variable or logging a CLI in. An application may call
`providers/clear-all!` followed by `providers/ensure-initialized!` during a
controlled reconfiguration, but should not do that while turns are active.

## Codex subscription quickstart

OpenAI documents two distinct local Codex sign-in modes: ChatGPT subscription
access and usage-based API-key access. Dvergr's `:codex-subscription` providers
require the former.

1. Install the Codex CLI and run `codex login`.
2. Choose **Sign in with ChatGPT** and finish the browser flow.
3. Confirm `codex login status` reports a ChatGPT login.
4. Start dvergr, call `providers/ensure-initialized!`, and inspect
   `providers/list-providers` as shown above.
5. Use the native model alias `"codex"`, or pin an agent explicitly:

```clojure
{:agents
 {:var {:provider :codex-subscription
        :model "codex-subscription"}}}
```

The explicit native model choices are `"codex-subscription-sol"`,
`"codex-subscription-terra"`, and `"codex-subscription-luna"`; their short
aliases are `"codex-sol"`, `"codex-terra"`, and `"codex-luna"`.

The native transport reads `$CODEX_HOME/auth.json`, falling back to
`~/.codex/auth.json`. If you have configured
`cli_auth_credentials_store = "keyring"` or `"auto"` and no file is present,
dvergr cannot read the keyring directly. You have two options:

- Select `:codex-subscription-cli` with model `"codex-subscription-cli"`. This
  starts a locked-down, ephemeral `codex exec` for each turn.
- Set `cli_auth_credentials_store = "file"` in `~/.codex/config.toml`, then run
  `codex login` again to create the file needed by the native transport.

Both providers leave agent state, context, tools, effects, and sandbox semantics
in dvergr. The CLI path is compatibility isolation, not delegation of the agent
runtime to Codex. Dvergr does not switch paths automatically: replaying a request
after streaming or a tool call has begun could duplicate effects.

Never copy `auth.json` into a project or container image. Dvergr injects its
tokens only after validating the exact `https://chatgpt.com` origin, refreshes
expiring tokens in the host process, and persists rotations back to the auth
file with owner-only permissions where the filesystem supports them.

The native route targets the private endpoint used by the open-source Codex
client, not the stable public OpenAI API. Treat it as experimental compatibility
machinery and keep the explicit CLI path available across upgrades.

Official setup references: [Codex CLI](https://learn.chatgpt.com/docs/codex/cli),
[authentication](https://learn.chatgpt.com/docs/auth), and the
[`cli_auth_credentials_store` configuration](https://learn.chatgpt.com/docs/config-file/config-reference#configtoml).

## API keys (env vars)

Keys are read from environment variables (or passed in a config map to the
`register-*!` fns):

```bash
export ANTHROPIC_API_KEY=sk-ant-…     # enables :anthropic
export OPENAI_API_KEY=sk-…            # enables :openai
export FIREWORKS_API_KEY=fw-…         # enables :fireworks
export OPENAI_BASE_URL=https://api.openai.com/v1               # optional
export FIREWORKS_BASE_URL=https://api.fireworks.ai/inference/v1 # optional
```

- **OpenAI and Fireworks are credential-scoped.** OpenAI reads only
  `OPENAI_API_KEY` / `OPENAI_BASE_URL`; Fireworks reads only
  `FIREWORKS_API_KEY` / `FIREWORKS_BASE_URL`. A key for one never registers or
  authorizes the other. Fireworks is the default provider represented in
  `resources/models.edn`.
- **Claude Code** has **no API key** — it auto-detects by running `claude --version`.
  If the CLI exits 0, `:claude-code` registers and bills against your Claude
  subscription (model pricing is `0`).
- **Codex subscription** has **no API key**. Run `codex login` and choose
  **Sign in with ChatGPT**. For the default file-backed login, dvergr refreshes
  OAuth credentials inside its trusted host boundary and sends dvergr-owned
  history and native tool definitions directly to the ChatGPT Codex Responses
  endpoint. The token is not placed in provider request maps or exposed to SCI.
  A keyring-only login registers `:codex-subscription-cli` as a compatibility
  path. Subscription model pricing is recorded as `0`, while normal plan limits
  still apply.

The subscription endpoint is an upstream Codex compatibility surface rather
than the public OpenAI API. Keep `:codex-subscription-cli` available when
upgrading Codex/dvergr so an upstream wire or authentication change has an
immediate fallback.

Pin an agent to the native subscription model with:

```clojure
:agents {:var {:provider :codex-subscription
               :model "codex-subscription"}}
```

The optional aliases `codex-sol`, `codex-terra`, and `codex-luna` select an
explicit subscription model. To force the compatibility path, use provider and
model `:codex-subscription-cli` / `"codex-subscription-cli"`.

### Provider recipes

| You have | Setup | Provider / bundled model |
|----------|-------|--------------------------|
| Anthropic API account | `ANTHROPIC_API_KEY` | `:anthropic` / `"claude-sonnet-4-6"` |
| Claude Code subscription | Logged-in `claude` on `PATH` | `:claude-code` / `"claude-code-sonnet"` |
| ChatGPT Codex subscription | `codex login` with ChatGPT | `:codex-subscription` / `"codex-subscription"` |
| Keyring-only Codex login | Logged-in `codex` on `PATH` | `:codex-subscription-cli` / `"codex-subscription-cli"` |
| Fireworks account | `FIREWORKS_API_KEY` | `:fireworks` / `"accounts/fireworks/models/minimax-m2p7"` |
| OpenAI API account | `OPENAI_API_KEY`, plus an OpenAI registry entry | `:openai` / the registered model id |

The built-in registry carries Anthropic, Claude Code, Codex subscription, five
native OpenAI Chat models, and the curated Fireworks registry in
`resources/models.edn`. `OPENAI_API_KEY` can therefore run one of the built-in
OpenAI models immediately. `(registry/refresh-from-models-dev! #{:openai})` is
an opt-in runtime overlay: it can add current models and replace its mapped
limits/rates in this process, but does not rewrite a resource or source file.
Declare a model in `resources/models.edn` when it is a dvergr-maintained
configuration rather than a live overlay.

For a custom or local OpenAI-compatible endpoint, register both its credentials
and at least one model. A credential-free local endpoint still receives an
origin-confined capability, so it cannot accidentally acquire another
provider's authorization header:

```clojure
(require '[dvergr.model.gateway :as gateway])

(providers/register-openai-compatible!
 :ollama
 {:base-url "http://localhost:11434/v1"
  :credentials (gateway/unauthenticated-credentials
                #{"http://localhost:11434"})})

(registry/register-model!
 {:id "qwen3:8b"
  :name "Qwen 3 8B (local)"
  :provider :ollama
  :api-type :openai-chat
  :capabilities #{:tools :streaming :system-prompt}
  :context 32768
  :max-output 8192
  :pricing {:input 0 :output 0}
  :quirks {}})

(chat/quick-chat "Reply with LOCAL_OK" "qwen3:8b")
```

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
Opus/Sonnet/Haiku plus Claude-Code and Codex-subscription models, defined in
`model/registry.clj`).

`(registry/refresh-from-models-dev! #{:anthropic …})` is an opt-in, network call
that overlays live pricing/context from <https://models.dev> into process state
only. It is not a build or release generator and does not persist a downloaded
snapshot. Built-in OpenAI rate fields are a separately documented dated
snapshot in `model/registry.clj`; `resources/models.edn` is a distinct curated
registry.

**Vision models** carry `:vision` in `:capabilities`. `dvergr.media.vision`
(image describe/extract) picks the registry's vision default, overridable with
`DVERGR_VISION_MODEL`. The bundled default is what's deployed on the reference
Fireworks account (probe-verified); a dedicated Qwen-VL is cheaper per token if
your account has one — register it and point `DVERGR_VISION_MODEL` at it. See
[media.md](media.md#vision--images-to-text-and-data).

Some Fireworks models leak their tool-call envelope into content (Kimi, GLM);
their `:quirks` (`:kimi-tool-id-format?`, `:glm-tool-format?`) turn on the
recovery/scrub in the OpenAI adapter, so you rarely touch them by hand.

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
