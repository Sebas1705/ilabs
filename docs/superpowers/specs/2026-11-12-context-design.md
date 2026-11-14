# `context` module — design

Status: draft for owner approval · Date: 2026-11-12 · Parent: [2026-11-11-ilabs-design.md](2026-11-11-ilabs-design.md)

## Intent

Keep a conversation inside a token budget, deterministically. Given a list of chat messages and a budget, `context` returns the messages that fit: it drops the oldest turns (sliding window) or replaces them by a summary produced by any `Provider` (incremental summary), and it reports what changed. It is the third iLabs module (after `providers` and `structured`) and plugs into the same `Provider`/`Message` types.

## Goals / non-goals

- Same behavior in Kotlin (KMP) and TypeScript, pinned by shared fixtures in `contracts/context/`, like the previous modules.
- Deterministic only: token estimation, windowing, summary-message construction and the bookkeeping are in the contract. Real model summaries and the summarizer instruction wording are NOT (the contract pins the summary message format and which messages go into the summarizer request).
- Non-goals: exact model tokenizers (the counter is pluggable so callers can bring one), semantic selection (RAG), streaming, truncating inside a message, multi-modal content, Unicode normalization (NFC is out of scope; a lone surrogate counts as one code point).

## Rules (my calls after the builder check, to confirm)

**Token cost (pinned, default counter).** `estimateTokens(text) = ceil(codePoints(text) / 4)`. A message costs `4 + estimateTokens(content)` (fixed overhead for role and framing). Code points, not UTF-16 units. `fits` means `total <= maxTokens`. A `TokenCounter` (`(message) -> tokens`) can be injected; it applies to every cost in this module (fits checks, `tokensBefore`, `tokensAfter`, windowing); fixtures use the default, unit tests cover a custom counter in each language.

**Options.** `maxTokens` must be an integer `>= 1` and `keepRecent` an integer `>= 1` (default 4); otherwise `RangeError` (TS) / `IllegalArgumentException` via `require` (Kotlin). An empty message list always fits.

**Summary message.** A `system` message whose content starts with the exact prefix `"Summary of the earlier conversation:\n"` is a *summary message*. The module never creates one that does not start with it. A reply is used as is (no trimming); an empty reply gives a summary that is just the prefix.

**`slidingWindow(messages, { maxTokens })`** (pure). Let `S` be all `system` messages (summary messages included) and `N` the non-system messages.
1. If the total fits, return the input unchanged (`droppedCount 0`).
2. System messages are never dropped. If `N` is empty the call fails with `budget_too_small` (`required` = cost of `S`).
3. Let `L` be the last message of `N` (not the last message overall). If `cost(S) + cost(L) > maxTokens` it fails with `budget_too_small`, `required = cost(S) + cost(L)`.
4. Otherwise keep the longest contiguous suffix of `N`, scanning from the end and stopping at the first message that does not fit (no skipping). The output is the input filtered to `S` plus the kept suffix, so the original order is preserved and system messages are not moved.
Result: `{ messages, droppedCount, tokensBefore, tokensAfter }` (`droppedCount` = input messages missing from the output).

**`summarizeContext(provider, messages, { maxTokens, keepRecent })`** (async).
1. If the total fits, return the input unchanged without calling the provider (`summarized false`, `summaryCalls 0`).
2. `N` = non-system messages; the last `min(keepRecent, |N|)` of `N` are *protected*; the others are *older* (`O`). Plain system messages stay where they are. `E` = all summary messages in the input, wherever they are.
3. If `O` is empty, nothing can be summarized: no provider call, apply `slidingWindow` to the input (it may drop protected messages or fail with `budget_too_small`). Existing summaries in `E` alone do not trigger a call.
4. Otherwise make exactly ONE provider call, whatever the size of what is sent (no cap; provider errors, including a script that is exhausted → `unavailable`, propagate unchanged and nothing else is attempted). The request has exactly 2 messages and no other fields: a non-empty `system` instruction (wording not pinned) and a `user` transcript that contains the content of every message in `O ∪ E`, in input order (wording and role labels of the transcript are not pinned; fixtures assert the contents appear as ordered, non-overlapping substrings). Plain system messages are never sent.
5. Build the result: remove `O ∪ E` from the input and insert ONE new summary message `"Summary of the earlier conversation:\n" + reply.text` at the position of the earliest removed message. So there is never more than one summary message in the result.
6. If the result still does not fit, apply `slidingWindow` to it (the new summary counts as a system message and is never dropped; it can drop protected messages; it can fail with `budget_too_small`, after the provider call was made).
Result: `{ messages, summarized, summaryCalls (0 or 1), droppedCount, tokensBefore, tokensAfter }` where `droppedCount` counts input messages that were replaced or dropped (the new summary is not counted), `tokensBefore` is measured on the input and `tokensAfter` on the final output.

**Fixtures.** `contracts/context/<name>.json`: `{ name, setup: {kind: "window"|"summarize", maxTokens, keepRecent?}, messages, provider?: {id, script}, expect: {result: {messages, droppedCount, tokensBefore, tokensAfter, summarized?, summaryCalls?} | {error: {kind, required?}}, calls?, transcriptContains?} }`; `keepRecent` absent means 4; `provider` reuses the providers mock script format (`{response}` or `{error}` entries; `script: []` for exhaustion); `calls` is the number of provider calls; error `kind` is `budget_too_small` or a provider kind; `required` is compared when present. About 40 fixtures: estimator edge cases (empty, emoji, exact multiples of 4), window edge cases (fits, only system, no non-system message, last message too big, interleaved system messages, budget exactly equal, suffix stops at the first non-fitting message), summarize cases (fits → no call, one summary, incremental second summary, several existing summaries merged, summary inside the protected window, interleaved system messages, still too big after summary → window, `budget_too_small` after the call, `keepRecent` larger than the conversation, empty reply, provider error and exhausted script propagate).

## Demos

A web page and an Android screen using the live implementations with the fixture conversations as sample data. Window fixtures: a budget slider (min 1, max = total tokens of the conversation, step 1) re-runs `slidingWindow` in real time and shows each message as kept or dropped with a token bar and the totals; below the required budget it shows a `budget too small (needs N)` state instead of clamping. Summarize fixtures: the budget is fixed to the fixture's `maxTokens` (the scripted provider cannot be replayed), the slider is disabled, and the page shows the summarizer request, the resulting summary message and the totals. es + en, light/dark, same shell as the other modules; the new page is one entry in the web route registry and the Android module home.

## Groundwork first (deferred from earlier reviews)

One small task before the module: on Android, give the structured-specific package-level names (`FixtureGroup`, `ProviderFailure`) a home so the third module cannot clash (nest or prefix them), add `plurals` for the "1 calls" string in the providers screen and make the code blocks selectable.

## Approaches considered

- **A. Hand-implemented deterministic estimator + two small functions (chosen):** identical in both languages with no tokenizer dependency; the counter is pluggable for real use. Less accurate than a model tokenizer.
- **B. Real tokenizers per language (tiktoken-like):** accurate but model-specific, heavy and different per language, which breaks the shared contract.
- **C. Sliding window only, no summary:** simpler, but misses the "optimized small context" case the owner cares about.

## Left to the builder

File layout, error type names (`BudgetTooSmall` carrying `required`), the exact wording of the summarizer instruction, UI copy, and how the slider and token bars are rendered.
