# CLAUDE.md — Dvergr development notes

Dvergr is a Clojure agent harness built on the spindel FRP runtime: rooms, agents and their Runs are
reactive processes, each attempt runs in a forked world, and benchmarks are experiments over
certified Attempts. README.md is the product overview; `doc/` holds the design
(`doc/architecture.md`, `doc/programming-model.md`, `doc/fork-and-world-model.md`,
`doc/evaluation-model.md`, `doc/benchmarks.md`, `doc/room-workflows.md`).

## Never use :reload-all

A transitive dependency of `io.aviso.ansi` hangs forever on `:reload-all`. Use `:reload`:

```clojure
(require '[dvergr.core :as d] :reload)               ; good
(require '[dvergr.agent.program :as program] :reload)
;; (require '[dvergr.core :as d] :reload-all)        ; hangs; kill the REPL
```

## REPL

```bash
clj -M:dev:repl          # nREPL with dev + test + benchmarks/src on the classpath
clojure -M:cli           # daemon + nREPL (:7888) + TUI
```

`clj-nrepl-eval --discover-ports` finds a running nREPL. The README's "REPL quickstart" shows a
room, a coder agent and a message (`d/room`, `d/join`, `d/coder`, `d/post!`, `d/log`).
`dvergr.core/run` loads a stored Run (`(run room run-id)`); there is no one-shot "run this task"
call.

## Layout

```
dvergr.core              public facade (rooms, discourse, personas, Runs)
dvergr.chat.agent        agent turn loop; dvergr.chat.context per-chat state
dvergr.model.*           providers (api/{anthropic,openai,codex_subscription,claude_code}), gateway, registry
dvergr.tools             tool registry; the effect boundary is dvergr.effects
dvergr.agent.program     Runs: hire, supervisor, workers (blocking work off the engine)
dvergr.agent.{evaluation,experiment,world,attempt,spend}   evaluation path
dvergr.agent.experiment.runner   run!/run-in/open-home!: experiments in a home's rooms, resume
dvergr.catalog.*         room workflows, case packs (casepack-cli), room-run, wiki benchmark, report
dvergr.mcp.*             MCP server (profiles; the `bench` profile has the catalog tools)
benchmarks/              tau2, BFCL, BIRD, SpreadsheetBench, AutomationBench, bank booking:
                         a separate artefact (org.replikativ/dvergr-benchmarks), on the
                         classpath with -A:benchmarks (included in :dev and :test);
                         benchmarks/reference/ runs BIRD and SpreadsheetBench as their
                         authors do (scripts, on no classpath)
examples/workflows/      room workflow bundles: bank-booking, competitors, contract-review (CUAD)
```

Tests: `clojure -M:test` (kaocha, both artefacts); one namespace with `--focus <ns>`. CI runs the
suite in 3 parallel containers, each on its share of the namespaces (`dvergr.test-split`, one
`test` check); `doc/testing.md` shows how to run a share locally and how to write waits that hold.
Format before committing: `clojure -M:ffix`.

## Models

`dvergr.model.registry` holds ids, prices (`:pricing`, per 1M tokens, incl. `:cache-read`) and
quirks; `resources/models.edn` adds Fireworks models, and `(registry/refresh-from-models-dev!
#{:fireworks})` overlays current prices.

- Subscriptions (no per-token bill; reports price them at list price via `:list-price-of`):
  `codex-subscription-luna`, `codex-subscription-sol` (Codex, GPT-5.6), `codex-subscription-luna-6`,
  `codex-subscription-sol-6.1`, `codex-subscription-astra-6` (Codex, GPT-6/6.1), `claude-code-haiku`,
  `claude-code-sonnet`, `claude-code-opus` (Claude Code CLI).
- Fireworks: `accounts/fireworks/models/minimax-m3`, `glm-5p3-flash`,
  `deepseek-v4p1-flash`, `glm-5p3`, `kimi-k3`. Key: `OPENAI_API_KEY` +
  `OPENAI_BASE_URL=https://api.fireworks.ai/inference/v1`, or `FIREWORKS_API_KEY`. An agent
  that pins no model and gets Fireworks as its provider runs `minimax-m2p7`
  (`providers/default-spec`); the registry's `:compaction-model` default and the `minimax`
  alias are `minimax-m3`.
- API: `claude-sonnet-4-6`, `claude-opus-4-7`, `claude-haiku-4-5`, `gpt-5.6-*`.

Claude Code CLI candidates call tools natively over MCP when they work in a room (room workflows;
`room/experiment!` starts a headless daemon for them) and through a text tool protocol otherwise
(protocol benchmarks such as BIRD and SpreadsheetBench). The Claude subscription's 7-day window is
shared with interactive use: the runner starts no new cell once a subscription window is 80 %
used or the experiment has taken ten points of it (`:allowance`, default `{:share 0.10 :pause-at
0.80}`; the result carries `:refused` and running again resumes). Separately, before each pass a
Claude Code experiment waits while a window is at least `:usage-pause-threshold` (0.97) used.

## Running benchmarks

- Each benchmark has `dvergr.benchmarks.<name>.experiment/run!`, or a room workflow
  (`clojure -M -m dvergr.catalog.room-run <bundle-dir> --models … --cases N --home DIR`). Both
  keep the experiment in a room of a dvergr **home** (`:dir` / `--home`): the system DB, the
  rooms' stores and `home.edn` (room-run adds its log, `dvergr.log`). `run!` is
  `runner/open-home!` (a headless daemon on that home, the process's for the call: no Telegram,
  web or MCP listener, no agents, schedules suspended, no storage GC) + `runner/run-in` +
  `runner/close-home!`: run it in its own JVM; in a process with a daemon pass `:daemon` (and
  `:experiment-id`) and it uses that daemon's home. A moved home is adopted, a copied one
  refused (`system.home/claim!`), and an old-layout dir (`artifacts/`) is refused.
- `room-run` calls in process the ops an MCP client calls (`ops/invoke` on the home's daemon,
  no MCP transport): `room_create` (once), `room_write` the bundle, `catalog_benchmark`, `job_status`
  until settled, `experiment_report`.
- Which room an experiment lives in depends on the path. `run!` keeps each ExperimentDef in a
  room of its own (`runner/experiment-slug`: readable id + the start of its content id), so a
  provider prompt or grader version bump is a new room. `room-run` keeps every version in the
  one room `<bundle name>`, as the experiment named `<bundle name>`; `catalog_benchmark` keeps it
  in its `room` (default: the new fixture room) under its `experiment` name (default: a fresh
  one). A changed bundle or model list is a new ExperimentDef in that same room. The
  `experiment` argument is a name in `catalog_benchmark`, but the ExperimentDef content id
  (what `catalog_benchmark` returns as `experiment`) in `experiment_report`.
- Running again on the same home (same experiment) resumes: cells with a verdict are kept,
  faulted cells re-run (and once more within the same run, `:fault-retries`). What a Run
  resolves only when it starts (the assembled system prompt, e.g. the sandbox pointer; the tools
  offered; the model an alias names) is not in the ExperimentDef: each Attempt records it as its
  inputs id (`program/run-inputs`), and a resume keeps a cell only while its candidate would
  get the same inputs now; the others re-run, their old Attempts stay in the store but count in
  no Scorecard (the report says how many re-ran). Attempts from before inputs ids (LLM and
  protocol candidates) count as stale and re-run once.
- The report is the room's, read through the `experiment_report` op (`runner/report dir`, the
  result's `:report`, `room-run` prints it or writes `--report FILE`): frontier with 95 %
  intervals, resources (tokens: input incl. cached, output; median/p90/summed/wall time;
  list-price cost), paired comparisons, failed checks. `runner/progress dir` and
  `runner/open-experiment` read a home back.
- Tune on the `:dev` split, report on `:eval`, and confirm a tuned change on questions not used
  for tuning. To compare candidates, read the report's Paired comparisons (exact McNemar on the
  cases where two candidates differ, Holm-adjusted; `baseline` picks the one to compare
  against); `doc/running-benchmarks.md` explains the table.
- One experiment JVM at a time on this machine, with an `-Xmx` cap.

## Spindel (the FRP runtime)

Namespaces: `org.replikativ.spindel.core :as sp` (facade), `org.replikativ.spindel.engine.core :as
ec` (`*execution-context*`). Docs: `../spindel/docs/` (`concepts.md`, `engine.md`, `forking.md`,
`effects.md`).

```clojure
(require '[org.replikativ.spindel.core :as sp]
         '[org.replikativ.spindel.engine.core :as ec])

(def ctx (sp/create-execution-context))
(binding [ec/*execution-context* ctx]
  (def total (sp/spin (+ (sp/await a) (sp/await b))))   ; composes; re-runs when a or b change
  @total)                                               ; deref ONLY at the REPL/test boundary
```

Rules:

1. **Compose with `await` inside `spin`; never `@deref` inside a spin.** Deref is for the
   REPL/test/host boundary.
2. **Never block inside a spin body.** Await continuations and deferred deliveries resume inline on
   the context's single drain thread, so blocking I/O there stalls every spin of the context. Run
   blocking work on embedder threads that talk back only through `sp/deliver!`, or wrap it in
   `org.replikativ.spindel.blocking/blocking` (a Spin of `(f)` run on a worker thread; await
   it), as dvergr's evaluation and Run code does. A Run's own native work goes through the
   supervised workers of `dvergr.agent.program` (`start-worker!`, private to that namespace).
   Engine threads are never interrupt targets; cancel through the engine (`cancel-spin!`, `race`,
   `timeout`).
3. **Bind `*execution-context*`** where spins are created; evaluate a room's work in the room's
   ctx (a daemon-ctx evaluation loses wakeups).
4. **Signals for the external world** (UI input, clocks, config); **sync primitives for internal
   coordination**: `sp/deferred`/`deliver!`, `sp/mailbox`/`post!`, `sp/semaphore`/`holding`,
   `sp/mult`/`tap`, `sp/pub`/`sub`; admission of concurrent work: `org.replikativ.spindel.work`.
5. **Fork for isolation**: `sp/fork-context` is O(1) copy-on-write; an attempt's world is a fork,
   certified then discarded or settled.
6. **Combinators**: `sp/parallel`, `sp/race`, `sp/timeout`, `sp/sleep`, `sp/debounce`, …

An agent is a long-lived reactive process (inbox mailbox, outbox, control deferred), not a
function call; multi-agent patterns (pipeline, fan-out, race, debate, manager–worker,
request–response) compose these primitives. `doc/programming-model.md` and
`doc/process-model.md` describe the real ones; sketches elsewhere are pseudocode.
