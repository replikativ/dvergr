# Repository checkpoints at Run admission

A trusted host may select an exact initial repository state for one Run through
`dvergr.agent.program/hire-in!` or a WorldSetup. The `:checkpoints` map names
registered systems and bounded portable, adapter-defined checkpoint descriptors.
Spindel validates all origins before allocation and constructs ordinary owned
workspaces. Dvergr validates portable definition data and exact capability
references; the underlying adapter validates the origin's backend-specific fields.
Geschichte owns its repository checkpoint resolution and construction, with
Yggdrasil retaining the overlay lifecycle and cross-system dispatch.

WorldSetup accepts `:checkpoints` beside its existing basis and preparer. Its exact
portable reference has an optional top-level `:setup/checkpoints` field; absent
and empty policies preserve old references. Keeping origin separate from arbitrary
caller basis prevents reference collisions. Evaluation checks the executable
capability field against this reference before admission. A wrapper must forward
checkpoints explicitly when composing its preparer and basis.

Selection happens before the preparer runs. The preparer continues to stage only
fork-local state. Ordinary filesystem calls see the selected state without a new
read tool. Checkpoints stay outside delegation capabilities and supervisor state:
a recursively hired child sees its immediate parent's edits. Resume continues to
use the savepoint's recorded logical snapshots; it must not replay the original
admission checkpoint. Overlapping physical/logical selections are refused.

## Input prediction and cleanup

For checkpointed experiments, prompt/tool/model input prediction runs in a
temporary owned native fork at the same origin, cached by candidate, protocol and
checkpoint policy. It uses the ordinary prompt assembler. It creates no Run,
Room, wallet or model dispatch and does not run WorldSetup. Other experiments
retain the existing prediction path. Explicit host workspace overrides are
refused for checkpoint prediction because they bypass the selected repository.

The existing room/group cleanup manager owns a Spindel scope before native
allocation starts. It joins disposal on success, failure and interruption, with a
bounded cleanup wait and preserved interrupt status. A late acknowledgement or
failed cleanup stays owned privately. Completed gates do not release held
ownership without settlement acknowledgement. HOST-only
`dvergr.agent.evaluation/recover-native-cleanup!` retries a cleanup token after the
original operation has exited; active native work refuses concurrent recovery.
Adapter uncertainty still requires its existing privileged reconciliation.

Public errors expose only inert bounded summaries and a recovery token. Full
errors and native scopes remain private to the HOST cleanup manager. Neither SCI
nor portable receipts receive mutable cleanup capabilities.

A setup can still change AGENTS.md or skills after prediction. The existing
actual-input comparison remains authoritative: such drift excludes the cell
rather than producing a misleading Scorecard. Trusted protocol input callbacks
retain their existing contract; prediction is not execution of a complete Run.

## Host obligations

The descriptor is not authorization or GC retention. The admitting host must
retain the checkpoint before prediction/admission and throughout execution,
uncertain cleanup and promised evidence lifetime. This API does not acquire or
release those pins. Environment identity records the initial repository state
available to execution, not a claim about which files were actually read.

Checkpointing an authoring repository does not provide clean benchmark fixtures.
Other cases and gold must remain unavailable to candidates through the existing
fixture isolation policy. The catalog's default fixture setup is unchanged here.

HOST fixture construction can use `evaluation/with-checkpoint-fixture!`. It
shares prediction's native fork ownership, independent context state, portable
return requirement, cancellation, bounded cleanup joins and explicit recovery
manager, with a separate fixed construction purpose. It creates no Room, Run,
wallet or provider call. The caller retains original source lifetime and
authority, validates finite input limits and bounds callback execution; the
helper does not impose byte/count limits or bound arbitrary HOST callback code.

Only the temporary fork is owned by that scope. A durable GC pin created by the
callback belongs to its separate evidence journal: persist that pin's exact
intent before native IO, retain ambiguous outcomes, and verify the pin before
retiring its builder. Discarding the temporary world neither releases nor proves
ownership of those external roots. The callback returns portable identity, never
native connections, contexts or recovery handles.

`catalog.workspace/seed-in-context!` initializes bounded text fixtures in the
explicitly selected primary repository of a caller-owned temporary context. It
resolves that workspace once and revalidates its identity and selection under
the existing workspace lock. The primary Geschichte filesystem adapter prevents
nested mount tables from redirecting writes. Existing Room-bound `seed!` keeps
its original behavior and shares the low-level mkdir/write loop.

The new initializer requires all three limits (`:max-files`, `:max-file-bytes`,
`:max-bytes`), which may only lower `fixture-seed-limits`. It rejects noncanonical
paths, file/parent collisions, malformed UTF-8 and oversized inputs before any
write. Both index and worktree must start clean, even for empty input. It verifies
written and staged content IDs, byte sizes and modes, stages only permitted
changed paths, commits through the retained workspace descriptor and requires a
clean final state. Existing executable modes are preserved. Empty or repeated
inputs do not create a logical commit.

`catalog.workspace/prepare-fixture-files` exposes the same pure preflight for
HOST coordinators that need to derive an expected complete manifest before
allocation. It returns canonical paths with exact UTF-8 text, logical content
IDs and byte sizes, without selecting or writing a workspace. Modes come from
the baseline file, or the regular-file default for a new file. This preflight
does not establish publication, authorization or ownership of a workspace.

Its portable result contains file/byte counts, a written manifest and
`:changed?` for logical tree changes. This is not Simmis semantic projection,
authorization, evidence retention or a published checkpoint claim. The caller
still verifies the complete baseline-plus-case manifest and resolves the actual
stored checkpoint basis. An unchanged child may retain C0's physical CID while
its live branch configuration differs; resolve the published DB from the
original store rather than inventing a new child-specific checkpoint. On any
failure, the caller's existing scope owns disposal or explicit recovery.
