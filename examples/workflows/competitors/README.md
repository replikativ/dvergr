# Competitor discovery (a room workflow)

A workflow bundle (doc/room-workflows.md): find products competing with Simmis, each with
its site, a claim and a quote from a page the run fetched. Reference set: the simm.is
comparison (Wato, PromptQL, Dust, Buzz). Needs web search: `BRAVE_API_KEY` as a sandbox
secret: a `:secrets` entry in `config.local.edn` in the directory `room-run` is started from
(or the file `DVERGR_CONFIG` names; see the example in doc/boundary-secret-injection.md). The
relevance judge is `claude-code-haiku`, so the `claude` CLI must be installed and logged in.

    clojure -M -m dvergr.catalog.room-run examples/workflows/competitors --check
    clojure -M -m dvergr.catalog.room-run examples/workflows/competitors \
      --models codex-subscription-luna,claude-code-haiku --repetitions 2

In a daemon (MCP client connected with `--profile bench`): write it to
`workflows/competitors/` of a room, then `catalog_check`,
`catalog_calibrate`, `catalog_promote` (admin profile), `catalog_benchmark {workflow "<room>/competitors"}`,
and `catalog_freeze` for a stable variant on the web the live runs saw.
