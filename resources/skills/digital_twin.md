---
name: digital-twin
description: Build internal models of external systems using propose_change
requires_tools:
  - propose_change
  - clojure_eval
  - knowledge_search
  - knowledge_add
---

## Digital Twin Pattern

A **digital twin** is a useful internal model of an external system — not a
perfect replica, but a pragmatic representation that lets you query, track
changes, and reason about the environment programmatically.

### When to build a twin

- You're repeatedly fetching the same external source (Zulip, GitHub, RSS)
- You want to detect changes or trends over time
- You need to cross-reference data from multiple sources
- Ad-hoc fetching is too slow or expensive for the questions you want to answer

### Entity Type System

Every entity in the knowledge graph can have a **type**, **URL**, and **tags**.
Use these when creating entities via `knowledge_add` to enable filtering and
structured queries on the `/entities` web page.

Available types:
- `competitor` — competing products/companies (e.g. Snowflake, Datomic)
- `client` — current or prospective clients (e.g. Stripe)
- `partner` — collaborators and partners
- `project` — internal or external projects
- `technology` — libraries, frameworks, protocols

Example — creating a typed entity:

```
knowledge_add {
  "title": "Snowflake",
  "entity_type": "competitor",
  "url": "https://snowflake.com",
  "tags": ["database", "cloud", "data-warehouse"],
  "summary": "Cloud data warehouse platform. Competitor in the enterprise data infrastructure space.",
  "source": "web",
  "relevance": 5
}
```

Example — client profiling:

```
knowledge_add {
  "title": "Stripe",
  "entity_type": "client",
  "url": "https://stripe.com",
  "tags": ["fintech", "payments", "api-first"],
  "summary": "Global payments platform. Large engineering org with significant data infrastructure needs.",
  "relevance": 4
}
```

### What a twin can be

Twins are environment models. They can take many forms:

- **Knowledge graph entities** — lightweight entities via `knowledge_add` with
  `entity_type`, `tags`, and `url`. Start here. Viewable at `/entities`.
- **Datahike entities** — structured data you can query with Datalog
- **Reactive spins** — live intake pipelines using spindel FRP in `clojure_eval`
- **Files on disk** — EDN snapshots, CSV exports, cached API responses

Choose the simplest form that answers your questions. A few knowledge_add
entries may be enough. Don't build a schema when a map will do.

### Reactive Intake with Spindel

The SCI sandbox has full spindel FRP support. You can write reactive spins
in `clojure_eval` that compose intake sources:

```clojure
;; In clojure_eval — full spin/await/track available
(require '[org.replikativ.spindel.spin.cps :refer [spin]])
(require '[org.replikativ.spindel.effects.await :refer [await]])
(require '[spindel.comb :as comb])
(require '[intake.web :as web])
(require '[intake.hn :as hn])

;; Parallel fetch from multiple sources
(def competitor-data
  (spin
    (let [[web-content hn-posts]
          (await (comb/parallel
                   (spin (web/fetch "https://snowflake.com/blog"))
                   (spin (hn/search "Snowflake" {:days-back 30}))))]
      {:website web-content
       :hn-discussions hn-posts})))

;; Deref at REPL boundary to see results
@competitor-data
```

Use this pattern to build live intake pipelines that aggregate data from
multiple sources, then store findings via `knowledge_add`.

### How to propose a twin

Use `propose_change` to delegate the implementation to a developer sub-agent.
The sub-agent works in a forked context — its code is reviewed before merging.

Your task description should include:

1. **What** external system to model (API endpoint, data source, feed)
2. **What** questions the twin should answer
3. **Entity type** — what type to assign (`competitor`, `client`, etc.)
4. **Schema** sketch (if Datahike — use `:db.unique/identity` for idempotent sync)
5. **Sync function** signature: `(sync! conn)` or `(sync! conn opts)`
6. **Where** to put the code: `src/dvergr/twins/<name>.clj`

Example call:

```
propose_change {
  "task": "Create src/dvergr/twins/zulip_datahike.clj that:
    1. Defines a Datahike schema for Zulip stream messages (:zulip.msg/id as unique identity)
    2. Implements (sync! conn {:stream \"datahike\" :days 7}) that fetches recent messages via Zulip API and transacts them
    3. Includes a test that syncs and queries for messages containing 'release'
    The goal: let us query Zulip conversations about datahike with Datalog instead of re-fetching every sweep.",
  "budget": 1.0,
  "phases": ["explore", "implement", "verify"]
}
```

### The twin lifecycle

1. **Create entity** — `knowledge_add` with type/url/tags for the target
2. **Write intake spins** — reactive pipelines in `clojure_eval` to gather data
3. **Derive model** — cross-reference sources, extract patterns
4. **Update knowledge** — store findings back via `knowledge_add` with context
5. **Propose code** — if the twin needs persistent infrastructure, use `propose_change`

### Budget and cost

Each twin proposal runs a sub-agent with its own budget (default $0.50).
This cost is tracked separately from your sweep budget. Be explicit about
budget when the task is complex — `"budget": 1.0` for multi-file work.

### After acceptance

Once a proposal is accepted via the web UI, the code is merged into the main
branch. On the next daemon restart, the new namespace is available:

```clojure
(require '[dvergr.twins.zulip-datahike :as zt] :reload)
(zt/sync! conn {:stream "datahike" :days 7})
```

Use `knowledge_add` to record that the twin exists and what it provides, so
future sweeps know to use it instead of raw fetching.

### Design principles

- **Useful, not perfect** — model what you need now, extend later
- **Typed entities first** — start with `knowledge_add` + type/tags before building code
- **Idempotent sync** — re-running sync should be safe (upsert, not duplicate)
- **Queryable** — the point is to answer questions, not just store data
- **Isolated creation** — always use `propose_change`, never write twins directly
- **Reactive composition** — use spindel spins to compose intake sources
