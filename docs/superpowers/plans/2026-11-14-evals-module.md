# `evals` Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Run independent tasks in parallel** (see "Execution order"): each parallel implementer works in its own git worktree and must FIRST verify that its base is the commit named in its dispatch (worktrees are not always cut from the current branch tip: `git merge-base --is-ancestor <sha> HEAD`, else `git merge --ff-only <full sha>`), and its commits are cherry-picked onto the branch after review.

**Goal:** Ship the fourth iLabs module, `evals` (run a suite of cases against providers and grade the replies deterministically), in Kotlin and TypeScript, pinned by shared fixtures and shown in a web page and an Android screen.

**Architecture:** `contracts/evals/` holds the spec and fixtures. Each language implements `parseSuite`, `evaluateGrader`, `runSuite`, `summarize` and a `runFixture` harness on top of `providers` (`Provider`, `MockProvider`, errors) and `structured` (`extractJson`, `validate`, `checkSchema`, deep equality). Contract tests run every fixture. Demos use the live implementations with the fixtures as sample suites and a grader playground.

**Tech Stack:** as the previous modules: Kotlin Multiplatform (Android + JVM), TypeScript (pnpm workspace, Vitest), Vite + React, Compose + Material 3, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-14-evals-design.md` (binding). Read the sibling modules for conventions (modules/context/{ts,kotlin}, contracts/context/README.md, their demos, `modules/*/kotlin/**/contract/Contract.kt` harness pattern and the shared web fixture loader `demos/web/src/fixtureLoader.ts`): fixture runner pattern, aggregated Kotlin contract failures, `invalid fixture:` errors, test guard `>= <real fixture count>`, the harness test rules (calls required for fixtures that make calls, unknown fixture keys rejected).

**Base:** this plan starts from the branch that already contains the cleanup (Kotlin harness in `...contract` subpackages, shared web fixture loader, `DEFAULT_KEEP_RECENT`): the dispatch of every task names the exact base commit.

## Global Constraints

- Public repo `Sebas1705/ilabs`, MIT; no secrets, no local paths or usernames in tracked files. Commits conventional, no trailers (no Co-Authored-By, no "Generated with"); do NOT push; push/PR/tag only when the owner asks.
- Repo content in English (code, docs, commits); demo UI texts in es + en (identical key sets; Android keys prefixed `evals_`).
- Same results in Kotlin and TS = deterministic logic only; the judge instruction wording and the transcript-like wording are NOT in the contract.
- Kotlin targets Android + JVM only; Gradle project `:modules:evals-kotlin` (dir `modules/evals/kotlin`), package `es.sebas1705.ilabs.evals` (harness in `es.sebas1705.ilabs.evals.contract`); TS package `@ilabs/evals` (dir `modules/evals/ts`).
- No regular expressions anywhere in graders (non-goal); ASCII-only case folding; code points, not UTF-16 units.
- Demos work offline with the mock provider; no keys, no network.
- Tooling on this machine: pnpm via `corepack pnpm`; Kotlin builds need JDK 21 (`JAVA_HOME`) and the Android SDK (`ANDROID_HOME` or `local.properties`).
- CI aggregates Kotlin module tests through `./gradlew jvmContractTests` (picks `:modules:*-kotlin` automatically); do not edit CI per module.

## Review Focus

1. Validation happens before ANY provider call: every `invalid suite` rule (empty cases, duplicate/empty ids, target id `judge`, missing graders, unknown kind, malformed parameters incl. a malformed `json` schema, `jsonEquals` without `value`, empty `values`, `length` with bad bounds) throws with zero calls (Tasks 1/2/3).
2. String graders: `trim` on BOTH sides (each `oneOf` candidate), ASCII-only folding (`ß`, `İ`, `ı` never match Latin letters), empty-string edges (`contains ""` passes, `notContains ""` fails), `length` in code points incl. a lone surrogate (Tasks 1/2/3).
3. Judge protocol: exact verdict grammar (`PASS`/`FAIL` case-sensitive, leading JSON whitespace removed, followed by end or a non-`[A-Za-z0-9_]` character: `PASS.` ok; `PASSED`, `pass`, `Passing` → `bad_verdict`), `no_judge` without a judge and without a call, request shape (2 messages, no other fields), one call per judge grader in grader order (case-major, target-minor), every call counted incl. failing or exhausted scripts, no user message → empty string (Tasks 1/2/3).
4. Statuses: `error` beats `fail` beats `pass`; a provider error gives `graders: []`, no judge calls, and the run continues; all graders are evaluated with no short circuit; a non-provider exception aborts (CancellationException never swallowed) (Tasks 2/3).
5. Summary and calls: `passRate` is the exact double `passed/total` (`0`, `1/3` → `0.3333333333333333`, `2/3` → `0.6666666666666666`, `1`), targets in order of first appearance, `calls` lists every target (0 when none) and `judge` only when a judge was supplied (Tasks 1/2/3).

---

## Contract reference (used by Tasks 1-6)

The binding rules are the "Rules" section of the spec. Fixture shape (`contracts/evals/<name>.json`):

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

or `{ "name", "suite", "targets", "judge"?, "expect": { "invalidSuite": true } }` for validation cases (all `calls` are 0 and none is made: the harness asserts the recorded call counts are 0). `judge` is `{ "script": [...] }` (a mock provider, id fixed by the harness); `expect.calls` has every target id and `judge` iff `judge` is present; `expect.judgeContains` (ordered non-overlapping substrings of the `user` message of the first judge request) and the harness rule (every judge request has exactly 2 messages, `system` then `user`, non-empty content, no other fields). Comparison: results in order by (case, target); `status` exact; `reply`, `error` compared when present; `graders` when present must list ALL graders of the case in order comparing `kind`, `passed`, and `error`/`issues` when present; `summary` entries by exact equality incl. `passRate` as an exact double.

---

### Task 1: `evals` contract and fixtures (first; run alone)

**Files:**
- Create: `contracts/evals/README.md` (the spec's Rules as the contract, the fixture shape and comparison rules above, a Clarifications section, a fixture table with one row per fixture and the single rule it pins), `contracts/evals/*.json` (about 55 fixtures)

**Interfaces:**
- Produces: fixtures consumed by Tasks 2-6. Names (each one rule), at least: graders — `equals-exact`, `equals-trim-both-sides`, `equals-ignorecase-ascii`, `equals-ignorecase-sharp-s`, `equals-ignorecase-turkish-i`, `contains-empty-value`, `notcontains-empty-value`, `contains-ignorecase`, `startswith-endswith`, `oneof-trim-candidates`, `oneof-ignorecase`, `length-code-points-emoji`, `length-lone-surrogate`, `length-bounds-inclusive`, `json-valid`, `json-no-json`, `json-invalid-json`, `json-schema-issues`, `jsonequals-match`, `jsonequals-null-value`, `jsonequals-key-order`, `jsonequals-number-by-value`, `jsonequals-extraction-failure`; judge — `judge-pass`, `judge-fail`, `judge-leading-whitespace`, `judge-pass-dot`, `judge-passed-is-bad-verdict`, `judge-lowercase-is-bad-verdict`, `judge-missing-no-call`, `judge-provider-error`, `judge-script-exhausted`, `judge-no-user-message`, `judge-two-graders-script-order`, `judge-order-case-major-target-minor`, `judge-request-shape`; status/run — `status-all-pass`, `status-fail-beats-pass`, `status-error-beats-fail`, `provider-error-skips-graders`, `provider-error-continues-run`, `provider-script-exhausted`, `no-short-circuit`, `multi-target-order`, `calls-lists-zero-target`, `calls-judge-key-zero`; summary — `summary-pass-rate-zero`, `summary-pass-rate-one-third`, `summary-pass-rate-two-thirds`, `summary-pass-rate-one`, `summary-first-appearance-order`; validation (`invalidSuite`) — `invalid-empty-cases`, `invalid-duplicate-case-id`, `invalid-empty-case-id`, `invalid-no-graders`, `invalid-no-messages`, `invalid-target-id-judge`, `invalid-duplicate-target-id`, `invalid-unknown-kind`, `invalid-oneof-empty-values`, `invalid-length-no-bounds`, `invalid-length-min-greater-than-max`, `invalid-length-negative`, `invalid-json-schema`, `invalid-jsonequals-missing-value`, `invalid-judge-empty-rubric`, `invalid-value-not-string`; add extras for rules you find unpinned (state them in the README).

- [ ] **Step 1: Write `contracts/evals/README.md`** (the spec's Rules adapted, English, plus the fixture format and comparison rules and a fixture table).
- [ ] **Step 2: Write the fixtures**, deriving every expectation by hand from the rules (statuses, grader outcomes incl. `issues` with `structured`'s `{path, code}`, `calls`, `summary` with exact double literals such as `0.3333333333333333` and `0.6666666666666666`). Keep conversations and replies short; for the judge fixtures state the judge script and the `judgeContains` substrings.
- [ ] **Step 3: Verify** every expectation with a throwaway Node verifier kept OUTSIDE the repo (an independent implementation of the rules in ~200 lines, importing nothing from the repo except reading the fixtures; it needs its own tiny extraction/validation reimplementation OR may import `@ilabs/structured` from the workspace for `extractJson`/`validate` only); run it over all fixtures and report the output; also run it against a mutated copy with 3 deliberately wrong expectations and against at least 15 known-wrong implementations (e.g. trim only the reply, Unicode case folding, UTF-16 length, accepting `PASSED`, short-circuiting graders, skipping the call when invalid, counting the judge in a target key) to prove the fixtures discriminate; fix any fixture that lets a wrong implementation pass. Check JSON well-formedness, `name` == file name, formatting (2-space indent, trailing newline, LF).
- [ ] **Step 4: Commit** `docs: add evals contract and fixtures`.

### Task 2: TypeScript `evals` (after Task 1; parallel with Task 3)

**Files:**
- Create: `modules/evals/ts/package.json` (name `@ilabs/evals`, `@ilabs/providers` and `@ilabs/structured` as `workspace:*`, same scripts/devDependencies as `@ilabs/context`), `tsconfig.json`, `src/types.ts`, `src/suite.ts`, `src/graders.ts`, `src/judge.ts`, `src/run.ts`, `src/summary.ts`, `src/contract.ts`, `src/index.ts`, tests (`contract.test.ts` + per-file unit tests)

**Interfaces:**
- Consumes: Task 1 fixtures; `Message`, `Provider`, `CompletionRequest`, `MockProvider`, `ProviderError` from `@ilabs/providers`; `extractJson`, `validate`, `checkSchema`, `jsonEqual` (export it from `@ilabs/structured` if it is not exported yet: a one-line additive export), `Issue`, `Schema` from `@ilabs/structured`.
- Produces:
  - `type PureGrader = {kind:"equals"|"contains"|"notContains"|"startsWith"|"endsWith"; value: string; trim?: boolean; ignoreCase?: boolean} | {kind:"oneOf"; values: string[]; trim?: boolean; ignoreCase?: boolean} | {kind:"length"; min?: number; max?: number} | {kind:"json"; schema: Schema} | {kind:"jsonEquals"; value: unknown}`; `type Grader = PureGrader | {kind:"judge"; rubric: string}`; `interface EvalCase { id: string; messages: Message[]; graders: Grader[] }`; `interface Suite { cases: EvalCase[] }`
  - `parseSuite(json: unknown): Suite` (throws `Error("invalid suite: <reason>")`; validates everything the spec lists except targets); `interface GraderResult { kind: string; passed: boolean; issues?: Issue[]; error?: string }`; `evaluateGrader(grader: PureGrader, reply: string): GraderResult`
  - `type Status = "pass" | "fail" | "error"`; `interface CaseResult { caseId: string; targetId: string; status: Status; reply?: string; error?: string; graders: GraderResult[] }`; `interface TargetSummary { targetId: string; total: number; passed: number; failed: number; errored: number; passRate: number }`; `summarize(results: CaseResult[]): TargetSummary[]`
  - `interface RunOptions { targets: Provider[]; judge?: Provider }`; `interface RunOutput { results: CaseResult[]; summary: TargetSummary[]; calls: Record<string, number> }`; `runSuite(suite: Suite, options: RunOptions): Promise<RunOutput>` (validates the suite AND the targets/judge ids first; zero calls on an invalid suite)
  - `runFixture(fixture: Fixture): Promise<FixtureRun>` with `FixtureRun = { invalidSuite: boolean; output: RunOutput | null; judgeRequests: CompletionRequest[] }`, browser-safe (no Node APIs outside tests); `invalid fixture: <reason>` for unusable fixtures. Export everything from `src/index.ts`.

- [ ] **Step 1: Write the failing tests:** `contract.test.ts` (every `contracts/evals/*.json`, path relative to the test file, guard `fixtures.length >= <real count>`, per-fixture `it`, the comparison rules above incl. the judge request rule, calls required for fixtures that expect calls, unknown-key rejection test like the context contract test); unit tests for `parseSuite` (every invalid rule → `invalid suite:` prefix), each pure grader, the judge verdict grammar, `summarize`, a thrown non-provider exception rethrown unchanged (run aborted), inputs not mutated. Run `corepack pnpm --filter @ilabs/evals test` → FAIL (record output).
- [ ] **Step 2: Implement** per the spec Rules. ASCII fold by mapping only `A–Z`; trim only space/tab/LF/CR; code points via `[...s].length`; graders in order with no short circuit; judge called once per judge grader in order; `calls` counted by the runner wrapper around each provider call.
- [ ] **Step 3: Run** `corepack pnpm --filter @ilabs/evals test`, `typecheck`, then `corepack pnpm -r typecheck && corepack pnpm -r test` → PASS, no noise.
- [ ] **Step 4: Commit** `feat(evals): add TypeScript implementation`.

### Task 3: Kotlin `evals` (after Task 1; parallel with Task 2)

**Files:**
- Create: `modules/evals/kotlin/build.gradle.kts` (same plugins/targets/SDK levels as the other modules, namespace `es.sebas1705.ilabs.evals`, `api(project(":modules:providers-kotlin"))` and `api(project(":modules:structured-kotlin"))`, the jvmTest input declaration for `contracts/evals` with the `*.json` filter), `src/commonMain/kotlin/es/sebas1705/ilabs/evals/{Types,Suite,Graders,Judge,Run,Summary}.kt`, `src/commonMain/kotlin/es/sebas1705/ilabs/evals/contract/Contract.kt`, `src/commonTest/.../*Test.kt`, `src/jvmTest/.../ContractTest.kt`, a `WildcardImportsTest`
- Modify: `settings.gradle.kts` (`include(":modules:evals-kotlin")` + `projectDir = file("modules/evals/kotlin")`)

**Interfaces:**
- Consumes: Task 1 fixtures; providers and structured Kotlin APIs (`Provider`, `Message`, `CompletionRequest`, `ProviderException`, `extractJson`, `validate`, `checkSchema`, `Issue`, JSON equality, `JsonElement`).
- Produces (mirror of Task 2): `sealed interface Grader` with `PureGrader` subtypes (`Equals`, `Contains`, `NotContains`, `StartsWith`, `EndsWith`, `OneOf`, `Length`, `Json`, `JsonEquals`) and `Judge(rubric)`; `data class EvalCase(id, messages, graders)`; `data class Suite(cases)`; `fun parseSuite(json: String): Suite` and `fun parseSuite(json: JsonElement): Suite` (`IllegalArgumentException("invalid suite: ...")`); `data class GraderResult(kind: String, passed: Boolean, issues: List<Issue>? = null, error: String? = null)`; `fun evaluateGrader(grader: PureGrader, reply: String): GraderResult`; `enum class Status`; `data class CaseResult(...)`; `data class TargetSummary(...)`; `fun summarize(results: List<CaseResult>): List<TargetSummary>`; `data class RunOutput(results, summary, calls: Map<String, Int>)`; `suspend fun runSuite(suite: Suite, targets: List<Provider>, judge: Provider? = null): RunOutput`; and in `commonMain` `.contract` package `parseFixture`/`suspend fun runFixture(fixture): FixtureOutcome` reused by the Android demo.

- [ ] **Step 1: Write the failing tests:** JVM `ContractTest` (walks up from `user.dir`, guard `size >= <real count>`, aggregated failures, same comparison rules and harness rules as Task 2 incl. unknown-key rejection), unit tests mirroring Task 2's, `WildcardImportsTest`. Run `./gradlew :modules:evals-kotlin:jvmTest` → FAIL (record output).
- [ ] **Step 2: Implement** per the spec Rules, mirroring Task 2 exactly (code point counting in common code, ASCII fold only A–Z, trim only space/tab/LF/CR, one call per judge grader, `calls` counted by the runner, `CancellationException` and `Error` never caught; a provider failure is `ProviderException` only).
- [ ] **Step 3: Run** `./gradlew :modules:evals-kotlin:jvmTest :modules:evals-kotlin:assemble jvmContractTests` → PASS; `./gradlew jvmContractTests --dry-run` lists four modules.
- [ ] **Step 4: Commit** `feat(evals): add Kotlin implementation`.

### Task 4: Web demo (after Task 2; parallel with Tasks 5 and 6)

**Files:**
- Create: `demos/web/src/pages/EvalsPage.tsx`, `src/components/ResultMatrix.tsx`, `src/components/GraderPlayground.tsx`, `src/evalsFixtures.ts` (through the shared `parseFixtureFiles` loader), `src/pages/EvalsPage.test.tsx`, component tests
- Modify: `demos/web/package.json` (`@ilabs/evals` as `workspace:*`; lockfile committed), `src/routes.ts` (one registry entry), `src/i18n.ts` (es + en), `src/styles.css`

**Interfaces:**
- Consumes: `runFixture`, `evaluateGrader`, `summarize`, `parseSuite`, types from `@ilabs/evals`; the shared web fixture loader, route registry, shell conventions of the existing pages.
- Produces: route `#/evals` and a nav entry; (1) **Run a suite**: fixture picker (grouped by prefix), the cases × targets matrix with pass/fail/error badges (glyph + text, never colour alone), per-target pass rate (text percentage + bar `aria-hidden`) and counts, a detail view per cell (reply, each grader with outcome, `error` and `issues`), `invalidSuite` fixtures showing the validation error state, a polite coarse status line; (2) **Grader playground**: grader kind select (all pure graders), parameter inputs (text; one value per line for `oneOf`; numbers for `length`; raw JSON text for `schema` and `value`; `trim`/`ignoreCase` checkboxes where applicable), a reply textarea, the verdict updating live via `evaluateGrader` with issues, and a translated parameter error instead of a verdict for invalid parameter text; every string in es + en.

- [ ] **Step 1: Write the failing tests:** nav to `#/evals` through the registry; selecting `status-fail-beats-pass` shows the matrix cell fail with its grader details; `provider-error-skips-graders` shows the error cell and no grader rows; a summary with `2/3` shows 67% and counts; an `invalid-*` fixture shows the validation error state; the playground: choosing `equals` with `trim`, typing a reply changes the verdict live; invalid JSON in the `schema` parameter shows the parameter error (no verdict); `oneOf` one value per line; every fixture renders (count from the contracts directory, lower bound); dictionary parity; a rejected run shows the error state; shape check of the loader covers the render path. Run `corepack pnpm --filter @ilabs/demo-web test` → FAIL.
- [ ] **Step 2: Implement** pages and components following the other pages' conventions (named regions, focus on navigation, language/theme shared, 44 px targets, mobile-first, reduced motion, text values for every bar).
- [ ] **Step 3: Verify:** tests, `typecheck`, `build`, `corepack pnpm -r typecheck && corepack pnpm -r test`; real browser: every fixture group renders, the playground reacts live, no console errors, 375 px and 1280 px without horizontal page scroll, es and en, light and dark, keyboard operation of the playground. Report what could not be verified.
- [ ] **Step 4: Commit** `feat(demo-web): add the evals page`.

### Task 5: Android demo (after Task 3; parallel with Tasks 4 and 6)

**Files:**
- Create: `demos/android/src/main/kotlin/es/sebas1705/ilabs/demo/{EvalsScreen,EvalsViewModel,EvalsMatrix,EvalsPlayground}.kt`, `demos/android/src/test/kotlin/es/sebas1705/ilabs/demo/EvalsViewModelTest.kt`
- Modify: `demos/android/build.gradle.kts` (`implementation(project(":modules:evals-kotlin"))`), `ModuleHome.kt`, `MainActivity.kt`, `res/values/strings.xml`, `res/values-es/strings.xml`

**Interfaces:**
- Consumes: Task 3's API and its `contract` harness; `FixtureLoader`, the module home and navigation of the existing demo; assets `contracts/evals/*.json` (copied by `copyContracts`).
- Produces: a fourth module on the home list; `EvalsViewModel(loader, savedState, runner)` exposing fixture names/groups, the selected fixture, the run output (matrix cells, per-target summary), the selected cell detail, the invalid-suite state, the playground state (grader kind, parameters, reply, verdict with issues, parameter error), partial `loadError`, `runError`; a screen with the same content and behavior as the web page (48 dp targets, centered max width on tablets, insets, es + en, `plurals` for counts, one spoken sentence per cell/row, `heading()` on the outer semantics of header rows, `SelectionContainer` for long text, polite coarse live status).

- [ ] **Step 1: Write the failing `EvalsViewModelTest` (JVM):** selecting `status-fail-beats-pass` exposes the fail cell and its grader details; `provider-error-skips-graders` the error cell; `invalid-*` the validation state; the playground verdict changes with the reply, a bad schema text → parameter error; one malformed fixture among valid ones keeps the valid ones with a partial `loadError`; a throwing runner sets `runError` (CancellationException rethrown); selection and playground state survive via `SavedStateHandle`; ALL fixtures (count derived from the contracts directory, lower bound) run through a test loader and match their `expect`. Run `./gradlew :demos:android:testDebugUnitTest` → FAIL.
- [ ] **Step 2: Implement** ViewModel, screen, home entry and strings (keys prefixed `evals_`, identical in both locales).
- [ ] **Step 3: Verify:** `./gradlew jvmContractTests :demos:android:testDebugUnitTest :demos:android:assembleDebug --rerun-tasks` → PASS with 0 warnings; emulator (dedicated AVD `ilabs_api36`; never wipe other projects' AVDs): home lists four modules; every fixture group renders against the fixtures; the playground works; es and en, light and dark, phone and tablet size (`wm size`, reset afterwards), font scale 1.5, rotation, back to home, process death and "Don't keep activities" keep module and selection. Screenshots stay outside the repo. Report what could not be verified (TalkBack, foldables).
- [ ] **Step 4: Commit** `feat(demo-android): add the evals screen`.

### Task 6: Module docs (after Tasks 2 and 3; parallel with Tasks 4 and 5)

**Files:**
- Create: `modules/evals/README.md`
- Modify: `README.md` (Modules table: `evals` done; run commands)

- [ ] **Step 1: Write `modules/evals/README.md`:** purpose, the suite format, the graders table (with the exact semantics: trim both sides, ASCII-only folding, code points, no regex and why), the judge protocol (verdict grammar, request shape pinned, wording not), statuses, `summarize` and `calls`, validation (`invalid suite`), how to compose with `providers` (wrap a target in `RetryingProvider`/`FallbackProvider`), limits, usage snippets in TypeScript and Kotlin that compile against the real APIs (two mock targets and a judge), a link to `contracts/evals/README.md` and the sibling READMEs; style follows `modules/context/README.md`. Update the root README Modules table and commands.
- [ ] **Step 2: Verify** both snippets compile and run (throwaway copies outside the repo or temporary tests deleted before the commit) and their printed output matches the README; every relative link resolves.
- [ ] **Step 3: Commit** `docs: document the evals module`.

---

## Execution order (parallelism)

1. **Wave 1:** Task 1 (contract + fixtures) alone with the strongest model; review it with an independent hand derivation of every fixture by the strongest model; integrate.
2. **Wave 2 (parallel):** Task 2 (TS) and Task 3 (Kotlin) from the integrated tip; review both in parallel; in parallel run a differential TS-vs-Kotlin harness over generated adversarial suites (random graders and replies, near-miss verdicts, ASCII/Unicode folding cases, lone surrogates, boundary lengths) once both are integrated.
3. **Wave 3 (parallel):** Tasks 4 (web), 5 (Android) and 6 (docs); review in parallel; integrate.
4. **Final:** one whole-branch review (hygiene, clean-clone verification of the exact CI commands, parity, architecture) and ONE fix wave split into parallel fixers with disjoint files, then a scoped re-review; squash-merge and push only when the owner asks.

## Self-review notes

- Spec coverage: validation, string/length/json/judge graders, statuses, summary and calls, playground function (Tasks 1-3); demos with the matrix and the playground (4-5); docs (6); CI needs no edit.
- Types consistent across tasks: `Grader`/`PureGrader`, `GraderResult`, `CaseResult`, `TargetSummary`, `RunOutput`, `parseSuite`, `evaluateGrader`, `runSuite`, `summarize`, `runFixture`.
- Not covered by design: regex graders, concurrency, retries, weights, persistence, pushing or tagging (owner's call).
