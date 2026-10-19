# Participant preparation

Joined conversational participants prepare their SCI context, extension namespaces,
tool context and model metadata on the existing supervised native executor, not
on Spindel's event drain. A cold namespace compilation therefore does not prevent
another participant in the same Room from making reactive progress.

Preparation precedes creation of the turn's Run. Its Room retains a process-local
lease **before** worker dispatch, scoped to the exact Room and participant
incarnations. Admission is nonblocking and bounded (64 outstanding preparations or
context retirements per Room, with one logically active preparation per joined
participant). These native handles are excluded from persisted metadata and from
forked Room copies.

Publication contends with cancellation under the Room and worker-scope fences.
Leaving a participant requests cancellation but does not treat Future cancellation
as worker exit. Forking, replacing or closing a Room requires physical worker and
cleanup acknowledgement. Failed cleanup keeps ownership retained and prevents
unsafe context release. A successful reactive handoff can admit the next FIFO turn
before its final physical acknowledgement; the native lease still fences snapshots
and teardown until that acknowledgement arrives.

Extension expansion forks the SCI interpreter and publishes it through the stable
Spindel component reference with expected-identity replacement. User vars, functions
and atoms survive expansion. Native extension resources remain owned by the exact
interpreter environment that installed them; projected children receive their own
ownership records, not their parent's cleanup chain. Narrowing retains the existing
fresh-context semantics. Tools for the first expanded turn use the staged interpreter,
not the previously published interpreter.

Host daemon and lite-client shutdown drain all registered Rooms before releasing
stores or the root executor. A root-owned native receipt retains contexts across
partial shutdown failure; children close before the root. Unexpected Rooms created
while shutdown drains cause refusal rather than premature release. Ingress must be
stopped before entering this host shutdown boundary.

The public speculative combinators (`simulate-reply`, `imagine-conversation`)
also keep their complete fork/ask/discard extent off the drain. Their existing
native settlement Run is admitted before a fork exists, owns interruption and
physical worker acknowledgement, and does not retire on Future cancellation.
Interrupted native waits cancel the child ask/workflow Spin explicitly so its
subscriptions unwind. Host interrupt status is restored only after physical join
and durable Run retirement; non-cancellation failures record a failed Run.
The public return remains an ordinary Spin, not a shared future. Spindel's
established concurrent-await semantics can re-enter an unfinished ordinary Spin;
each evaluation therefore owns and cleans its own speculative extent. Callers
needing exactly one execution should await once and fan out the result through
an explicit Deferred or a durable Run/result handle, rather than concurrently
awaiting the same uncached Spin. This change adds no hidden single-flight cache.

A bounded discard failure can leave a canonical child Room registered for an
explicit cleanup retry. Direct close refuses another registered owner of the
exact context; closing an actual root additionally refuses any other Room in its
root registry, since Spindel forks share the running flag and normally the
executor. These checks happen before unregistering the closing Room. They are
conservative even for externally supplied executors. Shared-context hosts should close Rooms with
`{:close-context? false}` and release the root only after all owners have drained.
This does not introduce a universal context-registration lease: unrelated trusted
host Room creation must not race physical root closure, just as host shutdown
requires ingress to stop first.

If a merge/discard settled its canonical substrate but failed during subsequent
teardown, an explicit host close may finish cleanup after that exact settlement
attempt has ended. The existing lifecycle fence is atomically changed to close;
settlement retries cannot race the takeover. Only terminal merged/discarded
handles qualify, never transferred or partitioned capabilities. Close still
physically drains listeners, preparation, work and Runs before unregistering,
and preserves the original semantic settlement claim/outcome for inspection.
Failed cleanup remains retryable and keeps the child registered, so the parent
cannot release the shared executor early. Hosts/tests must explicitly discard
open retained proposal forks or finish this cleanup before closing their root.

Physical drain ownership is determined by Spindel's thread-local drain marker,
not the inherited `*spin-id*` binding: a native future started by a continuation
is still a host boundary, whereas a bound function actually running on the
drain remains forbidden from blocking cleanup.

The REPL client's close operation recognizes the attached daemon's exact
computation world (including continuation facades) and closes only the Room
there. The owning lite daemon's stop operation releases that executor. Closing
an unrelated standalone Room still releases its context. Provisioned persistent
Rooms used by archive/purge already run on distinct child contexts; their close
cannot release the daemon root, and exact-context coowners still cause refusal.

Room cache teardown also retains a native receipt before detaching any cached
interpreter. It attempts every entry, acknowledges only successful releases, and
keeps failed entries for an explicit close retry. Unregister and context closure
cannot proceed while that receipt is incomplete; successful entries are not
released again on retry.

Cached interpreters are also checked against the exact Room monitor and execution
context before synchronous or staged reuse. The existing sandbox-work lifecycle
already refuses raw registry replacement by another incarnation or execution
context; no second replacement policy is introduced here. The host must close the
old Room first (without closing a shared execution context when appropriate), then
retry registration. The defensive cache identity check also fails closed if host
code otherwise introduces a stale cache entry; it never transfers or releases the
predecessor's authority on the replacement's behalf.

## Boundaries

- An inbox cancel directive is still queued behind initialization; leaving, host
  stop and Room lifecycle cancellation reach preparation directly.
- Roomless participants do not have a Room lifecycle lease.
- Direct synchronous host context constructors are not universally asynchronous;
  competing mutation of a joined participant's live context is fenced.
- This is in-process resource ownership, not durable resumption of native workers
  after process loss, and does not change Run accounting or model-call timeouts.
- The existing child-context partial extension-rebind failure cleanup gap is not
  resolved by this change.
- The existing extension registry's `release!` skips individual extension release
  failures. Cleanup receipts fence errors propagated by the SCI/resource release
  boundary; they do not strengthen that separate extension-release contract.
- Retained cache release runs synchronously on the host teardown thread. Native
  release functions are not interruptibly bounded by the preparation-worker join
  deadline; a stuck native release can still require operator intervention.

Deterministic tests intercept a real host namespace-load boundary, verify sibling
progress and cancellation, and retain resources until native exit. Further tests
cover interpreter expansion, per-world cleanup, rejoining the same Participant,
cleanup failure, shutdown retries and retained context receipts. No paid provider
or timeout increase is required.
