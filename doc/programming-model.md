# Programming Model

dvergr's programming model is a small set of primitives, then everything
composes through tagged messages. This page is the working reference: first
the core model (Rooms, Participants, the Bus), then the primitives and how
they compose, and at the end why it is called a *discourse*.

## Rooms and agents

Dvergr's core model is **Rooms + Participants on a Bus**. Every conversation —
a human chatting with one agent, a multi-agent debate, a speculative coding
fork — is the same shape. Understand this part and the rest of the system
follows.

### The three pieces

- **Room** — a discourse venue. It owns a set of participants, a bus they talk
  over, an execution context, and (optionally) a durable store. Rooms have a
  stable `:id`, a human-facing `slug`, and can nest under a parent.
- **Participant** — anything that sends and receives messages: an LLM agent, a
  human at a TUI/web client, or an egress adapter mirroring a room into Telegram.
  A participant is a long-lived reactive process with an `on-message` handler
  that returns a reply (or nil for silence). An LLM agent's Run still
  completes when it stays silent: with no reason for nil, `:silent` for a
  `[SKIP]` or blank reply, `:withheld` for a reply a wrapper drops
  ([runs.md](runs.md#how-a-turn-ends)).
- **Bus** — the pub/sub substrate. Posting a message fans it out to every
  matching subscriber and appends it to the room's log. Participants never call
  each other directly; they only post to, and subscribe on, the bus.

### Addressing: DM vs. broadcast

Every message carries a single `:to` value. Each participant subscribes to two
topics: `[:to <its-id>]` (directly addressed) and `[:to nil]` (broadcast). Since
a message has exactly one `:to`, nobody is delivered the same message twice, and
the chat-room semantic falls out naturally: a broadcast reaches everyone; a
targeted post reaches only the addressee.

For an explicitly selected group, `discourse/with-recipients` addresses ONE
message to several participant keywords:

```clojure
(d/post! room (d/with-recipients (d/message :you nil "Compare these options")
                                [:researcher :reviewer]))
```

The envelope retains `:to nil` and records `:metadata {:dvergr/recipients
#{:researcher :reviewer}}`. Its explicit recipients replace broadcast routing:
only those direct inboxes receive it; a type subscriber and the room history
see it once. Every observer receives the same immutable envelope, not an
address-rewritten copy. Do not post separate same-UUID copies to different
targets: durable message deduplication correctly suppresses the later copies.
Retry the one multicast envelope with its original id instead. Recipients are
typed `:message/recipients` cardinality-many keywords in Datahike, not EDN text;
they are independent of `:audience` (mentioned actors, not a delivery address).

Without an explicit recipient selection, frontends decide *who to address*
with `discourse/room-target`:

> A room with exactly **one** agent participant (reserved `:_…` ids ignored)
> addresses that agent — it's a **DM**. A room with **zero or several** agents
> returns `nil` — input **broadcasts**.

Every frontend (TUI, web, REPL, medium adapters) routes through this single rule,
so a room behaves identically no matter how it was entered. Deriving the target
from the room — not from UI state — is also what prevents echo loops, where
broadcast input would feed an agent that is itself subscribed to `[:to nil]`.

### How messages flow

1. Someone **posts** a message to the room (`discourse/post!`), from a human
   client, an adapter, or another agent's reply.
2. The **bus** fans it out to every matching subscription and logs it.
3. Each subscribed **participant** sees it, runs its `on-message`, and — if it
   has something to say — **posts a reply** back to the room, which flows the
   same way.

Agents **join** a room with `discourse/join`, which wires their inbox +
broadcast subscriptions and starts their process. Leaving (`leave`) just
unsubscribes them. Persistent rooms also mirror every message into a store, so
history survives restarts.

### Threads: topical projections inside a Room

A Room still owns one chronological message log, participant set, policy, and
substrate. A **Thread** is a lightweight topical projection over that log, not a
child Room or database. Every message carries two independent causal fields:

- `:in-reply-to` names its immediate parent;
- `:thread-root-id` names the stable root of the topic.

Top-level messages self-root. Use `reply` for nested replies so the root is
preserved automatically:

```clojure
(require '[dvergr.core :as d])

(def room (d/room :planning))
(def root (d/message :you :researcher "Compare the pricing options"))
(d/post! room root)

(def follow-up
  (d/reply :you :researcher "Include self-hosting" root))
(d/post! room follow-up)

;; The thread is queried from the Room; there is no Thread registry.
(d/messages room {:thread-root-id (:id root)})
```

Thread identity also informs the attention boundary for an LLM agent. The default
policy lets a direct same-thread message steer its active execution, while a
direct message rooted elsewhere queues a separate execution without cancellation
or topic mixing. `llm-agent` accepts an `:attention-policy` returning a validated
`dvergr.discourse.attention` decision with independent memory, activation,
control, boundary, and priority axes. The former `:steer`, `:queue`, and
`:observe` results remain accepted, so deployments can migrate while refining
human correction versus peer-agent chatter using authoritative identity and
addressing data.
Tool activity and telemetry remain non-waking observations.

When a topic needs separate participants, permissions, budget, or a branched
workspace, promote the work to a child **Workroom**. That is an actual Room; an
ordinary Thread deliberately is not.

### Forks: speculative work, then merge or discard

A room can be **forked** copy-on-write for hypothetical or risky work:

- `fork-room` clones the participants into a sibling room seeded with the
  parent's history. With `:isolation :ctx`, it also forks the execution
  context — branching the room's Datahike stores, its Geschichte workspace, and
  other substrate — so
  the fork's side effects stay isolated until you decide their fate.
- `merge-room` settles the fork's registered systems into the parent through a
  prechecked, journalable boundary (Datahike and Geschichte keep their native merge semantics).
- `discard` throws the fork away, deleting its branches; the parent is untouched.

This is the backbone of the proposal/review lifecycle (an agent works in a fork,
proposes a merge, a reviewer accepts or rejects) and of cheap *theory-of-mind*
probes — `simulate-reply` forks the room, asks a hypothetical, captures the
reply, and discards, all without disturbing the real conversation.

### In one breath

Rooms are venues; Threads are topical projections; participants are reactive
processes; the bus routes between them; addressing is "one agent = DM, otherwise
broadcast"; and forking gives any Room value semantics so speculation is free
and reversible.

## The five primitives

A **tagged message** is the unit of exchange — `{:from :to :type :payload
:metadata :reply-to}`. `:to` routes it directly to a participant; `:type` is an
*effect tag* (`:directive/raise-budget`, `:escalation/budget`, `:probe/memory`),
the lever for capability routing.

A **capability subscription**, `(d/subscribe! room participant [:type tag])`,
makes a participant receive *every* message carrying that tag, regardless of who
it was addressed to — the basis for escalation, monitoring, and fan-out.

**Reply correlation** is `(d/ask room target msg)`: it posts a message and returns
a `Spin[Message]` bound to the response (`await` it inside a spin, `@`-deref at the
REPL), so request/response composes the same way whether `target` is an LLM, a
human, or a scripted bot. (`ask` lives in the discourse algebra — `dvergr.core` /
`dvergr.discourse`; the higher-level `dvergr.clients.client` REPL surface drops it
in favor of `post!` + observe.)

A **substrate fork**, `(d/fork-room room {:isolation :ctx})`, branches the room's
registered systems together; the fork's side effects stay isolated until you
`merge` or `discard` them through its affine handle.

A **GenerationHandle** — `{:token-source :tool-calls :done :cancel!}` — is the
decision-side (F) primitive: it lets you swap *deciders* (which LLM, or a scripted
policy) without touching the agent loop.

Nothing in the model is special-cased to LLMs; humans, scripts, monitors, and
future agent types all live as participants.

## Agent programs in the REPL

The room SCI surface also exposes `dvergr.agent`: immutable, versioned
AgentDefs and Rosters are ordinary workflow data, while `hire!` is the explicit
effect that starts a durable Run and returns an opaque handle whose result is an
explicit native Spindel Spin. This is the first executable layer for agents
constructing specialized teams and parallel workflows for themselves without a
hidden mutable roster.

See [Agent programs in the room REPL](agent-programs.md) for the API, a
provider-free runnable example, and the state/fork contract. Besides the
deterministic `:echo` and `:scripted` programs, `hire!` runs the `:llm` program
kind, a bounded Dvergr-native model/tool loop, on the same boundary. Code running
inside a paid `:llm` Run may itself hire only `:echo` and `:scripted` children
(`child-program-authority` in `dvergr.agent.program`).

## The Room + Bus

A `Room` is a substrate where participants exchange messages. Under the
hood it's a `dvergr.runtime.bus.Bus` — a small pub/sub kernel on spindel's
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

Per-namespace defaults in `dvergr.runtime.bus/*default-buffers*`:

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
`:escalation/budget` *can* assume some handler will answer. Nothing
watches for a stuck queue, so this forces explicit escalation chains.

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

## Attention decisions and execution boundaries

An utterance is a durable Room fact; it is not ambient process-control
authority. Each participant may interpret the same fact differently through a
pure attention policy. Policies now return a validated product rather than
having to choose one overloaded action:

```clojure
{:memory     :remember       ; :ignore | :remember | :include
 :activation :enqueue        ; :none | :enqueue | :wake
 :control    :continue       ; :continue | :integrate | :restart | :suspend | :cancel
 :at         :quiescent
 :priority   0
 :reason     :conversation/different-thread}
```

The axes are independent: a fact can update awareness and enqueue later work
without interrupting the active computation. The standard boundary vocabulary
is `:now`, `:next-safe-boundary`, `:before-model`, `:token`, `:before-tool`,
`:after-tool`, `:after-model`, and `:quiescent`. An interpreter advertises and
honors only the boundaries it actually supports.

The native LLM participant negotiates each decision against an explicit
capability description. It honors memory (`ignore`, `remember`, and
safe-boundary `include`), activation (`none` and `enqueue`),
and `continue`, `restart`, `suspend`, or run-local `cancel` at boundaries it can
implement honestly. Provider-specific integration points and non-zero priority
remain unsupported and are retained as durable deferred attention; they do not
silently become ordinary queued Runs. `wake` is part of the provider-neutral
algebra but is not advertised by this interpreter because it has no durable LLM
continuation to wake.

Attention is stored as a typed participant projection in the Room store, not as
a hidden chat message. The shared transcript remains immutable speech. Provider
working context is reconstructed from the participant's Run triggers, its own
outputs, and monotone **applied** `include` admissions. Each decision has stable
identity; a separate append-only disposition proves when all supported axes
were applied. A ready decision without that disposition is recovery/audit input,
not a command that may be blindly replayed across external effects. The native
interpreter keeps live scheduling machinery process-local and exposes unapplied
facts for an explicit recovery policy.

`remember` stays durable awareness
without entering model input, while `ignore` excludes the fact from that input.
Thus live execution and restart/replay apply the same memory decision without
contaminating thread or message projections. At first upgrade, a deterministic
applied baseline preserves the existing provider context before new decisions
take over projection. The former `:steer`, `:queue`, and
`:observe` policy results remain accepted through normalization. Structured work admission is now
available directly in native code and the room SCI REPL as `spindel.work/latest`,
`serial`, `busy`, and bounded `parallel`. These are higher-order FRP policies:
they turn event values into owned child computations and make cancellation,
quiescence, backpressure, and completion observable. Attention decides what an
activity means to one participant; work admission decides how resulting
computations overlap. Keeping these layers separate lets another interpreter add
token- or tool-boundary integration without creating another scheduler or
changing the decision algebra.

## Built-in directives

`llm-agent` already handles a small set of tagged messages:

| Tag | Effect |
|---|---|
| message with no `:type` (the default branch) | run a generation turn |
| `:directive/raise-budget` | raise the dollar budget by `:payload.dollars` (default 0.25) |
| `:directive/cancel` | flip a cancelled flag; current generation aborts at next race point |
| `:directive/switch-model` | merge `:payload` into the spec atom (live) |
| `:directive/system-message` | inject a `:system` message into chat-ctx |
| `:probe/memory` | reply with the chat-ctx message list |

Every directive is a tag the agent **chooses** to handle. Add a new
directive = add a new branch to the case-dispatch. No subclassing, no
overriding the "policy". And nothing about it is specific to LLMs — a
human participant could handle `:directive/cancel` just as well
(e.g., show a "stop" control in their UI).

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
parent ctx — the room's Datahike stores, its Geschichte workspace, the KB, etc. The worker inside
the fork sees a forked DB; `merge-room` settles each registered system through
the canonical recoverable settlement boundary.

```clojure
(let [fork (d/fork-room room {:isolation :ctx})]
  (d/join fork (d/coder))
  (def reply @(d/ask fork :coder {:content "Refactor src/foo.clj"}))
  ;; Inspect — the fork's writes haven't touched the parent yet
  (if (passes-tests? reply)
    (d/merge-room room fork)
    (d/discard fork)))
```

Bounded agent programs normally use the sandbox's `dvergr.agent/hire!` (on the
host: `dvergr.agent.program/hire!`): it opens the canonical
world, admits a durable Run, and returns a native result Spin for composition.
`spawn_agent` and `propose_change` are model-tool adapters over that exact
interpreter, selecting `:automatic` and `:review` settlement respectively. They
do not create Participants, lifecycle logs, or forks of their own.

The theory-of-mind view of a substrate fork: `(simulate-reply parent
target msg)` runs a fork where only the target's behavior matters, then
discards — the parent's state is provably untouched.

## When to reach for what

| Want to | Use |
|---|---|
| Send a message to one named participant | `(d/post! room (d/message :from :to "..."))` |
| Send to all subscribers of a capability | `(d/post! room {:type :escalation/budget :payload {...}})` |
| Listen for any escalation regardless of recipient | `(d/subscribe! room p [:type :escalation/budget])` |
| Wait for one reply | `(d/ask room target msg)` |
| Fork the room for a what-if probe | `(d/fork-room room)` (shared ctx, ToM-style) |
| Fork with full workspace+DB isolation | `(d/fork-room room {:isolation :ctx})` |
| Construct and compose delegated work | immutable `dvergr.agent` Rosters + `hire!` / `result-spin` |
| Delegate from a model tool call | `spawn_agent` or `propose_change`, thin adapters over `dvergr.agent/hire!` |
| Swap an LLM's decision shape | `(llm-agent {:run-turn-fn ...})` using a GenerationHandle adapter |
| Observe cost / budget | read the chat-ctx `:budget-signal` (a tracked signal), or `dvergr.rooms.stats` |
| Classify conversational activity | return `dvergr.discourse.attention/decision` data from an attention policy |

## Why discourse

This section is short, optional theoretical context: *why it's shaped this way*.

dvergr calls its core abstraction a **discourse** rather than a "message bus" or
an "agent graph" on purpose: the design takes the *linguistic* case seriously.
Participants — humans, LLMs, scripts — are **speakers** exchanging **utterances**
in a shared room, and the framework's primitives line up with ideas from the
philosophy and the Bayesian modelling of language.

### Messages as speech acts

A message carries a `:type`, and that `:type` is its **illocutionary force** — in
the sense of speech-act theory (Austin, Searle): what the utterance *does*, not
just what it says. `:directive/raise-budget` directs; `:escalation/budget`
escalates; `:probe/memory` asks. Participants then **subscribe by the kinds of
speech act they answer** (capability routing), so a room is organised around *who
responds to what kind of move* — not by hardcoded sender→receiver wiring. Adding
a new kind of interaction is adding a new tag, not rewiring the graph.

An utterance may also name one primary application object through durable
metadata such as `{:object {:kind :proposal :id #uuid "…"}}`. The speech-act
type still says what the message *does*; the object says what it is *about*.
Dvergr validates and stores that reference as typed, indexed datoms while
remaining ignorant of the referenced domain model. Applications can therefore
render the same proposal, task, artifact or approval in chat and in their own
projections without encoding identity in prose.

### Theory of mind: RSA, made reactive

The deepest hook is **theory of mind**. In the Rational Speech Acts model
(Goodman & Frank), a speaker chooses an utterance by predicting how a listener
will update *their* beliefs — pragmatics as recursive Bayesian reasoning. dvergr
makes this *operational* rather than a line in a system prompt, because the
runtime forks cheaply:

```clojure
(defn simulate-reply
  "Fork the room, deliver a hypothetical to `other`, await their reply in the
   fork, then discard it. Returns the imagined reply; the real room is untouched."
  [room other hypothetical]
  (spin
    (let [fork     (fork-room room)
          imagined (await (ask (get-participant fork other) hypothetical))]
      (discard fork)
      imagined)))
```

A speaker can run `simulate-reply` over a few candidate utterances and keep the
one that best moves the listener — **RSA as implementation, not as prompt**. The
same primitive underlies "imagine the conversation before speaking"
(`imagine-conversation`), moderator previews, and debate look-ahead. The
`align-on` combinator — iteratively refining a statement toward common ground —
is named after Tessler et al.'s work on AI-assisted deliberation.

### The inference reading (optional)

Pushed further, the substrate has a probabilistic reading: a participant is a
particle in a Sequential Monte Carlo process, a message is an observation, a fork
is "try another hypothesis," and an accepted artifact is a posterior that becomes
the next prior — *programming as and for inference*. dvergr does **not** require
this mode (most workflows run once, normally, with no resampling); spindel's
inference suite simply makes population SMC over forked rooms available when a
workflow wants it. The mathematical frame is Feynman–Kac (Del Moral).

### Lineage

- **Rational Speech Acts (RSA)** — Goodman, Frank, Tessler et al.,
  <http://www.problang.org>. Pragmatic reasoning as recursive Bayesian inference
  over utterances; dvergr's theory-of-mind hook follows it.
- **Cohn-Gordon & Bärenz** — linguistic-convention convergence + reactive
  probabilistic inference; bridges RSA-style pragmatics with reactive multi-agent
  FRP (Rhine FRP, choreographic programming) — the bridge dvergr's substrate
  makes concrete.
- **rhine-bayes** — Bärenz et al. (Tweag, 2023), *online reactive Bayesian
  inference* (SMC on clock-safe FRP streams); the conceptual ancestor of
  spindel's inference package.
- **AI mediation / common ground** — Tessler et al., *Science* 2024; the
  `align-on` combinator is named for it.
- **Speech-act theory** — Austin, *How to Do Things with Words*; Searle — the
  reading of `:type` as illocutionary force.
- **Feynman–Kac** — Del Moral (2004); the math for the optional inference reading.

*The full internal design (the four-layer SMC ladder, the Feynman–Kac account,
LLaMPPL/ToT/RAP comparisons) is kept out of the public docs deliberately; this
section is the distilled version.*

## Further reading

- **[CLI](cli.md)** — `dvergr-cli` reference
- **[Examples](../examples/)** — runnable scenario programs
