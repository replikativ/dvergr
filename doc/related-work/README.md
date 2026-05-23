# Related work for `dvergr.discourse`

Surveys feeding the design doc at `../discourse-model.md`. Six topic reports, ~30 PDFs total, all checked into `pdfs/`. Read this index first; pick the topic file(s) you need.

## Topic reports

| File | Covers | Highest-priority finding |
|------|--------|---------------------------|
| [`plot-lineage.md`](plot-lineage.md) | The MIT (Mansinghka+Tenenbaum) / Stanford (Goodman) / Princeton (Griffiths) cluster: agenda papers on rethinking PPL with LLMs (Wong 2023 PLoT, Grand 2025 DisCIPL, Wong 2025 MSA, Chandra 2025 `memo`), Bayesian ToM as engineering (LaBToM, ThoughtTracing) | All four labs converge on *language → PPL → Bayesian runtime*. We sit one level up: discourse-level FK over their token / single-mind FK. |
| [`zenna-basis.md`](zenna-basis.md) | Zenna Tavares and Basis Research Institute (NYC) — causal PPL + program synthesis + universal reasoning engine | **Closest substrate-and-worldview alignment of any lab.** Their `effectful` library is in the same algebraic-effects family as spindel. April 2026 `Pact` multi-agent coordination language is a watch-item. Re-engage. |
| [`algebraic-llms.md`](algebraic-llms.md) | Categorical / monoidal / algebraic-effects accounts of LLM composition | **Foundation candidate:** Pangolin (LMPL 2025) + Plotkin/Xie selection monad (PLDI 2025) ground 4/5 of our algebra primitives. `debate` is the coalgebraic holdout — validates §5.4. ToM compositionally is novel from us. |
| [`mechinterp-steering.md`](mechinterp-steering.md) | Sparse autoencoders (Anthropic / OpenAI / DeepMind Gemma Scope / Goodfire), activation steering (RepE, CAA, refusal-direction), causal abstraction (Geiger DAS) | **Available today, not aspirational.** Open SAEs make a *feature-coded* belief substrate prototype-ready. **Bisimulation-by-features** is sharper than behavioural. `with-steering` becomes a first-class SCI primitive. |
| [`russell-cooperative-multi-agent.md`](russell-cooperative-multi-agent.md) | Stuart Russell's group + alums (Hadfield-Menell, Critch, etc.); Cooperative AI Foundation; multi-agent RL (Foerster, Leibo, Stone) | **Concordia / Melting Pot are the closest empirical analogs.** Concrete falsifiable experiment: run their scenarios through dvergr with `simulate-reply` as the only delta, measure ad-hoc-teamwork. DR-MDP audit of `align-on` for manipulation detection. |
| [`multi-agent-llm-frameworks.md`](multi-agent-llm-frameworks.md) | AutoGen (v0.2/v0.4/AG2/MS Agent Framework), MetaGPT, CAMEL, LangGraph, CrewAI, Swarm, Magentic-One | **AutoGen v0.4 (actor model + event-driven) is the closest mainstream comparison.** Borrow: OTel, MCP, A2A, subgraph composability. We retain: forkable runtime, continuous-time delta-reactive spins, FK at room granularity, bisimulation. |

## Cross-cutting synthesis (one paragraph)

The field has converged on a sharp claim: *language is the interface to a probabilistic-program runtime that does the actual reasoning.* LLMs are demoted from "the reasoner" to "the generator of structured inference artefacts." Algebraic-effects underpin the composition; Bayesian ToM is engineering, not psychology debate; mechanistic interpretability gives a feature-level handle on internal state. **Our discourse model sits one level up from all of this** — multi-agent, continuous-time, forkable, with bisimulation as the equivalence and Feynman–Kac at the room granularity. Specific lower-level pieces are now drop-in candidates: `chirho`/`effectful`/`Autumn` inside a participant (Basis lineage); `memo`/LaBToM as a belief updater (Mansinghka); LLaMPPL for constrained token generation (Mansinghka); DAS for feature-level bisimulation when both parties are open-weights (Geiger).

## Top reading-list (in priority order)

1. `pdfs/wong-2023-word-to-world-models.pdf` — the agenda paper
2. `pdfs/grand-2025-self-steering-discipl.pdf` — closest single-mind FK on LLMs
3. `pdfs/wong-2025-open-world-cognition-msa.pdf` — ephemeral world models
4. `pdfs/ying-2024-labtom-epistemic-language.pdf` — operational Bayesian ToM
5. `pdfs/kim-2025-thought-tracing-tom.pdf` — tracing ToM in dialogue
6. `pdfs/bricken-2023-towards-monosemanticity.pdf` — SAE foundation
7. `pdfs/geiger-2023-causal-abstraction.pdf` — DAS, the formal account for bisim-by-features
8. `pdfs/laidlaw-2025-assistancezero.pdf` — current Russell-lab scalable-CIRL
9. `pdfs/cross-2024-hypothetical-minds.pdf` — closest ToM-via-NL-hypothesis prior art

Pangolin (Tan/Wei/Sen/Zaharia, LMPL 2025) on algebraic-effects for LLMs is not yet in `pdfs/` — locate and add.

## Open follow-up items

1. **Read Basis's `effectful` library carefully** (half-day REPL session); decide whether to adopt idioms or fork
2. **Run a Concordia scenario through dvergr** with `simulate-reply` as the only added capability — falsifiable claim about our differentiator
3. **Re-engage Zenna Tavares** — strongest substrate alignment found; their `Pact` (Apr 2026) is a watch-item
4. **Locate the Pangolin paper PDF** and confirm the algebra primitive coverage; cite in §6 of `discourse-model.md`
5. **João Loula reconnect** (Mansinghka group) — see memory note
