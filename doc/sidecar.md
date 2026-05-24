# Sidecar — agents in your REPL

`dvergr.sidecar` lets you load an LLM agent into any Clojure REPL
session — your editor's nREPL, a bare `clj`, a long-running daemon —
and drive it as a value. Same JVM, same classpath, same in-memory
state as your project.

This is distinct from `dvergr-cli` (the dedicated TUI app). The CLI
owns the terminal; the sidecar plugs into your existing dev session.
Think of it like loading [clojure-mcp](https://github.com/bhauman/clojure-mcp)
or [scittle](https://github.com/babashka/scittle) — a library you
`require` to get a working vocabulary.

## Minimum example

```clojure
(require '[dvergr.sidecar :as s])

(def agent
  (s/spawn-agent!
    {:cwd   "."
     :model "claude-code-sonnet"}))   ; or claude-sonnet-4-6 via Anthropic API

@(s/ask agent "Read src/hello.clj and add a goodbye fn")
;; => "Added (defn goodbye [] \"goodbye, world\") at line 10."
```

The `cwd` field is the working directory the agent's `read_file` /
`write_file` / `edit_file` / `shell` tools operate against. Setting
it to `.` means the agent edits the project the REPL is running in.

## Provider auto-detection

`spawn-agent!` picks a provider in this order:

1. `:anthropic` if `ANTHROPIC_API_KEY` is in the JVM env
2. `:claude-code` if the `claude` CLI is on `PATH`
3. `:fireworks` (default fallback — needs `FIREWORKS_API_KEY`)

Override with explicit `:provider` and `:model`.

## What you get back

`spawn-agent!` returns a map:

```clojure
{:id        :assistant         ; participant id
 :room      <Room>             ; the dvergr.discourse Room (own bus, own ctx)
 :ctx       <ExecutionContext> ; spindel execution context
 :chat-ctx  <ChatContext>      ; conversation + budget + token accounting
 :pctx      <ParticipantContext>
 :tool-ctx  <ToolContext>      ; tool-execution context with sandbox
 :inbox     <atom of [Message]> ; everything addressed to :you
 :turns     <atom of [Turn]>    ; :telemetry/turn-complete payloads
 :stop!     (fn [])             ; tear down subscriptions
 :id        :assistant
 :user-id   :you}
```

You can reach in:

- `(s/messages agent)` — full conversation history
- `(s/last-reply agent)` — most recent assistant text
- `(s/budget agent)` — live budget snapshot
- `@(:inbox agent)` — every reply addressed to `:you` as a vector
- `@(:turns agent)` — per-turn telemetry (elapsed-ms, cost, tokens)

## ask vs. post

`(s/ask agent msg)` is the synchronous shorthand. It posts the
message and waits up to `:timeout-ms` (default 5 min) for the next
finalised assistant reply. Returns the reply's content string, or nil
on timeout.

For asynchronous / event-driven use, post directly through the room
and watch the bus:

```clojure
(s/with-room agent
  (d/post! (:room agent)
           (d/message :you (:id agent) "explain my.app.core/foo")))

;; Take :inbox snapshots when you want them
@(:inbox agent)
```

## Composing multiple agents

Each `spawn-agent!` is independent — own context, own room, own
subscriptions. Spawn many, observe traffic, route between them.

```clojure
(def researcher (s/spawn-agent! {:id :researcher
                                  :system-prompt "You research..."
                                  :tools :read-only}))
(def coder     (s/spawn-agent! {:id :coder
                                 :system-prompt "You implement..."
                                 :tools :coder}))

@(s/ask researcher "Find the public functions in src/core.clj")
@(s/ask coder "Add docstrings to those functions")
```

To put both agents *in the same room* (so they can address each
other) construct one room manually and pass `:ctx` + an explicit
join. See `dvergr.discourse/room` + `d/join` for the lower-level API.

## Inspecting bus traffic

Every message the agent posts (telemetry, partial tokens, escalations,
tool-results) is on the room's bus. Subscribe directly:

```clojure
(require '[dvergr.bus :as bus])

(def tokens (atom []))
(def sub (bus/subscribe! (:bus (:room agent)) [:type :partial/token]))
(s/with-room agent
  (sp/spawn!
    (sp/spin
      (loop [s (:aseq sub)]
        (when-let [[m r] (sp/await (aseq/anext s))]
          (swap! tokens conj (:payload m))
          (recur r))))))
```

The bus's policy table (see [programming-model.md](programming-model.md))
gives each tag namespace a sensible default buffer; override per
subscription if you need different semantics.

## Cleanup

```clojure
((:stop! agent))
```

Tears down the inbox + telemetry subscriptions. The execution context
remains alive; if you want to fully discard, call
`(org.replikativ.spindel.engine.context/stop-context! (:ctx agent))`.

## When to reach for which

| Use case | Reach for |
|---|---|
| "I want to chat with an agent in a TUI" | `dvergr-cli` |
| "I want an agent to edit my project's files" | `dvergr.sidecar` in your REPL with `:cwd "."` |
| "I want to build agents into my app's runtime" | `dvergr.core` primitives directly |
| "I want multiple agents in one room with routing" | `dvergr.core` + `d/room`/`d/join`/`d/subscribe!` |
| "I want a substrate-forked proposal flow" | `dvergr.proposals/propose!` |

## Forthcoming: shell sandbox via muschel

Today the `shell` tool runs through dvergr's existing tool registry,
which uses unrestricted `ProcessBuilder` underneath. For LLM agents
that should be constrained to a known-safe subset of bash, we're
building [`muschel`](https://github.com/replikativ/muschel) — a real
bash parser + AST + allowlist + Clojure compiler — to plug into
dvergr's shell tool. Once muschel ships:

```clojure
(s/spawn-agent!
  {:cwd "."
   :shell-policy {:allowlist #{"ls" "cat" "grep" "find" "git" "rg"
                                "wc" "head" "tail" "diff" "tree"}}})
```

muschel parses `cmd1 | cmd2 && cmd3` into an AST, checks every leaf
binary against the allowlist (including those inside `$(...)`), and
runs the rest through `babashka.process` with a curated env and locked
cwd. Forbidden constructs (process substitution, here-strings, backtick
substitution, `eval`, `source`, `exec`) fail at parse time.

Until then, the `shell` tool runs unconstrained inside the agent's
cwd. **For untrusted code, run dvergr in a container** (docker /
podman / firejail) — muschel is mistake-prevention, not adversarial
isolation. See [doc/programming-model.md](programming-model.md) for
how the bus + worktree + sandbox layers compose.

## See also

- [Getting Started](getting-started.md) — REPL tutorial from scratch
- [Programming Model](programming-model.md) — the compositional kernel
- [CLI Reference](cli.md) — the TUI app
- [Discourse Model](discourse-model.md) — the formal architecture
