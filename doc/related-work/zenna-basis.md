# Zenna Tavares & Basis Research Institute — Related-Work Brief

> Compiled 2026-05-23. Scope: Zenna Tavares' 2023–2025 trajectory and the Basis Research Institute (basis.ai / basis-research.org) NYC org he co-founded with Eli Bingham and Emily Mackevicius. Read against `discourse-model.md` and the broader related-work survey in `README.md` (the MIT-ProbComp / CoCoSci / Stanford-Goodman cluster).
>
> PDFs referenced are in `./pdfs/` with the `zenna-basis_*` prefix.

---

## 1. Zenna's recent trajectory (2023–2025)

Tavares' through-line since his MIT-CSAIL postdoc with Solar-Lezama and Tenenbaum has been **causal probabilistic programming as a substrate for "world-model induction"** — building runtimes in which causal counterfactual queries (do-calculus, `omega`/OMEGAC, the Random Conditional Distribution) are first-class, and using program synthesis to *discover* the causal generative model itself rather than handwriting it. The POPL 2023 paper *Combining Functional and Automata Synthesis to Discover Causal Reactive Programs* (with Ria Das, Tenenbaum, Solar-Lezama) is the canonical version of this commitment: take a stream of grid-world observations and synthesise an Autumn program that explains the dynamics. Autumn (the reactive DSL) is the engine, synthesis is the learning algorithm, causal counterfactuals are the queries you can then answer. He left his Columbia Zuckerman / DSI position (he had been the inaugural Alan Kanzer Innovation Scholar) to co-found Basis, holds a 2024–25 Visiting Scientist appointment at RIKEN Center for Brain Science, and now publishes almost exclusively under the Basis affiliation.

The 2024–2025 trajectory is a **deliberate pivot from "causal PPL for static problems" to "world-model induction for agents."** Three throughlines: (i) *Benchmarking world-model learning* — the WorldTest / AutumnBench effort (`zenna-basis_benchmarking-world-model-learning_2025.pdf`, arXiv 2510.19788, with Warrier, Nguyen, Naim, Liang, Schroeder, Yang, Tenenbaum, Vollmer, Ellis) which builds Autumn-based environments where you can score agents on *prediction, planning, and change detection* under reward-free interaction, the explicit critique being that next-frame-prediction-plus-RL is the wrong loss. (ii) *Cognitive-science framing of the same agenda* — the position paper *Assessing Adaptive World Models in Machines with Novel Games* (Ying, Collins, Sharma, Colas et al. with Tavares; arXiv 2507.12821) argues for evaluating rapid world-model induction in genuinely novel games rather than transfer benchmarks. (iii) *ExoPredicator* (Liang, Nguyen, Yang et al.) — learning abstract world models for robot planning, the same world-model thesis applied to manipulation. Cross-cutting: *NeuroAI for AI Safety* (Mineault, Zanichelli, Peng, Bingham, Jara-Ettinger, Mackevicius, Tavares, Tolias et al., arXiv 2411.18526, 152 pp.) — a roadmap arguing that brain-derived inductive biases are the under-utilised path to safe general intelligence. He also co-authored *Combining Induction and Transduction for Abstract Reasoning* (Li, Hu, Larsen, Wu et al., ICLR 2025) — neural program induction on ARC.

## 2. Basis Research Institute — mission, agenda, public outputs

**Type.** 501(c)(3) **non-profit research institute** in NYC (the `basis.ai` brand is theirs; the unrelated `getbasis.ai` accounting startup is a name collision — Basis Research has a $~2.9M operating budget per their 2025 IRS filings and is funded by Founders Pledge and similar). LinkedIn handle `basis-ri`. Founded ~2022 by Tavares with Eli Bingham (Pyro creator, ex-Uber AI Labs) and Emily Mackevicius (computational neuroscientist, ex-Columbia Zuckerman). Tavares and Mackevicius are co-directors.

**Thesis** (from the founding document, May 2025 blog post *Basis: Designing a New Kind of AI Research Organization*). Two interlocked claims:

1. *Universal principles of reasoning exist independent of any particular problem* — the goal is to "establish the mathematical principles of what it means to reason, to learn, to make decisions, to understand, and to explain; and to construct software that implements these principles."
2. *The output is a lingua franca of AI as open-source software*, not papers. A three-layer stack: **Infrastructure** (PPL runtimes, effect systems, verification) → **Modules** (reusable principle-encoding components) → **Applications** (full solutions to chosen Challenge Problems). The terminal artefact they describe is a "universal reasoning engine."

**Challenge Problems** (chosen so each requires a real theoretical advance *and* a real-world deliverable):

- **Intuitive Scientific Discovery** — Project MARA, "everyday scientific discovery through active experimentation": active program-and-language synthesis for hypothesis-testing agents.
- **Impossible Measurements of Cell/Tissue Dynamics** — Bayesian dynamical models with `dynestyx` (NumPyro extension) and the Broad-collaboration line via Jankowiak.
- **Participatory Citymaking** — digital city models with participatory inference, stakeholder agency.
- **R-ADA — Rational Automated Robot Design Agent** — LLM × probabilistic programming for robot morphology synthesis.
- **Collaborative Intelligent Systems** (Mackevicius' group) — multi-agent / cross-species cooperation; `collab-creatures`, `collab-environment`, `collab-splats` repos.

**Public outputs (2024–2026 blog cadence).** Blog at `basis.ai/blog/`, GitHub at `BasisResearch`:

- 2026-04 *Pact: Trustworthy Coordination for Multi-Agentic Ecosystems* — a formal coordination language combining game theory, distributed systems and cryptography. **Directly relevant to our discourse-protocol layer.**
- 2026-04 *ExoPredicator: Abstracting Time and State for Robot Planning*
- 2026-04 *Building an Unverified Compiler with Agents* — 14-day agent run producing 93k lines of Lean (JS→WASM compiler).
- 2025-07 *AutumnBench: World Model Learning in Humans and AI*
- 2025-05 *Basis: Designing a New Kind of AI Research Organization* (founding doc)
- 2024-12 *Project MARA Preview*
- 2024-11 *NeuroAI for AI Safety*

**Open-source stack** (GitHub):
- `chirho` — experimental causal-reasoning language (267★, Python on top of Pyro).
- `effectful` — algebraic-effects-and-handlers metaprogramming library. **This is the same family of substrate we use in spindel.**
- `dynestyx` — NumPyro extension for dynamical systems.
- `millipede` — Bayesian variable selection.
- `lean.py` — Lean↔Python bindings (powers their verified-compiler work).
- `Autumn.cpp` — the Autumn reactive DSL compiled to WASM with Python/Julia bindings.
- `predicators` — bilevel planning.
- `collab-creatures`, `collab-splats`, `collab-environment` — Mackevicius' collaborative-behaviour stack.
- `VerifiedJS` — Lean-verified compiler.

## 3. Researchers at Basis

Co-directors / co-founders: **Zenna Tavares**, **Eli Bingham** (Pyro), **Emily Mackevicius**.

Research scientists: **Fritz Obermeyer** (Pyro core), **Martin Jankowiak** (Pyro/NumPyro, Broad Institute, part-time), **Jack Feser** (programming languages / program synthesis), **Kiran Gopinathan** (formal verification, proof engineering), **Matthew Levine** (ML + dynamical systems + UQ), **Michelangelo Naim** (computational theories of intelligence), **Rafal Urbaniak** (Bayesian / causal / policy), **Sreela Kodali** (embedded systems & robotics), **Tim Cooijmans** (gradient-based learning dynamics), **Yair Shenfeld** (generative modelling, optimal transport), **Yiyun Liu** (type theory, formal verification), **Dmitry Batenkov** (applied math).

Postdocs / fellows: **Dan Waxman** (Bayesian + causal + dynamical), **Dat Nguyen** (PPL, neuro-symbolic), **Nick Jourjine** (bioacoustics), **Ralph Peterson** (multi-agent animal behaviour).

Trainees / residents: **Archana Warrier** (agent learning, world modelling), **Emily Bunnapradist** (world modelling, agentic systems), **Jean Yoo** (visual computing), **Yichao Liang** (generalist agents + probabilistic modelling).

Operations: **Karen Schroeder** (ops, ex-Columbia neuroscience), **Alyse Portera**, **Anna Hidalgo**, **Michelle Yi**, **Ravi Deedwania**.

The roster shows a **deliberate pairing of the Pyro/NumPyro core (Bingham, Obermeyer, Jankowiak) with a verification / PL group (Feser, Gopinathan, Liu) and a CoCoSci-adjacent cognitive group (Mackevicius, Naim, Schroeder, Jara-Ettinger collaborators)**. This is not a generic ML lab; it is exactly the union of probabilistic programming, programming-language theory, and cognitive science.

## 4. Specific work on our four target topics

- **Causal reasoning + LLMs.** `chirho` is their public stake. Tavares' POPL 2023 work on synthesising causal reactive programs is the direct technical lineage. They do not yet have a "RSA / LLM-as-translator into causal PPL" paper of the *Word Models to World Models* shape — but the MARA project (intuitive scientific discovery via active hypothesis-testing) is exactly that pattern, and `chirho` is the runtime it would target.
- **Scientific discovery automation.** *Project MARA* is the explicit programme. They frame it as "machines that emulate how children learn through hypothesis-testing and observation" — active program-and-language synthesis. *Building an Unverified Compiler with Agents* (April 2026) is a public demonstration of agent-driven synthesis at scale.
- **Probabilistic models of perception/cognition.** The Mackevicius/Jara-Ettinger axis: *MetaCOG* (recovering what objects are actually there from a noisy perception model), *How does the primate brain combine generative and discriminative computations in vision?* (Peters, DiCarlo, Gureckis, Tavares et al., 2024), and the entire NeuroAI-for-AI-Safety roadmap. This is the "what should a `belief` signal look like *biologically*" line.
- **Agent systems.** *Pact* (April 2026 blog post) is the multi-agent coordination language — game theory + distributed systems + cryptography. *AutumnBench / WorldTest* benchmarks agents on world-model induction. ExoPredicator and predicators are bilevel-planning agents. The Collaborative Intelligent Systems group is multi-agent at biological scale.

## 5. Relevance to our discourse model — alignment, collaboration, overlap

**Strong philosophical alignment.** Basis' three-layer software thesis (Infrastructure → Modules → Applications) and our spindel→dvergr→simmis release chain are isomorphic in spirit: both bet that *reusable, composable substrate* beats monolithic models. Both pick *probabilistic programming as the load-bearing abstraction*. Both treat *causal counterfactuals as first-class* (their `chirho`; our `with-belief`, `what-if`, `fork-room`). Both treat *active hypothesis-testing as the canonical agent loop* (their MARA; our `smc-discourse` / `(what-if …)` reflectivity in §12 of `discourse-model.md`).

**Where we overlap with what they have.** Their `effectful` library and our spindel engine are *in the same algebraic-effects / metaprogramming family*. Their `chirho` is the causal layer on top of Pyro/NumPyro; our discourse runtime is a causal-counterfactual layer on top of spindel reactive nodes. Their `Autumn.cpp` is a reactive DSL for grid-world world-models; we have a reactive substrate (spindel) but no Autumn-style DSL on top of it. **AutumnBench is a benchmark we could plausibly target** — Autumn programs are reactive, which is our shape.

**Where we are doing something they are not.** They have *not* publicly committed to a multi-agent **continuous-time discourse** runtime. Their multi-agent posture is Pact (coordination language, blog only, April 2026) and Mackevicius' biological-collaboration work — neither targets multi-participant *linguistic* deliberation under our coalgebraic-participant semantics. Their world-model story is single-agent-in-Autumn-environment; our story is multi-agent-talking-about-a-fork-room. *They synthesise a world model and act in it; we synthesise a discourse and let participants act in each other.* The closest MIT-side analogue to our agenda is the Wong-Collins-Tenenbaum *MSA / open-world cognition* line (see `README.md` §1), not anything from Basis directly — Basis is a level below, providing the PPL and PL machinery.

**Inheritance vs. competition.** Mostly **inheritance / complement**, not competition. Their `chirho`/`effectful`/`Autumn` stack could *plug into* our participants the same way Wong et al.'s PLoT plugs into a single `belief` signal — Basis would be the natural home of "what the inside of a participant's head looks like as a causal PPL," we would be the room/coalgebra layer over many such participants. The one place we *do* overlap and could see friction is the *Pact / multi-agent coordination* line: if Pact grows up into a full discourse-and-coordination runtime, it competes directly with our discourse-model claim. The April 2026 blog post is preliminary (game-theory + crypto framing, no published spec), so the window is open. Worth tracking.

**Collaboration angle — concrete openings.**

1. **`effectful` adoption / cross-pollination.** They are doing algebraic-effects-based metaprogramming for Python+Pyro; we are doing reactive-effects-with-spindel for the JVM. Their use of effects to layer causal interventions over a base PPL is *exactly* the move we want to make for layering discourse interventions over reactive nodes. A joint write-up or design-doc exchange here would be cheap and high-signal.
2. **AutumnBench / WorldTest as an evaluation target.** A "discourse agents collaboratively induce an Autumn program" demo would land cleanly with their benchmark and would showcase our multi-agent angle in their evaluation framework.
3. **R-ADA / participatory citymaking** are application slots where *multi-participant deliberation* is a missing ingredient — their Challenge Problems would benefit from our coalgebraic-participant layer.
4. **NeuroAI for AI Safety co-signing.** Mackevicius/Bingham/Tavares are co-authors of the safety roadmap. Our continuous-time multi-agent-deliberation story has a natural slot under the "cooperative / pragmatic / model-of-other" axes of that roadmap.

**Recommendation.** Re-engage. Of all the labs in the survey, **Basis is the one whose substrate and worldview compose most cleanly with ours without producing a duplicate-effort collision.** Approach Tavares with the spindel→dvergr→simmis story and the discourse-model `belief` / `what-if` semantics, pitched as "we are the multi-agent linguistic layer your `chirho` / `Autumn` / Pact stack does not yet have" — explicitly inviting use of `chirho` or `effectful` as the inside-the-participant layer.

---

## Files

PDFs (`./pdfs/`):
- `zenna-basis_neuroai-for-ai-safety_2024.pdf` — Mineault, …, Tavares et al., arXiv 2411.18526 (152 pp roadmap).
- `zenna-basis_assessing-adaptive-world-models_2025.pdf` — Ying, Collins, Sharma, …, Tavares et al., arXiv 2507.12821 (position paper).
- `zenna-basis_benchmarking-world-model-learning_2025.pdf` — Warrier, Nguyen, Naim, Liang, …, Tavares et al., arXiv 2510.19788 (WorldTest + AutumnBench).
- `zenna-basis_combining-induction-transduction_2025.pdf` — Li, Hu, Larsen, Wu, …, Tavares et al., ICLR 2025 (neural program induction on ARC).

Key URLs:
- Basis: <https://www.basis.ai/> | About: <https://www.basis.ai/about/> | Founding doc: <https://www.basis.ai/blog/basis-organization/>
- GitHub: <https://github.com/BasisResearch>
- Zenna: <https://www.zenna.org/> | Publications: <http://www.zenna.org/publications/> | Scholar: <https://scholar.google.com/citations?user=bK6k2gcAAAAJ>
- LinkedIn (institute): <https://www.linkedin.com/company/basis-ri>
