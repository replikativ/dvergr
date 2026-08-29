# Tools & the SCI sandbox

How agents act on the world in dvergr: a **tool registry** (`dvergr.tools`) the LLM
calls by name, and a **SCI sandbox** (`dvergr.sandbox`) that `clojure_eval` runs code
in. Companion to `doc/state-model.md` and `doc/process-model.md`.

## The tool registry

`dvergr.tools/registry` is an atom of `name → tool-def`. A tool-def is plain data:

```clojure
{:name "read_file"
 :description "..."          ; shown to the LLM
 :parameters  {:type "object" ...}   ; JSON schema (served as :input_schema + :parameters)
 :execute (fn [input ctx] ...)}      ; or :handler (fn [input]) for ctx-free tools
```

`execute` resolves `(:execute tool)`, falling back to `(:handler tool)`, then truncates
the result (~15K tokens, middle-preserving) to protect the context window. Every tool
returns `{:type :success/:error :content "..." :metadata {...}}`.

### Built-in tools (all `register!`ed in `dvergr.tools`)

- **Files & code edits** — `read_file`, `write_file`, `edit_file` (exact-string, must be
  unique), `glob`, `grep`, `clojure_edit` (structural form replace/insert), `code_query`
  (queries a katzen ACSet index built from the Clojure you write this session),
  `clj_kondo` (lint), `run_tests` (Kaocha, runs in the agent's worktree).
- **Code eval / shell** — `clojure_eval` (the main one — see below) and `shell` (a
  muschel-jailed bash via `dvergr.intake.bash/run`; read-only commands auto-allowed,
  destructive ones like `sudo`/`rm -rf` auto-denied, output capped at 8000 chars/stream).
- **Knowledge graph** — `knowledge_search`, `knowledge_add` (the `[[Entity]]` graph),
  `entity_sync` (refetch an entity's stored sync sources + LLM-extract). (A general
  fulltext search over a native Datahike `:scriptum` secondary index is planned; for
  now agents query Datahike directly in the sandbox.)
- **Tasks** — `task_create`, `task_list`, `task_update` (Datahike-backed `:task/*`).
- **Agent / orchestration** — `spawn_agent` (Run-backed delegation with automatic
  settlement), `propose_change` (the same interpreter with its world retained for
  review), `update_agent_profile` (rewrites an agent's system prompt → actor row),
  `budget` (remaining μ$ / cost estimate).
- **Data sources (intake)** — the `dvergr.intake.*` modules: HN, Reddit, Lobsters,
  Bluesky, Mastodon, dev.to, web fetch/search, YouTube transcripts, Twitter, GitHub,
  RSS, mail, Slack, Zulip, plus company/market intel (SEC EDGAR, Companies House,
  Finnhub, GLEIF, Wikidata, crt.sh, Wayback, LinkedIn, Adzuna jobs, arXiv). Most are
  surfaced **inside the sandbox** as `intake.*` namespaces rather than as discrete tools.

### Role-scoping = the `:tools` allowlist

There is **no separate role lattice**. An agent's tool set *is* its capability boundary.
`make-context` takes a `:tools` map (name→tool-def); `tool-definitions` offers exactly
that set to the LLM, and `execute` runs **only** that set — no fallback to the global
registry. A hallucinated, forced, or injected call to a tool the agent wasn't handed
returns `"Tool not available to this agent"`. When `:tools` is absent the global
registry is used unrestricted (e.g. an unscoped REPL). A "role" is just a reusable,
named tool set in agent config — plain data.

## The SCI sandbox (`clojure_eval`)

`clojure_eval` runs Clojure in a per-session [SCI](https://github.com/babashka/sci)
context (`dvergr.sandbox`). `(def x 1)` and `(defn …)` persist across evals within the
session; other sessions are isolated. Each call gets a **60s hard timeout** and is
cancellable.

### Surface agents get

- A safe subset of `clojure.core` + `clojure.string/set/walk/edn` and `clojure.test`
  (write + `run-tests` tests fully inside the sandbox).
- A curated set of Java classes (`Math`, `String`, numeric wrappers, exception types,
  `java.time.*`, `UUID`, `Date`). **`System` is deliberately not exposed** (no
  `System/exit`, no `System/getenv` secret leaks).
- **Injected integrated namespaces** (via `setup-agent-namespaces!`), called
  fully-qualified, no `require` needed:
  - `dh` — datahike `q`/`pull`/`transact!` against the shared chat DB.
  - `dvergr.intake.*` — read-only external SOURCE files in the room repo (cloned from
    the [dvergr-sandbox](https://github.com/replikativ/dvergr-sandbox) stdlib);
    `require` + extend them. e.g. `dvergr.intake.hn`,
    `dvergr.intake.web-fetch` (`fetch-page`), `dvergr.intake.web-search` (`search`),
    `dvergr.intake.github`, `dvergr.intake.youtube` (`get-transcript`), … —
    `(sandbox/overview)` lists the live set.
  - `search`, `entity`, `knowledge` — knowledge base + graph.
  - `room` (post to / read other rooms), `tasks`, `agents` (read-only directory),
    `actors` (spawn sub-agents / assign skills), `calendar`, `skills`.
  - `llm` — cheap one-shot LLM calls (`summarize`/`call`).
  - `doc`, `vision` — media extraction: `doc/extract-text` (PDF/text → string),
    `vision/describe` (image → OCR + description), `vision/extract` (image →
    schema-constrained JSON with per-field verification). Read through the
    chat-ctx's muschel FS, so `/drive` files work; see [media.md](media.md).
  - `scheduler` — per-room recurring / one-shot tasks, incl. `:code` schedules
    (see [scheduling.md](scheduling.md)).
  - `fs`, `git`, `bash`, `proc`, `http`, `env` — path-safe, audited I/O (see boundaries).
  - `spindel.comb` / `spindel.sig` / `sync` — reactive primitives; `spin`/`await`/`track`
    when the session is backed by a spindel execution context.
  - `sandbox` — runtime self-reflection: `(sandbox/overview)` lists every injected
    namespace with purpose + example + fns; `(sandbox/doc 'dh)` zooms into one.

### Safety boundaries

- **Denied**: `eval`, `load-file`, `load-string` (and their `clojure.core/*` forms).
- **No raw file/shell/network.** I/O only goes through the gated namespaces: `fs`/`git`
  are path-safe and **audited** (every op logged to an audit-log atom); `bash` is the
  muschel jail (workspace rooted at `/`, relative paths only, destructive ops blocked);
  `proc` is capability-gated by an explicit command allow-list (default `#{}` — nothing);
  `http` is domain-gated; `env` returns a *placeholder* for any configured API key,
  which `http` substitutes only at the key's bound domain + slot and scrubs from the
  response — so the agent uses keys it never sees (see
  [boundary-secret-injection.md](boundary-secret-injection.md)).
- **Resource limits**: an `interrupt-fn` fires at every fn-body entry to honour
  `Thread.interrupt()` (Esc / watchdog) and cap thread-allocated memory (default 256 MiB).
  Timeout is enforced by a watchdog thread plus a `future`/deref outer fence, so even a
  non-interruptible blocking syscall unblocks the caller.
- **Gated deps**: `clojure.repl.deps/add-libs` is available (`dvergr.sandbox.deps`) but
  passes through a policy gate with a denylist before adding/mirroring libraries.

## Room-scoping & fork isolation

Tool I/O is anchored to the surrounding room's workspace, not the daemon root. Tool
`:cwd` defaults to the **current yggdrasil git system's worktree** for the bound
execution context, so when a room is forked (`:isolation :ctx`), `read_file`/`write_file`/
`run_tests`/`bash` all see the fork's worktree automatically. Likewise `dh` writes go to
the **fork-local** Datahike conn — nothing reaches the parent until the fork is merged.
`spawn_agent` and `propose_change` construct ordinary AgentDefs and call the same
Run-backed `hire!` exposed in SCI. Their tool I/O therefore resolves in the child
world automatically. See `doc/state-model.md` for the full value-semantics picture.

## The `/drive` mount

Beyond the worktree, the shell host can **union-mount** extra filesystems at
absolute sandbox paths via `muschel.fs.mount`. The intended use is a **drive** at
`/drive` — a place for files that aren't source code (channel uploads, generated
artifacts, a content-addressed blob store): agents reach them with the same
`ls`/`cat`/`grep`/redirect idioms as any other path, and the media fns
(`doc/extract-text`, `vision/*`) read through the same FS.

Mounts are supplied by the embedder through the `:mounts` option on
`dvergr.intake.bash/make-host` (or the `set-mounts-fn!` hook, resolved per
workspace). The standalone daemon ships no drive yet; simmis provides a
datahike + CAS-backed one, and a built-in drive is on the roadmap — see
[media.md](media.md#files-in--the-drive-mount).
