# Coding benchmarks in the SCI workspace

The first reusable fixture is `dvergr.benchmarks.permutation`: repair a seeded
composition-direction regression in a pinned Spindel source file. The original
upstream code is correct. The fixture packages its source and license, so no
sibling checkout or network fetch is needed to construct the task.

It uses ordinary EnvironmentDef / WorldSetup / Evaluator values and returns an
evaluation Spin. It is not another agent loop, workspace manager, or scheduler.
The caller supplies a Room with a registered Geschichte workspace and an
AgentDef; an already initialized Dvergr room is the intended entry point.

```clojure
(require '[dvergr.agent.evaluation :as evaluation]
         '[dvergr.agent.roster :as roster]
         '[dvergr.benchmarks.permutation :as permutation]
         '[org.replikativ.spindel.engine.core :as ec])

(def team
  (roster/make-agent
   (roster/make-roster)
   {:id :coder
    :tools #{:clojure_eval}
    :model-policy {:provider :codex-subscription :model "codex-subscription-luna"}
    :program {:kind :llm :auto-compact? false :max-model-steps 16}}))

;; REPL top-level convenience. Inside a workflow, use spin/await and compose
;; evaluation Spins with Spindel's existing parallel/race/etc. combinators.
(def completion (promise))
(binding [ec/*execution-context* (:ctx room)]
  (let [attempt (evaluation/evaluate
                 room team :coder (permutation/definition) (permutation/evaluator)
                 {:world-setup (permutation/world-setup)})]
    (attempt #(deliver completion {:result %})
             #(deliver completion {:error %}))))

;; Poll without blocking the REPL. A nil result means it is still running.
(deref completion 0 nil)
```

The environment allows 180 seconds. The model-step cap is a runaway fuse, not
the definition of useful work. Choose resource grants and model/token limits
through the existing evaluation options for a particular experiment. Subscription
access does not imply unlimited compute or a measurable per-request dollar bill.

Setup resets both the source and the specified test file in the candidate fork,
and installs an offline HTTP fixture. A missing workspace fails setup; it must
not fall back to a physical checkout. Other unrelated workspace files remain,
so use a clean seed when comparing candidates. This fixture is not a hermetic
VM and does not claim that every possible external effect is stubbed.

On success, failure or cancellation, bounded capture snapshots the source and
test file (32 KiB each) before disposal. These appear under
`:attempt/evidence :artifacts :files`; missing, non-file, unreadable and oversized
files have explicit statuses. Test text is diagnostic evidence, not a trusted
score. Verification loads saved source in a fresh SCI execution context and
checks all 576 binary compositions over four positions plus nullary, unary,
ternary and vector-arrangement cases. Failed/cancelled/waiting execution never
earns success reward even if its saved source passes. Worlds are discarded and
the parent remains unchanged.

## What these results mean

The manifest and content basis identify the source commit, seeded source,
task wording, public checker, required runtime surface, capture bounds and
verification deadline. Record the actual Dvergr revision, dependency resolution
(including local overrides), JVM and provider/model resolution alongside each
launch. The named runtime surface is not an automatically detected binary lock.

This is a development benchmark, not hidden evaluation data or a tamper-resistant
training reward service. It measures whether an agent can inspect, edit, reload
and test a small real source library through the SCI workspace. A deterministic
stub provider exercises the same tool/world/capture path in tests; it does not
measure model reasoning. The checker has a bounded SCI evaluation window, not a
separate process/heap boundary. Do not treat it as an adversarial code runner.

## RSS reference-resolution fixture

`dvergr.benchmarks.rss` provides the same `definition`, `world-setup`, and
`evaluator` entry points. Substitute those constructors in the example above.
It packages a pinned, Apache-licensed intake source snapshot with a historical
relative-URL bug; it does not modify production intake code.

Setup resets `/dvergr/intake/rss.clj`, `/test/rss_repair_test.clj`, and a labelled
stub at `/dvergr/intake/core.clj` in the candidate fork. The stub's `fetch-text`
returns an error until a test supplies HTML/XML with `with-redefs`. Verification
reconstructs the stub independently, so changing that dependency cannot fix
the submitted RSS source. Fixture version 2 also captures the dependency and
requires its exact original text at completion (`:dependency-unchanged?`). Missing,
unreadable or oversized dependency captures fail that check. The task explicitly
identifies the stub as intentional scaffolding: do not restore it from Git HEAD
even if it appears as a working-tree modification. Source, tests and dependency
are captured with the same per-file bounds; the parent dependency is not overwritten.

This changes the fixture basis and checks version. Historical version-1 Attempts
retain their original meaning: functional success did not imply scaffold
preservation. This is a final-state check, not a prohibition on every intermediate
write or a complete audit of unrelated workspace paths.

There are 18 explicit URL cases covering document-relative paths, dot segments,
scheme-relative links, absolute references, ports, encoded paths, queries and
fragments. Four further checks cover fetch errors, fallback probing, RSS
parsing/count limits, and Atom title/link/category extraction. The snapshot has
an unrelated empty-string fallback issue for Atom summaries/dates; these fields
are deliberately outside this URL-repair task. This fixture exercises the
real sandbox XML parser but not production HTTP acquisition.

Deterministic lifecycle tests distinguish unchanged source (completed, reward
zero), repaired source (completed, reward one), and provider failure after
repair (failed, reward zero, artifacts retained). They check the authored
regression test's reported counts as well as independent source verification.

Next steps are held-out task variants, repeated model attempts and recursive
repair/review workflows. Comparing models
requires distributions of outcomes and resource use, not one successful repair.

## Compare editing affordances

`examples/coding_tool_comparison.clj` composes the existing APIs into a four-Run
smoke comparison. Both candidates have the same model, common prompt, 180-second
RSS environment, and 32-request runaway fuse. One receives only `clojure_eval`;
the other also receives `clojure_edit` and `write_file`. The treatment includes
their descriptions/context overhead and optional use, not just editor speed.
The fixture's instruction to use the REPL is unchanged; the common AgentDef
prompt permits all granted tools. Inspect actual tool usage before attributing
any outcome to structured editing.

```clojure
(load-file "examples/coding_tool_comparison.clj")
(def comparison
  (coding-tool-comparison/plan
   {:provider :codex-subscription :model "codex-subscription-luna"}))
(def group (evaluation/cleanup-group))
(def done (promise))
(binding [ec/*execution-context* (:ctx room)]
  (let [workflow (coding-tool-comparison/run room comparison group)]
    (workflow #(deliver done {:result %}) #(deliver done {:error %}))))
(deref done 0 nil)
;; Once finished/cancelled, join detached cleanup before closing the Room:
;; (evaluation/await-cleanups-for! room group)
```

The order is REPL → editing → editing → REPL, implemented as two serial ordinary
experiments. Each block persists its own scorecard and certified Attempts;
aggregate both blocks when reporting the two observations per candidate. No
failure is retried or dropped. A certification/cleanup error can abort the
remaining workflow; report incomplete cells rather than inventing receipts.

The Run API does not currently enforce token caps. This example measures tokens
and uses time plus the request fuse as limits; it must not be described as a
token-budget-controlled comparison. Four observations cannot establish a
ranking, significance, or a causal performance improvement. Log model resolution,
actual calls/tools, errors, tokens, elapsed time and captured artifacts, then
choose larger repeated or held-out experiments based on those observations.
