---
name: knowledge-intake
description: Pattern for reading from and writing to the shared knowledge base
requires_tools:
  - knowledge_search
  - knowledge_add
---

## Knowledge Base Usage

### Reading (before work)

Search prior knowledge before starting any research task to avoid duplicates
and to understand existing context:

```
knowledge_search {:query "relevant topic" :limit 5}
```

Use the results to:
- Skip topics already well-documented in the past 72 hours
- Identify knowledge gaps to fill
- Build on prior research rather than repeating it

### Writing (after work)

Store every significant finding before writing any text summary. Always include
a sweep/run summary even when nothing notable was found.

Required fields:
- `:title` — concise, searchable (required)
- `:summary` — 1-3 sentences

Optional intake fields:
- `:source` — where it came from: "hn", "reddit", "lobsters", "mail", "internal"
- `:url` — direct link if available
- `:relevance` — 1 (noise) to 5 (direct product mention / commercial inquiry)

```
knowledge_add {:title   "Topic — brief descriptor"
               :source  "hn"
               :url     "https://..."
               :summary "What was found and why it matters"
               :relevance 4}
```

### Deduplication rule

If `knowledge_search` returns an entry with the same title or URL from less
than 72 hours ago, skip storing it again unless there is meaningfully new
information to add.
