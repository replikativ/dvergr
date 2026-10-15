# The web dashboard and the JSON API

One http-kit server (`dvergr.web.server`) serves three things over the daemon:

- the **dashboard**, HTML rendered with hiccup and kept live with HTMX polls;
- **room apps**, a room's `app/` directory served as a static site under `/apps/<room>/`;
- the **JSON API** under `/api/v1/`, one route per operation of `dvergr.ops`, the same
  operations MCP serves (doc/mcp.md).

The server is optional. The daemon runs without it, and the TUI, Telegram, MCP and nREPL
are other frontends over the same daemon.

## Starting it

```bash
clojure -M:cli --web                    # daemon + nREPL + TUI + web on 127.0.0.1:17880
clojure -M:cli --no-tui --web           # server box: no TUI
clojure -M:cli --web --web-port 9090    # another port
```

Like Jupyter, it prints a login URL, `[dvergr] web dashboard: http://127.0.0.1:17880/?token=…`:
open it once and the page reloads, logged in, without the token. The token is the daemon's
local token, `$DVERGR_HOME/local-token` (a home from before keeps the value of its
`mcp/http-token`, which therefore now opens the web and TCP too). The login page also takes
the token in a form (a POST, so it stays out of the browser's history; the printed URL does
not). `(:login-url (dvergr.web.server/start! …))` returns the URL to a REPL; the server does
not keep it.

A daemon started any other way (as a library, `daemon/start!`) starts the server when its
config has `:http`:

```clojure
:http {:port 17880 :ip "127.0.0.1"
       :allowed-hosts   ["dvergr.example.com"]          ; names a reverse proxy sends
       :allowed-origins ["https://dvergr.example.com"]} ; origins whose writes are accepted
```

The JSON API needs reitit, which is not a dependency of the `dvergr` library. The `:cli`,
`:dev` and `:test` aliases bring it, and the `:web` alias adds it to any other command
(`clojure -M:local:web`). Without it the daemon logs `daemon/web-server-skipped` and runs
headless. The interactive Swagger UI at `/api/v1/docs` also needs
`metosin/reitit-swagger-ui`, which no alias includes; `/api/v1/swagger.json` works without it.

From a REPL: `(dvergr.web.server/start! daemon :port 17880)` and `(dvergr.web.server/stop!)`.
One server runs per process.

## Security stance

Every request is authenticated through the daemon's `:authenticate` seam (transport `:web`;
`doc/mcp.md` describes the seams and the principal), from the `Authorization: Bearer` header
alone: the local token, or a browser session's id. No credential ever travels in a cookie:
cookies are not scoped by port, so every server on this host (an agent's dev server on
`127.0.0.1:3000`, say) would receive one and could replay it. Where a request comes from
grants nothing, loopback included. A run token (an agent's, `doc/mcp.md`) is for MCP only and
is refused here.

- `GET <path>?token=<token>` (or the login page's POST to `/login`) with a valid token opens
  a session and answers 303 to `<path>#sid=<id>`. A fragment is never sent to a server nor
  in a `Referer`; the page's script (`dashboard/auth-script`) keeps the id in
  `sessionStorage`, which is this origin's alone (this port's), and sends it as the header on
  every request of this origin: htmx's, `fetch`'s, and the navigations and form posts it
  performs itself (a plain navigation could not carry a header). A navigation without the
  header gets the login page, which loads the page asked for with the session it holds.
  Sessions last 12 h, at most 1000 are kept, a new local token ends them all, and `POST
  /logout` ends one. A wrong token is 401;
- a script or curl sends `Authorization: Bearer $(cat $DVERGR_HOME/local-token)`.

A cross-site page cannot send the header, so it needs no CSRF token; the guard below stands
besides. A proxied or hosted deployment configures an authenticator (its own credentials) and
an `:authorize` seam. What a principal may do:

- the **JSON API** and the dashboard's derived actions (`dvergr.web.ops`) run each op for the
  request's principal, authorized like every other surface (403 with `{"error": …}` when
  denied; listings filtered);
- **room apps** need a read of their room. A browser opens one through a capability link the
  room page mints per click (`/api/app-link?room=…` → `/apps/<room>/~<capability>/`, an hour,
  that room only), since a navigation carries no header. An app is agent-written, so every
  response under `/apps/` carries `Content-Security-Policy: sandbox allow-scripts`: its
  scripts run in an opaque origin and their requests say `Origin: null`, which this server
  refuses outside `/apps/` (403);
- the rest of the **dashboard** (hand-written views and actions straight over the daemon:
  room pages, posts, voice, fragments) answers the local principal only, 403 for anyone
  else. `/api/health` answers anyone authenticated.

Every response outside `/apps/` refuses to be framed (`Content-Security-Policy:
frame-ancestors 'none'`, `X-Frame-Options: DENY`), and none is content-sniffed
(`X-Content-Type-Options: nosniff`). `dvergr.web.guard` checks every request before it is
routed:

- **Host**: the request must name this server: a loopback name, the bind address, or an
  `:allowed-hosts` entry. A DNS name rebound to 127.0.0.1 fails here, reads included.
- **Origin**: a state-changing request (anything but GET, HEAD, OPTIONS) that names an origin
  (`Origin`, or `Referer` without it) must name this server's own or an `:allowed-origins`
  entry. A client such as curl sends neither and passes.
- The JSON API's writes must be `application/json` (else 415), which a cross-site page can
  send only after a CORS preflight, and the server grants none.

Requests are limited to 1 MiB. Every route that changes state is a POST.

## The dashboard

`/` (or `/dashboard`) has four sections, each polled: System (`/api/system`), Agents
(`/api/agents`), Rooms (a fork tree, `/api/rooms`, with fork, merge, discard and delete
buttons) and Schedules (`/api/schedules`). `/rooms/<slug>` is a room's page: its messages,
stats and per-agent context bars, a box to post as the user (`POST /rooms/<slug>/post`) and a
microphone that posts a voice message (`POST /rooms/<slug>/voice`, transcribed by
`dvergr.audio.stt`; doc/media.md). `/agents/<id>/config` edits an agent; its form uses the
same field spec (`dvergr.agent.fields`) as the `agent_create`/`agent_update` operations.

The dashboard's room and agent actions call `dvergr.ops` through `dvergr.web.ops`, so they act
exactly as the MCP tools of the same name do. The HTML fragment routes under `/api/` (without
`v1`) are for the dashboard, not a stable interface.

## Room apps

`/apps/<room-slug>/` serves the files under `app/` in the room's workspace (its Geschichte
repository), for example `app/index.html` an agent wrote. A fork has its own workspace, so a
fork's app previews the fork's files; merging the fork publishes them. Serving is static and
GET-only: no code runs on the server, paths cannot leave `app/` (symlinks included), and
dotfiles are not served.

## The JSON API

`dvergr.web.api` derives every route from `dvergr.ops/specification`. An operation
`:room/messages` is the route `/api/v1/room_messages`, the same name as its MCP tool. A read is
a GET with its arguments as query parameters; a write is a POST with a JSON body. The
operation's malli schema coerces the request (a request that does not match is a 400); the
result is the operation's data as JSON.

```bash
curl -s 'http://127.0.0.1:17880/api/v1/room_list'
curl -s 'http://127.0.0.1:17880/api/v1/room_messages?room=general&limit=10'
curl -s -X POST -H 'Content-Type: application/json' \
     -d '{"room":"general","text":"hello"}' http://127.0.0.1:17880/api/v1/room_post
curl -s 'http://127.0.0.1:17880/api/v1/swagger.json'     # the OpenAPI description
```

An operation that fails returns its status (4xx when the request was wrong, as
`dvergr.ops/error-status` maps it) and `{"error": …, "op": …}`; without a daemon the API answers
503. `/api/health` reports the daemon status and the number of online agents.
