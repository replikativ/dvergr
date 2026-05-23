# Related Work — Algebraic / Categorical Modelling of LLMs

> Compiled 2026-05-23 for contrast against `doc/discourse-model.md` (algebra `ask`,
> `fan-out`, `race`, `pipeline`, `debate`, coalgebraic participants, bisimulation
> laws, theory-of-mind via fork + `simulate-reply`).
>
> Scope: work 2023–2025 that frames LLM behaviour, prompting, or composition
> through *algebraic / categorical / type-theoretic* structure rather than
> empirical engineering. We verified each paper on arXiv or the author page;
> nothing here is fabricated. Where the evidence is thin, we say so.
>
> PDFs are in `./pdfs/`. Filenames follow `firstauthor-year-shorttitle.pdf`.

---

## Executive read

The literature splits cleanly into two camps and a third aspirational one:

1. **Categorical semantics of a single LLM's distribution** — Bradley/Terilla/
   Vlassopoulos's enriched-category programme (2021–2025) and its descendants
   (Mahadevan's homotopy take, Zhang's Markov-categorical framing). This camp
   answers *"what is the meaning a trained LLM denotes?"* using enriched
   categories, copresheaves, magnitude, Markov categories, and weak equivalences
   for paraphrase. **None of it touches multi-call composition.**

2. **Algebraic effects / monadic composition of LLM pipelines** — Pangolin
   (Tan, Wei, Sen, Zaharia 2025) explicitly types LLM calls as algebraic effects
   and uses the selection monad to choose among non-deterministic outcomes.
   Plotkin & Xie's *Handling the Selection Monad* (PLDI 2025) is the underlying
   PL theory. This camp gives the closest formal foundation for *our* algebra of
   composition primitives — but says nothing about meaning, ToM, or
   bisimulation of agents.

3. **Operadic / higher-categorical accounts of multi-agent LLMs** — exists
   mostly as gestures (one Medium piece by Satyam Mishra on ∞-operads; nothing
   peer-reviewed). **This is aspirational.** Spivak's recent duoidal work
   (2210.01962) is suggestive but not LLM-specific.

The honest summary: **"algebraic modelling of LLMs" as a field is currently
two disjoint communities** (CT-of-distributions vs. PL-of-pipelines) plus
hand-waves on the operadic side. Nobody is yet doing what our discourse model
attempts, namely a *coalgebra of conversational participants with named
composition primitives and bisimulation laws*. Pangolin is the closest thing,
but it stops at the pipeline boundary and never asks what an *agent* is.

---

## 1. Categorical semantics of LLM output distributions

Bradley, Terilla & Vlassopoulos opened this thread in 2021 with an enriched
category of texts over `[0,1]`. Recent followups (Mahadevan 2025, Zhang 2025,
Bradley & Vigneaux 2025) re-frame the same question — *what is the semantic
content of a single LLM's next-token distribution?* — through Markov categories,
model categories, and magnitude / Tsallis-entropy. All of these are about
*one* model treating one text; they answer "what does the model mean?" not
"how do model calls compose?".

### **Bradley, Terilla, Vlassopoulos (2021/22) — *An Enriched Category Theory of Language: From Syntax to Semantics*** [arXiv:2106.07890](https://arxiv.org/abs/2106.07890)
*File: `pdfs/bradley-2021-enriched-category-language.pdf`. Published in La Matematica, 2022.*

- **Claim:** Probability distributions over text continuations (i.e. what a language model learns) determine an enriched category in which objects are expressions and homs are conditional extension probabilities; passing to the copresheaf category via Yoneda gives a *semantic* category equipped with logical operations.
- **Method:** Define a syntactic category enriched over the unit interval `[0,1]` (a quantale); Yoneda-embed into copresheaves; show that entailment, conjunction, etc. arise as adjoint functors in the enriched setting.
- **Evidence:** Mathematical construction, not benchmarks. The construction recovers expected logical structure on toy text fragments.
- **Stance:** **Foundational but orthogonal.** Gives a categorical reading of *what an LLM denotes*. Says nothing about composing multiple LLM calls, nothing about agents, no operational semantics. We can cite it as background for "an LLM-as-component has a categorical reading" but it does not ground our `ask`/`pipeline`/`debate` algebra.

### **Mahadevan (2025) — *A Rose by Any Other Name Would Smell as Sweet: Categorical Homotopy Theory for Large Language Models*** [arXiv:2508.10018](https://arxiv.org/abs/2508.10018)
*File: `pdfs/mahadevan-2025-categorical-homotopy-llms.pdf`.*

- **Claim:** LLMs assign different next-token probabilities to semantically equivalent phrases ("Charles Darwin wrote" vs "Charles Darwin is the author of"); this is a *paraphrase equivalence* problem and should be modelled as weak equivalences in a model category of LLM distributions.
- **Method:** Define an "LLM Markov category" where arrows are conditional next-token distributions; introduce weak equivalences (categorical-homotopy "same up to paraphrase"); import machinery from higher algebraic K-theory and model categories.
- **Evidence:** No benchmarks. Position-and-framework paper. The contribution is conceptual: a programme for how categorical homotopy *could* address paraphrase invariance.
- **Stance:** **Adjacent but useless for us.** This is the bisimulation-adjacent paper in the categorical-distribution camp — it does address an *equivalence* problem with category-theoretic machinery. But the equivalence is at the wrong scale (paraphrase between two token sequences inside one model) for our bisimulation claim (a `script` participant is bisimilar to a real `agent` on the scenarios their script covers). Useful only as evidence that "equivalence-up-to-something" is a known move in this literature.

### **Zhang (2025) — *A Markov Categorical Framework for Language Modeling*** [arXiv:2507.19247](https://arxiv.org/abs/2507.19247)
*File: `pdfs/zhang-2025-markov-categorical-llm.pdf`.*

- **Claim:** Single-step LM generation = composition of information-processing stages in a Markov category; this compositional categorical lens connects training loss, representation geometry, and downstream capability.
- **Method:** Markov categories (symmetric monoidal with copy-delete on every object) as the ambient setting; categorical entropy to formalise data uncertainty; spectral analysis of linear-softmax heads as a generalised CCA / eigenproblem.
- **Evidence:** Theoretical. Connects NLL training to a calibrated quadratic surrogate; derives an information-surplus quantity that justifies speculative decoding.
- **Stance:** **Genuinely compositional, but inside the model.** This is the most algebraically-honest paper in the distribution camp: it actually *uses* the symmetric monoidal structure for analysis. But composition here means "stack of linear maps inside a forward pass", not "compose two LLM calls". The Markov-category language is the right setting for our *participants as stochastic processes*, but the paper itself never leaves the single-call boundary.

### **Bradley & Vigneaux (2025) — *The Magnitude of Categories of Texts Enriched by Language Models*** [arXiv:2501.06662](https://arxiv.org/abs/2501.06662)
*File: `pdfs/bradley-2025-magnitude-texts.pdf`. Theory and Applications of Categories Vol. 44, No. 37, 2025.*

- **Claim:** Apply Leinster's *magnitude* to Bradley et al.'s enriched category of texts; the magnitude function turns out to be a partition-function-like sum over Tsallis entropies of next-token distributions.
- **Method:** Compute the Möbius function and magnitude of the [0,1]-enriched text category; interpret derivatives in the magnitude parameter as Shannon entropy in a limit.
- **Evidence:** Mathematical derivation; no experiments.
- **Stance:** **Pretty mathematics, irrelevant to us.** Useful for someone trying to *measure* an LLM's distributional capacity. Has no purchase on multi-call composition, ToM, or our algebra. Included for completeness of the Bradley line.

---

## 2. Algebraic effects, monads, and PL-of-LLM-pipelines

The strongest formal-foundation candidate for our work. Tan et al.'s Pangolin
(LMPL 2025) treats LLM calls as first-class algebraic effects and uses Plotkin
& Xie's selection monad (PLDI 2025) to capture scoring among non-deterministic
outcomes. This is **the same conceptual move we make** — composition primitives
have semantics independent of how the underlying LLMs are invoked — only Tan
et al. do it at the pipeline level (LMQL/DSPy replacement), where we do it at
the *conversational-participant* level.

### **Tan, Wei, Sen, Zaharia (2025) — *Programming Large Language Models with Algebraic Effect Handlers and the Selection Monad* (Pangolin)*** [LMPL 2025](http://shangyit.me/_assets/files/lmpl2025-paper11.pdf)
*File: `pdfs/tan-2025-pangolin-algebraic-effects.pdf`. 1st ACM SIGPLAN Workshop on Language Models and Programming Languages (co-located with SPLASH).*

- **Claim:** LLM interactions should be modelled as *first-class algebraic effects*; non-determinism over LLM outputs should be modelled by the *selection monad*; compound AI systems should be programmed with effect handlers so the same program runs under different execution strategies just by swapping handlers (e.g. single OpenAI call vs parallel candidates with reward-based selection).
- **Method:** A small language `Pangolin` whose operations are LLM calls, scoring, and choice; handlers interpret these into execution strategies. Selection monad gives the principled basis for "choose the best of several non-deterministic LLM outputs by a downstream metric."
- **Evidence:** Worked compound-AI examples (Tree-of-Thoughts among them, with reported ~10× speedup compared to monolithic implementations); the LMPL companion paper *Composable Effect Handling for Programming LLM-Integrated Scripts* (dl.acm.org/doi/10.1145/3759425.3763396) reports similar wins.
- **Stance:** **The closest published formal foundation for our algebra.** Their effect operations (call-LLM, score, choose) map onto our `ask` (LLM call wrapped as a participant), `fan-out` (parallel non-determinism = the selection monad's binding), `race` (reward-based selection with latency as reward), and `pipeline` (sequential effect composition). Crucially they make explicit the point we need: *the operations are independent of the handler interpretation*, which is exactly what makes our `script` vs `agent` substitution sound. **Where they stop:** no `debate` (no peer-symmetric communication primitive), no participants as coalgebras (effects are stateless from the program's view), no forkable rooms, no ToM. They formalise the *outer* layer of our work; we still need to formalise the participant layer ourselves.

### **Plotkin & Xie (2025) — *Handling the Selection Monad*** [PLDI 2025](https://arxiv.org/abs/2504.03890)
*File: `pdfs/plotkin-2025-handling-selection-monad.pdf`.*

- **Claim:** Algebraic effect handlers can be enriched with *choice continuations* — continuations that expose possible future losses/rewards — letting programmers write custom selection algorithms (not only the canonical optimum) on top of the selection monad.
- **Method:** Operational semantics + type theory for handlers-with-choice-continuations; equational theory; case studies in optimisation.
- **Evidence:** PL theory paper; case studies on selection-monad-based optimisation algorithms (game theory, Bayesian optimisation). No direct LLM application — that's Pangolin's contribution.
- **Stance:** **The PL substrate Pangolin uses.** Worth citing because it gives the *equational* story for our composition primitives: `fan-out` followed by argmax-by-reward is exactly a `selection` handler. If we ever want laws like "race over a singleton equals ask" (which we list as §6 laws in `discourse-model.md`) to be formal theorems instead of regression tests, this is the calculus they would live in.

---

## 3. Adjacent: group-theoretic, type-theoretic, and PL-abstraction angles

A handful of papers touch the area but don't deliver an algebra in our sense.

### **Imani & Palangi (2024) — *Exploring Group and Symmetry Principles in Large Language Models*** [arXiv:2402.06120](https://arxiv.org/abs/2402.06120)

- **Claim:** Test LLMs against the four group axioms (closure, identity, inverse, associativity) on arithmetic tasks; find systematic failures (sharp closure cliffs, weak inverse / negation handling).
- **Method:** Empirical benchmark probing of GPT-class models; group axioms used as a checklist of failure modes, not a structural model.
- **Stance:** **Diagnostic, not constitutive.** Uses group theory as a yardstick, not as a model of LLM behaviour. Doesn't ground anything in our algebra.

### **Katende (2024) — *Symmetry-Enriched Learning: A Category-Theoretic Framework for Robust Machine Learning Models*** [arXiv:2409.12100](https://arxiv.org/abs/2409.12100)

- **Claim:** Use higher categorical structures ("hyper-symmetry categories", functorial representations) to enforce invariances in ML models.
- **Stance:** **General ML, not LLM-specific.** Doesn't help.

### **Dantanarayana et al. (2024/25) — *MTP: A Meaning-Typed Language Abstraction for AI-Integrated Programming*** [arXiv:2405.08965](https://arxiv.org/abs/2405.08965) (OOPSLA 2025)

- **Claim:** Introduce a `by` operator and a meaning-typed IR (MT-IR) so calls into LLMs are inferred from surrounding code structure rather than written as prompt strings.
- **Stance:** **Practical, not algebraic.** Engineering paper: better ergonomics, no formal effect / monad / category theory. Useful as a sibling to Pangolin in the "languages-for-LLMs" space.

### **Mishra (2025) — *When Agents Compose: ∞-Operads and the Algebra of Multi-Agent Language*** [Medium](https://satyamcser.medium.com/when-agents-compose-operads-and-the-algebra-of-multi-agent-language-88e6cf97925c)

- **Claim:** ∞-operads are the right setting for n-ary agent composition with associativity-up-to-coherence.
- **Stance:** **Blog post, no theorems, no algorithms.** Mentioned only to flag that *somebody* has noticed the operadic shape of multi-agent composition. Until peer-reviewed work appears, this is aspirational.

### **Spivak et al. (2022/25) — *Duoidal Structures for Compositional Dependence*** [arXiv:2210.01962](https://arxiv.org/abs/2210.01962)

- **Claim:** Duoidal categories model interaction between independent (parallel) and dependent (sequential) composition with a shared unit; not framed as an LLM paper.
- **Stance:** **Latent foundation, not yet applied.** This is the kind of structure that *would* be the right setting for our two-axis `fan-out`/`pipeline` algebra, but no one (including Spivak) has connected it to LLMs. Worth tracking; not a citation yet.

---

## 4. Contrast with our discourse model

### 4.1 Does any of this work give us a formal foundation for our algebra?

**Partially, only for the pipeline layer.** Pangolin (Tan et al. 2025) +
Plotkin & Xie's selection monad calculus jointly give us a working PL-theoretic
account of:

| our primitive   | their structure                                                |
|-----------------|----------------------------------------------------------------|
| `ask`           | An `LLM` effect operation with a single response handler        |
| `fan-out`       | Non-deterministic choice + selection monad on a vector of responses |
| `race`          | Selection monad with a latency-shaped reward; choose-first handler |
| `pipeline`      | Sequential `>>=` over the effect monad                          |
| `debate`        | **Not covered.** Pangolin has no peer-symmetric communication primitive; debate requires participants to *see each other's* outputs, which is a coalgebraic, not effectful, structure |

So: Pangolin grounds 4 of our 5 headline primitives. **`debate` is the
primitive that demands the coalgebraic-participant view none of the surveyed
work supplies.**

### 4.2 Does any of this work speak to our bisimulation claim?

**Tangentially.** Three relevant data points:

- **Mahadevan (2025)** uses *weak equivalences* in a model category to argue
  paraphrases should be "the same" in an LLM's output distribution. This is
  bisimulation-flavoured but at the wrong scale: he equates token sequences
  with the same meaning *inside one model*; we equate *participants* (`script`
  ≈ `agent` on covered scenarios) and *forks* (fork-merge identity).
- **Plotkin & Xie (2025)** equate effectful programs via the standard
  equational theory of algebraic effects (handlers + operations modulo the
  effect's equational laws). Our pipeline-associativity, fan-out-singleton,
  race-singleton laws (`discourse-model.md` §7 table) are exactly the kind of
  laws that calculus is built to prove. **If we want our laws to be theorems,
  this is the formal home.**
- Categorical applicative-bisimilarity work (e.g. Lassen-Levy-style coalgebraic
  bisimulation for higher-order languages, *not* LLM-specific) is the right
  ambient theory for our coalgebraic participant claim — but no one has yet
  applied it to LLMs.

**Verdict:** the bisimulation claim is *not* directly supported by any
LLM-specific paper. We're combining a known PL-theoretic tradition
(coalgebraic bisimilarity) with LLMs in a way the surveyed literature has not
yet attempted. This is opportunity, not gap — but we have to do the work
ourselves and cite the *non-LLM* coalgebra literature as the formal basis.

### 4.3 Does any of this work address theory of mind compositionally?

**No.** The ToM literature we surveyed in `README.md` (Wong/Tenenbaum, Ying et
al., Kim et al., Cross et al.) is Bayesian / probabilistic-programming based.
The *algebraic* literature surveyed here is silent on ToM:

- The Bradley line is about a single model's distribution; ToM is multi-agent
  by definition.
- Pangolin's effect handlers can express "ask another LLM what they think"
  but the resulting handler is a black-box subroutine — there is no
  *structural* commitment that the sub-LLM is *modelling* the speaker.
  Our `simulate-reply` is meant to be exactly that structural commitment.
- The operadic Medium piece gestures at multi-agent composition but has no
  ToM content.

**Verdict:** the algebraic-LLM literature has *not* addressed ToM
compositionally. Our combination of (a) coalgebraic participants, (b) fork-room
for hypothetical "imagine the other person's reply", and (c) `simulate-reply`
as a first-class primitive looks genuinely novel against this corpus.

### 4.4 Honest summary

- **The categorical-semantics-of-distributions camp** (Bradley line,
  Mahadevan, Zhang) is interesting but **operating one level below our work**.
  They model the meaning denoted by *one* LLM; we model the discourse formed
  by *several*. Useful as background, not as foundation.
- **The algebraic-effects-of-pipelines camp** (Pangolin, Plotkin & Xie) is
  **directly the right substrate** for the pipeline-layer of our algebra, and
  covers 4 of 5 of our primitives. We should cite Pangolin as the closest
  prior art for "LLMs-as-algebraic-effects with handler-swapping" and Plotkin
  & Xie as the formal home for our composition laws.
- **The operadic / higher-categorical multi-agent camp** is **aspirational
  only**; one Medium piece, no peer-reviewed work. Our `debate` and
  fork-bisimulation claims are *not* supported by the literature and we
  should be honest about that — they extend the algebraic story rather than
  apply an existing one.
- **Theory of mind compositionally**: nothing in the algebraic camp; the
  Bayesian/ProbProg camp does it without algebraic structure. Our
  `simulate-reply` + fork is a genuine combination neither side has tried.

**Net stance for `discourse-model.md`:**
- *Add citations* to Pangolin (Tan et al. 2025) and Plotkin & Xie (2025) as
  the formal foundation for the composition primitives §6 and the laws §7.
- *Add a footnote* citing Bradley/Mahadevan/Zhang as background for "a single
  LLM has a categorical reading" but be clear they don't ground our work.
- *Do not over-claim* operadic / ∞-categorical foundations for `debate` or
  fork-bisimulation — those rest on classical coalgebra + our own
  construction, not on existing LLM-algebraic literature.
- *Flag as open problem*: a duoidal / operadic formal account of
  multi-participant composition is a real research opening that nobody has
  taken. We'd be the first.
