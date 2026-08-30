# Rooms & Agents

Dvergr's core model is **Rooms + Participants on a Bus**. Every conversation —
a human chatting with one agent, a multi-agent debate, a speculative coding
fork — is the same shape. Understand this page and the rest of the system
follows.

## The three pieces

- **Room** — a discourse venue. It owns a set of participants, a bus they talk
  over, an execution context, and (optionally) a durable store. Rooms have a
  stable `:id`, a human-facing `slug`, and can nest under a parent.
- **Participant** — anything that sends and receives messages: an LLM agent, a
  human at a TUI/web client, or an egress adapter mirroring a room into Telegram.
  A participant is a long-lived reactive process with an `on-message` handler
  that returns a reply (or nil for silence).
- **Bus** — the pub/sub substrate. Posting a message fans it out to every
  matching subscriber and appends it to the room's log. Participants never call
  each other directly; they only post to, and subscribe on, the bus.

## Addressing: DM vs. broadcast

Every message carries a single `:to` value. Each participant subscribes to two
topics: `[:to <its-id>]` (directly addressed) and `[:to nil]` (broadcast). Since
a message has exactly one `:to`, nobody is delivered the same message twice, and
the chat-room semantic falls out naturally: a broadcast reaches everyone; a
targeted post reaches only the addressee.

The one rule frontends use to decide *who to address* is `discourse/room-target`:

> A room with exactly **one** agent participant (reserved `:_…` ids ignored)
> addresses that agent — it's a **DM**. A room with **zero or several** agents
> returns `nil` — input **broadcasts**.

Every frontend (TUI, web, REPL, medium adapters) routes through this single rule,
so a room behaves identically no matter how it was entered. Deriving the target
from the room — not from UI state — is also what prevents echo loops, where
broadcast input would feed an agent that is itself subscribed to `[:to nil]`.

## How messages flow

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

## Threads: topical projections inside a Room

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

## Forks: speculative work, then merge or discard

A room can be **forked** copy-on-write for hypothetical or risky work:

- `fork-room` clones the participants into a sibling room seeded with the
  parent's history. With `:isolation :ctx`, it also forks the execution
  context — branching the Datahike DB, git worktrees, and other substrate — so
  the fork's side effects stay isolated until you decide their fate.
- `merge-room` settles the fork's registered systems into the parent through a
  prechecked, journalable boundary (Datahike and git retain their native merge semantics).
- `discard` throws the fork away, deleting its branches; the parent is untouched.

This is the backbone of the proposal/review lifecycle (an agent works in a fork,
proposes a merge, a reviewer accepts or rejects) and of cheap *theory-of-mind*
probes — `simulate-reply` forks the room, asks a hypothetical, captures the
reply, and discards, all without disturbing the real conversation.

## In one breath

Rooms are venues; Threads are topical projections; participants are reactive
processes; the bus routes between them; addressing is "one agent = DM, otherwise
broadcast"; and forking gives any Room value semantics so speculation is free
and reversible.
