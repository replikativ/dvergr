# Notebooks

Literate, live-running [Clay](https://scicloj.github.io/clay/) notebooks for
dvergr — rendered as a Quarto **book** and published at
[replikativ.github.io/dvergr](https://replikativ.github.io/dvergr/).

Each notebook:

- interleaves `;;`-markdown prose with code that **runs live** (the outputs you
  see are produced by evaluating the forms),
- builds and drives a real room **inline** — it never calls an example's `-main`
  (those `System/exit`),
- renders results as data (`kind/table`, `kind/md`) rather than `println`.

The mechanism notebooks **import** the runnable scenarios from
[`../examples/`](../examples/) and narrate them, so there's no logic duplication.

| Notebook | Shows | Imports |
|----------|-------|---------|
| `getting_started.clj`   | rooms, participants, `post!`, tagged routing — from zero | — |
| `humans_and_agents.clj` | humans as participants, background tasks, propose → accept/reject | `examples/humans_and_agents.clj` |
| `auditor.clj`           | capability-tag subscriptions beyond the inbox | `examples/scenario_auditor.clj` |
| `escalation.clj`        | budget escalation via tagged messages + bus-level policy | `examples/scenario_manager_escalation.clj` |
| `streaming.clj`         | per-consumer buffer/SLA policy on a token stream | `examples/scenario_streaming_partial.clj` |
| `llm_agent.clj`         | drive a real model with tools (`llm-agent` + `ask`) — key-gated | — |

## Build

```bash
clj -M:clay -m notebooks.render
```

Renders the book headlessly to `docs/` (needs the `quarto` CLI on `PATH`). Give
it a generous timeout — the demos `Thread/sleep` to let async routing settle, and
`llm_agent` makes a live model call **only** if a provider key
(`FIREWORKS_API_KEY` / `ANTHROPIC_API_KEY`, or `claude` on `PATH`) is present;
otherwise it renders an explanatory note so the book builds in CI without a key.

## Output

The rendered site lands under `docs/` (a **gitignored** build artifact — CI
renders and publishes it to GitHub Pages). Open it locally with:

```bash
xdg-open docs/index.html
```
