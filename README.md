# dvergr

A Clojure agentic programming framework. dvergr provides a principled model for composing LLM agents as reactive processes — built on [Spindel](https://github.com/replikativ/spindel) (functional reactive runtime), [Datahike](https://github.com/replikativ/datahike) (immutable Datalog database), and [Yggdrasil](https://github.com/replikativ/yggdrasil) (copy-on-write branching for git and databases).

## Concepts

dvergr treats agents at three levels:

**Config** — what an agent is: its model, tool permissions, and isolation mode.

**Task** — running an agent on a bounded piece of work. The execution context is automatically forked (git worktree + Datahike branch), and the result can be merged or discarded after review.

**Process** — a long-lived reactive loop. Agents wait on a mailbox, run tasks concurrently via the Spindel FRP runtime, and remain responsive to control signals (pause, stop, steer) while working.

## Quick start

```clojure
(require '[dvergr.core :as dvergr])
(require '[dvergr.agent :as agent])
(require '[dvergr.model.providers :as providers])

;; Point at any OpenAI-compatible endpoint
(providers/register-openai-compatible!
  :my-provider
  {:base-url "https://api.example.com/v1"
   :api-key  (System/getenv "MY_API_KEY")})

;; One-shot task — blocks until complete
(def result
  (dvergr/ask dvergr/coder
              "Add input validation to src/myapp/api.clj"
              :budget-dollars 0.50))

;; Inspect and accept/reject the agent's work
(println (:result result))
(agent/merge! result)   ; accept — merges git branch + DB into parent
;; or
(agent/discard! result) ; reject — discards branch, no trace
```

## Provider setup

dvergr works with any OpenAI-compatible endpoint. Anthropic is supported natively.

```clojure
(require '[dvergr.model.providers :as providers])

;; Auto-registers if env var is set
(providers/init-defaults!)
;; Reads: ANTHROPIC_API_KEY, OPENAI_API_KEY, FIREWORKS_API_KEY

;; Or register explicitly
(providers/register-openai-compatible!
  :groq
  {:base-url "https://api.groq.com/openai/v1"
   :api-key  (System/getenv "GROQ_API_KEY")})

(providers/register-openai-compatible!
  :ollama
  {:base-url "http://localhost:11434/v1"
   :api-key  "ollama"})
```

Default model is read from `resources/models.edn`. Override by editing that file or calling `registry/register-model!` at startup.

## Agent model

### Config

```clojure
(require '[dvergr.agent.config :as agent-cfg])

(def coder
  (agent-cfg/make-agent
    {:name        "coder"
     :provider    :my-provider
     :model       "my-model-id"
     :isolation   :sci       ; :native (full JVM) or :sci (sandboxed SCI)
     :tools       :all       ; :all, a set of tool names, or a predicate fn
     :permissions #{:use-tools :spawn-agents}
     :system-prompt "You are an expert Clojure developer."}))
```

### Task (one-shot, fork/merge)

```clojure
(require '[dvergr.agent.task :as task])
(require '[dvergr.agents.core :as proc])

(def ctx (proc/create-shared-context))   ; registers git + Datahike

(binding [rtc/*execution-context* ctx]
  ;; Run agent in isolated fork
  (let [result @(task/ask! coder "Refactor the auth module")]
    (when (task/successful? result)
      (task/merge! result))             ; git branch merged, DB merged
    (task/extract-result result)))      ; last assistant message as string

;; Parallel agents
(binding [rtc/*execution-context* ctx]
  (let [[r1 r2] @(task/parallel
                   (task/spawn! coder "Implement feature A")
                   (task/spawn! coder "Implement feature B"))]
    (task/merge! r1)
    (task/merge! r2)))
```

### Process (long-lived reactive loop)

```clojure
(require '[dvergr.agent.process :as proc])
(require '[dvergr.agent.turn :as turn])

(def ctx (proc/create-shared-context))

(binding [rtc/*execution-context* ctx]
  (def worker (proc/create-agent {:id :worker :provider :my-provider :model "..."}))

  ;; think-fn receives each task and returns a spin
  (proc/start! worker (turn/make-think-fn {:max-turns 10 :budget-dollars 1.0}))

  ;; Send tasks — fire and forget
  (proc/send! worker "Summarize the latest PRs")
  (proc/send! worker "Check for flaky tests")

  ;; Or await a specific task
  @(proc/ask worker "What is the current test coverage?")

  ;; Inject guidance without interrupting current work
  (proc/steer! worker "Focus on the authentication module")

  (proc/stop! worker))
```

## Multi-agent patterns

```clojure
(require '[dvergr.agent.patterns :as patterns])

;; Sequential pipeline: each agent continues the conversation
@(patterns/pipeline conversation [researcher analyst writer])

;; Fan-out: all agents solve the same problem in parallel
@(patterns/fan-out [coder-a coder-b coder-c] "Implement a rate limiter")

;; Race: first agent to finish wins, others cancelled
@(patterns/race-first [fast-agent careful-agent] task)

;; Adversarial debate with a judge
@(patterns/debate agent-a agent-b judge "Should we use CQRS here?" 3)

;; Feedback loop: coder implements, reviewer critiques, iterate
@(patterns/feedback-loop coder reviewer "Add pagination" :max-rounds 3)
```

## Phase-based workflows

Agents can be structured around phases, each with different tool access and prompts:

```clojure
(require '[dvergr.agent.task :as task])

;; Run agent through explore → implement → verify phases
;; Phase definitions loaded from resources/phases/*.edn
@(task/ask! coder "Add rate limiting to the API"
            {:workflow [:explore :implement :verify]})
```

Phase EDN files are hot-reloadable and can be edited by meta-agents.

## Tools

Tools are registered in a global registry and injected into agent context at runtime.

```clojure
(require '[dvergr.tools :as tools])

;; Register a custom tool
(tools/register!
  {:name        "query_database"
   :description "Run a Datalog query against the application database."
   :schema      {:type "object"
                 :properties {:query {:type "string"}}
                 :required ["query"]}
   :execute     (fn [{:keys [query]} _ctx]
                  {:content (pr-str (d/q (edn/read-string query) @conn))})})

;; See registered tools
(keys @tools/registry)
```

Built-in tools include: `glob`, `grep`, `read_file`, `write_file`, `edit_file`, `run_tests`, `bash`, `clojure_eval`, `git_*`, `knowledge_search`, `knowledge_add`, `web_fetch`, `youtube_transcript`, `tweet_lookup`, `hn_search`, `reddit_search`, `github_*`, and more.

## SCI sandbox

Agent code can run in an isolated SCI (Small Clojure Interpreter) context, preventing access to the host JVM while retaining Clojure semantics. The sandbox provides curated namespaces and enforces resource limits.

```clojure
(require '[dvergr.sandbox :as sandbox])

(def sci-ctx (sandbox/fork-for-session))

;; Evaluate code in isolation — no file I/O, no shell, no class loading
(sandbox/eval-code sci-ctx "(+ 1 2)")
;; => 3

;; Add application namespaces selectively
(sandbox/add-datahike-query-ns! sci-ctx conn)
(sandbox/add-inference-ns! sci-ctx runtime)
```

Agents with `:isolation :sci` run all LLM-generated code through the sandbox automatically.

## Simulation system

Simulations are sandboxed Clojure programs with their own Datahike database, external data access, and an optional run loop.

```clojure
(require '[dvergr.simulations.core :as sim])

;; Create a simulation from source code
(sim/create! conn {:name      "market-model"
                   :code      (slurp "resources/simulations/market-model.clj")
                   :maintainer :analyst})

;; Run it (executes the (main) function in the simulation)
(sim/run! conn "market-model" execution-ctx)

;; Query its internal Datahike DB
(sim/query conn "market-model" '[:find ?e :where [?e :signal/type :price]])

;; Update the code — agents can self-modify simulations
(sim/update-code! conn "market-model" new-source)
```

Inside a simulation, available namespaces: `dh/*` (own Datahike DB), `intake.*` (read-only external data), `llm/call`, `llm/summarize`, `spin`/`await`, `calendar/*`.

## Daemon

The daemon runs a set of named agents as long-lived processes, wired to Telegram, rooms, and schedules.

```clojure
(require '[dvergr.daemon :as daemon])

(def d
  (daemon/start!
    {:agents {:assistant {:provider    :anthropic
                          :model       "claude-opus-4-5-20251015"
                          :tags        #{:secretary}
                          :profile     "assistant"}   ; loads resources/agents/assistant.md
              :analyst   {:provider    :my-provider
                          :model       "my-model"
                          :tags        #{:analyst}
                          :interval-ms 3600000}}      ; sweeps every hour
     :default-agent   :assistant
     :bootstrap-rooms [{:slug "general" :title "General"}
                       {:slug "research" :title "Research"}]
     :telegram        {:token (System/getenv "TELEGRAM_TOKEN")}}))

(daemon/dispatch! d {:chat-id 12345 :text "Summarize today's news"
                     :from    {:username "alice"}})

(daemon/stop! d)
```

Agents tagged `:secretary` handle conversational Telegram messages. Agents with `:interval-ms` run periodic sweeps.

## Intake tools

External data ingestion via registered tools:

| Source | Tool name(s) |
|---|---|
| Hacker News | `hn_search`, `hn_item` |
| Reddit | `reddit_search`, `reddit_post` |
| Bluesky | `bsky_search` |
| Mastodon | `mastodon_search` |
| GitHub | `github_repo`, `github_search_code`, `github_releases`, `github_contributors` |
| Web | `web_fetch` |
| YouTube | `youtube_transcript` |
| Twitter/X | `tweet_lookup` |
| Email (IMAP) | `mail_search`, `mail_read` |
| LinkedIn | `linkedin_profile` |
| Companies House | `companies_house_search` |
| SEC EDGAR | `sec_edgar_filings` |
| Wayback Machine | `wayback_fetch` |

## MCP server

dvergr exposes itself as an MCP server so other MCP-capable clients (Claude Desktop, etc.) can drive dvergr agents as tools:

```bash
clj -M:mcp-server
```

Tools exposed: `agent_list`, `agent_create`, `agent_send_message`, `agent_stop`, `clojure_eval`.

## Knowledge base

Agents share a persistent knowledge base backed by Datahike and Yggdrasil, with full-text search via Scriptum/Lucene.

```clojure
;; Inside an agent tool call:
knowledge_add {:title   "Rate limiting: token bucket algorithm"
               :summary "Token bucket is the standard approach: N tokens replenished/second,
                         each request costs 1 token. Simple, predictable under burst load."
               :tags    ["architecture" "api-design"]}

knowledge_search {:query "rate limiting algorithms" :limit 5}
```

The knowledge base is forked alongside git and Datahike when an agent starts a task — writes stay isolated until the agent's work is merged.

## Budget accounting

All LLM usage is tracked to the microdollar.

```clojure
(require '[dvergr.chat.context :as chat-ctx])

(def ctx (chat-ctx/create-chat-context
           {:title          "My task"
            :budget-dollars 1.00}))

(chat-ctx/get-budget ctx)
;; => {:budget-microdollars 1000000
;;     :spent-microdollars  42318
;;     :remaining-microdollars 957682}
```

Agents stop cleanly when budget is exhausted — no surprise bills.

## Architecture

```
dvergr.core             Public API (ask, run, pre-built agents)
dvergr.agent            Public re-export facade
dvergr.agent.config     Agent configuration record (make-agent)
dvergr.agent.task       One-shot task execution (ask!, spawn!, merge!, discard!)
dvergr.agent.process    Long-lived reactive loop (create-agent, start!)
dvergr.agent.turn       Async LLM turn bridge (run-turn-async, make-think-fn)
dvergr.agent.phases     Phase-based execution engine (run-turns, run-workflow)
dvergr.agent.patterns   Multi-agent coordination (pipeline, fan-out, debate, ...)
dvergr.agent.testing    Test utilities (stub-llm, scripted-llm)
dvergr.chat.agent       LLM turn execution (run-agent-turn!)
dvergr.chat.context     Conversation + budget tracking
dvergr.chat.schema      Datahike schema for messages, tools, budget
dvergr.chat.accounting  Microdollar cost tracking
dvergr.model.provider   Provider protocols (LLMProvider, ToolFormatter, ...)
dvergr.model.providers  Provider registration (register-openai-compatible!, init-defaults!)
dvergr.model.registry   Model registry (get-model, get-default, register-model!)
dvergr.model.chat       Raw LLM call (chat)
dvergr.tools            Tool registry and execution
dvergr.sandbox          SCI sandbox (eval-code, fork-for-session)
dvergr.simulations.*    Simulation lifecycle and sandboxing
dvergr.daemon           Long-running multi-agent daemon
dvergr.rooms.*          Room pub/sub bus
dvergr.intake.*         External data ingestion tools
dvergr.web.*            Web dashboard
dvergr.scheduler.*      Periodic task scheduling
```

## Dependencies

| Library | Role |
|---|---|
| [Spindel](https://github.com/replikativ/spindel) | FRP reactive runtime, CoW context forking |
| [Datahike](https://github.com/replikativ/datahike) | Immutable Datalog database, persistent conversation storage |
| [Yggdrasil](https://github.com/replikativ/yggdrasil) | Copy-on-write branching for git and Datahike |
| [SCI](https://github.com/babashka/sci) | Small Clojure Interpreter for sandboxed agent code |
| [Scriptum](https://github.com/replikativ/scriptum) | Versioned full-text search indices (Lucene) |
| [hato](https://github.com/gnarroway/hato) | HTTP client for provider APIs |
| [Telemere](https://github.com/taoensso/telemere) | Structured logging |

## License

Copyright © 2024–2026 Christian Weilbach. All rights reserved.
