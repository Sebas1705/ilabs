# providers contract

Shared contract for the `providers` module (Kotlin and TypeScript). Both implementations must pass every `*.json` fixture in this folder.

Request: `{ "messages": [{ "role": "system"|"user"|"assistant", "content": string }], "maxTokens"?: number }`
Response: `{ "text": string, "provider": string }`
Error: `{ "kind": "rate_limit"|"timeout"|"invalid_output"|"unavailable", "retryAfterMs"?: number }`

- `Provider`: `id: string`, `complete(request) -> response`, or fails with an error as above.
- `MockProvider(id, script)`: call N (1-based) consumes `script[N-1]`; an entry is `{"response": "<text>"}` or `{"error": {...}}`; after the script is exhausted every call fails with `unavailable`.
- `RetryingProvider(inner, {maxAttempts, baseDelayMs, maxDelayMs, sleep})`: retries `rate_limit` and `timeout` only. Wait before attempt k+1 = `retryAfterMs` if present else `baseDelayMs * 2^(k-1)`, clamped to `[0, maxDelayMs]`; `sleep(ms)` is injected and recorded by tests. After `maxAttempts` failures returns the last error.
- `FallbackProvider(providers)`: tries in order; moves on after `rate_limit`, `timeout`, `unavailable`; returns `invalid_output` immediately; if all fail returns the last error; empty list fails with `unavailable`. The response `provider` is the id of the provider that answered.

Fixture file (`contracts/providers/<name>.json`):

```json
{
  "name": "retry-then-success",
  "setup": {"kind": "retry", "maxAttempts": 3, "baseDelayMs": 100, "maxDelayMs": 1000},
  "providers": [{"id": "a", "script": [{"error": {"kind": "rate_limit"}}, {"response": "ok"}]}],
  "request": {"messages": [{"role": "user", "content": "hi"}]},
  "expect": {"result": {"text": "ok", "provider": "a"}, "sleeps": [100], "calls": {"a": 2}}
}
```

`setup.kind` is `retry` (wraps `providers[0]`) or `fallback` (all `providers`, in order). `expect.result` is a response or `{"error": {"kind": ...}}`.

## Fixture semantics

- `calls[id]` is the number of `complete()` invocations on that provider, including invocations that fail because the script is exhausted. Every provider in `providers` has an entry, `0` if it was never called. Compare with exact equality (an empty `providers` list gives `{}`).
- `sleeps` lists every `sleep(ms)` call in order, after clamping. There is no sleep after the final failed attempt.
- `retryAfterMs` is "present" when it is not null/absent: `0` counts as present and yields a wait of `0`, not the exponential delay. Negative values are clamped to `0`.
- `expect.result` errors: compare `error.kind` only, plus `error.retryAfterMs` only if the expected error specifies it.
- `expect.events` is optional. When present it is the exact, ordered trace of the run, compared by equality: `{"type":"call","provider":"<id>","outcome":"ok|rate_limit|timeout|invalid_output|unavailable"}` for every `complete()` invocation (including script-exhausted ones, outcome `unavailable`) and `{"type":"wait","ms":<int>}` for every `sleep(ms)` call. The `wait` events must equal `expect.sleeps`, in order; both runners check this for every fixture. An empty trace is `[]`.
- Non-provider exceptions (anything that is not a `ProviderError` / `ProviderException`) are rethrown unchanged by `RetryingProvider` and `FallbackProvider`: never mapped, never retried, and `FallbackProvider` does not move on to the next provider.

## Option preconditions

`RetryingProvider` validates its options when constructed: `maxAttempts >= 1`, `baseDelayMs >= 0` and `maxDelayMs >= 0`. A violation throws `RangeError` (TypeScript) or `IllegalArgumentException` via `require` (Kotlin). All three are integer milliseconds (`maxAttempts` an integer count); fixture numbers must be integers.

## Malformed fixtures

`runFixture` validates a fixture before running it and fails with the explicit error `invalid fixture: <reason>` (an `Error` in TypeScript, an `IllegalStateException` in Kotlin) when:

- `setup.kind` is neither `retry` nor `fallback`: `unknown setup kind: <kind>`
- a `retry` setup has no providers: `retry setup needs a provider`
- a script entry has neither `response` nor `error`: `script entry <n> of provider <id> has neither response nor error` (`n` is 1-based)
