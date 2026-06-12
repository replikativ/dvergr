# Boundary secret injection — credential handling in the sandbox

**Goal: an agent USES an API key without ever SEEING it.** Intakes that need a
credential (web search, GitHub, Zulip, …) ask for it the normal way — `(env/get
"BRAVE_API_KEY")` — but in the sandbox that returns an opaque *placeholder*
(`@@secret:BRAVE_API_KEY@@`). The real value is substituted in only at HTTP
egress, bound to a specific destination, and scrubbed back out of the response.
Key *exfiltration* is made structurally impossible; the agent can still *use* the
key against its bound API (the "use-not-read" residual, see below).

This works because dvergr already owns the **only** egress function the agent can
reach (`dvergr.sandbox.ns.io/do-request`) plus a domain policy + SSRF guard — so
substitution happens **in-process, just before the bytes leave the trusted fn**,
with no TLS-MITM proxy, CA, or sidecar.

## How it works

The host holds a secret registry (`build-secret-registry`, closed over in
`add-env-ns!`/`add-http-ns!` — **never an SCI value**). Each entry:

```clojure
{"BRAVE_API_KEY"
 {:value             "real-key"            ; resolved host-side (see sources)
  :placeholder       "@@secret:BRAVE_API_KEY@@"
  :allowed-domains   #{"https://api.search.brave.com"}
  :allowed-locations #{:header :query}     ; :body is default-deny
  :header-names      #{"X-Subscription-Token"}}}
```

1. **`env/get` returns the placeholder**, not the value. (Non-secret `:sandbox-env`
   entries — e.g. an endpoint URL — still return their real string.)
2. **Substitution in `do-request`**, after audit → domain-policy → SSRF-guard,
   before the HTTP call: for each placeholder found in the request, resolve its
   secret, assert the URL is in `:allowed-domains` (origin-anchored) and the slot
   is in `:allowed-locations` (+ `:header-names`), then replace it with `:value`
   in that slot only. **Any assertion failure throws — never strip-and-send.**
3. **Response scrub:** any known `:value` reflected in the response body/headers is
   re-masked to its placeholder before returning to SCI. Because the agent never
   holds plaintext, a single scrub at this one egress point is complete.
4. **Audit:** placeholder *names* (never values) + slot + reject reasons are logged
   at every boundary.

## Configuration

Add a `:secrets` vector to `config.local.edn` (see `config.example.edn` for the
full commented set). Each entry binds a credential to its destination + slot. The
**value source** is one of:

| Source | Meaning |
|---|---|
| `:env "VAR"` | host environment variable |
| `:value "…"` | literal |
| `:config-path [:k :path]` | reuse a value already in the config (e.g. `[:github :token]`) |
| `:basic-auth-config-paths [[user-path] [pass-path]]` | HTTP Basic — pre-encodes `base64(user:pass)` so the placeholder stands for the whole `Authorization` credential |

```clojure
:secrets [{:name "BRAVE_API_KEY" :env "BRAVE_API_KEY"
           :allowed-domains ["https://api.search.brave.com"]
           :allowed-locations [:header :query] :header-names ["X-Subscription-Token"]}
          {:name "GITHUB_TOKEN" :config-path [:github :token]
           :allowed-domains ["https://api.github.com"]
           :allowed-locations [:header] :header-names ["Authorization"]}
          {:name "ZULIP_AUTH" :basic-auth-config-paths [[:zulip :email] [:zulip :api-key]]
           :allowed-domains ["https://your-org.zulipchat.com"]
           :allowed-locations [:header] :header-names ["Authorization"]}]

;; non-secret endpoints/identifiers the sandbox needs verbatim:
:sandbox-env {"ZULIP_SITE" "https://your-org.zulipchat.com"}
```

An entry whose source resolves to nothing (unset env var, missing config) is
silently skipped — the intake simply won't run. `:basic-auth` intakes read a single
pre-encoded credential (`(str "Basic " (env/get "ZULIP_AUTH"))`) rather than
base64-ing the key themselves, which would defeat literal-placeholder substitution.

Implementation: `dvergr.sandbox.ns.io` (registry + substitute + scrub),
`dvergr.substrate.config/secret-specs` (config resolution),
`dvergr.sandbox/setup-agent-namespaces!` (wiring). Tests:
`test/dvergr/sandbox/secret_injection_test.clj`.

## Residual: use-not-read

The agent can still *use* the key against the bound API — cost abuse, exfiltrating
data *through* an allowed call, abusing write scopes. Mitigations (least-privilege
keys at issuance are the first line) and a hardening pass left for later: per-secret
RPM/daily **quota** enforced at substitution, an outbound **tripwire** alarming on a
raw value headed to a non-bound host, `:allowed-methods` per destination, disabling
HTTP auto-redirect for secret-bearing requests (or re-running domain+SSRF per hop),
and human approval for write/destructive bound calls.
