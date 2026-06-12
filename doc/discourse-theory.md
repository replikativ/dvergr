# Discourse — the theory behind the framework

dvergr calls its core abstraction a **discourse** rather than a "message bus" or
an "agent graph" on purpose: the design takes the *linguistic* case seriously.
Participants — humans, LLMs, scripts — are **speakers** exchanging **utterances**
in a shared room, and the framework's primitives line up with ideas from the
philosophy and the Bayesian modelling of language.

This page is short, optional theoretical context. The working reference is
[programming-model.md](programming-model.md); this is the *why it's shaped this
way*.

## Messages as speech acts

A message carries a `:type`, and that `:type` is its **illocutionary force** — in
the sense of speech-act theory (Austin, Searle): what the utterance *does*, not
just what it says. `:directive/raise-budget` directs; `:escalation/budget`
escalates; `:probe/memory` asks. Participants then **subscribe by the kinds of
speech act they answer** (capability routing), so a room is organised around *who
responds to what kind of move* — not by hardcoded sender→receiver wiring. Adding
a new kind of interaction is adding a new tag, not rewiring the graph.

## Theory of mind: RSA, made reactive

The deepest hook is **theory of mind**. In the Rational Speech Acts model
(Goodman & Frank), a speaker chooses an utterance by predicting how a listener
will update *their* beliefs — pragmatics as recursive Bayesian reasoning. dvergr
makes this *operational* rather than a line in a system prompt, because the
runtime forks cheaply:

```clojure
(defn simulate-reply
  "Fork the room, deliver a hypothetical to `other`, await their reply in the
   fork, then discard it. Returns the imagined reply; the real room is untouched."
  [room other hypothetical]
  (spin
    (let [fork     (fork-room room)
          imagined (await (ask (get-participant fork other) hypothetical))]
      (discard fork)
      imagined)))
```

A speaker can run `simulate-reply` over a few candidate utterances and keep the
one that best moves the listener — **RSA as implementation, not as prompt**. The
same primitive underlies "imagine the conversation before speaking"
(`imagine-conversation`), moderator previews, and debate look-ahead. The
`align-on` combinator — iteratively refining a statement toward common ground —
is named after Tessler et al.'s work on AI-assisted deliberation.

## The inference reading (optional)

Pushed further, the substrate has a probabilistic reading: a participant is a
particle in a Sequential Monte Carlo process, a message is an observation, a fork
is "try another hypothesis," and an accepted artifact is a posterior that becomes
the next prior — *programming as and for inference*. dvergr does **not** require
this mode (most workflows run once, normally, with no resampling); spindel's
inference suite simply makes population SMC over forked rooms available when a
workflow wants it. The mathematical frame is Feynman–Kac (Del Moral).

## Lineage

- **Rational Speech Acts (RSA)** — Goodman, Frank, Tessler et al.,
  <http://www.problang.org>. Pragmatic reasoning as recursive Bayesian inference
  over utterances; dvergr's theory-of-mind hook follows it.
- **Cohn-Gordon & Bärenz** — linguistic-convention convergence + reactive
  probabilistic inference; bridges RSA-style pragmatics with reactive multi-agent
  FRP (Rhine FRP, choreographic programming) — the bridge dvergr's substrate
  makes concrete.
- **rhine-bayes** — Bärenz et al. (Tweag, 2023), *online reactive Bayesian
  inference* (SMC on clock-safe FRP streams); the conceptual ancestor of
  spindel's inference package.
- **AI mediation / common ground** — Tessler et al., *Science* 2024; the
  `align-on` combinator is named for it.
- **Speech-act theory** — Austin, *How to Do Things with Words*; Searle — the
  reading of `:type` as illocutionary force.
- **Feynman–Kac** — Del Moral (2004); the math for the optional inference reading.

*The full internal design (the four-layer SMC ladder, the Feynman–Kac account,
LLaMPPL/ToT/RAP comparisons) is kept out of the public docs deliberately — this
is the distilled version.*
