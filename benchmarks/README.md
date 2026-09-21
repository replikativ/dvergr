# dvergr-benchmarks

Benchmarks for dvergr's evaluation path.

- **Public benchmarks, transcribed**: **tau2** (retail, banking, airline,
  telecom) and **BFCL** v4 (the Python single-turn categories), each checked
  for equivalence with upstream's own code:
  [doc/benchmarks.md](../doc/benchmarks.md).
- **Dvergr's own fixtures**: coding repair (`permutation`, `rss`, on
  `coding-workspace`): [doc/coding-benchmarks.md](../doc/coding-benchmarks.md);
  frozen-web discovery, citation verification and market evidence
  (`discovery`, `discovery-citations`, `frozen-web`, `market-evidence`):
  [doc/practical-agent-evaluation.md](../doc/practical-agent-evaluation.md).

This is its own artefact, `org.replikativ/dvergr-benchmarks`. The `dvergr` jar
does not contain it. What stays in `dvergr` is what a benchmark is built
*with*: `dvergr.agent.evaluation`, `environment`, `experiment`,
`experiment.runner`, `verifiers`, `roster`. A benchmark of your own is a third
provider next to these two, in your repo (the provider contract:
doc/benchmarks.md, "What a benchmark brings").

| | |
| --- | --- |
| `src/` | `dvergr.benchmarks.*`; what tau2 and BFCL share: `pyjson`, `python` (Python value semantics), `live` (model-backed generate functions) |
| `resources/` | tau2 tool schemas emitted by upstream; the pinned source snapshots of the coding fixtures |
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
