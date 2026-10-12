# Testing

The suite is one kaocha suite, `:unit` (tests.edn), over `test/` (dvergr) and
`benchmarks/test/` (the benchmark providers). `^:integration` tests (real
provider calls) and `^:security-open` tests (sandbox escapes still open) are
skipped by default.

The MCP relay (`bin/dvergr-mcp`) is a babashka script, and its tests in
`dvergr.mcp.smoke-test` skip (with a note) when `bb` is not on `PATH`; CI
installs babashka 1.12.200 so they run there. Install `bb` to run them locally.

## Running tests locally

```bash
clojure -M:test                                    # everything
clojure -M:test --focus dvergr.jobs-test           # one namespace
clojure -M:test --focus dvergr.jobs-test/cancelling-a-job-settles-its-run-cancelled
clojure -M:test --focus-meta :integration          # the provider-calling tests
clojure -M:test --seed 1713321041                  # repeat a run's test order
```

Every run prints how long loading (compiling) the test namespaces took and how
long the tests ran (`dvergr.test-phases`), and the profiling plugin lists the
40 slowest namespaces and tests. Note that `--focus` filters after loading:
kaocha still compiles every test namespace first.

To run only some namespaces, the way one CI container does, put them in a file
and point the split hook at it. Only those namespaces are loaded:

```bash
.circleci/test-namespaces.sh > /tmp/all.txt
printf 'dvergr.jobs-test\ndvergr.effects-test\n' > /tmp/mine.txt
DVERGR_TEST_ALL_NAMESPACES=/tmp/all.txt DVERGR_TEST_NAMESPACES=/tmp/mine.txt clojure -M:test
```

## How CI splits the suite

The CircleCI `test` job runs with `parallelism: 3`. Each container:

1. lists every test namespace (`.circleci/test-namespaces.sh`, from the file
   names under `test/` and `benchmarks/test/`);
2. takes its share with `circleci tests split --split-by=timings
   --timings-type=classname`, which balances the shares by the namespace
   timings of earlier runs (the JUnit results each container stores);
3. runs kaocha on that share: `dvergr.test-split`, a kaocha `pre-load` hook,
   narrows the suite's `:ns-patterns` to exactly those namespaces, so a
   container compiles only what it runs.

No test is lost or run twice: `tests split` puts each listed namespace in
exactly one container, and the hook refuses to run unless the list equals the
set of namespaces kaocha itself finds in the suite. A namespace the file-name
derivation misses, or a stale name, fails every container before any test
runs. The containers' summary lines add up to the full run's test count.

The three containers report as one check, `ci/circleci: test`.

## Writing tests that stay green

- Wait on the condition, not on a fixed time. A wait for something that must
  happen (a turn starting, a reply, a Run settling) gets a generous bound
  (llm-test's `wait-ms` is 30 s), since a passing test never waits it out
  and a cold CI JVM can be slow. A check that something does *not* happen
  watches a short window that starts after the event that would have caused
  it has settled.
- A stochastic assertion needs a stated error bound: fix the seed, or size the
  sample so the tolerance is many standard deviations (see
  `dvergr.sandbox.steer-test`).
- Process-wide registries (tools, MCP tools, channels) are shared by every
  test in the JVM. Undo only what the test changed (`dvergr.test-registries`):
  restoring a whole snapshot drops what namespaces loaded during the test
  registered, and a later test then fails depending on order.
