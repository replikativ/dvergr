# Discourse — Multi-Agent Linguistic FRP for dvergr

> Status: **design** · Last revised 2026-05-22 · Owner: dvergr/spindel · Audience: implementers and reviewers

A unifying programming model for dvergr's agentic harnesses: continuous-time participants exchanging messages in forkable rooms, with the chatroom as the user-facing surface and Feynman–Kac inference as the math substrate. Designed to subsume `dvergr.workflows`, `dvergr.agent.process`, `dvergr.chat.feedback`, and simmis's bespoke turn loop with one substrate that works the same for the developer composing a harness and the agent composing sub-agents inside the SCI sandbox.

---

## 1. Premise

An agent is a particle in a Sequential Monte Carlo (SMC) process; a chatroom is the substrate it lives in; a message is an observation; a workflow is an inference pattern; a fork is "try another hypothesis". Every artifact the system produces — a knowledge-base note, a verified plan, an accepted code change, a discarded rollout — is a posterior that becomes the next prior. The leverage point is the substrate: making speculation cheap and commitment the only friction (see the London talk, *Programming as and for Inference*).

dvergr already has the pieces:

- **spindel** — forkable execution contexts, typed delta algebra, full inference suite (SMC, IS, PIMH, PGIBBS, PGAS, IPMCMC, BBVI) in `org.replikativ.spindel.inference.*`
- **yggdrasil** — fork/merge coordination across substrates (datahike, git, SCI, scriptum, proximum, stratum)
- **datahike** — Datalog with branch/merge; the persistent memory layer
- **raster** — typed numerics + AD; the inference backend when gradients matter

What's missing is a **single discourse-level model** that ties these together for the multi-agent linguistic case. This document defines it.

## 2. Non-goals (deliberately)

- We do **not** require SMC. Most workflows run once, normally, with no resampling. SMC is an opt-in mode reachable at three intensities (no fork, single local fork, population SMC). The user-facing surface is a chatroom, not a particle filter.
- We do **not** replace LLaMPPL [1]. LLaMPPL is *token-level* SMC inside a single LLM call; we are *utterance-level* and *room-level*. They compose: a participant *may* internally use LLaMPPL-style steering. Most participants won't.
- We do **not** ship a stepper. Participants are continuous-time spins driven by mailbox deltas; nothing externally calls `step()` in a loop.
- We do **not** decide ParticipantBelief or learned-proposal representations here. Those are open questions (§13).

## 3. The four-layer ladder

| Layer | Concern | Realised by | Status |
|-------|---------|-------------|--------|
| **L0 — Foundation** | reactive evaluation, forkable runtime, typed deltas | spindel core, yggdrasil, datahike | exists |
| **L1 — Token-level inference** *(optional, per-participant)* | constrained generation, structured decoding | spindel `sample`/`observe` or wrapped LLaMPPL | optional |
| **L2 — Discourse substrate** | participants, messages, rooms, the algebra | **this document — `dvergr.discourse`** | new |
| **L3 — Discourse-level inference** *(opt-in)* | population SMC over rooms, Feynman–Kac | `(smc-discourse …)` on top of L2 | new |

Each layer is testable independently. L2 is testable with no LLMs at all (§9). L3 is testable with no LLMs and no real LLM inference — just scripted participants with weights.

## 4. Substrate (L2): types

### 4.1 Message

```clojure
(defrecord Message
  [id            ; UUID
   from          ; participant-id (keyword or qualified id)
   to            ; participant-id | :all | #{participant-id …} (multicast)
   content       ; string | rich map (text + attachments + structured data)
   ts            ; java.time.Instant
   in-reply-to   ; message-id (optional)
   tool-call     ; {:name … :args … :result …} (optional)
   metadata])    ; arbitrary map (provenance, weights, etc.)
```

A message is the only thing that crosses participant boundaries. Everything else (beliefs, internal state, tool internals) is private to its owning participant. Messages are immutable values.

### 4.2 Participant

```clojure
(defrecord Participant
  [id             ; keyword
   spec           ; static descriptor (prior): provider, model, system-prompt, tools, …
   inbox          ; Signal[Vector[Message]] — addressed to me; typed-delta vector
   outbox         ; Signal[Vector[Message]] — emitted by me
   state          ; Signal[Keyword] — :idle | :thinking | :awaiting-tool | :done | :error | …
   belief         ; Signal[ParticipantBelief] — my model of topic, others, common ground (§8)
   process])      ; the spin driving this participant
```

A participant is a **continuous-time process**. Its `process` is a spindel spin that awaits inbox deltas, reacts to new messages, updates `state` and `belief`, and posts to `outbox`. There is no central scheduler. The FRP runtime drives reactions when signal deltas occur. A participant exists *between* messages just as it does *during* them; "step" is an internal concept of its spin, never an external operation.

### 4.3 Room

```clojure
(defrecord Room
  [id               ; keyword
   participants     ; Signal[Map[id → Participant]]
   log              ; Signal[Vector[Message]] — global ordering of every delivered message
   topic            ; Signal[String]
   common-ground    ; Signal[CommonGround] — Stalnakerian mutual-knowledge digest (§8)
   delivery         ; Delivery — how messages route (in-memory, pub-sub, distributed, …)
   fork-ctx])       ; spindel execution context (forkable: datahike + signals + git + SCI together)
```

A room is the **delivery medium** and the **forkable substrate** for a population of participants. When a message is posted, `delivery` routes it into addressed participants' inboxes and appends to `log`. The room owns the spindel execution context — forking the room forks every system registered with yggdrasil in one operation.

## 5. Substrate (L2): semantics

### 5.1 Continuous-time / mailbox-driven

A participant is "always on". Its spin starts when it joins the room and runs until it leaves. The body is shaped like:

```clojure
(spin
  (loop []
    (let [delta (await (inbox-delta this))]   ; suspends until next message(s)
      (try
        (reset! (:state this) :thinking)
        (let [reply (handle-messages this delta)]
          (when reply (post! room reply))
          (reset! (:state this) :idle))
        (catch :default e
          (reset! (:state this) :error)
          (post! room (error-message this e))))
      (recur))))
```

There is no outer driver calling `step`. Cascades happen organically: A posts → B's inbox-delta fires → B's spin wakes → B posts → C's inbox-delta fires → … . The room remains coherent because spindel's typed deltas + scheduling enforce glitch-free updates (deltas propagate in topological order; the log is the canonical witness).

### 5.2 Heterogeneous participants — one shape

Five constructors, one type. All behave identically from the room's perspective:

```clojure
(human         {:id :alice :name "Alice"})                  ; outbox driven by UI; inbox visible
(agent         {:id :coder :spec coder-spec})               ; LLM-driven; spin runs LLM-turn loop
(script        {:id :rev   :replies […]})                   ; deterministic replies (tests)
(echo          {:id :echo})                                 ; replies with received content (tests)
(tool-runner   {:id :runner :tool fn :listen-pattern p})    ; reacts to tool-call messages
```

…plus wrappers that decorate a participant:

```clojure
(with-latency  p {:base-ms 800 :jitter-ms 300})             ; realistic delay
(with-system   p prompt)                                    ; override/inject system prompt
(flaky         p {:rate-limit-prob 0.1 :timeout-prob 0.05}) ; chaos wrapper for tests
(with-budget   p {:max-dollars 0.50})                       ; budget guard (state → :budget-exhausted)
(with-belief   p {:initial-belief b})                       ; seed prior belief
```

Wrappers are **participant-to-participant**: they take a participant, return a participant with the same inbox/outbox protocol. Decorators compose.

### 5.3 Identity and isolation

Two participants in the same room may not share an `id`. Two rooms may host participants with identical `id`s — they are different *instances* (different spins, different signals). Forking a room duplicates the participant set; the forked participants share the *spec* (prior) but get **fresh inbox/outbox/state/belief signals** in the forked execution context. This is what makes the population SMC of L3 possible: we fork the room → we get N independent rooms → each one evolves under its own spin dynamics.

### 5.4 Coalgebraic view & bisimulation

A participant is, formally, a **coalgebra**: a state-transition system `c : S → (Event → S × Maybe[Message])`. Its state `S` evolves on each event (inbox / tick / goal / source / observation / introspection — §5.5); its output is a message it chooses to emit, or nothing. Spins are the operational realisation of this coalgebra in spindel.

This is the dual of spindel's existing **comonadic** structure for signals. Spindel formalises `track` as a comonad in `docs/engine-formalism.md §1.1`: `extract ∘ duplicate = id`, with track continuations persistent so re-running them respects the comonad laws. Comonads handle *"this value is derived from its observation history"*; coalgebras handle *"this state evolves in response to its event stream"*. Together — comonad for derivation in time, coalgebra for evolution in time — both grounded in the same forkable execution context.

**Bisimulation** is the equivalence we care about. Two participants `p` and `q` are bisimilar iff their observable outputs are indistinguishable across all event streams from a common starting state. In spindel today, observational equivalence is implemented via per-observer generation tracking (`effects/track.cljc:80`): two observers consuming the same signal at the same generation see identical deltas. The discourse layer lifts this from signals to behaviour: two participants are observationally equivalent if their outbox streams match for every inbox/tick/source pattern.

Three reasons this is load-bearing:

1. **Surrogate ↔ virtual interchangeability.** A surrogate-CFO persona (built from real CFO emails and positions) and a virtual-CFO persona (pure system prompt) are *substitutable in any context where they are bisimilar over the relevant event horizon*. Bisimulation is the precise notion of "faithful proxy".

2. **Test ↔ production substitution.** A `script` participant and a real `agent` participant are bisimilar on the scenarios their script covers. The test harness validates the algebra on scripts; production runs the real agents under the same algebra. The substitution is justified by bisimulation on the test scenarios.

3. **Fork-merge identity is a bisimulation.** The fork-identity law (§7) — "applying ops in a fork and merging equals applying ops in place when no isolation diverges" — is exactly the statement that a forked participant is bisimilar to the parent at fork time and diverges only via differential evidence. This is what makes speculative exploration *sound*.

**Bisimulation by features (the sharper form, for open-weights participants).** Behavioural bisimulation is the default we just defined; it is checkable by running scripts against both participants and comparing outbox streams. For open-weights participants, **Geiger 2023's *causal abstraction* + DAS (Distributed Alignment Search)** [19] gives a strictly sharper notion: two participants are bisimilar *by features* iff their internal SAE activations are mapped by an alignment to a common abstract circuit. With open SAEs available today (Gemma Scope, Goodfire), this is *checkable* for open-weights participants — not just a definition. Behavioural bisimulation remains the default for closed-weights or scripted participants; feature bisimulation is the upgrade when both parties expose their internals. The two combine: behavioural for the algebra-as-tested, feature-level for the strongest soundness claims.

### 5.5 Drivers: inbox is not the only signal

A participant's spin races over **six channels**, not just inbox:

| Driver | Source | Triggers |
|--------|--------|----------|
| Inbox | room delivery → my inbox-signal | message addressed to me |
| Tick / cadence | spindel timer signal | periodic reflection, scheduled reasoning |
| Goal | own goal-signal | progress demands action |
| Sources | subscribed intake feeds (HN, arxiv, calendar, RSS) | new external data |
| Observation | other participants' state/belief signals | relational change ("Alice changed mood", "moderator opened floor") |
| Introspection | own belief-signal crossed a threshold | "I now have enough evidence to say X" |

Same `Participant` record covers all. What differs is the spin body and the wrappers composed:

```clojure
(-> (agent {:id :alice :spec spec})
    (with-cadence     {:every 15 :unit :min :do :reflect})
    (with-sources     #{:hn :arxiv})
    (with-goal        (goal :answer-pending-questions))
    (with-observation #{:moderator})            ; watch moderator's state
    (with-introspection {:threshold 0.7}))      ; emit when belief crosses
```

The spin body, abstractly:

```clojure
(spin
  (loop []
    (let [event (race
                  [(inbox-delta this)
                   (tick (:cadence this))
                   (goal-progress this)
                   (sources-update this)
                   (observation this room)
                   (introspection this)])]
      (handle-event this room event)
      (recur))))
```

`dvergr.agent.process` already runs this shape (`race [inbox control tick sources active-task-completion]`); the discourse substrate makes it the default participant shape with named wrappers.

### 5.6 Personas: long-running participants

A persona is a participant with **persistent identity** — KB, beliefs, goals, history survive daemon restart and (under the cross-room extension, Open Q #3) can span rooms.

```clojure
(persona
  {:id        :archivist
   :spec      archivist-spec
   :kb        archivist-kb-id            ; persistent datahike (yggdrasil-aware)
   :goals     (signal [:catalogue :answer])
   :cadence   (every 15 :min :do :review-recent)
   :sources   #{:hn :arxiv :user-mentions}
   :belief    (load-belief :archivist)   ; restored on join
   :on-event  (fn [p room event] …)
   :persists? true})                     ; survives daemon restart
```

Concretely:
- **Identity persists.** Across reboots, the same `archivist` exists, loaded by `:id` from datahike on daemon start.
- **KB persists.** The persona's knowledge is a yggdrasil-managed datahike — when the room forks, the KB branches with it; when the fork merges, KB updates merge.
- **Belief persists.** Reload restores belief state from datahike.
- **Goals are mutable signals.** Belief-updates can revise goals; goals drive proactive emissions.
- **Background reasoner** (optional). A cadence-driven spin updates belief between user messages — agents that *think* between turns, not just during them.

### 5.7 Gating: the cheap-LLM dispatcher pattern

In a many-persona room, you don't want every persona's main LLM call to fire on every message. Solution: **first-class gates that wrap participants and consult before the main spin fires.**

```clojure
(defn with-gate
  "Decorate a participant. Before the spin reacts, the gate is consulted:
     :pass  → main spin fires
     :skip  → message logged; no LLM call
     :defer → wait for next message; consider as a batch."
  [participant gate]
  …)
```

Gate kinds — all interchangeable, all compose:

```clojure
(gate/rule         (fn [msg state] :pass-or-skip))             ; deterministic, free
(gate/keyword      #{"budget" "spend" "ROI"})                  ; trivial
(gate/classifier   {:model :haiku-mini :prompt "…"})           ; cheap LLM
(gate/relevance-to (kb-of participant) :threshold 0.6)         ; KB similarity
(gate/learned      (load-classifier :cfo-gate))                ; trained model
(gate/turn-taking  (moderator-rule moderator-id))              ; floor control
(gate/quorum       [gate-a gate-b gate-c] :require 2)          ; composition
(gate/all-of       [gate-a gate-b])                            ; conjunctive
(gate/any-of       [gate-a gate-b])                            ; disjunctive
```

Stacked:

```clojure
(-> (persona {:id :cfo :spec cfo-spec})
    (with-gate
      (gate/quorum
        [(gate/keyword #{"$" "budget" "ROI" "revenue"})
         (gate/classifier {:model :haiku-mini
                           :prompt "Should the CFO weigh in on this?"})]
        :require 1)))
```

Defaults are free: `(with-gate (gate/rule (constantly :pass)))` is the no-gate case. Cost grows monotonically with the number of stacked gates and the model behind each `gate/classifier`. Gates are themselves participant-shaped predicates; in a future iteration a gate could *become* a participant (a "gate persona" that the room consults). We don't take that step in v1, but the shape is open to it.

### 5.8 Sub-agent hiring

The pattern for agents-hiring-agents:

```clojure
(defn hire
  "Spawn a sub-participant in a forked room with bounded budget, capability
   scope, and a typed accept-fn for output integration. Returns Spin[SubResult]:
     :status   → :merged | :discarded | :over-budget | :failed
     :outputs  → typed sub outputs (per accept-fn)
     :log      → the imagined sub-conversation (always returned)"
  [parent room spec
   {:keys [budget    ; {:dollars … :turns … :wall-ms …}
           goal      ; what the sub is for
           tools     ; SCI namespaces lifted into the sub's sandbox
           sources   ; subscribed feeds
           cadence   ; if the sub is long-running
           accept-fn]}] ; (sub-output → parent-belief-delta) — typed merge
  …)
```

**Safety story (v1):**

1. **Capability ≈ SCI tool exposure.** The set of namespaces lifted into the sub's SCI sandbox *is* its capability set. Want the sub to use `clojure_eval`? Lift it. Want it gated? Don't lift it. This matches dvergr's existing `setup-agent-namespaces!` pattern (`dvergr.sandbox`).

2. **The sub runs in a forked room.** It cannot reach the parent's state by construction (different signals, different datahike branch, different SCI context). Anything it writes is in the fork.

3. **Outputs flow back through a typed accept-fn.** The parent decides what to merge. The accept-fn returns a structured belief-delta + KB-merge spec; raw state transfer is not the API.

4. **Budget exhaustion → discard.** No state corruption possible; parent learns "I tried, got nothing useful within budget."

**Future work (deferred, Open Q):** an explicit per-capability `ExecApprovalRequirement` (auto / required / never), inspired by Codex's exec-policy model — every potentially-dangerous operation has an explicit approval flag and policies inherit from parent to child. v1 covers the common case via tool-exposure granularity; v2 will need finer per-operation gates as use cases push the limits (e.g., let an agent read files freely but require approval for writes; let it run pure clojure freely but require approval for `proc/run`).

## 6. The algebra (L2)

The user composes participants and rooms through a small algebra. Every primitive is either FRP-pure (returns a value) or returns a spin (a continuous-time process that yields a value upon completion).

**Formal foundation.** The asymmetric primitives — `ask`, `fan-out`, `race`, `pipeline` — sit naturally in the **algebraic-effects** tradition for LLM composition: **Pangolin (Tan, Wei, Sen, Zaharia LMPL 2025) [15]** types LLM calls as first-class algebraic effects, and the **selection monad (Plotkin & Xie PLDI 2025) [16]** provides reward-based choice over non-deterministic outputs. These two together cover 4 of our 5 algebraic combinators directly. The fifth — `debate` — requires the **coalgebraic view** of §5.4 because it has peer-symmetric communication that algebraic-effects alone cannot express. This is why our §5.4 commitment is *load-bearing*, not decorative: algebra covers the asymmetric primitives, coalgebra covers the symmetric ones, both compose under one substrate.

### 6.1 Addressing

```clojure
(direct    :alice {:content "hi"})        ; addressed to alice's inbox
(broadcast        {:content "…"})         ; everyone in room
(multicast #{:a :b :c} {:content "…"})    ; specified set
(reply     msg     {:content "…"})        ; sets :in-reply-to
```

Returns a Message. Pure construction.

### 6.2 Membership and delivery

```clojure
(join   room p)              ; adds p; starts its spin in room's fork-ctx
(leave  room p-id)           ; stops spin; removes participant
(post!  room msg)            ; routes to addressed inbox(es) + appends to log
(post-batch! room [msg …])   ; one transaction; preserves order; one log append
```

`post!` is the only state-changing primitive. Everything else builds on it.

### 6.3 Combinators

Each returns a **spin** yielding a value when the combinator completes.

```clojure
(ask        p msg)                          ; → Spin[Message]  : send-and-await-reply
(fan-out    msg #{p1 p2 …})                 ; → Spin[Vector[Message]] : parallel ask
(race       msg #{p1 p2 …})                 ; → Spin[Message] : fastest reply wins
(quorum     msg #{p1 p2 …} n)               ; → Spin[Vector[Message]] : first n replies
(pipeline   [p1 p2 …] msg)                  ; → Spin[Message] : reply-threaded chain
(debate     [p1 p2 …] {:rounds n})          ; → Spin[Vector[Vector[Message]]] : round-robin
(moderate   moderator #{p1 p2 …})           ; → Spin[Vector[Message]] : moderator picks speaker
(align-on   room topic {…})                 ; → Spin[CommonGround] : Habermas Machine (§8)
```

…and the corresponding **fork** primitives for local branching:

```clojure
(fork-room  room)              ; → forked Room (O(1) CoW; siblings to original)
(merge-room fork)              ; → original Room with fork's changes merged via yggdrasil
(discard    fork)              ; releases the fork without merging
```

`fork-room` returns a Room whose every system (datahike, git, signals, SCI) is forked simultaneously through yggdrasil's `PForkable` dispatch. Participants in the fork are fresh instances of the same specs running in the forked context.

### 6.4 Derived patterns

Every pattern in the current `dvergr.workflows` collapses to a few lines on the algebra. For example, `iterative-refinement` (today ~25 lines) becomes:

```clojure
(defn iterative-refinement
  [producer critic msg {:keys [accept? max-iter]}]
  (spin
    (loop [i 0 m msg]
      (if (>= i max-iter) ::max-iterations
        (let [draft  (await (ask producer m))
              review (await (ask critic  draft))]
          (if (accept? review) draft (recur (inc i) (with-context draft review))))))))
```

Similarly, `research-implement-test`, `parallel-research`, `competitive-race`, `then`, and `and-parallel` are 3–8-line spins on the algebra. The benefits: one substrate, one set of laws, predictable composition.

### 6.5 Thought experiments & theory of mind

Spindel's forkable runtime + the discourse algebra make theory of mind **operational**, not merely modelled in a system prompt:

```clojure
(defn simulate-reply
  "Fork the room, deliver `hypothetical-msg` to `other-id`, await their reply
   in the fork, discard the fork. Returns Spin[Message] — the imagined reply.
   The original room is untouched. Cheap: O(1) fork + one LLM call."
  [room other-id hypothetical-msg]
  (spin
    (let [fork     (fork-room room)
          other'   (get-in @(:participants fork) [other-id])
          imagined (await (ask other' hypothetical-msg))]
      (discard fork)
      imagined)))

(defn imagine-conversation
  "Fork, run a workflow, capture the imagined log, discard. For multi-turn what-ifs."
  [room workflow]
  (spin
    (let [fork    (fork-room room)
          outcome (await (workflow fork))
          log     (current-log fork)]
      (discard fork)
      {:outcome outcome :imagined-log log})))

(defn what-if
  "Spawn k forks; run hypothetical workflow in each; rank by score-fn;
   commit to the best in the real room (or with :dry-run? return all results
   without committing)."
  [room k workflow score-fn & {:keys [dry-run?]}]
  …)
```

The RSA hook becomes concrete. A speaker considering utterance U:

```clojure
(spin
  (let [candidates ["U1" "U2" "U3"]
        imagined   (await (parallel
                            (mapv #(simulate-reply room :listener {:content %})
                                  candidates)))
        best-idx   (argmax (map (predicted-listener-utility-score) imagined))]
    (await (post! room {:to :listener :content (nth candidates best-idx)}))))
```

Small local SMC (Intensity 1 in §10.2) over three candidate utterances, picked by predicted listener response. **Theory of mind, implemented** — with one LLM call per candidate. Composes with everything else: an iterative-refinement step can include simulate-reply for tone calibration; a debate participant can imagine the panel before speaking; a moderator can run three forked openings before picking one.

**The resumable-runtime differentiator.** Because spindel's execution context is a value, we can fork, run forward, observe the outcome, *jump back to a different track checkpoint*, and run forward from there. Codex, Claude Code, and OpenCode (§15) cannot do this — their forks are one-shot snapshots; the only "back" is starting over. Our forks are *resumable* — `simulate-reply` is the simplest case; `what-if` is the multi-fork version; `smc-discourse` (§10.3) is the population version.

## 7. Laws (L2)

These are testable invariants the implementation must satisfy. Property-based tests (§9) enforce them with `script`/`with-latency`/`flaky` participants — no LLMs needed.

**The formal home for these laws is Plotkin & Xie's effect-handler equational theory** [16] — the standard algebraic account of how effect handlers compose and what equations they preserve. Our combinators inherit those equations directly (`pipeline` associativity, `fan-out` singleton, etc.); we add the coalgebraic laws (fork-identity, idle quiescence) on top.

| Law | Statement |
|-----|-----------|
| Pipeline associativity | `(pipeline [a (pipeline [b c])] m) ≡ (pipeline [a b c] m)` |
| Fan-out singleton | `(fan-out m #{p}) ≡ (spin [(await (ask p m))])` |
| Race singleton | `(race m #{p}) ≡ (ask p m)` |
| Quorum bounds | `(quorum m S n)` returns a vector of length n; ordered by completion |
| Post batch monoid | `(post! r m1) (post! r m2) ≡ (post-batch! r [m1 m2])` modulo intervening reactions |
| Fork identity | `(merge-room (apply ops (fork-room r))) ≡ (apply ops r)` *when no isolation diverges* |
| Wrapper transparency | `(post! r (msg-from (with-latency p …) m)) ≡ (post! r (msg-from p m))` modulo delivery time |
| Idle quiescence | A room with no pending inbox deltas eventually reaches `state = :idle` for all participants (assuming bounded spins) |

The fork-identity law is the deep one: it says a fork is *behaviorally* a snapshot. When operations applied in a fork are also applicable to the parent (no merge conflicts, no diverging external effects), the post-merge state equals the in-place state. This is the algebraic foundation of "fork = try another hypothesis".

## 8. Belief and common ground

### 8.1 Belief signal (participant-level)

Each participant carries a `belief` signal — a model of (a) the topic, (b) what other participants believe, (c) the common ground (§8.2). The shape of `ParticipantBelief` is **deliberately open** (§13). Concrete implementations may use:

- a plain map of {participant-id → estimated-stance}
- a categorical posterior over discrete dialogue states
- a structured prompt rendered into the participant's system prompt before each turn
- a tensor produced by raster, for learned beliefs

The substrate only requires that `belief` is a signal updated on each new inbox message. The spin's body may read it when composing utterances. This is the RSA hook [4]: a speaker chooses an utterance by predicting how its listener will update *their* belief; the speaker's `belief` includes the listener's belief model.

**Three coupled substrates for `belief`.** Belief lives at three substrates simultaneously, each useful for different things:

| Substrate | What it holds | Inspected by | Implementation |
|-----------|---------------|--------------|----------------|
| **Symbolic** | Discrete maps / propositions / dialogue states | Read with `@belief`; printable; queryable in datahike | The default; what scripted and closed-weights participants expose |
| **Population-coded** | Residual-stream embeddings | Compared by cosine similarity; clustered for "common stance" | Captured from LLM forward passes when the participant is LLM-driven |
| **Feature-coded** | SAE feature activations [17][18] | Inspected feature-by-feature; mapped by DAS [19] for bisimulation-by-features (§5.4); manipulable via `with-steering` | Available today for open-weights participants via Gemma Scope / Goodfire |

The symbolic substrate is always present; the latter two come "for free" when a participant is an open-weights LLM whose activations we can read. A participant's `with-steering` wrapper (sibling of `with-system`) applies activation-level deltas (RepE [39], CAA [40], single-direction interventions [41]) to nudge feature-coded belief at inference time — cheap, deterministic, composable. Mechanistic interpretability research (the SAE lineage [17][18], DAS [19], steering [39][40][41]) supplies the tools; we just consume them through the substrate.

### 8.2 Common-ground signal (room-level)

`(:common-ground room)` is a derived signal. Its concrete contents are open (§13), but conceptually it captures *what is mutually known* — Stalnaker's common ground. A first realisation:

```clojure
{:facts        #{…}              ; propositions held with high confidence by all participants
 :open         #{…}              ; propositions raised but not (yet) acknowledged
 :disagreed    #{…}              ; propositions where participants are visibly split
 :ack-by       {:fact → #{ids}}  ; per-fact, who has acknowledged}
```

Updates incrementally per new message. The reactive substrate gives us this cheaply: each message contributes a delta to the common ground; spindel's typed delta algebra means only the changed portion flows.

### 8.3 `align-on` — Habermas Machine pattern

Tessler et al. 2024 [5] showed an AI mediator can iteratively refine a "common ground statement" that participants converge on. In our model:

```clojure
(align-on room topic
  {:participants #{:alice :bob :carol}
   :mediator     :judge
   :accept?      (fn [cg] (>= (count (:facts cg)) 5))
   :max-rounds   10})
;; → Spin[CommonGround]
```

Internally: the mediator agent issues a draft statement, broadcasts it, collects each participant's critique/acceptance (via `fan-out`), updates the draft, repeats until `accept?` fires on `common-ground` or `max-rounds` exhausts.

This is one example of a discourse-level combinator with explicit pragmatic semantics. Others can be added similarly (e.g., `socratic`, `delphi`, `consensus-by-quorum`).

## 9. Testing strategy

The discourse substrate is testable **without a single API key**.

### 9.1 The dummy-LLM harness

```clojure
(def coder    (-> (script :coder ["impl v1" "impl v2" "impl v3"])
                  (with-latency {:base-ms 600 :jitter-ms 200})
                  (flaky {:rate-limit-prob 0.1})))
(def reviewer (script :reviewer ["needs work" "needs work" "lgtm"]))
(def r        (room {:id :scenario}))
(-> r (join coder) (join reviewer))
(iterative-refinement coder reviewer "build login form"
  {:accept? #(re-find #"lgtm" (:content %)) :max-iter 5})
```

The scenario runs deterministically. Vary `seed` for stochastic coverage. Multiply latencies by 10 to find timeout bugs. Inject failures via `flaky`. The full agentic harness is exercised end-to-end on a CI runner with no network calls.

### 9.2 Property-based tests of the laws

Each law in §7 becomes a `test.check` property over generated participant sets, scripts, and combinator nestings:

```clojure
(defspec pipeline-associativity 100
  (prop/for-all [ps (gen/such-that #(<= 3 (count %) 6) (gen/vector script-gen))
                 m  msg-gen]
    (= (run (pipeline ps m))
       (run (pipeline [(first ps) (pipeline (rest ps))] m)))))
```

### 9.3 Reference scenarios

A canonical test suite covering: one-on-one chat, pipeline of three, fan-out + race with latency, quorum with one slow participant, debate with budget exhaustion, error in one participant, fork-and-discard, fork-and-merge, fork divergence under merge conflict, and an `align-on` reaching common ground over scripted critiques.

### 9.4 LLM scenarios as a separate suite

A second suite (run on demand, not on every CI build) exercises real LLM providers. The seam: replace `script` participants with `agent` participants; everything else is unchanged.

### 9.5 Canonical scenarios

Three scenarios designed to exercise the full substrate end-to-end. Each is implementable with `script` participants for CI and with real `agent` participants for staging.

**Scenario A — Board meeting modelling.** simmis's headline use case.

```
Room :board-q3-acquisition
Participants:
  :ceo     (surrogate)  KB = strategic memos + past meetings
  :cfo     (surrogate)  KB = financial models + past statements
  :cto     (virtual)    KB = tech-due-diligence patterns
  :counsel (virtual)    KB = corporate-law + deal precedents
  :user    (human)      observes, occasionally redirects

User: "Discuss Q3 acquisition of Acme — should we?"

Cascade:
  1. Broadcast → all inboxes
  2. Each persona's gate fires (cheap classifier per §5.7): CFO/CEO/CTO/Counsel pass
  3. CFO emits first — "EBITDA multiple too high"
  4. CTO observes via observation-driver (§5.5) — "tech-debt would slow integration ~6mo"
  5. Counsel notices CFO-vs-CEO conflict via observation-driver
  6. Counsel runs simulate-reply (§6.5) on CFO ("what if I propose escrow X?"), then
     emits the proposal that maximises predicted CFO buy-in
  7. CEO replies; cascade continues
  8. User can fork the room at any point to explore "what if we tried Y?"
```

Tests exercised: gating, observation-driver, simulate-reply, user-fork, multi-persona orchestration without explicit moderator. KBs scoped per persona for token economy.

**Scenario B — Code review with specialist personas.**

```
Room :pr-review-1729
Participants:
  :architect     KB = ADRs + architecture diagrams
  :security      KB = OWASP + threat models + CVE patterns
  :tester        KB = test patterns + coverage history
  :user-advocate KB = user feedback + accessibility guidelines
  :integrator    (the moderator-persona that consolidates)

User posts a PR diff (broadcast)

Cascade:
  1. fan-out: specialists review in parallel (each gate fires on diff-relevance)
  2. align-on: integrator consolidates findings into a single review comment
  3. simulate-reply on the PR author: "if I post this, how will they react?"
     → adjust tone
  4. emit the final review to the PR thread
```

Tests exercised: `fan-out` + `align-on` + `simulate-reply` for tone calibration. Closer to what Claude Code does for code review, but specialists have *separate KBs* (token economy) and the algebra is explicit (fan-out / align-on are first-class, not implicit in a long prompt).

**Scenario C — Open-ended research.** The headline use case where multi-participant beats single-agent assistants.

```
Persona :lead-researcher
  goal-signal: "survey SMC + LLM literature 2023-25"
  cadence:    tick every 30min → reflect on progress

On goal-signal-rising:
  hire :literature-scanner
    {:budget    {:dollars 0.30 :turns 8}
     :sources   #{:arxiv :hn}
     :tools     #{:arxiv :web_fetch}
     :accept-fn (fn [out] {:kb-merges (paper-summaries out)
                           :belief-delta (coverage-update out)})}

  hire :paper-deep-reader
    {:budget    {:dollars 0.50 :turns 12}
     :tools     #{:web_fetch :clojure_eval}
     :sources   #{:arxiv}
     :can-hire? true}                ; may spawn sub-sub for cited papers

  hire :synthesis-writer
    {:budget    {:dollars 0.40 :turns 6}
     :tools     #{:clojure_eval :kb-write}
     :accept-fn (fn [out] {:kb-merges {:synthesis out}})}

Each sub runs in a fork (yggdrasil: own datahike branch, own SCI, own KB).
On completion → merge via accept-fn. On budget exhaust → discard.
Parent's belief updates with synthesised findings; emits a digest message
into the room. User reviews; may fork to explore a contrarian angle.

The lead-researcher's spin races over:
  - inbox        (user redirects)
  - tick         (periodic reflection)
  - sub-completion (subs finished; integrate)
  - goal-progress (if behind schedule, reallocate)
```

Tests exercised: continuous-time persona (§5.6), multi-driver spin (§5.5), sub-agent hiring with budget (§5.8), accept-fn typed merging, fork isolation, the resumable-runtime differentiator (a parent can roll a sub-fork back and retry with different prompt). This is where the multi-participant + forkable-substrate design beats single-context coding assistants for multi-hour open-ended work — multiple subs with separate KBs and budgets, divergent exploration via forks, all under explicit cost and capability control.

## 10. Inference layer (L3)

### 10.1 The Feynman–Kac reading

A Feynman–Kac process [10] is a sequence of measures Mₜ defined by a propagation kernel and a potential function. Mapped onto our substrate:

| FK concept | Discourse realisation |
|------------|----------------------|
| State Xₜ | Room state at time t (log + participants' beliefs + common ground) |
| Propagation kernel | Participants' spins firing asynchronously in response to inbox deltas |
| Potential Gₜ | A score signal over the room (alignment, progress, common-ground coverage, custom reward) |
| Barriers | Chosen — turn boundary, quorum, moderator-declared round end, explicit `resample-at` |
| Particle | A single (forked) room — its full continuous-time trajectory |
| Population | N forked rooms, indexed |

The framing carries over from discrete-time FK because resampling is bound to chosen barriers, not a uniform clock. Between barriers, particles evolve freely under their own continuous-time spin dynamics. This generalisation is the *point* of running SMC on FRP [9] [11].

**Where this sits in the existing FK-on-LLMs literature.** Token-level FK on LLMs is established (LLaMPPL [1]). The closest existing work at higher granularity is **DisCIPL** (Grand, Lew et al. 2025) [21] — self-steering inference where an LLM generates a probabilistic program and SMC steers it — and **MSA** (Wong et al. 2025) [22] — ephemeral world models synthesised on demand. Both still operate at the single-mind level (one LLM, one inference). Our position: lift FK to *room granularity*, with the population being forked rooms rather than forked token sequences. A discourse particle is a *conversational trajectory* across many participants, not a token sequence inside one LLM. The substrate is the same (spindel's fork + SMC kernels); the granularity is one level up.

### 10.2 Three intensities of inference

| Intensity | Mechanism | Cost | When to use |
|-----------|-----------|------|-------------|
| 0 — no inference | Plain composition; one trajectory | 1× | The default; most workflows |
| 1 — local exploration | `fork-room` → try → `merge-room` or `discard` | ~2× | "What if we tried this?" — accept/reject a single speculative move |
| 2 — population SMC | `smc-discourse` with N particles | ~N× | Hard exploration; needs population to find a good trajectory |

The user picks. Most code stays at intensity 0.

### 10.3 `smc-discourse`

```clojure
(smc-discourse room
  {:particles    16
   :potential    score-signal            ; Room → Number (or Signal[Number])
   :resample-at  potential-signal        ; barrier — when to resample
   :kernel       :smc-standard           ; or :smc-steer (no-replacement K-beam)
   :resampler    :systematic             ; or :multinomial :stratified :residual
   :ess-thresh   0.5                     ; resample when ESS/N < this
   :max-time-ms  60000})
;; → Spin[EmpiricalMeasure[Room]]
```

Implementation: `(repeatedly N #(fork-room room))` creates the particle population. Each particle runs its own spin dynamics. At each resampling barrier, the potential is evaluated across particles, weights normalised, ESS computed; if below threshold, resample by the chosen rule (steal LLaMPPL's optimal-without-replacement for `smc-steer`). Resampling concretely means: yggdrasil-merge winners into a shared lineage and yggdrasil-discard losers, then re-fork to repopulate.

The output is an `EmpiricalMeasure[Room]` — exactly spindel's existing inference output type. You can call `query` to get statistics or `predict` to sample from the population.

### 10.4 Engineering tricks adopted from LLaMPPL

1. **Async batching with timeout/batch-size.** When N participants' spins concurrently `await` LLM calls, coalesce HTTP requests with a small window (~20ms) so providers see one batched request per provider rather than N. We already do this partially in dvergr; LLaMPPL's pattern shows the clean shape.
2. **Optimal-without-replacement resampling** (`smc-steer`). Deterministic copies of high-weight particles plus stratified sampling of the rest, with weight rescaling to stay unbiased. Worth porting.
3. **ESS-threshold gating**. Don't resample every turn — only when effective sample size drops below θ × N.

### 10.5 Training utility (informative; not part of this MVP)

Every discourse trajectory plus its potential is a *weighted sample* from the model. Logging `(trajectory, weight)` pairs gives a training corpus with proper importance weights. The same machinery supports REINFORCE-style policy-gradient training of participant policies (spindel already has BBVI). World-model fitting follows by designating one participant as "environment" and others as "agents" and running FK over the joint state. This is what closes the *posteriors become priors* loop.

## 11. Implementation plan (L2 → L3)

Six steps, each REPL-validated before the next. Each step is small enough that we can stop after it and ship.

### Step 1 — Skeleton (data + signals, no combinators)
- New namespace `dvergr.discourse` with records (Message, Participant, Room)
- Signal wiring: inbox/outbox/state/belief/log/common-ground/topic
- `join` / `leave` / `post!` / `post-batch!`
- `script` and `echo` participants
- REPL validation: 2 scripted participants in a room exchange 3 messages; verify signal deltas tick; verify log ordering; verify spin lifecycle.

### Step 2 — Core combinators
- `ask`, `fan-out`, `race`, `quorum`, `pipeline`
- `with-latency`, `flaky`, `with-system`, `with-belief`, `with-budget`
- REPL validation: each combinator on scripted participants with latency; verify ordering, completion, and timing.

### Step 3 — Forking
- `fork-room` / `merge-room` / `discard`
- Ensure SCI context is forked alongside (add `PForkable` to `create-spindel-sci-ctx` if not already)
- REPL validation: fork a scenario mid-conversation, run divergent moves, merge or discard; verify parent unaffected when discarded.

### Step 4 — Higher patterns
- `debate`, `moderate`, `iterative-refinement` (a 5-line port)
- `align-on` with a basic common-ground update rule
- REPL validation: 3-way debate; moderator-picked turn-taking; alignment over scripted critiques.

### Step 5 — Inference (L3)
- `smc-discourse` with multinomial resampling, `:smc-standard` kernel
- Port LLaMPPL's optimal-without-replacement for `:smc-steer`
- Potential signals; ESS-threshold gating; async batching wrapper
- REPL validation: scripted "guess the number" game over a population; verify particles converge.

### Step 6 — Migration
- Rewrite `dvergr.workflows` patterns on top of `dvergr.discourse`
- Convert `dvergr.agent.process` into a participant constructor (`agent`) — the long-lived loop disappears in favour of the participant spin
- Collapse `dvergr.chat.feedback` into a `moderate` pattern
- Migrate simmis `room_agents/dispatch-to-agents!` to use `dvergr.discourse/iterative-refinement` (or `wf/chat-loop` if a no-critic variant is needed)
- Move generic KB-attachment mechanism into dvergr (matches earlier project direction)
- Promote `dvergr.discourse` to public API; update README and SCI lift

After Step 6 the codebase has one substrate, one set of laws, one programming model.

### Step 7 (post-MVP) — Concordia / Melting Pot benchmark

The cleanest empirical claim our differentiator makes is *forkable runtime + `simulate-reply` improves ad-hoc teamwork*. The cooperative-AI community has thousands of vetted multi-agent scenarios in **Concordia** and **Melting Pot** [34]. Concrete experiment:

1. Pick one Concordia scenario (e.g., a coordination/negotiation game)
2. Implement the same participants in `dvergr.discourse` with **only the baseline algebra** (`ask`/`pipeline`/`debate`) — no theory of mind
3. Re-run with `simulate-reply` (§6.5) added as the only delta — participants may now run hypothetical replies in forked rooms before committing utterances
4. Measure: outcome quality, AHT coverage-set membership, token cost

This is a *falsifiable* claim, not a marketing one. If `simulate-reply` doesn't help AHT, we know to revisit the theory-of-mind framing. If it does, we have a publishable result and a concrete differentiator. Deferred to after the substrate is stable (post-Step 6).

## 12. Reflectivity — same algebra inside SCI

The substrate is **reflective**: anything a developer composes in Clojure, an agent inside the SCI sandbox can compose with identical syntax. We add the lift in `dvergr.sandbox/setup-agent-namespaces!`:

```clojure
;; New SCI namespaces exposed to agents:
;;   dvergr.discourse  →  participant, agent, script, with-latency, …
;;                        ask, fan-out, race, quorum, pipeline, debate, …
;;                        fork-room, merge-room, discard
;;                        smc-discourse (L3, available but rarely needed)
```

An agent inside the sandbox can now write the *same* `(iterative-refinement coder reviewer …)` a developer writes. It can also `(fork-room room)` to speculatively try sub-conversations, `(ask sub-agent …)` to delegate, `(debate panel …)` to convene a council. The agent's programming model is the developer's programming model.

## 13. Open questions (deliberately deferred)

These do not block the MVP. They will be settled by experience with real workflows.

1. **`ParticipantBelief` representation.** Map / categorical / structured prompt / learned tensor? Most likely we ship a default (map-of-stances) and an extension protocol so callers can plug in richer beliefs (including learned ones from raster).
2. **Learned proposals.** LLaMPPL supports custom proposal distributions per `sample`. For discourse, the analog is "what should this participant try next?" given the current room state. A future extension.
3. **Cross-room participants** ("mobile particles"). A participant in multiple rooms simultaneously, with shared state. Useful for a "user" who is in many chats. Deferred; current model is participant-per-room.
4. **Distributed delivery semantics.** The in-memory delivery rule is the MVP; pub-sub / kabel-distributed delivery comes later (the existing `dvergr.rooms.bus` is a candidate backend).
5. **Persistence of discourse trajectories.** Logs should be queryable in datahike (rooms already are); the participant inbox/outbox/belief history is more nuanced. We may serialise to datahike at coarse barriers (turn ends, room close) initially.
6. **Schema migration.** simmis's `kb/*` SCI namespace overlaps with what discourse will provide for knowledge attachment. Migration path TBD; likely "KB attached as a special participant" — a knowledge-base is a participant whose inbox accepts `:store` / `:query` messages.

7. **Bridging via MCP.** The `dvergr.mcp` namespace exposes dvergr capabilities to other systems. Once `dvergr.discourse` lands, the question is *which* combinators are exposed as MCP tools (likely `ask` / `simulate-reply` / `hire` / `smc-discourse` as the public surface; algebra-level primitives like `post!`/`join`/`leave` stay runtime-internal). Pending v1 experience before deciding.

8. **Finer-grained capability/approval model.** v1 capabilities are SCI tool exposure (§5.8). v2 should add per-operation `ExecApprovalRequirement` (auto / required / never) inspired by Codex's exec-policy, with policy inheritance from parent to child. This becomes necessary when (a) the same tool is acceptable in some contexts and not others (e.g., `fs/write` to fork-only paths vs system paths), or (b) users want UI-driven approval-on-demand rather than static lift/no-lift.

9. **`Pact` watch-item** (Basis Research, April 2026) — a multi-agent coordination language announced after this doc's drafting. Potentially adjacent or competitive with `dvergr.discourse`. Worth examining the design and deciding whether (a) we adopt their primitives where they're cleaner, (b) we explicitly differentiate, or (c) we collaborate. The substrate-and-worldview alignment with Basis is otherwise the strongest of any lab surveyed (their `effectful` library is in the spindel algebraic-effects family).

10. **DR-MDP audit of `align-on`** (Carroll 2024 [33], Lang 2024). The Habermas-Machine pattern is vulnerable to participants nudging others' beliefs through framing/ordering, biasing the converged common-ground. A correctness test: simulate the same `align-on` multiple times with permuted speaking order / dropped participants and verify stability of the converged result. Deception detection becomes a property test, not an afterthought. Tracks AI-safety work on preference dynamics (DR-MDP).

## 14. Relationship to existing namespaces

| Existing | After migration | Notes |
|----------|-----------------|-------|
| `dvergr.workflows` | becomes a thin combinator file on top of `dvergr.discourse` (or merges into it) | 4 patterns → 3-line spins on the algebra |
| `dvergr.agent.process` | one of the constructors of `dvergr.discourse` (`agent`) | the long-lived loop becomes a participant spin |
| `dvergr.agent.task` | a particle-level wrapper: fork + agent + merge/discard, on top of `discourse` | the existing yggdrasil fork stays — discourse uses it |
| `dvergr.agent.config` → `AgentSpec` | becomes the `:spec` field of a `Participant` (the prior) | rename clarifies the inference reading |
| `dvergr.chat.context` | becomes the per-participant `belief` + the room `common-ground` | signals replace the manual scaffolding |
| `dvergr.chat.feedback` | a `moderate` pattern (3–5 lines) on the algebra | massive collapse |
| `dvergr.rooms` | persistence backend for `Room.log`; unchanged | discourse is the *live* view; rooms is the *persistent* view |
| `dvergr.rooms.bus` | one possible `delivery` for distributed rooms | optional plug-in |
| `dvergr.sandbox` | adds the SCI lift for `dvergr.discourse` (§12) | reflectivity |
| simmis `room_agents/dispatch-to-agents!` | replaced by `(iterative-refinement … )` or `(chat-loop …)` | validates the model from the consumer side |
| Basis Research `effectful` (Python) | *substrate sibling, external* — same algebraic-effects family as spindel | not a dependency; worth studying for idiom transfer; see Open Q #9 (`Pact`) |

## 15. Comparison to SoTA coding assistants

Our peer set as of mid-2026. Two clusters: (a) coding assistants (single-agent-rooted with delegation), (b) multi-agent LLM frameworks (population-of-agents-from-the-start). We are closest in spirit to the second cluster, while having to compete with the first on solo-developer UX.

**Coding assistants:**

| System | Architecture | Sub-agents | Sandbox | Forking | Fork resumability |
|--------|--------------|------------|---------|---------|-------------------|
| **Codex** (OpenAI) | Rust core; single-agent root with delegation | Yes — thread-fork with policy inheritance | OS-level (Seatbelt / Linux / Windows) | Snapshot only | No — fresh context per sub |
| **Claude Code** | Single agent with long context + tools | Effectively no (deep prompting only) | Sandbox via tool list | No | No |
| **OpenCode** (sst) | TS effect-based tool dispatch | Permission-mode subagent | No OS sandbox; permission gates only | No | No |

**Multi-agent LLM frameworks** (4 "AutoGens" is now real — v0.2 is the original paper-era; **v0.4** is the actor-model + event-driven rewrite (Jan 2025); **AG2** is the community fork by the original authors after their Sep 2024 Microsoft departure; **MS Agent Framework / Semantic Kernel** is the merger announced Oct 2025, RC1 Feb 2026):

| System | Communication | State / fork | Sandbox | Theory of mind | Notes |
|--------|---------------|--------------|---------|----------------|-------|
| **AutoGen v0.4** | Actor model; direct + topic pub/sub; distributed runtime; OTel | Per-actor state; no fork | None native | No first-class | **Closest mainstream comparison.** Turn-based actors, not delta-reactive spins |
| **MetaGPT** | Typed-artefact pub/sub pool | Shared artefacts; no fork | None | No | Second-closest at message routing |
| **CAMEL** | Role-play dyads | Per-role state; no fork | None | No | Strong as a *pattern library*; weak as substrate |
| **LangGraph** | Stateful graph nodes | **Checkpointers + time-travel** (graph state only) | None | No | Closest at the state/replay layer; forks state, not runtime |
| **CrewAI Flows** | Sequential + parallel tasks | Pluggable state; no fork | None | No | Strong ergonomics; weak isolation |
| **OpenAI Swarm** | Function-handoff | Stateless | None | No | Experimental; minimal |
| **Magentic-One** | Orchestrator + team Ledger | Dual ledger (task plan + facts) | None | No | Closest at *orchestration-with-team-model* |

**And us:**

| System | Communication | Fork | Sandbox | Theory of mind | Bisimulation |
|--------|---------------|------|---------|----------------|--------------|
| **dvergr.discourse** | Continuous-time, mailbox-delta-driven, typed-message envelopes; six-driver participant spins (§5.5) | **Forkable entire runtime** — datahike + SCI + signals + git via spindel/yggdrasil; *resumable* from any track checkpoint | SCI sandbox + yggdrasil fork = belt-and-braces capability | First-class via `simulate-reply` (§6.5); ToM as fork-and-run, not just prompt | **Both behavioural and feature-level** (§5.4): DAS-based feature bisim available for open-weights participants today |

What we adopt from Codex: the `ExecApprovalRequirement` model for finer-grained capability gating (Open Q #8). Codex's policy inheritance from parent to child is the right shape.

What we adopt from AutoGen v0.4 / MetaGPT / LangGraph: **OTel tracing**, **MCP tool interop** (we already have `dvergr.mcp`), **A2A protocol** for inter-room interop, **LangGraph-style subgraphs** for composability ergonomics, **AutoGenBench/Studio-style trace UI** as inspiration for simmis.

**What we offer that none of them do:**

1. **Resumable forks.** Codex snapshots; LangGraph forks graph state only. Ours are runtime *values* — we can jump back to any track checkpoint. This is what makes `simulate-reply` cheap enough to do several times per emission decision.
2. **Many participants, each with separate KBs.** Specialised reasoning + token economy beat single-long-context for multi-hour open-ended work.
3. **Explicit theory of mind via `simulate-reply`.** RSA-as-implementation, not RSA-as-prompt.
4. **Continuous-time personas with multi-driver spins** (§5.5–§5.6). Agents that work between user messages — reactive, proactive, observational, introspective.
5. **Modelling primitives, not just task execution.** simmis is built for *modelling* (board meetings, code reviews, research investigations).
6. **Compositional algebra + coalgebra with bisimulation** (§5.4, §6). Surrogates and virtuals interoperate; tests and production share semantics by formal substitution.
7. **FK / SMC at room granularity** (§10). DisCIPL and MSA do token-level FK; we do conversational-trajectory-level FK.
8. **Three-substrate `belief`** (§8.1). Symbolic + population-coded + feature-coded; prototype-ready today on open-weights participants via SAE/DAS.

**Where we will be worse, initially:** single-agent polish. Codex and Claude Code have years of UX iteration on "developer asks a question, gets a great answer." simmis's solo-use path must not lose too badly here. The model is multi-agent-first, but every use of `(ask single-agent msg)` must still feel as fast and clean as the competition. We have to invest specifically here, not just on the headline multi-agent scenarios.

What we adopt from Codex: the `ExecApprovalRequirement` model for finer-grained capability gating (Open Q #8 in §13). Codex's policy inheritance from parent to child is the right shape; we'll implement it as v2 evolves.

**What we offer that none of them do:**

1. **Resumable forks.** Codex's "forks" are snapshot-inherited fresh contexts. Ours are runtime *values* — we can jump back to any track checkpoint and replay from there. This is what makes `simulate-reply` cheap enough to do several times per emission decision.
2. **Many participants, each with separate KBs.** Specialised reasoning + token economy beat single-long-context for multi-hour open-ended work.
3. **Explicit theory of mind via `simulate-reply`.** RSA-as-implementation, not RSA-as-prompt.
4. **Continuous-time personas with multi-driver spins** (§5.5–§5.6). Agents that work between user messages — reactive, proactive, observational, introspective — not just one-shot tool calls.
5. **Modelling primitives, not just task execution.** simmis is built for *modelling* (board meetings, code reviews, research investigations); the substrate matches the use case.
6. **Compositional algebra with bisimulation.** Surrogates and virtuals interoperate; tests and production share semantics by formal substitution (§5.4).

**Where we will be worse, initially:** single-agent polish. Codex and Claude Code have years of UX iteration on "developer asks a question, gets a great answer." simmis's solo-use path must not lose too badly here. The model is multi-agent-first, but every use of `(ask single-agent msg)` must still feel as fast and clean as the competition. We have to invest specifically here, not just on the headline multi-agent scenarios.

## 16. Citations and prior art

[1] **Lew, A. K., Zhi-Xuan, T., Grand, G., Mansinghka, V. K. (2023)** — *Sequential Monte Carlo Steering of Large Language Models using Probabilistic Programs*. arXiv:2306.03081 / NeurIPS 2023 workshop. The reference for token-level SMC over LLMs (LLaMPPL). Our L1 borrows their async-batching and optimal-resampling patterns; their `Model.step()` is one layer below our `Participant`.

[2] **(authors) (2024)** — *Effective Sequential Monte Carlo for Language Model Probabilistic Programs*. LAFI / POPL 2024. LLaMPPL methodological deepening; auto-batching and SMC algorithm exploration.

[3] **TRICE — Training Chain-of-Thought via Latent-Variable Inference.** arXiv:2312.02179, NeurIPS 2023. CoT as MCMC-EM over latent rationales; relevant to participant-internal reasoning.

[4] **Goodman, N. D., Frank, M. C., Tessler, M. H. et al. — Rational Speech Acts (RSA).** http://www.problang.org. Recursive Bayesian inference on utterances; the formal account of pragmatic reasoning. Our `belief` signal + the RSA hook for utterance composition follow this lineage.

[5] **Tessler, M. H. et al. (2024)** — *AI can help humans find common ground in democratic deliberation.* Science 386, eadq2852. An AI mediator iteratively refines a common-ground statement. Our `align-on` combinator is named after this work; the Israeli–Palestinian peacebuilders follow-up validated it in the field.

[6] **Cohn-Gordon, R., Bärenz, M.** — Linguistic convention convergence + reactive probabilistic inference. Discussed personally; relates to Rhine FRP and choreographic programming. Bridges RSA-style pragmatics with reactive multi-agent FRP — the bridge our discourse substrate makes concrete.

[7] **Yao, S. et al. (2023)** — *Tree of Thoughts*. Tree-search over partial reasoning traces. The single-agent analog of our `fork-room`-based exploration.

[8] **Hao, S. et al. (2023)** — *Reasoning with Language Model is Planning with World Model* (RAP). EMNLP 2023; arXiv:2305.14992. MCTS over reasoning traces with the LLM as both agent and world model. Informs the L3 inference reading.

[9] **Bärenz, M. et al.** — *rhine-bayes: online reactive Bayesian inference.* Tweag blog 2023. SMC on Rhine's clock-safe FRP streams. The conceptual ancestor of spindel's inference package.

[10] **Del Moral, P.** — *Feynman–Kac Formulae: Genealogical and Interacting Particle Systems with Applications* (Springer, 2004). The mathematical reference for the FK framing.

[11] **spindel inference package** — `org.replikativ.spindel.inference.*`. SMC, importance sampling, PIMH, PGIBBS, PGAS, IPMCMC, BBVI on forking execution contexts. The substrate at L0/L3.

[12] **Belief-driven Multi-Agent LLM Debate (iMAD)** — arXiv:2511.11306 and arXiv:2510.18476. Multi-agent debate framed as Bayesian belief update; closest published analog of our discourse-level inference reading.

[13] **Sap, M. et al. (2022); Kosinski, M. (2023); Shapira, et al. (2023)** — Empirical work on (and critiques of) Theory-of-Mind in LLMs. Motivates the explicit `belief` signal in our model: ToM cannot be assumed; it has to be modelled.

[14] **Zelikman, E. et al. (2022)** — *STaR: Bootstrapping Reasoning With Reasoning*. CoT-as-EM; in the user's logseq.

### Algebraic foundations (the formal home of §6 and §7)

[15] **Tan, K., Wei, A., Sen, A., Zaharia, M. (2025)** — *Pangolin*. LMPL 2025. Types LLM calls as first-class algebraic effects; the closest formal account of LLM-call composition. Grounds 4/5 of our algebra primitives directly (`ask`/`fan-out`/`race`/`pipeline`); `debate` is the coalgebraic holdout.

[16] **Plotkin, G., Xie, N. (2025)** — *Selection monad for reward-based choice*. PLDI 2025; arXiv:2504.03890. The formal home for our laws (§7). Together with Pangolin, the algebraic-effects line for LLM composition.

### Mechanistic interpretability (the technical substrate for §5.4 feature-bisim and §8.1 feature-coded belief)

[17] **Bricken, T. et al. (Anthropic, 2023)** — *Towards Monosemanticity*. Founding sparse-autoencoder paper.

[18] **Templeton, A. et al. (Anthropic, 2024)** — *Scaling Monosemanticity*. SAE on Claude 3 Sonnet; Golden Gate Claude.

[19] **Geiger, A. et al. (2023)** — *Causal Abstraction + DAS (Distributed Alignment Search)*. The formal account for **bisimulation-by-features** (§5.4).

[20] **Lieberum, T. et al. (DeepMind, 2024)** — *Gemma Scope*. Open SAEs across Gemma 2; makes feature-coded belief substrate prototype-ready today.

### PLoT lineage (the language→PPL→runtime agenda — what §1 inherits from)

[21] **Wong, L., Grand, G., Lew, A. K., Goodman, N. D., Mansinghka, V. K., Tenenbaum, J. B., Andreas, J. (2023)** — *From Word Models to World Models: Translating from Natural Language to the Probabilistic Language of Thought*. arXiv:2306.12672. **The agenda paper.** LLMs as generators of probabilistic programs.

[22] **Grand, G. et al. (2025)** — *DisCIPL: Self-Steering Probabilistic Inference*. The closest existing FK/SMC-on-LLMs work at the level above token; single-mind. We lift to room granularity (§10).

[23] **Wong, L. et al. (2025)** — *Open-World Cognition via Modeling-the-Self-Awareness (MSA)*. Ephemeral world models synthesised on demand.

[24] **Ying, L. et al. (2024)** — *LaBToM: Language-Based Theory of Mind*. Operational Bayesian ToM; candidate belief updater inside a participant.

[25] **Kim, J. et al. (2025)** — *Thought Tracing*. ToM tracing in dialogue.

[26] **Chandra, K. et al. (Tenenbaum group, 2025)** — *memo: a DSL for stable world models*.

[27] **Zhi-Xuan, T. et al. (Mansinghka group, 2024)** — *CLIPS: Continual LLM-Backed Probabilistic Synthesis*.

### Cognitive science + LLMs

[28] **Gandhi, K. et al. (Goodman, 2023)** — *BigToM*. Benchmark for theory of mind in LLMs.

[29] **Liu, R. et al. (Griffiths, 2024)** — *LLMs Are Too Rational*. Resource-rational analysis of LLM cognition.

[30] **Binz, M., Schulz, E. et al. (Griffiths, 2024 → Nature 2025)** — *Centaur*. LLM fine-tuned as a foundation model of human cognition.

### Multi-agent LLM frameworks (§15 — the engineering peer set)

[31] **Wu, Q. et al. (Microsoft, 2025)** — *AutoGen v0.4*. Actor-model + event-driven rewrite. Closest mainstream comparison to dvergr.discourse.

[32] **Hong, S. et al. (2024)** — *MetaGPT*. Software-company-as-agents; typed-artefact pub/sub.

### Multi-agent RL / cooperative AI (§11 Step 7 benchmark; §13 Open Q #10)

[33] **Cross, L., Yamins, D., Haber, N. (2024)** — *Hypothetical Minds*. Closest ToM-via-NL-hypothesis prior art to our `simulate-reply`.

[34] **Hughes, E., Leibo, J. Z. et al. (DeepMind)** — *Melting Pot* and *Concordia*. The empirical benchmark suite proposed in §11 Step 7.

[35] **Laidlaw, C., Dragan, A., Russell, S. (Russell lab, 2025)** — *AssistanceZero*. Scalable cooperative IRL.

[36] **Carroll, M. et al. (Russell lab, 2024)** — *Influenceable Rewards / DR-MDP*. Preference dynamics under partial observability; basis for the `align-on` audit (Open Q #10).

[37] **Hammond, L. et al. (CAIF, 2025)** — *Multi-Agent Risks from Advanced AI*. Agenda paper for the Cooperative-AI community.

[38] **Critch, A. (2023)** — *TASRA: Trustworthy AI Systems Risk Analysis*. Societal-scale multi-agent risk taxonomy.

### Activation steering (§8.1 `with-steering`)

[39] **Zou, A. et al. (2023)** — *Representation Engineering (RepE)*. Activation-space steering directions.

[40] **Panickssery, N. et al. (2023)** — *Contrastive Activation Addition (CAA)*. Practical activation steering.

[41] **Arditi, A. et al. (2024)** — *Refusal in LLMs as a Single Direction*. Single-direction interventions.

### Categorical semantics of LLMs (background only — §5.4 paraphrase-bisimulation gestures)

[42] **Bradley, T., Terilla, J., Vlassopoulos, Y. (2021)** — *An Enriched Category Theory of Language*. arXiv:2106.07890. Foundational.

[43] **Mahadevan, S. (2025)** — *Categorical Homotopy for LLMs*. arXiv:2508.10018. Markov-category model; paraphrase as weak equivalence.

[44] **Zhang, Y. (2025)** — *Markov Categorical Framework for Language Modeling*. arXiv:2507.19247.

### Substrate sibling (§14 row)

[45] **Tavares, Z., Bingham, E., Mackevicius, E. (Basis Research Institute, 2023–2025)** — `chirho` (causal PPL), `effectful` (algebraic-effects metaprogramming in spindel's family), `Autumn` (causal reactive programs), *NeuroAI for AI Safety* roadmap. Closest substrate-and-worldview alignment of any external lab. April 2026 `Pact` multi-agent coordination language is a watch-item (Open Q #9).

---

## Appendix A — Canonical `dvergr.discourse` namespace sketch

```clojure
(ns dvergr.discourse
  "Multi-agent linguistic FRP: continuous-time participants in forkable rooms.

   This is the discourse substrate. Participants exchange Messages through
   Rooms; each Participant is a spindel spin reacting to inbox deltas.
   Optional discourse-level inference (smc-discourse) sits on top.

   See doc/discourse-model.md for the design rationale."
  (:require [org.replikativ.spindel.core :as sp]
            [org.replikativ.spindel.engine.core :as eng]
            [org.replikativ.spindel.signal :as sig]
            [org.replikativ.spindel.combinators :as comb]
            [org.replikativ.spindel.sync :as sync]
            [org.replikativ.yggdrasil.api :as ygg]
            [datahike.api :as d]))

;; --- Records ---------------------------------------------------------------

(defrecord Message     [id from to content ts in-reply-to tool-call metadata])
(defrecord Participant [id spec inbox outbox state belief process])
(defrecord Room        [id participants log topic common-ground delivery fork-ctx])

;; --- Construction (Layer 2) ------------------------------------------------

(defn room        [opts] …)
(defn participant [{:keys [id spec on-message] :as opts}] …)
(defn human       [opts] …)
(defn agent       [opts] …)
(defn script      [id replies & {:as opts}] …)
(defn echo        [opts] …)
(defn tool-runner [opts] …)

;; --- Wrappers --------------------------------------------------------------

(defn with-latency  [p opts] …)
(defn with-system   [p prompt] …)
(defn flaky         [p opts] …)
(defn with-budget   [p opts] …)
(defn with-belief   [p opts] …)

;; --- Addressing ------------------------------------------------------------

(defn direct    [to content & {:as opts}] …)
(defn broadcast [content & {:as opts}] …)
(defn multicast [ids content & {:as opts}] …)
(defn reply     [msg content & {:as opts}] …)

;; --- Membership / delivery -------------------------------------------------

(defn join         [room p] …)
(defn leave        [room id] …)
(defn post!        [room msg] …)
(defn post-batch!  [room msgs] …)

;; --- Combinators (return spins) --------------------------------------------

(defn ask        [p msg & {:as opts}] …)
(defn fan-out    [msg ps & {:as opts}] …)
(defn race       [msg ps & {:as opts}] …)
(defn quorum     [msg ps n & {:as opts}] …)
(defn pipeline   [ps msg & {:as opts}] …)
(defn debate     [ps {:keys [rounds] :as opts}] …)
(defn moderate   [moderator ps & {:as opts}] …)
(defn align-on   [room topic {:keys [participants mediator accept? max-rounds]}] …)

;; --- Forking (Layer 2) -----------------------------------------------------

(defn fork-room  [room] …)
(defn merge-room [fork] …)
(defn discard    [fork] …)

;; --- Inference (Layer 3, opt-in) -------------------------------------------

(defn smc-discourse
  [room {:keys [particles potential resample-at kernel resampler
                ess-thresh max-time-ms]}] …)
```

## Appendix B — Worked example

```clojure
(require '[dvergr.discourse :as d])

;; 1. Construct a room with three participants
(def r        (d/room {:id :code-review}))
(def coder    (d/agent {:id :coder    :spec coder-spec    :tools [:fs :git :clojure_eval]}))
(def reviewer (d/agent {:id :reviewer :spec reviewer-spec :tools [:fs :clj_kondo]}))
(def user     (d/human {:id :alice}))

(-> r (d/join coder) (d/join reviewer) (d/join user))

;; 2. User asks the coder to do something; the harness drives the iteration
(def task (d/direct :coder {:content "Add an :auth/login route returning a JWT."}))

(def outcome
  @(d/iterative-refinement coder reviewer task
     {:accept? (fn [m] (re-find #"(?i)lgtm|approved" (:content m)))
      :max-iter 3}))

;; 3. Suppose we want to explore two refactoring strategies in parallel
(def variants
  @(d/fan-out
     (d/direct :coder
       {:content "Two strategies: (a) plain ring middleware, (b) reitit interceptor.
                  Implement both in forked rooms."})
     #{(d/fork-room r) (d/fork-room r)}))

;; 4. Have the reviewer race between them; keep the winner
(def best
  @(d/race
     (d/broadcast {:content "Pick the better strategy and explain why."})
     #{coder reviewer user}))

;; 5. Higher-order: full population SMC over the room
(def measure
  @(d/smc-discourse r
     {:particles   8
      :potential   alignment-signal
      :resample-at turn-boundary
      :kernel      :smc-steer
      :ess-thresh  0.5
      :max-time-ms 60000}))
```

Steps 1–4 are typical user-facing patterns. Step 5 is the rarely-needed L3 escalation, available for hard exploration without changing the substrate.

---

**End of document.**
