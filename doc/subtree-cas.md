# Exact bounded subtree saves (HOST API)

The optional `:expected-subtree` batch-write precondition is closed data:

```clojure
{:prefix "/workflows/example/"
 :entries {"main.clj" {:content content-uuid :size 123 :mode 33188}}}
```

It accepts an empty manifest for creation. Prefixes and relative paths must be
canonical, non-sensitive native workspace paths, with a slash directory boundary.
Each BEFORE and independently derived AFTER tree is limited to 200 regular files,
8 MiB/file and 16 MiB total. The host may impose stricter workflow limits.
Stage tombstones are inspected too, with a finite 20,000 indexed-row cap. Missing
metadata or unknown staging states fail closed; inner joins cannot hide them.

The fixed native executor validates BEFORE before any canonical mutation and
again before forwarding physical work. All batch paths must be beneath prefix.
AFTER is derived from that original manifest and submitted bytes/deletions, not
a later worktree read. Original modes are preserved unless explicitly overridden
with the existing `:file-modes` option.

The actual prepared target transaction requires BOTH the complete worktree
prefix AND the staged-index prefix to equal AFTER, including for a no-op. This
prevents phantom sibling paths and avoids reporting a committed source that
omits an untouched dirty/untracked helper. Such helpers are NOT implicitly staged:
the save refuses unless they are explicitly included in the submitted batch.

The initial clean HEAD/index proof comes from ONE immutable DB (nonmutating
Geschichte preparation must report no-op), not a fresh index fetched after a
loose staged check. Conditional staging is itself a fixed writer transaction:
it compares that original index prefix and independently expected worktree,
then stages only the batch paths. A racing raw stage is not overwritten.

Predispatch cleanup also uses a joined fixed metadata transaction. Worktree and
index values are compared independently against FULL expected native AFTER
(content UUID, size and mode, or absence). Different foreign content/modes are
preserved and reported as conflicts. Index cleanup additionally requires THIS
extent's original validated stage ACK; no ACK means no index undo. Dispatched
uncertainty never grants cleanup/replay ownership. This is value CAS, not
history-sensitive detection of an identical-value ABA.

The full prepared basis additionally fences any mutation after preparation.
Content ABA back to exactly the same manifest is allowed; this is not an
ever-changed generation counter. Existing worktree/stage writes still happen
before commit and are not invisible. A failed predispatch batch uses full-entry
value-CAS cleanup; concurrently changed files are never presumed rolled back.

Condition-enabled ordinary writes use the same joined native transaction
primitive as durable publication, without installing a fake receipt journal.
An original before/after transaction ACK and validated committed index establish
success, even for no-op. Dispatched errors or missing/stale/lost ACK remain
uncertain: do not restore, replan or replay. Interrupted callers physically join
the submitted operation before their interrupt flag is restored.
If interruption is observed after staging, the extent stops pending before
commit or any further cleanup mutation; it never treats interruption as rollback
permission or starts a detached cleanup executor.
Staging and cleanup also verify the original repository/store/backend/branch
and logical ref inside their actual transaction. A checkout with identical
bytes is not the original target. Cleanup uncertainty remains observable even
if an outer effect handler catches and suppresses the cleanup exception.

A fresh successful result includes `:subtree` with its validated AFTER manifest.
It describes the original committed index, NOT a subsequently current worktree.
Current source inspection remains a separately authorized read. A durable scoped
receipt deduplicates BEFORE fresh source validation; historical receipt recovery
returns `:recovered? true` and deliberately does not invent a fresh `:subtree`.

This precondition grants no permission and selects no repository. Explicit
`write-to!` retains one registered native descriptor; room/effect policy and
current host authorization remain the caller/native gates. No temporary parent
selection changes or cross-control/target distributed transaction is implied.
When the option is absent the existing native writer route is unchanged.

Use `write-to-completion!` when an owning application must reason about a sealed
physical extent, saved forwarding, no-entry cleanup or positive evidence after
an outer handler throws. The ordinary `write-to!` still has its established
handler/callback semantics; this subtree option does NOT silently add a sealing
promise to that API. Future owned workflow authoring must use the sealed API.
