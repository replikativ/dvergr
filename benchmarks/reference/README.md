# Reference setups: BIRD and SpreadsheetBench as their authors run them

These scripts run each benchmark's own published harness with a model that Dvergr's model
layer reaches, on the same cases a Dvergr experiment ran, and grade the replies with the
benchmark's own evaluation code. They give the comparison numbers for the question "how much
of a score is the model, and how much the workflow around it?".

They are standalone reproduction scripts, not part of Dvergr: nothing here is on a classpath,
and Python is needed only to run them. Kaocha and CI do not see them.

| | Reference setup (here) | Dvergr's workflow (`dvergr.benchmarks.*`) |
| --- | --- | --- |
| BIRD | One prompt (schema DDL, hint, question), one reply, no data seen. | A loop with a `query` tool: the model runs queries, sees up to 50 rows, then submits. |
| SpreadsheetBench | The instruction and the first 5 rows of each sheet; the model writes openpyxl code. Single round: run once. Multi-round (`row_react_exec`): the code's output or error is shown, up to 5 turns, until the output file exists. | The workbook as a rechentafel value: the model reads and writes cells, sees what formulas compute, and submits. |
| Grading | Upstream's code: BIRD `execute_model`, SpreadsheetBench `compare_workbooks`. | Ports of the same graders (`bird.core`, `spreadsheetbench.core`). |

## Layout

```
mcnemar.py              exact McNemar test between two verdict files (both benchmarks)
dvergr_verdicts.clj     per-case verdicts of a Dvergr experiment's candidate, to pair with
dvergr_verdicts.py      the same for experiment directories of the layout before homes
bird/
  ids.txt               the 100 question ids
  prompts.py            builds the reference prompts (upstream code), --columns adds one line
  ask.clj               sends each prompt once to a model, saves replies and token usage
  grade.py              grades replies with upstream's execute_model
  upstream/             gpt_request.py, evaluation.py (vendored unmodified) and LICENSE (MIT)
spreadsheetbench/
  ids.txt               the 59 task ids
  fetch.sh              upstream checkout at the pinned commit + verified_400 data
  driver.py             upstream's single and multi-round inference loops
  bridge.clj            the model calls for driver.py (JSON lines over stdin/stdout)
  exec/                 Dockerfile + kernel_server.py: the sandbox image sbref-exec:1
  grade.py              grades with upstream's compare_workbooks, as shipped and recalculated
  summarize.py          scores, tokens and time per task, turns, replies without code
```

## The cases

- **BIRD**: 100 questions of the dev set (`dev_20240627`), all in Dvergr's `:eval` split
  (`dvergr.benchmarks.bird.experiment/split-of`: a question is `:dev` when the first 8 hex
  digits of sha256("<db-id>/<question-id>") fall in the lowest third). They are the
  `:question-ids` of the Dvergr BIRD experiments in the post (11 databases).
- **SpreadsheetBench**: 59 tasks of `spreadsheetbench_verified_400`, all in Dvergr's `:eval`
  split (`dvergr.benchmarks.spreadsheetbench.experiment/split-of`, sha256 of
  "spreadsheetbench/<id>") and certified by Dvergr's oracle. They are the `:ids` of the Dvergr
  SpreadsheetBench experiments in the post.

The splits are recomputable without a JVM:

```bash
python3 -c 'import hashlib,sys; print(sum(int(hashlib.sha256(("spreadsheetbench/"+i).encode()).hexdigest()[:8],16) >= 0x100000000//3 for i in open(sys.argv[1]).read().split()))' \
  benchmarks/reference/spreadsheetbench/ids.txt          # 59: all :eval
```

## Prerequisites

- This repository's toolchain: a JDK and the Clojure CLI (`clojure -M:benchmarks` must start).
  The model is called through `dvergr.benchmarks.live/model-generate`, so the model id is a
  `dvergr.model.registry` id with its credentials set up as for any Dvergr run:
  `codex-subscription-luna` (GPT-5.6 Luna, a logged-in Codex subscription),
  `claude-code-haiku` (Claude Haiku 5.5 through the Claude Code CLI, `claude` on the PATH).
- Python 3.10 or later (run with 3.12.2). BIRD: `pip install func_timeout` (upstream's
  evaluation.py imports it). SpreadsheetBench, on the host: `pip install pandas openpyxl numpy tqdm`
  (run with pandas 3.0.2, openpyxl 3.1.5, numpy 2.2.6, tqdm 4.67.3; the 5-row preview in the
  prompt is pandas' `to_string`, so another pandas version can change the prompt).
- SpreadsheetBench: Docker (run with 29.8.0) for the sandbox, and LibreOffice (run with
  26.2.5.2) for the recalculated grade.
- Data, at the defaults Dvergr's providers use:
  - BIRD: `BIRD_ROOT`, default `~/.cache/dvergr-bench/bird/dev_20240627`: BIRD's `dev.zip`
    (https://bird-bench.github.io) unpacked, with `dev_databases.zip` unpacked in it.
  - SpreadsheetBench: `spreadsheetbench/fetch.sh` checks out upstream at the pinned commit
    (`SPREADSHEETBENCH_REPO`, default `~/.cache/dvergr-bench/spreadsheetbench/repo`) and
    unpacks verified_400 from it (`SPREADSHEETBENCH_ROOT`, default
    `~/.cache/dvergr-bench/spreadsheetbench/data/spreadsheetbench_verified_400`).

Commands below run from the repository root. Outputs go to `~/.cache/dvergr-bench/...`
(override with the variables named); none of them belong in the repository.

## BIRD

The reference prompt is upstream's `generate_combined_prompts_one` (DAMO-ConvAI
`bird/llm/src/gpt_request.py`) with BIRD's evidence as knowledge, as `run_gpt.sh`'s
`use_knowledge=True` run builds it: every table's `CREATE TABLE`, the evidence, the question,
"Generate the SQL after thinking step by step:" and a trailing `SELECT `. The chat
adaptation: the prompt is the only user message (no system prompt from us, no tools, no
temperature or stop sequences; upstream sent completions with temperature 0, max_tokens 256,
stop `--`, blank line, `;`, `#`), and the SQL is the reply's last fenced block (else the reply
if it starts with SELECT or WITH, else `SELECT ` + the reply; `bird/grade.py`).

```bash
B=~/.cache/dvergr-bench/bird-reference; mkdir -p $B
python3 benchmarks/reference/bird/prompts.py $B/prompts.json
python3 benchmarks/reference/bird/prompts.py --columns $B/prompts-cols.json

for m in codex-subscription-luna claude-code-haiku; do
  MODEL=$m clojure -J-Xmx2g -M:benchmarks benchmarks/reference/bird/ask.clj $B/prompts.json      $B/replies-$m.json
  MODEL=$m clojure -J-Xmx2g -M:benchmarks benchmarks/reference/bird/ask.clj $B/prompts-cols.json $B/replies-cols-$m.json
done

for f in replies-codex-subscription-luna replies-cols-codex-subscription-luna \
         replies-claude-code-haiku replies-cols-claude-code-haiku; do
  python3 benchmarks/reference/bird/grade.py $B/$f.json $B/verdicts-$f.json
done
```

`N=3` before `ask.clj` sends only the first three prompts (a smoke test).

| Workflow | GPT-5.6 Luna | Claude Haiku 5.5 | Tokens per question (Luna) |
| --- | ---: | ---: | ---: |
| BIRD's prompt, one reply (`replies-*`) | 51 | 53 | 1,200 |
| the same, plus the line about columns (`replies-cols-*`) | 61 | 64 | 1,200 |

The column line is `-- Return exactly the columns the question asks for, no extra columns.`,
after the question. Tokens are input (including cached) plus output, the mean `grade.py`
prints. Haiku's input is larger (about 4,500 tokens) because the Claude Code CLI adds its own
context to the prompt.

The models are not deterministic: a rerun gives scores near these, not necessarily equal
(see "What a rerun reproduces" below).

Paired tests (below): Luna 51 against 61, 2 and 12 questions one way and the other, p = 0.013;
Haiku 53 against 64, 1 and 12, p = 0.0034.

```bash
python3 benchmarks/reference/mcnemar.py $B/verdicts-replies-codex-subscription-luna.json \
                                        $B/verdicts-replies-cols-codex-subscription-luna.json
```

## SpreadsheetBench

Build the sandbox image once:

```bash
docker build -t sbref-exec:1 benchmarks/reference/spreadsheetbench/exec
bash benchmarks/reference/spreadsheetbench/fetch.sh
```

Each task's code runs in a fresh container of that image: no network, read-only root, a
the invoking user's uid/gid (not root), 4 GB and 2 CPUs, and only the task's input workbook mounted at `/mnt/data`
(upstream runs one shared Jupyter kernel gateway with the whole dataset directory mounted,
answers included). `driver.py`'s docstring lists every change from upstream's
inference scripts; the prompts, the 5-row preview, code extraction and execution feedback
are read from the upstream checkout and run unmodified.

```bash
python3 benchmarks/reference/spreadsheetbench/driver.py row_react_exec rre      # multi-round
python3 benchmarks/reference/spreadsheetbench/driver.py single single          # single round
python3 benchmarks/reference/spreadsheetbench/grade.py rre
python3 benchmarks/reference/spreadsheetbench/grade.py single
python3 benchmarks/reference/spreadsheetbench/summarize.py rre
python3 benchmarks/reference/spreadsheetbench/summarize.py single
```

`driver.py` starts `bridge.clj` itself (`clojure -J-Xmx2g -M:benchmarks …`, from the
repository root) and runs four tasks at a time; `MODEL` picks the model (default
`codex-subscription-luna`). Results go to `~/.cache/dvergr-bench/sbref/<tag>/` (`SBREF_OUT`):
`results/<id>.json` (the conversation, per-turn usage and time) and `outputs/<id>.xlsx`. A
rerun with the same tag runs only the tasks without a result or with an error. The container
mounts are staged in `~/sbref-stage` (`SBREF_STAGE`): Docker installed as a snap mounts only
non-hidden directories under `$HOME`. With LibreOffice as a snap, `grade.py` stages its
conversion in `~/snap/libreoffice/common/sbref-stage` (`SBREF_LO_STAGE`), the only place
the snap can read and write.

| Harness, GPT-5.6 Luna, 59 tasks | Graded as shipped (`raw`) | After recalculation (`lo`) | Tokens per task (median) | Median time |
| --- | ---: | ---: | ---: | ---: |
| one reply with code (`single`) | 14 | 34 | 2,200 | 15 s |
| up to five turns with execution (`row_react_exec`) | 18 | 41 | 4,300 | 17 s |

*Graded as shipped* is upstream's `compare_workbooks` on the workbook as written: openpyxl
reads cached values, and a formula openpyxl wrote has none, so it reads as empty. *After
recalculation*, the produced workbook and the golden file both go through LibreOffice's
conversion (as upstream's `evaluation/open_spreadsheet.py` does) before the same comparison.
`summarize.py` prints both scores, tokens (input including cached, plus output) and wall
time per task, the turns, and the replies without code (29 of 124 in the multi-round run).
As for BIRD, a rerun gives scores near these, not necessarily equal.

Upstream's `evaluation.py` main cannot score verified_400 as shipped: it reads each task's
input file (the output path is commented out) and expects earlier file names. `grade.py`
therefore calls only `compare_workbooks`. Task 45944's answer position contains spaces, and
the comparison raises on it for every answer.

## Paired tests (McNemar)

Two candidates of one Dvergr experiment need none of these scripts: the experiment's report
(`experiment_report`, `runner/report`) has a **Paired comparisons** table with the same
exact McNemar test (Holm-adjusted across pairs; doc/running-benchmarks.md). The scripts are
for pairing a Dvergr run with a run outside Dvergr, such as the reference setups here.

Every comparison in the post is paired: two candidates on the same cases. For two verdict
files, `mcnemar.py` counts b (A right, B wrong) and c (the reverse) and reports the exact
two-sided binomial test of b against Binomial(b + c, 1/2):
p = min(1, 2 · Σ_{i ≤ min(b,c)} C(b+c, i) / 2^(b+c)). Ties (both right or both wrong) do not
count. Verdict files are `{case-id: 0|1}`; for SpreadsheetBench's `grade.json` name the
field: `grade.json:lo` or `grade.json:raw`.

To pair a reference run with a Dvergr run of the same model on the same cases, extract the
Dvergr experiment's verdicts (its `:dir` and the candidate's `:id`) and test. Both files must
grade the same cases; `mcnemar.py` refuses otherwise.

```bash
clojure -J-Xmx2g -M:benchmarks benchmarks/reference/dvergr_verdicts.clj ~/.cache/dvergr-bench/<bird-experiment> luna-sqlite $B/dvergr-luna.json
python3 benchmarks/reference/mcnemar.py $B/verdicts-replies-cols-codex-subscription-luna.json $B/dvergr-luna.json

clojure -J-Xmx2g -M:benchmarks benchmarks/reference/dvergr_verdicts.clj ~/.cache/dvergr-bench/<sb-experiment> luna ~/.cache/dvergr-bench/sbref/dvergr-luna.json
python3 benchmarks/reference/mcnemar.py ~/.cache/dvergr-bench/sbref/rre/grade.json:lo ~/.cache/dvergr-bench/sbref/dvergr-luna.json
```

`dvergr_verdicts.clj` opens the experiment's home, so run it in a JVM of its own, not beside a
daemon. The post's Dvergr experiments predate homes (an `artifacts/` directory); for those,
`python3 benchmarks/reference/dvergr_verdicts.py DIR CANDIDATE OUT.json` reads the artifacts.

For the post's runs this gives, on SpreadsheetBench, the multi-round harness after
recalculation (41) against Luna on Dvergr's harness and checker (51): 5 and 15, p = 0.041;
on BIRD, the reference prompt with the columns line (61) against Luna on Dvergr's SQLite
workflow (65): 3 and 7, p = 0.34, and the plain prompt (51) against it: 1 and 15, p = 0.0005.
The post's other SpreadsheetBench comparison (Dvergr's own workbooks put through the same
recalculation, 41 as written and 48 Excel-encoded) replays Dvergr's recorded cell writes onto
the input workbooks and is not part of these scripts.

The Dvergr experiments are run as `benchmarks/README.md` and `doc/benchmarks.md` describe,
with `:split :eval` and these ids (`:question-ids` for BIRD, `:ids` for SpreadsheetBench).

## What a rerun reproduces

The prompts are deterministic: `prompts.py` and `driver.py` build, byte for byte, the prompts
the post's runs sent (checked for all 100 BIRD questions in both variants and all 59
SpreadsheetBench tasks in both settings). The grading is deterministic too: run on the
post's recorded replies and workbooks, `bird/grade.py`, `spreadsheetbench/grade.py`,
`summarize.py` and `mcnemar.py` give every number above exactly.

The model replies are not deterministic, and this repository carries no model outputs. A
rerun therefore measures the same setup again: expect scores within a few points of the
post's, and McNemar counts that differ case by case. The conclusions to check are the
differences the post draws (the columns line against the plain prompt; graded as shipped
against recalculated), each re-tested on the rerun's own paired verdicts.

## Upstream code and licenses

| Benchmark | Upstream | Revision | License | Here |
| --- | --- | --- | --- | --- |
| BIRD | [AlibabaResearch/DAMO-ConvAI](https://github.com/AlibabaResearch/DAMO-ConvAI) `bird/llm/src/gpt_request.py`, `evaluation.py` | commit `188835d4f9948563a6b9c8ac50cd0f3ae4021ed6` (the files last changed in `03898f5`, 2023-05-04, and `dec31ae`, 2023-06-14) | MIT (code); the BIRD data is CC BY-SA 4.0 and is not included | vendored unmodified in `bird/upstream/` with a provenance header and the MIT license; `sed '1,/^# ---- upstream ----$/d' FILE \| sha256sum` checks the bytes |
| SpreadsheetBench | [RUCKBReasoning/SpreadsheetBench](https://github.com/RUCKBReasoning/SpreadsheetBench) | commit `49b73a94775fb489063f60ca1865e3a650079a79` | CC BY-SA 4.0 (code and data) | nothing copied: `fetch.sh` checks out the commit and the scripts read upstream's code from it. `driver.py` and `exec/kernel_server.py` adapt upstream's inference loop and kernel client and are under CC BY-SA 4.0, as the license requires of adaptations |

The rest of this directory is under the repository's Apache License 2.0.
