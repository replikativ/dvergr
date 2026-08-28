# Recursive work orchestration

Status: accepted design contract, implemented incrementally. This document fixes
the vocabulary, ownership, and interoperability boundaries for the implementation
work; a staged API described below may not exist yet.

## Objective

Dvergr should support teams of communicating reasoning agents that solve complex
tasks together without turning a room into a linear job queue or flooding its
conversation with tool chatter.

The target combines four properties:

1. Agents remain long-lived room participants that observe conversation and react
   selectively, rather than stateless functions invoked by a workflow engine.
2. Complex work can recursively create scoped teams, fork substrate state, and
   merge or discard their results.
3. Every consequential action remains inspectable even when the default UI shows
   a compact, hierarchically generated summary.
4. Codex, OpenCode, Claude Code, native Dvergr agents, and later providers project
   into one provider-neutral work protocol.

This is not a proposal to model every conversational turn as a heavyweight
workflow run. The room's chronological log is a synchronization order; its
semantic structure is a graph.

## Room and thread boundary

`Room` remains Dvergr's primary social and work substrate. `Thread` is its main
conversation-topology extension: a lightweight projection over messages in the
same room, store, policy environment, and chronological log. It does not create a
database, participant registry, execution context, or workspace.

Tasks and agent executions are orthogonal projections over Room facts rather than
extensions of Room itself. A Workroom is different: it is an actual child Room,
created only when work needs scoped participants, policy, budget, resources, or a
forked substrate.

## Vocabulary

Use the following concepts distinctly:

| Concept | Meaning |
|---|---|
| **Room** | Long-lived social and work environment with participants, messages, resources, and policy. |
| **Thread** | Lightweight topical conversation rooted at a message. It shares the room substrate. |
| **Task** | Durable intention or delegated responsibility to produce an outcome. |
| **Workroom** | Scoped child room for complex collaborative work, optionally with a forked substrate. |
| **Agent execution** | One bounded invocation/attempt by one agent in response to a trigger. |
| **Workflow run** | One invocation of an explicit reusable workflow. |
| **Simulation run** | One bounded inference or what-if experiment. |
| **Activity** | An observation about execution: tool action, edit, test, approval, error, or progress. |
| **Process** | A live execution mechanism and control handle. It is transient unless separately persisted. |

A thread answers “what are we discussing?” A task answers “what outcome is owed?”
An execution answers “what did this agent do this time?” A run is reserved for an
explicit program or simulation whose invocation has its own lifecycle.

## The semantic graph

Several projections coexist over the same durable facts:

```text
chronological room log
  M1 pricing question
  M2 deployment question
  A1 pricing research activity
  B1 deployment diagnostic activity
  B2 deployment response
  A2 pricing response

thread projection
  pricing:    M1 -> A1 -> A2
  deployment: M2 -> B1 -> B2

work projection
  pricing task -> researcher execution -> evidence -> A2
  deploy task  -> operator execution   -> diagnosis -> B2
```

These projections are deliberately many-to-many:

- one message may trigger multiple agent executions;
- one task may require multiple attempts and agents;
- one thread may contain several tasks and executions;
- one execution may produce messages, files, proposals, and simulations;
- one workroom may contain multiple threads;
- an artifact may support several summaries or decisions.

Do not make a run or task own a private linear copy of the room transcript.
Correlate facts using identities and edges.

## Conversation topology

`Message` already carries `:in-reply-to`. Add a durable thread-root identity so
clients can query a thread without recursively loading every parent:

```clojure
{:id             message-id
 :in-reply-to    parent-message-id
 :thread-root-id  root-message-id}
```

Rules:

- A top-level message is its own thread root.
- A reply preserves the root and names its immediate parent.
- A thread is a projection inside a room, not a separate room or database.
- Busy multi-agent clients may group replies beneath the root; quiet DMs may show
  the same data as a flattened conversation.
- Thread membership influences attention and notification policy, but does not by
  itself force every participant to respond.

## Observation, wake, and steering

Dvergr's current behavior has a useful foundation:

- each `[room, agent]` has a long-lived working `ChatContext`;
- the room fold observes conversational messages from other participants;
- only direct or broadcast delivery wakes an agent;
- tool-activity rows are excluded from model context;
- an addressed message during an active turn currently steers it;
- non-addressed messages update room awareness without preempting the turn.

Make the target semantics explicit. These are defaults, classified by an
attention policy rather than permanently equating thread membership with
interruption. The policy receives the active and incoming envelopes plus Room and
Participant, so deployments can use authoritative sender identity and addressing:

| Incoming fact | Active execution behavior |
|---|---|
| Same-thread direct message | Steer at the next safe model boundary. |
| Different-thread direct message | Queue a new execution; do not mix topics or cancel by default. |
| Explicit urgent interrupt | Interrupt or suspend according to policy. |
| Passive message in same thread | Observe; integrate at a declared attention boundary. |
| Passive message in another thread | Retain as room awareness for later context assembly. |
| Tool activity, telemetry, reaction | Never wake an LLM agent by default. |
| Explicit delegation | Create or resume a task and wake its assignee. |

Start with one active execution per `[room, agent]`; agents remain concurrent with
one another. Later, one agent identity may run concurrent task-scoped executions
using forked contexts.

Every execution should eventually record a context frontier rather than relying
on an implicit moving signal:

```clojure
{:execution/context
 {:room-basis    basis-or-tx
  :thread-root   root-message-id
  :trigger       trigger-message-id
  :observations  [explicit-message-ids]}}
```

This makes replay, diagnosis, simulation, and “why did it miss that message?”
answerable. It does not require copying the room database.

## Recursive workrooms

When a thread becomes long-running, multi-agent, branchable, or approval-gated,
promote its work into a child workroom:

```text
product room
└── thread: Implement forecasting
    └── forecasting workroom
        ├── lead
        ├── data specialist
        ├── implementer
        └── reviewer
```

A workroom may:

- inherit selected thread messages and resources;
- fork the Spindel execution context;
- branch Datahike, Geschichte/git, and other Yggdrasil systems;
- select its own participants, budget, permissions, and notification policy;
- host ordinary directed or broadcast agent communication;
- publish summaries and proposals to its parent thread;
- merge accepted substrate changes or discard rejected experiments.

A child agent may recursively create another workroom. Existing
`fork-room {:isolation :ctx}`, `merge-room`, and `discard` provide the substrate
semantics; orchestration should compose them rather than introduce a second
branch manager.

Workspace policy must be explicit:

```clojure
:workspace/shared-read   ; safe concurrent investigation
:workspace/shared-write  ; only when conflict semantics are accepted
:workspace/branch        ; isolated edits, later merge/review
```

## Provider-neutral task protocol

The first orchestration surface should be small and message-oriented:

```clojure
(delegate! context
  {:objective        "Review the access-control changes"
   :parent-task      parent-task-id
   :thread-root      thread-root-id
   :agent            :agent/reviewer
   :context-policy   :thread-and-resources
   :workspace-policy :workspace/branch})
;; => task-id

(message! task-id content)     ; deliver information, no turn required
(follow-up! task-id content)   ; deliver and start/resume a turn
(interrupt! task-id reason)
(await! [task-id ...])
(snapshot task-id)
```

The public names are provisional. Preserve these behavioral distinctions even if
the final API is expressed as tagged room messages:

- sending information is not the same as waking an agent;
- following up is not the same as creating a new task;
- interrupting a live execution is not deleting its durable task;
- waiting is internal coordination, not a message in the public room;
- completion delivers a result to the parent without requiring publication to
  the parent room timeline.

Task identity is separate from agent identity. Persistent room agents and
task-scoped ephemeral workers should both implement the same protocol.

### Context policy

Delegation must say what the child sees:

```clojure
:context/none
:context/task-brief
:context/thread
:context/selected-messages
:context/full-snapshot
```

Record the selected basis/frontier. Avoid passing opaque mutable parent context.

### Capability policy

Delegated permissions narrow by default. A child receives only the tools,
resources, network domains, credentials, and write scopes necessary for its task.
An expansion requires an explicit policy decision or approval.

External effects retain their approval requirements regardless of delegation
depth. A parent cannot launder authority through a child.

### Communication vocabulary

Agents communicate conclusions and evidence, not required private chain-of-thought.
Useful typed speech acts include:

```clojure
:work/brief
:work/finding
:work/question
:work/answer
:work/proposal
:work/review
:work/result
```

The body may remain natural language. Types support routing, summarization, and
presentation without constraining reasoning style.

## Normalized lifecycle events

Provider adapters should emit one typed vocabulary:

```clojure
:work/delegated
:work/started
:work/message-sent
:work/followed-up
:work/activity
:work/waiting
:work/completed
:work/failed
:work/interrupted
:work/result-produced
```

A minimal envelope:

```clojure
{:work-event/id          event-id
 :work-event/type        :work/activity
 :work-event/task        task-id
 :work-event/root        root-task-id
 :work-event/parent      parent-task-id
 :work-event/room        room-id
 :work-event/thread-root thread-root-id
 :work-event/actor       actor-id
 :work-event/execution   execution-id
 :work-event/at          instant}
```

Activity facts add structured semantics where known:

```clojure
{:activity/verb       :test
 :activity/object     {:artifact/ref file-or-command-ref}
 :activity/status     :failed
 :activity/outcome    "1 failure in access-control tests"
 :activity/critical?  true}
```

Do not add an opaque EDN or JSON payload column to Datahike. Extend typed
attributes as durable concepts stabilize; put large output behind store refs.

The event log is authoritative. Live process handles, cancellation functions,
provider streams, and mailbox registrations are transient projections.

## Codex mapping

Codex Multi-Agent V2 is a recursive tree of agent threads with canonical paths.
Its operations map as follows:

| Codex | Dvergr |
|---|---|
| Root agent thread | Lead execution in a workroom |
| Agent path `/root/reviewer` | Task/delegation lineage, not global actor identity |
| `spawn_agent` | `delegate!` plus child execution/workroom |
| `fork_turns` | Explicit context policy and frontier |
| Shared working directory | Selected workspace policy |
| `send_message` | `message!` without wake |
| `followup_task` | `follow-up!` with wake |
| `wait_agent` | Internal `await!` |
| `interrupt_agent` | Interrupt the live execution, retain task history |
| Child final response | `:work/result-produced`, delivered to parent |
| Collaboration activity item | Normalized work/activity event |

Codex normally shares one filesystem among workers. Dvergr should prefer branched
workspaces for independent writers and retain shared-write as an explicit policy.

## OpenCode mapping

OpenCode's Task tool creates a child session with `parentID`, derives restricted
permissions, runs in the foreground or background, and can resume the same child
session using `task_id`.

| OpenCode | Dvergr |
|---|---|
| Child session | Child task execution or private workroom |
| `parentID` | Parent task/workroom edge |
| `subagent_type` | Agent role/specification |
| Foreground task | Parent awaits child result |
| Background task | Parent continues and receives completion event |
| `task_id` reuse | Follow-up/resumption on the existing task |
| Derived permission rules | Narrowed delegated capability policy |
| Synthetic task result | Result delivery to the parent execution |

Unlike OpenCode's default UI contract, child work must remain user-expandable in
Simmis when authorization permits. Parent synthesis is the default view, not the
only surviving record.

## Hierarchical semantic zoom

Buzz's “verb, object, outcome” activity cards solve immediate supervision. Simmis
also needs recursive temporal and topical summaries:

```text
sentence
  -> paragraph
    -> work episodes
      -> actions/messages
        -> raw arguments, output, diffs, and traces
```

A summary is a derived, versioned lens over evidence:

```clojure
{:summary/id              summary-id
 :summary/level           :sentence
 :summary/covers          #{event-id ...}
 :summary/source-frontier basis-or-tx
 :summary/content         "Three agents fixed token refresh..."
 :summary/critical        #{critical-event-id ...}
 :summary/generated-by    {:model model-id :prompt-version version}
 :summary/created-at      instant}
```

Requirements:

- Raw facts are never replaced or deleted by summarization.
- Expansion follows explicit `:summary/covers` links.
- A changed source marks its summary stale or creates a new summary version.
- Higher-level summaries retain claim/evidence links and may retrieve raw critical
  evidence rather than repeatedly summarizing summaries.
- Access checks apply to every covered source; a summary must not leak material the
  viewer cannot read.
- UI expansion state is per-user Spindel state, not shared room truth.
- UI summarization, model-context compaction, and durable memory consolidation are
  separate consumers even when they reuse summary artifacts.

Critical state propagates structurally through every collapsed level, independent
of generated prose:

- errors and failed tests;
- pending or denied permissions;
- destructive or external effects;
- unresolved conflicts and unreviewed changes;
- security findings;
- budget exhaustion;
- explicit user decisions or objections;
- material uncertainty and agent disagreement.

The UI may say “three agents implemented token refresh” while still displaying
“one unresolved test failure” without expansion.

## State placement and fork safety

Follow `doc/state-model.md`:

- Durable task, event, thread, artifact, and summary facts belong in the room's
  forkable Datahike/Geschichte/Yggdrasil state.
- Execution-context primitives and Spindel sync primitives coordinate internal
  work and follow context fork semantics.
- Ordinary atoms are limited to live handles, locks, caches, provider processes,
  streams, and other Tier 3 resources.
- No ordinary atom or process-global registry may determine a durable semantic
  result.

Do not use signals as agent mailboxes. Signals integrate observable external
state; mailboxes, deferreds, pub/sub, and semaphores coordinate agents.

## Implementation sequence

### Stage 0: schema, identity, and ownership consolidation

1. Use UUIDs by value for durable cross-store identities. Runtime room keywords,
   slugs, and actor handles remain aliases, not cross-store identity.
2. Reconcile the two existing `:task/*` models before adding delegated work.
   Authoritative project tasks live in forkable room state; a global human inbox
   is a derived delivery projection, not the semantic owner.
3. Persist Agent executions, Workflow runs, and Simulation runs as distinct
   concepts while sharing lifecycle, activity, and control implementation.
4. Treat immutable domain event entities as the portable causal trace. Datahike's
   transaction log remains storage history; do not introduce an unrelated opaque
   event-sourcing layer.
5. Specify idempotent outbox/receipt delivery for results crossing room stores;
   never imply an atomic transaction across parent and child Room databases.

### Stage 1: thread and attention contract

1. Add typed `:message/thread-root-id` persistence and round-trip tests.
2. Preserve immediate `:message/in-reply-to` separately.
3. Make same-thread steer versus different-thread queue behavior explicit.
4. Test passive observation, explicit wake, agent-to-agent targeting, and activity
   exclusion from model context.
5. Add a context frontier to execution diagnostics before changing context
   assembly substantially.

This is the immediate dependency for Simmis's room UI.

### Stage 2: lightweight execution correlation

1. Allocate an execution ID for each agent turn.
2. Correlate trigger, thread, tool activity, final response, failure, and cancel.
3. Key live control by execution ID while preserving room-level convenience APIs.
4. Do not introduce a universal workflow-run aggregate in this stage.

### Stage 3: durable delegated tasks

1. Define task identity, parent/root lineage, objective, assignee, status, context
   frontier, and workspace policy as typed attributes.
2. Implement normalized events and pure task projection.
3. Implement provider-neutral `delegate!`, `message!`, `follow-up!`, `interrupt!`,
   `await!`, and `snapshot` behavior using Spindel coordination primitives.
4. Add permission narrowing and concurrency limits.

### Stage 4: recursive workrooms

1. Promote a thread/task into a child Room.
2. Reuse existing context fork and Yggdrasil merge/discard paths.
3. Deliver child results to the parent without automatically publishing internal
   chatter.
4. Add explicit publish, propose, merge, and discard decisions.

### Stage 5: provider adapters

1. Map Codex collaboration operations and lifecycle into the normalized protocol.
2. Map OpenCode child sessions/background jobs into the same protocol.
3. Keep Claude Code and native Dvergr agents behaviorally equivalent.
4. Add deterministic provider-free contract tests before live subscription tests.

### Stage 6: semantic zoom

1. Normalize action semantics as actor/verb/object/status/outcome.
2. Group streaming fragments into stable actions.
3. Add episode, thread, daily, and project summary entities with evidence links.
4. Propagate critical state structurally.
5. Let Simmis render user-specific expansion over the shared facts.

## REPL acceptance scenarios

Keep the orchestration surface REPL-first and provider-independent. The initial
contract should be demonstrable with scripted agents:

1. Create a room and two threads.
2. Address one agent in each thread while its first execution is active.
3. Verify the same-thread follow-up steers and the other-thread request queues.
4. Delegate independent research to two scripted child agents and await both.
5. Send information to one child without waking it, then follow up and verify it
   resumes.
6. Fork a workroom, write a Datahike fact and file, discard it, and prove the
   parent is untouched.
7. Repeat and merge; prove the parent receives the accepted facts and artifacts.
8. Interrupt one child and verify durable task history remains while the live
   handle disappears.
9. Fold normalized events into the same projection before and after rehydrate.
10. Collapse the work into a summary and traverse its evidence links back to raw
    messages and activity.

Use `await` inside spins and dereference only at the REPL/test boundary. Never use
`:reload-all`; see `CLAUDE.md`.

## Decisions deliberately deferred

- Whether task/work/summary schemas live in `dvergr.chat.schema` or dedicated
  namespaces merged into `full-schema`.
- Whether a complex workroom is always durable or may be ephemeral until publish.
- Whether one persistent actor may run concurrent task contexts in the same room.
- The exact policy for integrating passive same-thread observations between model
  rounds.
- The first shared versus viewer-specific summary storage boundary.

Resolve these from executable scenarios and query requirements, not by forcing all
work into one abstraction upfront.
