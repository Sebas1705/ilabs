# `structured` Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the second iLabs module, `structured` (JSON extraction, schema validation, bounded repair loop), in Kotlin and TypeScript, pinned by shared fixtures and shown in a web page and an Android screen, after a small groundwork task that makes the repo scale to module 2.

**Architecture:** `contracts/structured/` holds the spec and fixtures. Each language implements `extractJson`, `validate`, `structured()` and a `runFixture` harness on top of the existing `providers` `Provider` and `MockProvider`; contract tests run every fixture. Demos render the attempt trace.

**Tech Stack:** as the `providers` module: Kotlin Multiplatform (Android + JVM), TypeScript (pnpm workspace, Vitest), Vite + React, Compose + Material 3, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-11-structured-design.md` (parent: `2026-11-11-ilabs-design.md`)

**Previous plan:** `docs/superpowers/plans/2026-11-11-ilabs-foundation-providers.md` (done; read the `providers` code and `contracts/providers/README.md` for conventions: fixture runner pattern, error comparison rules, messages in English, `invalid fixture:` errors).

## Global Constraints

- Public repo `Sebas1705/ilabs`, MIT; no secrets in it. Commits conventional, no trailers (no Co-Authored-By, no "Generated with"); do NOT push; push/PR/tag only when the owner asks.
- Repo content in English (code, docs, commits); demo UI texts in es + en (Android string resources with keys prefixed `structured_`, web i18n dictionary).
- Same results in Kotlin and TS = deterministic logic only. Real model output and the repair prompt wording are NOT in the contract; the contract pins how many messages are appended and with which roles.
- Kotlin targets Android + JVM only; one Gradle module per module; Kotlin package `es.sebas1705.ilabs.structured`; TS package `@ilabs/structured`.
- Provider errors (`rate_limit`, `timeout`, `unavailable`, and `invalid_output` thrown by the provider) propagate unchanged from `structured`.
- Demos work offline with the mock provider; no keys, no network.
- Tooling: pnpm via `corepack pnpm`; Kotlin builds need JDK 21 (`JAVA_HOME`) and the Android SDK (`ANDROID_HOME` or `local.properties`).

## Review Focus

1. Empty reply `""` (and whitespace-only): failure `no_json`, never an exception (Task 3/4/5).
2. Braces, brackets and fences inside JSON string values, and escaped quotes: the balanced scanner must respect string literals (Task 3/4/5).
3. Object keys that are integer-like (`"10"`, `"2"`) and keys containing `/` or `~`: issue order and JSON pointer escaping (`~0`, `~1`) must match in both languages (Task 3/4/5).
4. Non-BMP characters (emoji) in `minLength`/`maxLength`: length counts Unicode code points, not UTF-16 units (Task 3/4/5).
5. Very deep nesting (e.g. 200 nested `[`): rejected as unparsable (`invalid_json`), never a stack overflow (Task 3/4/5).

---

## Contract reference (used by Tasks 3-8)

**Schema subset** (JSON object): `type` (`object|array|string|number|integer|boolean|null`), `enum` (non-empty array), `minimum`, `maximum` (inclusive), `minLength`, `maxLength` (Unicode code points), `minItems`, `maxItems`, `properties` (object of schemas), `required` (array of strings), `additionalProperties` (boolean; absent = true), `items` (schema). Unknown keywords are ignored. A malformed schema (unknown `type` string, non-array `required`, non-object `properties`, empty `enum`, non-numeric `minimum`…) throws `invalid schema: <reason>` (`InvalidSchemaError` in TS, `InvalidSchemaException : IllegalArgumentException` in Kotlin); not pinned by fixtures, only by unit tests.

**`integer`** = a JSON number whose value is integral (`2`, `2.0` and `2e0` pass; `2.5` fails). Numbers outside double range are out of scope.

**Issues:** `{ "path": "<RFC 6901 JSON pointer, \"\" = root>", "code": "type|enum|minimum|maximum|minLength|maxLength|minItems|maxItems|required|additionalProperties" }`. Order, per node: (1) `type`, and if it fails, stop at this node (no further checks, no descent); (2) `enum`; (3) `minimum`, `maximum`; (4) `minLength`, `maxLength`; (5) `minItems`, `maxItems`; (6) objects: `required` (in the order listed in the schema; one issue per missing name, path = node path + `/` + escaped name), then `additionalProperties` (when `false`: one issue per extra name, names sorted by UTF-16 code unit order, path = the extra property), then declared `properties` present in the value, visited in sorted name order (UTF-16 code unit order); arrays: `items` applied to each index in order. `enum` uses JSON deep equality (numbers by numeric value, objects without key order). Pointer escaping: `~` → `~0`, `/` → `~1`.

**Extraction** (`extractJson(text)` → `{ok:true,value}` | `{ok:false,code:"no_json"|"invalid_json"}`): try candidates in this order, the first one that parses as strict JSON wins: (a) the whole text, trimmed; (b) each fenced block in document order (a line starting with three backticks, optional language tag, up to the next line that is three backticks; the content between); (c) the region from the first `{` or `[` to its balanced closing character, scanning with JSON string-literal and escape awareness (unbalanced → no candidate). Any candidate whose nesting depth exceeds 128 is rejected. Strict JSON: no comments, no trailing commas, no single quotes, no `NaN`/`Infinity`. If nothing parses: `no_json` when the text contains none of `{`, `[` or three backticks; otherwise `invalid_json`.

**Repair loop:** `structured(provider, request, schema, {maxRepairs = 2})` (`maxRepairs >= 0`, else `RangeError` / `require`). Call 1 sends `request.messages`. If the reply fails extraction or validation and calls made `<= maxRepairs`, append one `assistant` message (the reply, verbatim) and one `user` message (a non-empty repair request listing the issues; wording not pinned) to the messages and call again; messages accumulate across repairs. Success → `{ value, attempts, trace }`; after the last allowed attempt fails → `StructuredError` with `kind: "invalid_output"`, `issues` (of the last attempt; extraction failures are `[{path:"", code:"no_json"|"invalid_json"}]`), `attempts`, `trace`. `attempts` = number of `provider.complete` calls made (a provider error counts the call that failed). Provider errors propagate unchanged. `trace` = one `{ reply, extracted: {ok:true,value}|{ok:false,code}, issues }` per attempt (not pinned by fixtures).

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

---

### Task 1: Scale-up groundwork (Gradle names, Android assets, Kotlin contract diagnostics)

**Files:**
- Modify: `settings.gradle.kts`, `demos/android/build.gradle.kts`, `demos/android/src/main/kotlin/es/sebas1705/ilabs/demo/MainActivity.kt`, `modules/providers/kotlin/src/jvmTest/kotlin/es/sebas1705/ilabs/providers/ContractTest.kt`, `.github/workflows/ci.yml`, `README.md`, `modules/providers/README.md`

**Interfaces:**
- Produces: Gradle project path `:modules:providers-kotlin` (dir stays `modules/providers/kotlin`; next module will be `:modules:structured-kotlin`); Android asset tree `contracts/<module>/<name>.json` (all modules copied by one task); the Kotlin contract test reports every failing fixture instead of stopping at the first.

- [ ] **Step 1: Rename the Gradle project.** In `settings.gradle.kts` replace `include(":modules:providers:kotlin")` by `include(":modules:providers-kotlin")` plus `project(":modules:providers-kotlin").projectDir = file("modules/providers/kotlin")`. Update every reference to the old path (`git grep -n "modules:providers:kotlin"`), excluding historical files under `docs/superpowers/specs` and `docs/superpowers/plans`.
- [ ] **Step 2: Generalize the Android copy task.** Rename `CopyFixturesTask`/`copyProvidersFixtures` to `CopyContractsTask`/`copyContracts`: copies `contracts/*/*.json` (not READMEs) into the generated assets directory `contracts/`, keeping the per-module subfolder, with `@InputDirectory`/`@OutputDirectory`, `@CacheableTask`, and a filtered input (`*/*.json`). In `MainActivity.kt` the loader reads from `contracts/providers` instead of `fixtures`.
- [ ] **Step 3: Make the Kotlin contract test list every failure.** In `ContractTest.everyFixtureMatchesItsExpectation`, collect one message per failing check (fixture name + what differed) into a list and fail once at the end with all of them joined; the test must still fail when any fixture mismatches. Prove it by temporarily editing two fixture expectations in a scratch copy of the test run (do not commit the break): the failure message must name both fixtures.
- [ ] **Step 4: Verify** with `./gradlew :modules:providers-kotlin:jvmTest :demos:android:testDebugUnitTest :demos:android:assembleDebug --rerun-tasks` → BUILD SUCCESSFUL, and confirm the debug APK contains `assets/contracts/providers/*.json` (13 files) and no `assets/fixtures`.
- [ ] **Step 5: Commit** `refactor: unique Gradle project names, per-module Android contract assets`.

### Task 2: `expect.events` for the `providers` contract

**Files:**
- Modify: `contracts/providers/*.json` (all 13 fixtures), `contracts/providers/README.md`, `modules/providers/ts/src/contract.ts` (Fixture type) and `contract.test.ts`, `modules/providers/kotlin/src/commonMain/kotlin/es/sebas1705/ilabs/providers/Contract.kt` (Fixture DTO) and `ContractTest.kt`

**Interfaces:**
- Produces: optional `expect.events` in the providers fixture format: an ordered array of `{"type":"call","provider":"<id>","outcome":"ok|rate_limit|timeout|invalid_output|unavailable"}` and `{"type":"wait","ms":<int>}`; compared by exact equality with the run's `events` in both languages when present.

- [ ] **Step 1: Write the failing assertions first.** Extend both contract tests to compare `expect.events` (when present) with `out.events`, and add `events` to ONE fixture (`retry-then-success`: call a rate_limit, wait 100, call a ok); run both suites → FAIL only if the harness types reject or the data mismatches (record output). Then add the optional field to the TS `Fixture` type and the Kotlin DTO.
- [ ] **Step 2: Add `events` to the other 12 fixtures**, derived by hand from the contract semantics (e.g. `retry-exhausted`: call a rate_limit, wait 100, call a timeout, wait 200, call a rate_limit; `fallback-empty`: no events). Document the field in `contracts/providers/README.md` (optional; exact order; the sleep lines `sleeps` must equal the `wait` events).
- [ ] **Step 3: Run** `corepack pnpm --filter @ilabs/providers test` and `./gradlew :modules:providers-kotlin:jvmTest` → PASS, 13 fixtures each.
- [ ] **Step 4: Commit** `feat(providers): pin the event trace in the contract`.

### Task 3: `structured` contract and fixtures

**Files:**
- Create: `contracts/structured/README.md` (the Contract reference above, verbatim, plus a fixture table), `contracts/structured/*.json`

**Interfaces:**
- Produces: fixtures consumed by Tasks 4, 5, 7, 8. Names (each exercises one rule): extraction — `extract-plain`, `extract-fenced-json`, `extract-fenced-no-lang`, `extract-prose-around`, `extract-braces-in-string`, `extract-escaped-quotes`, `extract-array-root`, `extract-whole-primitive`, `extract-second-fence-wins`, `extract-no-json`, `extract-empty-reply`, `extract-invalid-trailing-comma`, `extract-unbalanced`, `extract-too-deep`; validation — `validate-type-mismatch`, `validate-type-stops-descent`, `validate-required-two-missing`, `validate-enum`, `validate-enum-deep-equal`, `validate-minimum-maximum`, `validate-integer-float`, `validate-integer-2-point-0`, `validate-string-length-emoji`, `validate-array-bounds`, `validate-additional-properties`, `validate-nested-pointer`, `validate-pointer-escaping`, `validate-order-integer-like-keys`, `validate-items-per-index`; loop — `loop-first-try`, `loop-repair-second`, `loop-repair-third`, `loop-exhausted`, `loop-max-repairs-zero`, `loop-provider-error-first`, `loop-provider-error-mid`, `loop-script-exhausted`.

- [ ] **Step 1: Write the README** with the Contract reference verbatim, the fixture comparison rules, and a table of fixtures and the rule each pins.
- [ ] **Step 2: Write the fixtures**, computing every `expect` by hand from the rules (issues in the pinned order; `attempts`; `appended`). Examples that must be exact: `extract-too-deep` has a reply of 200 `[` characters (nested) → `invalid_json` with `maxRepairs: 0`; `validate-string-length-emoji` uses `"😀😀"` with `maxLength: 2` → passes and `"😀😀😀"` → `maxLength`; `validate-order-integer-like-keys` has properties `"10"`, `"2"`, `"b"` and issues ordered by UTF-16 code unit order of the names (`"10"`, `"2"`, `"b"`); `validate-pointer-escaping` has keys `"a/b"` and `"c~d"` → paths `/a~1b`, `/c~0d`; `loop-provider-error-mid` has script `[ {response: invalid value}, {error: rate_limit} ]` → error `rate_limit`, `attempts: 2`, `appended: ["assistant","user"]`; `loop-exhausted` (`maxRepairs: 1`, two invalid replies) → error `invalid_output` with the issues of the second reply, `attempts: 2`; `loop-script-exhausted` → error `unavailable`.
- [ ] **Step 3: Verify** each fixture's `expect` with a throwaway Node verifier kept OUTSIDE the repo (implements the contract rules independently in ~120 lines; run it over all 37 fixtures and report the output). Do not commit it.
- [ ] **Step 4: Commit** `docs: add structured contract and fixtures`.

### Task 4: TypeScript `structured`

**Files:**
- Create: `modules/structured/ts/package.json` (name `@ilabs/structured`, `@ilabs/providers` as `workspace:*`, same scripts and devDependencies as `@ilabs/providers`), `tsconfig.json`, `src/schema.ts`, `src/extract.ts`, `src/structured.ts`, `src/contract.ts`, `src/index.ts`, `src/contract.test.ts`, `src/schema.test.ts`, `src/extract.test.ts`, `src/structured.test.ts`

**Interfaces:**
- Consumes: Task 3 fixtures; `Provider`, `CompletionRequest`, `MockProvider`, `ProviderError` from `@ilabs/providers`.
- Produces:
  - `type IssueCode = "type"|"enum"|"minimum"|"maximum"|"minLength"|"maxLength"|"minItems"|"maxItems"|"required"|"additionalProperties"`; `interface Issue { path: string; code: IssueCode | "no_json" | "invalid_json" }`
  - `type Schema = { type?: ...; enum?: unknown[]; minimum?: number; maximum?: number; minLength?: number; maxLength?: number; minItems?: number; maxItems?: number; properties?: Record<string, Schema>; required?: string[]; additionalProperties?: boolean; items?: Schema }`
  - `class InvalidSchemaError extends Error` (message `invalid schema: <reason>`); `checkSchema(schema: unknown): asserts schema is Schema`; `validate(schema: Schema, value: unknown): Issue[]`
  - `type Extraction = { ok: true; value: unknown } | { ok: false; code: "no_json" | "invalid_json" }`; `extractJson(text: string): Extraction`
  - `interface AttemptTrace { reply: string; extracted: Extraction; issues: Issue[] }`; `interface StructuredOptions { maxRepairs?: number }`; `interface StructuredResult { value: unknown; attempts: number; trace: AttemptTrace[] }`; `class StructuredError extends Error { kind: "invalid_output"; issues: Issue[]; attempts: number; trace: AttemptTrace[] }`
  - `structured(provider: Provider, request: CompletionRequest, schema: Schema, options?: StructuredOptions): Promise<StructuredResult>`
  - `runFixture(fixture: Fixture): Promise<FixtureRun>` with `FixtureRun = { result: {value} | {error:{kind, issues?}}; attempts: number; appended: Role[]; trace: AttemptTrace[] }`, browser-safe (no Node APIs in `src/contract.ts`).

- [ ] **Step 1: Write the failing tests:** `contract.test.ts` runs every `contracts/structured/*.json` (path relative to the test file; guard `fixtures.length >= 37`) with the comparison rules of the contract; `schema.test.ts` (malformed schemas throw `invalid schema: ...` for each malformed case; `validate` on each keyword); `extract.test.ts` (empty and whitespace-only → `no_json`; fence variants; a string value containing ```` ``` ````, `{` and `}`; depth 200 → `invalid_json`); `structured.test.ts` (`maxRepairs` -1 and NaN → `RangeError`; the appended `assistant` message equals the previous reply verbatim and the appended `user` message is non-empty; messages accumulate over two repairs; the original `request.messages` array is not mutated; a thrown non-provider exception is rethrown unchanged). Run `corepack pnpm --filter @ilabs/structured test` → FAIL (record output).
- [ ] **Step 2: Implement** `checkSchema`, `validate`, `extractJson`, `structured`, `runFixture` exactly per the Contract reference. Iterative (non-recursive) scanning in `extractJson` so depth 200+ cannot overflow; check depth ≤ 128 before parsing a candidate; parse candidates with `JSON.parse`, which is strict RFC 8259 and sufficient (no reviver, no custom leniency); sort property names with the default `Array.prototype.sort()` (UTF-16 code unit order); count string length with `[...s].length`; deep-equality helper for `enum` handles numbers by value and objects without key order.
- [ ] **Step 3: Run** the same command plus `corepack pnpm --filter @ilabs/structured typecheck` → PASS, no noise.
- [ ] **Step 4: Commit** `feat(structured): add TypeScript implementation`.

### Task 5: Kotlin `structured`

**Files:**
- Create: `modules/structured/kotlin/build.gradle.kts`, `src/commonMain/kotlin/es/sebas1705/ilabs/structured/{Schema,Extract,Structured,Contract}.kt`, `src/commonTest/.../{SchemaTest,ExtractTest,StructuredTest}.kt`, `src/jvmTest/.../ContractTest.kt`
- Modify: `settings.gradle.kts` (`include(":modules:structured-kotlin")` with `projectDir = file("modules/structured/kotlin")`)

**Interfaces:**
- Consumes: Task 3 fixtures; `:modules:providers-kotlin` (as `api(project(...))`): `Provider`, `CompletionRequest`, `Message`, `MockProvider`, `ProviderException`, `Role`, `ScriptEntry`; if `ScriptEntry`/`CompletionRequest` are not yet `@Serializable`, annotate them (backward compatible).
- Produces (mirror of Task 4): `enum class IssueCode(val wire: String)` (including `NO_JSON`, `INVALID_JSON`); `data class Issue(val path: String, val code: IssueCode)`; `class InvalidSchemaException(reason: String) : IllegalArgumentException("invalid schema: $reason")`; `fun checkSchema(schema: JsonObject)`; `fun validate(schema: JsonObject, value: JsonElement): List<Issue>`; `sealed interface Extraction { data class Found(val value: JsonElement); data class Failed(val code: IssueCode) }`; `fun extractJson(text: String): Extraction`; `data class AttemptTrace(val reply: String, val extracted: Extraction, val issues: List<Issue>)`; `data class StructuredOptions(val maxRepairs: Int = 2)` (`require(maxRepairs >= 0)`); `data class StructuredResult(val value: JsonElement, val attempts: Int, val trace: List<AttemptTrace>)`; `class StructuredException(val issues: List<Issue>, val attempts: Int, val trace: List<AttemptTrace>) : Exception()`; `suspend fun structured(provider: Provider, request: CompletionRequest, schema: JsonObject, options: StructuredOptions = StructuredOptions()): StructuredResult`; `parseFixture(json: String): Fixture` and `suspend fun runFixture(fixture: Fixture): FixtureOutcome` in `commonMain` (reused by the Android demo).

- [ ] **Step 1: Write the failing tests:** the JVM `ContractTest` (all `contracts/structured/*.json`, repo root found by walking up from `user.dir`, size guard `>= 37`, collects every failure and fails once with the list), plus `SchemaTest`, `ExtractTest`, `StructuredTest` mirroring Task 4's unit tests (malformed schemas, empty/whitespace → `NO_JSON`, depth 200 → `INVALID_JSON`, `maxRepairs = -1` → `IllegalArgumentException`, verbatim assistant message, message accumulation, input list not mutated, non-provider exception rethrown unchanged). Run `./gradlew :modules:structured-kotlin:jvmTest` → FAIL (record output).
- [ ] **Step 2: Implement** per the Contract reference, mirroring Task 4's behavior: iterative scanner (no recursion on user-controlled depth), depth ≤ 128 checked before parsing, strict JSON parsing per RFC 8259 (do NOT rely on `Json.parseToJsonElement` alone: it is lenient on unquoted tokens such as `True`/`NaN`/bare words, raw control characters in strings and number syntax such as `01`/`+1`/`.5`/`1.`; validate the candidate text with a small hand-written strict RFC 8259 validator/tokenizer, iterative or depth-bounded, before or instead of `parseToJsonElement`; the `extract-invalid-*`, `extract-raw-*-in-string` and `extract-bare-word` fixtures pin this), property names sorted with `String.compareTo` (UTF-16 order), string length via code points, number equality by numeric value, `integer` check by integral value. A parse failure of any kind (including `SerializationException`, `IllegalArgumentException`) is `INVALID_JSON`; never catch `CancellationException` or `Error`.
- [ ] **Step 3: Run** `./gradlew :modules:structured-kotlin:jvmTest :modules:structured-kotlin:assemble` → PASS; the Android target assembles.
- [ ] **Step 4: Commit** `feat(structured): add Kotlin implementation`.

### Task 6: CI, module README and root README

**Files:**
- Modify: `.github/workflows/ci.yml`, `README.md`
- Create: `modules/structured/README.md`

**Interfaces:** consumes the test/build commands of Tasks 4 and 5.

- [ ] **Step 1: Extend CI:** the Kotlin job also runs `:modules:structured-kotlin:jvmTest`; the TS job already runs `-r typecheck` and `-r test`, so it covers `@ilabs/structured`; keep job names unchanged.
- [ ] **Step 2: Write `modules/structured/README.md`:** purpose, the repair loop in two sentences, a usage snippet in each language that compiles against the real API (mock provider with a bad first reply and a good second one), link to `contracts/structured/README.md`. Update the root README Modules table (`structured`: done) and the run commands.
- [ ] **Step 3: Verify** both snippets compile and run (throwaway copies outside the repo), the YAML parses, and `corepack pnpm -r test` + `./gradlew :modules:providers-kotlin:jvmTest :modules:structured-kotlin:jvmTest` pass.
- [ ] **Step 4: Commit** `docs: document the structured module and run it in CI`.

### Task 7: Web demo (`structured` page and module navigation)

**Files:**
- Create: `demos/web/src/App.tsx`, `src/components/Nav.tsx`, `src/pages/StructuredPage.tsx`, `src/pages/StructuredPage.test.tsx`, `src/structuredFixtures.ts`
- Modify: `demos/web/package.json` (`@ilabs/structured` as `workspace:*`), `src/main.tsx` (render `App`), `src/i18n.ts` (new keys), `src/styles.css`, the existing `ProvidersPage` only as needed to sit inside `App`

**Interfaces:**
- Consumes: `runFixture`, `AttemptTrace` from `@ilabs/structured`; the structured fixtures through `import.meta.glob` (same `server.fs.allow` repo root).
- Produces: hash routes `#/providers` (default) and `#/structured`; a nav with both modules; `StructuredPage` with a fixture selector (all structured fixtures grouped by prefix `extract-`, `validate-`, `loop-`), the schema and request, a timeline of attempts (reply as text, extraction outcome with a visible label plus code, each issue as `path + code` with a translated label, the repair step, the final value or error and `attempts`), no network calls.

- [ ] **Step 1: Write the failing tests:** navigation switches between the two pages and keeps the language; selecting `loop-repair-second` shows two attempts, an issue row with `/age` and `minimum`, and the final value `age: 7`; selecting `extract-no-json` shows the `no_json` outcome and a final `invalid_output` error; a rejected run shows the error state (as in the providers page); every UI string exists in es and en (a test iterating over the dictionary keys). Run `corepack pnpm --filter @ilabs/demo-web test` → FAIL.
- [ ] **Step 2: Implement** the router (hash based, no new dependency), the nav, the page and the i18n keys (outcome and issue-code labels translated and shown next to the raw code, text labels not colour alone, `lang` attribute updated, accessible names containing visible text), following the providers page conventions and tokens.
- [ ] **Step 3: Run** the tests plus `corepack pnpm --filter @ilabs/demo-web build`; start the dev server and verify in a browser: every structured fixture renders without console errors, 375 px and desktop widths, es and en, light and dark; report what could not be verified.
- [ ] **Step 4: Commit** `feat(demo-web): add structured page and module navigation`.

### Task 8: Android demo (`structured` screen and module switcher)

**Files:**
- Create: `demos/android/src/main/kotlin/es/sebas1705/ilabs/demo/{StructuredScreen,StructuredViewModel,StructuredTimeline,ModuleHome}.kt`, `demos/android/src/test/kotlin/es/sebas1705/ilabs/demo/StructuredViewModelTest.kt`
- Modify: `demos/android/build.gradle.kts` (`implementation(project(":modules:structured-kotlin"))`), `MainActivity.kt`, `res/values/strings.xml`, `res/values-es/strings.xml`

**Interfaces:**
- Consumes: `parseFixture`, `runFixture`, `AttemptTrace` from `:modules:structured-kotlin`; assets `contracts/structured/*.json` (copied by Task 1's task); the existing `FixtureLoader`.
- Produces: a home list of modules (Providers, Structured) with back navigation that survives rotation and process death (selected module in saved state; `BackHandler`); `StructuredViewModel(loader: FixtureLoader, savedState: SavedStateHandle, runner = ::runFixture)` exposing fixture names, selected fixture, schema, request, trace, result, attempts, `loadError` (partial, per-document parse errors like the providers ViewModel) and `runError`.

- [ ] **Step 1: Write the failing `StructuredViewModelTest` (JVM):** selecting `loop-repair-second` exposes two trace entries, `attempts == 2` and the final value; `extract-no-json` exposes `no_json` and a final `invalid_output` error; one malformed fixture among valid ones keeps the valid ones listed with a partial `loadError`; a runner that throws sets `runError` and never crashes; the selection survives via `SavedStateHandle`. Run `./gradlew :demos:android:testDebugUnitTest` → FAIL (record output).
- [ ] **Step 2: Implement** the ViewModel, the screen (Compose, Material 3, centered max-width column on tablets, edge-to-edge insets, es + en strings with keys prefixed `structured_`, 48 dp targets, translated labels next to codes, one spoken sentence per timeline row for TalkBack, `plurals` for counts), the module home and navigation.
- [ ] **Step 3: Run** `./gradlew :modules:providers-kotlin:jvmTest :modules:structured-kotlin:jvmTest :demos:android:testDebugUnitTest :demos:android:assembleDebug` → PASS.
- [ ] **Step 4: Verify on an emulator** with a dedicated AVD (reuse `ilabs_api36` if it exists; never wipe other projects' AVDs): all structured fixtures render and match their expected results, es and en, light and dark, phone and a tablet-size window (`wm size 1600x2560` + `wm density 320`, reset afterwards), rotation and back keep the module and selection, the providers demo still works. Screenshots stay outside the repo. Stop the emulator at the end. Report what could not be verified (TalkBack, foldables).
- [ ] **Step 5: Commit** `feat(demo-android): add structured screen and module home`.

---

## Self-review notes

- Spec coverage: groundwork (Tasks 1-2), schema subset, issues, extraction, repair loop, reuse of `providers` (Tasks 3-5), demos (7-8), module README and CI (6). Remaining `providers` backlog (accessibility details, plurals in the providers screen, etc.) stays out by design.
- Types consistent across tasks: `Issue`, `IssueCode`, `Extraction`, `AttemptTrace`, `StructuredResult`/`StructuredError`/`StructuredException`, `runFixture` with `trace`.
- Not covered by design: pushing, tags, Doppler, a real model provider (the Worker proxy arrives with the full-stack demo plan).
