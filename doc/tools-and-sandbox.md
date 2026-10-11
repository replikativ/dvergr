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
- **Knowledge graph** — `knowledge_search` (fulltext over the room's native Datahike
  `:scriptum` secondary index) and `knowledge_add` (the `[[Entity]]` graph).
- **Tasks** — `task_create`, `task_list`, `task_update` (Datahike-backed `:task/*`).
- **Agent / orchestration** — `spawn_agent` (Run-backed delegation with automatic
  settlement), `propose_change` (the same interpreter with its world retained for
  review), `update_agent_profile` (rewrites an agent's system prompt → actor row),
  `budget` (remaining μ$ / cost estimate).
- **Registered beside `dvergr.tools`** — `llm_call` (a cheap one-shot LLM call,
  `dvergr.tools.llm-call`), `request_dependency` and `request_plan_review` (ask a human
  to approve a library or a plan, `dvergr.tools.approval`).
- **Data sources (intake)** — the `dvergr.intake.*` modules: HN, Lobsters, Bluesky,
  Mastodon, dev.to, web fetch/search, YouTube transcripts, Twitter, GitHub, RSS, Zulip,
  plus company/market intel (SEC EDGAR, Companies House, Finnhub, GLEIF, Wikidata,
  crt.sh, Wayback, LinkedIn, Adzuna jobs, arXiv), and mail (`dvergr.mail`,
  `intake.mail`). They are surfaced **inside the sandbox** as namespaces rather than as
  discrete tools.

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
  fully-qualified or required and aliased as in babashka. The borrowed surfaces carry
  their real names, so the model's training transfers:
  - `babashka.fs` (path-clamped to the workspace; content I/O is `slurp`/`spit`),
    `babashka.http-client` (domain-gated), `babashka.process` (`shell`/`sh`, muschel-backed
    and jailed), `cheshire.core`, `clojure.data.xml` (XXE-safe), `datahike.api` (the real
    API on any conn), `dvergr.codec` (base64, url, html helpers).
  - `dh` — datahike `q`/`pull`/`pull-many`/`entity`/`datoms`/`schema`/`db` against the
    room's DB, and `dh/search` (fulltext over the scriptum secondary index).
  - `dvergr.intake.*` — read-only external SOURCE files in the room repo (cloned from
    the [dvergr-sandbox](https://github.com/replikativ/dvergr-sandbox) stdlib);
    `require` + extend them. e.g. `dvergr.intake.hn`,
    `dvergr.intake.web-fetch` (`fetch-page`), `dvergr.intake.web-search` (`search`),
    `dvergr.intake.github`, `dvergr.intake.youtube` (`get-transcript`), … —
    `(sandbox/overview)` lists the live set.
  - `dvergr.room` — your room: its `*kb*`/`*room*` conns, databases, `fork!`/`merge!`,
    posting to other rooms, `kb-search`.
  - `dvergr.agent` — rosters, environments and `hire!` (a durable Run with a result
    Spin; on the host `dvergr.agent.program/hire!`), `dvergr.tasks` (the task ledger),
    `dvergr.agents` (read-only directory), `dvergr.actors` (register/retire participants,
    assign skills), `dvergr.skills` (find, author, lift, promote), `dvergr.mail` (the room's
    mailbox, when attached).
  - `llm` — cheap one-shot LLM calls (`summarize`/`call`).
  - `doc`, `vision` — media extraction: `doc/extract-text` (PDF/text → string),
    `vision/describe` (image → OCR + description), `vision/extract` (image →
    schema-constrained JSON with per-field verification). Read through the
    chat-ctx's muschel FS, so `/drive` files work; see [media.md](media.md).
  - `dvergr.scheduler` — per-room recurring / one-shot tasks, incl. `:code` schedules
    (see [scheduling.md](scheduling.md)); there is no separate calendar namespace.
  - `git` (structured git for the workspace), `dvergr.shell` (the shell's
    `check`/`builtins`/`allowlist`), `env` (env vars, secrets as placeholders),
    `processes` (list and steer your own long-running work).
  - `spindel.comb` / `spindel.sig` / `spindel.work` / `sync` — reactive primitives;
    `spin`/`await`/`track` when the session is backed by a spindel execution context.
  - `infer` / `dist` — probabilistic inference (foerster).
  - `sandbox` — runtime self-reflection: `(sandbox/overview)` lists every injected
    namespace with purpose + example + fns; `(sandbox/doc 'dh)` zooms into one.

### Safety boundaries

- **Denied**: `eval`, `load-file`, `load-string` (and their `clojure.core/*` forms).
- **No raw file/shell/network.** I/O only goes through the gated namespaces:
  `babashka.fs`/`git` are path-safe (clamped to the workspace); `babashka.process` is the
  muschel jail (workspace rooted at `/`, relative paths only, destructive ops blocked);
  `babashka.http-client` is domain-gated; `env` returns a *placeholder* for any configured
  API key, which the HTTP client substitutes only at the key's bound domain + slot and scrubs from the
  response — so the agent uses keys it never sees (see
  [boundary-secret-injection.md](boundary-secret-injection.md)).
- **Resource limits**: an `interrupt-fn` fires at every fn-body entry to honour
  `Thread.interrupt()` (Esc / watchdog) and cap cumulative thread-allocated memory (default 4 GiB: allocation, garbage included, not retained memory; a runaway backstop, since 256 MiB killed real feed scans). A shell command's output is bounded as it is written (four times `:max-out` bytes kept, the rest counted and dropped), and an environment can bound what an attempt writes (`:world :effects {:quota-bytes n}`, doc/effects.md).
  Timeout is enforced by a watchdog thread plus a `future`/deref outer fence, so even a
  non-interruptible blocking syscall unblocks the caller.
- **Gated deps**: `clojure.repl.deps/add-libs` is available (`dvergr.sandbox.deps`) but
  every request waits for an operator's decision (nothing auto-approves; a `:local/root`
  source is refused, since it stays writable after approval). After a successful load the
  agent may require exactly the namespaces the new jars provide, bound to the loaded
  source; a hard denylist (host eval, REPL servers, raw HTTP, the real XML parser)
  applies on top. Libraries the daemon already ships are requirable only through the
  namespace allowlist.

### Real programs in the shell: the jail (bubblewrap)

The room shell runs muschel builtins over the virtual worktree; it has no network (`curl` and
`wget` are not its builtins) and no external binaries by default. A daemon may give it real
programs, jailed:

```clojure
:shell {:jail {:commands ["python3" "pytest"] :mem-max "2G" :tasks-max 256 :cpu-quota "200%"}}
```

A listed command runs under bubblewrap (muschel's `SandboxedHost`): network unshared, PID
namespace unshared, only `/usr` and `/etc` read-only, the worktree at `/home/agent`, cgroup
limits through `systemd-run --user`. The worktree stays virtual (Geschichte, forked with the
room); each spawn syncs it into a disk mirror under the state root (`jail/`, one per
workspace, changed files only), runs, and syncs back what the program wrote, created or
deleted (`dvergr.intake.jail`). A fork's shell has its own mirror. Without bubblewrap on the
host, the setting is ignored and the commands stay unavailable. The program runs inside the
shell's `:process/run` effect, and with no network cannot reach past the sandbox's HTTP
boundary. One jailed program runs at a time per workspace.

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

Mounts are supplied through the `:mounts` option on `dvergr.intake.bash/make-host`
(or the `set-mounts-fn!` hook, resolved per workspace). The daemon installs the
built-in drive (`dvergr.drive.*`, `dvergr.drive.integration/install!`): every room's
shell sees its own drive at `/drive`, provisioned on first use. File contents are
content-addressed blobs in a konserve store, a filestore under `.dvergr/blobs` unless
`:blob-store` in the config names another (e.g. `{:backend :s3 :bucket … :region …}`).
Other hosts (simmis) call the same `install!` — see
[media.md](media.md#files-in--the-drive-mount).
