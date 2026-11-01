# Dispatch settlement and custody

One native Run supervisor owns the accounting obligations of its governed
model calls. Its LLM turns and protocols share that owner through their HOST
model scope. The owner is private process state, outside forkable Room state;
Kontor remains the sole authority for balances and transfer receipts.

Each call registers before effects. Each dispatch retains its reservation and
admission commands before submitting them, including their original identity,
route, amount and explicit audit timestamps. Provider entry is recorded before
the HTTP function is invoked. Reconciliation never invokes that function.

## Settlement invariants

1. A submitted command remains reachable until acknowledgement. Missing
   acknowledgement does not mean failure to commit.
2. Retry sends the exact original command. It cannot generate a new admission,
   change a timestamp or recompute an amount from a later balance.
3. Settlement plans freeze before the first settlement transfer. A successful
   priced answer can require both a hold transfer and a return; acknowledging
   one does not acknowledge the other. Recovery with no new usage resumes the
   original plan. Different priced usage cannot replace it.
4. A definitely refused reservation/admission is distinct from a refused
   mandatory settlement command. The latter remains an obligation.
5. A request that never entered HTTP can return its monetary reservation after
   reconciling the submitted commands. An ambiguous provider request retains
   the existing conservative forfeiture policy. Admissions already consumed
   are not refunded.
6. Nested model calls have distinct records. The gateway reuses its enclosing
   call but carries the exact dispatch identity through response handling.
7. Original cleanup owns a call until it stops touching the records. Only then
   may a reconciler claim it. Completion is conditional and idempotent; it
   cannot resurrect a removed call or duplicate custody evidence.
8. The finalizer seals accounting admission after supervisor join. It waits
   for active conveyed calls and reconciles exited calls before returning
   available Run resources or settling its world. Failed ledger operations
   retain the original live owner. No ordinary consumed cleanup callback is
   used as the recovery owner.

Accounting failures are distinct from provider failures, so a ledger timeout
cannot trigger another inference request. The gateway retains response-body
custody until handoff, closing a response when intervening accounting fails.

## Independent facts and limits

`run/observe-settlement` reads the original live Room incarnation's native
facts. It reports workers' quiescence, supervisor admission sealing, reservation
reconciliation and hold-transfer evidence separately. It performs no recovery
or authority grant. Its absence after owner release or restart is **unknown**;
it is not a durable completion acknowledgement.

A reservation's paid amount moves into the payer's shared hold wallet.
Consumer usage charging occurs later and can draw on that hold. Therefore
reservation reconciliation does not establish usage consumption. The observer
reports `:usage-consumption :not-established` and identifies hold transfers;
these are historical transfer evidence, not a claim about the hold's current
balance. Recovery neither refunds nor sweeps that shared hold, which can also
serve other calls or Runs. Returning actual available allowance remains a
separate operation.

This contract covers current-process governed HTTP dispatch ownership. It
does not reconstruct native closures or unfinished call journals after process
loss, prove publication/acceptance, or make arbitrary consumer usage charging
atomic. Evaluation judges and money-only legacy calls do not acquire a new
governed scope implicitly. Stronger restart and charge-handoff contracts require
their own explicit witnesses and integration with their actual native owner.
