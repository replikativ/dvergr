# Captured conversations for stopped Runs (alpha v1)

An LLM Run's completed-step savepoint records its working conversation, not a
cursor into an evolving chat log. `resume!` restores that ordered frozen input
before admitting a successor. Later chat rows, edits and deletions do not enter
the checkpoint implicitly. Ordinary live Room message delivery is unchanged;
an explicit refreshed-input resume operation is not implemented by this change.

The existing same-store artifact publisher owns immutable conversation bytes.
The portable payload contains only a versioned manifest (content UUID, encoded
byte length and message count); that manifest participates in the existing
savepoint content identity. One fixed serialized Run operation commits its
typed `:run/conversation-ref` and matching descriptor together. Lifecycle writes
cannot change checkpoint fields. Original immutable transaction reports, not
readback after lost acknowledgements, acknowledge publication. A later coherent
durable read may discover a committed checkpoint but grants no execution claim.

Preparation and publication use the Run's existing supervised workers. The
savepoint handler only announces its pending point; the same supervised worker
captures portable metadata (including native snapshot reads) and publishes it.
The Run waits for that original completion before continuing. Per-gap handlers are
restored on unwind, and inherited handlers cannot advance the originating Run
from another world. Cancellation remains owned by the same worker/session
cleanup boundary; no new executor, timeout-success rule or detached task exists.

V1 limits are 4096 messages, 2 MiB encoded artifact, bounded structural depth and
node count, and conservative pre-encoding scalar limits. Messages retain order,
IDs, reasoning, typed tool inputs/results and pruning/compaction markers. Tool
inputs must round-trip through the existing captured tool schema; raw fallback
inputs, unresolved entity references and unknown message fields refuse rather
than being silently dropped. Attachments refuse until their actual bytes have
explicit typed retention. Only complete, unambiguous tool/result boundaries
are captured. Nonportable/oversized capture loses the new checkpoint, not Run
progress: its previous durable checkpoint remains available.

The reader bounds actual bytes before decoding, verifies the artifact content
UUID, codec, version, manifest length/count and semantic representation. Legacy
descriptors without a retained conversation refuse frozen resume; there is no
live-tail fallback or migration. Validation happens before successor/world/
resource claims. Captured content never restores current permissions or wallets.
Stores without the optional fixed Run-savepoint store and bounded managed
artifact reader (including the current MemoryStore/legacy blob path) cannot
publish or resume these captures. They explicitly lose new resumability rather
than silently substituting a live chat load. This alpha change does not migrate
old descriptors or implement a MemoryStore persistence backend.

Latest-only typed roots permit superseded payloads to be collected according to
the store's real history/GC policy; no direct byte deletion is needed. Full
prefix artifacts still have alpha write/storage amplification across turns.
Moving bytes out of database strings does not solve quadratic cumulative IO.
Incremental message/chunk roots are a separate optimization, not a hidden new
format in this change. UUIDs merely embedded in EDN are not GC retention edges.

Stopping inside an incomplete model/tool step still resumes the previous
completed boundary. Already performed `:once` effects remain the caller's
reconciliation obligation. Frozen conversation fidelity does not imply dirty
workspace fidelity, a coherent multi-system snapshot or exactly-once effects.

## Verification sources

- `dvergr.agent.conversation-capture-test`: real typed nested tool serialization,
  pruning/compaction preservation, refusal limits, native typed-root publication,
  source edits/deletion, stale lifecycle/CAS/terminal refusal, missing/stale/lost
  original acknowledgements, bounded reads, and actual GC retention/supersession.
- `dvergr.agent.run-savepoint-prefix-test`: unchanged strict provider-free
  stopped-Run late-message oracle. It is not an OS-process crash test.
- `dvergr.agent.conversation-capture-run-test`: partial next-step/tool mutations,
  live edits/deletion, corrupt actual bytes before any successor/resource claim,
  and held native capture with unrelated engine progress and cancellation.
- `dvergr.agent.conversation-capture-restart-probe`: opt-in `-main` with `write`
  and `read` modes, a supplied fresh file-store EDN config and Run UUID. Run in
  TWO SEPARATE JVM invocations, allowing the first to physically exit before
  launching the second. The read mode verifies contents after restart and GC.
  It refuses an existing database in write mode and never deletes caller data;
  the invoking test host owns deletion of its exact fresh fixture afterward.

Local verification (2026-10-20): all four focused namespaces above plus the
existing artifact, native/memory Room store, Run/program/world, chat context/
compaction, preparation and resource-authority regressions passed together:
202 tests, 1,282 assertions, zero failures, with physical JVM exit 0. The actual
GC reclamation test uses a fresh file-backed, exclusive, no-history store:
Datahike deliberately does not flush memory-backend indices for disk marking.
It awaits the original GC promise and checks both reachability and swept keys.

The opt-in durability probe also passed in TWO SEPARATE JVMs: the writer exited
0 before the reader started; the reader reopened the exact same file database,
restored the conversation, awaited real GC, restored it again, and exited 0.
This verifies retained conversation publication/loading across process exit,
not complete process recovery or dirty-workspace portability. Full exact-head CI
and independent review remain release gates; these counts are not a full suite.
