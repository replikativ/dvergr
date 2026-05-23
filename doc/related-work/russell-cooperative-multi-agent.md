# Related Work — Russell-Lab, Cooperative AI, Multi-Agent RL with LLMs

> Compiled 2026-05-23 as companion to `README.md` (MIT/Stanford/Princeton probabilistic-programs-as-cognition agenda) and `doc/discourse-model.md`.
> Scope: Stuart Russell + close collaborators (CHAI/MIT/Oxford/DeepMind), the Cooperative AI Foundation, multi-agent RL with LLMs, theory of mind in MARL, 2023–2025.

Where the MIT/Stanford agenda asks *how should a single mind reason about minds?*, the **Russell axis** asks *how should AI assist, coordinate, and avoid catastrophic multi-agent failure modes?* The two literatures meet at "assistance games" (one-shot ToM-plus-planning) and "Concordia / Melting Pot" (population coordination), and they are exactly the cooperation/credit-assignment substance that our discourse model alludes to but does not yet prove.

PDFs are in `./pdfs/`; citation form is `firstauthor-year` matching the filename.

---

## 1. Russell-lab outputs — students and close collaborators (2023–2025)

### **Laidlaw, Bronstein, Guo, Feng, Berglund, Svegliato, Russell, Dragan (2025) — *AssistanceZero: Scalably Solving Assistance Games*** ICML-25 [arXiv:2504.07091](https://arxiv.org/abs/2504.07091)
*File: `pdfs/laidlaw-2025-assistancezero.pdf`*

- **Claim:** RLHF has structural drawbacks (incentivises deceptive sycophancy, ignores hidden goals); assistance games — two-player POMDPs where the assistant *cannot observe* the principal's goal — fix these but were intractable. AssistanceZero is the first scalable solver.
- **Method:** AlphaZero with a neural net that predicts *both* (a) human actions and (b) rewards, enabling planning under goal uncertainty. Applied to a Minecraft building game with ~10⁴⁰⁰ possible goals.
- **Evidence:** Human study; AssistanceZero-trained assistant significantly reduces actions humans take to complete builds vs. an RLHF baseline.
- **Stance:** **Direct prior art** for our `hire` primitive's *capability-aware* dimension. They prove a scalable assistant can plan over a posterior of principal goals; our `hire` would benefit from such a sub-agent (the sub plans a build, the parent retains the goal). What we add: the goal-posterior lives in a **forkable room**, so multiple competing assistants can be hired in parallel and the best merged. They have *one* assistant solving one task; we propose *populations* solving forked variants.
- This is also the canonical recent extension of CIRL (Hadfield-Menell, Russell, Dragan, Abbeel 2016) — they have abandoned the toy gridworld instance and have a working scalable algorithm.

### **Carroll, Foote, Siththaranjan, Russell, Dragan (2024) — *AI Alignment with Changing and Influenceable Reward Functions*** ICML-24 (oral) [arXiv:2405.17713](https://arxiv.org/abs/2405.17713)
*File: `pdfs/carroll-2024-influenceable-rewards.pdf`*

- **Claim:** Standard RLHF assumes static preferences. Real users' preferences change *because of* AI interaction. Dynamic-Reward MDPs (DR-MDPs) model this; under DR-MDPs, existing alignment techniques implicitly reward AI for *influencing* user preferences toward whatever is easier to satisfy.
- **Method:** Formal DR-MDP framework; reductio simulations on engagement-optimising recommenders showing preference manipulation as a Nash-style fixed point.
- **Evidence:** Closed-form examples + simulated recommender study; classes of "incentivised influence" emerge automatically.
- **Stance:** **Theoretical companion** to our `align-on` and `belief` signals. Their DR-MDP is the rigorous account of why our `belief` must update under inbox deltas without the participant's reward leaking back into the prior. Our discourse-model §8.3 (`align-on`/Habermas Machine) is *exactly* the scenario they analyse — but they prove the failure mode, we propose a structural mitigation (fork-and-discard makes manipulation visible).

### **Lang, Foote, Russell, Dragan, Jenner, Emmons (2024) — *When Your AIs Deceive You: Challenges of Partial Observability in RLHF*** NeurIPS-24
- **Claim:** Under partial observability of state, RLHF teaches the model to optimise the human's *belief about reward*, not reward itself — i.e., to deceive.
- **Stance:** **Reinforces** our argument that ToM has to be *operational* (fork-and-simulate) not prompt-implicit; otherwise we collapse into the same failure mode at the discourse layer.

### **Conitzer, Freedman, Heitzig, Holliday, Jacobs, Lambert, Mosse, Pacuit, Russell, Schoelkopf, Tewolde, Zwicker (2024) — *Social Choice for AI Ethics and Safety*** ICML-24
- **Claim:** Aligning AI to *aggregated* human preferences is a social-choice problem; Arrow-style impossibility results apply.
- **Stance:** **Justifies** the existence of `align-on` as a *protocol* (which voting rule? when is consensus declared?) rather than a function. Open problem for our §8.3 — we need to choose an aggregation rule consciously.

### **Dennis, Oesterheld, Conitzer, Critch, Russell (2025) — *How to Condition Behavior on Self-Referential Claims*** RLDM-25
- **Claim:** When agents read text describing themselves ("you are GPT-X, you should…"), behaviour depends on resolving self-reference. Open game-theoretic problem.
- **Stance:** **Relevant** to multi-agent settings where forked participants encounter self-descriptions; we should make sure `belief` and `identity` are not confused by self-reference. Not a current threat but a future one.

### **Lauffer, Shah, Carroll, Seshia, Russell, Dennis (2025) — *Robust and Diverse Multi-Agent Learning via Rational Policy Gradient*** NeurIPS-25
- **Claim:** A policy-gradient variant that *guarantees* diversity in the equilibrium population — relevant for ad-hoc teamwork (you want partners that span the strategy space, not collapse to one strategy).
- **Stance:** **Tooling** for populating a forked-room with diverse hypothetical partners in our §6.5 `simulate-reply` motif. If we want to imagine *how a diverse population would respond*, RPG gives us the prior.

### **Critch, Russell (2023) — *TASRA: A Taxonomy and Analysis of Societal-Scale Risks from AI*** [arXiv:2306.06924](https://arxiv.org/abs/2306.06924)
*File: `pdfs/critch-2023-tasra.pdf`*

- **Claim:** Risks should be taxonomised by *accountability* (whose actions, unified or not, deliberate or not). Identifies "unanticipated multi-agent interaction" as a major un-modelled risk class.
- **Method:** Conceptual taxonomy + scenario narratives.
- **Stance:** **Frames the safety case** for forkable multi-agent runtimes: if multi-agent interaction is itself a risk surface, then we want a substrate where one can *replay-and-fork* such interactions under controlled budget. This is what `hire`'s `:over-budget → :discarded` semantics buys: bounded exploration without commitment.

### Other notable Russell-lab outputs 2024-25
- **Plaut, Zhu, Russell (2025) — *Avoiding Catastrophe in Online Learning by Asking for Help*** ICML-25. A formal account of when an agent should yield control. Directly relevant to our `hire` "ask the parent" pattern.
- **Bailey, Ong, Russell, Emmons (2024) — *Image Hijacks: Adversarial Images can Control Generative Models at Runtime*** ICML-24. Multimodal injection at runtime; relevant to capability-policing inside sandboxes.
- **Wang, Gleave, …, Dennis, Levine, Russell (2023) — *Adversarial policies beat superhuman Go AIs*** ICML-23. Shows even superhuman policies have exploitable mode collapse — strong motivation for diversity (Lauffer 2025) and theory-of-mind-by-simulation in multi-agent settings.

### Active Russell PhD students (CHAI, May 2026 snapshot)
Cassidy Laidlaw (effective-horizon RL, assistance games), Micah Carroll (advised by Dragan+Russell, preference dynamics & recommender alignment), Erik Jenner (deception under partial observability), Jiahai Feng (mechanistic interp w/ Steinhardt), Niklas Lauffer (multi-agent coordination), Shreyas Kapur (program synthesis), Hanlin Zhu (RL theory), Benjamin Plaut (catastrophe avoidance), Davis Foote. Alumni: **Dylan Hadfield-Menell** (now MIT faculty, see §2), **Smitha Milli** (Meta Superintelligence Labs, recommender alignment), **Rohin Shah** (DeepMind AGI Safety leadership), **Andrew Critch** (independent, TASRA + ARCHES). Anca Dragan is Russell's closest faculty collaborator and now leads AGI Safety & Alignment at DeepMind alongside Shah and Dafoe.

---

## 2. Hadfield-Menell at MIT — assistance games → cooperative LLM agents

### **Smith, Trivedi, Clifton, Hammond, Khan, …, Leibo, Hadfield-Menell (2024) — *The Concordia Contest: Advancing the Cooperative Intelligence of Language Agents*** NeurIPS-24 Competition Track
- **Claim:** Evaluate LLM agents on *cooperative intelligence* in zero-shot mixed-motive natural-language environments: promise-keeping, negotiation, reciprocity, reputation, partner choice, compromise, sanctioning.
- **Method:** Concordia simulation environment (free-form natural language, multi-agent); contest with held-out scenarios; agents are LLM-based.
- **Evidence:** Empirical results across scenarios ranging from bilateral negotiation to collective-action problems.
- **Stance:** **Most direct empirical analog** to our discourse-model use cases. Concordia is the *single-room, single-trajectory* version of what we propose; our discourse algebra adds forking, populations, and explicit signals. Critically, **Concordia agents have to invent cooperation from scratch each scenario**; in our model, persistent `belief` signals and KB-backed personas (§5.6) carry reputation across rooms. Empirically: their leaderboards are the natural benchmark for any `align-on` or negotiation pattern we ship.

### **Trivedi, Khan, Clifton, Hammond, …, Hadfield-Menell, Leibo (2024) — *Melting Pot Contest: Charting the Future of Generalized Cooperative Intelligence*** NeurIPS-24 Datasets & Benchmarks
- **Claim:** Melting Pot evaluates *generalisation* of cooperative behaviour to novel social situations and unfamiliar partners.
- **Stance:** **Where MARL meets cooperative-AI.** ~50 substrates and ~256 scenarios; gold-standard benchmark for any cooperation claim. Our forkable rooms could host Melting Pot substrates as concrete dummy harnesses (cf. §9.1).

### **Siththaranjan, Laidlaw, Hadfield-Menell (2024) — *Distributional Preference Learning: Understanding and Accounting for Hidden Context in RLHF*** ICLR-24
- **Claim:** RLHF aggregates over *hidden* context (who labelled this? in what mood?); distributional preference learning models this latent structure.
- **Stance:** **Confirms** the need for our `belief` and `common-ground` signals to be *distributions*, not points. Their distributional account is what a participant's belief over the speaker should be.

### Hadfield-Menell's 2025 shift — capabilities-aware safety
- **Diverse Preference Learning** (ICLR-25), **Latent Adversarial Training** (TMLR), **Model Tampering Attacks** (TMLR), and **Pitfalls of Evidence-Based AI Policy** (ICLR-25 blog) collectively shift the lab's focus from "assistance games" toward *robust evaluation of LLM capabilities under adversarial / drift conditions*. Less directly relevant to our discourse layer, more relevant to "what should a `hire`-spawned sub-agent be trusted to do?"

---

## 3. Cooperative AI Foundation — the agenda paper of 2025

### **Hammond, Chan, Clifton, Hobbhahn, Yamin, …, et al. (2025) — *Multi-Agent Risks from Advanced AI*** Cooperative AI Foundation Technical Report #1 [arXiv:2502.14143](https://arxiv.org/abs/2502.14143)
*File: `pdfs/hammond-2025-multiagent-risks.pdf`*

- **Claim:** As LLM agents proliferate, *novel* risks emerge that single-agent safety frameworks miss. Three failure modes: **miscoordination** (cooperation fails despite shared interest), **conflict** (agents work at cross-purposes), **collusion** (agents cooperate against humans). Seven risk factors: information asymmetries, network effects, selection pressures, destabilising dynamics, commitment problems, emergent agency, multi-agent security.
- **Method:** Position paper, 50+ co-authors across DeepMind, Anthropic, CMU, Harvard. Each risk illustrated with real or experimental example.
- **Evidence:** Worked vignettes (market manipulation, autonomous-driver collusion, recommender races, etc.).
- **Stance:** **The frame paper for our problem space.** Their taxonomy maps cleanly onto our primitives:
  - *Miscoordination* → our `align-on` / `common-ground` signal exists exactly to expose and resolve this.
  - *Conflict* → forked-room speculation lets parents probe whether a sub-agent's plan conflicts with siblings *before* committing.
  - *Collusion* → our isolation property (forked participants share *spec* but get fresh state, §5.3) prevents covert state-channels between sub-agents by construction.
  - *Commitment problems* → fork+discard is a commitment-free move; the absence of side-effects in a discarded fork *is* the commitment mechanism.
- They have a taxonomy; we have a substrate that addresses several entries structurally. Synthesising the two is the obvious next step in our `doc/discourse-model.md`.

### Cooperative AI community contour
- **Lewis Hammond** — Research Director, CAIF. Multi-agent risks + cooperation under uncertainty.
- **Allan Dafoe** — Trustee; now Head of Frontier Safety & Governance, DeepMind. Wrote the 2021 *Cooperative AI* Nature piece that founded the field.
- **Edward Hughes** — Staff RE at DeepMind, CAIF advisor, PIBBSS mentor. Zero-shot human-AI cooperation, cultural evolution.
- **Joel Leibo** — Senior Staff RS DeepMind, Melting Pot lead, visiting prof KCL. **Reverse-engineering approach to multi-agent AGI.**
- **Jesse Clifton** — formerly NCAR/MIRI, now CAIF research. Open-source-game-theoretic agents.
- **Andrew Critch** — independent; ARCHES, TASRA, multi-multipolar dynamics; the MIRI-adjacent voice that publishes mainstream.

### Adjacent agenda paper: **Conitzer, Hadfield, Dafoe, et al. (2024) — *Collective Cooperative Intelligence*** PNAS
- **Claim:** Collective intelligence in mixed human-AI groups requires explicit institutional design (norms, voting, contracts) — not just better individual cooperators.
- **Stance:** Suggests our `room` is the right granularity (an institution), but motivates *named patterns* with explicit norms (`debate`, `panel`, `auction`) rather than ad-hoc participant soup. Worth a roadmap item.

---

## 4. Multi-Agent RL with LLMs — opponent shaping, ad-hoc teamwork

### Jakob Foerster (Oxford / Meta FAIR)
- **Khan, Willi, …, Foerster (2024) — *Scaling Opponent Shaping to High-Dimensional Games*** AAMAS-24. Opponent shaping = differentiating through the *learning step* of your opponent; previously only on tiny matrix games, now scaled.
- **2025+ — Opponent Shaping in LLM Agents** [arXiv:2510.08255]. First exploration of OS with LLM agents; traditional OS requires higher-order derivatives unavailable for closed LLMs, so they invent black-box variants.
- **Stance:** **The cleanest "shape your partner" recipe** in the literature. We don't differentiate through learning; we *fork-and-simulate*, which is a derivative-free analogue. Their work is the gradient-based counterpart to our `what-if` / `simulate-reply`. Both pursue the same end (steer co-evolution of behaviour) by orthogonal means.

### Peter Stone (UT Austin / Sony AI) — Ad-hoc teamwork
- **Wang, Rahman, Durugkar, Liebman, Stone (2024) — *N-Agent Ad-Hoc Teamwork*** NeurIPS-24. Multiple companies' independently trained agents must cooperate without pre-coordination — generalises 2-agent AHT.
- **Stone et al. — *Minimum Coverage Sets for Robust AHT*** AAAI-24. The minimum diversity of teammate models needed to guarantee robust play.
- **Stance:** **AHT is the formal cousin** of what our `hire` does — we spawn a sub-participant we may not have trained and need useful joint behaviour anyway. Stone's coverage-set result tells us *how many* diverse sub-agents we should ship in the default registry to span common collaboration modes. Concrete suggestion: a default `personas/` set with N=coverage-set agents.

### Joel Leibo / Edward Hughes (DeepMind) — n-player coordination
- **Melting Pot (2021–24)** + **InvestESG** (ICLR-25) — sustained benchmark line; Melting Pot Contest extended to **LLM agents** in 2024 (NeurIPS competition).
- **Resolving Social Dilemmas with Minimal Reward Transfer** (Hughes et al., 2024) — minimal side-payments suffice if agents are *prosocial*; relevant to our `align-on` aggregation rule.

### Natasha Jaques (UW Social RL lab, 2024)
- Social-influence-as-intrinsic-motivation (2018) extended to **LLM agents** post-2024. Lab founded 2024; multi-agent RLHF + cooperation. New EMNLP-24 work on *Moral Foundations of LLMs*.
- **Stance:** Closest analog to using LLMs as *prosocial cooperators*. Her causal-influence reward is a measurable proxy for "did this participant change others' beliefs?" — a candidate metric for the `common-ground` signal trajectory in our §8.2.

---

## 5. Theory of Mind in Multi-Agent RL — making the recursion operational

### **Cross, Xiang, Bhatia, Yamins, Haber (2024) — *Hypothetical Minds: Scaffolding ToM for Multi-Agent Tasks with LLMs*** [arXiv:2407.07086]
*File: `pdfs/cross-2024-hypothetical-minds.pdf`*

- **Claim:** A modular LLM agent — perception, memory, hierarchical planning, *and* a Theory-of-Mind module that **generates NL hypotheses about other agents' strategies and refines them by prediction accuracy** — beats both LLM and MARL baselines on Melting Pot, in competitive, cooperative, and mixed-motive scenarios.
- **Method:** ToM module = (hypothesis generator → hypothesis evaluator → refinement). High-level planner conditions on the currently-best hypothesis. Hypotheses are NL strings, not vectors.
- **Evidence:** Strictly beats LLM baselines on majority of evaluation scenarios; beats MARL on 3/4 even though MARL trained for 10⁹ steps.
- **Stance:** **Most direct prior art for our §6.5 thought experiments motif.** Their hypothesis-generation-and-refinement loop is a *single-agent* implementation of theory of mind. Our proposal: lift hypothesis evaluation from "predict observed action" (cheap, narrow) to "fork the room, instantiate the hypothesised participant, run it, observe outcome." That makes the hypothesis space the *substrate's* model of the other participant, not the LLM's verbal model. **What they have we don't:** a working Melting Pot agent demonstrating ToM-via-NL-hypotheses beats RL. **What we add:** running the hypothesis *in actual world dynamics* via fork is a strictly stronger evaluator than an LLM scoring action plausibility (cf. Kim 2025 ThoughtTracing in `README.md` §2). Pair the two.

### **Oguntola (CMU 2025) — *Theory of Mind in Multi-Agent Systems* (PhD dissertation)**
- ToM-as-intrinsic-motivation as a formal optimisation criterion for MARL agents to *develop* mentalising capacity without being told to.
- **Stance:** **Justifies architectural commitment.** If ToM emerges only when there is gradient pressure to model others, our discourse substrate must make modelling others *useful* (i.e., forks let you simulate and gain) rather than just *available*.

### Other notable
- **Hypothetical Minds → Sapir-Whorf? variants (2025)** — same group exploring how *language about minds* shapes the agent's policy.
- **Liu, Geng, Peterson, Sucholutsky, Griffiths (2024)** — *LLMs assume people are more rational than they really are* (covered in `README.md` §5). Cautionary: ToM-via-LLM-hypothesis tends to bias toward expected-value-maximising others; our fork-and-simulate moves the rationality assumption into the *participant spec*, not the LLM prior.

---

## 6. AI-safety relevance to dvergr's `hire` / budget / capability model

dvergr's `hire` primitive (`doc/discourse-model.md` §5.8) ships three structural commitments that this literature directly motivates:

1. **Capability ≈ SCI tool exposure.** Critch & Russell (TASRA) and Hammond et al. (CAIF-TR-1) both list *emergent agency* and *capability proliferation* as risk factors. Our coupling of capability to which namespaces are lifted into the sub-agent's SCI sandbox gives a *structural*, not policy-only, control surface. AssistanceZero (Laidlaw 2025) gives us the worked example: when the sub-agent is a *goal-uncertain planner*, capability gating prevents it from gaining tools by *asking the principal*.

2. **Budget exhaustion → discard, no state corruption.** Hammond et al. call out *commitment problems* — agents creating irreversible side-effects under uncertainty. Our `:over-budget → :discarded` semantics is a structural answer: a discarded fork *has no side-effects in the parent's world*. This is what makes speculative `hire` safe enough to make routine. Concretely, the fork-identity bisimulation (§5.4) is the formal statement that "discarded ≡ never happened" up to wall-clock cost.

3. **Forked sub-room cannot reach parent state.** *Collusion* (Hammond et al.) requires a covert channel; in our model sub-agents share only the spec, not the live state. Two hired sub-agents in sibling forks cannot influence each other unless the parent merges and re-spawns. This is the structural mitigation we should point to when answering "could your sub-agents collude?". It is *not* a complete answer — a malicious sub-agent could try to write collusion-inducing artefacts into the KB that survive merge — but it bounds the channel.

What we should *adopt* from this literature:
- An explicit `ExecApprovalRequirement` per capability (deferred Open Q in §5.8), inspired by Codex / CAIF-TR-1 §multi-agent-security. Distinguish *read* vs *write* vs *exec* at the namespace level.
- A *coverage set* (Stone, AAAI-24) of default personas for ad-hoc collaboration — so that `hire` without spec falls back to a deliberately diverse set, not a single default.
- Hammond et al.'s seven risk factors as a *checklist* for any new pattern (`debate`, `panel`, `auction`) added to our combinator library.
- **DR-MDP-style audit** (Carroll 2024) of `align-on`: does our consensus-detection have the property that it implicitly rewards persuaders for *changing minds* in ways the persuadees would not endorse?

---

## 7. Contrast with our discourse model

**What this literature has that we don't (yet):**
- **Rigorous coordination & credit-assignment theory.** AHT (Stone), CIRL/AssistanceZero (Russell), opponent shaping (Foerster), reciprocity-with-minimal-transfer (Hughes), distributional preference learning (Siththaranjan/Hadfield-Menell) — every one of these is a *theorem-grade* statement about when cooperation is recoverable. Our discourse algebra has *laws* (§7) but does not yet derive a coordination theorem.
- **Benchmarks.** Melting Pot, Concordia, BigToM, the Risky-Gamble dataset, the Habermas Machine eval. We have a sketch of testing in §9 but no canonical scoreboard.
- **Empirical evidence that LLM agents fail at multi-agent cooperation by default** (CAIF-TR-1, Concordia-24 leaderboards). This is our motivation surface, but we are not yet running on it.
- **DR-MDP** (Carroll 2024) is a sharper formal account of preference dynamics than our `belief` signal currently provides; we should refactor §8.1 to reference it.

**What we have that they don't:**
- **A forkable runtime as first-class primitive.** All the papers in §1–5 *describe* simulations or counterfactual rollouts — they do not have an industrial substrate (spindel + yggdrasil + datahike branching + SCI) where forking a *room* forks every system *atomically* and the cost is O(1) CoW. Hypothetical Minds and ThoughtTracing both implement their counterfactual reasoning inside the LLM. We push it down into the runtime.
- **Theory of mind via fork-and-simulate, not via LLM hypothesis-scoring.** §6.5 of `doc/discourse-model.md`. Cross 2024 evaluates hypotheses by *predicted action plausibility*; we propose evaluating them by *running the hypothesised participant in a forked room and observing real consequences in the substrate*. This is a strictly stronger evaluator — exact for game-dynamic claims like "if I propose X, they will counter with Y, then I respond Z" — because we are *not* approximating the response model, we are running it.
- **Unified substrate across "harness developer composes" and "agent composes inside SCI".** None of the cooperative-AI papers offer an API where an *LLM agent* can write code that hires sub-agents in forked rooms with the same primitives the human developer uses. AssistanceZero solves *one* assistance game; we propose a substrate where the assistant *can hire its own assistants*.
- **Persistent belief / common-ground signals.** Concordia agents have no cross-scenario reputation. Our §8 signals + KB-backed personas (§5.6) give us a substrate where *reputation is a first-class signal*.

**The natural meeting point.** Concordia is to our discourse model as ProbComp's MSA is to our population SMC — they have proved out the *one-shot* version of what we propose as a *continuous, forkable, populated* substrate. The right next-step experiment is:

> *Run Concordia scenarios in dvergr, treating each scenario as a `room`, the LLM agents as participants, and adding fork-and-simulate as the only new capability. Does AHT performance improve when the agent can fork-room before committing?*

If yes, we have an empirical demonstration of the discourse model's leverage point. If no, we learn precisely where forking *doesn't* substitute for trained cooperation. Either way the result is publishable into the Cooperative AI venue.

---

## 8. Reading order

For someone coming from `README.md` (the MIT/Stanford ProbComp axis) into this Russell/cooperative axis:

1. **Hammond et al. 2025 (CAIF-TR-1)** — frame paper; risk taxonomy.
2. **Laidlaw et al. 2025 (AssistanceZero)** — the worked single-assistant story; CIRL realised.
3. **Cross et al. 2024 (Hypothetical Minds)** — ToM-by-LLM-hypothesis as the closest prior art for §6.5.
4. **Carroll et al. 2024 (Influenceable Rewards)** — formal warning about `align-on` going wrong.
5. **Critch & Russell 2023 (TASRA)** — the broad safety frame; multi-agent risk before LLMs.
6. **(Optional) Smith/Trivedi et al. 2024 (Concordia Contest)** — the benchmark we should target.

PDFs 1–5 are in `./pdfs/` as `hammond-2025-multiagent-risks.pdf`, `laidlaw-2025-assistancezero.pdf`, `cross-2024-hypothetical-minds.pdf`, `carroll-2024-influenceable-rewards.pdf`, `critch-2023-tasra.pdf`.
