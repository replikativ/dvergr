# Related Work — Mechanistic Interpretability & Inference-Time Steering of LLMs

> Compiled 2026-05-23 as a companion to `related-work/README.md` (the PPL-as-cognition agenda).
> Scope: Anthropic interpretability, Goodfire, DeepMind interpretability (Neel Nanda), Representation Engineering (Zou/Hendrycks), activation steering (CAA, Arditi), causal abstraction (Geiger), 2023–2025.
> Question that frames this file: *if a participant's `belief` signal is reified at the activation level, what do these communities give us?*

PDFs cited below live in `./pdfs/`. Citations follow the same `firstauthor-year` form.

---

## 1. The sparse-autoencoder (SAE) lineage — feature dictionaries

### **Bricken, Templeton, Batson, Chen, Jermyn, Conerly, Turner, Anil, Denison, Askell, Lasenby, Wu, Kravec, Schiefer, Maxwell, Joseph, Hatfield-Dodds, Tamkin, Nguyen, McLean, Burke, Hume, Carter, Henighan, Olah (Anthropic, 2023) — *Towards Monosemanticity: Decomposing Language Models With Dictionary Learning*** [transformer-circuits.pub](https://transformer-circuits.pub/2023/monosemantic-features)
*File: `pdfs/bricken-2023-towards-monosemanticity.pdf`*

- **Claim:** Polysemanticity in transformer activations is an artefact of *superposition*: many features share neurons. A sparse autoencoder trained on the residual stream recovers an over-complete dictionary of *monosemantic* features that each fire on one human-readable concept.
- **Method:** 1-layer transformer; SAE with L1 sparsity penalty over residual activations; manual + automated interpretability of ~4k learned dictionary atoms.
- **Evidence:** Features for Arabic script, DNA motifs, base64, etc.; ablating a feature surgically removes the corresponding behaviour.
- **Stance:** **Founding paper of the SAE-feature programme**. Sets the lemma we rely on in §11: *concepts have linear-algebraic addresses inside the model*. Everything else in this file is the consequence of that lemma being repeatedly confirmed.

### **Templeton, Conerly, Marcus, Lindsey, Bricken, Chen, Pearce, Citro, Ameisen, Jermyn, Olsson, Olah et al. (Anthropic, May 2024) — *Scaling Monosemanticity: Extracting Interpretable Features from Claude 3 Sonnet*** [transformer-circuits.pub](https://transformer-circuits.pub/2024/scaling-monosemanticity/)
*(HTML-only "infinite paper"; PDF presentation in `pdfs/templeton-2024-scaling-monosemanticity.pdf` — slides from JHU mirror)*

- **Claim:** SAEs scale: 34M-feature dictionary recovered from Claude 3 Sonnet's middle-layer residual stream. Features are abstract, multilingual, multimodal, and *causally manipulable*.
- **Method:** Train SAEs at three scales (1M / 4M / 34M) on Claude 3 Sonnet; auto-interp + clamp-to-X experiments. Famous result: clamping the "Golden Gate Bridge" feature → "Golden Gate Claude" personality.
- **Evidence:** Features for famous people, code-type-signatures, deceptive intent, sycophancy, security vulnerabilities. Steering by feature-clamping causally changes outputs (not just probes).
- **Philosophical thrust:** The *unit of cognition inside a production LLM* is the SAE feature, not the token and not the neuron.
- **Stance:** **Most directly load-bearing for our SCI design.** If "what an agent is currently thinking about" has a 34M-dim sparse code we can read *and write*, then `belief` and `attention` signals in our discourse model can be backed by feature dictionaries rather than by prompt-engineering tricks. Golden Gate Claude is a one-feature precursor of `(with-belief participant belief-vector …)`.

### **Gao, la Tour, Tillman, Goh, Troll, Radford, Sutskever, Leike, Wu (OpenAI, June 2024) — *Scaling and Evaluating Sparse Autoencoders*** [arXiv:2406.04093](https://arxiv.org/abs/2406.04093)
*File: `pdfs/gao-2024-scaling-evaluating-saes.pdf`*

- **Claim:** TopK activation (k-sparse) replaces L1 in the SAE objective, yielding cleaner scaling laws and removing one hyperparameter. They train 16M-latent SAEs on GPT-4 activations over 40B tokens.
- **Method:** TopK forward pass; new metrics (probe loss, downstream-effect sparsity, automated-interp recall) to evaluate feature quality without humans-in-the-loop.
- **Evidence:** Empirical scaling laws across dict size and k; TopK strictly dominates ReLU+L1 on the reconstruction–sparsity Pareto frontier.
- **Stance:** **Substrate paper**. If we ever want to *train our own* SAE over an open-weights base model, this is the recipe. Also the canonical reference for "SAE features are reproducible and measurable", which we need to cite when we claim feature-level signals are stable across runs.

### **Lieberum, Rajamanoharan, Conmy, Smith, Sonnerat, Varma, Kramár, Dragan, Shah, Nanda (DeepMind, August 2024) — *Gemma Scope: Open Sparse Autoencoders Everywhere All At Once on Gemma 2*** [arXiv:2408.05147](https://arxiv.org/abs/2408.05147)
*File: `pdfs/lieberum-2024-gemma-scope.pdf`*

- **Claim:** Release 400+ JumpReLU SAEs covering *every layer and sub-layer* of Gemma 2 2B/9B (and select layers of 27B), totalling 30M+ features. JumpReLU fixes the L1-vs-detection tradeoff with a learned threshold.
- **Method:** JumpReLU activation = ReLU with per-feature learnable threshold; trained at ~20% of the compute that produced GPT-3, ~20 PiB of cached activations.
- **Evidence:** Comprehensive coverage means downstream researchers can ask "what features fire at *this* layer at *this* token" for free on a real production-grade open model.
- **Stance:** **The most actionable upstream artifact for us.** Open-weights, open-SAE, every layer covered, runs on a laptop class GPU. If we want a working prototype where `belief` is *literally* a vector of SAE-feature intensities at layer L, Gemma 2 + Gemma Scope is the shortest path. Anthropic's Sonnet SAEs are not public; Gemma Scope is.

### **Goodfire (Balsam, McGrath, Ho et al., 2024–2025) — *Ember* API + open-source SAEs for Llama 3.1 8B and Llama 3.3 70B**
- [Ember launch (Dec 2024)](https://www.goodfire.ai/blog/announcing-goodfire-ember)
- [Open SAEs (Jan 2025)](https://www.goodfire.ai/blog/sae-open-source-announcement)
- [Feature steering for AI engineering](https://www.goodfire.ai/blog/feature-steering-for-reliable-and-expressive-ai-engineering)
- *No single canonical PDF; links above are blog posts.*

- **Claim:** Interpretability can be **productised**: instead of writing fine-tuning scripts, an engineer should be able to *clamp / amplify / suppress features by handle* at inference time, via a hosted API. They have built this for Llama and DeepSeek-R1; they offer BatchTopK SAEs trained on the reasoning model R1 — "the first public interpreter models for a reasoning model."
- **Method:** Train production-grade SAEs (BatchTopK variant of Gao 2024), expose them behind an API: list/search features, clamp intensities, steer generation. Plus tooling like *Paint with Ember* (May 2025) for diffusion models.
- **Stance:** **The most relevant outside party.** Goodfire is building exactly the *engineering substrate* our SCI vision needs: a programmable, feature-addressable interface to LLM internals. If we want SCI tools to expose `(clamp model :feature/deception 0.0)` as a first-class primitive, Goodfire's API is the prior art, and their open-source Llama SAEs are the artifact we can demo against today. **Worth contacting** if we get serious.

### **Anthropic Interpretability — *Circuit Tracing: Revealing Computational Graphs in Language Models* and *On the Biology of a Large Language Model* (March 2025)**
- [transformer-circuits.pub/2025/attribution-graphs/methods.html](https://transformer-circuits.pub/2025/attribution-graphs/methods.html)
- [transformer-circuits.pub/2025/attribution-graphs/biology.html](https://transformer-circuits.pub/2025/attribution-graphs/biology.html)
- *Open-source code: `safety-research/circuit-tracer` (May 2025).*

- **Claim:** Beyond a feature dictionary, you can build *attribution graphs* connecting features across layers, yielding a per-prompt computational graph: which features at layer L−1 caused which features at layer L for *this token*. Applied to Claude 3.5 Haiku; reveals planning behaviour (rhymes chosen before the line that leads to them), multi-step factual reasoning, and arithmetic circuits.
- **Method:** Replacement model where MLPs are swapped for SAE+attribution; trace causally-effective edges per prompt.
- **Stance:** **Promotes SAEs from "static dictionary" to "dynamic execution trace."** This is the level at which one could imagine treating *circuits* (not just features) as the unit a participant's `cogn-trace` signal reports — a structured story of how a thought was assembled rather than the bag of features that fired. Open-source release means we can in principle run circuit-tracing on a small open model under our control.

---

## 2. Activation / representation steering — *control* at inference time

### **Zou, Phan, Chen, Campbell, Guo, Ren, Pan, Yin, Mazeika, Dombrowski, Goel, Li, Byun, Wang, Mallen, Basart, Koyejo, Song, Fredrikson, Kolter, Hendrycks (October 2023) — *Representation Engineering: A Top-Down Approach to AI Transparency*** [arXiv:2310.01405](https://arxiv.org/abs/2310.01405)
*File: `pdfs/zou-2023-representation-engineering.pdf`*

- **Claim:** Take *population-level* representations — not neurons, not features — as the primary unit. Derive steering vectors for high-level constructs (honesty, power-seeking, morality, emotion, memorisation) by contrasting prompt distributions, and inject them into the residual stream to monitor and *control* the corresponding behaviour.
- **Method:** Linear Artificial Tomography (LAT) — collect activations under "concept-on" vs "concept-off" prompts, take the top principal component as the concept direction; intervene by add/clip/project.
- **Evidence:** Honesty lie-detection that beats GPT-4; controllable refusal, sycophancy, harmful-instruction-following; jailbreak detection; power-seeking control.
- **Philosophical thrust:** Don't go bottom-up neuron-by-neuron — *population codes* (à la cognitive neuroscience) are the right granularity to talk to a network in.
- **Stance:** **The canonical "representation-level steering" paper** and the most direct alternative philosophy to the SAE programme. RepE says: *we don't need a complete dictionary, we just need an axis per concept we want to control.* For our discourse model that maps almost too neatly: each participant's `belief` could be a list of `(concept-direction, intensity)` pairs, applied at every generation. Compare and contrast with the SAE view in §4 below.

### **Panickssery, Gabrieli, Schulz, Tong, Hubinger, Turner (December 2023) — *Steering Llama 2 via Contrastive Activation Addition* (CAA)** [arXiv:2312.06681](https://arxiv.org/abs/2312.06681) (ACL 2024)
*File: `pdfs/panickssery-2023-contrastive-activation-addition.pdf`*

- **Claim:** Steering vectors should be derived as the **mean difference** in residual activation between positive and negative *behaviour exemplars* (multiple-choice A vs B). Adding/subtracting this vector at every token position post-prompt monotonically shifts behaviour, on top of and orthogonal to system prompts and fine-tuning.
- **Method:** Hand-curated MCQ datasets for sycophancy, corrigibility, hallucination, power-seeking, survival-instinct, myopic-reward; CAA on each layer of Llama-2-Chat 7B/13B.
- **Evidence:** Linear coefficient → linear behaviour shift; minimal capability damage at moderate strengths.
- **Stance:** **The simplest steering recipe that actually works** and is now a community baseline (`steering-vectors` library, etc.). Combined with RepE this is the canonical "steering without training" toolkit. **Most directly mappable to our `belief`-as-signal idea** — a CAA vector for "Alice's current opinion on topic X" is exactly the type of thing a `belief` signal could output and a participant body could consume.

### **Arditi, Obeso, Syed, Paleka, Panickssery, Gurnee, Nanda (June 2024) — *Refusal in Language Models Is Mediated by a Single Direction*** [arXiv:2406.11717](https://arxiv.org/abs/2406.11717) (NeurIPS 2024)
*File: `pdfs/arditi-2024-refusal-single-direction.pdf`*

- **Claim:** In every chat-tuned LM they tested, **a single residual-stream direction** is sufficient to mediate refusal. Project it out → the model complies with harmful requests; add it → the model refuses benign ones.
- **Method:** Difference-of-means between harmful/harmless prompts → refusal direction; project-out at every layer + token to "ablate."
- **Evidence:** Generalises across Llama 2/3, Qwen, Yi, Gemma; ablation = jailbreak with no fine-tuning, fine-tuning cost equivalent.
- **Philosophical thrust:** A *safety-relevant disposition* is sometimes one rank-1 update away. This is both a research result and a safety alarm.
- **Stance:** **Smoking-gun evidence for the linear-representation-of-high-level-dispositions claim** that everything else in this section presupposes. For us: it means giving a participant a `disposition` signal is not just a useful abstraction — it has a known mechanistic substrate.

---

## 3. Causal interventions & causal abstraction — interpretability with *proofs*

### **Geiger, Wu, Potts, Icard, Goodman (2023, published JMLR 2025) — *Causal Abstraction: A Theoretical Foundation for Mechanistic Interpretability*** [arXiv:2301.04709](https://arxiv.org/abs/2301.04709)
*File: `pdfs/geiger-2023-causal-abstraction.pdf`*

- **Claim:** Mechanistic interpretability is *formally* the search for a **causal abstraction** of the network: a high-level causal graph whose interventions agree with low-level interventions on the network up to a tolerance. *Interchange interventions* (swap activations between runs) are the diagnostic.
- **Method:** Define partial/approximate causal abstraction; introduce DAS (Distributed Alignment Search) — learn the linear subspace where a hypothesised high-level variable lives, then check that interchange interventions on it produce the expected counterfactual behaviour.
- **Evidence:** Worked examples on arithmetic, MNLI, behavioural reasoning; formalises and unifies probing / patching / steering.
- **Philosophical thrust:** Behavioural agreement is not enough; you need *interventional* agreement to claim a feature *is* the variable you think it is.
- **Stance:** **Crucial for our `bisimulation` discussion** (see §4 below). If we want participants to be considered "the same agent" *in the relevant respects*, the right notion is not "behaviour matches on inputs" but "interchange interventions on the candidate-belief-subspace produce matching counterfactuals." This is **bisimulation-by-features**, and Geiger gives us the formal definition.

### **Wu, Geiger, Icard, Potts, Goodman (2024) — *Interpretability at Scale: Identifying Causal Mechanisms in Alpaca* (Boundless DAS)** — relevant follow-up scaling DAS to chat models. (Not downloaded; see Geiger's CV.)
- **Stance:** Confirms DAS scales; the same group is now publishing tooling like *pyvene* for intervention-based interpretability.

### **Marks, Rager, Michaud, Belinkov, Bau, Mueller (2024) — *Sparse Feature Circuits*** — uses Anthropic-style SAE features as the nodes of a Geiger-style causal graph. (Mentioned as the natural marriage of §1 and §3 — see references in Circuit Tracing.)

---

## 4. Synthesis — relevance to our discourse model

This section is where we connect the above to `doc/discourse-model.md`.

### 4.1 SAE features as "concepts" in SCI

In our discourse algebra, an SCI agent has access to *tools*. So far our tools are external (`search-web`, `fork-room`, `align-on`).
**The SAE programme suggests a new class of tool: *internal, feature-addressable* tools.**

- `(features-at participant :layer L)` → sparse vector of currently-active SAE features.
- `(clamp participant :feature/<id> intensity)` → Goodfire-style feature steering inside a generation.
- `(search-features "deception")` → auto-interp lookup, à la Gemma Scope's labelled atlas.

This makes the participant *transparent to itself*: an agent can inspect *what it is thinking* in a structured way, not just chain-of-thought sample its own next tokens. We get **meta-cognition with a mechanistic substrate** rather than meta-cognition by prompt incantation. The cleanest open-weights demo path is Gemma 2 + Gemma Scope; the cleanest hosted path is Goodfire Ember for Llama 3.3 70B.

### 4.2 Steering vectors as the substrate for the `belief` signal

Our `belief` signal currently has signature *"some thing a participant emits and others can consume"*. We left it abstract on purpose. Three concrete substrates suggest themselves:

| Layer of `belief` | Substrate | Source |
|---|---|---|
| Symbolic (PLoT-style) | A `memo` / Church program describing the participant's world model | Chandra 2025; Wong 2023; Ying 2024 |
| Population-coded | A list of `(direction, intensity)` pairs added to the residual stream | RepE (Zou 2023); CAA (Panickssery 2023); Arditi 2024 |
| Feature-coded | A sparse vector of SAE feature intensities (potentially per layer) | Templeton 2024; Lieberum 2024; Goodfire SAEs |

We do **not** have to pick one. The discourse algebra can carry all three: a participant's `belief` is a *bundle* of (symbolic-program, steering-vectors, feature-intensity-vector), and different consumers project to the level they need. The key insight from this body of work is that **the bottom two layers exist** — they are not aspirations. Anthropic, OpenAI, DeepMind, and Goodfire have all shipped artifacts confirming concepts have linear-algebraic addresses in production-grade models.

### 4.3 Bisimulation by features, not by behaviour

This is the most exciting structural opening. Coalgebraically, two participants are bisimilar when no observer can distinguish them. Behavioural bisimulation says: same inputs → same outputs. That is very weak; two participants can be behaviourally identical while internally meaning entirely different things (e.g., the Mary's Room intuition; or two LLM finetunes that happen to share a benchmark profile).

Geiger's causal-abstraction framework gives us a sharper notion:
> *Two participants are **feature-bisimilar** if interchange interventions on their candidate high-level variables produce matching counterfactual trajectories.*

Operationally:

- Project both participants' residual streams onto an alignment subspace `S` (DAS).
- For any sampled state in `S`, swap the projection from participant A into participant B.
- If B's resulting trajectory is the same as if B had been in that state natively, they are bisimilar on `S`.

This is **bisimulation at the cognitive-state level**, not at the I/O level. It is the right notion for "two agents in our system share enough understanding to collaborate" — and it is *checkable* with current interpretability tools.

This is the missing piece for taking our SCI claim about shared semantics seriously: when we say two participants `align-on` something, we want this to mean more than "they emit similar tokens"; it should mean their high-level-variable subspaces have been brought into agreement *under interchange*.

### 4.4 Circuits as `cogn-trace` signals

The Anthropic 2025 circuit-tracing work moves from "what features fire" to "how features caused features." If we expose this as a signal:

- `cogn-trace` = a stream of attribution-graph fragments, one per generated token.

then a participant publishes *not just what it said but how the network computed it*. Other participants (e.g., a `critic`) can subscribe to `cogn-trace` rather than to `output`, and we get *mechanistic* peer review rather than behavioural peer review. This is speculative but the open-source `circuit-tracer` release means it is at least *available* to prototype.

### 4.5 What we should NOT do

- Don't reinvent SAEs. Gemma Scope and Goodfire's open SAEs cover the open-weights case; Anthropic and Goodfire-Ember cover the hosted case.
- Don't claim "explainable" if all we have is a steering vector. Geiger-style interchange-intervention proofs of alignment are the bar to clear; we should at least *measure* against them, even when we ship without them.
- Don't reify a single mechanism as the substrate. The three-layer table in §4.2 is deliberate; the discourse algebra should be agnostic about which layer a given `belief` lives in.

---

## 5. Reading order for the next iteration

1. **Bricken 2023** + **Templeton 2024** — the SAE thesis statement.
2. **Lieberum 2024** (Gemma Scope) — what we can actually run.
3. **Zou 2023** (RepE) + **Panickssery 2023** (CAA) + **Arditi 2024** — the steering toolkit.
4. **Geiger 2023** — the theory we need for bisimulation-by-features.
5. **Anthropic circuit-tracing 2025 (web)** — the long-term shape.
6. **Goodfire blog posts** — the engineering target for SCI feature-tools.

If we want a single afternoon's worth of paper-reading, **Templeton 2024 + Arditi 2024 + Geiger 2023** is the minimal sufficient set: one to establish features, one to establish steering, one to establish causal proof.
