---
name: summarize
description: Summarize a URL, file, or transcript into a tight digest
provides: [:summarization, :transcription]
requires_tools: [clojure_eval]
vetted: false
source: openclaw
---

# Summarize

Produce a concise digest of long content: an article URL, a YouTube
video transcript, a long-form chat log, or a local file.

## When to use (trigger phrases)

- "what is this article about?"
- "tl;dr this for me"
- "summarize the meeting transcript"
- "transcribe this video"

## Tactics

1. If the input is a URL, fetch via `(intake.web/fetch url)`.
2. If it's a YouTube link, prefer `(intake.youtube/transcript url)`.
3. For a long transcript, call `(llm/summarize text)` with a focused
   prompt: "summarize for an engineer who wants the key claims and any
   counter-arguments, no filler".
4. If the source is over 20k tokens, summarize in chunks and then
   summarize the summaries.

## Output shape

Return three sections:
- **TL;DR**: 1-2 sentences.
- **Key points**: 3-7 bullets.
- **Pull quotes**: 1-3 direct quotes that carry the argument.

Avoid hallucination — if a section is empty, write "none in source".

## Caveats

- For paywalled URLs, the fetch may return a stub. Surface that
  explicitly to the user, don't summarize the paywall message.
- For YouTube videos without a transcript, fall back to summarizing
  the title + description + top comments.
