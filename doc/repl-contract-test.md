# REPL Meta-Harness Contract Test

Before asking an LLM to invent an agentic workflow, dvergr verifies that the
programming surface it will use can construct and observe one without a model.

The contract executes the real `clojure_eval` tool against a room-bound SCI
context and ChatContext. Sandboxed Clojure creates a persistent child room through
`dvergr.room/create!`; a second evaluation proves that the REPL definition and
room view persisted; the host then checks the shared room registry and Datahike
registry row.

Run the test directly:

```bash
clojure -M:test --focus dvergr.repl-meta-harness-test
```

Or invoke the same test fixture interactively:

```clojure
;; Start with: clojure -M:dev
(require '[dvergr.repl-meta-harness-test :as contract])

(def result (contract/run-contract!))
(:passed? result)
;; => true

(:checks result)
```

`run-contract!` uses a fresh state root under the JVM temporary directory,
disables file-driven agent autostart, stops its daemon, and restores the prior
dvergr state root. It refuses to run if a daemon is already active in the JVM.
No model provider is resolved or invoked.

To exercise an already running development daemon deliberately, use a unique
child slug. This retains the child for inspection through the REPL, TUI, or web
UI:

```clojure
(require '[dvergr.repl-meta-harness-test :as contract])
(require '[dvergr.orchestration.daemon :as daemon])

(contract/run-against-daemon!
 @daemon/current-daemon
 {:parent-slug "boardroom"
  :child-slug "my-meta-harness-probe"})
```
