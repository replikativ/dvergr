---
name: mcp-porter
description: Wrap an external MCP server as an :external actor in the registry
provides: [:mcp, :integration]
requires_tools: [clojure_eval]
vetted: false
source: openclaw
---

# MCP porter

Take an MCP server URL + auth + skill manifest, and register it as
an `:external` actor so other agents can dispatch to its tools.

## When to use (trigger phrases)

- "wire up the <name> MCP server"
- "add this MCP endpoint as an actor"
- "let agents call tools from <service>"

## Tactics

1. Probe the MCP endpoint for its tool list (`tools/list` over HTTP).
2. Map each MCP tool to a `:provides` tag (often the tool name
   becomes a kebab-case keyword).
3. `(dvergr.actors/spawn-agent! {:id <kw> :kind :external ...})`
   — set `:actor/config` to `{:transport :mcp :url <url> :auth ...}`.
4. Tell the user which skills the new actor now provides.

## Caveats

- MCP transport is wired in phase D; until then the actor row is
  cosmetic and `(dvergr.skills/dispatch <skill>)` will see it but can't
  actually invoke it.
- Don't store auth tokens in `:actor/config` plaintext — use a
  reference into the secrets table (TBD in phase E).
