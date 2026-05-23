# Test Suite Performance Analysis (2026-05-23)

Captured after the 6f.* cleanup (task #96 — discourse migration). Suite
takes ~5 min with integration tests (`--skip-meta :integration` brings
it to ~3-5 min depending on JVM state). Investigation methodology:

1. Per-ns timing instrumentation (`dev/time_tests.clj`)
2. `jstack` snapshots at the slow phases
3. Thread name + CPU-time analysis

## Per-ns breakdown (kaocha, 1 JVM)

| Namespace | load (ms) | run (ms) | tests | s/test | Notes |
|---|---:|---:|---:|---:|---|
| **dvergr.daemon-test** | 3,553 | **33,687** | 7 | **5.2** | daemon start/stop per test |
| **dvergr.analysis.queries-test** | **24,965** | 2,589 | 2 | — | first ns triggering big cold-load chain |
| dvergr.chat.context-test | 109 | 5,563 | 7 | 0.8 | datahike write per test |
| dvergr.discourse.llm-test | 53 | 4,218 | 6 | 0.7 | future-bridging + sleeps |
| dvergr.distributed-test | 4,513 | 29 | 9 | 0.0 | kabel/spindel.distributed cold-load |
| dvergr.analysis.coverage-test | 3,220 | 4,807 | 6 | 0.8 | analysis cold-load + 6 tests |
| dvergr.personas-test | 49 | 2,527 | 5 | 0.5 | mock-turn-fn + future-bridging |
| dvergr.sandbox-shell-test | 93 | 2,500 | 26 | 0.1 | shell exec per test |
| dvergr.discourse.enrichment-test | 143 | 2,039 | 7 | 0.3 | shared-executor pollution |
| dvergr.chat.compaction-test | 41 | 1,615 | 8 | 0.2 | |
| dvergr.discourse.drivers-test | 136 | 1,241 | 5 | 0.2 | sleep-based cadence test |
| dvergr.proposals-test | 99 | 870 | 9 | 0.1 | |
| dvergr.discourse-test | 113 | 704 | 23 | 0.03 | fast — scripted participants only |
| dvergr.mcp.server-test | 162 | 104 | 17 | 0.006 | fast |
| dvergr.scheduler.core-test | 35 | 22 | 7 | 0.003 | fast |
| dvergr.registry-test | 45 | 7 | 14 | 0.0005 | fast |

Self-programming-test, web.server-test, sessions-test, workflows-test,
tools/* run too late in the suite to time cleanly with the current
script (integration tests hang the harness).

## Where the time really goes

Three things dominate, in order:

### 1. Daemon-touching tests (~50% of run time)

`daemon-test` alone is **37 seconds** — 5.2s per test. Each test calls
`daemon/start!` then `daemon/stop!`. `start!` does heavy I/O work
synchronously:

- `create-shared-context` → Datahike file-backend connect (~1-2s on
  cold disk, faster on warm cache)
- `ensure-full-schema!` on the chat db
- yggdrasil git system registration (creates worktree dir, sets
  refs)
- `search/init!` opens the Lucene index on disk
- `bus/init!`, `rooms/init!`, `stats/init!`
- Calendar schema install + iCal sync init
- Per-agent: SCI sandbox fork, skills load, system-watcher spawn,
  driver pumps if room subscriptions

`stop!` does the symmetric work plus a 5s outer-timeout wait on
`drain-active` (see `spindel.engine.context/stop-context!`).

Tests that share this profile: `daemon-test` (7), `self-programming-test`
(several non-integration), `web/server-test` (mock daemon but still
heavy), `distributed-test` (spawns a kabel peer — kabel cold-load is
the worst single ns at 4.5s).

### 2. Cold-load tail (~25-30% of run time on first ns)

`dvergr.analysis.queries-test` shows **25 seconds of "load"** — but
the ns itself is tiny. What's actually being loaded is the entire
dvergr.daemon dep tree (datahike, kabel, lucene, anglican, jackson,
http-kit, …) the first time anything in the suite requires it.

The kaocha runner pre-loads all test nses before running, so this
cost is amortized once in the full-suite kaocha run. Our per-ns
script re-requires each ns so the first one that pulls daemon pays
the full bill. With kaocha you actually see this as the long startup
gap before any `(...)` group appears.

JVMCI compiler thread on GraalVM accumulates **~50s of CPU in the
first 3.5 min** (24% wall in pure JIT, mostly on background cores).
GraalVM picks compile-time-for-runtime-speed harder than HotSpot;
it's the right default for daemon production runs but it's a lot of
work to do for a 5-minute test suite.

### 3. Spindel ExecutionContext drain-thread leak

`jstack` after ~90s showed **103 threads alive**; after ~210s,
**119 threads**. The growth is almost entirely `Thread-N` entries
parking on `LinkedBlockingQueue.poll`:

```
at LinkedBlockingQueue.poll(...)
at org.replikativ.spindel.engine.context/create_execution_context$fn__76169$fn__76170
```

Every `(create-execution-context)` spawns a daemon Thread that runs
this loop until the context's `running` atom flips false. The
context is supposed to be cleaned up by a registered GC `Cleaner` —
but the JVM only runs the Cleaner when GC actually fires, which in a
JVM with plenty of heap may never happen during a 5-minute test
suite.

Mitigation that already exists in spindel: `stop-context!` explicitly
stops the drain. Discourse `Room`s don't currently call it on
teardown because rooms don't have a teardown — every room created in
a test leaks its context's thread.

**Impact**: thread accumulation alone doesn't slow tests much
(~each thread parks at ~0 CPU). But:
- It does add scheduling overhead and L1/L2 contention.
- It also pollutes test isolation — leaked spins from prior tests
  still listen on mailboxes and respond to messages, so subtle
  cross-test interactions surface as flake.

## Concrete actions

Ranked by win-per-effort.

### Cheap, high win (~25s shaved from `daemon-test` alone)

**1. Make `daemon-test` use `:once` instead of `:each` fixture, sharing
one daemon across all tests.**

Each test today creates an independent throwaway daemon to assert
isolation properties. Most of those assertions don't care about a
fresh start — they exercise `list-agents`, `dispatch!`, `create-agent!`,
`stop-agent!` etc. The two that DO require a fresh start
(`test-daemon-start-stop-no-telegram`, `test-daemon-no-agents-config`)
can opt out with a separate fixture or a per-test daemon.

Expected: ~37s → ~10s.

**2. Skip integration tests by default in kaocha** via `tests.edn`:

```edn
#kaocha/v1
{:tests [{:id :unit
          :test-paths ["test"]
          :skip-meta [:integration]}]}
```

This makes `clojure -M:test` the right default for CI/dev; opt in
with `clojure -M:test --focus-meta :integration` when a Fireworks API
key is available.

Expected: removes the ~2-5 min hang when API keys are missing.

**3. Share the daemon's execution context across daemon-using test
namespaces** by binding `daemon/start!` to a session-scoped fixture
in a shared test helper.

This requires turning daemon's global `current-daemon` atom + several
`defonce`s (sessions, registry, room bus, search index) into something
testable. Less surgical than #1, defer.

### Spindel-side, medium effort (~no test-time win but fixes leak)

**4. Spindel: drop the dedicated drain-thread-per-context model OR
add a hard `close-context!` that joins the thread.**

Two options:

  - **Shared drain executor**: one drain thread per JVM,
    multiplexing across all contexts. Each context registers/
    unregisters with the executor. Eliminates the leak completely.
    Bigger change.
  - **Add `close-context!` callable from teardown** (and call it
    from discourse `(room/close! r)`). Smaller change; fixes the
    leak when callers cooperate, doesn't help when they don't.

The first option is the right long-term fix — drain-thread-per-
context is over-engineered for what amounts to a 1Hz polling loop.

**5. Spindel: shorten the 5s `stop-context!` deadline OR document
its cost.**

`stop-context!` blocks for up to 5s waiting for `drain-active` to
go to zero. In tests with plenty of in-flight no-op drains this
adds up. The 5s is documented as a "safety valve for a deadlocked
spin body" but typical test teardowns hit it for non-deadlock
reasons.

### Long-tail GraalVM tuning (~unclear win)

**6. Disable JVMCI for tests:** `-XX:-UseJVMCICompiler -XX:+UseGraalJIT`
isn't quite right — what we actually want is HotSpot's tiered
compilation, which is faster to warm up. Pass
`-J-XX:-EnableJVMCI` (might require alias-level config).

Less convincing — tests probably aren't waiting on JIT work, the
compiler thread runs concurrently. Worth a one-shot benchmark.

**7. Lucene cold-load via lazy require:** ~3-5s of the slow tail is
Lucene class init via `dvergr.search`. The search ns is required
transitively whenever the daemon loads. Could lazy-load with
`requiring-resolve` so non-daemon tests don't pay.

## Spindel issues to file

1. **Drain thread leaks across context GC.** Cleaner-based stop is
   too lazy; ~40 threads accumulate over a 5-min test run. Fix:
   single shared drain executor, OR explicit close-context! contract
   wired into discourse Room teardown.

2. **`stop-context!` blocks up to 5s on drain-active.** This is
   probably fine in production but compounds in test suites. The
   documented "safety valve for deadlocked spins" feels like it
   belongs as a `:timeout-ms` option, not a hardcoded 5000.

## Next steps

Immediate (this session): tasks #1 + #2 above — ~25s shaved off the
suite, removes the integration-hang failure mode.

Spindel-side (next session): file the drain-thread issue with this
analysis attached. Then either fix discourse to call `stop-context!`
on room teardown (small win, needs an API for it), or do the shared-
drain-executor refactor (proper fix).
