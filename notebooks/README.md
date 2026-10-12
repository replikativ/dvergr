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
| `index.clj`             | the book's landing page: what dvergr is, the reading order | — |
| `getting_started.clj`   | rooms, participants, `post!`, tagged routing, capability subscriptions, the fork-and-merge proposal — from zero | — |
| `programming_model.clj` | the compositional kernel: tag routing, capability subscriptions beyond the inbox, budget escalation as a posted tag, per-consumer buffer policy on a token stream | `examples/scenario_auditor.clj`, `examples/scenario_manager_escalation.clj`, `examples/scenario_streaming_partial.clj` |
| `humans_and_agents.clj` | humans as participants, background tasks, propose → accept/reject on substrate forks | `examples/humans_and_agents.clj` |
| `agents_and_tools.clj`  | a real LLM agent with the `clojure_eval` sandbox as a tool (`llm-agent` + `ask`); shown as code with a captured reply, not run at render time | — |

`render.clj` builds the book (in the order of its `notebooks` vector);
`support.clj` holds the shared `settle` helper.

## Build

```bash
clj -M:clay -m notebooks.render
```

Renders the book headlessly to `docs/` (needs the `quarto` CLI on `PATH`). Give
it a generous timeout — the demos `Thread/sleep` (`support/settle`) to let async
routing settle. No notebook calls a model at render time, so the book builds in
CI without a provider key: `agents_and_tools` shows its code and a captured
example reply.

## Output

The rendered site lands under `docs/` (a **gitignored** build artifact — CI
renders and publishes it to GitHub Pages). Open it locally with:

```bash
xdg-open docs/index.html
```
