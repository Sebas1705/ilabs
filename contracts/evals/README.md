# evals contract

Shared contract for the `evals` module (Kotlin and TypeScript): run a suite of cases (a conversation plus graders) against one or more `providers` `Provider`s, grade every reply deterministically and report who passed what. Both implementations must pass every `*.json` fixture in this folder.

Messages, providers and provider errors are the `providers` types (see [../providers/README.md](../providers/README.md)). The `json` and `jsonEquals` graders reuse `structured`'s `extractJson`, `validate`, `checkSchema` and JSON deep equality exactly as pinned in [../structured/README.md](../structured/README.md).

## Rules

**Suite and run.** `runSuite(suite, { targets, judge? })`; `suite = { cases: [{ id, messages, graders }] }`; each target is a `Provider` (its `id` names it in the report); `judge` is an optional `Provider`. The suite is VALIDATED COMPLETELY BEFORE ANY CALL; on any problem `runSuite` throws `invalid suite: <reason>` (`Error` in TypeScript, `IllegalArgumentException` in Kotlin; the prefix is pinned, the reason wording is not) and no provider is called. Rules: `cases` non-empty; case ids non-empty and unique; each case has `messages` (an array of `{role, content}`) and at least one grader; target ids unique, non-empty and not equal to `judge` (reserved); no unknown grader kind; grader parameters well-formed (below); and, only when the suite is parsed from JSON, no unknown key (below; a `parseSuite`-only rule). Execution is sequential: case order, then target order; one provider call per (case, target) with exactly `case.messages`. A suite given as JSON (fixtures, demos) goes through `parseSuite(json)`, which validates everything except the targets and the judge (see below) and returns the typed suite (in Kotlin the graders are a sealed type).

**Unknown keys (a `parseSuite` rule).** When a suite is parsed from JSON, an unknown key in the suite object, a case object, a message object or a grader object fails with `invalid suite: <reason>` (only the prefix is pinned). Unknown keys are checked only for a known kind; an unknown kind is reported as an unknown kind. The suite object accepts only `cases`; a case only `id`, `messages`, `graders`; a message only `role` and `content` (as in `providers`); a grader only `kind` plus the keys of its own kind: string graders `value`, `trim`, `ignoreCase`; `oneOf` `values`, `trim`, `ignoreCase`; `length` `min`, `max`; `json` `schema`; `jsonEquals` `value`; `judge` `rubric`. So `trim`/`ignoreCase` on `length`, `json`, `jsonEquals` or `judge`, `value` on `length`/`json`/`judge` or `schema` on a string grader are unknown keys. The rule does not apply inside `json.schema` (unknown schema keywords stay ignored, as in `structured`) nor inside `jsonEquals.value` (arbitrary JSON). It is a parse-time rule: a typed Kotlin suite cannot carry extra keys, and TypeScript `runSuite` on a hand-built object validates only the typed fields (extra properties are not inspected, which matches Kotlin).

**`parseSuite` and `runSuite`.** `parseSuite` accepts a parsed JSON value or a string; a string is parsed as strict JSON (`structured`'s strict rules: no comments, trailing commas, `NaN`, single quotes…; duplicate keys are accepted and the last one wins; the nesting-depth limit of 128 applies where the implementation reuses `structured`'s parser) and a parse failure fails with `invalid suite: <reason>`. A fixture's `suite` is never a string: the harness passes it already parsed, and a string-valued `suite` in a fixture is not allowed (it is a JSON object, except in `invalid-suite-null` and `invalid-suite-array`, which pass `null` and an array on purpose). `parseSuite` validates everything except the targets and the judge; `runSuite` validates the target ids (non-empty, unique, `judge` reserved). Both happen before any call.

**Graders.** Each yields `{ kind, passed, issues?, error? }`; all graders of a case are evaluated in order on the reply text exactly as returned (no normalization), with no short circuit.

- String graders: `equals { value, trim?, ignoreCase? }`, `contains`, `notContains`, `startsWith`, `endsWith` (same options), `oneOf { values, trim?, ignoreCase? }`. `value` must be a string, `values` a non-empty array of strings, the options booleans (default false). `trim` removes only JSON whitespace (space, tab, LF, CR) from BOTH the reply and the compared value(s) (each candidate in `oneOf`); then `ignoreCase` folds ASCII letters only (A–Z ↔ a–z) on both sides (every other character is compared as is: `ß`, `İ` and `ı` never match their Latin counterparts). `contains`/`startsWith`/`endsWith` with `""` always pass; `notContains ""` always fails.
- `length { min?, max? }`: at least one bound; integers `>= 0`; `min <= max`; the reply length in Unicode code points (lone surrogates count one), inclusive.
- `json { schema }`: `schema` must pass `structured`'s `checkSchema`; the reply goes through `structured`'s `extractJson` then `validate`: passes when extraction succeeds and there are no issues; `issues` = the extraction failure `[{path: "", code: "no_json" | "invalid_json"}]` or the validation issues `{path, code}`.
- `jsonEquals { value }`: the key `value` must be present (`null` is a valid JSON value, an absent key is invalid); passes when extraction succeeds and the extracted value deep-equals `value` (`structured`'s equality); `issues` as above for extraction failures, none for a mismatch.
- `judge { rubric }`: `rubric` a non-empty string. ONE judge call per judge grader (grader order, case-major then target-minor), every call counted (including a failing one or an exhausted script). The request has exactly 2 messages and no other fields: a non-empty `system` instruction, then a `user` message that contains, in this order, the `rubric`, the content of the case's LAST `user` message (the empty string if there is none) and the reply (wording and labels not pinned). Verdict: remove leading JSON whitespace from the judge reply; it must start with `PASS` or `FAIL` (case-sensitive) and be followed by the end of the text or a character that is not an ASCII letter, digit or underscore (`PASS.` and `PASS: ok` are verdicts, `PASSED`, `pass` and `Passing` are not). `PASS` → `passed true`; `FAIL` → `passed false`; anything else → the grader errors with `error: "bad_verdict"`. No `judge` provider supplied → `error: "no_judge"` (no call). A judge provider error → `error: <the provider error kind>`. An errored grader entry has `passed false`.

`evaluateGrader(grader, reply)` is the pure, exported form of one non-judge grader.

**Result of a (case, target).** `{ caseId, targetId, status, reply?, error?, graders }`:

- the provider call fails with a provider error → `status "error"`, `error` = the error kind (a string), no `reply`, `graders: []` (so no judge call is made); the run continues with the next pair; a failing call counts in `calls`;
- otherwise `reply` = the provider's `text` verbatim and `graders` has one entry per grader; `status` is `"error"` if any grader errored, else `"fail"` if any grader failed, else `"pass"`;
- an exception that is not a provider error (and, in Kotlin, `CancellationException` or `Error`) propagates and aborts the run. Not pinned by fixtures, only by unit tests.

**Summary and calls.** `summarize(results)` → one entry per target in order of first appearance: `{ targetId, total, passed, failed, errored, passRate }` with `passRate = passed / total` as an IEEE-754 double (an empty list gives an empty summary; `total` is never 0 for a listed target). `runSuite` returns `{ results, summary, calls }`; `calls` has EVERY target id with its call count (0 when none) and the key `judge` only when a judge was supplied (value 0 when never called).

## Fixture format

`contracts/evals/<name>.json`:

```json
{
  "name": "status-fail-beats-pass",
  "suite": {"cases": [{"id": "c1", "messages": [{"role": "user", "content": "Say hello"}],
            "graders": [{"kind": "contains", "value": "hello"}, {"kind": "length", "max": 3}]}]},
  "targets": [{"id": "a", "script": [{"response": "hello there"}]}],
  "expect": {
    "results": [{"caseId": "c1", "targetId": "a", "status": "fail", "reply": "hello there",
                 "graders": [{"kind": "contains", "passed": true}, {"kind": "length", "passed": false}]}],
    "summary": [{"targetId": "a", "total": 1, "passed": 0, "failed": 1, "errored": 0, "passRate": 0}],
    "calls": {"a": 1}
  }
}
```

(This is the real `status-fail-beats-pass.json`, only reformatted. Every file is exactly `JSON.stringify(fixture, null, 2) + "\n"` (2-space indentation, LF, trailing newline) except the three literal-number files of clarification 2.)

- Top-level keys: `name` (equal to the file name), `suite` (passed to `parseSuite` as is, so it can be malformed on purpose), `targets`, optional `judge`, `expect`.
- `targets` is a list of `providers` mock providers `{ "id", "script" }`, passed to `runSuite` in order. Call N (1-based) on a mock consumes `script[N-1]`; an entry is `{"response": "<text>"}` or `{"error": {"kind": "rate_limit" | "timeout" | "invalid_output" | "unavailable"}}`; once the script is exhausted every call fails with `unavailable` (`script: []` fails on the first call).
- `judge` is `{ "script": [...] }`: a mock provider with the same script format whose id the harness fixes to `judge`. The judge is never listed in `targets`.
- `expect` is either `{ "results", "summary", "calls", "judgeContains"? }` or `{ "invalidSuite": true }`.

## Comparison and harness

- `expect.invalidSuite: true`: `runSuite` (through `parseSuite`, with the fixture's targets and judge) throws an error whose message starts with `invalid suite:`, and the recorded call count of every target and of the judge is 0.
- `results`: same length, in order (case-major, then target); `caseId`, `targetId`, `status` exact. `reply` is compared exactly when the expected result has it (every fixture writes it for results that have one). When the expected result has `error`, the actual result has that exact `error` and no `reply`; when it has none, the actual result has no `error`.
- `graders`, when present (every fixture writes it), lists ALL graders of the case in order: the actual list has the same length and each entry the same `kind` and `passed`. `error` is compared always: an expected entry without `error` requires an actual entry without `error`. `issues` is compared always, as an ordered list of `{path, code}` with exact equality, a missing `issues` counting as `[]` on BOTH sides: an expected entry without `issues` requires an actual entry with no issues (absent or `[]`), so a grader that is not `json`/`jsonEquals` must not report any issue.
- `summary`: same length, in order; `targetId` exact, `total`, `passed`, `failed`, `errored` as integers, and `passRate` compared as an IEEE-754 double VALUE, not as literal text (`0` and `0.0` are equal; fixtures write the shortest round-trip literal: `0`, `0.2`, `0.3333333333333333`, `0.5`, `0.6666666666666666`, `1`).
- `calls`: exact map equality (same keys, same counts).
- The harness wraps every provider to record the requests it receives.
  - **Target requests:** each one has exactly the field `messages`, deep-equal (role and content) to the `messages` of the case being run.
  - **Judge requests:** every one (also when the call fails) has exactly the field `messages` with exactly 2 messages, `system` then `user`, each with non-empty `content` and no other fields.
  - **`judgeContains`** (only in fixtures that make at least one judge call): strings that appear in the `user` message of the FIRST judge request as ordered, non-overlapping substrings (search each one starting right after the end of the previous match). It applies to the first judge request only, so `judge-first-call-for-second-target` arranges for that first request to belong to a later pair (case `c1`, target `b`, after target `a` failed), which catches a mix-up of the case or reply sent with a call.
- Both runners check every rule above for every fixture. `runFixture` returns `{ invalidSuite, output (null when invalid), judgeRequests }`.

## Malformed fixtures

`runFixture` fails with the explicit error `invalid fixture: <reason>` (an `Error` in TypeScript, an `IllegalStateException` in Kotlin) when the fixture itself is unusable: an unknown top-level key, a target without a string `id` or an array `script`, a script entry that has neither `response` nor `error` (`script entry <n> of provider <id> has neither response nor error`, `n` 1-based), or an `expect` that is neither `{ "invalidSuite": true }` nor an object with `results`, `summary` and `calls`. A malformed `suite` is NOT a malformed fixture: it is what the `invalid-*` fixtures test.

## Clarifications

These make the Rules exact where two implementations could otherwise differ. They do not change them.

1. **Shape of a valid suite.** The suite is a JSON object with `cases`, an array. A case is an object with `id` (a string), `messages` (an array, possibly empty) and `graders` (a non-empty array). A message is an object with `role` (`system`, `user` or `assistant`) and `content` (a string). A grader is an object whose `kind` is exactly one of `equals`, `contains`, `notContains`, `startsWith`, `endsWith`, `oneOf`, `length`, `json`, `jsonEquals`, `judge` (case-sensitive). A key the contract defines and that is present must have the right type; `null` is not the same as absent (only `jsonEquals.value` accepts `null`). Any other key is an unknown key (see the Rules). "Non-empty" for ids and `rubric` means at least one character, so a rubric of one space is valid. Pinned by `empty-messages-allowed`, `judge-whitespace-rubric-valid`, `invalid-suite-null`, `invalid-suite-array`, `invalid-case-not-object`, `invalid-message-not-object`, `invalid-message-missing-role`, `invalid-message-missing-content`, `invalid-case-id-not-string`, `invalid-grader-not-object`, `invalid-message-role`, `invalid-message-role-null`, `invalid-message-content-not-string`, `invalid-kind-wrong-case`, `invalid-missing-kind`, `invalid-kind-not-string`, `invalid-option-null` and `invalid-length-null-bound`.
2. **Numbers are compared by value, and three files keep literal spellings.** `length` bounds are integers by value, as `structured`'s `integer`: `2.0` and `1e2` are the integers 2 and 100 and valid, `1.5` is not; bounds above 2^31 − 1 are out of scope. A number inside `jsonEquals.value` is also compared by value (`1.0` equals `1`). `length-integral-float-bound.json` (`"max": 2.0`), `length-exponent-bound.json` (`"max": 1e2`) and `jsonequals-float-value.json` (`"value": 1.0`) contain literal numbers that `JSON.stringify` would rewrite: do NOT reformat them. They are the only formatting exceptions; every other file is exactly `JSON.stringify(x, null, 2) + "\n"`. TypeScript cannot see the difference (`JSON.parse` yields the same number); the three files guard the Kotlin parser, which sees the literal text. Pinned by those three fixtures and `invalid-length-fractional`.
3. **An empty target list is valid**: no call is made and `runSuite` returns `results: []`, `summary: []` and `calls: {}` (plus `judge: 0` when a judge is supplied). Pinned by `no-targets-empty-run`.
4. **Validation covers everything before the first call.** Every `invalid-*` fixture whose problem is in a case puts it in the second case, after a valid first case, so an implementation that validates lazily makes a call and fails the zero-call check. The target id `judge` is rejected whether or not a judge is supplied. Pinned by every `invalid-*` fixture, `invalid-target-id-judge` and `invalid-target-id-judge-with-judge`.
5. **Verdict boundary.** "Not an ASCII letter, digit or underscore" means not in `[A-Za-z0-9_]`: any other character, including a non-ASCII letter such as `é`, ends the keyword, so `PASSé` is the verdict `PASS`. Do not use Unicode-aware tests (`\w` with Unicode flags, Kotlin `isLetterOrDigit()`). Removing leading JSON whitespace removes only space, tab, LF and CR (not a no-break space, so do not use `trimStart()` / Kotlin `trimStart()`). Pinned by `judge-pass-non-ascii-letter`, `judge-word-char-is-bad-verdict` and `judge-nbsp-not-trimmed`.
6. **Order of the string steps.** `trim` (when set) applies first to the reply and to the value or each candidate, then `ignoreCase` (when set), then the comparison. The empty-string rules apply to the value after these steps, so a value that `trim` reduces to `""` makes `contains`/`startsWith`/`endsWith` pass and `notContains` fail. Pinned by `contains-trim-value-to-empty`. As for `structured`, `trim` must not use `String.prototype.trim()` / Kotlin `trim()`, which strip more (pinned by `equals-trim-nbsp-kept` and `string-graders-nbsp-not-trimmed-all-kinds`); and ASCII-only folding applies to every string kind (pinned by `string-graders-ascii-fold-all-kinds`).
7. **Code points in `length`** follow the `context` rule: a high surrogate followed by a low surrogate is one code point; any other surrogate is one code point on its own and never swallows its neighbour. Pinned by `length-lone-surrogate`.
8. **`issues` presence.** `json` and `jsonEquals` always report `issues` (an empty list when there are none); the other graders (judge included) never report any issue. The harness compares `issues` on every grader entry, treating a missing `issues` as `[]` on both sides, so the fixtures pin the content, not the presence: an actual `issues: []` on a string, `length` or `judge` grader is tolerated only because it counts as absent.
9. **No zero-call target in a valid run.** `cases` is non-empty and every target is called once per case, so in a valid run every target has at least one call; a target count of 0 only exists for invalid suites (checked by the harness) and `judge: 0` for a supplied but unused judge. `calls-lists-every-target` pins that every target is listed, with failing calls counted, and `calls-judge-key-zero` pins the zero entry.

## Pinned by unit tests, not fixtures

- A non-provider exception (and, in Kotlin, `CancellationException` or `Error`) aborts the run and is rethrown unchanged.
- `summarize` on results in arbitrary order (targets by first appearance, counts per status); an empty list gives `[]`.
- `evaluateGrader` on every pure grader directly (the fixtures reach it only through `runSuite`).
- The empty string stands in for the last `user` message in the judge request when the case has none (`judge-no-user-message` only shows that the call is made).
- `parseSuite` on a string (strict JSON, parse failure → `invalid suite:`).
- Extra properties on a hand-built TypeScript suite object are not inspected by `runSuite`.

## Validation rule → fixture map

| Rule | Fixtures |
|---|---|
| the suite is a JSON object | `invalid-suite-null`, `invalid-suite-array` |
| `cases` present, an array, non-empty | `invalid-cases-missing`, `invalid-cases-not-array`, `invalid-empty-cases` |
| a case is an object | `invalid-case-not-object` |
| case `id` present, a string, non-empty, unique | `invalid-case-id-missing`, `invalid-case-id-not-string`, `invalid-empty-case-id`, `invalid-duplicate-case-id` |
| `messages` present and an array (may be empty) | `invalid-no-messages`, `invalid-messages-not-array`; valid: `empty-messages-allowed` |
| a message is an object with a valid `role` and a string `content` | `invalid-message-not-object`, `invalid-message-missing-role`, `invalid-message-role-null`, `invalid-message-role`, `invalid-message-missing-content`, `invalid-message-content-not-string` |
| `graders` present, an array, non-empty | `invalid-graders-missing`, `invalid-graders-not-array`, `invalid-no-graders` |
| a grader is an object with a known `kind` | `invalid-grader-not-object`, `invalid-missing-kind`, `invalid-kind-not-string`, `invalid-unknown-kind`, `invalid-kind-wrong-case`, `invalid-kind-wrong-case-with-value` |
| no unknown key | `invalid-unknown-suite-key`, `invalid-unknown-case-key`, `invalid-unknown-message-key`, `invalid-unknown-grader-key`, `invalid-option-on-non-string-grader`, `invalid-key-of-other-kind`, `invalid-value-on-oneof`, `invalid-values-on-string-grader`, `invalid-option-on-jsonequals`, `invalid-option-on-json`, `invalid-option-on-judge`; not inside a schema: `json-schema-unknown-keywords-ignored`; only for a known kind: `invalid-kind-wrong-case-with-value` |
| string grader `value` present and a string | `invalid-value-not-string` (`equals`), `invalid-contains-value-not-string`, `invalid-notcontains-value-not-string`, `invalid-startswith-value-not-string`, `invalid-endswith-value-not-string`, `invalid-value-missing` (`contains`), `invalid-equals-value-missing`, `invalid-notcontains-value-missing`, `invalid-startswith-value-missing`, `invalid-endswith-value-missing` |
| options are booleans | `invalid-option-not-boolean`, `invalid-option-null`, `invalid-oneof-trim-not-boolean`, `invalid-contains-ignorecase-not-boolean` |
| `oneOf.values` a non-empty array of strings | `invalid-oneof-values-missing`, `invalid-oneof-values-not-array`, `invalid-oneof-empty-values`, `invalid-oneof-value-not-string` |
| `length`: at least one bound, integers `>= 0`, `min <= max` | `invalid-length-no-bounds`, `invalid-length-string-bound`, `invalid-length-boolean-bound`, `invalid-length-null-bound`, `invalid-length-fractional`, `invalid-length-negative`, `invalid-length-negative-max`, `invalid-length-min-greater-than-max`; valid: `length-min-equals-max`, `length-integral-float-bound`, `length-exponent-bound`, `length-single-bound` |
| `json.schema` passes `checkSchema` | `invalid-json-missing-schema`, `invalid-json-schema-null`, `invalid-json-schema-array`, `invalid-json-schema` |
| `jsonEquals` has the key `value` (`null` allowed) | `invalid-jsonequals-missing-value`; valid: `jsonequals-null-value` |
| `judge.rubric` a non-empty string | `invalid-judge-missing-rubric`, `invalid-judge-rubric-not-string`, `invalid-judge-empty-rubric`; valid: `judge-whitespace-rubric-valid` |
| target ids non-empty, unique, not `judge` | `invalid-empty-target-id`, `invalid-duplicate-target-id`, `invalid-target-id-judge`, `invalid-target-id-judge-with-judge`; valid: `no-targets-empty-run` |

## Fixtures

162 fixtures. Unless stated otherwise each fixture has one target `a` and no judge; "1/3" and "2/3" are the `passRate` doubles `0.3333333333333333` and `0.6666666666666666`. Every `invalid-*` fixture expects `{"invalidSuite": true}` and zero calls.

| Fixture | Rule it pins | Expected |
|---|---|---|
| `equals-exact` | `equals` without options compares exactly (no implicit trim, no implicit case folding) | `Paris` pass; ` Paris`, `paris` fail; 1/3 |
| `equals-trim-both-sides` | `trim` removes JSON whitespace from BOTH the reply and the value | reply `"  yes\n"` equals value `"\tyes "` → pass |
| `equals-trim-nbsp-kept` | `trim` removes only space, tab, LF, CR: a no-break space (U+00A0) is kept | reply `" ok "` vs `ok` with trim → fail |
| `equals-ignorecase-ascii` | `ignoreCase` folds ASCII letters on both sides | `hELLO wORLD-1` equals `Hello World-1` → pass |
| `equals-ignorecase-non-ascii` | `ignoreCase` folds ASCII only: `É`/`é` and the Kelvin sign U+212A/`k` do not match | both fail; 0 |
| `equals-ignorecase-sharp-s` | `ß` never matches `SS` and `ẞ` (U+1E9E) never matches `ß` under `ignoreCase` | both fail; 0 |
| `equals-ignorecase-turkish-i` | `İ` never matches `i` and `ı` never matches `I`; ASCII `TITLE`/`title` still match | fail, fail, pass; 1/3 |
| `contains-empty-value` | `contains`, `startsWith`, `endsWith` with `""` always pass (also on an empty reply) | all pass; 1 |
| `notcontains-empty-value` | `notContains ""` always fails (also on an empty reply) | both fail; 0 |
| `contains-trim-value-to-empty` | clarification 6: a value that `trim` empties behaves as `""` (`contains` passes, `notContains` fails) | `contains` pass, `notContains` fail → fail |
| `contains-ignorecase` | `contains`/`notContains` with `ignoreCase` fold both sides; without it the match is case-sensitive | pass, fail, fail → fail |
| `startswith-endswith` | `startsWith`/`endsWith` anchor at the ends; `trim` and `ignoreCase` apply to them | c1: pass, pass, fail, fail, pass; c2: pass, pass, fail, fail |
| `oneof-trim-candidates` | `oneOf` with `trim` trims the reply and EACH candidate; without `trim` nothing is trimmed | pass, fail; 0.5 |
| `oneof-ignorecase` | `oneOf` with `ignoreCase` folds the reply and every candidate; no match fails | pass, fail, fail; 1/3 |
| `length-code-points-emoji` | `length` counts code points: two astral emoji are 2 (UTF-16 would give 4) | `😀😀` pass, `😀` fail; 0.5 |
| `length-lone-surrogate` | clarification 7: a lone surrogate is one code point and never swallows its neighbour | `x\ud83dy` = 3, `\ude00\ud83dA` = 3 → both pass |
| `length-bounds-inclusive` | `min` and `max` are inclusive | min 2 max 3: `ab`, `abc` pass; `a`, `abcd` fail; 0.5 |
| `length-single-bound` | one bound is enough; the other side is unbounded; `0` is a valid bound | `min 1` on `""` fail; `max 0` on `""` pass; 0.5 |
| `length-integral-float-bound` | clarification 2: a bound is an integer by value, so `2.0` is valid | `max 2.0` on `ab` → pass |
| `json-valid` | `json` passes when extraction succeeds (here a fenced block) and there are no issues | pass, `issues: []` |
| `json-no-json` | `json` extraction failure `no_json` (no `{`, `[` or three backticks) | fail, `[{"", no_json}]` |
| `json-invalid-json` | `json` extraction failure `invalid_json` (single quotes are not JSON) | fail, `[{"", invalid_json}]` |
| `json-schema-issues` | `json` reports the validation issues in `structured` order | fail, `/name` required then `/age` minimum |
| `jsonequals-match` | `jsonEquals` extracts (here a balanced region in prose) and deep-equals `value` | pass, `issues: []` |
| `jsonequals-null-value` | `value: null` is valid and matches the reply `null` only | `null` pass; `0` fail with `issues: []`; 0.5 |
| `jsonequals-key-order` | object equality ignores key order | pass |
| `jsonequals-number-by-value` | numbers compare by value: `1.0`, `2e0`, `-0`, `1E2` equal `1`, `2`, `0`, `100` | pass |
| `jsonequals-mismatch-no-issues` | a `jsonEquals` mismatch (different value, extra key) fails with no issues | both fail, `issues: []`; 0 |
| `jsonequals-no-coercion` | no coercion between kinds: `"1"` and `true` are not `1` | both fail, `issues: []`; 0 |
| `jsonequals-extraction-failure` | `jsonEquals` reports extraction failures as `issues` | `no_json`, then `invalid_json` (unbalanced); 0 |
| `judge-pass` | verdict `PASS` → `passed true`; the request carries rubric, last user message and reply in order | pass; judge called once |
| `judge-fail` | verdict `FAIL` followed by `:` → `passed false` | `FAIL: too curt` → fail |
| `judge-leading-whitespace` | leading JSON whitespace is removed before reading the verdict | `" \n\t\r PASS"` → pass |
| `judge-pass-dot` | `PASS` followed by a non-word ASCII character is a verdict | `PASS.`, `PASS: polite enough` → pass |
| `judge-pass-non-ascii-letter` | clarification 5: only `[A-Za-z0-9_]` blocks the verdict, so `PASSé` is `PASS` | `PASSé` → pass |
| `judge-word-char-is-bad-verdict` | a digit or underscore right after the keyword is not a verdict | `PASS1`, `FAIL_x` → `bad_verdict` |
| `judge-passed-is-bad-verdict` | `PASSED` and `Passing` are not verdicts | both `bad_verdict` → error |
| `judge-lowercase-is-bad-verdict` | the verdict is case-sensitive | `pass`, `fail` → `bad_verdict` |
| `judge-empty-reply-bad-verdict` | an empty or whitespace-only judge reply is not a verdict | `""`, `"   "` → `bad_verdict` |
| `judge-nbsp-not-trimmed` | clarification 5: only JSON whitespace is removed before the verdict; a leading no-break space stays | `" PASS"` → `bad_verdict` |
| `judge-verdict-must-lead` | the verdict must be at the start: `Verdict: PASS` is not one | `bad_verdict` |
| `judge-missing-no-call` | no `judge` provider → the judge grader errors with `no_judge`; the other graders still run; no `judge` key in `calls` | `no_judge`, contains pass → error |
| `judge-provider-error` | a judge provider error → `error: <kind>`; the failing call is counted | `rate_limit` → error; judge 1 |
| `judge-script-exhausted` | an exhausted judge script fails with `unavailable` and the call is counted | c1 pass, c2 `unavailable`; judge 2 |
| `judge-no-user-message` | a case with no `user` message still gets its judge call and nothing crashes (the empty-string stand-in itself is pinned by unit tests) | pass; judge 1 |
| `judge-two-graders-script-order` | two judge graders in one case make two calls in grader order | `FAIL`, `PASS` → [false, true] → fail; judge 2 |
| `judge-order-case-major-target-minor` | judge calls follow case order, then target order (targets `a`, `b`) | `PASS,FAIL,PASS,FAIL` → c1a pass, c1b fail, c2a pass, c2b fail |
| `judge-request-shape` | the judge request uses the LAST `user` message (not the last message, not the first user message) | pass; `judgeContains` rubric, `Second question`, reply |
| `judge-whitespace-rubric-valid` | clarification 1: a rubric of one space is a non-empty string, so it is valid | pass; judge 1 |
| `status-all-pass` | every grader passes → `pass` | pass |
| `status-fail-beats-pass` | one failed grader and no errored grader → `fail` | fail |
| `status-error-beats-fail` | an errored grader after a failed one → `error` (not the first non-pass outcome) | fail, `no_judge` → error |
| `provider-error-skips-graders` | a provider error → `status error`, `error` kind, no `reply`, `graders: []`, no judge call | `timeout`; judge 0 |
| `provider-error-continues-run` | after a provider error the run continues with the next pair | c1 `rate_limit`, c2 pass; 0.5 |
| `provider-script-exhausted` | an exhausted target script fails with `unavailable` and the call is counted | c1 pass, c2 `unavailable`; a 2 |
| `no-short-circuit` | all graders run after a failure, including a judge call | fail, pass, pass → fail; judge 1 |
| `multi-target-order` | results are ordered by case, then target; each target consumes its own script (targets `a`, `b`) | c1a pass, c1b fail, c2a pass, c2b pass |
| `calls-lists-every-target` | clarification 9: `calls` lists every target, counting failing calls (error and exhausted script) | a pass, b `timeout`, c `unavailable`; 1 call each |
| `calls-judge-key-zero` | a supplied judge that is never called still appears in `calls` with 0 | pass; judge 0 |
| `no-targets-empty-run` | clarification 3: an empty target list is valid and gives empty results, summary and calls | `[]`, `[]`, `{}` |
| `empty-messages-allowed` | clarification 1: `messages` may be an empty array; the call is made with it | pass |
| `json-schema-unknown-keywords-ignored` | unknown keys are not checked inside `json.schema`: unknown schema keywords stay ignored (`structured`) | `format`/`pattern` ignored → pass, `issues: []` |
| `string-graders-ascii-fold-all-kinds` | clarification 6: ASCII-only `ignoreCase` on `contains`, `notContains`, `startsWith`, `endsWith`, `oneOf`: `CAFÉ`/`café` and U+212A/`k` never match (one grader per case) | only the two `notContains` pass; 10 total, 2 passed, `0.2` |
| `string-graders-nbsp-not-trimmed-all-kinds` | clarification 6: `trim` keeps a no-break space (U+00A0) on `contains` (value), `startsWith`, `endsWith`, `oneOf` (reply) | all 4 fail; 0 |
| `equals-trim-cr` | `trim` removes CR (and LF); without `trim` a trailing CRLF stays | `"ok\r\n"`: with trim pass, without fail; 0.5 |
| `equals-no-normalization-bom-crlf` | the reply is not normalized: a BOM is kept and CRLF is not LF | `"﻿ok"` ≠ `ok`, `"a\r\nb"` ≠ `"a\nb"`; 0 |
| `endswith-ignorecase` | `endsWith` with `ignoreCase` folds both the reply and the value (`WORLD` vs `World`); without it the match is case-sensitive | pass, fail; 0.5 |
| `startswith-endswith-trim-value` | `trim` applies to the VALUE of `startsWith` and `endsWith` (`" Hi "` → `Hi`) | both pass; 1 |
| `equals-empty-value` | `equals ""` matches only an empty reply | `""` pass, `x` fail; 0.5 |
| `notcontains-empty-reply` | `notContains` with a non-empty value passes on an empty reply | pass |
| `length-min-only-max-only-pass` | a missing bound is unbounded (not 0): `min 1` passes on `a`, `max 5` on the empty reply | both pass; 1 |
| `length-min-equals-max` | `min == max` is valid and allows exactly that length | `ab` pass, `abc`, `a` fail; 1/3 |
| `length-exponent-bound` | clarification 2: `1e2` is the integer 100 by value, so it is a valid bound (literal kept in the file) | `max 1e2` on `abc` → pass |
| `jsonequals-array-order` | arrays compare in order: `[2, 1]` is not `[1, 2]` | fail, `issues: []` |
| `jsonequals-nested-key-order` | key order is ignored at every depth (objects inside objects and arrays) | pass |
| `jsonequals-float-value` | clarification 2: numbers by value also in the suite: `value: 1.0` (literal kept in the file) equals the reply `1` | pass |
| `jsonequals-string-value` | a string `value` needs a JSON string reply: raw `Paris` is `no_json`, `"Paris"` matches | fail with `no_json`, pass; 0.5 |
| `judge-two-graders-two-targets-script-order` | inside one case all graders of target `a` run before target `b` (not grader-major) | `PASS,FAIL,PASS,PASS` → a [true,false] fail, b [true,true] pass; judge 4 |
| `judge-continues-after-judge-error` | a judge error does not stop the next judge grader or the other graders | `timeout`, PASS, contains pass → error; judge 2 |
| `judge-first-call-for-second-target` | the first judge request belongs to the first pair that got a reply (here case `c1`, target `b`, since `a` failed), with that case and reply | c1a `timeout`, c1b pass, c2a fail, c2b pass; judge 3 |
| `status-error-then-fail` | an errored grader before a failed one → `error` (not the last non-pass outcome) | `no_judge`, fail → error |
| `summary-pass-rate-zero` | `passRate` 0 with one failed and one errored result | a: 2, 0, 1, 1, `0` |
| `summary-pass-rate-one-third` | `passRate` = 1/3 as the exact double | a: 3, 1, 1, 1, `0.3333333333333333` |
| `summary-pass-rate-two-thirds` | `passRate` = 2/3 as the exact double | a: 3, 2, 1, 0, `0.6666666666666666` |
| `summary-pass-rate-one` | `passRate` 1 when every result passes | a: 2, 2, 0, 0, `1` |
| `summary-first-appearance-order` | summary entries follow the first appearance of each target, not alphabetical order | `zeta` then `alpha` |
| `invalid-empty-cases` | `cases` must be non-empty | `invalidSuite`, 0 calls |
| `invalid-cases-missing` | `cases` is required | `invalidSuite`, 0 calls |
| `invalid-cases-not-array` | `cases` must be an array | `invalidSuite`, 0 calls |
| `invalid-duplicate-case-id` | case ids are unique | `invalidSuite`, 0 calls |
| `invalid-empty-case-id` | case ids are non-empty | `invalidSuite`, 0 calls |
| `invalid-case-id-not-string` | clarification 1: a case id is a string | `invalidSuite`, 0 calls |
| `invalid-no-graders` | each case has at least one grader | `invalidSuite`, 0 calls |
| `invalid-graders-missing` | `graders` is required | `invalidSuite`, 0 calls |
| `invalid-grader-not-object` | clarification 1: a grader is an object | `invalidSuite`, 0 calls |
| `invalid-no-messages` | `messages` is required | `invalidSuite`, 0 calls |
| `invalid-messages-not-array` | `messages` is an array | `invalidSuite`, 0 calls |
| `invalid-message-role` | clarification 1: `role` is `system`, `user` or `assistant` | `invalidSuite`, 0 calls |
| `invalid-message-content-not-string` | clarification 1: `content` is a string | `invalidSuite`, 0 calls |
| `invalid-target-id-judge` | clarification 4: the target id `judge` is reserved, even without a judge | `invalidSuite`, 0 calls |
| `invalid-duplicate-target-id` | target ids are unique | `invalidSuite`, 0 calls |
| `invalid-empty-target-id` | target ids are non-empty | `invalidSuite`, 0 calls |
| `invalid-unknown-kind` | no unknown grader kind (`regex` is a non-goal) | `invalidSuite`, 0 calls |
| `invalid-kind-wrong-case` | clarification 1: `kind` is case-sensitive (`Contains` is unknown) | `invalidSuite`, 0 calls |
| `invalid-missing-kind` | clarification 1: `kind` is required | `invalidSuite`, 0 calls |
| `invalid-value-not-string` | `value` of a string grader must be a string | `invalidSuite`, 0 calls |
| `invalid-value-missing` | `value` of a string grader is required | `invalidSuite`, 0 calls |
| `invalid-option-not-boolean` | options are booleans (`"true"` is not) | `invalidSuite`, 0 calls |
| `invalid-option-null` | clarification 1: a present option must be a boolean; `null` is not absent | `invalidSuite`, 0 calls |
| `invalid-oneof-empty-values` | `oneOf` needs a non-empty `values` | `invalidSuite`, 0 calls |
| `invalid-oneof-value-not-string` | every `oneOf` candidate is a string | `invalidSuite`, 0 calls |
| `invalid-length-no-bounds` | `length` needs at least one bound | `invalidSuite`, 0 calls |
| `invalid-length-min-greater-than-max` | `min <= max` | `invalidSuite`, 0 calls |
| `invalid-length-negative` | bounds are `>= 0` | `invalidSuite`, 0 calls |
| `invalid-length-fractional` | clarification 2: bounds are integers (`1.5` is not) | `invalidSuite`, 0 calls |
| `invalid-length-null-bound` | clarification 1: a present bound must be an integer; `null` is not absent | `invalidSuite`, 0 calls |
| `invalid-json-schema` | `schema` must pass `checkSchema` (unknown `type`) | `invalidSuite`, 0 calls |
| `invalid-json-missing-schema` | `json` needs a `schema` | `invalidSuite`, 0 calls |
| `invalid-jsonequals-missing-value` | `jsonEquals` needs the key `value` | `invalidSuite`, 0 calls |
| `invalid-judge-empty-rubric` | `rubric` is a non-empty string (a judge is supplied) | `invalidSuite`, 0 calls |
| `invalid-judge-missing-rubric` | `judge` needs a `rubric` (a judge is supplied) | `invalidSuite`, 0 calls |
| `invalid-unknown-suite-key` | unknown keys: the suite object accepts only `cases` (the whole suite) | `invalidSuite`, 0 calls |
| `invalid-unknown-case-key` | unknown keys: a case accepts only `id`, `messages`, `graders` | `invalidSuite`, 0 calls |
| `invalid-unknown-message-key` | unknown keys: a message accepts only `role` and `content` | `invalidSuite`, 0 calls |
| `invalid-unknown-grader-key` | unknown keys: a grader accepts only its kind's keys (the typo `ignorecase` on `equals`) | `invalidSuite`, 0 calls |
| `invalid-option-on-non-string-grader` | unknown keys: `trim` is not a key of `length` | `invalidSuite`, 0 calls |
| `invalid-key-of-other-kind` | unknown keys: `value` is not a key of `length` | `invalidSuite`, 0 calls |
| `invalid-value-on-oneof` | unknown keys: `value` is not a key of `oneOf` | `invalidSuite`, 0 calls |
| `invalid-values-on-string-grader` | unknown keys: `values` is not a key of `contains` | `invalidSuite`, 0 calls |
| `invalid-option-on-jsonequals` | unknown keys: `trim` is not a key of `jsonEquals` | `invalidSuite`, 0 calls |
| `invalid-option-on-json` | unknown keys: `ignoreCase` is not a key of `json` | `invalidSuite`, 0 calls |
| `invalid-option-on-judge` | unknown keys: `trim` is not a key of `judge` (a judge is supplied) | `invalidSuite`, 0 calls |
| `invalid-kind-wrong-case-with-value` | `Contains` with a well-formed `value` is still an unknown kind (unknown keys are checked only for a known kind, so `value` is not a second violation) | `invalidSuite`, 0 calls |
| `invalid-suite-null` | the suite is a JSON object (`null` is not) | `invalidSuite`, 0 calls |
| `invalid-suite-array` | the suite is a JSON object (an array is not) | `invalidSuite`, 0 calls |
| `invalid-case-not-object` | a case is an object | `invalidSuite`, 0 calls |
| `invalid-case-id-missing` | a case needs an `id` | `invalidSuite`, 0 calls |
| `invalid-graders-not-array` | `graders` is an array | `invalidSuite`, 0 calls |
| `invalid-message-not-object` | a message is an object | `invalidSuite`, 0 calls |
| `invalid-message-missing-role` | a message needs a `role` | `invalidSuite`, 0 calls |
| `invalid-message-missing-content` | a message needs a `content` | `invalidSuite`, 0 calls |
| `invalid-message-role-null` | `role` is a string (`null` is not) | `invalidSuite`, 0 calls |
| `invalid-kind-not-string` | `kind` is a string (`5` is not) | `invalidSuite`, 0 calls |
| `invalid-contains-value-not-string` | `value` of `contains` must be a string | `invalidSuite`, 0 calls |
| `invalid-notcontains-value-not-string` | `value` of `notContains` must be a string | `invalidSuite`, 0 calls |
| `invalid-startswith-value-not-string` | `value` of `startsWith` must be a string | `invalidSuite`, 0 calls |
| `invalid-endswith-value-not-string` | `value` of `endsWith` must be a string | `invalidSuite`, 0 calls |
| `invalid-equals-value-missing` | `value` of `equals` is required | `invalidSuite`, 0 calls |
| `invalid-startswith-value-missing` | `value` of `startsWith` is required | `invalidSuite`, 0 calls |
| `invalid-endswith-value-missing` | `value` of `endsWith` is required | `invalidSuite`, 0 calls |
| `invalid-notcontains-value-missing` | `value` of `notContains` is required | `invalidSuite`, 0 calls |
| `invalid-oneof-values-missing` | `oneOf` needs `values` | `invalidSuite`, 0 calls |
| `invalid-oneof-values-not-array` | `oneOf.values` is an array | `invalidSuite`, 0 calls |
| `invalid-oneof-trim-not-boolean` | `oneOf.trim` is a boolean | `invalidSuite`, 0 calls |
| `invalid-contains-ignorecase-not-boolean` | `contains.ignoreCase` is a boolean (`1` is not) | `invalidSuite`, 0 calls |
| `invalid-length-string-bound` | bounds are numbers (`"3"` is not) | `invalidSuite`, 0 calls |
| `invalid-length-boolean-bound` | bounds are numbers (`true` is not) | `invalidSuite`, 0 calls |
| `invalid-length-negative-max` | `max` is `>= 0` | `invalidSuite`, 0 calls |
| `invalid-json-schema-null` | `schema` is an object (`null` is not) | `invalidSuite`, 0 calls |
| `invalid-json-schema-array` | `schema` is an object (`[]` is not) | `invalidSuite`, 0 calls |
| `invalid-judge-rubric-not-string` | `rubric` is a string (`5` is not; a judge is supplied) | `invalidSuite`, 0 calls |
| `invalid-target-id-judge-with-judge` | clarification 4: the target id `judge` is reserved also when a judge is supplied | `invalidSuite`, 0 calls |
