# BIRD on Datahike (text-to-SQL, and Datalog)

Part of the [benchmark catalog](../../doc/benchmarks.md); how to run an experiment and read its
report: [doc/running-benchmarks.md](../../doc/running-benchmarks.md).

BIRD (https://bird-bench.github.io, CC BY-SA 4.0; dev set: 11 SQLite databases, 1,534 questions
with gold SQL; `dvergr.benchmarks.bird.*`). A question is answered by a query; upstream's
execution accuracy runs it and compares its SET of result rows with the gold SQL's on SQLite
(Python equality: 1 = 1.0). The question here is whether Datahike, the store dvergr is built
on, is competitive as the database under such work, with SQL (through pg-datahike) and with
Datalog.

**Reference setup.** [`benchmarks/reference/`](../reference/README.md) runs BIRD's
own one-prompt harness on 100 `:eval` questions a Dvergr experiment ran, graded by upstream's
code: GPT-5.6 Luna 51 of 100 with BIRD's prompt, 61 with one line about returning only the
asked-for columns, against 65 in Dvergr's SQLite workflow (paired: 3 and 7 questions one way
and the other, p = 0.34; the plain prompt against it 1 and 15, p = 0.0005).

- **Data:** every SQLite table is loaded into Datahike as attributes `:table/column` with
  pg-datahike's row marker (`bird.load`), so the same data answers SQL and `d/q`.
  Identifiers are lower-cased (SQLite's are case-insensitive), and value types are inferred
  from the values (SQLite does not enforce declared types).
- **Candidates:** one loop for every engine (`bird.provider`). The candidate sees the
  question, BIRD's evidence and the schema, with a `query` tool (run a query, see up to 50
  rows) and `submit`. The engine is the candidate's:
  - `:sqlite`: SQL on SQLite, as upstream;
  - `:pg-datahike`: SQL on Datahike;
  - `:datalog`: a Clojure expression over `(q query & inputs)`, evaluated in the sandbox.
    Ranking uses the map query form's `:order-by`, `:limit` and `:offset` (since #210; a
    vector query with `:order-by` reads as the map form).
- **Read-only SQL** (provider version 14): a candidate's SQL must be one read statement
  (SELECT or WITH); pg-datahike runs each query in a fresh session that is itself read only
  (`SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY`: pg-datahike refuses any write or
  DDL with SQLSTATE 25006), SQLite connections open the file read-only (`mode=ro`) and cannot
  ATTACH files, and Datalog queries an immutable db value. The databases are shared host
  stores, not forked per Attempt.
- **Grading:** the evaluator re-runs the submitted query on the candidate's engine and
  compares rows with the gold.
- **Grader cross-check** (2026-10-08): for 200 stored SQLite answers (100 Haiku 5.5 from
  `bird-haiku55`, 100 Luna from `bird-a4`), the reward our grader certified was compared with
  upstream's `execute_sql` from `evaluation.py` (DAMO-ConvAI `bird/llm/src`: `set(fetchall
  (pred)) == set(fetchall(gold))`, an exception scores 0), run on the same SQLite files opened
  read-only with a 30 s timeout. They agreed on 200/200 (134 right, 66 wrong; no answer
  where one said right and the other wrong). The pg-datahike and Datalog answers have no
  upstream grader to compare with.

**Substrate check (zero tokens, `bird.compat`).** Every gold query on SQLite and through
pg-datahike on the same data, raw and through a SQLite→PostgreSQL rewrite (`bird.dialect`:
backticks and case-folding, `IIF`, `STRFTIME` on ISO text, `INSTR`, `DATE('now')`,
`LIMIT a, b`, SQLite's NULL ordering, negative `SUBSTR` starts, case-insensitive `LIKE`).

All 11 dev databases, 1,534 questions (#207, measured on 2026-09-29): with the dialect
rewrite, **1372 / 1534 (89.4%)** return SQLite's exact rows on pg-datahike 0.1.264.

The first check (2026-09-28) covered 6 of the 11 databases (858 questions):

| | exact match with SQLite |
| --- | --- |
| raw BIRD SQL through pg-datahike | 624 / 858 (73%) |
| with the dialect rewrite | 748 / 858 (87%): simple 437/479, moderate 227/277, challenging 84/102; 774 / 858 with #207 |

The remaining 110 of that first check split into:
- pg-datahike defects, 11 of them, each reproduced in isolation and handed over
  (2026-09-28). The worst: every window function returns NULL after the 8th row (a
  transient written in place). Others include a derived table's `ORDER BY … LIMIT` and
  `HAVING` being ignored, and `DISTINCT … ORDER BY … LIMIT` over a join dropping rows.
- SQLite semantics PostgreSQL rightly rejects (type affinity, lenient CAST, bare GROUP BY
  columns), for a proposed SQLite compatibility mode.
- Ties at `LIMIT` and float summation order, which are not defects.


**Model runs, Luna, SQL on SQLite vs Datalog on Datahike** (2026-09-28). Harness iterations on
the dev split (`:split :dev`, a third of each database's questions by digest): 4 databases ×
8 questions × 2, 128 cells each, a few minutes each, the weekly window unmoved. Later
versions and the held-out results follow below.

| Harness | SQL on SQLite | Datalog | Datalog − SQL [95%] | Datalog query errors | Datalog cost vs SQL |
| --- | --- | --- | --- | --- | --- |
| v1 (first run, not the dev split) | 0.625 | 0.578 | −0.05 [−0.16, +0.06] | 75 / 223 | 5.5× |
| v2: plain EDN queries, equal example rows | 0.69 | 0.31 | −0.38 [−0.55, −0.20] | 57 / 188 | 2.5× |
| v3: canonical identifiers, a query-only reply counts | 0.70 | 0.53 | −0.17 [−0.34, −0.00] | 8 / 105 | 1.5× |
| v4: `:find`'s set semantics and `:with` named | 0.73 | 0.55 | −0.19 [−0.36, −0.02] | 14 / 122 | 1.7× |

What each step fixed:
- **v1:** the model closed Clojure quotes like SQL strings, `(q '[...]')`. The prompts were
  also unequal, with example rows for Datalog only.
- **v2:** Datalog got plain EDN queries, and every engine got the same example rows. The
  model then often answered with the query as text instead of calling submit, and columns
  with spaces (`:frpm/county name`) cannot be written as keywords.
- **v3:** identifiers became safe keywords for both Datahike engines (`bird.load/2`, with the
  dialect mapping quoted SQL names the same way; the compatibility report is unchanged at
  748/858), and a final reply that is only a query counts as submitted, for every engine.
  Query errors fell from 30% to 8%.
- **v4:** the prompt names the remaining Datalog-specific error: `:find` is a set, so
  `(avg ?h)` without `:with ?e` averages distinct heights. That didn't move the score
  measurably.

What holds:
- With Luna, Datalog answers about 0.17–0.19 fewer questions than SQL at 1.5–1.7× the tokens.
- Its failures are now mostly wrong but running answers, which is model skill in a language
  it has seen far less of, not the substrate.
- Some losses are float last-digit differences from the order of operations, graded wrong by
  upstream's exact comparison for any engine.

**Provider versions 5–14** (2026-09-29 to 2026-10-02; each version is in the verifier's
basis, so a change never resumes into old cells):
- **5** (#209): aggregates see every row of the join, as in SQL (`:with` over the entity
  variables); exact ratios are graded as their nearest double.
- **6–7** (#210): sort and limit with the map form's `:order-by`/`:limit`/`:offset`; the
  arithmetic example takes each aggregate from its own query.
- **8** (#214): a submitted query that fails comes back with its error (every engine);
  Datalog queries have a 60 s deadline; a shorter Datalog description; a final reply that is
  not a working query gets one reminder to submit.
- **9** (#215): agent-written queries resolve functions as the Datahike server does, not
  through the host.
- **10–11** (#216): the description names the functions a clause may call, subqueries and the
  0-based `:order-by` index; an experimental `:nested` candidate (nested calls made it worse:
  0.625 vs flat 0.688).
- **12** (#224): Datahike binds a function clause to a constant itself; `if` and
  `clojure.math` in clauses.
- **13** (#232): the description says rows are already distinct and how to return the row
  with the largest value without that value, the two Datalog-only failures of the held-out
  tier-1 run.
- **14** (#242): a candidate's SQL may only read (see Candidates).

**Held-out results** (`:split :eval`, Luna = `codex-subscription-luna`, paired: both engines
answer the same questions):

| Measured | Questions | SQL on SQLite | Datalog | Notes |
| --- | --- | --- | --- | --- |
| 2026-09-30 | 84 | 0.732 | 0.708 | Datalog − SQL −0.024 [95%: −0.08, +0.03] |
| 2026-10-01, tier 1 | 100 | 0.67 | 0.64 | within variance (McNemar p = 0.51) |
| 2026-10-01, A3 (100 fresh questions) | 100 | 0.59 | v13: 0.63, v12: 0.58 | v13 − v12 +0.05 [−0.01, +0.11], p = 0.23; Datalog vs SQLite not significant (p = 0.29) |

On held-out questions Datalog on Datahike is within noise of SQL on SQLite with Luna, at about
1.6× SQLite's tokens per question. Open: the cost gap.

SQL on Datahike remains the other Datahike candidate: 89.4% of the gold queries already return
SQLite's exact rows, and the model writes the SQL it is best at.

