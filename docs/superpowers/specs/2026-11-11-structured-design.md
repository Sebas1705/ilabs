# `structured` module — design

Status: draft for owner approval · Date: 2026-11-11 · Parent: [2026-11-11-ilabs-design.md](2026-11-11-ilabs-design.md)

## Intent

Ask a `Provider` for JSON that matches a schema, and get either a validated value or a clear failure. The model may wrap the JSON in prose or code fences, or get a field wrong; `structured` extracts, validates, and asks the model to repair its own answer a bounded number of times. It is the second iLabs module (after `providers`) and plugs into any `Provider`.

## Goals / non-goals

- Same behavior in Kotlin (KMP) and TypeScript, pinned by shared fixtures in `contracts/structured/`.
- Deterministic only: the contract covers extraction, validation and the repair loop driven by scripted mock replies. Real model output and the repair prompt wording are NOT in the contract (the contract pins how many messages are appended and with which roles, not their text).
- Non-goals: full JSON Schema, streaming, tool calling, schema generation from types, provider-native "JSON mode" (may come later).

## Decisions (my calls, to confirm)

1. **Schema = a small JSON Schema subset, hand-implemented in both languages** (no ajv/kotlinx-schema dependency, so behavior cannot drift): `type` (`object|array|string|number|integer|boolean|null`), `properties`, `required`, `additionalProperties` (boolean), `items`, `enum`, `minimum`, `maximum`, `minLength`, `maxLength`, `minItems`, `maxItems`. `integer` means a JSON number with no fractional part; numbers beyond 2^53 are out of scope.
2. **Issues are stable and pinned:** each validation problem is `{ path: "<JSON pointer>", code: "<type|required|enum|minimum|maximum|minLength|maxLength|minItems|maxItems|additionalProperties>" }`, reported in document order. Message text is not pinned.
3. **Extraction rule (pinned):** take the whole reply if it parses as JSON; else the first fenced block (```` ``` ```` with optional `json`) that parses; else the balanced `{...}` or `[...]` region that starts at the first `{` or `[` of the reply, if it parses (only that one region is tried); else failure `no_json`. Parse failures that remain are `invalid_json`.
4. **Repair loop:** `structured(provider, request, schema, { maxRepairs })` calls the provider; on `no_json`, `invalid_json` or validation issues it appends one `assistant` message (the previous reply) and one `user` message (the repair request listing the issues) and calls again, up to `maxRepairs` (default 2, `>= 0`). Result: `{ value, attempts }` on success, or a `StructuredError` (`kind: invalid_output`, `issues`, `attempts`) after the last attempt. Provider errors (`rate_limit`, `timeout`, `unavailable`, `invalid_output` thrown by the provider) propagate unchanged: compose `RetryingProvider`/`FallbackProvider` outside.
5. **Reuse:** takes any `providers` `Provider`; fixtures use the providers mock script format. Fixture shape: `{ name, schema, request, setup: {maxRepairs}, providers: [{id, script}], expect: { result: {value}|{error:{kind, issues}}, attempts, appended: ["assistant","user", ...] } }`.
6. **Demos:** one page / one screen per platform: pick a fixture and see the attempt timeline (raw reply → extracted JSON → issues → repair → final value or failure), es + en, light/dark, same layout rules as the `providers` demos.

## Groundwork done first (deferred from the `providers` review)

Before module code, one small task makes the structure scale to module 2: Android fixture assets per module (`assets/contracts/<module>/`), Gradle project names unique (`providers-kotlin`, `structured-kotlin`), one Kotlin test per fixture (failures no longer hide each other), an optional `expect.events` pinned in `providers` fixtures and asserted on both sides, and the web demo gets a second route with shared layout. Remaining `providers` minors (accessibility, plurals, etc.) stay in the backlog.

## Approaches considered

- **A. Hand-implemented schema subset + shared fixtures (chosen):** small, identical in both languages, no dependency drift, fits the "tiny reusable module" goal. Less expressive than JSON Schema.
- **B. Wrap a library per language (ajv, a Kotlin JSON-schema lib):** more complete but behavior and error codes differ per library, which breaks the same-results contract.
- **C. Type-driven validation (zod / kotlinx.serialization):** idiomatic per language but no shared schema, so no shared contract.

## Left to the builder

Internal file layout, JSON pointer escaping details (RFC 6901), how the repair message is worded, UI copy, and how demos render long replies.
