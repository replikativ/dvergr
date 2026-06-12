---
name: hn-research
description: Search Hacker News for discussions and signals
provides: [:research, :hn-research, :community-signals]
requires_tools:
  - hn_search
vetted: true
vetted_at: 2026-05-26
vetted_by: ch_weil
source: dvergr
---

## Hacker News Research

Use `hn_search` to find relevant discussions on Hacker News.

### Parameters

```
hn_search {:query "search terms" :days_back 7}
```

- `:query` — keywords to search (use product names, technologies, categories)
- `:days_back` — how far back to look (default 7; use 3 for recent buzz)

### Efficiency tips

- Combine related terms in one query: `"datalog immutable database"` beats three separate searches
- Start narrow (exact product name), then broaden (category terms)
- 3 searches covers most signal; 5+ rarely adds value

### Relevance signals on HN

High-value: threads with 10+ comments, "Ask HN" posts, comparisons mentioning our products
Medium-value: "Show HN" posts in adjacent space, blog posts from known engineers
Low-value: generic listicles, news reposts with few comments
