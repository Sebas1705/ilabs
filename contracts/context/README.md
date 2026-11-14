# context contract

Shared contract for the `context` module (Kotlin and TypeScript): keep a conversation inside a token budget, deterministically, by dropping the oldest turns (`slidingWindow`) or replacing them by a summary produced by any `providers` `Provider` (`summarizeContext`). Both implementations must pass every `*.json` fixture in this folder.

Messages and providers are the `providers` types: a message is `{ "role": "system"|"user"|"assistant", "content": string }` (see [../providers/README.md](../providers/README.md)).

## Rules

**Token cost (default counter).** `estimateTokens(text) = ceil(codePoints(text) / 4)`. A message costs `4 + estimateTokens(content)` (fixed overhead for role and framing). Code points, not UTF-16 units; a lone surrogate counts as one code point. `fits` means `total <= maxTokens`. A `TokenCounter` (`(message) -> tokens`) can be injected; it applies to every cost in this module (fits checks, `tokensBefore`, `tokensAfter`, windowing). Fixtures use the default counter; unit tests cover a custom counter in each language.

**Options.** `maxTokens` must be an integer `>= 1` and `keepRecent` an integer `>= 1` (default 4); otherwise `RangeError` (TypeScript) / `IllegalArgumentException` via `require` (Kotlin). Not pinned by fixtures (every fixture uses valid options), only by unit tests. An empty message list always fits.

**Summary message.** A `system` message whose content starts with the exact prefix `"Summary of the earlier conversation:\n"` (37 code points, ending with LF) is a *summary message*. The module never creates one that does not start with it. A reply is used as is (no trimming); an empty reply gives a summary that is just the prefix. A system message that is not a summary message is a *plain system message*.

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
4. Otherwise make exactly ONE provider call, whatever the size of what is sent (no cap; provider errors, including a script that is exhausted → `unavailable`, propagate unchanged and nothing else is attempted). The request has exactly 2 messages and no other fields: a non-empty `system` instruction (wording not pinned) and a `user` transcript that contains the content of every message in `O ∪ E`, in input order (wording and role labels of the transcript are not pinned). Plain system messages are never sent.
5. Build the result: remove `O ∪ E` from the input and insert ONE new summary message `"Summary of the earlier conversation:\n" + reply.text` at the position of the earliest removed message. So there is never more than one summary message in the result.
6. If the result still does not fit, apply `slidingWindow` to it (the new summary counts as a system message and is never dropped; it can drop protected messages; it can fail with `budget_too_small`, after the provider call was made).

Result: `{ messages, summarized, summaryCalls (0 or 1), droppedCount, tokensBefore, tokensAfter }` where `droppedCount` counts input messages that were replaced or dropped (the new summary is not counted), `tokensBefore` is measured on the input and `tokensAfter` on the final output.

## Fixture format

`contracts/context/<name>.json`:

```json
{
  "name": "summarize-plain-system-never-sent",
  "setup": {"kind": "summarize", "maxTokens": 50, "keepRecent": 2},
  "messages": [
    {"role": "system", "content": "Code word: kiwi."},
    {"role": "user", "content": "User turn 1"},
    {"role": "system", "content": "Mention the moon."},
    {"role": "assistant", "content": "Bot turn 1"},
    {"role": "user", "content": "User turn 2"},
    {"role": "assistant", "content": "Bot turn 2"},
    {"role": "user", "content": "User turn 3"}
  ],
  "provider": {"id": "a", "script": [{"response": "They said hello."}]},
  "expect": {
    "result": {
      "messages": [
        {"role": "system", "content": "Code word: kiwi."},
        {"role": "system", "content": "Summary of the earlier conversation:\nThey said hello."},
        {"role": "system", "content": "Mention the moon."},
        {"role": "assistant", "content": "Bot turn 2"},
        {"role": "user", "content": "User turn 3"}
      ],
      "droppedCount": 3, "tokensBefore": 52, "tokensAfter": 49, "summarized": true, "summaryCalls": 1
    },
    "calls": 1,
    "transcriptContains": ["User turn 1", "Bot turn 1", "User turn 2"],
    "requestExcludes": ["Code word: kiwi.", "Mention the moon."]
  }
}
```

(This is the real `summarize-plain-system-never-sent.json`, only reformatted with one message per line.)

- `setup.kind` is `window` (runs `slidingWindow`; the fixture has no `provider` and no `calls`) or `summarize` (runs `summarizeContext`; the fixture always has a `provider` and `expect.calls`). `setup.keepRecent` absent means 4; it is ignored by `window`.
- `provider` uses the `providers` mock format: `MockProvider(id, script)`; call N (1-based) consumes `script[N-1]`; an entry is `{"response": "<text>"}` or `{"error": {"kind": "..."}}`; once the script is exhausted every call fails with `unavailable` (`script: []` fails on the first call).
- `expect.result` is the result object or `{"error": {"kind": "budget_too_small" | <provider kind>, "required": <int>?}}`. A `window` result has `messages`, `droppedCount`, `tokensBefore`, `tokensAfter`; a `summarize` result also has `summarized` and `summaryCalls`. `required` appears only for `budget_too_small`.
- `expect.calls` is the number of `complete()` invocations on the provider, including one that fails (provider error or exhausted script).
- `expect.transcriptContains` (optional, only when a call is made): strings that appear in the `user` message of the summarizer request as ordered, non-overlapping substrings (search each one starting right after the end of the previous match).
- `expect.requestExcludes` (optional, only when a call is made): strings that appear in NEITHER message of the summarizer request (instruction or transcript). Fixtures only list distinctive texts that a sensible instruction would not contain.

## Comparison and harness

- `messages` by deep equality, in order (role and content exact). Numbers and booleans exact. Error `kind` always; `required` when the expected error has it.
- `calls` exact.
- The harness wraps the provider to record every request it receives. Whenever a call was made (also when it failed), the recorded summarizer request must have exactly 2 messages, `system` then `user`, both with non-empty content, and no other fields (no `maxTokens`). Then `transcriptContains` and `requestExcludes` are checked against it. Both runners check this for every fixture.
- `runFixture` returns `{ result | error, calls, summarizerRequest }` (`summarizerRequest` is `null` when no call was made).

## Malformed fixtures

`runFixture` fails with the explicit error `invalid fixture: <reason>` (an `Error` in TypeScript, an `IllegalStateException` in Kotlin) when:

- `setup.kind` is neither `window` nor `summarize`: `unknown setup kind: <kind>`
- a `summarize` setup has no `provider`: `summarize setup needs a provider`
- a script entry has neither `response` nor `error`: `script entry <n> of provider <id> has neither response nor error` (`n` is 1-based)

## Clarifications

These make the Rules exact where two implementations could otherwise differ. They do not change them.

1. **Summary detection is exact.** The role must be `system` and the content must start with the 37 code points of the prefix exactly (case-sensitive, including the final LF). A `system` message starting with `"Summary of the earlier conversation: "` (no LF) is a plain system message: it stays in place and is never sent. A `user` or `assistant` message starting with the full prefix is an ordinary non-system message (it can be protected, older or dropped). Pinned by `summarize-prefix-must-be-exact` and `summarize-prefix-on-user-not-summary`.
2. **The transcript carries full contents.** "The content of every message in `O ∪ E`" is the whole content string, so an existing summary is sent with its prefix (the transcript does not strip it). Pinned by `summarize-incremental`, `summarize-several-summaries-merged`, `summarize-summary-in-recent-window` and `summarize-transcript-order`.
3. **Only `O ∪ E` is sent.** Protected messages and plain system messages appear in neither message of the summarizer request (they stay in the result, so sending them would duplicate them). Pinned by `summarize-protected-never-sent` and `summarize-plain-system-never-sent` (field `requestExcludes`).
4. **The transcript is never empty.** When every sent content is empty the `user` message still has non-empty content (role labels or framing), as the harness requires. Pinned by `summarize-empty-contents`.
5. **`summarized` and `summaryCalls`.** In a returned result, `summarized` is `true` exactly when the provider call was made and the new summary was inserted, and then `summaryCalls` is `1`; otherwise `false` and `0`, also when step 3 dropped messages through `slidingWindow`. Pinned by `summarize-nothing-older-window-only`, `summarize-keeprecent-larger-than-list` and `summarize-summaries-alone-no-call`.
6. **`droppedCount` counts positions, not values.** Identical messages are distinct input messages; dropping one of three identical messages is `droppedCount 1`. Pinned by `window-duplicate-messages-counted`.
7. **Code points.** A high surrogate followed by a low surrogate is one code point; any other surrogate (lone high, lone low, or a low followed by a high) is one code point on its own and never swallows its neighbour. An empty content costs `4`. Pinned by `window-lone-surrogate-cost` and `window-empty-content`.

## Costs used by the fixtures

| Content | Code points | Cost |
|---|---|---|
| `""` | 0 | 4 |
| `"Okay"` | 4 | 5 |
| `"Sure!"`, `"Thanks!!"`, `"😀😁😂🤣😃"` | 5, 8, 5 | 6 |
| `"Be brief."`, `"Use English."`, `"Stay polite."`, `"User turn N"`, `"Bot turn N"`, `"Good day!"` | 9 to 12 | 7 |
| `"Keep this one."`, `"Noted, will do."`, `"Code word: kiwi."` | 14, 15, 16 | 8 |
| `"Mention the moon."` | 17 | 9 |
| `"Describe Rome in detail."` | 24 | 10 |
| `"Rome was founded in 753 BC."` | 27 | 11 |
| `"You are a helpful travel guide."` | 31 | 12 |
| prefix + `""` or `"Hi."` | 37, 40 | 14 |
| prefix + `"Part one."`, `"Old notes."`, `"  Hello.  \n"`; `"Summary of the earlier conversation: none yet."` | 46, 47, 48; 46 | 16 |
| prefix + `"Notes merged."` | 50 | 17 |
| prefix + `"They said hello."`, `"Parts one and two."` | 53, 55 | 18 |
| prefix + `"They planned a trip."` | 57 | 19 |

## Fixtures

48 fixtures. "U*n*" / "B*n*" are `"User turn n"` / `"Bot turn n"`, "Sum(x)" is the summary message with body `x`, budgets are `maxTokens` and `k` is `keepRecent`.

| Fixture | Rule it pins | Expected |
|---|---|---|
| `window-empty-list` | an empty list always fits | `[]`, dropped 0, 0 → 0 |
| `window-emoji-cost` | 5 astral emoji = 5 code points → cost 6 (UTF-16 would give 7) | keeps the emoji only, dropped 1, 13 → 6 |
| `window-cost-multiple-of-four` | 4 code points → 1 token, 5 → 2, 8 → 2, 9 → 3 | unchanged, 24 → 24 |
| `window-empty-content` | clarification 7: empty content costs 4 | unchanged, 9 → 9 |
| `window-lone-surrogate-cost` | clarification 7: low+high surrogates are two code points | unchanged, 6 → 6 |
| `window-fits-unchanged` | total fits → input unchanged | unchanged, 21 → 21 |
| `window-only-system-fits` | only system messages that fit → unchanged | unchanged, 14 → 14 |
| `window-only-system-too-big` | no non-system message and no fit → `required` = cost(S) | `budget_too_small`, required 14 |
| `window-last-too-big` | `required = cost(S) + cost(L)` | `budget_too_small`, required 17 |
| `window-single-message-too-big` | no system message: `required = cost(L)` | `budget_too_small`, required 10 |
| `window-trailing-system` | `L` is the last non-system message, not the trailing system message | `budget_too_small`, required 18 |
| `window-last-fits-exactly` | `cost(S) + cost(L) == maxTokens` keeps exactly S and L | `[S1, U2]`, dropped 2, 28 → 14 |
| `window-budget-equal-total` | `fits` is `<=` | unchanged, 28 → 28 |
| `window-drops-oldest` | the oldest non-system turns go first | `[S1, U2, B2, U3]`, dropped 2, 42 → 28 |
| `window-interleaved-system` | system messages keep their original positions | `[S1, U2, "Use English.", B2, U3]`, dropped 2, 49 → 35 |
| `window-stops-at-first-nonfitting` | contiguous suffix, no skipping (U1 would fit but is dropped) | `[U2]`, dropped 2, 25 → 7 |
| `window-system-never-dropped` | an old, large system message is never dropped | `[guide, U2]`, dropped 2, 33 → 19 |
| `window-summary-message-is-system` | a summary message counts as system and is never dropped | `[Sum, U2]`, dropped 2, 40 → 26 |
| `window-duplicate-messages-counted` | clarification 6: dropped counted by position | two `"Okay"`, dropped 1, 15 → 10 |
| `summarize-fits-no-call` | fits → unchanged, no call | unchanged, calls 0 |
| `summarize-empty-list` | empty list fits, no call (`script: []` never consumed) | `[]`, calls 0 |
| `summarize-fits-keeps-summaries` | fits → several summaries are left alone | unchanged, 46 → 46, calls 0 |
| `summarize-one-summary` | O replaced by one summary at O's first position | `[S1, Sum, U3, B3]`, dropped 4, 49 → 39, calls 1 |
| `summarize-incremental` | an existing summary is re-summarized with O (sent with its prefix) | `[S1, Sum, U4, B4]`, dropped 3, 53 → 40, calls 1 |
| `summarize-several-summaries-merged` | every existing summary is merged into one | `[Sum, U2, B2]`, dropped 4, 60 → 32, calls 1 |
| `summarize-summary-in-recent-window` | a summary between protected messages is still merged | `[S1, Sum, B2, U3]`, dropped 4, 58 → 38, calls 1 |
| `summarize-interleaved-system` | plain system messages stay where they are | `[S1, Sum, "Use English.", B2, "Stay polite.", U3]`, dropped 3, 56 → 53, calls 1 |
| `summarize-nothing-older-window-only` | `keepRecent` equal to the number of non-system messages → window only, no call | `[S1, B1, U2]`, dropped 1, 28 → 21, calls 0 |
| `summarize-nothing-older-budget-too-small` | window-only path can fail | `budget_too_small`, required 17, calls 0 |
| `summarize-summaries-alone-no-call` | existing summaries alone do not trigger a call | `[S1, Sum, B1]`, dropped 1, 39 → 32, calls 0 |
| `summarize-still-too-big-window` | after the summary the window drops protected messages | `[S1, Sum, B3]`, dropped 5, 49 → 32, calls 1 |
| `summarize-budget-too-small-after-call` | `budget_too_small` after the call was made | `budget_too_small`, required 32, calls 1 |
| `summarize-call-when-protected-over-budget` | exactly one call even when system + protected (35) alone exceed the budget; the window then drops protected messages | `[S1, Sum, B3]`, dropped 5, 49 → 32, calls 1 |
| `summarize-call-then-budget-too-small-protected-heavy` | protected alone (28) exceed the budget: the call is still made, then the window fails | `budget_too_small`, required 32, calls 1 |
| `summarize-summary-before-plain-system` | an existing summary is the earliest removed message, before a plain system message and the first older message | `[Sum, "Use English.", U2, B2]`, dropped 3, 51 → 39, calls 1 |
| `summarize-keeprecent-larger-than-list` | `keepRecent` larger than the number of non-system messages → all protected, window only | `[U2, B2]`, dropped 2, 28 → 14, calls 0 |
| `summarize-keeprecent-default` | absent `keepRecent` is 4 | `[Sum, B2, U3, B3, U4]`, dropped 3, 49 → 46, calls 1 |
| `summarize-empty-reply` | empty reply → summary is the prefix alone | `[S1, Sum(""), U3, B3]`, dropped 4, 49 → 35, calls 1 |
| `summarize-whitespace-reply-not-trimmed` | reply used as is (`"  Hello.  \n"`) | `[S1, Sum("  Hello.  \n"), U3, B3]`, dropped 4, 49 → 37, calls 1 |
| `summarize-provider-error` | provider error propagates unchanged | `rate_limit`, calls 1 |
| `summarize-script-exhausted` | `script: []` → `unavailable` | `unavailable`, calls 1 |
| `summarize-position-first-removed` | a plain system message between removed messages; summary at the earliest removed position | `[Sum, "Use English.", B2, U3]`, dropped 3, 42 → 39, calls 1 |
| `summarize-transcript-order` | transcript in input order (a summary between older messages) | `[Sum, B2, U3]`, dropped 4, 51 → 32, calls 1 |
| `summarize-plain-system-never-sent` | clarification 3: plain system messages are never sent | `["Code word: kiwi.", Sum, "Mention the moon.", B2, U3]`, dropped 3, 52 → 49, calls 1 |
| `summarize-protected-never-sent` | clarification 3: protected messages are never sent | `[Sum, "Keep this one.", "Noted, will do."]`, dropped 3, 37 → 34, calls 1 |
| `summarize-prefix-must-be-exact` | clarification 1: prefix without LF is a plain system message | `[fake, Sum, B2, U3]`, dropped 3, 51 → 48, calls 1 |
| `summarize-prefix-on-user-not-summary` | clarification 1: a `user` message with the prefix is not a summary | `[Sum, B2, user-with-prefix]`, dropped 3, 43 → 40, calls 1 |
| `summarize-empty-contents` | clarification 4: transcript non-empty with empty contents | `[Sum("Hi."), U3, B3]`, dropped 4, 30 → 28, calls 1 |
