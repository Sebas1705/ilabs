# structured

Ask a `Provider` for JSON that matches a schema, with identical behavior in Kotlin and TypeScript. `structured(provider, request, schema)` extracts a JSON value from the reply, validates it against the schema and, when the reply is not valid, asks the provider again with the problems listed. The behavior is pinned by the shared fixtures in [`contracts/structured`](../../contracts/structured/README.md); read that README for the exact rules (extraction order, issue order, fixture format).

It builds on [`providers`](../providers/README.md): any `Provider` works, including `MockProvider` for tests and demos (no API key needed).

## Supported schema keywords

A small JSON Schema subset. Unknown keywords are ignored; a malformed schema throws `InvalidSchemaError` (TypeScript) or `InvalidSchemaException` (Kotlin) before any call is made.

| Keyword | Applies to | Meaning |
|---|---|---|
| `type` | any value | `object`, `array`, `string`, `number`, `integer`, `boolean` or `null` |
| `enum` | any value | non-empty array of allowed values (JSON deep equality) |
| `minimum`, `maximum` | numbers | inclusive bounds |
| `minLength`, `maxLength` | strings | length in Unicode code points |
| `minItems`, `maxItems` | arrays | length bounds |
| `items` | arrays | schema applied to every element |
| `properties` | objects | schema per property name |
| `required` | objects | property names that must be present |
| `additionalProperties` | objects | `false` rejects properties not listed in `properties` (absent means allowed) |

Validation problems are reported as issues: `{ path, code }`, where `path` is an RFC 6901 JSON pointer (`""` is the root) and `code` is the failed keyword: `type`, `enum`, `minimum`, `maximum`, `minLength`, `maxLength`, `minItems`, `maxItems`, `required` or `additionalProperties`. Extraction problems are `no_json` and `invalid_json` at the root: `no_json` if and only if the text contains none of `{`, `[` or three backticks, otherwise `invalid_json`. Parsing is strict RFC 8259 JSON (no comments, trailing commas, single quotes, `NaN`/`Infinity` or raw control characters).

## How the repair loop works

The first call sends `request.messages` unchanged. The reply is parsed (the whole text, then fenced code blocks, then the first balanced `{...}` or `[...]` region, so prose or a markdown fence around the JSON is fine) and validated. If it is not valid, `structured` appends the reply as an `assistant` message and a `user` message listing the issues, and calls again; messages accumulate across repairs.

`maxRepairs` (default 2, must be a non-negative integer) is the number of repair calls after the first one, so at most `maxRepairs + 1` calls are made. `maxRepairs: 0` means validate once and never repair. When the last allowed attempt still fails, `structured` throws `StructuredError` (TypeScript) or `StructuredException` (Kotlin) with `kind: "invalid_output"`, the `issues` of the last attempt, the number of `attempts` and the per-attempt `trace`. On success it returns `{ value, attempts, trace }`.

## Provider errors

Errors thrown by the provider (`ProviderError` / `ProviderException`) propagate unchanged and are never repaired, even `invalid_output` raised by the provider itself; in the contract fixtures and `runFixture`, the call that failed counts in `attempts`. To survive transient failures, compose the provider first: wrap it in `RetryingProvider` and/or `FallbackProvider` from `providers` and pass the result to `structured`. Each repair is one more call through that chain, so retries and fallbacks apply per call.

## TypeScript

```ts
import { MockProvider } from "@ilabs/providers";
import { structured, type Schema } from "@ilabs/structured";

const schema: Schema = {
  type: "object",
  required: ["name", "age"],
  properties: { name: { type: "string", minLength: 1 }, age: { type: "integer", minimum: 0 } },
  additionalProperties: false,
};

// The first reply breaks the schema (negative age); the second is fixed and wrapped in a fence.
const provider = new MockProvider("mock", [
  { response: 'Sure! {"name": "Ada", "age": -1}' },
  { response: '```json\n{"name": "Ada", "age": 36}\n```' },
]);

const result = await structured(provider, { messages: [{ role: "user", content: "Who is Ada?" }] }, schema, {
  maxRepairs: 2,
});
console.log(result.value); // { name: 'Ada', age: 36 }
console.log(result.attempts); // 2
console.log(result.trace[0]?.issues); // [ { path: '/age', code: 'minimum' } ]
```

Composing with `providers`:

```ts
import { FallbackProvider, MockProvider, RetryingProvider } from "@ilabs/providers";
import { structured } from "@ilabs/structured";

const chain = new FallbackProvider([
  new RetryingProvider(new MockProvider("primary", [{ error: { kind: "rate_limit" } }, { response: "[1, 2, 3]" }]), {
    maxAttempts: 2,
    baseDelayMs: 100,
    maxDelayMs: 1000,
    sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  }),
  new MockProvider("backup", [{ response: "[4]" }]),
]);

const list = await structured(chain, { messages: [{ role: "user", content: "Numbers?" }] }, { type: "array" });
console.log(list.value); // [ 1, 2, 3 ]
```

## Kotlin

```kotlin
import es.sebas1705.ilabs.providers.*
import es.sebas1705.ilabs.structured.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

suspend fun demo() {
    val schema = Json.parseToJsonElement(
        """
        {
          "type": "object",
          "required": ["name", "age"],
          "properties": {
            "name": {"type": "string", "minLength": 1},
            "age": {"type": "integer", "minimum": 0}
          },
          "additionalProperties": false
        }
        """,
    ).jsonObject

    // The first reply breaks the schema (negative age); the second is fixed and wrapped in a fence.
    val provider = MockProvider(
        "mock",
        listOf(
            ScriptEntry(response = "Sure! {\"name\": \"Ada\", \"age\": -1}"),
            ScriptEntry(response = "```json\n{\"name\": \"Ada\", \"age\": 36}\n```"),
        ),
    )

    val result = structured(
        provider,
        CompletionRequest(listOf(Message(Role.USER, "Who is Ada?"))),
        schema,
        StructuredOptions(maxRepairs = 2),
    )
    println(result.value) // {"name":"Ada","age":36}
    println(result.attempts) // 2
    println(result.trace[0].issues) // [Issue(path=/age, code=MINIMUM)]
}
```

Compose with `RetryingProvider` and `FallbackProvider` the same way as in TypeScript: build the chain, then pass it as the `provider` argument.

## Contract

[`contracts/structured/README.md`](../../contracts/structured/README.md) defines the behavior and the fixture format. Both implementations must pass every fixture in that folder.
