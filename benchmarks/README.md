# dvergr-benchmarks

Benchmarks for dvergr's evaluation path.

- **Public benchmarks**, each with its section in
  [doc/benchmarks.md](../doc/benchmarks.md):
  - **tau2** (retail, banking, airline, telecom) and **BFCL** v4 (the Python
    single-turn categories), transcribed and checked for equivalence with
    upstream's own code;
  - **AutomationBench** (`automationbench`): upstream's Python tools and rubric
    behind a sidecar process;
  - **BIRD** (`bird`): text-to-SQL, SQL on SQLite and on Datahike
    (pg-datahike), and Datalog;
  - **SpreadsheetBench** (`spreadsheetbench`): the Verified 400 tasks, graded on
    rechentafel over the tasks a zero-token oracle certifies.
- **Bank booking** (`bankbooking`): writes the bank-booking case pack
  (`examples/workflows/bank-booking`, DATEV via Kontor), a room workflow run with
  `dvergr.catalog.room-run` (doc/room-workflows.md).
- **Dvergr's own fixtures**: coding repair (`permutation`, `rss`, on
  `coding-workspace`): [doc/coding-benchmarks.md](../doc/coding-benchmarks.md);
  frozen-web discovery, citation verification and market evidence
  (`discovery`, `discovery-citations`, `frozen-web`, `market-evidence`):
  [doc/practical-agent-evaluation.md](../doc/practical-agent-evaluation.md).

This is its own artefact, `org.replikativ/dvergr-benchmarks`. The `dvergr` jar
does not contain it. What stays in `dvergr` is what a benchmark is built
*with*: `dvergr.agent.evaluation`, `environment`, `experiment`,
`experiment.runner`, `verifiers`, `roster`. A benchmark of your own is a third
provider next to these, in your repo (the provider contract:
doc/benchmarks.md, "What a benchmark brings").

| | |
| --- | --- |
| `src/` | `dvergr.benchmarks.*`; what the providers share: `pyjson`, `python` (Python value semantics), `live` (model-backed generate functions) |
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

Task data is not vendored. Tests that need it report as pending without it.

| Benchmark | Data |
| --- | --- |
| tau2 | a pinned `../tau2-bench` checkout (https://github.com/sierra-research/tau2-bench; file digests are checked) |
| BFCL | a pinned `../gorilla` checkout (https://github.com/ShishirPatil/gorilla; file digests are checked) |
| AutomationBench | `AUTOMATIONBENCH_ROOT`, default `~/.cache/dvergr-bench/automationbench`: `git clone https://github.com/zapier/AutomationBench`, check out `4a8e1061254004d9dac807054eed33fad7d1ff14`, then `uv sync && uv pip install --python .venv/bin/python time-machine` in it |
| BIRD | `BIRD_ROOT`, default `~/.cache/dvergr-bench/bird/dev_20240627`: BIRD's `dev.zip` (https://bird-bench.github.io) unpacked, with `dev_databases.zip` unpacked in it |
| SpreadsheetBench | `SPREADSHEETBENCH_ROOT`, default `~/.cache/dvergr-bench/spreadsheetbench/data/spreadsheetbench_verified_400`; experiments also need the oracle's certification at `~/.cache/dvergr-bench/spreadsheetbench/oracle-verified-400.edn`, written once with `(spit (spreadsheetbench.experiment/oracle-file) (pr-str (spreadsheetbench.oracle/report)))` |
| Bank booking | none: the bundle and its DATEV export are in `examples/` |

How to run one (aliases, credentials, a dedicated JVM, reading report.md):
[doc/running-benchmarks.md](../doc/running-benchmarks.md). Licences of the
transcribed code: [NOTICE](NOTICE).
