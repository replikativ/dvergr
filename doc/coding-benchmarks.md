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

Next steps are the RSS reference-resolution fixture, held-out task variants,
repeated model attempts and recursive repair/review workflows. Comparing models
requires distributions of outcomes and resource use, not one successful repair.
