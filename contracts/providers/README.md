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
