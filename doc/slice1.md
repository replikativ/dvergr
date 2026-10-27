# Slice 1 on `next`

This is the synchronous JVM assembly surface for provider-free programs. It does
not start Runs or admit effects. Production snapshots refuse with P1 missing;
hot and warm requests refuse with P3 and P6 respectively. Following worlds and
following rooms refuse. Frozen worlds exist only in `dvergr.test-support` and
record `:p1? false`, no cut identity, and no SCI or Geschichte tier.

The host opens `dvergr.store/open!`, then creates an explicit
`dvergr.world/installation` from that control capability. World creation,
selection, descriptors, closure, room creation/access, SCI evaluation, and handle
construction/resolution/invocation take this installation as their first
argument. Store readers and writers take the control capability. Room lookup
also takes the control capability, and does not imply a live interpreter.
There are no process-wide dvergr runtime directories. The installation's lock
serialises its synchronous entry and lifecycle; the host must stop users before
closing their control capability. Internal assembly/publication helpers marked
`:no-doc` are host implementation seams, not sandbox exports.

Control configuration has exactly `:installation-id`, `:control-store-id`,
`:test?`, and `:database`. The first two are UUID identities. Database storage
is explicit: `{:store {:backend :file :id <UUID> :path <local-path>}}` for a
durable installation, or `:memory` with a UUID and `:test? true`. The capability
sets write-schema validation, history, commit graph and the local exclusive
writer. Existing databases require their matching installation/role/version
marker and are never adopted as control databases.

Assembly has exactly `:systems`, `:injections`, `:effect-policy`, and
`:resource-authority`. The last two are `:unavailable-in-slice-1` and `:unbound`.
Systems are explicit maps with `:id`, `:adapter` (`:datahike` or `:geschichte`),
`:connection`, `:ownership` (`:owned` or `:borrowed`), `:operations`, and
`:attributes`. Only the approved constructors run. Physical store UUID and
canonical file location checks reject control aliases, independently of branch.
Datahike attributes are a declared set of cardinality-one business attributes;
schema attributes refuse. This restriction makes the shallow pull bounded.
Geschichte has an empty operation set in this slice.

Injections contain exactly `:bindings` and `:operations`, each mapping namespace
symbols to maps of simple symbols. Bindings pass the recursive v1 classifier.
An operation entry is `{:system <logical-id> :operation <keyword>}` and builds an
audited immutable callable containing a logical handle. For example:

```clojure
{:bindings {'user {'config {:feature :example}}}
 :operations {'work {'read {:system :work :operation :datahike/pull}
                     'write {:system :work :operation :datahike/transact}}}}
```

Pull requests are `{:selector [:item/value] :eid 100}`, with at most 64 declared
attributes (also allowing `:db/id`), no recursion or wildcard. Transactions are
vectors of at most 128 four-element `:db/add` or `:db/retract` datoms, with
integer entity IDs, declared attributes and portable scalar values. Reports
contain only tempid mappings and datom counts. Queries arrive with the effects
slice; the keyword operation vocabulary is their extension seam.

SCI supports definitions, closures, managed atoms, var and namespace metadata,
and mapping changes through `def`/`ns-unmap`. Namespace enumeration, `intern`,
and namespace switching are excluded: they can recover denied built-in values
indirectly. No class table, loader, runtime resolver or arbitrary host callable
is accepted. Portable export can refuse after managed state changed; it is not
a rollback guarantee. Host failures retain causes; operation exceptions seen
inside SCI contain only the sanitised refusal code.

## Pinned upstream verification

Implementation was checked against source entries in the pinned Maven jars,
then exercised by the law and adapter suites. Paths below are jar entries.

| Owner | Verified calls and dependencies |
|---|---|
| Spindel 0.1.148 | `engine/context.cljc`: `create-execution-context` 248, `close-context!` 383, frozen/component options 467, `get-fork-id` 762; `engine/core.cljc`: explicit dynamic binding 30 and `current-execution-context` 234 |
| Spindel bridge | `yggdrasil.cljc`: `register!` 339, effective `system` 437, `fork-descriptor` 676, selected synchronous `fork!` 1146, excluded-signal masking 1291, `merge-fork!` 2128 and `discard-fork!` 2148 (test-only settlement) |
| Spindel SCI | `sci/world.clj`: `context-in` 17 (adapter test), `create!` 57, forced forkable mode 89 and `eval-string*` 96; SCI init/fork remain upstream-owned |
| SCI 0.15.59-replikativ.5 | `sci/fork.cljc`: `Forkable`/`fork-value` 4; `sci/core.cljc`: `init` 401 and `fork` 415 (indirect); `sci/impl/resolve.cljc`: allow checks 23; `sci/impl/namespaces.cljc`: namespace enumeration 598 and two-arity `intern` 650 explain their exclusion |
| Yggdrasil 0.3.52 | `adapters/datahike.cljc`: effective `:conn` representation 801, historical `as-of` 824 and constructor 1151; `adapters/geschichte.cljc`: constructor 643, connection 649 and workspace identity 660; `protocols.cljc`: snapshot protocol 59 and system identity/capabilities 336 |
| Datahike 0.8.1910 | `api/specification.cljc`: exists 94, create 110, connect 151, release 184, synchronous transact 205, pull 411, exact indexed datoms lookup 473 (control identity lookup only), branch 741 (alias test only); public emission in `api.cljc` 34/84 |
| Datahike config | `config.cljc`: `:commit-graph?` 54 and exclusive local writer 127; `connector.cljc`: concrete local `Connection` 27; missing lookup refs throw (`db/utils.cljc` 179), so control readers perform one exact unique-index lookup before pull |
| Konserve 0.9.394 | `konserve/store.cljc`: store configuration validation 46 requires UUID `:id`, including file stores |
| Geschichte 0.1.25 | `geschichte/repo.cljc`: `init!` 65 and `write!` 271 (dirty-workspace adapter fixture only) |
| Kontor 0.1.40 | No runtime calls; account identity domain reserved without account initialisation |

## Sequential process verification

The process law has an optional external evidence path. Without it, the suite
still exercises file-store release/reopen. With it, the law also asserts a
different writer process (PID plus JVM start time, to tolerate PID namespaces),
comparing exact saved descriptors, bases, source edges,
room bindings and absent P1 evidence. Run phase A to completion before phase B;
no subprocess or overlapping JVM is needed:

```sh
clojure -J-Xmx1500m -Sdeps '{:paths ["src" "test"]}' -M -m dvergr.process.lineage-test /tmp/dvergr-slice1-restart
DVERGR_PROCESS_EVIDENCE=/tmp/dvergr-slice1-restart clojure -M:test
clojure -M:format
git diff --check
```

Use a fresh evidence directory for an independent verification. Test helpers,
including the frozen bridge and cleanup, live in `test/dvergr/test_support.clj`.
Runtime release retains durable records; process reopen does not hydrate worlds.
