# providers

Small building blocks for calling AI providers reliably, with identical behavior in Kotlin and TypeScript. The behavior is pinned by the shared fixtures in [`contracts/providers`](../../contracts/providers/README.md); read that README for the exact rules (retry waits, error handling, fixture format).

## The three providers

| Provider | What it does |
|---|---|
| `MockProvider(id, script)` | Deterministic provider for tests and demos: call N consumes `script[N-1]`, then fails with `unavailable`. No API key needed. |
| `RetryingProvider(inner, options)` | Retries `rate_limit` and `timeout` with exponential backoff (or the error's `retryAfterMs`), clamped to `maxDelayMs`. The `sleep` function is injected so tests never wait. |
| `FallbackProvider(providers)` | Tries providers in order, moving on after `rate_limit`, `timeout` and `unavailable`; `invalid_output` stops the chain. |

Each implementation lives in its own folder: `modules/providers/ts` (package `@ilabs/providers`) and `modules/providers/kotlin` (package `es.sebas1705.ilabs.providers`, KMP for Android and JVM).

## TypeScript

```ts
import { FallbackProvider, MockProvider, RetryingProvider } from "@ilabs/providers";

const primary = new RetryingProvider(
  new MockProvider("primary", [{ error: { kind: "rate_limit" } }, { response: "hello" }]),
  {
    maxAttempts: 3,
    baseDelayMs: 100,
    maxDelayMs: 1000,
    sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  },
);
const chain = new FallbackProvider([primary, new MockProvider("backup", [{ response: "hi" }])]);

const reply = await chain.complete({ messages: [{ role: "user", content: "Say hello" }] });
console.log(reply.text, reply.provider); // "hello" "primary"
```

## Kotlin

```kotlin
import es.sebas1705.ilabs.providers.*
import kotlinx.coroutines.delay

suspend fun demo() {
    val primary = RetryingProvider(
        MockProvider(
            "primary",
            listOf(ScriptEntry(error = ScriptError(ErrorKind.RATE_LIMIT)), ScriptEntry(response = "hello")),
        ),
        RetryOptions(maxAttempts = 3, baseDelayMs = 100, maxDelayMs = 1000, sleep = { ms -> delay(ms) }),
    )
    val chain = FallbackProvider(listOf(primary, MockProvider("backup", listOf(ScriptEntry(response = "hi")))))

    val reply = chain.complete(CompletionRequest(listOf(Message(Role.USER, "Say hello"))))
    println("${reply.text} ${reply.provider}") // hello primary
}
```

Failures are reported as `ProviderError` (TypeScript) or `ProviderException` (Kotlin) carrying a `kind` and an optional `retryAfterMs`. Any other exception is rethrown unchanged.

## Contract

[`contracts/providers/README.md`](../../contracts/providers/README.md) defines the behavior and the fixture format. Both implementations must pass every fixture in that folder.
