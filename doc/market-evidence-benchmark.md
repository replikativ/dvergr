# Market evidence benchmark, v1

This consolidates the earlier experimental source-faithfulness pilot into the
existing EnvironmentDef / Evaluator / Attempt programming model. It adds no
agent loop, scheduler, accounting ledger or alternative certification mechanism.

The task is a prerequisite for a useful market report: distinguish documented
capabilities, explicit limitations and unsupported claims. It is **not** a test
of competitor discovery, deployed functionality, market size or willingness to
pay. The source is documentation about a development-stage product.

## Frozen input and verifier

Five selected plaintext excerpts come from `simm.is` revision
`6c4034660ce5dae5aaae557e364dd5776bc9b243`, at
`src/pages/docs/known-limits.astro`. The original file SHA-256 is
`3407d03d8182099df93f4adde8febd38eb28a332c242b69fac5e63e8ba5da7db`.
The revision and hash were checked against the sibling checkout on 2026-09-06.
The fixture identity also hashes the extracted text, claims and provenance.

Candidates classify nine claims as supported, contradicted or unknown. Supported
and contradicted claims need an exact, relevant complete sentence and the correct source
ID. Unknown claims must not invent evidence. The host verifier checks a narrow
set of explicit evidence anchors; it is not a general entailment judge. Exact
format and completeness gate dense credit over the nine claim checks.

Only the task, sources and claims are sent to the candidate. The answer key and
trusted evaluator are not included in the prompt. The initial model candidate
has no tools, isolating source fidelity from acquisition and tool discovery.
Changing the fixture, verifier basis or execution policy changes the appropriate
content-addressed definition/reference.

## REPL entry point

With `src` and `dev` on the classpath:

```clojure
(require '[dvergr.agent.market-bench :as market])

;; Returns a lazy ordinary evaluation Spin. The caller owns room and binds its
;; Spindel execution context when executing the Spin.
(market/evaluate room :codex-subscription "codex-subscription-sol")
```

Use a caller-owned Room to retain and inspect the ordinary Run, Attempt and
Episode. Join evaluation cleanup before closing that Room. An in-memory Room
retains facts only for that process lifetime; it is not restart durability.

Execution has a 180-second deadline and a ten-second cancellation grace. The
candidate has a two-request provider-loop fuse and a nominal dollar budget.
The Codex subscription registry's zero token prices do **not** enforce a token
quota or establish that the work is free: retain actual reported token usage,
elapsed time and model identity. This probe does not claim allocation accounting
for virtual-thread work. No web acquisition, browser or publication is needed.

## Validation results and release dependency

Pure tests cover labels, exact provenance, relevant rather than merely present
quotes, invented demand, malformed/trailing EDN, completeness, and the canonical
Environment/Evaluator reference pair. Including scripted certification and
cleanup, the revised benchmark passes 4 tests / 35 assertions locally.

The first scripted certification attempt exposed a missing Spindel callback:
the existing `agent/program.clj` integration uses `spawn! :on-success`, but
released Spindel 0.1.44 ignores that option. A minimal patch on a worktree based
on `v0.1.44` adds success delivery after GC-pin release, with immediate,
suspended-success and failure tests. Validation uses a local dependency override
to `/tmp/spindel-spawn-callback`; the published dependency remains unchanged.
That was the baseline of the original probe, not a remaining release blocker:
on 2026-09-07 the remote check confirmed that Spindel **0.1.47** already contains
the terminal-callback integration, and Dvergr main `78eae43` already pins it.
The integration branch uses that published dependency without a local override.

On 2026-09-06, the approved Codex subscription probe completed through the normal
Room / Run / certified Attempt path:

- Model alias `codex-subscription-sol` (`gpt-5.6-sol`), one request, no tools.
- All nine claims correct, schema and completion checks passed; reward **1.0**.
- Attempt elapsed time **16,977 ms**.
- Provider response: **565 input / 328 output tokens**, including a separately
  reported reasoning-output count of 84; no cache-read tokens.
- Certified Attempt matched the room store; no active Run remained; cleanup
  joined before the in-memory room closed.

That historical probe used Environment content ID
`0061f7f9-b854-578d-b137-5ec56abca70a` and the original substring quotation rule.
Review tightened the current task/verifier to complete sentences so a compliant
answer cannot miss a hidden anchor by shortening its quotation. This changes
the task and verifier content identities; no live result is claimed for the
revised contract yet. Sentence splitting is deliberately specific to this small
frozen fixture, not a general natural-language sentence parser.

This is one successful narrow fixture, not a statistical capability estimate or
validation of a market report. Scripted correct and malformed answers also test
certification, reward, persistence and Run cleanup without a model.

The probe exposed an accounting discrepancy: its Attempt metrics contain 656
output tokens rather than the provider's 328. The integration fixes this by
making message `:tokens` metadata only: provider response usage is accounted
explicitly, once, with model pricing. Regression tests check both reactive
budget state and durable ledger rows/costs for subscription and paid-model
prices, plus preservation of token metadata without billing history insertion.
This intentionally changes `add-message!`: callers that need to record actual
provider consumption must call `account-tokens!` separately. Existing historical
receipts are not rewritten. The original probe's counts above come from its
provider response log; its stored usage/cost totals remain unsuitable for
resource comparisons.

Next: attach acquired-source receipts and expand into competitor discovery and report
assembly. Keep source coverage separate from evidence correctness and buyer
validation.

## Next acquisition/discovery slice

Keep the agent-modifiable `dvergr.intake.web-search` and
`dvergr.intake.web-fetch` source interfaces. Instrument their existing native
HTTP boundary rather than introducing another crawler or agent loop. The
acquisition-history prototype in the earlier experimental worktree is not yet
on current main; reconcile it before wiring a live discovery evaluator.

The next deterministic environment should expose a small frozen web corpus
through those same interfaces, including a failed fetch and irrelevant results.
Its host verifier joins submitted citations to actual Run-correlated acquisition
receipts and immutable captured text, never to an agent's claimed fetch log.
Measure discovery coverage, evidence validity and report quality separately.
The website's competitor shortlist is a held-out, incomplete reference set—not
the complete market or proof of competitive superiority. Do not put it in the
discovery task or allow the candidate to read the reference site during discovery.

Only after this contract passes without a model should it run against public
sources, then compare one researcher with a researcher/reviewer team through
the existing Experiment API. Bound attempts, concurrent work, deadlines,
HTTP count/response sizes and model consumption; prompt requests alone are not
enforced resource limits. Browser sessions and broad crawling are unnecessary
for this first slice. No new live discovery run is claimed by this PR.
