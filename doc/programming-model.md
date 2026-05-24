# Programming Model

dvergr's programming model is a small set of primitives, then everything
composes through tagged messages. This page is the working reference.
For the formal/theoretical view see [discourse-model.md](discourse-model.md).

## The five primitives

| Primitive | Shape | What it gives |
|---|---|---|
| **Tagged message** | `{:from :to :type :payload :metadata :reply-to}` | `:type` is the effect tag (`:directive/raise-budget`, `:escalation/budget`, `:probe/memory`); `:to` is direct routing |
| **Capability subscription** | `(d/subscribe! room participant [:type tag])` | participant receives every message matching the tag, regardless of recipient |
| **Reply correlation** | `(d/ask room target msg)` returns a Deferred bound to the response | works whether `target` is an LLM, human, or scripted bot |
| **Substrate fork** | `(d/fork-room room {:isolation :ctx})` | branched git + datahike; merge or discard atomically |
| **GenerationHandle** | `{:token-source :tool-calls :done :cancel!}` | the F-side primitive — swap deciders without touching the agent loop |

Five primitives. Nothing in the model is special-cased to LLMs; humans,
scripts, monitors, and future agent types all live as participants.

## The Room + Bus

A `Room` is a substrate where participants exchange messages. Under the
hood it's a `dvergr.bus.Bus` — a small pub/sub kernel on spindel's
pub/sub primitives — keyed on two routing dimensions:

```
[:to   <participant-id>]   — direct routing to a participant
[:type <tag>]              — capability routing by message tag
```

Every message reaches every matching subscription. Same source mailbox,
one `mult`, two `pub`s (one keyed by `:to`, one keyed by `:type`).
Both routing dimensions are special cases of the same primitive.

```clojure
(require '[dvergr.core :as d])

(def room (d/room :my-room))
(d/join room (d/coder {:id :coder}))   ; default inbox subscription on [:to :coder]
```

## Opinionated buffer policy

Per-namespace defaults in `dvergr.bus/*default-buffers*`:

| Tag namespace | Default buffer | What the model says |
|---|---|---|
| `:message` | `fixed-buffer 64` | first-class content; generous backpressure |
| `:directive` | `fixed-buffer 16` | imperatives; serial; never lose |
| `:escalation` | `fixed-buffer ##Inf-ish` | must be answered or explicitly time out |
| `:partial` | `fixed-buffer 256` | LLM tokens / stream chunks are discrete data — losing one loses information |
| `:tick` | `sliding-buffer 1` | cadence; latest pulse is the meaningful snapshot |
| `:source` | `sliding-buffer 8` | external readings; recent N tunable per source |
| `:telemetry` | `sliding-buffer 32` | observation events; UIs want recent, not full backlog |

Each line is a programming-model commitment. Two strong opinions:

**Escalations cannot be silently dropped.** An agent posting
`:escalation/budget` *can* assume some handler will answer (or the bus
health monitor will surface a stuck queue). Forces explicit
escalation chains.

**Streaming defaults to fixed-buffer.** Tokens are discrete data;
losing them loses information. UI consumers that only want "current
accumulated state" override per-subscription with `sliding-buffer 1`.
This is a *consumer* policy choice, not a producer commitment.

## Dynamic subscriptions

Participants can subscribe to extra tag-channels at any time during
their lifetime. A monitor agent that watches every escalation:

```clojure
(def auditor
  (d/participant
    {:id :auditor
     :on-message
     (fn [_p msg]
       (sp/spin
         (swap! audit-log conj (select-keys msg [:from :to :type]))
         nil))}))

(binding [ec/*execution-context* (:ctx room)]
  (d/join room auditor)
  (d/subscribe! room auditor [:type :escalation/budget]))
```

Both subscriptions (the default `[:to :auditor]` inbox AND the
extra `[:type :escalation/budget]`) pump into one merge mailbox the
auditor's spin drains. Same on-message handler for both shapes.

## The compositional kernel

Agents escalate by **posting a tagged message** — not by calling a
named manager:

```clojure
(d/post! room {:type :escalation/budget
               :from :coder
               :payload {:remaining 200 :requested 500}})
```

A policy-bot somewhere has subscribed to `[:type :escalation/budget]`.
It replies with a `:directive/raise-budget` addressed back to the
escalator:

```clojure
(d/post! room {:to :coder
               :type :directive/raise-budget
               :from :policy
               :payload {:amount 500}})
```

The coder's existing on-message handler branches on `:type` and
applies the bump. Neither party hardcodes the other's identity —
they compose through capability routing.

See [`examples/scenario_manager_escalation.clj`](../examples/scenario_manager_escalation.clj)
for the full pattern.

## The bialgebra

An LLM agent (or any participant) is a **bialgebra**:

- **F-arc (past, induction):** memory + budget + status. F-steps fold
  incoming messages into memory; `compaction` folds memory into a
  summary; budget delta is the cost of folding.
- **G-arc (future, coinduction):** spin-race over the inbox + any
  subscribed tag-channels + budget-threshold + cancel signal. Same race
  observes every kind of event.
- **Distributive law λ:** the `GenerationHandle`. F is wrapped in a
  reactive subsystem (token stream, tool-call mailbox, done deferred,
  cancel fn) so G can race against it.

```clojure
;; The handle is the named lambda
{:token-source  PAsyncSeq of partial-output deltas (or nil)
 :tool-calls    mailbox/aseq of tool-call requests + results (or nil)
 :done          Deferred resolved with the final result
 :cancel!       0-arg fn to abort generation early}
```

Four canonical adapters wrap different F shapes:

- **`sync-handle`** — F runs inline in a spawned spin (scripted bots, mocks)
- **`future-handle`** — F runs on a Clojure future, result bridged
  via Deferred (the current LLM call pattern)
- **`external-handle`** — caller controls when `:done` resolves
  (human-in-loop, slow async LLM)
- **`streaming-handle`** — F streams deltas via aseq (real SSE pump)

Plus combinators `race-handles` (first-arrival wins; losers cancelled)
and `fallback-handle` (primary errors → recover-fn builds secondary).

Swap adapters to change F's shape without touching the agent's G-side
spin. The distributive law is *named and pluggable* rather than baked
into one closure.

## Built-in directives

`llm-agent` already handles a small set of tagged messages:

| Tag | Effect |
|---|---|
| `:user/message` (or message with no `:type`) | run a generation turn |
| `:directive/raise-budget` | bump turn cap by `:payload.turns` (default 4) |
| `:directive/wrap-up` | flip a wrapping-up flag; loop exits early |
| `:directive/cancel` | flip a cancelled flag; current generation aborts at next race point |
| `:directive/switch-model` | merge `:payload` into the spec atom (live) |
| `:directive/system-message` | inject a `:system` message into chat-ctx |
| `:probe/memory` | reply with the chat-ctx message list |

Every directive is a tag the agent **chooses** to handle. Add a new
directive = add a new branch to the case-dispatch. No subclassing, no
overriding the "policy". And nothing about it is specific to LLMs — a
human participant could handle `:directive/wrap-up` just as well
(e.g., display a "wrap up" badge in their UI).

## Compaction as race-arm (optional)

Compaction normally runs synchronously before each LLM turn. For
hot agents that hit compaction often, the race-arm mode runs the
expensive LLM-based summarization on a future in parallel with the
next generation:

```clojure
(d/llm-agent
  {:id   :hot-coder
   :spec spec
   :compaction {:auto?    true
                :strategy :race-with-turn}    ; opt-in
   ...})
```

The current turn uses pre-compaction context; the next turn picks up
the compacted state. Saves ~2-3s on the boundary turn.

## Substrate forks (yggdrasil)

`fork-room {:isolation :ctx}` branches every yggdrasil system in the
parent ctx — datahike connection, git worktree, KB conn, etc. The
worker inside the fork sees a forked DB; on `merge-room`, all branches
merge atomically through one workspace commit.

```clojure
(let [fork (d/fork-room room {:isolation :ctx})]
  (d/join fork (d/coder))
  (def reply @(d/ask fork :coder {:content "Refactor src/foo.clj"}))
  ;; Inspect — the fork's writes haven't touched the parent yet
  (if (passes-tests? reply)
    (d/merge-room room fork)
    (d/discard fork)))
```

This is the "proposal" pattern, packaged in `dvergr.proposals`:

```clojure
(require '[dvergr.proposals :as p])

(def proposal @(p/propose! {:room   room
                            :worker (d/coder)
                            :goal   "Refactor src/foo.clj"
                            :conn   conn}))
;; ... present to a human via UI ...
(p/accept-proposal! conn (:proposal/id proposal))   ; merges atomically
;; or
(p/reject-proposal! conn (:proposal/id proposal))   ; discards
```

See [discourse-model.md §6.5](discourse-model.md) for the theory-of-mind
view of substrate-fork: `(simulate-reply parent target msg)` runs a
fork where only the target's behavior matters, then discards. The
parent's state is provably untouched.

## When to reach for what

| Want to | Use |
|---|---|
| Send a message to one named participant | `(d/post! room (d/message :from :to "..."))` |
| Send to all subscribers of a capability | `(d/post! room {:type :escalation/budget :payload {...}})` |
| Listen for any escalation regardless of recipient | `(d/subscribe! room p [:type :escalation/budget])` |
| Wait for one reply | `(d/ask room target msg)` |
| Fork the room for a what-if probe | `(d/fork-room room)` (shared ctx, ToM-style) |
| Fork with full git+DB isolation | `(d/fork-room room {:isolation :ctx})` |
| Run a worker on a goal, get a proposal | `(dvergr.proposals/propose! ...)` |
| Swap an LLM's decision shape | `(llm-agent {:run-turn-fn ...})` using a GenerationHandle adapter |
| Observe live cost / latency | subscribe to `[:type :telemetry/turn-complete]` |

## Further reading

- **[Discourse Model](discourse-model.md)** — formal architecture
- **[CLI](cli.md)** — `dvergr-cli` reference
- **[Examples](../examples/)** — runnable scenario programs
