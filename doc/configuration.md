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
| **Runtime state** | `./.dvergr/` (Datahike — chat, actors, **agent prompts**; worktrees; index) | **authoritative once seeded** | the running system / UIs |

Rule of thumb: the **file** is a *seed*; once the system boots, the **Datahike
store under `.dvergr/`** is the source of truth. Editing an agent in the TUI/web
writes runtime state, not your `config.local.edn`.

## Where things resolve from

### Config file — `dvergr.substrate.config`
Priority: `$DVERGR_CONFIG` → `./config.local.edn` → `./config.example.edn`.
It's a single EDN map, gitignore it (it holds secrets). Every secret also has an
**env fallback**, so you can keep tokens out of the file entirely.

### State root — `dvergr.substrate.paths`
Priority: `(paths/set-home! …)` → `$DVERGR_HOME` → `./.dvergr`. Layout:

```
.dvergr/
  db/            Datahike file store (chat + KB + code + actors + agent prompts)  ← authoritative
  system-db/     registry of parties / systems / rooms / grants
  systems/       per-system stores (each room's KB/repo scope)
  worktrees/     git worktrees for :ctx room forks
  workspace/     the agent code workspace (sandbox load root, cloned from the sandbox stdlib)
  transcripts/   intake transcript cache
  dvergr.log     daemon/substrate log
.nrepl-port      written at repo root for client discovery
```

## `config.local.edn` schema (all keys optional)

```clojure
{;; LLM agents — SEED for the Datahike actor rows (see "Agents" below).
 :agents {:var {:provider :fireworks
                :model    "accounts/fireworks/models/minimax-m2p5"
                :tags     #{:secretary}
                :description "Primary interface — chat and task routing"
                :profile  "var"}}   ; optional; defaults to the agent id → resources/agents/<id>.md
 :default-agent :var

 ;; Channels (secrets fall back to env)
 :telegram      {:token "…"}                 ; or env TELEGRAM_BOT_TOKEN
 :allowed-users [{:id 12345 :username "…"}]   ; Telegram access control
 :notify-chat-ids [12345]                     ; route intake output to these chats
 :slack         {:token "xoxp-…"}             ; or env SLACK_USER_TOKEN
 :zulip         {:email "…" :api-key "…" :site "…"}
 :github        {:token "…"}                  ; or env GITHUB_DVERGR_TOKEN
 :mail          {:account-id {:email "…" :imap {…} :smtp {…} :data-path "…"}}

 ;; Sandbox credential injection — an agent uses an API key it never sees.
 ;; See doc/boundary-secret-injection.md for the full :secrets/:sandbox-env schema.
 :secrets       [{:name "BRAVE_API_KEY" :env "BRAVE_API_KEY"
                  :allowed-domains ["https://api.search.brave.com"]
                  :allowed-locations [:header :query] :header-names ["X-Subscription-Token"]}]
 :sandbox-env   {"ZULIP_SITE" "https://your-org.zulipchat.com"}

 ;; Sandbox stdlib source — every room workspace is cloned from here.
 :sandbox-repo  "https://github.com/replikativ/dvergr-sandbox"}
```

### Environment variables
`DVERGR_CONFIG` (config path) · `DVERGR_HOME` (state root) ·
`TELEGRAM_BOT_TOKEN` · `GITHUB_DVERGR_TOKEN` · `SLACK_USER_TOKEN`.

## Agents — how config resolves at runtime

Two independent pieces, both project-overridable:

1. **Config** (model / provider / tags / description): the **Datahike actor row
   is authoritative**. `config.local.edn :agents` is materialised into actor rows
   **once** at startup (`dvergr.actors/bootstrap-from-config!`, idempotent — it
   only writes agents that don't exist yet). Thereafter the runtime / UI edits the
   actor row; the file is not rewritten. To re-seed a changed file, add the new
   agent (bootstrap skips existing) or edit the actor row directly.

2. **Persona** (the system prompt): **managed state, not a file** — stored on the
   actor row (`:actor/system-prompt`) and resolved **DB-first** by
   `dvergr.agent.persona`, falling back to the built-in `resources/agents/<id>.md`.
   `update_agent_profile`, the agent-config UI, and `ops/update-agent!` all write the
   row; dvergr's own resources are never mutated. `(persona/source id)` reports
   `:db` / `:builtin` / `:none`. Because the prompt lives in Datahike it is
   value-semantic (forks with the actor row) and versioned with the rest of the DB —
   see `doc/state-model.md`.

So a host project customises agents by: declaring them under `:agents` in its
`config.local.edn` (seed), shipping defaults as `resources/agents/<id>.md` on its
classpath, and/or editing the prompt at runtime through the API/UI (persists to the
DB) — without forking dvergr.

## Quick start (host project)

```clojure
;; deps.edn → add dvergr as a dependency, then:
;; 1. ./config.local.edn  — your agents + tokens (gitignored)
;; 2. ./.dvergr/agents/*.md — optional persona overrides
;; 3. start the daemon from your project root; state lands in ./.dvergr/
```
