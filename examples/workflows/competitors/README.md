# Competitor discovery (a room workflow)

A workflow bundle (doc/room-workflows.md): find products competing with Simmis, each with
its site, a claim and a quote from a page the run fetched. Reference set: the simm.is
comparison (Wato, PromptQL, Dust, Buzz). Needs web search: `BRAVE_API_KEY` as a sandbox
secret (see the `:secrets` example in doc/boundary-secret-injection.md).

    clojure -M -m dvergr.catalog.room-run examples/workflows/competitors --check
    clojure -M -m dvergr.catalog.room-run examples/workflows/competitors \
      --models codex-subscription-luna,claude-code-haiku --repetitions 2

In a daemon: write it to `workflows/competitors/` of a room, then `catalog_check`,
`catalog_calibrate`, `catalog_promote`, `catalog_benchmark {workflow "<room>/competitors"}`,
and `catalog_freeze` for a stable variant on the web the live runs saw.
