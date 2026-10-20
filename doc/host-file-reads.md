# Embedding immutable room-file reads

This is an optional trusted HOST embedding contract. It does not change
authentication, op authorization, token ceilings, agent SCI tools, or selected
sandbox workspaces. Nothing installs it automatically.

An MCP host may configure:

```clojure
{:execution-ctx application-context
 :config {:auth {:authorize existing-authorizer}
          :room-files {:resolve-read fixed-host-resolver}}}
```

`fixed-host-resolver` receives the explicit authenticated principal and a closed
request map containing `:operation` (`:list`, `:read` or `:resource`), the ORIGINAL
`:room` handle, and applicable `:path`, `:offset`, `:length`, and
`:expected-target-stamp`. The host must perform its own current canonical
identity/permission/default-ownership checks before hydration or opening stores.
It returns ONLY:

```clojure
{:db one-immutable-datahike-db
 :target-stamp "opaque-current-target-identity"}
```

The stamp is nonblank, control-character-free text of at most 256 characters.
It is a PRECONDITION, never a target selector or authorization grant. No Room,
native System, connection, callback, credential or host descriptor is returned
over the protocol. Do not retain this binding or an allow decision in a session.

`dvergr.ops/room-file-read` is an INTERNAL already-authorized gateway: ordinary
ops retain their `invoke` gate, and custom MCP resources retain their separate
`room/read` gate. A host's authorizer must support its cold canonical-room
identity mapping without relying on gateway hydration; this API does not bypass
an authorization refusal. Configured reads must run off the physical FRP drain.

Both file tools and BOTH `room://ROOM/files` resource paths use this gateway.
The configured resolver runs exactly once. A file-resource directory fallback
uses the SAME captured DB, without a second lookup or `room_files` capability.
Resource file text and binary bytes stay unchanged; configured resource contents
carry the stamp in `_meta["dvergr/target-stamp"]`. Tool/listing data carries
`target-stamp`. The current resource URI is not a historical snapshot URI.

A configured hook that is nil, malformed, throws, or returns an invalid binding
fails closed. It NEVER falls back to selected files. Callback and projection
failures use fixed safe public messages; arbitrary exception messages/ex-data
and native handles are not exposed. Unsupported expected-target-stamp on an
unconfigured host is refused, not silently ignored.

The new `rooms.files/list-dir-in` and `read-file-in` primitives project ONE flat
native repository DB, including explicit directories, without native mount or
workspace selection. They are storage helpers, not authorization endpoints or
SCI exports. They bound metadata indices at 20000 entries each, files at 8 MiB,
tool chunks at the existing 24576-byte cap and whole resources at 4 MiB.
Listing pages retain the existing entry/character budgets; an individual entry
larger than the character budget is refused rather than emitted oversized.
Strict UTF-8 boundaries, binary encoding, sensitive-path and symlink rules remain.
Metadata is checked before body decoding; missing/collected bytes fail closed.
An immutable DB is not a GC retention pin.

Without this hook, the existing selected `list-dir`/`read-file` MountFS wrappers
remain unchanged, including nested mounts and explicit directories. These new
flat projectors are NOT generic MountFS replacements. Existing unstamped calls
keep their selected semantics; no writer or new credential mode is added.

The target stamp deliberately does not promise stable file contents or directory
pages across separate calls. Clients reading changing files in chunks must also
compare content IDs (or use a separately designed content precondition). This
contract does not add retained exports, history, automatic file-resource
notifications, authoring CAS, agent tools, or a new filesystem capability.
