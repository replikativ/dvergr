# SpreadsheetBench (spreadsheet tasks, on rechentafel)

Part of the [benchmark catalog](../../doc/benchmarks.md); how to run an experiment and read its
report: [doc/running-benchmarks.md](../../doc/running-benchmarks.md).

SpreadsheetBench (https://github.com/RUCKBReasoning/SpreadsheetBench, CC BY-SA 4.0; the
Verified set `verified_400`: 400 tasks from Excel forums, one test case each;
`dvergr.benchmarks.spreadsheetbench.*`). A task is an instruction, an input workbook and an
answer position; upstream grades the cells of the answer range against a gold workbook.

**Reference setup.** [`benchmarks/reference/`](../reference/README.md) runs
upstream's single-round and multi-round (`row_react_exec`) harnesses on 59 `:eval` tasks a
Dvergr experiment ran: GPT-5.6 Luna 14 and 18 graded as shipped, 34 and 41 after LibreOffice
recalculation, against 51 on Dvergr's harness and checker (paired, multi-round recalculated
against Dvergr: 5 and 15 tasks, p = 0.041).

- **Grading** (`spreadsheetbench.core`): upstream's `compare_workbooks` (evaluation.py),
  ported: answer ranges column-major, numbers and booleans rounded to 2 places as Python's
  `round` does, dates to a day serial, times to `HH:MM`, nil and `""` equal; the truth is the
  value Excel saved in the gold workbook. One deliberate difference: rechentafel keeps no
  number formats, so a produced cell is typed by the gold cell's format.
- **Oracle** (`spreadsheetbench.oracle`, zero tokens): for each task, whether the input
  workbook with the gold's answer cells written into it recalculates in rechentafel to the
  answer Excel saved (`:certified`: a correct answer would be graded correct), or, for a
  solution that also edits other cells, whether recalculating the gold workbook reproduces
  its saved answer (`:certified-gold`). The rest are reported with the reason (unsolvable,
  malformed, missing sheet, error, timeout), never charged to a candidate. **390 of 400** tasks are
  certified. Experiments run only certified tasks.
- **Candidates** (`spreadsheetbench.provider`, version 3): the workbook is a rechentafel value
  in memory; the tools are `read` (a range's values and formulas), `write` (values or
  formulas; the workbook recalculates and the candidate sees the computed values) and
  `submit`. The evidence is the list of writes; the evaluator replays them onto a fresh copy
  of the input, recalculates and grades the answer range (`:checks {:submitted :correct}`),
  so a verdict is reproducible without the model.
- **Splits** (`spreadsheetbench.experiment`): `:dev` (a third) and `:eval`, fixed by the
  digest of the task id. `:sample n` takes n tasks of a split, the same ones for a given
  `:seed`. Tune on `:dev`, report on `:eval`.
- **Search** (`spreadsheetbench.smc`): `:search {:particles 4 :twist :oracle}` runs each
  episode as SMC over agent steps (foerster's `steer`): N particles, each an episode advanced
  one model turn at a time and resampled on a value estimate after every turn. Value
  estimates: `:oracle` (the answer cells still wrong; privileged, an upper bound),
  `:structural` (write errors), `:judge` (a judge model's estimate that the answer will be
  correct) or `:none` (best-of-N). Every particle's model calls are counted, so a search is
  compared with single episodes at equal model calls. `:export` writes the searches'
  trajectories as training records.

Data: `SPREADSHEETBENCH_ROOT` (default
`~/.cache/dvergr-bench/spreadsheetbench/data/spreadsheetbench_verified_400`). Experiments
read the oracle's certification from `~/.cache/dvergr-bench/spreadsheetbench/oracle-verified-400.edn`
and throw "Run the oracle first" without it; write it once with
`(spit (experiment/oracle-file) (pr-str (oracle/report)))`.

```clojure
(require '[dvergr.benchmarks.spreadsheetbench.experiment :as sbx])
(sbx/run! {:dir "runs/sb-eval" :split :eval :sample 100
           :candidates [{:id :luna :model "codex-subscription-luna"}]})
```

**Held-out result** (measured on 2026-10-01; provider version 3): a sample of 100 certified
`:eval` tasks, Luna (`codex-subscription-luna`): **85 / 100** (95% interval 77–91%), $0.0045
per pass at list price, median 29 s per task. This is not comparable to the public
leaderboard: it is a subset (certified, held-out), the tool interface differs (read, write and
submit over rechentafel, where the agent sees computed values) and each task has one test
case.

