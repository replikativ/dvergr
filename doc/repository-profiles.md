# Repository profiles and existing Room admission

A repository profile is a persisted keyword resolved through trusted HOST
options. Repository contents and transports cannot supply its executable policy.
The required synchronous lifecycle hooks remain `:initialize!`,
`:prepare-open!`, and `:abort-create!`.

A host may additionally provide `:legacy-room-ready?`. This read-only predicate
receives the immutable control DB, the Room UUID, and the actual prepared default
repository DB. Only literal `true` admits a profiled Room whose historical
provisioning state and version are both absent. Missing policy, absent predicate,
false/nil/non-boolean results, partial provisioning, and archived Rooms do not
gain admission. Ordinary unprofiled legacy Rooms and ready/version-1 Rooms keep
their existing behavior.

For example, an embedder can attest a completed additive upgrade by matching its
control journal with the native repository's upgrade receipt and foundation.
It need not rewrite historical provisioning fields or reinitialize the store.
The predicate must not provision, recover, register another Room, fork, retain
live resources, or start asynchronous work. It is an integrity observation, not
permission for a caller to access the Room.

Supply the same trusted options to both startup phases and later registration:

```clojure
(system-rooms/hydrate-rooms! {:repository-profiles policies})
(rooms/hydrate-registry! root {:repository-profiles policies})
(rooms/register-prepared-room! room-id {:repository-profiles policies})
```

The existing one-argument entrypoints retain their defaults. Both eager and
explicit registration use the same readiness decision. Source lookup validates
the actual owned workspace, canonical root/context, and physical store mapping;
control and repository bases plus context/connection identity are rechecked
after the predicate. A concurrent change refuses that observation. Existing
messages-identity, foreign-incarnation, and constructor-ownership checks remain.

An original migration source hash is historical evidence, not an ongoing ban on
editing files. Normal later edits must remain admissible. The predicate should
validate durable upgrade identity and mandatory foundation shapes rather than
requiring every authored Markdown file to parse; raw repair must remain possible.
