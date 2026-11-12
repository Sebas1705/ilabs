# structured contract

Shared contract for the `structured` module (Kotlin and TypeScript): JSON extraction, schema validation and a bounded repair loop on top of any `providers` `Provider`. Both implementations must pass every `*.json` fixture in this folder.

## Contract reference

**Schema subset** (JSON object): `type` (`object|array|string|number|integer|boolean|null`), `enum` (non-empty array), `minimum`, `maximum` (inclusive), `minLength`, `maxLength` (Unicode code points), `minItems`, `maxItems`, `properties` (object of schemas), `required` (array of strings), `additionalProperties` (boolean; absent = true), `items` (schema). Unknown keywords are ignored. A malformed schema (unknown `type` string, non-array `required`, non-object `properties`, empty `enum`, non-numeric `minimum`…) throws `invalid schema: <reason>` (`InvalidSchemaError` in TS, `InvalidSchemaException : IllegalArgumentException` in Kotlin); not pinned by fixtures, only by unit tests.

**`integer`** = a JSON number whose value is integral (`2`, `2.0` and `2e0` pass; `2.5` fails). Numbers outside double range are out of scope.

**Issues:** `{ "path": "<RFC 6901 JSON pointer, \"\" = root>", "code": "type|enum|minimum|maximum|minLength|maxLength|minItems|maxItems|required|additionalProperties" }`. Order, per node: (1) `type`, and if it fails, stop at this node (no further checks, no descent); (2) `enum`; (3) `minimum`, `maximum`; (4) `minLength`, `maxLength`; (5) `minItems`, `maxItems`; (6) objects: `required` (in the order listed in the schema; one issue per missing name, path = node path + `/` + escaped name), then `additionalProperties` (when `false`: one issue per extra name, names sorted by UTF-16 code unit order, path = the extra property), then declared `properties` present in the value, visited in sorted name order (UTF-16 code unit order); arrays: `items` applied to each index in order. `enum` uses JSON deep equality (numbers by numeric value, objects without key order). Pointer escaping: `~` → `~0`, `/` → `~1`.

**Extraction** (`extractJson(text)` → `{ok:true,value}` | `{ok:false,code:"no_json"|"invalid_json"}`): try candidates in this order, the first one that parses as strict JSON wins: (a) the whole text, trimmed; (b) each fenced block in document order (a line starting with three backticks, optional language tag, up to the next line that is three backticks; the content between); (c) the region from the first `{` or `[` to its balanced closing character, scanning with JSON string-literal and escape awareness (unbalanced → no candidate). Any candidate whose nesting depth exceeds 128 is rejected. Strict JSON: no comments, no trailing commas, no single quotes, no `NaN`/`Infinity`. If nothing parses: `no_json` when the text contains none of `{`, `[` or three backticks; otherwise `invalid_json`.

**Repair loop:** `structured(provider, request, schema, {maxRepairs = 2})` (`maxRepairs` is a non-negative integer, else `RangeError` / `require`). Call 1 sends `request.messages`. If the reply fails extraction or validation and calls made `<= maxRepairs`, append one `assistant` message (the reply, verbatim) and one `user` message (a non-empty repair request listing the issues; wording not pinned) to the messages and call again; messages accumulate across repairs. Success → `{ value, attempts, trace }`; after the last allowed attempt fails → `StructuredError` with `kind: "invalid_output"`, `issues` (of the last attempt; extraction failures are `[{path:"", code:"no_json"|"invalid_json"}]`), `attempts`, `trace`. `attempts` = number of `provider.complete` calls made (a provider error counts the call that failed). Provider errors propagate unchanged. `trace` = one `{ reply, extracted: {ok:true,value}|{ok:false,code}, issues }` per attempt (not pinned by fixtures).

**Fixture** (`contracts/structured/<name>.json`):

```json
{
  "name": "repair-succeeds-second",
  "schema": {"type": "object", "required": ["age"], "properties": {"age": {"type": "integer", "minimum": 0}}},
  "request": {"messages": [{"role": "user", "content": "How old?"}]},
  "setup": {"maxRepairs": 2},
  "provider": {"id": "a", "script": [{"response": "{\"age\": -1}"}, {"response": "{\"age\": 7}"}]},
  "expect": {"result": {"value": {"age": 7}}, "attempts": 2, "appended": ["assistant", "user"]}
}
```

`expect.result` is `{"value": <json>}` or `{"error": {"kind": "...", "issues": [...]}}`. Comparison: values by JSON deep equality; error `kind` always, `issues` only when the expected error specifies them (so provider errors, which carry no issues, omit them); `attempts` exact; `appended` = the roles of the messages beyond the original request that the **last** provider call received, exact. `provider` is a single `providers`-format provider (`id` + `script`).

`runFixture` returns the trace of every attempt whose reply was received, also when the provider fails on a later attempt (the failing call itself has no trace entry, so `trace.length` can be `attempts - 1`).

## Fixture semantics

- `provider` uses the `providers` mock format: call N (1-based) consumes `script[N-1]`; an entry is `{"response": "<text>"}` or `{"error": {"kind": "...", "retryAfterMs"?: number}}`; once the script is exhausted every call fails with `unavailable` (see [../providers/README.md](../providers/README.md)).
- Every fixture sets `setup.maxRepairs` explicitly. All `extract-*` and `validate-*` fixtures use `maxRepairs: 0`, so they make one call, pin the issues of that single reply and have `appended: []`.
- `issues` are compared as an ordered list of `{path, code}` with exact equality.
- "JSON deep equality" in the comparison of `expect.result.value` is the same relation as `enum`: numbers by numeric value (`2.0` equals `2`, `-0` equals `0`), objects without key order, arrays in order, no coercion between kinds (`true` is not `1`, `"1"` is not `1`).

## Clarifications

These rules make the Contract reference exact where two implementations could otherwise differ. They do not change it.

1. **Trimming** (candidate a) removes only the JSON whitespace characters space, tab, LF and CR from both ends. Other Unicode spaces (no-break space, BOM, …) are kept, so they make the candidate fail. Do not use `String.prototype.trim()` / Kotlin `trim()`, which strip more. Pinned by `extract-nbsp-not-trimmed`.
2. **Strict JSON** means RFC 8259 syntax exactly, applied to the whole candidate: after optional JSON whitespace the candidate is exactly one JSON value followed only by JSON whitespace. Values are objects, arrays, strings, numbers and the literals `true`, `false`, `null` only (no `True`, `None`, `undefined`, `NaN`, `Infinity` or other bare words). Numbers follow the RFC number grammar: optional `-`, then `0` or a non-zero digit followed by digits, optional `.` followed by at least one digit, optional `e`/`E` with optional sign and at least one digit (so `01`, `+1`, `.5`, `1.` are rejected). Strings are double-quoted, use only the RFC escapes (`\"`, `\\`, `\/`, `\b`, `\f`, `\n`, `\r`, `\t`, `\uXXXX`) and contain no raw control characters (U+0000–U+001F, so no literal newline or tab). Object keys are strings. No comments, no trailing commas. Any other trailing text makes the candidate fail (`42 is the answer` has no candidate that parses and none of `{`, `[`, three backticks, so it is `no_json`). `JSON.parse` implements exactly this; Kotlin's `Json.parseToJsonElement` is lenient (unquoted tokens, control characters, number syntax) and must not be relied on alone. Pinned by `extract-whole-trailing-text`, `extract-invalid-trailing-comma`, `extract-invalid-nan`, `extract-invalid-single-quotes` and every `extract-invalid-*`, `extract-raw-*-in-string` and `extract-bare-word` fixture.
   Numbers are compared and classified (enum, minimum, maximum, integer) as IEEE-754 doubles; integers beyond 2^53 are out of scope.
3. **Duplicate object keys** are accepted and the last occurrence wins (the behavior of `JSON.parse` and of `Json.parseToJsonElement`). Pinned by `extract-duplicate-keys`.
4. **Lines** for fenced blocks are the text split on LF (`\n`). An **opening line** is a line whose first three characters are backticks (no leading indentation); the rest of that line is the language tag and is ignored, whatever it contains. The **closing line** is the next line that equals three backticks after removing trailing JSON whitespace (spaces, tabs and the CR of a CRLF line ending); leading whitespace is not allowed. The block content is the exact text between the end of the opening line (after its LF) and the start of the closing line. Scanning for the next opening line resumes on the line after the closing line. Pinned by `extract-fence-trailing-whitespace`.
5. **Unterminated fence:** an opening line with no closing line after it yields no candidate, and no further fenced blocks exist after it. Pinned by `extract-unterminated-fence`.
6. **Balanced region** (candidate c): the start is the first `{` or `[` character of the text, found without string awareness (quotes before it are prose). From there one depth counter is used: outside string literals `{` and `[` add one, `}` and `]` subtract one; a `"` opens a string literal, inside which `\` skips the next character and `"` closes it. The region ends at the character that brings the depth back to zero. Mismatched bracket kinds are left to the parser (the candidate fails). Reaching the end of the text first gives no candidate. Only this one region is tried: later `{`/`[` characters are never used as a start. Pinned by `extract-braces-in-string`, `extract-escaped-quotes`, `extract-unbalanced`, `extract-only-first-region`.
7. **Nesting depth** of a candidate is the maximum number of simultaneously open arrays and objects, counted outside string literals (a scalar has depth 0, `[]` and `{}` depth 1). Depth 128 is accepted; 129 or more is rejected before parsing and counts as a candidate that does not parse. Pinned by `extract-depth-128`, `extract-depth-129`, `extract-too-deep` and `extract-depth-brackets-in-string`.
8. **"Contains three backticks"** in the `no_json` rule means the substring three backticks anywhere in the text, not only at a line start. Pinned by `extract-inline-backticks`.
9. **Keywords apply by instance kind:** `minimum`/`maximum` only to numbers, `minLength`/`maxLength` only to strings, `minItems`/`maxItems`/`items` only to arrays, `required`/`additionalProperties`/`properties` only to objects; on any other kind they are ignored, with or without `type`. `type` and `enum` apply to every kind. Pinned by `validate-keywords-by-instance-type`.
10. **`required`** checks presence only and is independent of `properties`: a required name with no schema in `properties` is still reported when missing. `additionalProperties: false` with absent or empty `properties` makes every property extra. Pinned by `validate-required-two-missing`.
11. **`integer`** is decided on the parsed numeric value, whatever its spelling: `2.0`, `2e0`, `1e2`, `1.5e1` and `-0` are integers. Pinned by `validate-integer-2-point-0`.
12. **Sorting** uses UTF-16 code unit order (JS default sort, Kotlin `String.compareTo`), not code point order: a name starting with an emoji (surrogate `U+D83D`) sorts before `U+FF61`. Pinned by `validate-order-utf16-units`.
13. **Escaping order:** `~` is replaced before `/` (`e/~f` → `e~1~0f`). Pinned by `validate-pointer-escaping`.
14. **Provider-thrown `invalid_output`** is a provider error: it propagates unchanged after one call and is never repaired. Pinned by `loop-provider-invalid-output`.

## Fixtures

70 fixtures. The seventeen rows from `extract-invalid-python-literals` to `extract-invalid-unquoted-key` use the empty schema `{}`, which accepts any value, so a lenient parser that accepted the text would return a value instead of the expected error.

| Fixture | Rule it pins | Expected |
|---|---|---|
| `extract-plain` | (a) the whole reply is JSON | value |
| `extract-fenced-json` | (b) a ```` ```json ```` block wins over a valid balanced region in the prose before it | value `{"a":1}` |
| `extract-fenced-no-lang` | (b) a fence without language tag | value `[1,2]` |
| `extract-prose-around` | (c) balanced region inside prose | value |
| `extract-braces-in-string` | (c) braces, brackets and backticks inside a string literal do not count | value |
| `extract-escaped-quotes` | (c) `\"` stays inside the string and `\\` before `"` closes it | value |
| `extract-array-root` | (c) region starting at `[` | value |
| `extract-whole-primitive` | (a) a trimmed whole-text scalar | value `42` |
| `extract-second-fence-wins` | (b) fences tried in order; an invalid first fence falls through to the second | value `{"a":2}` |
| `extract-no-json` | no `{`, `[` or three backticks → `no_json` | `invalid_output`, `no_json` |
| `extract-empty-reply` | empty reply → `no_json`, never an exception | `invalid_output`, `no_json` |
| `extract-invalid-trailing-comma` | strict JSON: trailing comma | `invalid_output`, `invalid_json` |
| `extract-unbalanced` | unbalanced region → no candidate | `invalid_output`, `invalid_json` |
| `extract-too-deep` | 200 nested arrays (valid JSON) exceed depth 128 | `invalid_output`, `invalid_json` |
| `extract-whitespace-reply` | whitespace-only reply → `no_json` | `invalid_output`, `no_json` |
| `extract-whole-trailing-text` | clarification 2: trailing text after a scalar | `invalid_output`, `no_json` |
| `extract-nbsp-not-trimmed` | clarification 1: only JSON whitespace is trimmed | `invalid_output`, `no_json` |
| `extract-unterminated-fence` | clarification 5: unterminated fence is no candidate | `invalid_output`, `invalid_json` |
| `extract-fence-trailing-whitespace` | clarification 4: tag and closing line with trailing spaces, CRLF | value `{"a":1}` |
| `extract-only-first-region` | clarification 6: only the first `{`/`[` starts a region | `invalid_output`, `invalid_json` |
| `extract-duplicate-keys` | clarification 3: last duplicate key wins | value `{"a":2}` |
| `extract-invalid-nan` | strict JSON: `NaN` | `invalid_output`, `invalid_json` |
| `extract-invalid-single-quotes` | strict JSON: single quotes | `invalid_output`, `invalid_json` |
| `extract-invalid-python-literals` | clarification 2: `True`/`None` are not JSON literals | `invalid_output`, `invalid_json` |
| `extract-invalid-js-undefined` | clarification 2: `undefined` is not a JSON literal | `invalid_output`, `invalid_json` |
| `extract-bare-word` | clarification 2: a bare word (`Sorry`) is not JSON | `invalid_output`, `no_json` |
| `extract-invalid-number-leading-zero` | clarification 2: number grammar rejects `01` | `invalid_output`, `invalid_json` |
| `extract-invalid-number-plus-sign` | clarification 2: number grammar rejects `+1` | `invalid_output`, `invalid_json` |
| `extract-invalid-number-leading-dot` | clarification 2: number grammar rejects `.5` | `invalid_output`, `invalid_json` |
| `extract-invalid-number-trailing-dot` | clarification 2: number grammar rejects `1.` | `invalid_output`, `invalid_json` |
| `extract-invalid-number-infinity` | clarification 2: `-Infinity` is not JSON | `invalid_output`, `invalid_json` |
| `extract-raw-newline-in-string` | clarification 2: a literal LF inside a string | `invalid_output`, `invalid_json` |
| `extract-raw-tab-in-string` | clarification 2: a literal tab inside a string | `invalid_output`, `invalid_json` |
| `extract-invalid-escape` | clarification 2: `\x41` is not an RFC escape | `invalid_output`, `invalid_json` |
| `extract-invalid-escape-quote` | clarification 2: `\'` is not an RFC escape | `invalid_output`, `invalid_json` |
| `extract-invalid-unicode-escape-nonhex` | clarification 2: `\u12G4` is not a `\uXXXX` escape (exactly 4 hex digits) | `invalid_output`, `invalid_json` |
| `extract-invalid-unicode-escape-nonascii-digits` | clarification 2: `\u` followed by the Arabic-Indic digits U+0660 U+0660 U+0664 U+0661 is not hex (only ASCII `0-9a-fA-F`) | `invalid_output`, `invalid_json` |
| `extract-invalid-block-comment` | clarification 2: `/* */` comments | `invalid_output`, `invalid_json` |
| `extract-invalid-line-comment` | clarification 2: `//` comments | `invalid_output`, `invalid_json` |
| `extract-invalid-unquoted-key` | clarification 2: object keys must be strings | `invalid_output`, `invalid_json` |
| `extract-depth-128` | clarification 7: depth 128 is accepted (then fails `maxItems: 0` at the root) | `invalid_output`, `maxItems` at `""` |
| `extract-depth-129` | clarification 7: depth 129 is rejected | `invalid_output`, `invalid_json` |
| `extract-depth-brackets-in-string` | clarification 7: 200 `[` inside a string value do not count toward depth (document depth 1) | value |
| `extract-inline-backticks` | clarification 8: three backticks inline in prose count for `no_json` vs `invalid_json` | `invalid_output`, `invalid_json` |
| `validate-type-mismatch` | `type` for each kind; properties in sorted order | 5 × `type` |
| `validate-type-stops-descent` | a failed `type` stops all checks and descent at that node | `type` at `/p` only |
| `validate-required-two-missing` | `required` in schema order, undeclared names included | `/zeta`, `/alpha` `required` |
| `validate-enum` | `enum` before `minLength` at the same node | `enum`, `minLength` at `""` |
| `validate-enum-deep-equal` | deep equality: numbers by value, objects without key order | `enum` at `/3`..`/7` |
| `validate-minimum-maximum` | inclusive bounds | `/0` `minimum`, `/3` `maximum`, `/5` `minimum` |
| `validate-integer-float` | `2.5` is not an integer | `type` at `/1` |
| `validate-integer-2-point-0` | clarification 11: integral spellings pass | value `[2,2,100,0,15]` |
| `validate-string-length-emoji` | lengths in code points (`"😀😀"` passes `maxLength: 2`) | `/1` `maxLength`, `/2` `minLength` |
| `validate-array-bounds` | inclusive `minItems`/`maxItems` | `/few` `minItems`, `/many` `maxItems` |
| `validate-additional-properties` | `required`, then sorted extras, then properties | `/id`, `/a`, `/z`, `/b` |
| `validate-nested-pointer` | pointers through objects and arrays | `/users/1/name`, `/users/1/tags/1` |
| `validate-pointer-escaping` | `~0`/`~1` escaping, `~` first (clarification 13) | `/e~1~0f`, `/a~1b`, `/c~0d` |
| `validate-order-integer-like-keys` | names `"10"`, `"2"`, `"b"` sorted by UTF-16 units, not JS integer-key order | `/30`, `/4` extras, then `/10`, `/2`, `/b` |
| `validate-items-per-index` | `items` applied to each index in order | `/1/id`, `/2/id`, `/3` |
| `validate-keywords-by-instance-type` | clarification 9: keywords apply by instance kind | 6 issues under `/0`..`/3` |
| `validate-order-utf16-units` | clarification 12: UTF-16 units, not code points | `/z`, `/😀`, `/｡` |
| `loop-first-try` | valid first reply, no repair | value, attempts 1, `[]` |
| `loop-repair-second` | one repair | value, attempts 2, `assistant,user` |
| `loop-repair-third` | `no_json`, then a validation failure, then a fenced success; messages accumulate | value, attempts 3, `assistant,user,assistant,user` |
| `loop-exhausted` | `maxRepairs: 1`; issues of the last attempt | `invalid_output` (`/age` `type`), attempts 2, `assistant,user` |
| `loop-max-repairs-zero` | `maxRepairs: 0` never repairs | `invalid_output`, attempts 1, `[]` |
| `loop-provider-error-first` | provider error on call 1 propagates | `timeout`, attempts 1, `[]` |
| `loop-provider-error-mid` | provider error on the repair call propagates and counts | `rate_limit`, attempts 2, `assistant,user` |
| `loop-script-exhausted` | exhausted mock script fails with `unavailable` | `unavailable`, attempts 2, `assistant,user` |
| `loop-provider-invalid-output` | clarification 14: provider `invalid_output` is not repaired | `invalid_output`, attempts 1, `[]` |
