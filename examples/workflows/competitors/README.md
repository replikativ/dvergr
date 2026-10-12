# Competitor discovery (a room workflow)

A workflow bundle (doc/room-workflows.md): find products competing with Simmis, each with
its site, a claim and a quote from a page the run fetched. Reference set: the simm.is
comparison (Wato, PromptQL, Dust, Buzz). Needs web search: `BRAVE_API_KEY` as a sandbox
secret: a `:secrets` entry in `config.local.edn` in the directory `room-run` is started from
(or the file `DVERGR_CONFIG` names; see the example in doc/effects.md, "Secrets at the boundary"). The
config is the process's, not the home's: the home's headless daemon builds each sandbox's
secrets from it, and the agent's `(env/get "BRAVE_API_KEY")` returns a placeholder that the
sandbox's HTTP client replaces with the key only on a request to an allowed domain. The
relevance judge is `claude-code-haiku`, so the `claude` CLI must be installed and logged in.

    clojure -M -m dvergr.catalog.room-run examples/workflows/competitors --check
    clojure -M -m dvergr.catalog.room-run examples/workflows/competitors \
      --models codex-subscription-luna,claude-code-haiku --repetitions 2

Without `--home`, the experiment is kept in the home `$DVERGR_HOME/workflow-runs/competitors`
when `DVERGR_HOME` is set, else `workflow-runs/competitors` in the working directory; the
log is `dvergr.log` in that home. Running the same command again resumes it.

In a daemon (MCP client connected with `--profile bench`): write it to
`workflows/competitors/` of a room (`room_write`), then `catalog_check`,
`catalog_calibrate`, `catalog_promote` (admin profile), `catalog_benchmark {workflow "<room>/competitors"}`,
and `catalog_freeze` for a stable variant on the web the live runs saw.
