# Getting Started

This guide walks you through your first dvergr session: a room with one
LLM agent, then a multi-room scenario, then a substrate-isolated
proposal. No prior dvergr experience assumed.

## Dependencies

Add dvergr to your `deps.edn`:

```clojure
{:deps {org.clojure/clojure  {:mvn/version "1.12.0"}
        org.replikativ/dvergr {:git/url "https://github.com/replikativ/dvergr"
                                :git/sha "<sha>"}}}
```

dvergr pulls in spindel, datahike, yggdrasil, SCI, and a small set of
provider libraries automatically.

## Provider setup

dvergr supports three provider classes out of the box:

```clojure
(require '[dvergr.model.providers :as providers])

;; Reads ANTHROPIC_API_KEY, OPENAI_API_KEY, FIREWORKS_API_KEY from env
;; and auto-detects the local `claude` CLI if present.
(providers/ensure-initialized!)
```

If you have a Claude Code subscription, the `claude-code` provider is
the easiest way to get started — no API key needed. It uses `claude -p`
under the hood.

## Path A — try the CLI

The fastest way to feel dvergr is the TUI chat client:

```bash
clojure -M:cli
```

You'll see a sidebar with one room (`scratch`) and a chat pane. Type
a message, press **Enter**. The footer shows the budget used, the
last turn's elapsed time + token counts, and the current generation
status.

Try:

- **Ctrl-N** — create a fresh room (`room-2`)
- **Tab** — cycle the active room
- **q** or **Ctrl-C** — quit (the session is saved to
  `~/.dvergr-cli/sessions/<id>.edn`)
- **resume** — `clojure -M:cli :resume <id>` — re-open the prior
  conversation

See [cli.md](cli.md) for the full reference.

## Path B — the REPL

For a hands-on feel of the programming model, the REPL is better.
Start a Clojure REPL with dvergr on the classpath and follow along:

### Step 1 — make a room

A `Room` is a discourse substrate: participants exchange tagged
messages on a small pub/sub bus. Every Room has its own spindel
execution context, so reactivity, atoms, and signals stay isolated.

```clojure
(require '[dvergr.core :as d])
(require '[org.replikativ.spindel.engine.core :as ec])

(def room (d/room :scratch))
```

### Step 2 — join an agent

A participant is just a value with `:id` and `:on-message`. The
`d/coder` persona is a pre-built LLM agent with a coder-shaped system
prompt and a sandboxed SCI context for `clojure_eval`/`write_file`/etc:

```clojure
(binding [ec/*execution-context* (:ctx room)]
  (d/join room (d/coder {:id :coder})))
```

### Step 3 — post a message, observe the reply

`(d/post! room msg)` enqueues onto the bus. Subscribers (the agent's
inbox, the audit log, anyone else listening) wake up:

```clojure
(d/post! room (d/message :you :coder "What is 2 + 2?"))

;; The reply lands asynchronously. Sleep a few seconds then read the log:
(Thread/sleep 8000)
(mapv (juxt :from :content) (d/log room))
;; =>
;; [[:you   "What is 2 + 2?"]
;;  [:coder "4."]]
```

### Step 4 — subscribe by capability tag

The agent's inbox is one subscription (`[:to :coder]`). You can also
subscribe by message **type** for cross-cutting concerns. An
"auditor" can log every escalation regardless of who it's addressed to:

```clojure
(require '[dvergr.bus :as bus])

(def escalations (atom []))
(def sub (bus/subscribe! (:bus room) [:type :escalation/budget]))

;; Drain it in a fire-and-forget spin (see `examples/scenario_auditor.clj`
;; for the full pattern).
```

The bus's `:partial/*`, `:directive/*`, `:escalation/*`,
`:notification/*` namespaces each have an opinionated default buffer
policy. See [programming-model.md](programming-model.md) for the
table.

### Step 5 — observe streaming tokens

The CLI's footer shows `last 8.3s 245tok $0.0012` because
`dvergr.cli.streaming` posts `:telemetry/turn-complete` events.
The same events are available to any subscriber:

```clojure
(def turn-events (atom []))
(bus/subscribe! (:bus room) [:type :telemetry/turn-complete])
;; Drain into turn-events the same way as above
```

For real-time token streaming (when the provider supports it), subscribe
to `[:type :partial/token]`. The default buffer is `fixed-buffer 256`
— tokens are discrete data; the default doesn't lose them.

UI consumers that only want "current state" override:

```clojure
(require '[org.replikativ.spindel.pubsub.buffer :as buf])
(bus/subscribe! (:bus room) [:type :partial/token] (buf/sliding-buffer 1))
```

### Step 6 — a substrate-isolated proposal

The killer feature: fork a room, send a worker into the fork to do
something (write files, change state in a branched datahike, etc.),
inspect the result, then **merge** or **discard** atomically.

```clojure
(require '[dvergr.proposals :as p])

(def proposal
  @(p/propose! {:room   room
                :worker (d/coder)
                :goal   "Add input validation to src/app.clj"
                :conn   (:db-conn (:chat-ctx (d/from-chat-context :coder ...)))}))

;; The worker ran in a branched git worktree + datahike. Inspect:
(println (:proposal/summary proposal))
(println (:proposal/test-result proposal))

;; Accept (merges branches atomically into the parent)
(p/accept-proposal! conn (:proposal/id proposal))

;; Or reject (discards the branches, nothing leaks into the parent)
(p/reject-proposal! conn (:proposal/id proposal))
```

The fork uses **yggdrasil** — a copy-on-write protocol across git, datahike,
btrfs, ZFS, and IPFS. The worker's writes go onto branched copies; on
accept, all branches merge atomically through one workspace commit.

## Next steps

- **[Programming Model](programming-model.md)** — the compositional
  kernel: tagged messages, capability routing, GenerationHandle, the
  distributive law λ
- **[CLI Reference](cli.md)** — `dvergr-cli` full keys + persistence +
  provider config
- **[Discourse Model](discourse-model.md)** — the formal architecture
  doc with bialgebra, ToM, drivers
- **[Examples](../examples/)** — runnable scenarios: manager
  escalation, streaming SLAs, auditor, humans + agents

## Troubleshooting

**"Could not locate dvergr.core__init.class..."** — make sure dvergr
is on the classpath. From a checkout: `clojure -M:cli` (uses the
`:cli` alias) or `clojure -M:repl`.

**Agent doesn't reply** — make sure the provider is configured.
`(providers/ensure-initialized!)` must run before the first
`llm-agent` is constructed. The `claude-code` provider needs the
`claude` CLI on PATH.

**"namespace 'dvergr.X' not found"** — check `(:provider config)`
matches a registered provider. List models with
`(dvergr.model.registry/list-models)`.

**Streaming tokens drop** — this was a bug in `spindel.pubsub.pub`
fixed in spindel 0.1.12. Upgrade.
