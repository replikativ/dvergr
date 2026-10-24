# Cash for the next eight weeks

You are helping a small software company plan its cash. Today is in
`/inputs/terms.edn`, with the cash in the bank, the founder's hours for
calls, the factoring fee and the ruin limit. The numbers are a synthetic
scenario, not market data.

The company's documents:

- `/inputs/invoices.csv`: every invoice of the last eighteen months, paid or
  written off, with the collection action taken on it then (`none`,
  `reminder` or `call`), and the invoices still open. The actions in the
  history were assigned at random, so their effects can be compared.
- `/inputs/bank.csv`: the business account over the last eight weeks.
  Recurring costs continue as they appear there; there are no other costs
  or income in the next eight weeks beyond the open invoices.
- `/inputs/notes.md`: recent emails, call notes and news. Some may matter,
  some may not.

## Decisions

Choose one action per open invoice:

- `:none`: wait.
- `:reminder`: a free email reminder, sent today.
- `:call`: the founder calls today. At most `:founder-hours` calls.
- `:factor`: sell the invoice today for its amount less `:factoring-fee`.
  The cash arrives today and the risk is gone.

The company is **ruined** if its cash is below zero at the end of any of
the next eight weeks (weeks start today). The chance of ruin must stay at or
below `:max-ruin`. Within that, the plan should maximise the expected worth
after eight weeks: the cash then plus 98 % of what is still owed by
customers who will pay later.

## Forecasts

For each open invoice, if no action were taken on it: the probability that
it is paid within 14, 28 and 56 days from today. And under your plan: the
cash after eight weeks as 10th, 50th and 90th percentiles, and the chance
of ruin. Forecasts are scored with proper scoring rules against what really
happens in the company's world, which you do not see: be calibrated and as
sharp as the evidence allows.

## Answer

Write `/out/answer.edn`, one EDN map:

```clojure
{:actions  {"INV-2041" :factor, ...}            ; every open invoice you act on
 :payments {"INV-2041" {:by-day-14 0.3 :by-day-28 0.6 :by-day-56 0.85}, ...}
 :cash     {:p10 12000 :p50 18000 :p90 21000}
 :p-ruin   0.02}
```

Only the answer in `/out/answer.edn` when you stop is scored, and your time
and steps are limited. Write a first complete answer early, from a simple
model, then keep working: build the model, check it, and overwrite the answer
as it improves. The first answer is a safeguard, not the end of the task.

Put the model you built to get there under `/out/model/` (code and notes),
and your reasoning in `/out/rationale.md`: what you inferred about each
customer and each action, what the notes changed, and where you are unsure.
