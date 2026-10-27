# Slice 2a: durable discourse on `next`

This slice admits immutable envelopes, authenticated local host authors, durable
history, retained conversation projections, and versioned decision bindings.
`post!` always returns `:live-publication :not-offered`. There is no delivery
executor or activation gate. Slice 1 still owns installation identity, assembly,
selected-world resolution, logical handles, and world closure.

Live delivery moves to its own slice after the Spindel mult-pump lost-wakeup
fix. That slice will restore sessions, participant handlers, observations,
scoped asks and replies, `decide`, terminal arbitration/reconciliation, timers,
and multicast ownership and cleanup. Review findings 1, 2, 5, 6, 7, 8 and 9
belong there. Their implementations, refusal tests and mult-completion probe
have been removed here. No remaining durable operation uses ask control records,
so their writers, recovery functions and schemas have also been removed. D2 and
D3 remain targets; this slice claims no live completion or recovery evidence.

Decision handles and `set-decider!` now live in `dvergr.discourse`; there is no
`dvergr.participant` namespace. Handles contain the existing logical system
handle, projection UUID and decision UUID. Construction and every edit compare
the stored projection and venue's system binding. Edits use expected versions.
Every operation resolves the caller's selected world or refuses; none switches
to the room owner's execution context. Root creation binds only its newly
constructed world to initialize that world's projection.

Room creation accepts `:discourse {:system <declared-id> :projection <UUID>
:retention :indefinite}`. Its Datahike system must use the exact discourse schema
and operations `#{:discourse/read :discourse/write}`, with history and a commit
graph enabled. Room records use schema version 2; version 1 records remain
readable. `close!` fences durable work, removes the room's world closure guard,
and returns an idempotent passive completion observation. There is no native
activity to join. Existing conservative refusal of frozen forks while a room
is open remains.

Each envelope and origin sequence commit atomically. Identity retries compare
the canonical request, author and destination without restamping server fields.
Ordering keys encode `(origin sequence, world identity, message identity)` and
make no causal-order claim across independent origins. Unsupported delivery
destinations refuse with `:effect-boundary-unavailable`; control record shapes
cannot be written as conversational authority. SCI cannot receive host authors
or rooms, or export raw discourse writers.

History orders and deduplicates indexed metadata before loading envelope bodies.
A page returns at most the requested count (maximum 256), further limited by the
aggregate portable-result budget. Metadata supplies lookahead; a large next
body cannot prevent returning the current message. Admission validates both the
post result and a singleton history page, including continuation metadata,
before committing an envelope. Oversized requests/envelopes refuse before write.
The implementation still scans projection metadata; it adds no query engine.

Cursors keep `{:basis ... :projection/id ... :after ...}`. A continuation's
basis must be the selected branch's head or one of its ancestors, and its
boundary key must exist at that basis; descendants, siblings and foreign
commits refuse with `:invalid-cursor`. Reading history never writes: a read
that recorded its basis would add work state, and so a diff and a merge
contribution, to the world being read. An ancestor basis is the caller's own
history, so accepting one discloses nothing the branch cannot already read.
History is retained indefinitely in this slice; no GC is added.
Conversation branches separately retain their parent's basis, sharing the same
world and logical system handle; subsequent parent posts do not enter the
branch's inherited history.

Regression coverage:

| Finding | Passing regression |
|---|---|
| 3, cursor authority | `laws/discourse_test.clj`: `d1-w1-history-cursors-cannot-read-child-or-sibling`; `process/discourse_test.clj`: `d1-d4-process-history` |
| 4, aggregate paging | `laws/discourse_test.clj`: `d1-history-pages-budget-aggregate-envelopes` (two 26,000-node bodies, limits 1 and 256, singleton refusal before commit) |
| 10, decision binding | `adapters/discourse_test.clj`: `d4-w1-decision-binding-validates-projection-and-system` (construction and forged-handle edit) |
| 11, selected writes | `adapters/discourse_test.clj`: `w1-w2-durable-writers-use-caller-world` (all work writers and public mutations; unrelated, excluded and missing selection; child edit leaves parent unchanged) |

Existing generated posting/order models, shared-world projection tests, commit
ambiguity injection, work/control separation, merge containment and W1–W6 tests
remain. Test paths in the table are relative to `test/dvergr`.

Verification uses one JVM at a time with `JAVA_TOOL_OPTIONS=-Xmx1500m`:

```sh
clojure -Sdeps '{:paths ["src" "test"]}' -M -m dvergr.process.lineage-test /tmp/dvergr-process-evidence
clojure -Sdeps '{:paths ["src" "test"]}' -M -m dvergr.process.discourse-test /tmp/dvergr-process-evidence
DVERGR_PROCESS_EVIDENCE=/tmp/dvergr-process-evidence clojure -M:test
clojure -M:format
git diff --check
```

Both process writers remain in CI, sequentially before the suite. The discourse
reader compares exact envelopes and resumes an issued page after a real JVM
restart, without hydrating a world or any live runtime.

Final local verification: both writers exited 0; the full suite passed 32 tests
and 354 assertions with zero failures. Formatting and `git diff --check` passed.
Source totals 1,201 physical lines, 412 net above slice 1 and 409 fewer than the
saved slice 2 implementation. No commit or push was made.
