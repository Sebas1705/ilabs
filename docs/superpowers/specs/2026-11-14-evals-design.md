# `evals` module — design

Status: draft for owner approval · Date: 2026-11-14 · Parent: [2026-11-11-ilabs-design.md](2026-11-11-ilabs-design.md)

## Intent

Compare models and prompts on a small suite, deterministically. A suite is a list of cases (a conversation plus graders); `evals` runs every case against one or more `Provider`s, grades each reply, and reports who passed what. It is the fourth iLabs module and the last of the first batch (after `providers`, `structured` and `context`): it builds on `providers` for the calls and on `structured` for the JSON graders.

## Goals / non-goals

- Same behavior in Kotlin (KMP) and TypeScript, pinned by shared fixtures in `contracts/evals/`, like the previous modules.
- Deterministic only: validation, grading, status rules, run bookkeeping (order, call counts), aggregation and the judge protocol (request shape, verdict parsing) are in the contract. Real model output and the judge instruction wording are NOT.
- Non-goals: regular expressions (the ECMAScript and Java dialects differ at the edges: `$`, `\w`, case folding; a hand-written matcher can come later), concurrency or retries (compose `RetryingProvider`/`FallbackProvider` from `providers` around a target), scoring weights, cost/latency metrics, persistence of results.

## Rules (my calls after the builder check, to confirm)

**Suite and run.** `runSuite(suite, { targets, judge? })`; `suite = { cases: [{ id, messages, graders }] }`; each target is a `Provider` (its `id` names it in the report); `judge` is an optional `Provider`. The suite is VALIDATED COMPLETELY BEFORE ANY CALL; on any problem `runSuite` throws `invalid suite: <reason>` (`Error` in TypeScript, `IllegalArgumentException` in Kotlin; the prefix is pinned, the reason wording is not) and no provider is called. Rules: `cases` non-empty; case ids non-empty and unique; each case has `messages` (an array of `{role, content}`) and at least one grader; target ids unique, non-empty and not equal to `judge` (reserved); no unknown grader kind; grader parameters well-formed (below); only when parsed from JSON (`parseSuite`), no unknown key in the suite (only `cases`), a case (`id`, `messages`, `graders`), a message (`role`, `content`) or a grader of a known kind (`kind` plus its own kind's keys, so `trim` on `length` is invalid), not checked inside `json.schema` or `jsonEquals.value` (an unknown key also fails with `invalid suite: <reason>`). Execution is sequential: case order, then target order; one provider call per (case, target) with exactly `case.messages`. A suite given as JSON (fixtures, demos) goes through `parseSuite(json)`, which validates everything except the targets and the judge and returns the typed suite (in Kotlin the graders are a sealed type).

**Graders** — each yields `{ kind, passed, issues?, error? }`; all graders of a case are evaluated in order on the reply text exactly as returned (no normalization), with no short circuit:
- String graders: `equals { value, trim?, ignoreCase? }`, `contains`, `notContains`, `startsWith`, `endsWith` (same options), `oneOf { values, trim?, ignoreCase? }`. `value` must be a string, `values` a non-empty array of strings, the options booleans (default false). `trim` removes only JSON whitespace (space, tab, LF, CR) from BOTH the reply and the compared value(s) (each candidate in `oneOf`); then `ignoreCase` folds ASCII letters only (A–Z ↔ a–z) on both sides (every other character is compared as is: `ß`, `İ` and `ı` never match their Latin counterparts). `contains/startsWith/endsWith` with `""` always pass; `notContains ""` always fails.
- `length { min?, max? }`: at least one bound; integers `>= 0`; `min <= max`; the reply length in Unicode code points (lone surrogates count one), inclusive.
- `json { schema }`: `schema` must pass `structured`'s `checkSchema`; the reply goes through `structured`'s `extractJson` then `validate`: passes when extraction succeeds and there are no issues; `issues` = the extraction failure `[{path: "", code: "no_json" | "invalid_json"}]` or the validation issues `{path, code}`.
- `jsonEquals { value }`: the key `value` must be present (`null` is a valid JSON value, an absent key is invalid); extraction succeeds and the extracted value deep-equals `value` (`structured`'s equality); `issues` as above for extraction failures, none for a mismatch.
- `judge { rubric }`: `rubric` a non-empty string. ONE judge call per judge grader (grader order, case-major then target-minor), every call counted (including a failing one or an exhausted script). The request has exactly 2 messages and no other fields: a non-empty `system` instruction, then a `user` message that contains, in this order, the `rubric`, the content of the case's LAST `user` message (the empty string if there is none) and the reply (wording and labels not pinned). Verdict: remove leading JSON whitespace from the judge reply; it must start with `PASS` or `FAIL` (case-sensitive) and be followed by the end of the text or a character that is not an ASCII letter, digit or underscore (`PASS.` and `PASS: ok` are verdicts, `PASSED`, `pass` and `Passing` are not). `PASS` → `passed true`; `FAIL` → `passed false`; anything else → the grader errors with `error: "bad_verdict"`. No `judge` provider supplied → `error: "no_judge"` (no call). A judge provider error → `error: <the provider error kind>`. An errored grader entry has `passed false`.

**Result of a (case, target).** `{ caseId, targetId, status, reply?, error?, graders }`:
- the provider call fails with a provider error → `status "error"`, `error` = the error kind (a string), no `reply`, `graders: []` (so no judge call is made); the run continues with the next pair; a failing call counts in `calls`;
- otherwise `reply` = the provider's `text` verbatim and `graders` has one entry per grader; `status` is `"error"` if any grader errored, else `"fail"` if any grader failed, else `"pass"`;
- an exception that is not a provider error (and, in Kotlin, `CancellationException` or `Error`) propagates and aborts the run.

**Summary and calls.** `summarize(results)` → one entry per target in order of first appearance: `{ targetId, total, passed, failed, errored, passRate }` with `passRate = passed / total` as an IEEE-754 double (an empty list gives an empty summary; `total` is never 0 for a listed target). Compared with exact equality; fixtures write the shortest round-trip double literal (`0.6666666666666666`). `runSuite` returns `{ results, summary, calls }`; `calls` has EVERY target id with its call count (0 when none) and the key `judge` only when a judge was supplied (value 0 when never called).

**Playground function.** `evaluateGrader(grader, reply)` is the pure, exported form of one non-judge grader (the demos use it).

**Fixtures.** `contracts/evals/<name>.json`: `{ name, suite, targets: [{id, script}], judge?: {script}, expect: { results: [{caseId, targetId, status, reply?, error?, graders?: [{kind, passed, issues?, error?}]}] , summary, calls, judgeContains?: [...] } }` or `{ name, suite, targets, judge?, expect: { invalidSuite: true } }` for validation cases (no provider is called: `calls` all 0); targets and judge reuse the providers mock script format; `reply` is compared when present; `graders`, when present, must list all graders in order and compares `kind`, `passed`, `error` and `issues` when expected; `judgeContains` are ordered non-overlapping substrings of the `user` message of the judge request of the first judge call (and the harness rule: 2 messages, `system` then `user`, non-empty, no other fields, for every judge call). About 55 fixtures: one per grader edge (trim both sides, ASCII-only case folding incl. `ß`/`İ`, empty strings, code points in `length`, extraction failures and schema issues, `jsonEquals` with `null`), status rules (fail beats pass, error beats fail, provider error skips graders and judge calls, no judge supplied), judge protocol (PASS/FAIL/leading whitespace/`PASS.`/`PASSED`/lowercase, no user message, several judge graders consuming the script in order across cases and targets, exhausted script), ordering (case-major then target-minor), multiple targets, summary arithmetic (`passRate` 0, 1/3, 2/3, 1), validation failures (each rule above → `invalidSuite`, zero calls).

## Demos

A web page and an Android screen using the live implementations with the fixture suites as samples. (1) **Run a suite**: pick a sample suite and see the cases × targets matrix with pass/fail/error badges (glyph + text), per-target pass rate with a bar and the counts, and a detail view per cell (the reply, each grader with its outcome, `error` and `issues`). (2) **Grader playground**: choose a pure grader (everything except `judge`), set its parameter (text; one value per line for `oneOf`; numbers for `length`; raw JSON text for `schema` and `value`) and type a reply: the verdict (`evaluateGrader`) updates live with the issues; invalid parameter text shows a translated parameter error instead of a verdict. es + en, light/dark, same shell as the other modules.

## Approaches considered

- **A. Hand-implemented grader set without regex, shared fixtures (chosen):** identical in both languages; the judge makes it usable with real models. Less expressive than regex.
- **B. Include `regex` through each platform's engine:** more expressive, but results can differ at the edges, which breaks the same-results contract.
- **C. A fuller framework (concurrency, retries, weights, metrics):** useful, but each piece is its own contract; compose `providers` instead.

## Left to the builder

File layout, error type names, the judge instruction wording, UI copy, the matrix layout and how the playground is rendered.
