# Lifted skills (provenance: external)

Skills under this directory are adapted from external catalogues
(primarily [openclaw](https://github.com/steipete/openclaw)). They
land here in three states:

- `vetted: true` — a maintainer has reviewed the skill end-to-end:
  the prompt body is honest, the listed `requires_tools` /
  `requires_env` are correct, and there's no hidden side effect.
  An online agent with the required tools can use it freely.

- `vetted: false` — copied with minimal adaptation. The frontmatter
  may not match dvergr's tool surface yet; the body may refer to
  CLIs that aren't installed on the daemon's host. Agents will see
  `eligible? = false` and the skill stays inactive until either
  (a) the tool gap is closed and the frontmatter is corrected, or
  (b) the user explicitly opts in for a single conversation.

- `source: openclaw` — these can be removed if upstream changes the
  license; check the openclaw repo before promoting one to vetted.

## How to vet a skill

1. Read the body. Does it accurately describe what the underlying
   capability does, or does it embellish?
2. Check `requires_tools`. Do those names match real dvergr tools?
3. Check `requires_env`. Do those env keys gate real secrets?
4. Try it in an isolated session (`(dvergr.skills/dispatch :<tag>)` with a
   throwaway actor).
5. Set `vetted: true`, `vetted_at: <yyyy-mm-dd>`, `vetted_by: <handle>`.

Unvetted skills are kept around as templates and trigger references —
they help agents recognise when the user wants the capability even
if the implementation isn't wired yet.
