# muschel integration: sandboxed bash for LLM agents

`dvergr.tools/shell` today is a thin wrapper over `bash -c command`: it
captures stdout/stderr, applies a timeout, and returns. No parsing, no
permission check, no env shaping — whatever the agent emits, the host
shell runs. That's the right shape for a developer-trusting REPL
session but wrong for the agent-deployment model dvergr is heading
toward, where:

- LLM-emitted shell may compose tools the operator never anticipated
  (`$(curl ...)`, `eval`, env-var-driven flags);
- per-room / per-proposal forks need their own working dir + env
  without leaking back to the parent;
- compliance contexts want an auditable allow/deny boundary, not
  reactive incident response.

[muschel](https://github.com/replikativ/muschel) is the substrate for
that: parse → permit → execute, with `babashka.process` underneath and
an explicit env value threaded through. This doc sketches how dvergr
plugs muschel in without changing the agent-facing tool surface.

## What muschel gives us

```clojure
(require '[muschel.env  :as m-env]
         '[muschel.exec :as m-exec]
         '[muschel.permit :as m-permit]
         '[muschel.session :as m-session])

(let [env  (m-env/empty-env :cwd "/tmp" :PATH "/usr/bin:/bin")
      sess (m-session/atom-session env)
      rules m-permit/default-rules]
  (m-exec/run-and-capture
    env "ls -la | head -5"
    {:session sess
     :permit  {:rulesets [rules]}}))
;; => {:env env' :exit 0 :stdout "..." :stderr "" :session sess
;;     :permit {:decision :allow :per-call [...]}}
```

Three values to thread:

| Value | What it is | dvergr scope |
|---|---|---|
| `env` | functional shell environment (cwd, vars, PATH, last-exit) | per ParticipantContext / per session |
| `session` | mutable holder for the env + bg jobs (time-travel pivot) | per ParticipantContext |
| `rules` | vector of permit rules; eval order is daemon-defaults → user → session | per daemon, with per-session overrides |

## Where it slots into dvergr

### 1. ParticipantContext carries a muschel session

`dvergr.discourse/ParticipantContext` already aggregates the per-agent
runtime view (chat-ctx, budget, derived past-arc signals, generation
handle). Add:

```clojure
;; dvergr/discourse/participant_context.clj
{:shell {:session  (m-session/atom-session
                     (m-env/empty-env :cwd  (or cwd (System/getProperty "user.dir"))
                                       :PATH (System/getenv "PATH")))
         :rulesets [(m-permit/default-rules)
                    (user-rules  user-id)
                    (session-rules session-id)]}}
```

- Forks branch the session (the session itself is an atom — fork-room
  with `:isolation :ctx` already copy-on-writes RuntimeAtoms via the
  overlay backend, so the fork's shell env diverges from parent's on
  first write — exactly the substrate-isolation story propose! tells
  for datahike).
- Merge folds the fork's env back into the parent on `accept!`;
  `reject!` drops it with the fork-ctx.

### 2. New tool: `muschel_shell`

Live alongside the existing `shell` for one release, then replace.

```clojure
(register!
 {:name "muschel_shell"
  :description "Run a shell command in a sandboxed env. Parsed against a
                bash grammar, checked against an allow/deny policy, and
                executed via babashka.process. Stateful: cd, env exports,
                bg jobs persist across calls in the same session."
  :parameters {:type "object"
               :properties {:command {:type "string"
                                      :description "Shell source (single command or pipeline)"}
                            :timeout_ms {:type "integer"
                                         :description "Wall-clock cap; default 30000"}}
               :required ["command"]}
  :execute
  (fn [{:keys [command timeout_ms]} {:keys [pctx]}]
    (let [{:keys [session rulesets]} (:shell pctx)
          permit  {:rulesets rulesets
                   :prompter (make-prompter pctx)}]
      (try
        (let [{:keys [env exit stdout stderr permit]}
              (m-exec/run-and-capture (m-session/-env session)
                                       command
                                       {:session session
                                        :permit  permit
                                        :timeout-ms (or timeout_ms 30000)})]
          (m-session/-swap-env! session (constantly env))
          (cond
            (= 126 exit)
            {:type :error
             :error (str "Permission denied: "
                         (-> permit :per-call
                             (->> (filter #(= :deny (:decision %))))
                             first :reason))}

            (zero? exit)
            {:type :success
             :content (format-output stdout stderr)
             :metadata {:exit-code 0 :stdout stdout :stderr stderr}}

            :else
            {:type :error
             :content (format-output stdout stderr)
             :metadata {:exit-code exit :stdout stdout :stderr stderr}}))
        (catch Throwable t
          {:type :error :error (.getMessage t)}))))})
```

Key wins over the current `shell`:

- **cd / export persist** across calls — the LLM can pipeline real shell sessions.
- **`$(rm -rf /)` inside an `echo`** is caught — permit walks substitutions too.
- **PATH and env are explicit values** — no inheritance surprise from the host shell.
- **Per-call permit decisions** are surfaced — the agent's reply explains *why* a command was denied.

### 3. Prompter wiring: ask the user, not the LLM

When the permit hits `:ask`, muschel calls a `prompter` fn. In dvergr
this routes through the existing discourse channel:

```clojure
(defn- make-prompter [pctx]
  (fn [{:keys [cmd argv reason matched-rule]}]
    (let [reply (d/ask (:room pctx) :user
                       {:content (format "Allow `%s %s`? (reason: %s) [yes/no/always]"
                                          cmd (str/join " " argv) reason)
                        :metadata {:permit-request true}})]
      (case (str/trim (str/lower-case (:content reply)))
        "yes"    {:result :allow-once}
        "always" {:result :allow-always :scope :argv-prefix}
        "no"     {:result :deny-once}
        {:result :deny-once}))))
```

`always`-decisions return a `:new-rules` payload from `m-permit/check`;
dvergr appends them to the session ruleset so subsequent calls don't
re-prompt.

### 4. Default ruleset for dvergr

A `dvergr.shell.policy` ns ships a curated default that pairs with the
LLM-corpus muschel built — auto-allow for read-only inspection, `:ask`
for everything else, hard-deny for the known-dangerous list. Operators
override per agent via config:

```clojure
{:agents {:coder {:shell-policy {:allow ["git status" "git diff *" "ls"
                                          "rg *" "cat *" "head *" "wc *"]
                                  :deny  ["rm -rf *" "curl * | sh"
                                          "sudo *" "shutdown *"]
                                  :ask   :default}}}}
```

These translate to muschel rule maps and feed the daemon-defaults
layer of every spawned agent's pctx.

### 5. Tool registry coexistence

`tools/registry` (still defonce — see audit doc) keeps both:

| Tool name | Notes |
|---|---|
| `shell` | unsandboxed `ProcessBuilder` — kept for back-compat / trusted REPL use |
| `muschel_shell` | sandboxed — default for new agents |

Agent configs opt into one or the other via `:tools #{...}`; the
sidecar already does this via `:tool-preset`.

## Open questions

1. **CLJS** — muschel parser+permit are CLJC, exec is CLJ-only. dvergr's
   shell tool is CLJ-only (the daemon process is JVM). For browser
   agents we'd parse+check on the client and ship only allowed source
   to a server-side runner.
2. **Sessions outliving an agent turn** — muschel sessions cache env
   across calls. When does a pctx's session get torn down? Probably on
   chat-ctx close (already wired through `dvergr.sessions/close-session!`).
3. **Streaming output** — muschel's `run-and-capture` is blocking;
   long-running commands (e.g. `npm install`) would benefit from chunked
   output via the dvergr.bus's `:partial/...` channel. Add `run-streaming`
   later — for the initial cut, just enforce a tight `timeout_ms`.
4. **Resource limits** — muschel doesn't cgroup itself. For untrusted
   agent code, run dvergr inside a container/firejail. muschel is
   mistake-prevention, not adversarial isolation.

## Sequencing

The integration is a 4-step build, each independently shippable:

1. **PoC tool**: `dvergr.tools/muschel-shell` calling `m-exec/run-and-capture`
   with the host's cwd + a stateless session — no pctx integration yet.
2. **Per-pctx session**: thread `{:shell session/rulesets}` through
   ParticipantContext; sessions branch on fork via RuntimeAtom COW.
3. **Prompter**: route `:ask` decisions through `d/ask` to the user
   channel; persist `always`-decisions to the session ruleset.
4. **Default policy**: ship `dvergr.shell.policy/default-rules` and a
   config knob for operator overrides.

Step 1 lands the moment muschel cuts its first Clojars release. The
rest can follow incrementally without breaking the tool surface.

## See also

- [doc/sidecar.md](sidecar.md) — the "forthcoming muschel integration"
  note this doc replaces.
- [doc/programming-model.md](programming-model.md) — the broader
  ParticipantContext / GenerationHandle model.
- [doc/state-audit.md](state-audit.md) — `tools/registry` stays
  process-singleton per the audit; this doc registers a new tool, not
  a per-ctx tool table.
