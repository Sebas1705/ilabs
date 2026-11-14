# context

Keep a conversation inside a token budget, deterministically, with identical behavior in Kotlin and TypeScript. `slidingWindow` drops the oldest turns; `summarizeContext` replaces them by one summary written by any `Provider`. The behavior is pinned by the shared fixtures in [`contracts/context`](../../contracts/context/README.md); read that README for the exact rules and the fixture format.

It builds on [`providers`](../providers/README.md): any `Provider` can write the summary, including `MockProvider` for tests and demos (no API key needed). It is independent of [`structured`](../structured/README.md); the two modules can be used together or alone.

## Token cost

A message costs `4 + ceil(codePoints / 4)` tokens: a fixed overhead for role and framing plus a quarter of the content length. The length is counted in Unicode code points, not UTF-16 units (an emoji is one code point, a lone surrogate counts as one). An empty message costs 4. The conversation fits when its total is `<= maxTokens`; an empty list always fits.

The cost is pluggable: pass a `TokenCounter` as the optional `counter` option of either function to use a real tokenizer. It receives one message and must return a non-negative integer (in TypeScript `(message: Message) => number`, in Kotlin `(Message) -> Int`). The counter applies to every cost in the module: the fits check, `tokensBefore`, `tokensAfter` and the windowing. The module also exports `estimateTokens`, `defaultCounter`, `isSummaryMessage` and `SUMMARY_PREFIX`.

## `slidingWindow`

`slidingWindow(messages, { maxTokens, counter? })` is pure and never mutates its input. If the conversation fits it is returned unchanged. Otherwise:

- System messages are never dropped or moved, and the order of the rest is preserved.
- It keeps the longest contiguous suffix of the non-system messages that fits, scanning from the newest and stopping at the first message that does not fit (no skipping).
- If even the system messages plus the last non-system message exceed the budget, it fails with `budget_too_small` (`BudgetTooSmallError` in TypeScript, `BudgetTooSmallException` in Kotlin) and reports `required`, the smallest budget that would work.

## `summarizeContext`

`summarizeContext(provider, messages, { maxTokens, keepRecent, counter? })` makes at most one provider call. If the conversation fits it returns it unchanged and does not call the provider. Otherwise:

- The `keepRecent` most recent non-system messages (default 4) are protected and never summarized. Plain system messages stay where they are and are never sent to the provider.
- The older non-system messages and every existing summary are sent to the provider in one request and replaced by one new summary, inserted at the position of the earliest removed message. Existing summaries are merged, so there is never more than one summary in the result.
- If nothing is older than the protected messages, no call is made and the result is a plain `slidingWindow` (existing summaries alone never trigger a call).
- If the result still does not fit, `slidingWindow` runs on it: the new summary counts as a system message and is never dropped, but protected messages can be. It can fail with `budget_too_small` after the call was made.
- Provider errors propagate unchanged and nothing else is attempted.

A summary message is a `system` message whose content is the exact prefix `Summary of the earlier conversation:` followed by a line feed, then the provider's reply as is (an empty reply gives just the prefix). Detection is exact and case-sensitive.

`maxTokens` and `keepRecent` must be integers `>= 1`: otherwise TypeScript throws `RangeError` and Kotlin throws `IllegalArgumentException`.

## Results

| Field | `slidingWindow` | `summarizeContext` | Meaning |
|---|---|---|---|
| `messages` | yes | yes | The conversation that fits the budget |
| `droppedCount` | yes | yes | Input messages dropped or replaced by the summary (the new summary is not counted) |
| `tokensBefore` | yes | yes | Cost of the input |
| `tokensAfter` | yes | yes | Cost of `messages` |
| `summarized` | | yes | `true` when the provider call was made and the new summary inserted |
| `summaryCalls` | | yes | `0` or `1` |
| `summarizerRequest` | | yes | The request sent to the provider, or `null` when no call was made |

## TypeScript

```ts
import { MockProvider, type Message } from "@ilabs/providers";
import { BudgetTooSmallError, slidingWindow, summarizeContext } from "@ilabs/context";

// A system message and three turns: 7 messages, 49 tokens with the default counter.
const messages: Message[] = [{ role: "system", content: "Be brief." }];
for (let n = 1; n <= 3; n++) {
  messages.push({ role: "user", content: `User turn ${n}` }, { role: "assistant", content: `Bot turn ${n}` });
}

// Over budget: drop the oldest turn.
const windowed = slidingWindow(messages, { maxTokens: 40 });
console.log(windowed.messages.map((m) => m.content)); // [ 'Be brief.', 'User turn 2', 'Bot turn 2', 'User turn 3', 'Bot turn 3' ]
console.log(windowed.droppedCount, windowed.tokensBefore, windowed.tokensAfter); // 2 49 35

// Same budget, but replace the older turns by a summary (the mock provider writes it).
const provider = new MockProvider("mock", [{ response: "They said hi." }]);
const summarized = await summarizeContext(provider, messages, { maxTokens: 40, keepRecent: 2 });
console.log(summarized.messages.map((m) => m.content));
// [ 'Be brief.', 'Summary of the earlier conversation:\nThey said hi.', 'User turn 3', 'Bot turn 3' ]
console.log(summarized.summarized, summarized.summaryCalls, summarized.droppedCount); // true 1 4
console.log(summarized.tokensBefore, summarized.tokensAfter); // 49 38

// A budget that cannot hold the system message plus the last turn.
try {
  slidingWindow(messages, { maxTokens: 10 });
} catch (error) {
  if (error instanceof BudgetTooSmallError) console.log(error.kind, error.required); // budget_too_small 14
}
```

## Kotlin

```kotlin
import es.sebas1705.ilabs.context.*
import es.sebas1705.ilabs.providers.*

suspend fun demo() {
    // A system message and three turns: 7 messages, 49 tokens with the default counter.
    val messages = buildList {
        add(Message(Role.SYSTEM, "Be brief."))
        for (n in 1..3) {
            add(Message(Role.USER, "User turn $n"))
            add(Message(Role.ASSISTANT, "Bot turn $n"))
        }
    }

    // Over budget: drop the oldest turn.
    val windowed = slidingWindow(messages, WindowOptions(maxTokens = 40))
    println(windowed.messages.map { it.content }) // [Be brief., User turn 2, Bot turn 2, User turn 3, Bot turn 3]
    println("${windowed.droppedCount} ${windowed.tokensBefore} ${windowed.tokensAfter}") // 2 49 35

    // Same budget, but replace the older turns by a summary (the mock provider writes it).
    val provider = MockProvider("mock", listOf(ScriptEntry(response = "They said hi.")))
    val summarized = summarizeContext(provider, messages, SummarizeOptions(maxTokens = 40, keepRecent = 2))
    println(summarized.messages.map { it.content })
    // [Be brief., Summary of the earlier conversation:
    // They said hi., User turn 3, Bot turn 3]
    println("${summarized.summarized} ${summarized.summaryCalls} ${summarized.droppedCount}") // true 1 4
    println("${summarized.tokensBefore} ${summarized.tokensAfter}") // 49 38

    // A budget that cannot hold the system message plus the last turn.
    try {
        slidingWindow(messages, WindowOptions(maxTokens = 10))
    } catch (e: BudgetTooSmallException) {
        println(e.required) // 14
    }
}
```

## Composing with `providers`

The summarizer is any `Provider`, so the chain from [`providers`](../providers/README.md) applies to the one summary call. Wrap the real provider in `RetryingProvider` and/or `FallbackProvider` and pass the result as the first argument:

```ts
// `messages` is the conversation from the TypeScript example above.
import { FallbackProvider, MockProvider, RetryingProvider } from "@ilabs/providers";
import { summarizeContext } from "@ilabs/context";

const chain = new FallbackProvider([
  new RetryingProvider(new MockProvider("primary", [{ error: { kind: "rate_limit" } }, { response: "They planned a trip." }]), {
    maxAttempts: 2,
    baseDelayMs: 100,
    maxDelayMs: 1000,
    sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  }),
  new MockProvider("backup", [{ response: "Backup summary." }]),
]);

const result = await summarizeContext(chain, messages, { maxTokens: 40, keepRecent: 2 });
```

In Kotlin build the chain the same way and pass it as the `provider` argument. If the chain still fails, its `ProviderError` (`ProviderException` in Kotlin) propagates unchanged and the conversation is not modified.

## Limits

- Costs and budgets are `Int` in Kotlin and `number` in TypeScript; the default estimate is only an approximation of what a model counts. For real budgets pass a `TokenCounter` backed by the model's tokenizer.
- The summary text comes from the provider and costs tokens too: when the result is still over budget, `summarizeContext` windows it, which can drop protected messages.
- The contract pins the request shape: exactly 2 messages, a non-empty `system` instruction then a `user` transcript holding the full content of every replaced message (older turns and existing summaries) in input order. Protected and plain system messages are never sent. The wording of both is not pinned and may differ between the Kotlin and TypeScript implementations. Inspect `summarizerRequest` to see what was sent.

## Contract

[`contracts/context/README.md`](../../contracts/context/README.md) defines the behavior and the fixture format. Both implementations must pass every fixture in that folder.
