# Certified conversational episodes

Status: implemented 2026-09-18. The design and its binding revisions were reviewed against the code, and the implementation was reviewed independently. Scope: run tau2-style benchmarks, and in
general any multi-turn environment with a simulated counterpart, *inside*
Dvergr's programming model, so every episode is recorded durably, certified,
and inspectable.

## Problem

The tau2 runner (`dvergr.benchmarks.tau2.runner`) is a host loop. The
conversation never crosses a Room bus, the world is a value in that loop,
harness candidates use a throw-away in-memory Room and chat context, and
results are EDN lines. None of it is a Run, Attempt, or Scorecard, so nothing
is in a room store, simmis cannot show it, and forking an episode at a turn
is not possible.

Dvergr already has the pieces:

- **Rooms.** A Room has a durable store with messages, Runs, attention,
  Attempts, and Scorecards. One `DatahikeStore` holds many Rooms.
- **LLM participants** (`discourse.llm/llm-agent`). They answer each inbound
  message with an `:agent-turn` Run that is durably admitted and finished,
  and they post correlated `:_activity` rows carrying tool names and inputs.
- **The trusted evaluation types.** These are `EnvironmentDef`,
  `Evaluator`, `WorldSetup`, `Attempt` (validated, content-addressed, stored
  through `PAttemptStore`), and `Scorecard` (`experiment/make-scorecard` from
  `{:experiment/job :attempt}` cells).

What is missing is a certification path whose root Run is a *conversation*.
`evaluation/evaluate` certifies exactly one hired `AgentDef` Run with one
task and one answer.

## Design

### Entities

```
experiment Room  (durable DatahikeStore, one per experiment directory)
 ├─ ExperimentDef / DatasetDef / EnvironmentDefs (content-addressed, portable)
 ├─ episode Run  :run/kind :episode, actor :environment, one per cell
 │    └─ certified Attempt (attempt/id = episode run id)  → Scorecard
 └─ episode Room (child, :parent-id experiment room, same store)
      ├─ participants: candidate (:agent) + environment counterpart (:customer)
      ├─ messages: the dialogue, as ordinary Room messages (thread = greeting)
      ├─ :agent-turn Runs of the candidate (one per customer message)
      ├─ :_activity rows: the candidate's tool uses (names + inputs)
      └─ environment effect rows: every tool effect with its exact result
```

### Namespaces

- **`dvergr.agent.conversation`** (new, generic, agent layer). It certifies
  conversational episodes:
  - `(episode-spin experiment-room store env-def evaluator candidate opts)`
    returns a Spin that yields a certified, persisted Attempt;
  - `ConversationEnv` is a host capability (never exposed to SCI) providing
    `:initial-world`, `:agent-tools`, `:counterpart`, `:respond` (the effect
    interpreter), `:limits`, and the terminal predicate.

  It reuses `environment/make-attempt-receipt`, `attempt/make-attempt`,
  `attempt/persist!`, and `experiment/make-scorecard` /
  `persist-scorecard!`. It adds no new durable types.
- **`dvergr.benchmarks.tau2.env`**. This is the tau2 `ConversationEnv`: the
  world, tools, customer, grader, and evaluator, built from the existing
  domain map (`t2/load-domain`).
- **`dvergr.benchmarks.tau2.inspect`**. Read-only queries over an experiment
  store. It lists Scorecards and Attempts, shows an episode transcript
  (messages + activities + effects), replays the world, diffs against gold,
  and computes pass^k.

### The world lives in the episode Room's execution context

The tau2 world value (retail db, or the banking
`{:db :agent-unlocked :user-given :allowlist}`) is stored in the episode
Room's Spindel context state at `[:dvergr.conversation/world]`. Tool
execution reads and swaps it with the episode Room's context bound. As a
result:

- it forks with the Room: `fork-room :isolation :ctx` is copy-on-write, so
  fork-at-turn needs no extra machinery (a later step, not in v1);
- it is never visible to SCI unless the environment chooses to expose it
  (the REPL action space binds tool *functions*, not the world).

The world is not written to datahike. It is a pure function of the
recorded effects (`replay-world` in `inspect`). The Attempt evidence
records the final world hash and the gold hash.

### Environment effect log (the authoritative tool record)

`:_activity` rows keep tool inputs but deliberately not raw results. So every
environment tool call, from the candidate or from the customer's own tools,
is also posted by the environment as a non-triggering row: from
`:environment`, to the activity id, typed `:environment/effect`, with
`{:requestor :tool :arguments :content :error :world-hash-after}`. These rows
are the source for DB replay, ACTION grading, step/error accounting, and
inspection. Posting through the Room means the store records them like
everything else.

### Participants

- **Candidate** (`:agent`). This is built from the candidate `AgentDef`,
  which is portable data in the experiment:
  - `:harness :dvergr` gives a production `llm-agent` participant. Its
    system prompt is the environment's agent prompt, and its tools are the
    environment tool map bound to this episode Room. The action space is
    `:tools` or `:repl` (`clojure_eval` plus SCI namespace `tau2`). This is
    exactly how Dvergr agents run in Rooms.
  - `:harness :reference` gives a thin participant implementing tau2's
    `LLMAgent`, with one model step per protocol step. It starts and
    finishes its own `:agent-turn` Run per inbound message, so the recording
    is uniform.
  - The candidate's `AgentDef` is `{:program {:kind :llm ...} :model-policy
    ... :metadata {:conversation/harness ... :conversation/action-space ...}}`.
    Its content hash is the Scorecard candidate identity.
- **Counterpart** (`:customer`). This is a trusted environment participant
  wrapping the user simulator. It sees the dialogue role-flipped, like
  upstream, and executes its own tools through the same effect interpreter.
  It replies in the greeting's thread, so every candidate turn is a
  same-thread follow-up. When it emits a stop token it resolves the episode.

### Episode lifecycle

1. **Admission.** `run/start!` records an `:episode` Run in the experiment
   Room (trigger: a durable "episode opened" message) with provenance
   `:run/agent-def-hash`, `:run/program-kind :llm`, and
   `:run/interpreter-version`.
2. **Setup.** The environment creates the episode Room (same store,
   `:parent-id` the experiment Room), installs the initial world (the
   `WorldSetup` equivalent), joins `:agent` and `:customer`, and posts the
   greeting from `:agent`. For tau2 that is the fixed
   "Hi! How can I help you today?", delivered through the candidate's
   history.
3. **Dialogue.** This runs over the bus. The customer DMs the agent; the
   agent's `:agent-turn` Run replies; the customer answers.
4. **Termination.** The environment ends the episode when:
   - the customer sends a stop token (`###STOP###`, `###TRANSFER###`,
     `###OUT-OF-SCOPE###`), or the agent does;
   - the step or error bounds are hit, counted from messages and effect rows
     with tau2's accounting;
   - the wall-clock `:timeout-ms` expires.

   Bounds and timeout cancel live candidate Runs (`run/cancel-room-runs!`),
   and the episode waits for them to quiesce.
5. **Observation.** This is read from durable facts only. The dialogue comes
   from room messages, tool traffic from effect rows, and the final world
   from the episode context.
6. **Verification.** The trusted grader runs `t2/grade` over the
   reconstructed trajectory, producing checks such as `{:db :action
   :nl-assertion :communicate}` and a reward.
7. **Certification.** The episode Run finishes (`:completed`, or
   `:failed`/`:cancelled` for infrastructure faults, which are not scored).
   Then `make-attempt-receipt` → `make-attempt` → `attempt/persist!` run in
   the experiment Room. Evidence is:
   - `{:result {:termination :reward-breakdown}}`;
   - `{:trace {:runs [...] :messages [...ids]}}`, covering the episode Run,
     agent-turn Runs and message ids;
   - `{:world {:final-hash :gold-hash}}`;
   - `{:grading {...}}`.
8. **Teardown.** Participants leave and the episode Room closes. Its store
   rows remain, which is the recording.

The experiment runs the cells, batched with bounded parallelism exactly like
`experiment/run`, then builds `make-scorecard` / `persist-scorecard!`.
Incomplete experiments leave their Attempts and no Scorecard, consistent
with the existing policy.

### Fidelity versus the host runner

The environment itself (tools, prompts, grading) is the verified
transcription and does not change. What changes is the candidate surface.
Room participants see messages decorated with author and time, and
`llm-agent` appends a "now" note to the system prompt. For tau2 that note
contradicts the frozen world clock (banking is fixed at 2025-11-14). So:

- `llm-agent` gains an opt-in `:system-suffix` override (nil disables the
  now-note). This is an additive option in `discourse/llm.clj`.
- The decoration of customer messages is kept for `:harness :dvergr`,
  because that is the production surface being measured. The reference
  harness builds its own messages and stays tau2-exact.
- All of this is recorded in the candidate `AgentDef` metadata and in the
  experiment metadata. Results are tau2 "custom" submissions.

### Recording and inspection

- **Store.** Each experiment writes to
  `.dvergr/benchmarks/<experiment>/store` (datahike on the filesystem, plus
  the blob CAS for large Attempt and Scorecard values). An EDN summary
  alongside it is optional and only for grep.
- **`inspect` API.**
  - `(scorecards store)` and `(attempts store {:environment-id ..})`;
  - `(episode store attempt-id)`, which returns messages, activities,
    effects, runs and grading;
  - `(replay-world store attempt-id)` and `(diff-against-gold ...)`;
  - `(pass-hat-k attempts k)`.
- **Other readers.** Simmis can open the same store and render the episode
  Rooms with its existing run views.

## Revisions after code review

The review verified the core shape against the code with a throwaway REPL.
An explicit `:episode` Run can own a certified Attempt, and nothing needs
`program.clj`. These corrections are binding:

1. **Evidence stays in the experiment Room.** The datahike store and the
   Attempt governance require every `:attempt/evidence-runs` and
   `:attempt/evidence-messages` entry to belong to the Attempt's own Room
   (`room/store/datahike.clj:284-300`, `attempt/governance.clj:103-113`). The
   trace therefore names only the episode Run and its "opened" message. The
   episode Room, the agent-turn Run ids, and counts go into evidence as
   *plain data* (`:episode {...}`), and `inspect` re-verifies them.
2. **No unfinished Runs.**
   - `llm-agent` drops a blank or `[SKIP]` reply in its self-filter and never
     finishes the Run, which then blocks `close-room!`. Candidates are built
     with `:room-safe? false`.
   - Teardown cancels and awaits any Run still active in the episode Room
     before closing it.
3. **Customer role.** The customer posts with `:metadata {:role :user}`.
   Without it, store hydration infers `:assistant` for keyword senders, and
   the first customer message reached the model as the assistant's own words.
4. **Turn ends and bounds.**
   - `llm-agent` has no model-step cap, and failed turns post no reply. The
     host watches `run/watch-runs!` `:run/finished` events for the episode
     Room.
   - The effect interpreter enforces tau2's step and error bounds and cancels
     candidate Runs when they are exceeded.
   - A wall-clock timeout backs this up.
   - The `watch-runs!` callback only delivers a promise, because it runs under
     the lifecycle lock.
5. **Process isolation.**
   - Benchmark experiments run with `paths/set-home!` pointed at the
     experiment directory and a reset system DB, or in their own JVM.
   - They use an experiment-local artifact store (EDN files by content hash)
     instead of the global blob CAS, so a running daemon's `.dvergr` is never
     touched.
6. **No new `llm-agent` option.**
   - The now-note is stripped by a `:run-turn-fn` wrapper that dissociates
     `:system-suffix`. The wrapper also counts model steps.
   - Auto-compaction is off (`:compaction {:auto? false}`), and the dollar
     budget is explicit.
   - The attention policy enqueues, so a stray message can never cancel an
     in-flight call.
7. **Effect rows.**
   - The payload is EDN in `:content`, with an explicit `:seq`. Store order is
     millisecond-granular and must not be relied on.
   - Metadata is only `{:kind :environment/effect :role :tool}`, because
     arbitrary message metadata keys are rejected by the store.
8. **Blocking work runs at the host boundary.** Episode orchestration,
   grading (the NL judge calls an LLM), certification, and `close-room!` run
   on host threads. Participants do blocking model calls through
   `generation/future-handle` and never block the Spindel drain.
9. **Faults still get Attempts.** An episode that ends in an infrastructure
   fault is certified with status `:failed` and reward 0. The failure class is
   recorded, and inspection and metrics filter it out, so a Scorecard can
   always be built from one Attempt per cell.
10. **Fork-at-turn needs more than the context fork.** It needs a `:frozen`
    fork (the default `:following` mode reads the parent's live values) and a
    store that follows the fork (unprovisioned Room forks get none). It
    remains a later step.
11. **Episode ids are unique per Attempt.** Working-context caches and
    hydration are keyed by Room id and slug.

## Implementation

| Namespace | Role |
| --- | --- |
| `dvergr.agent.conversation` | Generic pieces. It owns the experiment store (a file-backed Datahike database plus a file artifact store), home isolation, episode Run admission and finish, effect rows, and certification. |
| `dvergr.benchmarks.tau2.episode` | One episode. It keeps the world in the episode Room's context and runs the effect interpreter with its bounds. It builds the candidates (the production `llm-agent` with `:tools` or `:repl`, or the tau2 reference loop), the customer participant and a Run watcher, and it does host orchestration and fault certification. |
| `dvergr.benchmarks.tau2.experiment` | Experiments. It builds content-addressed Environment, Dataset and Experiment defs and runs cells on bounded host threads. Resume is keyed by the ExperimentDef content id. It only writes a Scorecard for a fault-free, complete experiment. |
| `dvergr.benchmarks.tau2.inspect` | Read-only views: summary and pass^k, Attempts, episode reconstruction, a printed transcript, `verify-world` (replay → certified hash), and `verify-episode` (the graded log against Room rows). |

**The implementation review** found these defects, all fixed:

- provider errors were scored as model failures;
- faults could leave no Attempt behind;
- a customer reply could be sent after a bound ended the episode;
- teardown failures were swallowed;
- the summary counted failures wrongly;
- the REPL candidate missed the greeting;
- experiment identity was missing from resume keys.

The review's hardening changes are also in:

- world and log advance together under the lock;
- the graded trajectory and prompt hashes are stored in evidence;
- per-role usage is recorded;
- step accounting counts one step per message and two per tool batch.

`benchmarks/test/dvergr/benchmarks/tau2_rooms_test.clj` covers:

- retail gold plus resume, with store-only reconstruction;
- banking dual-control gold;
- the production participant in both action spaces;
- a provider fault, which is certified `:failed` and produces no Scorecard;
- the step bound, which ends the episode with no further turn.

Remaining known gaps:

- **Unrecorded outputs.** REPL eval outputs as the model saw them are not
  stored (only the code in `:_activity` rows). The same holds for the
  customer model's raw text when it comes with tool calls.
- **Surface caveats.** The Claude Code provider injects the account email.
  The customer message decoration shows local wall-clock `HH:mm`.
- **Sandbox scope.** The REPL action space exposes the full agent sandbox,
  not only `tau2/*`.

## Non-goals (v1)

- Fork-at-turn and grouped rollouts. The design enables them, since the
  world and dialogue live in a forkable Room, but they come later.
- Resuming a partially run experiment. Attempts survive; the Scorecard
  requires completion, as today.
- Token-level logprobs.
- Changes to `program.clj` or `evaluation/evaluate`.

## Test plan

- **Provider-free.** A scripted model and scripted customer run one retail
  and one banking episode end to end in an in-memory store. The test asserts:
  - the Runs, Attempt and Scorecard are durable and valid;
  - effect rows are complete;
  - the grade equals the host runner's grade for the same scripted
    trajectory;
  - the store reopens and the episode is reconstructable.
- **Gold equivalence.** Gold agent and gold customer through the Room path
  score 1.0 on all retail and banking tasks (re-using the gold pairs).
- **Live.** The comparison experiment on the new path.
