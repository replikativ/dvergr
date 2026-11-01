# Native Run families and settlement evidence

This contract uses the current Spindel runtime. It does not depend on the proposed model-based runtime, change forkable computation state, or reconstruct native handles after restart.

A structural Run parent owns the admission and return of its children. Causal links (`:run/caused-by`) describe observed inputs, and do not create this ownership. A structural parent must be a live original Run in the exact control Room incarnation. Historical or invented parent IDs cannot admit new work.

## Authorities and boundaries

`run/active` remains the single process-local lifecycle owner. Its initialized, versioned family state holds private admission leases. Program supervisors keep references to those leases; they do not independently decide child return. No additional Run registry, resource ledger, or execution engine is introduced. Native closures, executor tasks and context identities never enter a Room database or a forkable execution context.

A child boundary reserves a lease before creating a world, moving a grant or invoking a constructor. Its marker is single-use and tied to the original parent, context and lease. A consumed marker conveyed to another thread cannot admit a second child. The registered child UUID, rather than a callback's return value, determines ownership.

```text
reserve → prepare → start → admitted → physical child return
    │          │        │                    │
    │          │        └─ uncertain write ── held original admission
    │          └─ failed construction ─────── held original cleanup
    └─ preflight refusal: no effects

callback exit + child/preparation return + optional publication ACK
                            ↓
                    native reference release
                            ↓
                      family returned
```

Sealing rejects new leases but retains already reserved work. Cancellation is sticky and reaches children admitted after cancellation was requested. Parent finalization may seal while holding its own short lifecycle frontier, but physically joins children only on an unlocked HOST worker. Waiting under a Run stripe, Room lock or engine drain is refused.

Program execution itself waits for supervisor quiescence before publishing its outcome. Its supervisor therefore owns a tracked family-join worker; putting the only join behind outcome publication would deadlock. A failed join worker is held, and only its positive return sentinel acknowledges family return. Native lifecycle joiners and experiment publication use the shared cached native executor, independently of Clojure's Agent executor.

## Failed child preparation and admission

Known preflight refusal has no cleanup obligation. After native entry, the family retains the original constructor acquisition and idempotent phased disposal callback before effects. It captures partial native construction ownership, the ForkHandle and the returned Room, including failures during subsequent wallet/context setup. Successful admission installs the original supervisor controls in the native Run entry before transferring family custody.

An uncertain initial Run write cannot be resolved by observing no row, nor by disposing its world first. Recovery persists the frozen failed/unexecuted command under the same Run UUID, timestamps and `:admission-failed` reason. Memory and Datahike validate terminal immutability inside their serialized mutation boundary. A delayed initial write therefore cannot reopen that identity. Only an acknowledged fence permits original world disposal. Disposal must positively acknowledge completed discard; `:lost`, `:gone` or incomplete native settlement remains held. Its terminal projection is acknowledged before releasing the lease.

`run/observe-settlement` exposes bounded lease, phase, revision and error facts. `run/reconcile-settlement!` is a HOST operation on the original Room/owner. A child-admission recovery requires callback exit and the exact lease, phase and recovery revision. It claims recovery before I/O, rejects concurrent attempts, and rotates the revision on failure. It invokes no candidate program, model, grant transfer or new constructor. Recovery coordinates an existing capability; it grants no permission to a caller.

This supports current-process recovery of managed structural children. If native construction returns neither a handle nor a recoverable construction owner, the obligation stays unknown with `:construction-owner-unavailable`. Restart cannot invent that authority. Top-level preparations without a structural parent retain their existing ownership path; this contract does not promise new recovery for every root constructor failure.

## Native worker launch recovery

A selected cleanup/family callback and its stable launch UUID are retained before worker construction. Registration records its exact FutureTask before executor submission. Constructor failure retries only the original unregistered launch. Uncertain submission re-submits the same uncancelled, run-once FutureTask, including acceptance followed by a lost acknowledgement. A stale launch revision is refused.

Only an idempotent family join may receive a new physical attempt after the previous task both terminated and reports done. Arbitrary cleanup callbacks that entered are not replayed by launch recovery, whether they succeeded or failed; their existing consumed/error semantics remain in force. No replacement callback may be supplied through recovery.

## Independent durable witnesses

A family return witness is a stable UUID at `:run/family-return-ack`. It certifies that this incarnation's child-admission boundary was sealed and every admission, callback, preparation, child, publication and reference-release obligation returned. It does not certify its own execution-lease release, world acceptance, usage consumption or snapshot retirement.

A Job also has independent stable `:run/job-core-cleanup-ack` and `:run/job-adapter-cleanup-ack` witnesses. The stages are:

```text
join family → before-finish → core/family ACK → terminal persistence
            → settled callback → adapter ACK → native release → done
```

A lost ACK retries its frozen persistence command, not a successful callback. Witness attributes are typed, identity-bound and append-only after admission. Historical rows without family evidence remain readable but cannot satisfy the stronger settlement observation. An old hot owner without supported initialized family state cannot mint the witness. Storeless Runs cannot claim durable witnesses. `:waiting` may close an incarnation's family but is not terminal Job settlement.

A cold Job observer requires terminal status, all three witnesses, and no remaining live native owner/lease. Simmis must additionally verify the exact Request/batch binding and its required conserved-resource/world disposition evidence. Neither absent owners nor held funds imply consumed usage or successful settlement.

## Verification

The regression suites cover sealing/admission races, child return versus publication, same-stripe parent/child joins, stale/conveyed markers, partial construction, before/after-commit write failures, lost disposal ACKs, cancellation and concurrent recovery, retained executor submission, native binding restoration, both Room stores, nested experiments and original Job cleanup stages. Tests use deterministic programs and scripted transport; no provider requests are necessary.
