# Native Room retirement

Closing a Room and retiring its native storage ownership are separate operations.
`dvergr.discourse/close-room!` stops Room activity and acknowledges its runtime
close. `dvergr.system.rooms/retire-room-runtime!` then releases the exact connection
acquisitions retained by its profiled bootstrap and evicts that context from the
host cache. Neither operation deletes repository files, commits or checkpoint GC
pins. Persistent deletion remains a separate operation.

This matters for bounded work such as benchmark fixtures: an archived Room must
not retain its composite, connections and context until the daemon exits.

## Ownership and ordering

The existing preparation journal is transferred to a native cache owner during
publication. It records each acquisition separately, including two acquisitions
that return the identical connection object. The owner stays outside interpreter
and forkable semantic state. Registration binds the exact live Room to that
published context before acknowledging success.

Retirement requires the actual host root, the original persistent UUID and exact
Room, acknowledged runtime close, and a matching archived profiled control row.
It owns an empty-slot Room registry reservation while checking identity, releasing
ownership and evicting the exact cache entry. A stale Room, replacement context,
foreign root or missing owner grants no release authority.

Owned KB acquisitions participate in profiled hydration's preparation journal,
just like messages and the profiled repository. An additional adapter without a
tracked acquisition prevents this retirement path; enumeration cannot recreate
its lease ownership.

## Progress and failure

Every unregister and release records its intent before dispatch and its
acknowledgement afterward. A retry skips acknowledged work. A lost unregister
acknowledgement can be observed through the exact native registration's absence.
A release that throws remains uncertain: it is held for reconciliation, never
blindly repeated. Releases use the ordinary one-lease Datahike operation, without
forcing other users of the same store to close.

Until cleanup is acknowledged, the same cache entry retains the owner and blocks
hydration or reads of its partially retired context. Shutdown/reset cannot erase
that unresolved ownership. After acknowledgement, a native receipt is recorded
before identity-checked cache eviction. This allows an exact completed retry to
succeed without repeating release, including a lost eviction acknowledgement.
Missing cache state alone is not that receipt.

Retained evidence is independent of the live runtime: a pinned checkpoint must
remain readable from its preserved store after the Room's live leases are gone.
The retirement regressions verify this with a file-backed repository, an outsider
lease, and a cold reopen of the pinned commit.
