# Job cleanup and Room lifetime

A Job remains a durable Run. The existing process-local `dvergr.jobs/live`
owner retains its callbacks, outcome and waiters until physical cleanup has
been acknowledged. It is not another execution engine or persisted workflow.

The finishing order is:

```mermaid
flowchart LR
    work[Work completes] --> core[before-finish: join work and return resources]
    core --> persist[Persist terminal Run; retain execution lease]
    persist --> adapter[settled: retire adapter]
    adapter --> release[Release execution lease]
    release --> done[Drop Job owner and acknowledge done]
```

Core cleanup, terminal persistence and adapter retirement run under the control
Room's context. `:ctx` still selects the work Spin's execution context, but an
adapter can close that separate context during retirement. Final Run release
therefore uses the surviving control context. No Room access follows release;
the remaining actions only drop process-local ownership and deliver `done`.

`run/retain-finished!` persists the chosen outcome without removing its active
execution lease. Room teardown must still wait for that Run. Only
`run/release-finished!` removes the lease and emits `:run/finished`. A terminal
status is consequently compatible with `jobs/status` reporting
`:settled? false` while adapter retirement is pending.

## Failure and recovery

A failed stage stays with the same Job owner. It retains the original error
privately, exposes a bounded stage keyword through `:cleanup-stage`, and reports
`:cleanup-required? true`. Before core cleanup succeeds, the Run may still have
its running/cancelling projection. After terminal persistence, a later cleanup
failure preserves that immutable outcome. It never rewrites a completed Run
into a failed one.

The HOST-only `recovery-state` returns the exact held stage and a fresh token.
After reconciling the original native operation, the HOST calls `recover!` with
that locator and either:

- `:retry`, after establishing that repeating the failed stage is safe;
- `:acknowledge`, attesting the physical outcome without repeating its callback.

Acknowledgement cannot invent terminal persistence or bypass a live execution
lease. Acknowledged stages never repeat. Recovery is exclusive with the monitor
and other recovery attempts; a subsequent failure invalidates the previous
token. These tokens coordinate one native owner; they confer no permission and
are not exposed through SCI or registered as tools. There is no automatic
cleanup replay or timer.

Synchronous work-construction failure uses the same lifecycle. Once the Job's
work callback has begun, its adapter retirement belongs to Jobs even if another
admission journal failed to publish its Job pointer. Callers must not also run
an abandonment callback for that same owner.

These native callback handles are process-local. Restart recovery uses the
existing orphan Run/accounting machinery; it does not restore arbitrary native
closures. Durable evidence roots and their retained source bindings require
their own journal and retirement policy.

Tests cover real Room-close refusal while core or adapter cleanup is held,
explicit recovery, context retirement, lost terminal/release acknowledgements,
concurrent/stale recovery and synchronous construction failure. Work and all
native fixtures are deterministic and perform no inference.
