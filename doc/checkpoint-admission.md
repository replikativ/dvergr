# Repository checkpoints at Run admission

A trusted host may select an exact initial repository state for one Run through
`dvergr.agent.program/hire-in!` or a WorldSetup. The `:checkpoints` map names
registered systems and closed physical checkpoint descriptors. Spindel validates
all origins before allocation and constructs ordinary owned workspaces.

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
