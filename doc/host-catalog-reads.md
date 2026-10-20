# Configured immutable catalog inspection

Hosts using the optional `:room-files :resolve-read` gateway also use that SAME
resolver for room-authored `catalog_list` rows. No second resolver, caller callback,
new tool capability, or sandbox selection mutation is introduced. Normal catalog
authorization runs first. The embedding must still perform current canonical
room-read/default ownership checks before hydration; a cold-room-capable authorizer
is required. No Simmis routing is automatically activated.

The callback receives the explicit authenticated principal and fixed closed request
`{:operation :list :room ORIGINAL_HANDLE :path "/workflows"}`. Exactly one returned
immutable DB supplies every name, definition, checker, fixture and supporting file.
Only folders containing `workflow.edn` are described. The checker is NEVER executed
or calibrated by listing. Portable bundle IDs and trusted promotion-tier lookup
remain unchanged; promotion metadata is NOT pinned by the repository DB snapshot.

Configured inspection reads the WHOLE `/workflows` subtree or refuses: at most500
files and2097152 actual UTF-8 bytes, independently bounded20000 worktree metadata
entries,4096-character canonical paths, regular files and exact content metadata.
All selected metadata is validated before the first body read. Missing/collected
content, invalid UTF-8, forbidden paths, symlinks and inconsistent sizes fail closed.
An immutable DB is not a GC or authorization lease.

Returned authored metadata is bounded to20000 representation nodes, depth32 and
2097152 encoded JSON bytes. Oversized params/descriptions fail the entire configured
projection; they are not silently truncated. Structurally invalid workflow bundles
produce a fixed inert problems marker, including nil/non-map/invalid definitions.
Storage, resolver and trusted promotion failures remain safe read errors, not bogus
malformed-workflow rows. Arbitrary callback exception messages/data never escape.

Authored rows include `target-stamp` as target provenance, not content revision or
authority. Their `room` and `id` prefix are the ORIGINAL supplied UUID/slug DISPLAY
reference. They are NOT promised launch handles: generic `catalog_start/check/export`
have not migrated to this owned source, and may reject canonical UUIDs or still
access selected source. Do not present these inspection rows as safe runnable choices
until source-bound admission exists. The same source has the same portable bundle ID
regardless of UUID versus slug display prefix or target stamp.

Without a room argument, static catalog inspection does not consult any room hook,
even a malformed one. Without a configured hook, legacy selected MountFS behavior,
limits, slug-based IDs and generic agent behavior remain unchanged. This extension
does not change read/work credential ceilings, expose workflow execution or writing,
or add export, source CAS, scheduling, promotion or retained-run migrations.
