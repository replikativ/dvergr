# Configuration & state — using dvergr in your own project

dvergr is designed to run **embedded in a host project** (as a `deps.edn`
dependency), not only from its own repo. Everything it reads or writes is
**project-local** (resolved from the host's working directory), so two projects
that both depend on dvergr stay fully isolated.

## The three layers

| Layer | Lives in | Authority | Owned by |
|-------|----------|-----------|----------|
| **Built-in defaults** | dvergr's classpath: `resources/agents/*.md`, `resources/models.edn`, `resources/phases/*.edn`, `resources/skills/*.md` | fallback | dvergr (the library) |
| **Project config** | `./config.local.edn` (+ project skills) | seed + override | your project |
| **Runtime state** | `./.dvergr/`, the home ([homes.md](homes.md)): the system DB (actors, **agent prompts**, the room registry) and each room's stores (messages, KB, Geschichte workspace) | **authoritative once seeded** | the running system / UIs |

Rule of thumb: the **file** is a *seed*; once the system boots, the **Datahike
store under `.dvergr/`** is the source of truth. Editing an agent in the TUI/web
writes runtime state, not your `config.local.edn`.

## Where things resolve from

### Config file — `dvergr.substrate.config`
Priority: `$DVERGR_CONFIG` → `./config.local.edn`. With neither, the config is
empty: no Telegram (unless `TELEGRAM_BOT_TOKEN` is set), no web server.
`config.example.edn` is documentation to copy from; dvergr never loads it.
It's a single EDN map, gitignore it (it holds secrets). Every secret also has an
**env fallback**, so you can keep tokens out of the file entirely. A configured
Telegram or GitHub token, or a `:secrets` `:config-path` value, that is blank or a
placeholder (contains `YOUR_`, as in the example) counts as unset, so the env var
is used.

### State root — `dvergr.substrate.paths`
Priority: `(paths/set-home! …)` → `$DVERGR_HOME` → `./.dvergr`. The state root is a dvergr
**home**: the system DB, every room's stores under `systems/`, `home.edn`, the log, the MCP
token. [homes.md](homes.md) has the layout as built, moved vs copied homes, experiment homes
and storage GC. The `.nrepl-port` of a REPL is written at the project root, not in the home.

## `config.local.edn` schema (all keys optional)

```clojure
{;; LLM agents — SEED for the Datahike actor rows (see "Agents" below).
 :agents {:var {:provider :fireworks
                :model    "accounts/fireworks/models/minimax-m3"
                :tags     #{:secretary}
                :description "Primary interface — chat and task routing"
                :profile  "var"     ; optional; defaults to the agent id → resources/agents/<id>.md
                :sandbox  #{:foerster/inference}}}  ; optional sandbox extensions (dvergr.sandbox.extensions)
 :default-agent :var

 ;; Channels (secrets fall back to env). See doc/channels.md.
 :telegram      {:token "…"                   ; or env TELEGRAM_BOT_TOKEN
                 :tool-commands? false        ; allow /clojure_eval etc. from Telegram (default off)
                 :owners [12345]}              ; numeric ids of senders who act as the local principal (the operator)
 :allowed-users [{:id 12345 :username "…"}]   ; Telegram access control: listed senders act as telegram/<id>;
                                              ; empty lets messages in but identifies nobody (commands, posts refused)
 :strict-allowlist? true                      ; empty :allowed-users denies everyone (default false)
 :notify-chat-ids [12345]                     ; route intake output to these chats
 :zulip         {:email "…" :api-key "…" :site "…"}
 :github        {:token "…"}                  ; or env GITHUB_DVERGR_TOKEN
 :mail          {:account-id {:email "…" :imap {…} :smtp {…} :data-path "…"}}

 ;; Sandbox credential injection — an agent uses an API key it never sees.
 ;; See doc/effects.md ("Secrets at the boundary") for the full :secrets/:sandbox-env schema.
 :secrets       [{:name "BRAVE_API_KEY" :env "BRAVE_API_KEY"
                  :allowed-domains ["https://api.search.brave.com"]
                  :allowed-locations [:header :query] :header-names ["X-Subscription-Token"]}]
 :sandbox-env   {"ZULIP_SITE" "https://your-org.zulipchat.com"}

 ;; Sandbox stdlib source — every room workspace is cloned from here.
 :sandbox-repo  "https://github.com/replikativ/dvergr-sandbox"

 ;; Daemon services.
 :http          {:port 17880 :ip "127.0.0.1"}   ; web dashboard + JSON API (needs the web deps); starts only when set (or `--web`);
                                                ; behind a proxy (or TLS terminator) add :allowed-hosts ["dvergr.lan"]
                                                ; and :allowed-origins ["https://dvergr.lan"]
 :mcp           {:profile "offload"               ; MCP on the home's Unix domain socket (bin/dvergr-mcp)
                 :tcp true :port 17888 :bind "127.0.0.1" ; also TCP (default off)
                 :http {:port 17889}}           ; and Streamable HTTP; doc/mcp.md
 ;; Who may call: by default the local token ($DVERGR_HOME/local-token) is the owner on MCP
 ;; (TCP, HTTP) and the web; nothing is trusted for where it comes from. doc/mcp.md, principals.
 :auth          {:authenticate my.host/authenticate  ; who calls (fn [request] -> principal | nil)
                 :authorize    my.host/authorize     ; may they (fn [principal request] -> decision)
                 :on-created   my.host/grant-creator} ; (fn [principal resource]) after a create
                                                ; default: local access only, everything allowed;
                                                ; a hosted daemon sets both (doc/mcp.md, principals)
 :gc            {:enabled? true                 ; storage GC; default OFF (doc/homes.md: datahike#1117)
                 :interval-ms 21600000          ; every 6 h (default)
                 :retention-days 30}            ; default nil: keep all history, reclaim only fork garbage
 :blob-store    {:backend :s3 :bucket "…" :region "…"}  ; /drive blobs; default a filestore under .dvergr/blobs
 :shell         {:jail {:commands ["python3"]}}}  ; real programs in the room shell; doc/tools-and-sandbox.md
```

### Environment variables

**Core** — `DVERGR_CONFIG` (config path) · `DVERGR_HOME` (state root) ·
`TELEGRAM_BOT_TOKEN` · `GITHUB_DVERGR_TOKEN`.

**Models** — `ANTHROPIC_API_KEY` · `OPENAI_API_KEY` / `OPENAI_BASE_URL` ·
`FIREWORKS_API_KEY` / `FIREWORKS_BASE_URL` · `CODEX_HOME` (providers) ·
`GROQ_API_KEY` (ASR) · `DVERGR_VISION_MODEL` (override the vision model). See
[provider-setup.md](provider-setup.md).

**Media / voice** (see [media.md](media.md)) — `DVERGR_RECORD_CMD` (mic-capture
command, `%s` = output WAV) · `DVERGR_AUDIO_DEVICE` (pin the Java capture
device) · `DVERGR_ASR_MODEL_DIR` / `DVERGR_WHISPER_MODEL` / `DVERGR_WHISPER_SCRIPT`
(local/native STT backends).

## Agents — how config resolves at runtime

Two independent pieces, both project-overridable:

1. **Config** (model / provider / tags / description): the **Datahike actor row
   is authoritative**. `config.local.edn :agents` is materialised into actor rows
   at daemon start (`dvergr.orchestration.daemon/start-from-config!` creates each
   configured agent; `dvergr.actors/ensure-agent!` is idempotent — it only writes
   agents that don't exist yet). Thereafter the runtime / UI edits the
   actor row; the file is not rewritten. To re-seed a changed file, add the new
   agent (bootstrap skips existing) or edit the actor row directly.

2. **Persona** (the system prompt): **managed state, not a file** — stored on the
   actor row (`:actor/system-prompt`) and resolved **DB-first** by
   `dvergr.agent.persona`, falling back to the built-in `resources/agents/<id>.md`.
   `update_agent_profile`, the agent-config UI, and `dvergr.agent.ops/update-agent!` all write the
   row; dvergr's own resources are never mutated. `(persona/source id)` reports
   `:db` / `:builtin` / `:none`. The prompt lives in the actor row of the
   system DB, which is shared by every room and not forked — see
   [worlds-and-forks.md](worlds-and-forks.md#shared-durable-the-system-db).

So a host project customises agents by: declaring them under `:agents` in its
`config.local.edn` (seed), shipping defaults as `resources/agents/<id>.md` on its
classpath, and/or editing the prompt at runtime through the API/UI (persists to the
DB) — without forking dvergr.

## Skills and agent definitions

Skills (instructions an agent pulls in for a kind of task) and agent identities (a persona
with its model, tools and rooms) are both **definition files**: markdown with a frontmatter
block, loaded by `dvergr.discourse.definitions`. `kind: agent` makes the body a system prompt,
`kind: skill` a skill template.

```markdown
---
kind: agent
name: researcher
description: Finds and cites sources
provider: fireworks
model: accounts/fireworks/models/minimax-m3
tools: [clojure_eval, knowledge_search]
provides: [:research]
rooms: ["*"]
autostart: true
vetted: true
---
You are a careful researcher. …
```

Skill frontmatter adds `requires_tools` and `requires_env` (what an agent must have for the
skill to apply). List values are `[a, b]` or indented `  - item` lines; `provides` becomes
keywords. An agent's `provides` are its capability tags (routing) and skills; `rooms: ["*"]`
joins every room.

**Scope chain.** Each kind (`agents/`, `skills/`) is read from four places; a later one
overrides an earlier one with the same file name (the path relative to the kind's directory,
without `.md`; the frontmatter `name` does not decide it):

1. built-in: `resources/agents/`, `resources/skills/` on the classpath (dvergr's own, or your
   project's resources);
2. user: `~/.dvergr/<kind>/`;
3. project: `./.dvergr/<kind>/` (relative to the process's working directory, not
   `$DVERGR_HOME`);
4. room: `<kind>/` in a room's workspace, which an agent can write from the sandbox.

**Vetting.** `vetted: true` gates skills and file-driven agent autostart, not every use of a
definition: an unvetted agent definition's body is still the fallback system prompt of a
configured agent of that id with no prompt of its own (`dvergr.agent.persona/resolve-prompt`).
An unvetted skill can be listed
and read but is not offered in agent prompts (`dvergr.orchestration.skills/eligible?`; also
`disabled: true`, a missing `requires_tools` tool or a missing `requires_env` variable keep it
out); the externally lifted skills under `resources/skills/lifted/` are unvetted. Agents
author skills into their own room from the sandbox with `dvergr.skills/author!` and `lift!`
(written `vetted: false`); `dvergr.skills/promote!` marks a room skill vetted and stamps
`vetted_by`/`vetted_at`.

**Autostart.** At start the daemon creates every agent whose definition (built-in, user or
project scope) has `autostart: true` and `vetted: true`, unless config `:agents` declares it or
the system DB already has its row; from then on the row is authoritative, as for configured
agents. A headless daemon (doc/homes.md) autostarts none. So config `:agents` is optional: a
vetted agent file is enough.

## Quick start (host project)

```clojure
;; deps.edn → add dvergr as a dependency, then:
;; 1. ./config.local.edn  — your agents + tokens (gitignored)
;; 2. ./.dvergr/agents/*.md — optional agent definitions (frontmatter + prompt;
;;    the daemon starts those with `autostart: true` and `vetted: true`;
;;    scope chain of dvergr.discourse.definitions: builtin → ~/.dvergr → ./.dvergr → room)
;; 3. start the daemon from your project root; state lands in ./.dvergr/
```
