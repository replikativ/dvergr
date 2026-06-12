---
name: feed-watch
description: Monitor RSS/Atom feeds and surface new items relevant to a topic
provides: [:rss, :feed-watch, :research]
requires_tools: [clojure_eval]
vetted: false
source: openclaw
---

# Feed-watch

Watch a set of RSS/Atom feeds and surface new items that match a
topic. Useful for tracking blogs, news sources, release notes,
podcasts that publish to RSS.

## When to use (trigger phrases)

- "any updates on X this week?"
- "what's new in the <topic> space?"
- "tell me when <blog> posts something about <topic>"

## Tactics

1. The user names the topic and (optionally) a feed list.
2. Persist the watch via `(dvergr.scheduler/every <interval-ms> ...)` so it
   keeps running between conversations.
3. Each tick: `(intake.web/fetch feed-url)`, parse with
   `clojure.xml`, filter items newer than last-seen, score by topic
   match.
4. Post a digest to the room when matches accumulate; otherwise
   stay silent.

## Caveats

- Feed parsing is fragile; many "RSS" endpoints are actually HTML
  or behind a JS wall. Confirm one item parses before persisting
  the watch.
- Per-feed polling intervals should match how often the source
  actually updates — hourly for news, daily for blogs, weekly for
  release notes.
