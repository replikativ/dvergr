---
name: model-usage
description: Inspect LLM spend and tokens consumed across recent runs
provides: [:observability, :cost-analysis]
requires_tools: [clojure_eval]
vetted: false
source: openclaw
---

# Model usage

Help the user understand where LLM spend is going: which agents,
which chats, which models, and which tools.

## When to use (trigger phrases)

- "how much have I spent today?"
- "which agent is burning my budget?"
- "show me token usage by model"

## Tactics

1. Query the `:account/*` schema via Datalog:
   ```clojure
   (d/q '[:find ?type (sum ?amount)
          :where [?e :account/type ?type]
                 [?e :account/amount ?amount]]
        @dvergr.room/*room*)   ; the cost ledger lives in your room's store
   ```
2. For per-chat breakdown, group by `:account/chat`.
3. For per-model breakdown, look at `:account/metadata` (often
   has `:model`).
4. Return a small table and one observation (e.g. "75% of today's
   spend is one runaway research session").

## Caveats

- The ledger captures dollars in microdollar units; divide by 1e6.
- Don't claim trends from <24 hours of data; the volatility is
  too high.
