# dvergr-benchmarks

Benchmark providers for dvergr's evaluation path: **tau2** (retail, banking,
airline, telecom) and **BFCL** v4 (the Python single-turn categories), each
transcribed to Clojure and checked for equivalence with upstream's own code.
What they measure, how they are verified and how to run them is in
[doc/benchmarks.md](../doc/benchmarks.md).

This is its own artefact, `org.replikativ/dvergr-benchmarks`. The `dvergr` jar
does not contain it. What stays in `dvergr` is what a benchmark is built
*with*: `dvergr.agent.evaluation`, `environment`, `experiment`,
`experiment.runner`, `verifiers`, `roster`. A benchmark of your own is a third
provider next to these two, in your repo (the provider contract:
doc/benchmarks.md, "What a benchmark brings").

| | |
| --- | --- |
| `src/` | `dvergr.benchmarks.{tau2,bfcl}.*`, and what they share: `pyjson`, `python` (Python value semantics), `live` (model-backed generate functions) |
| `resources/` | tool schemas emitted by upstream |
| `test/` | the equivalence digests and the provider tests; run with the suite (`clojure -M:test`) |
| `dev/` | the Python oracles that run upstream's code; needed only to regenerate digests |

Use:

```clojure
;; in this repo
clj -A:benchmarks

;; Maven
org.replikativ/dvergr-benchmarks {:mvn/version "0.1.N"}   ; same version as dvergr

;; git
org.replikativ/dvergr-benchmarks {:git/url "https://github.com/replikativ/dvergr"
                                  :git/sha "..." :deps/root "benchmarks"}
```

Task data is not vendored: tau2 reads a pinned `../tau2-bench` checkout, BFCL
a pinned `../gorilla` one (file digests are checked). Tests that need a
checkout report as pending without it. Licences of the transcribed code:
[NOTICE](NOTICE).
