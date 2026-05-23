# Related Work — Multi-Agent Linguistic FRP vs. the Probabilistic-Programs-as-Cognition Agenda

> Compiled 2026-05-23 for contrast against `doc/discourse-model.md`.
> Scope: MIT (ProbComp / CoCoSci), Stanford (Goodman), Princeton (Griffiths), and adjacent collaborators, 2023–2025.

Four research programmes are now visibly converging on the same big bet:
**"natural language interfaces a probabilistic-program runtime that does the actual reasoning."**
This file surveys their recent agenda papers, identifies the load-bearing technical commitments, and contrasts them to our discourse design.

The PDFs referenced below are in `./pdfs/`. Citations follow the `firstauthor-year` form used in the filename.

---

## 1. Agenda papers (the rethinking-PPL-with-LLMs framing)

### **Wong, Grand, Lew, Goodman, Mansinghka, Andreas, Tenenbaum (2023) — *From Word Models to World Models: Translating from Natural Language to the Probabilistic Language of Thought*** [arXiv:2306.12672](https://arxiv.org/abs/2306.12672)
*File: `pdfs/wong-2023-word-to-world-models.pdf`*

- **Claim:** Linguistic meaning is a context-sensitive mapping from natural language into a *probabilistic language of thought* (PLoT). Thought is not the LLM; thought is the probabilistic program the LLM produces.
- **Method:** "Rational meaning construction": LLM as a *meaning function* translating utterances into church-style probabilistic-programming code; standard Bayesian inference over the resulting program produces the prediction or judgment.
- **Evidence:** Four worked domains — probabilistic reasoning, logical/relational, intuitive physics, social reasoning about agents and their plans. The LLM-as-translator + PPL-as-engine division beats LLM-only on every case.
- **Philosophical thrust:** LLMs are statistical-pattern surfaces over language; *meaning lives in the structured world model the language denotes*. The roadmap is neurosymbolic: keep the LLM for breadth, put the symbolic substrate (PPL) underneath for coherence.
- **Stance:** **Confirms our discourse model** at the philosophical level (language as interface to a probabilistic runtime) and **extends it** with concrete PLoT machinery we don't compete with. We are the multi-agent + continuous-time *outer* layer; their PLoT is what a single participant's `belief` signal can be made of. They co-author Mansinghka and Tenenbaum, so this is the joint agenda paper of both MIT groups.

### **Collins, Sucholutsky, Bhatt, Chandra, Wong, Lee, Zhang, Zhi-Xuan, Ho, Mansinghka, Weller, Tenenbaum, Griffiths (2024) — *Building Machines that Learn and Think with People*** [arXiv:2408.03943](https://arxiv.org/abs/2408.03943)
*File: `pdfs/collins-2024-thought-partners.pdf`*

- **Claim:** The next scaling axis is not bigger LLMs, it is *thought partners* that maintain explicit Bayesian models of (a) the user and (b) the world, and reason over them.
- **Method:** A Perspective paper laying out criteria (understand us / be understandable / share enough world to ground common reasoning) and modes of collaborative thought (interrogator, teacher, sounding-board, etc.). Concrete proposals point to PPLs, goal-directed search, and explicit ToM models.
- **Evidence:** Position paper backed by computational-cognitive-science motifs and worked case studies (programming, medicine, storytelling, embodied assistance).
- **Philosophical thrust:** Bayesian models *of the user* and *of the world* are the unit of intelligence; LLMs are one — not the only — implementation substrate.
- **Stance:** **Confirms** our design strongly. The diagrams in §2 ("beliefs about machine, beliefs about Alice, beliefs about world") are exactly our `belief` and `common-ground` signals. They argue for it at the level of a research agenda; we are building the substrate that makes it cheap to compose. Co-authored by all four labs.

### **Grand, Tenenbaum, Mansinghka, Lew, Andreas (2025) — *Self-Steering Language Models* (DisCIPL)** [arXiv:2504.07081](https://arxiv.org/abs/2504.07081)
*File: `pdfs/grand-2025-self-steering-discipl.pdf`*

- **Claim:** LMs can generate their *own* inference programs to coordinate populations of smaller LMs via SMC. Reasoning becomes a Planner-LM-written program executed by Follower LMs in parallel.
- **Method:** Planner LM emits a probabilistic program with `sample`/`observe` over Follower LMs; runtime executes it as SMC; Followers can be tiny (Llama-3.2-1B) and still beat o1 on constrained generation.
- **Evidence:** COLLIE + custom PUZZLES (poetry/grant-writing/itinerary) benchmark; small Followers match GPT-4o and o1 on hard constraints.
- **Philosophical thrust:** Test-time scaling is not "longer chain-of-thought" — it is "let the model write the inference program." Probabilistic programming is the test-time-compute API.
- **Stance:** **Extends and aligns with us** — most direct prior art for "agents writing inference algorithms inside an SCI sandbox" (our §12 reflectivity). They run it at token level; we run the same shape at utterance / room level. Their Planner ≈ our agent inside SCI calling `(smc-discourse …)` or `(what-if …)`.

### **Wong, Collins, Ying, Zhang, Weller, Gerstenberg, O'Donnell, Lew, Andreas, Tenenbaum, Brooke-Wilson (2025) — *Modeling Open-World Cognition as On-Demand Synthesis of Probabilistic Models* (MSA)** [arXiv:2507.12547](https://arxiv.org/abs/2507.12547)
*File: `pdfs/wong-2025-open-world-cognition-msa.pdf`*

- **Claim:** People don't have one big Bayesian model of the world; they *synthesise* a small task-specific one on demand, then reason inside it.
- **Method:** Model Synthesis Architecture: LM-guided relevance retrieval → LM-guided probabilistic-program synthesis → standard Bayesian inference. Sequential staged synthesis so later generation focuses on programs already scoring well under earlier evaluation.
- **Evidence:** "Model Olympics" sports-vignette benchmark with novel causal structure and unseen variables; MSA beats LM-only and pure PPL baselines on three experiments of increasing open-endedness.
- **Philosophical thrust:** Bridges Bayesian modelling and LLMs by making *model construction itself* the LM's job. Open-world coherence comes from disposable, locally-coherent models.
- **Stance:** **Strongly aligned**: this is exactly the picture of `fork-room + ad-hoc participant + discard` for an imagined deliberation. Their MSA is the *single-shot* version of our forkable discourse — they synthesise one model per question; we let a population synthesise many in parallel via `smc-discourse`. **Extends** our `what-if` to model-synthesis-not-just-utterance-sim.

---

## 2. Mansinghka group — follow-ups since LLaMPPL

### **Lew, Zhi-Xuan, Grand, Mansinghka (2023) — *Sequential Monte Carlo Steering of LLMs using Probabilistic Programs* (LLaMPPL)** [arXiv:2306.03081](https://arxiv.org/abs/2306.03081)
*File: `pdfs/lew-2023-llamppl-smc-steering.pdf`*

- **Claim:** Constrained / steered LLM generation is best framed as posterior inference in a *Feynman–Kac Transformer model*, not as beam search or token masking.
- **Method:** Feynman–Kac Transformer = (initial state, Markov-kernel-from-LM, potential-function); SMC steering = without-replacement particle resampling with activation caching; LLaMPPL = Python PPL on top.
- **Evidence:** Infilling, hard syntactic constraints, prompt intersection — at cost similar to beam search.
- **Philosophical thrust:** Decoding is inference; inference is the API.
- **Stance:** **The substrate we cite as [1] in `discourse-model.md`** — we adopt its async-batching and without-replacement-resampling recipes for our §10 inference layer. We are the *utterance-and-room-level* lift of the same FK reading.

### **Zhi-Xuan, Ying, Mansinghka, Tenenbaum (2024) — *Pragmatic Instruction Following and Goal Assistance via Cooperative Language-Guided Inverse Planning* (CLIPS)** [AAMAS 2024](https://www.ifaamas.org/Proceedings/aamas2024/pdfs/p2094.pdf)
*File: `pdfs/zhuxuan-2024-clips-pragmatic-instruction.pdf`*

- **Claim:** A pragmatic instruction-follower must do *multimodal Bayesian inference* over a goal: actions + utterances jointly evidence the goal under a model of the human as a cooperative planner.
- **Method:** Two-player Markov game, LLM scores `P(utterance | plan)`, SMC inverse-plan-search computes the goal posterior; assistant acts to minimise expected goal-cost.
- **Evidence:** Doors-Keys-Gems + VirtualHome — CLIPS outperforms GPT-4V, literal LLM-following, unimodal inverse planning; tracks human-rater judgments.
- **Philosophical thrust:** RSA is too thin for instruction-following; you need a planner under the hood. LLM = utterance likelihood, planner = policy, Bayes = the glue.
- **Stance:** **Confirms** our coalgebraic participant view (§5.4) — the human and the assistant are exchangeable participants in a joint plan. **Different scale**: they target *one-shot* assistance in a known world; we want *continuous* multi-participant conversation. Their CLIPS could be the internal reasoning loop of one of our `agent` participants.

### **Ying, Zhi-Xuan, Wong, Mansinghka, Tenenbaum (2024) — *Understanding Epistemic Language with a Language-augmented Bayesian Theory of Mind* (LaBToM)** [arXiv:2408.12022](https://arxiv.org/abs/2408.12022)
*File: `pdfs/ying-2024-labtom-epistemic-language.pdf`*

- **Claim:** Statements like "Alice thinks the key might be in box 2" should be interpreted via Bayesian ToM inferences over inverse planning, against an *epistemic language of thought* (ELoT) parsed from natural language.
- **Method:** Grammar-constrained LLM decoding into ELoT formulas; BToM module (inverse planning) produces a posterior over beliefs/goals; statement plausibility = expectation of the ELoT formula under that posterior.
- **Evidence:** Doors-Keys-Gems maze experiments; correlates with humans across modals, knowledge claims, false-belief attributions; beats GPT-4o and Gemini Pro.
- **Philosophical thrust:** Belief-talk is interpreted by *running* a ToM model; LLMs alone cannot ground epistemic modals reliably.
- **Stance:** **Strong validation** of our `belief` signal (§8.1) being a *first-class* object, not a prompt-implicit one. Our model says belief is a participant signal; theirs is the algorithmic recipe for what it should encode and how to evaluate epistemic claims against it. **Could plug into** our `with-belief` wrapper.

### **Kim, Sclar, Zhi-Xuan, Ying, Levine, Liu, Tenenbaum, Choi (2025) — *Hypothesis-Driven Theory-of-Mind Reasoning for LLMs* (ThoughtTracing)** [arXiv:2502.11881](https://arxiv.org/abs/2502.11881)
*File: `pdfs/kim-2025-thought-tracing-tom.pdf`*

- **Claim:** ToM can be done at inference time by running an SMC-style filter over LLM-generated natural-language hypotheses about a target agent's mental state.
- **Method:** Parse a story into (state, action) trajectory; at each step initialise / propagate / weight / resample / rejuvenate a particle set of NL hypotheses about beliefs; weights come from LLM-scored action likelihoods.
- **Evidence:** Four ToM benchmarks; outperforms o3-mini and R1 with shorter traces.
- **Philosophical thrust:** ToM is not chain-of-thought; ToM is *belief tracking under uncertainty*. SMC over hypotheses is the right shape.
- **Stance:** **Direct prior art** for our `belief` signal update under inbox deltas, and for what a participant's "thinking between turns" could look like internally. Same SMC-of-hypotheses pattern we propose for `smc-discourse`, one level down (per-participant rather than per-room).

### **Becker, Lew, Wang, Ghavami, Huot, Cusumano-Towner, Mansinghka (2024) — *Probabilistic Programming with Programmable Variational Inference*** PLDI 2024
- **Claim/Method:** Programmable VI as a first-class PPL feature.
- **Stance:** Substrate work we'd build *on top of*, not in parallel. Same authors / same group as LLaMPPL.

---

## 3. Tenenbaum group — world models, language of thought

### **Chandra, Chen, Tenenbaum, Ragan-Kelley (2025) — *A Domain-Specific PPL for Reasoning about Reasoning (Or: A Memo on memo)*** [OOPSLA 2025 / DOI:10.1145/3763078](https://doi.org/10.1145/3763078)
*File: `pdfs/chandra-2025-memo-ppl-reasoning.pdf`*

- **Claim:** Recursive Bayesian ToM models in general-purpose PPLs are bug-prone (cross-agent variable leaks) and slow. A DSL with theory-of-mind-native syntax + array-programming compilation fixes both.
- **Method:** `memo` language — primitives like `believe`, `think`, `see`, `do`, `want`, `imagine`; compiler emits parallel array code; inference is end-to-end differentiable.
- **Evidence:** Classic recursive-rationality models reimplemented; dramatic speed/LOC wins; adopted by multiple labs.
- **Philosophical thrust:** "Recursive rationality" deserves a programming paradigm of its own.
- **Stance:** **Most relevant existing language design** to our discourse algebra. `memo`'s primitives (`believe`, `think`, `imagine`) sit inside *one* mind; our `simulate-reply` / `what-if` / `align-on` (§6.5) compose multiple minds plus their substrate (datahike, KB, time). **Complementary**: a `memo` program could be the body of a participant's `belief` updater.

### **Chandra, Chen, Tenenbaum et al. (2024) — *Storytelling as Inverse Inverse Planning*** Topics in Cognitive Science
- **Claim:** Storytelling is the dual of ToM: a storyteller picks a sequence of events so that the reader's inverse-planning recovers an intended interpretation. Differentiable probabilistic programs make this optimisable.
- **Stance:** Beautiful conceptual extension; relevant to our "speaker chooses utterance by predicted listener belief update" pattern (§6.5 RSA hook).

### **Wong et al. (2025) MSA** — covered as agenda paper above.

---

## 4. Goodman — RSA, pragmatics, social reasoning, language-of-thought-style work in LLMs

### **Gandhi, Fränken, Gerstenberg, Goodman (2023) — *Understanding Social Reasoning in LLMs with LLMs* (BigToM)** [arXiv:2306.15448](https://arxiv.org/abs/2306.15448)
*File: `pdfs/gandhi-2023-bigtom-social-reasoning.pdf`*

- **Claim:** ToM benchmarks should be procedurally generated from *causal templates* (percepts, beliefs, desires, actions, causal events) so we can isolate which inference fails.
- **Method:** Template → populate → instantiate Forward Belief / Forward Action / Backward Belief tasks; 25 controls × 5000 model-written items.
- **Evidence:** GPT-4 mirrors human inference patterns but is brittle; other LLMs struggle, especially on backward belief.
- **Philosophical thrust:** Don't trust ToM scores without controls. Causal-graph templating is the right way to evaluate "social reasoning."
- **Stance:** **Methodological backbone** for our §9 testing strategy. Their causal templates are the script-participant generators we'd write to exercise multi-participant ToM in a `room`.

### **Gandhi, Chakravarthy, Singh, Lile, Goodman (2025) — *Cognitive Behaviors that Enable Self-Improving Reasoners (Four Habits of Highly Effective STaRs)*** [arXiv:2503.01307](https://arxiv.org/abs/2503.01307)
*File: `pdfs/gandhi-2025-cognitive-behaviors-stars.pdf`*

- **Claim:** Which base LMs benefit from RL self-improvement depends on whether they *already* exhibit four cognitive behaviours: verification, backtracking, subgoal-setting, backward chaining. Priming Llama with these behaviours lets it match Qwen.
- **Method:** Behavioural-pattern analysis on Countdown RL traces; priming with synthetic exemplars + filtered OpenWebMath continued pretraining.
- **Stance:** **Loosely aligned, complementary**. Their "backtracking" is, at the substrate level, our `fork-room → discard`; their "verification" maps to a `critic` participant in a `iterative-refinement` pattern. We expose these behaviours as composable algebra; they show empirically why a model that *has* them benefits from extra compute.

### **Prystawski, Li, Goodman (2023, NeurIPS Oral) — *Why Think Step by Step? Reasoning Emerges from the Locality of Experience*** [arXiv:2304.03843](https://arxiv.org/abs/2304.03843)
*File: `pdfs/prystawski-2023-why-step-by-step.pdf`*

- **Claim:** CoT reasoning helps precisely when direct conditioning on training distributions is biased; chaining locally-experienced inferences recovers the true distribution.
- **Stance:** Theoretical justification for why decomposed (multi-participant) reasoning over a chain of utterances can outperform single-turn — relevant to our `pipeline` / `debate` motifs.

### **Wong (Stanford alum) et al. (2025) Open-World MSA** — covered above; Wong is now Stanford-affiliated.

### Also worth flagging (Goodman 2024–25, see CocoLab list):
- **"Bayesian scaling laws for in-context learning"** (Arora, Jurafsky, Potts, Goodman, COLM 2025) — ICL behaves as Bayesian model averaging.
- **"BoxingGym"** (Gandhi et al., NeurIPS workshop 2025) — automated experimental design & model discovery, RSA-style scoring of scientific hypotheses.
- **"Stream of Search"** (Gandhi, Lee, Grand, …, Goodman, COLM 2024) — fold tree-search into the language model itself.
- **"Hawkins, Tsvilodub, Bergey, Goodman, Franke (2025) — Relevant answers to polar questions"** Phil. Trans. B — Hawkins's continuation of RSA-style pragmatic-coordination work.

---

## 5. Griffiths — Bayesian models of cognition + LLMs

### **Liu, Geng, Peterson, Sucholutsky, Griffiths (2024) — *Large Language Models Assume People are More Rational than We Really Are*** [arXiv:2406.17055](https://arxiv.org/abs/2406.17055)
*File: `pdfs/liu-2024-llms-rational-than-we-are.pdf`*

- **Claim:** When simulating or predicting people, frontier LLMs (GPT-4o/4-Turbo, Llama-3-8B/70B, Claude 3 Opus) act like expected-value maximisers — much more rational than humans are.
- **Method:** Risky-choice gamble dataset (n=13k) for forward modelling; Jern-style inference task for inverse modelling.
- **Evidence:** Spearman ρ(LLM, EV) = 0.94 vs ρ(human, EV) = 0.48. LLMs *interpret* others as rational too, matching the human inferential bias.
- **Stance:** **Implication for our `belief` model:** if we use LLMs to fill in `belief` signals, the modelled-other will be miscalibrated toward rationality. We should *bake bounded-rationality into the prior of the agent's belief* rather than expect LLMs to recover it. Relevant to §13 Open Q1.

### **Binz, Akata, Bethge, Brändle, …, Griffiths, Schulz et al. (2024) — *Centaur: A Foundation Model of Human Cognition*** [arXiv:2410.20268](https://arxiv.org/abs/2410.20268) → Nature 2025
*File: `pdfs/binz-2024-centaur-foundation-cognition.pdf`*

- **Claim:** Fine-tune a frontier LM on Psych-101 (10M trial-level human choices across 160 experiments) and you get a single model that predicts held-out human behaviour better than bespoke cognitive models, and generalises to new stories / structures / domains.
- **Method:** LoRA fine-tuning of Llama-3 on Psych-101 (60k participants, every experiment expressed as natural language).
- **Evidence:** Better than every per-task cognitive model in 159/160 experiments; internal representations more brain-aligned post-finetune.
- **Stance:** **Different programme from ours**: they want a *single model* of human cognition. We want a *substrate* on which models compose. Could play the role of a `:human` participant's policy in our rooms.

### **Zhu, Yan, Griffiths (2024/25, ICLR 2025) — *Language Models Trained to do Arithmetic Predict Human Risky and Intertemporal Choice*** [arXiv:2405.19313](https://arxiv.org/abs/2405.19313)
*File: `pdfs/zhu-2024-arithmetic-gpt-human-choice.pdf`*

- **Claim:** A tiny LM pretrained only on ecologically-valid arithmetic predicts human risky/intertemporal choice better than classic cognitive models. The mechanism that an LM and a rational agent must both master (EV arithmetic) suffices.
- **Stance:** Methodological: when the *task ecology* is right, small bespoke LMs explain humans. Implication for us: cheap specialised participants beat one generalist for many roles.

### Also worth flagging (Griffiths 2023–25):
- **"Meta-Learned Models of Cognition"** (Binz, Dasgupta, Jagadish, Botvinick, Wang, Schulz, 2023, BBS).
- **"Meta-learning ecological priors from LLMs (ERMI)"** (Jagadish, Binz, Saanum, Wang, Schulz, 2024–25).
- **"Rational Metareasoning for LLMs"** (2024).
- Lieder & Griffiths *resource-rational* programme: cited heavily in MSA above as the analytical lens for "useful model under bounded compute."

---

## 6. Cross-cutting themes (synthesis)

**Theme 1 — Language interfaces a probabilistic-program runtime.**
Wong-Grand-2023 and Wong-2025 say it explicitly; LLaMPPL, DisCIPL, CLIPS, LaBToM, ThoughtTracing and `memo` each implement a different slice of it. The shared move: **stop using the LM as the reasoner; use it as a generator of structured inference artefacts (programs, hypotheses, formulas, plans) and let a Bayesian engine do the reasoning.** This is the central commitment of all four labs.

**Theme 2 — SMC is the dominant inference shape.**
LLaMPPL (token-level), CLIPS (inverse plan over actions+utterances), ThoughtTracing (NL-hypothesis particles over mental states), DisCIPL (Planner-written SMC over a Follower population). Multi-particle weighted-resampling is the workhorse of test-time inference at all scales. Our `smc-discourse` puts the same machinery one level higher — over rooms.

**Theme 3 — Bayesian Theory of Mind is being operationalised.**
CLIPS, LaBToM, ThoughtTracing, `memo`, BigToM, Liu-2024-rationality and Storytelling-as-Inverse-Inverse-Planning all treat ToM as **explicit Bayesian inference over a structured generative model of the other**, with LLMs as either utterance-likelihood functions or hypothesis generators. The community has decisively moved on from "does the LLM have ToM?" benchmarks to "give the LLM a ToM engine and inference loop."

**Theme 4 — World models are bespoke, not monolithic.**
The MSA paper crystallises a view shared (implicitly) across the agenda: useful Bayesian models are *small*, *ad-hoc*, *constructed-on-demand*, and *thrown away*. This matches our forkable-room intuition exactly.

### Where our discourse design agrees, extends, differs, complements

**We share:**
- The FK / SMC framing of inference. Our citation chain matches theirs (Del Moral, Lew-2023). Theirs is single-mind / single-context; ours lifts it to multi-participant rooms.
- The view that ToM is operational and needs an explicit `belief` representation (§8). LaBToM and ThoughtTracing are direct evidence we are right to make it a first-class signal.
- The neurosymbolic split: LLM for breadth, structured runtime for coherence. Our participants embed LLM calls; the discourse algebra is the structured runtime.
- Reflectivity / self-steering: DisCIPL is the closest published analog of our §12 (agent writes the inference program from inside the substrate).

**We extend:**
- **Continuous-time multi-agent.** Every one of these papers is single-agent (with environment) or single-mind-reasoning-about-other-minds-as-objects. Nobody else models the *full population of conversational participants* as a coalgebraic system with mailbox-driven spins (§5.1).
- **Forkable runtime with resumable checkpoints.** MSA, CLIPS, ThoughtTracing all "imagine" by re-running from scratch; we fork the *runtime* and can resume. Our §6.5 `simulate-reply` / `what-if` is operationally cheaper.
- **Bisimulation as the equivalence relation** (§5.4). Nobody else uses it; it gives us surrogate ↔ virtual substitutability, script ↔ production substitutability, and grounds the fork-identity law.
- **Reflective SCI sandbox.** DisCIPL self-steers; we let *every* participant compose the same algebra (§12). The same `(iterative-refinement c r m)` runs whether typed by a developer or by an agent inside the sandbox.
- **Discourse-level Feynman–Kac.** Theirs is FK on tokens (LLaMPPL) or on a single agent's mental state (ThoughtTracing). Ours is FK on rooms-as-particles (§10).

**We differ:**
- **Language stack.** Their PPL is Python — Gen, NumPyro, PyMC, `memo`, LLaMPPL. Ours is Clojure + spindel + raster + datahike. Cost: smaller ecosystem. Benefit: forkable execution context as a *value*, persistent reactive signals, datahike branch-and-merge integrated with the runtime, SCI for reflection.
- **Granularity.** They optimise the *token* (LLaMPPL, DisCIPL) or the *single inferential step* (CLIPS, LaBToM, ThoughtTracing, `memo`). We optimise the *conversational turn* and the *room trajectory*.
- **Persona persistence.** Nobody else has long-running personas with goals, KBs, cadence, and source subscriptions across reboot (§5.6). The MSA and memo programmes are stateless / one-shot.

**We complement:**
- A participant in our discourse can use LLaMPPL internally for constrained token generation.
- A participant's belief updater can be a `memo` program.
- An MSA-style on-demand model synthesis is exactly what a `hire`d sub-participant could be tasked with (§5.8).
- A debate participant could call CLIPS for predicting how another participant interprets the next utterance.
- ThoughtTracing-style NL-hypothesis SMC is one realisation of `ParticipantBelief` (§13 Open Q1).

---

## 7. Reading list — papers we should actually read in full

Ranked by relevance to the discourse-model design. All paths absolute.

1. **`/home/christian-weilbach/Development/dvergr/doc/related-work/pdfs/wong-2023-word-to-world-models.pdf`** — the agenda paper. Read sections 1, 2, 6, 7 (language to world model, social reasoning, conclusion).
2. **`/home/christian-weilbach/Development/dvergr/doc/related-work/pdfs/grand-2025-self-steering-discipl.pdf`** — closest published shape to "agents write inference programs in the sandbox" (§12). Read all of it, it's compact.
3. **`/home/christian-weilbach/Development/dvergr/doc/related-work/pdfs/wong-2025-open-world-cognition-msa.pdf`** — on-demand model synthesis is the single-shot version of our forkable discourse. Read intro + MSA section.
4. **`/home/christian-weilbach/Development/dvergr/doc/related-work/pdfs/ying-2024-labtom-epistemic-language.pdf`** — concrete recipe for what our `belief` signal could be. Read intro + model section.
5. **`/home/christian-weilbach/Development/dvergr/doc/related-work/pdfs/kim-2025-thought-tracing-tom.pdf`** — SMC over NL hypotheses for ToM; closest analog to per-participant belief tracking.
6. **`/home/christian-weilbach/Development/dvergr/doc/related-work/pdfs/lew-2023-llamppl-smc-steering.pdf`** — the FK formalism we already cite as [1]; re-read §2 for the formal setup.
7. **`/home/christian-weilbach/Development/dvergr/doc/related-work/pdfs/chandra-2025-memo-ppl-reasoning.pdf`** — DSL for recursive rationality; valuable as a contrasting language-design choice. Read §1–2 + case studies.
8. **`/home/christian-weilbach/Development/dvergr/doc/related-work/pdfs/zhuxuan-2024-clips-pragmatic-instruction.pdf`** — joint multimodal Bayesian inference over plans+utterances; relevant to RSA hook (§6.5).
9. **`/home/christian-weilbach/Development/dvergr/doc/related-work/pdfs/collins-2024-thought-partners.pdf`** — the cross-lab Perspective. Read in full as a position statement we want to *cite and build on*.
10. **`/home/christian-weilbach/Development/dvergr/doc/related-work/pdfs/liu-2024-llms-rational-than-we-are.pdf`** — concrete reason to bake bounded rationality into belief priors.

### Bonus / deprioritised
- **`gandhi-2023-bigtom-social-reasoning.pdf`** — methodological backbone for ToM evaluation; use when designing §9 ToM test cases.
- **`binz-2024-centaur-foundation-cognition.pdf`** — interesting but tangential to substrate design.
- **`zhu-2024-arithmetic-gpt-human-choice.pdf`** — interesting "small model trained on the right thing" anecdote.
- **`gandhi-2025-cognitive-behaviors-stars.pdf`** — operational evidence for verification/backtracking primitives.
- **`prystawski-2023-why-step-by-step.pdf`** — theoretical justification for decomposed reasoning.

---

## 8. Fetch / verification notes

All 15 PDFs in `./pdfs/` downloaded successfully on 2026-05-23, including the OOPSLA `memo` PDF (initially failed via ACM; fetched from MIT DSpace on retry). No paper claimed in the report is unverified — every cited title was located via arXiv ID, conference proceedings, or lab publication page.

Items we found but did *not* fetch because they were sufficiently summarised in lab listings:
- *Probabilistic Programming with Programmable Variational Inference* (Becker, Lew, Mansinghka et al., PLDI 2024) — substrate-level work; cited but not central.
- *Storytelling as Inverse Inverse Planning* (Chandra et al., 2024) — referenced through the `memo` paper.
- *Bayesian scaling laws for in-context learning* (Arora, Jurafsky, Potts, Goodman, 2025) and *BoxingGym* (Gandhi et al., 2025) — interesting follow-ups in the Goodman lab but not load-bearing for our design.
- Habermas Machine Science paper (Tessler et al., 2024, [doi:10.1126/science.adq2852](https://www.science.org/doi/10.1126/science.adq2852)) — already cited in `discourse-model.md` [5]; behind paywall, abstract sufficed for confirming the `align-on` framing.
