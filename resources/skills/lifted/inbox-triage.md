---
name: inbox-triage
description: Sort an inbox of unprocessed items into action/research/discard
provides: [:triage, :task-management]
requires_tools: [clojure_eval]
vetted: false
source: openclaw
---

# Inbox triage

Take an unsorted stream of items (emails, room messages, articles
to read, half-finished thoughts) and classify each into one of:

- **Act**: I should do something specific within 48 hours.
- **Research**: there's a question to follow up on; queue for later.
- **Reference**: keep for context but no action needed.
- **Discard**: noise; archive.

## When to use (trigger phrases)

- "help me clear my inbox"
- "what should I be working on?"
- "triage my unread messages"

## Tactics

1. Pull the source items (room history, mail, knowledge base entries).
2. For each item, decide the bucket using these heuristics:
   - Direct ask with a deadline → Act
   - Question that needs investigation → Research
   - Fact worth remembering, no ask → Reference
   - Auto-generated, no human author → Discard
3. Return a markdown table grouped by bucket.
4. For Act items, also propose a concrete first step.

## Caveats

- Resist over-acting. If you can't articulate a first step for an
  Act item, downgrade it to Research.
- Discard liberally; the user can always retrieve via the knowledge
  base if they need it back.
