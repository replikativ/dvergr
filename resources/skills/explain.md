---
name: explain
description: Ask the agent to explain a file, namespace, or concept in depth
kind: prompt
argument-hint: <file-or-topic>
requires_tools: [clojure_eval]
vetted: true
vetted_at: 2026-06-05
vetted_by: ch_weil
source: dvergr
---
Explain **$ARGUMENTS** in depth.

- If it's a file or namespace, read it first with `clojure_eval` (`(bash/run "cat …")` or `(fs/read "…")`), then explain what it does, how it fits the wider system, and any gotchas.
- If it's a concept, explain it concretely with reference to where it shows up in this codebase.

Be precise and cite `file:line` where useful.
