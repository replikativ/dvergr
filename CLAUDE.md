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
dvergr.core              public facade (rooms, discourse, personas, proposals)
dvergr.chat.agent        agent turn loop; dvergr.chat.context per-chat state
dvergr.model.*           providers (api/{anthropic,openai,codex_subscription,claude_code}), gateway, registry
dvergr.tools             tool registry + effects boundary (dvergr.agent.effects)
dvergr.agent.program     Runs: hire, supervisor, workers (blocking work off the engine)
dvergr.agent.{evaluation,experiment,world,attempt,spend}   evaluation path
dvergr.agent.experiment.runner   run!/run-in/open-home!: experiments in a home's rooms, resume
dvergr.catalog.*         room workflows, case packs, wiki benchmark, report
dvergr.mcp.*             MCP server (profiles; the `bench` profile has the catalog tools)
benchmarks/              tau2, BFCL, BIRD, SpreadsheetBench, AutomationBench, CUAD, bank booking:
                         a separate artefact (org.replikativ/dvergr-benchmarks), on the
                         classpath with -A:benchmarks (included in :dev and :test)
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
- Fireworks: `accounts/fireworks/models/minimax-m3` (default), `glm-5p3-flash`,
  `deepseek-v4p1-flash`, `glm-5p3`, `kimi-k3`. Key: `OPENAI_API_KEY` +
  `OPENAI_BASE_URL=https://api.fireworks.ai/inference/v1`, or `FIREWORKS_API_KEY`.
- API: `claude-sonnet-4-6`, `claude-opus-4-7`, `claude-haiku-4-5`, `gpt-5.6-*`.

Claude Code CLI candidates call tools natively over MCP when they work in a room (room workflows;
`room/experiment!` starts a headless daemon for them) and through a text tool protocol otherwise
(protocol benchmarks such as BIRD and SpreadsheetBench). The Claude subscription's 7-day window is
shared with interactive use: the runner stops admitting cells at 80 % (`:usage-pause-threshold`).

## Running benchmarks

- Each benchmark has `dvergr.benchmarks.<name>.experiment/run!`, or a room workflow
  (`clojure -M -m dvergr.catalog.room-run <bundle-dir> --models … --cases N --home DIR`). Both
  keep the experiment in a room of a dvergr **home** (`:dir` / `--home`: system DB + the rooms'
  stores, nothing else). `run!` is `runner/open-home!` (a headless daemon on that home, the
  process's for the call) + `runner/run-in` + `runner/close-home!`: run it in its own JVM; in a
  process with a daemon pass `:daemon` (and `:experiment-id`) and it uses that daemon's home.
  `room-run` is a client of the MCP path: `room_write` the bundle, `catalog_benchmark`, `job_status`.
- The experiment's room is one per ExperimentDef (`runner/experiment-slug`): running again on the
  same home resumes in it: cells with a verdict are kept, faulted cells re-run (and once more
  within the same run, `:fault-retries`). A prompt or grader version bump is a new ExperimentDef,
  hence a new room, so no resume mixes versions. An old-layout dir (`artifacts/`) is refused.
- The report is the room's, read through the `experiment_report` op (`runner/report dir`, the
  result's `:report`, `room-run` prints it or writes `--report FILE`): frontier with 95 %
  intervals, failed checks, tokens (input incl. cached, output), median/p90/summed/wall time,
  list-price cost. `runner/progress dir` and `runner/open-experiment` read a home back.
- Tune on the `:dev` split, report on `:eval`, and confirm a tuned change on questions not used
  for tuning. Compare candidates paired (same cases).
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
   blocking work on embedder threads that talk back only through `sp/deliver!`: in dvergr,
   `program/start-worker!` (supervised); in spindel, `org.replikativ.spindel.blocking/blocking`.
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
