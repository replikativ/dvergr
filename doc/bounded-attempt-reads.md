# Bounded exact Attempt reads

`dvergr.agent.episode/attempt-bounded` reads one managed certified Attempt:

```clojure
(episode/attempt-bounded room attempt-id)
(episode/attempt-bounded room attempt-id {:max-bytes 1048576})
```

The optional `PBoundedAttemptStore` protocol leaves existing stores compatible.
A configured store without the capability refuses; there is no fallback to
`attempt`, `export`, a legacy artifact loader or the current worktree. A missing
Attempt in the selected Room returns nil. A Room without a store returns nil.
The ID must be a UUID and the options must be a closed map.

The fixed ceilings are 4 MiB of actual encoded artifact bytes, 1,024 checks,
4,096 evidence Runs and 4,096 evidence messages. Options may lower any ceiling;
zero, negative, unknown or raised limits refuse before native lookup. These are
restricted-reader limits, not new rules for certification or writing valid
Attempts. Native certification, ordinary readers and storage remain unchanged.

The Datahike implementation captures one immutable DB value. It first finds the
exact Room/Attempt ownership relation and reads only a scalar header. All
relation prefixes stop at their cap plus one before child expansion. Managed
UUID payload/codec, root Run identity, child ownership and check identities are
validated. Actual byte and parser-depth bounds precede EDN decoding;
collection counts precede Attempt reconstruction/hashing. The exact native
Attempt validator and typed projection comparison still determine truth. Missing,
corrupt, unsupported or oversized payloads fail closed.

The shared managed-artifact helper explicitly requests Konserve streaming reads.
Native file stores lend their stream inside the locked callback; at most the
byte ceiling plus one is read. Memory stores may lend their existing byte array
under the explicit `:blob` contract. Unknown handles refuse without a generic
materializing fallback. This also corrects the shared helper used by bounded
Scorecard readers; ordinary artifact reads and all write paths are unchanged.
Restricted Attempt validation errors expose only fixed diagnostic tags and
allowlisted field identity, never large native values, payloads or raw causes.

The caller must hold current authorization, the selected execution context and
the physical lifetime of the Room/store through this synchronous read. This
function does not hydrate Rooms, choose a host owner, authorize artifact IDs,
grant access, execute user code or pin history. In particular, an immutable DB
snapshot is not a GC lease. Retention must keep managed payloads reachable while
they are needed; disappearance during a read is an error, not permission to read
today's source as a replacement.

Certification retains captured evidence in the control Room before a Run world
is discarded. The bounded reader can therefore return that exact evidence after
discard; it does not imply uncaptured files, all external sources or all original
world state were archived. Later assessments/forms can build on this capability
without mutating Attempt receipts, Scorecards or settlement.

This API is a host reader only. It does not add a new MCP tool, SCI binding,
Assessment writer or public endpoint.
