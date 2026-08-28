# Harness Benchmarks

Dvergr's benchmark floor is deterministic and provider-free. Before asking an
LLM to invent an agentic workflow, we verify that the programming surface it will
use can construct and observe one on its own.

## REPL meta-harness benchmark

The first benchmark runs the real `clojure_eval` tool against a room-bound SCI
context and ChatContext. Sandboxed Clojure creates a persistent child room through
`dvergr.room/create!`; a second evaluation proves that the REPL definition and
room view persisted; the host then checks the shared room registry and Datahike
registry row.

It does not resolve or call an LLM. The isolated runner also disables file-driven
agent autostart, so provider discovery cannot affect the result.

Run it as a test:

```bash
clojure -M:test --focus dvergr.benchmarks.repl-harness-test
```

Or use it interactively, which is the intended feel:

```clojure
;; Start with: clojure -M:dev
(require '[dvergr.benchmarks.repl-harness-test :as bench])

(def result (bench/run-benchmark!))
(:passed? result)
;; => true

(:checks result)
(:timings-ms result)
```

`run-benchmark!` uses a fresh state root under the JVM temporary directory,
stops its daemon, and restores the prior dvergr state root. It refuses to run if
a daemon is already active in the JVM, rather than disrupting a live harness.

To exercise an already running development daemon deliberately, call the lower
level function with a unique child slug. This retains the child so it can be
inspected from the REPL, TUI, or web UI:

```clojure
(require '[dvergr.benchmarks.repl-harness :as bench])
(require '[dvergr.orchestration.daemon :as daemon])

(bench/run! @daemon/current-daemon
            {:parent-slug "boardroom"
             :child-slug "my-meta-harness-probe"})
```

The phase timings are diagnostic rather than a fixed performance threshold.
Correctness is the gate; repeated timing distributions and regression budgets can
be added once this scenario is stable in CI.
