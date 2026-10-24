# Company cash: constructing models and making decisions

This is a synthetic business benchmark, generated from versioned source. Its
first four businesses reproduce the recovered October 2026 company-cash cases.
Those historical results evaluate forecasts and decisions. They do not establish
that an agent produced a reusable model or that the simulated assumptions fit
real businesses.

## Reproduce and expand

From the repository root:

```sh
clojure -M:benchmarks -m dvergr.benchmarks.company-cash target/company-cash-v1
clojure -M:benchmarks -m dvergr.benchmarks.company-cash target/company-cash-expanded \
  '{:development 8 :held-out 16 :seed 20261024}'
```

The output directory must be absent or empty. With no options, the generator
reproduces the four regression businesses. Tests pin SHA256 hashes of the twenty
original data/gold files. Public `observations.edn` adds structured history,
correspondence and the company contract for executable models. Checker source is
assembled from the same kernel and scorer used natively. Expanded bundles
calibrate their checker on a freshly generated development case.

Businesses have customer-specific payment delay and default parameters,
randomized historical collection actions, open invoices, recurring outflows and
correspondence describing simplified parameter shocks. Plans choose no action,
reminders, calls or factoring under a founder-time and ruin constraint. Generated
businesses vary the template, names, parameters, cash and cost scale, with
separate development/held-out seed ranges. A seed without a paid untreated history row is rejected with its case id, seed and reason before writing an unusable pooled baseline. They share four synthetic mechanism
families; more cases within these families do not test structural misspecification.

The historical kernel is intentionally preserved, including its finite rejection
approximation for overdue invoices. Correspondence is a simplified description
of parameter shifts, not a fully modeled organizational process. The planning
reference uses coordinate search and 300 Monte Carlo worlds: it is best found,
not a proven optimum. Calibration establishes discrimination against specific
bad answers, not empirical business fidelity or accuracy of the numerical
reference posterior.

## Two evaluation paths

The ordinary exported room bundle works with the existing dvergr/simmis workflow
surface. Its v1 checker scores `/out/answer.edn`: 50% decision value relative to the
best-found reference, 30% payment forecast skill and 20% cash/ruin forecast skill.
It does not execute or score `/out/model/`. The scoring contract matches v1,
but the added structured observations change candidate inputs and the bundle
identity; historical and new scores must not be pooled. This initial artifact
slice isolates numerical modeling from document parsing. It does not yet measure
the complete process of extracting a business model from raw evidence.

The native artifact runner explicitly adds executable-model evaluation:

```clojure
(require '[dvergr.benchmarks.company-cash.experiment :as company])
(company/run! {:dir "target/company-cash-home"
               :bundle-dir "examples/workflows/company-cash"
               :models ["codex-subscription-sol-6.1@medium"]
               :split :development :cases 1 :repetitions 1
               :parallelism 1 :budget-dollars 1.0 :timeout-ms 600000})
```

Run this in a dedicated JVM with the `:benchmarks` alias. `:dir` is a fresh dvergr
home, not a copy of another home. Candidates execute in the experiment's fixture
world; the checker and gold remain in host capabilities. Standard experiment
reports, Attempts, resume identity and subscription allowances apply.

A candidate must write `/out/model/receivables.clj`, containing one self-contained
namespace with:

- `fit(observations, seed, budget)` → finite portable maps/vectors. Observations
  contain `:history`, `:notes` and `:company`; budget contains `:max-samples`.
- `simulate(fitted, actions, seed, n)` → a vector of exactly n outcomes, each
  `{:final-cash number :final-value number :ruin? boolean}`.

The evaluator reads this frozen source after execution and creates a fresh SCI
interpreter with no filesystem/network or namespace load path. Hidden parameters
and scoring draws are never supplied to model code. It checks finite data,
repeatability for explicit seeds, cash/value bounds and fully factored cash
conservation. A model that fails execution cannot earn credit from a good final
answer. These checks establish basic executable behavior, not model fidelity.

The same source is refitted on an independent business with new names, histories
and correspondence that was unavailable during construction. Both the original
and transfer business are scored against independent future draws under a fixed
no-action policy. Raw cash quantile losses and ruin Brier losses are retained,
alongside pooled-baseline comparisons, seeds, source identity, elapsed evaluation
time and simulation counts. The bounded reward is 70% mean predictive quality and 30% the original
business's v1 answer score. Cash quality is `1 - pinball / (1.5 * R)`, where R is
the public sum of invoice amounts; ruin quality is `1 - Brier`. Model quality
weights cash at 75% and ruin at 25%. These positive affine transforms preserve
the forecasts elicited by the raw proper losses. Bounds are checked on model
outcomes, with numerical rounding protection at score boundaries. They elicit
three cash quantiles and ruin probability, not the entire simulator distribution.

Baseline-relative skill is a separate diagnostic: baseline earns zero, negative
values expose regressions, and a baseline/reference loss gap of at most 1e-6 is
reported as uninformative (nil). All panels and both forecast components still
contribute raw proper loss and absolute quality, so a wrong ruin forecast is
penalized even when the pooled baseline is already correct. Separate original,
aggregate and transfer checks report forecast improvement and quality versus the
pooled baseline. A correct baseline can earn high quality without claiming
improvement. Historical
and artifact scores therefore belong to different experiments.

Each model evaluation defaults to 64 samples, 10 seconds, 4 GiB cumulative
thread allocation (garbage included: a runaway backstop, not a memory gauge) and
a finite function-call allowance. Checks are cooperative SCI limits on the owned
scoring worker, not a hard OS isolation boundary: they run at interpreted function
calls, so a single native operation (a huge array, `vec` over a long range)
allocates or runs before any check. A runaway model can therefore exhaust the
scoring JVM's heap; the evaluator bounds a mistaken model's work, not a hostile
one's memory. Invalid
models return a failed verdict with a bounded diagnostic. Evaluator policy and
kernel/scoring/generation source identities enter the verifier basis.

`reference/model-source` exports a self-contained history-only baseline using
conjugate/grid calculations and eight importance samples over shared action
effects. It deliberately ignores correspondence, so it is an attainable numerical
baseline rather than a privileged truth reference. The native reference also
supports more particles and a best-found posterior plan.

## Current boundary and next experiments

The native artifact evaluator is not yet exposed through simmis MCP. Simmis's
owned benchmark profile deliberately selects the isolated file-worker surface and
excludes the optional foerster extension. A versioned admitted evaluator/profile
is required there; importing this bundle alone continues to run the v1 checker.

Next compare supplied-model inference/planning, agent-built models with fixed
inference/planning, and fully agent-built models/inference/planning. Keep raw
metrics and query counts separate. The present slice evaluates model forecasts
under a fixed policy and the agent's submitted decision separately; it does not
yet implement that three-condition decomposition. Add rolling future observation
splits and genuinely misspecified mechanism families before claiming robustness.
Only after that baseline should a budgeted proposer optimize the builder from
development Attempts and face untouched held-out evaluation. Certified traces
carry provenance and checker results; real-world validation is a separate claim.
