# Related Work — Multi-Agent LLM Frameworks (2023–2025)

> Compiled 2026-05-23 for contrast against `doc/discourse-model.md`.
> Scope: the concrete, production-ish multi-agent stacks shipped between mid-2023 and mid-2025, plus the two interop protocols (MCP, A2A) the field has now standardised on.
> PDFs (where the project is academic) live in `./pdfs/` under the same `firstauthor-year` convention as the cognitive-science survey.

This file complements `related-work/README.md`. That document surveys the *probabilistic-programs-as-cognition* agenda (MIT ProbComp, Stanford Goodman, Princeton Griffiths). This document surveys the *engineering* agenda — the multi-agent frameworks that the rest of the industry has actually adopted. The discourse model has to be legible to *both* communities.

---

## 1. AutoGen (Microsoft → Microsoft + AG2)

### 1.1 AutoGen v0.2 — *Wu, Bansal, Zhang, Wu, Zhang, Zhu, Li, Jiang, Zhang, Wang (2023)*
arXiv [2308.08155](https://arxiv.org/abs/2308.08155) · *File: `pdfs/wu-2023-autogen.pdf`*

- **Architecture**: `ConversableAgent` is the base type; `AssistantAgent` (LLM) and `UserProxyAgent` (executor / human) are the canonical pair. Multi-agent settings are organised by a `GroupChat` + `GroupChatManager`, which round-robins or LLM-routes the next speaker.
- **Communication**: direct, synchronous message-passing through `initiate_chat` / `send` / `receive`. The default v0.2 topology is **two-agent ping-pong** or **group-chat with a manager picking who speaks next**.
- **State**: chat histories live on the agents; tool/code results are folded back into the message stream. No first-class room object; the manager *is* the room.
- **Sandbox**: code-execution in Docker (preferred), local Python, or IPython kernel; an explicit `code_execution_config` per agent.
- **Sub-agent delegation**: `register_nested_chats` lets an agent open a sub-conversation; results bubble back as a single message.
- **Budget**: per-LLM-call cost tracking (`autogen.token_count_utils`, `consumption_summary`) but no built-in global budget enforcement; cap by `max_consecutive_auto_reply` / `max_turns`.
- **Fork/branch**: none. Histories are linear lists; no branching/replay primitives.
- **Theory-of-mind**: none beyond what the LLM imagines from the shared chat log.

### 1.2 AutoGen v0.4 — the 2024 rewrite
Microsoft Research blog ([announcement](https://www.microsoft.com/en-us/research/blog/autogen-v0-4-reimagining-the-foundation-of-agentic-ai-for-scale-extensibility-and-robustness/), Jan 2025) · no separate arXiv paper.

- **Architecture**: complete rewrite as an **asynchronous, event-driven, actor-model** runtime. Three layers:
  1. `autogen-core` — actor framework (mailboxes, async dispatch, subscriptions, routing).
  2. `autogen-agentchat` — task-driven high-level API; the v0.2 abstractions reimplemented on top of Core.
  3. `autogen-ext` — third-party extensions (model clients, tools, runtimes).
- **Communication**: **two modes** on the same substrate — *direct messaging* (request/response between agent identities) and *event/topic publish-subscribe* (broadcast to subscribers). This is the closest mainstream framework to our discourse model.
- **State**: actor-local; messages are typed; cross-language interop (Python + .NET) over a wire protocol; supports a **distributed runtime** where actors live in separate processes.
- **Observability**: OpenTelemetry built in (traces + metrics), message tracing, AutoGen Studio UI.
- **Sandbox**: inherited via extensions; Docker runners + the AutoGenBench harness.
- **Sub-agent delegation**: actors can spawn nested actors; group-chat patterns are reimplemented as topic subscriptions.
- **Budget**: still per-call cost tracking + `max_turns`; no first-class global budget.
- **Fork/branch**: **none** — the actor runtime is single-history.
- **Theory-of-mind**: none built in.

### 1.3 The AutoGen / AG2 / Microsoft Agent Framework split
- Sep 2024: Chi Wang and Qingyun Wu leave Microsoft.
- Nov 2024: they fork v0.2 into **AG2** ([ag2ai/ag2](https://github.com/ag2ai/ag2)), keep the `pyautogen` package, community-govern it. AG2 Beta later added its own streaming event-driven layer and multi-provider model clients.
- Oct 2025: Microsoft merges AutoGen v0.4 into the **Microsoft Agent Framework** (with Semantic Kernel enterprise primitives); RC1 in Feb 2026, GA targeted Q1 2026.
- The practical result: four AutoGen-derived stacks (`autogen-0.2`, `autogen-0.4`, `ag2`, `microsoft-agent-framework`) coexist in 2025, and "which AutoGen?" is now a non-trivial question.

---

## 2. MetaGPT — *Hong, Zhuge, Chen, Zheng, Cheng, Zhuang, Wang, Yau, Lin, Zhou, Ran, Xiao, Wu, Schmidhuber (2023)*
arXiv [2308.00352](https://arxiv.org/abs/2308.00352), ICLR 2024 · *File: `pdfs/hong-2023-metagpt.pdf`*

- **Architecture**: an *assembly line of roles* mirroring a software company — Product Manager, Architect, Project Manager, Engineer, QA. Each role has a system prompt encoding a **Standardized Operating Procedure (SOP)**.
- **Communication**: a **shared publish/subscribe Message Pool**. Roles publish structured artefacts (PRD, system design, task list, code) and subscribe to artefacts relevant to their role. *This is one of the few systems other than AutoGen v0.4 with an explicit pub/sub message substrate.*
- **State**: artefacts (documents, diagrams) are the state, not chat logs. Messages have role-typed schemas, which heavily constrains what each agent reads.
- **Sandbox**: executable feedback loop — code is run, errors fold back into the Engineer role for iteration.
- **Sub-agent delegation**: implicit via role hand-off; the SOP defines who picks up what.
- **Budget**: token usage tracked; no hard budgeter.
- **Fork/branch**: none — single linear company.
- **Theory-of-mind**: none beyond what the role-specific prompts encode about "the Architect's job is to imagine the Engineer's needs."

---

## 3. CAMEL — *Li, Hammoud, Itani, Khizbullin, Ghanem (2023)*
KAUST · arXiv [2303.17760](https://arxiv.org/abs/2303.17760), NeurIPS 2023 · *File: `pdfs/li-2023-camel.pdf`*

- **Architecture**: the canonical **role-playing pair** — an *AI User* (issues sub-instructions) and an *AI Assistant* (executes). A *Task Specifier* preprocessor refines a vague human task into a concrete brief that seeds the dialogue.
- **Communication**: strictly turn-based, two-agent dialogue, started by an "inception prompt" that pins each role's persona and conversation protocol.
- **State**: chat history only; the *protocol itself* is the state — termination tokens, role-flipping detection.
- **Sandbox**: none (early framework; tool use bolted on later in the CAMEL-AI library).
- **Sub-agent delegation**: none structurally; everything is two-agent.
- **Budget**: turn limit; cost not first-class.
- **Fork/branch**: none.
- **Theory-of-mind**: implicit — the User must model the Assistant's competence enough to issue tractable sub-instructions; the paper documents *failure modes* (role-flipping, instruction-repetition, infinite politeness loops) that we'd today call ToM failures.

CAMEL is now also a broader open-source framework (`camel-ai/camel`) with hundreds of agents and the OWL / Loong sub-projects, but the paper's contribution is the role-playing protocol.

---

## 4. AgentVerse — *Chen, Su, Yan, Cong, Tian, Xu, Zhou, Han, Liu, Chen, Sun (2023)*
arXiv [2308.10848](https://arxiv.org/abs/2308.10848), ICLR 2024 · *File: `pdfs/chen-2023-agentverse.pdf`* · OpenBMB/AgentVerse.

- **Architecture**: a 4-stage outer loop — **Expert Recruitment → Collaborative Decision-Making → Action Execution → Evaluation** — that *dynamically re-recruits* agents per problem and per stage.
- **Two frameworks** in the same repo: *task-solving* (assemble agents to do a job) and *simulation* (NLP-classroom, debate, etc., as Park-style social sims).
- **Communication**: horizontal (debate, peer review) and vertical (recruiter assigns) modes are both supported. Closer to a *meeting* than a pipeline.
- **State**: per-stage context plus a global memory; emergent behaviour (volunteer behaviour, conformity, destructive behaviour) is the headline finding.
- **Sandbox**: tool use, code execution via extensions.
- **Sub-agent delegation**: the recruiter is a meta-agent; this is the closest paper to "agent composes sub-agents on the fly."
- **Budget**: turn-based; no hard cap.
- **Fork/branch**: none in the substrate, but the dynamic recruitment is a form of *exploring alternative team compositions*.
- **Theory-of-mind**: studied empirically — they observe ToM-like behaviour emerging from the protocol, but don't model it explicitly.

---

## 5. AGENTS (the framework) — *Zhou, Jin, Li, Xu, Sun, Yu, Wei, Bi, Wang, He, Zhou, Liu (2023)*
arXiv [2309.07870](https://arxiv.org/abs/2309.07870) · *File: `pdfs/zhou-2023-agents-framework.pdf`*

- **Architecture**: a config-driven framework — **agents defined by YAML** with planning, memory (short, long, episodic), tools, multi-agent roles via SOPs and a *Symbolic Operating Procedure* DSL that resembles a tiny FSM.
- **Communication**: SOP-edge-driven; turn-taking is part of the static config rather than emergent.
- **State**: explicit short/long/episodic memory split; vectorstore-backed retrieval.
- **Sandbox**: tool plug-ins; no strong code sandbox.
- **Sub-agent delegation**: SOP can call SOPs; recursion is supported.
- **Budget**: none first-class.
- **Fork/branch**: none.
- **Theory-of-mind**: none.
- **Significance**: the most explicitly *declarative* of the academic frameworks — the closest to "describe the protocol, then run it" before LangGraph made that the dominant idiom.

---

## 6. LangGraph (LangChain, 2024–)
`langchain-ai/langgraph` — no paper; documented at [langchain-ai.github.io/langgraph](https://langchain-ai.github.io/langgraph/) and the [multi-agent workflows](https://www.langchain.com/blog/langgraph-multi-agent-workflows) blog.

- **Architecture**: a **typed state graph**. Nodes are functions/agents; edges (rule-based or LLM-routed) decide who runs next; a single typed `State` (often `MessagesState`) flows through. Sub-graphs are first-class.
- **Communication**: **shared state** is the default — all nodes read/write the same `State`. The newer `Command` primitive lets a node both update state *and* hand off to a named successor, giving "handoff" semantics on top of the graph.
- **Patterns**: **Supervisor** (router agent picks a worker each turn), **Hierarchical Teams** (sub-graphs as agents), **Swarm** (agents hand off arbitrarily), **Network** (full mesh).
- **State**: typed reducer-based — fields can append, merge, or overwrite. **Checkpointers** persist state per *thread*, enabling resume, replay, and **time-travel** (rewind to a checkpoint and run forward differently).
- **Sandbox**: outside the framework; usually a tool-execution node.
- **Sub-agent delegation**: sub-graphs; standard.
- **Budget**: not first-class but easy to add as a state field.
- **Fork/branch**: **partial** — checkpointers + `update_state` + `as_node` give a *manual* time-travel/replay capability; there is no built-in population/SMC, but it's the most fork-friendly mainstream framework.
- **Theory-of-mind**: none built in.
- **Observability**: **LangSmith** — traces, evals, dataset replay, human review. Industry-leading observability for the LangChain stack.

---

## 7. CrewAI
`crewAIInc/crewAI` — no paper; large community framework.

- **Architecture**: a **Crew** of role-based **Agents** executing a list of **Tasks** under a **Process** (Sequential or Hierarchical). 2024 added **Flows**: an event-driven decorator-based workflow layer (`@start`, `@listen`, `@router`) for production orchestration on top of Crews.
- **Communication**: in *Sequential* process, task outputs feed the next task; in *Hierarchical*, a manager agent delegates and validates. Inter-agent messages are managed by the framework, not by user code.
- **State**: per-crew memory (short-term, long-term, entity, contextual); Flows add an explicit state object.
- **Sandbox**: code-execution tools but not a first-class sandbox.
- **Sub-agent delegation**: native — agents can call peers via `delegation=True`.
- **Budget**: usage logging; no hard global budget.
- **Fork/branch**: none.
- **Theory-of-mind**: none.
- **Observability**: CrewAI AMP provides traces; AgentOps and Langfuse integrate.

---

## 8. OpenAI Swarm → OpenAI Agents SDK
Swarm: `openai/swarm` (Oct 2024, experimental) · Agents SDK: announced Mar 2025, replaces Swarm.

- **Architecture**: minimalist. An **Agent** has *instructions* + *functions*. A function may return another Agent → that becomes a **handoff** (control transfer). The runtime is **stateless across `client.run()` calls** — the entire state is the messages array + a `context_variables` dict you carry yourself.
- **Communication**: implicit — there's no message-passing primitive; "agents communicate" by being handed the same chat log when control transfers.
- **State**: explicitly client-side. No memory, no persistence.
- **Sandbox**: none.
- **Sub-agent delegation**: handoff is the *only* multi-agent mechanism.
- **Budget**: only `max_turns`.
- **Fork/branch**: none.
- **Theory-of-mind**: none.
- **Significance**: tiny (~few hundred LoC), but it normalised the "agent = instructions + tools, handoff = function-returns-agent" idiom that LangGraph's swarm pattern and the OpenAI Agents SDK both inherit.

---

## 9. Magentic-One — *Fourney, Bansal, Mozannar, Tan, Salinas, Niedtner, Proebsting, Bassman, Gerrits, Alber, Chang, Sarrafzadeh, Loynd, Smith, Awadallah, Horvitz, Wang (Microsoft, 2024)*
arXiv [2411.04468](https://arxiv.org/abs/2411.04468) · *File: `pdfs/fourney-2024-magentic-one.pdf`* · built on AutoGen v0.4.

- **Architecture**: an **Orchestrator** agent + four specialists — **WebSurfer**, **FileSurfer**, **Coder**, **ComputerTerminal**. The Orchestrator maintains a **Task Ledger** (facts, guesses, plan) and a **Progress Ledger** (per-step status, stall detection, next speaker).
- **Communication**: event-driven on the v0.4 actor substrate; the Orchestrator drives by writing to a shared ledger and addressing the next worker.
- **State**: the dual Ledger is the explicit shared state; chat history is secondary.
- **Sandbox**: ComputerTerminal + Coder give the team a shell; the WebSurfer runs a real browser via Playwright.
- **Sub-agent delegation**: fixed roster; the modular design lets you swap workers without re-prompting.
- **Budget**: turn caps; stall-detection causes re-plan rather than burning more turns.
- **Fork/branch**: none.
- **Theory-of-mind**: the Orchestrator's Progress Ledger explicitly tracks *what each worker is doing and why*, which is the closest any production framework comes to a model-of-the-team. Still not Bayesian.
- **Significance**: the first system to be SoTA on GAIA / AssistantBench / WebArena from a multi-agent design (rather than tool-augmented single agent). Bundled with **AutoGenBench**, an agent-eval harness that has become a de-facto standard.

---

## 10. AutoGPT / BabyAGI (Mar 2023)
- **AutoGPT** (Toran Bruce Richards): a single-agent goal-loop — `plan → criticise → execute tool → observe → loop`. By late 2023 the vector-DB memory was removed in favour of a local file; AutoGPT pivoted from research toy to a workflow-automation product (`AutoGPT Platform`, `Forge`).
- **BabyAGI** (Yohei Nakajima): a 3-LLM-call loop — *task creator*, *task prioritiser*, *task executor* — backed by Pinecone (originally) for episodic memory.
- **Significance**: historically the first *autonomous* agent demos; architecturally not multi-agent in any meaningful sense, but they seeded the field's vocabulary (Plan/Execute/Critique, episodic memory, tool calling) and the failure modes (goal drift, infinite planning, hallucinated tools) that later frameworks tried to fix.

---

## 11. The 2025 interop layer: MCP and A2A

These are not frameworks — they are *standards* the frameworks adopt, and they matter for us because dvergr should speak them at the boundary.

- **MCP (Model Context Protocol)** — Anthropic, Nov 2024 ([anthropic.com/news/model-context-protocol](https://www.anthropic.com/news/model-context-protocol)). LSP-inspired JSON-RPC 2.0 protocol for agent ↔ tool/data servers. SDKs in Python, TS, C#, Java, Kotlin, Rust. Adopted by OpenAI (Mar 2025), Google DeepMind (Apr 2025), and donated to the Linux Foundation's Agentic AI Foundation in Dec 2025.
- **A2A (Agent2Agent)** — Google, Apr 2025 ([Google Developers blog](https://developers.googleblog.com/en/a2a-a-new-era-of-agent-interoperability/)). HTTP + Server-Sent-Events + JSON-RPC; **Agent Cards** at `/.well-known/agent.json` for capability discovery; lifecycle-typed Tasks. 150+ partners by 2026, also under the Linux Foundation.

The current consensus stack is **MCP for agent→tool** and **A2A for agent→agent**, with framework-specific orchestrators on top.

---

## 12. Theory-of-Mind in multi-agent LLM systems (an aside)

ToM is *not* a feature of any production framework above. It appears as a research direction:
- *Hypothetical Minds* (Cross et al., 2024 — in our `pdfs/`) — explicit ToM module for ad-hoc coordination.
- *Theory of Mind for Multi-Agent Collaboration via LLMs* (Li et al., 2023, arXiv 2310.10701).
- *LLM-Coordination* benchmark (Agashe et al., NAACL 2025) — ToM correlates with coordination performance.
- *Oguntola CMU thesis* (2025) — full survey of ToM in MA systems.
- *ToMA / Infusing ToM into Socially Intelligent LLM Agents* (Sep 2025, arXiv 2509.22887).

Takeaway: industry frameworks are *not* doing this yet. Our `belief` + `common-ground` signals in §8 of the discourse model are an early, opinionated commitment in a direction the literature is now converging on.

---

## 13. Comparison matrix

Legend — *first-class* = built into the substrate; *via-extension* = supported by a community extension or recipe; *no* = absent or hostile.

| Framework | Comms model | Fork / branch | Budget control | Sandbox / isolation | Multi-LLM | Observability | ToM |
|---|---|---|---|---|---|---|---|
| **AutoGen v0.2** | direct + group-chat manager | no | per-call cost; turn cap | Docker code-exec | yes (LiteLLM-style) | logs; AutoGen Studio | no |
| **AutoGen v0.4** | **actor + topic pub/sub** | no | turn cap | extension | yes (multi-provider) | **OpenTelemetry built-in** | no |
| **AG2** | as v0.2 + streaming events | no | turn cap | Docker | yes | logs | no |
| **MS Agent Framework** | as v0.4 + SK workflows | no | per-call | extension | yes | OTel + Foundry | no |
| **MetaGPT** | **shared publish/subscribe pool, typed artefacts** | no | turn cap | code exec | yes | logs | no |
| **CAMEL** | two-agent role-play | no | turn cap | no | yes (provider-agnostic) | minimal | empirical-only |
| **AgentVerse** | dynamic recruit + horizontal/vertical | no | turn cap | tools | yes | minimal | empirical-only |
| **AGENTS** | SOP DSL / FSM | no | turn cap | tools | yes | minimal | no |
| **LangGraph** | **shared typed state graph + Command handoffs** | **partial — checkpoint + time-travel** | DIY via state | tool node | yes | **LangSmith (first-class)** | no |
| **CrewAI** | sequential / hierarchical / Flows | no | DIY | tools | yes | AMP + AgentOps | no |
| **OpenAI Swarm / Agents SDK** | handoff via function return | no | `max_turns` only | no | OpenAI-centric | OpenAI dashboards | no |
| **Magentic-One** | actor + dual-ledger | no | stall-detect re-plan | browser + shell | yes (v0.4) | OTel via v0.4 | partial (Progress Ledger) |
| **AutoGPT / BabyAGI** | single agent | no | iteration cap | local exec | yes | logs | no |
| **MCP / A2A (standards)** | JSON-RPC + SSE + Agent Cards | no | not addressed | not addressed | n/a | not addressed | no |
| **dvergr discourse** | **continuous-time spins + room delivery, direct + multicast + pub/sub** | **first-class room/spin fork via spindel/yggdrasil** | **first-class signal-typed budgets (planned)** | **spindel SCI sandbox + datahike fork** | yes (LLM-agnostic) | spindel traces + datahike provenance | **first-class `belief` + `common-ground` signals** |

---

## 14. Closest comparison — and where we differ

> *"AutoGen v0.4's event-driven rewrite is the closest comparison."*

**Confirmed.** AutoGen v0.4 is the closest mainstream framework to the dvergr discourse model in *substrate shape*:

- both are **actor / spin -shaped** — agents are first-class processes, not pure functions called in a loop;
- both expose **direct + topic-based** message routing;
- both treat the runtime as the thing being built — observability, distributed deployment, typed messages are runtime concerns, not user concerns.

MetaGPT's *publish/subscribe message pool* is the second-closest at the message-routing level, but its agents are still synchronous role-pipeline steps rather than continuous-time spins. LangGraph is the closest at the *graph/state* level — shared typed state + Command handoffs + checkpointers give the cleanest *replay / time-travel* story in production. Magentic-One is the closest at the *orchestration-with-explicit-team-model* level (the Progress Ledger).

### Where dvergr differs from AutoGen v0.4 and the rest

1. **Forkable runtime** — none of the frameworks above have first-class fork/branch of the *execution context*. LangGraph's checkpoint + time-travel is the closest, but it forks the *graph state*, not the *runtime* (no forked datahike, no forked SCI sandbox, no forked git repo at once). dvergr inherits this for free from spindel + yggdrasil. This is what makes `what-if` / population-SMC / `smc-discourse` feasible.

2. **Continuous-time participants** — all of the above have *turn-based* agents: someone wakes them, they reply, they sleep. In dvergr a participant is a `spin` that is *always on* and reacts to mailbox deltas; there is no central scheduler calling `step()`. (AutoGen v0.4's actor model is the closest, but its actors are still request/response, not delta-reactive on typed signals.) See §5.1 of `discourse-model.md`.

3. **Feynman–Kac / population SMC as a first-class mode** — `(smc-discourse …)` runs population SMC over forked rooms with weighted resampling. No production framework supports this; only academic papers like *Self-Steering LMs (DisCIPL)* and *MSA* (covered in `README.md`) operate in that space, and they do it at *token* or *single-question* granularity, not at *utterance / room* granularity.

4. **Bisimulation as the equivalence relation** — the discourse model commits to a bisimulation-style equivalence over participants (two participants are interchangeable iff their inbox/outbox signal-deltas are observationally indistinguishable). No framework above formalises agent equivalence at all; this is a substrate property we lean on for caching, deduplication, and proving fork merges sound.

5. **Theory-of-mind as a signal** — `belief` and `common-ground` are *signals on the participant*, not prompt fragments. Spindel's reactive evaluation propagates them as the dialogue evolves; they're first-class addressable state. The literature is converging on the need for this (§12); we have the substrate to make it cheap.

6. **One shape, many participants** — humans, LLM agents, tool-runners, scripts, echos are *literally the same `Participant` type* in dvergr. Most frameworks have a `UserProxy` or a `HumanInLoop` mode tacked on; we have a single substrate that doesn't care whether the outbox is driven by a UI, an LLM, or a deterministic test script. (AutoGen comes close with `ConversableAgent`; LangGraph and CrewAI bolt humans on at the edges.)

---

## 15. What they have that we'd benefit from

These are features we should adopt or interop with, not reinvent.

- **OpenTelemetry traces, as AutoGen v0.4 ships them.** Span-per-spin, span-per-message, propagating trace context across forks. The hardest part of debugging a forkable-discourse system is reading the trace; OTel is the lingua franca.
- **LangSmith-style trace UI and dataset replay.** Even if we don't use LangSmith itself, the *idea* — record every run, diff runs, replay with overrides — is exactly what a forkable runtime should be able to render.
- **MCP for tools.** A tool-runner participant should be a thin shim over an MCP client. Free access to the Anthropic/OpenAI/Google tool ecosystem.
- **A2A for inter-room / inter-org boundaries.** Agent Cards at `/.well-known/agent.json` give us a discovery story for federating dvergr rooms with non-dvergr agents.
- **AutoGen Studio / Magentic-One UI.** A browser UI for inspecting an in-flight room (who said what to whom, why, with what weight) is the user-facing surface we still owe ourselves.
- **AutoGenBench.** A standard harness for agent-eval that other frameworks already speak. We should be able to take an AutoGenBench task and run it inside a dvergr room.
- **CrewAI Flows / LangGraph subgraphs**: explicit production composability — entire rooms as reusable, parameterised building blocks. Spindel already has `pfork`; we want the same ergonomic affordance at the discourse layer.
- **Typed message schemas** (MetaGPT artefacts, AutoGen v0.4 typed messages). Today our `Message.content` is loose; structured typed envelopes are how you avoid cascading hallucination.

## 16. What we have that they don't

These are the discourse axes that are ours and (today) only ours.

- **Fork / branch of the entire runtime** — execution context, datahike, SCI sandbox, git, signals — in one operation. The basis for cheap counterfactuals, population SMC, and "try another plan" without polluting the trunk room.
- **Continuous-time, mailbox-delta-driven participants** — no `step()`, no central scheduler. The room is a delivery medium, not a turn manager.
- **Feynman–Kac / SMC at the room / utterance level** — `(smc-discourse …)`, particle-weighted resampling of rooms, hierarchical Feynman–Kac for population-of-populations.
- **Bisimulation-based equivalence on participants** — formal substitution and caching semantics; the basis for proving fork-merges sound.
- **`belief` + `common-ground` as first-class reactive signals** — Stalnakerian common ground as a signal, not a prompt fragment; updated by the spindel runtime, not the model.
- **Same shape for human / LLM / tool / script / echo** — one `Participant` type, five constructors. Tests run on the same substrate as production.
- **Three intensities of speculation** — no-fork, single local fork, population SMC — reachable on the same API. Most frameworks support only the first.
- **Provenance via datahike** — every belief / message / fork is a Datalog fact with branch ancestry. The trace UI is then a query, not a separate database.

---

## 17. Sources

- AutoGen v0.4 announcement — Microsoft Research blog ([link](https://www.microsoft.com/en-us/research/blog/autogen-v0-4-reimagining-the-foundation-of-agentic-ai-for-scale-extensibility-and-robustness/)) and dev blog ([link](https://devblogs.microsoft.com/autogen/autogen-reimagined-launching-autogen-0-4/)).
- AutoGen v0.2 paper — arXiv [2308.08155](https://arxiv.org/abs/2308.08155).
- MetaGPT — arXiv [2308.00352](https://arxiv.org/abs/2308.00352), ICLR 2024.
- CAMEL — arXiv [2303.17760](https://arxiv.org/abs/2303.17760), NeurIPS 2023.
- AgentVerse — arXiv [2308.10848](https://arxiv.org/abs/2308.10848), ICLR 2024; [OpenBMB/AgentVerse](https://github.com/OpenBMB/AgentVerse).
- AGENTS — arXiv [2309.07870](https://arxiv.org/abs/2309.07870).
- LangGraph — [docs](https://langchain-ai.github.io/langgraph/), [multi-agent workflows blog](https://www.langchain.com/blog/langgraph-multi-agent-workflows).
- CrewAI — [crewAIInc/crewAI](https://github.com/crewAIInc/crewAI).
- OpenAI Swarm — [openai/swarm](https://github.com/openai/swarm); superseded by the OpenAI Agents SDK.
- Magentic-One — arXiv [2411.04468](https://arxiv.org/abs/2411.04468).
- AutoGPT — [Significant-Gravitas/AutoGPT](https://github.com/Significant-Gravitas/AutoGPT); BabyAGI — [yoheinakajima/babyagi](https://github.com/yoheinakajima/babyagi).
- MCP — [Introducing the Model Context Protocol (Anthropic)](https://www.anthropic.com/news/model-context-protocol).
- A2A — [Google Developers blog](https://developers.googleblog.com/en/a2a-a-new-era-of-agent-interoperability/), [a2aproject/A2A](https://github.com/a2aproject/A2A).
- AG2 fork — [ag2ai/ag2](https://github.com/ag2ai/ag2); Microsoft Agent Framework — [Microsoft Learn](https://learn.microsoft.com/en-us/agent-framework/).
- ToM in multi-agent LLM systems — Hypothetical Minds (Cross et al. 2024), Theory of Mind for Multi-Agent Collaboration (Li et al. 2023, arXiv 2310.10701), Oguntola CMU thesis (2025), ToMA (arXiv 2509.22887).
