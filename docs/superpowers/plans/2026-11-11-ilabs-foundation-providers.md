# iLabs Foundation + `providers` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the iLabs repo skeleton and ship the first module, `providers` (retry + fallback + scripted mock), in Kotlin and TypeScript, verified by shared contract fixtures and shown in an Android and a web demo.

**Architecture:** `contracts/providers/` holds the spec and JSON fixtures. Each language implements the same three types (`MockProvider`, `RetryingProvider`, `FallbackProvider`) and a contract test runs every fixture. Demos render the same fixtures as timelines of calls and waits.

**Tech Stack:** Kotlin Multiplatform (Gradle, Android + JVM targets), TypeScript (pnpm workspace, Vitest), Vite + React (web demo), Jetpack Compose + Material 3 (Android demo), GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-11-ilabs-design.md`

**Later plans (not here):** `structured`, `context`, `evals`, Worker + full-stack demo.

## Global Constraints

- Public repo `Sebas1705/ilabs`, MIT license; **no secrets in the repo** (local folder `Repos\Sebas1705\ilabs`). Do NOT create the GitHub repo or push: the owner asks first.
- Repo content in English (code, docs, commits, PRs). Demo UI texts in es + en (string resources on Android, i18n dictionary on web).
- Commits as the already-configured git identity, conventional commits, **no trailers** (no Co-Authored-By, no "Generated with").
- Same results in Kotlin and TS = deterministic logic only, driven by scripted mock responses. Real LLM output is NOT in the contract.
- Provider error taxonomy: `rate_limit`, `timeout`, `invalid_output`, `unavailable`.
- Kotlin targets: Android + JVM only. One Gradle module per module, built from source, no Maven publishing.
- Demos work offline with the mock provider; no keys required.
- Build tooling: Gradle (KMP) for Kotlin; pnpm workspace + Vite + Vitest for TS; web demo Vite + React.
- Android: AGP 9 / current Kotlin; consult the `mobiai-kmp` and `agp-9-upgrade` skills before wiring KMP + Android.

## Review Focus

1. `FallbackProvider` with an empty provider list: returns `unavailable`, never throws a raw exception (pinned in Task 2/3/4).
2. `maxAttempts = 1`: no retry and no sleep (Task 3/4).
3. `retryAfterMs` of `0`, negative, or larger than `maxDelayMs`: clamp to `[0, maxDelayMs]` (Task 3/4).
4. Mock script exhausted: next call returns `unavailable` instead of crashing or looping (Task 2/3/4).
5. Empty `messages` array: provider returns normally; the module does no validation of content (Task 3/4).

---

## Contract reference (used by Tasks 2-7)

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

---

### Task 1: Repo foundation

**Files:**
- Create: `README.md`, `LICENSE` (MIT), `.gitignore`, `pnpm-workspace.yaml`, `package.json`, `settings.gradle.kts`, `docs/adr/0001-contracts-first.md`

**Interfaces:**
- Produces: workspace layout `modules/<name>/{kotlin,ts}`, `demos/{android,web}`, `contracts/<name>/`; pnpm workspace globs `modules/*/ts`, `demos/web`; Gradle includes added per task.

- [ ] **Step 1: `git init` in `Repos/Sebas1705/ilabs` (default branch `main`); keep the existing `docs/` files.**
- [ ] **Step 2: Create the files above.** README (English): purpose of iLabs, the layout, "no keys needed", link to the design spec. ADR 0001 records "contracts first, deterministic only" using the `docs-conventions` skill format.
- [ ] **Step 3: Verify**
  Run: `pnpm install` → Expected: succeeds with an empty workspace.
- [ ] **Step 4: Commit** `chore: add repo foundation` (docs spec and plan included).

### Task 2: `providers` contract fixtures

**Files:**
- Create: `contracts/providers/README.md` (the Contract reference above, verbatim), `contracts/providers/*.json`

**Interfaces:**
- Produces: fixtures consumed by Tasks 3, 4, 6, 7. Names: `retry-then-success`, `retry-exhausted`, `retry-after-header`, `retry-clamped`, `retry-no-retry-on-invalid`, `single-attempt`, `fallback-second-wins`, `fallback-invalid-stops`, `fallback-all-fail`, `fallback-empty`, `mock-exhausted`.

- [ ] **Step 1: Write the 11 fixtures** with the spec's exact values: `retry-after-header` has `retryAfterMs: 250` and `sleeps: [250]`; `retry-clamped` has `retryAfterMs: 99999`, `maxDelayMs: 1000`, `sleeps: [1000]`; a negative `retryAfterMs` case goes in `retry-clamped` as a second request via a second fixture `retry-negative-after` (sleeps `[0]`); `single-attempt` has `maxAttempts: 1`, `sleeps: []`; `fallback-empty` expects error `unavailable`; `mock-exhausted` expects error `unavailable` on the second call.
- [ ] **Step 2: Verify** each file parses as JSON: `node -e "require('fs').readdirSync('contracts/providers').filter(f=>f.endsWith('.json')).forEach(f=>JSON.parse(require('fs').readFileSync('contracts/providers/'+f)))"` → no output, exit 0.
- [ ] **Step 3: Commit** `docs: add providers contract and fixtures`.

### Task 3: TypeScript `providers`

**Files:**
- Create: `modules/providers/ts/package.json` (name `@ilabs/providers`), `tsconfig.json`, `src/types.ts`, `src/mock.ts`, `src/retry.ts`, `src/fallback.ts`, `src/index.ts`, `src/contract.test.ts`, `src/mock.test.ts`

**Interfaces:**
- Consumes: Task 2 fixtures.
- Produces: `interface Provider { id: string; complete(req: CompletionRequest): Promise<CompletionResponse> }`; `class ProviderError extends Error { kind: ErrorKind; retryAfterMs?: number }`; `new MockProvider(id: string, script: ScriptEntry[])`; `new RetryingProvider(inner: Provider, opts: {maxAttempts: number; baseDelayMs: number; maxDelayMs: number; sleep: (ms: number) => Promise<void>})`; `new FallbackProvider(providers: Provider[])`; `runFixture(fixture): Promise<{result, sleeps, calls}>` exported from `src/contract.ts` for the web demo (Task 6).

- [ ] **Step 1: Write the failing contract test** `contract.test.ts`: loads every `contracts/providers/*.json` (resolved relative to the repo root), runs `runFixture`, and asserts `result`, `sleeps` and `calls` equal `expect`. Also `mock.test.ts`: `empty messages` request returns normally.
- [ ] **Step 2: Run** `pnpm --filter @ilabs/providers test` → FAIL (modules missing).
- [ ] **Step 3: Implement** the types and the three providers exactly as in the Contract reference; `runFixture` builds providers from the fixture, injects a `sleep` that records instead of waiting, and counts calls per provider id.
- [ ] **Step 4: Run** the same command → PASS for all 11+ fixtures.
- [ ] **Step 5: Commit** `feat(providers): add TypeScript implementation`.

### Task 4: Kotlin `providers`

**Files:**
- Create: `modules/providers/kotlin/build.gradle.kts`, `src/commonMain/kotlin/dev/ilabs/providers/{Types,MockProvider,RetryingProvider,FallbackProvider}.kt`, `src/commonTest/.../MockProviderTest.kt`, `src/jvmTest/.../ContractTest.kt`
- Modify: `settings.gradle.kts` (include `:modules:providers:kotlin`), root `build.gradle.kts` / version catalog as needed

**Interfaces:**
- Consumes: Task 2 fixtures (read in the JVM test from `contracts/providers/`).
- Produces: `interface Provider { val id: String; suspend fun complete(req: CompletionRequest): CompletionResponse }`; `class ProviderException(val kind: ErrorKind, val retryAfterMs: Long? = null) : Exception()`; `class MockProvider(id: String, script: List<ScriptEntry>)`; `class RetryingProvider(inner: Provider, opts: RetryOptions)` with `RetryOptions(maxAttempts: Int, baseDelayMs: Long, maxDelayMs: Long, sleep: suspend (Long) -> Unit)`; `class FallbackProvider(providers: List<Provider>)`; `suspend fun runFixture(fixture: Fixture): FixtureOutcome` in `commonMain` (reused by the Android demo, Task 7).

- [ ] **Step 1: Write the failing `ContractTest`** (JVM): parse each fixture with kotlinx.serialization, run `runFixture`, assert result, sleeps and calls equal `expect`. Add `MockProviderTest` for empty messages and script exhaustion.
- [ ] **Step 2: Run** `./gradlew :modules:providers:kotlin:jvmTest` → FAIL.
- [ ] **Step 3: Implement** targets Android (library) and JVM, `commonMain` code as in the Contract reference; injected `sleep` records waits in tests (use `runTest`, no real delays).
- [ ] **Step 4: Run** the same command → PASS; also `./gradlew :modules:providers:kotlin:assemble` succeeds for the Android target.
- [ ] **Step 5: Commit** `feat(providers): add Kotlin implementation`.

### Task 5: CI

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:** consumes Tasks 3 and 4 test commands.

- [ ] **Step 1: Write the workflow:** on push and PR, two jobs: `ts` (pnpm install, `pnpm -r test`) and `kotlin` (JDK 21, `./gradlew jvmTest`). Add a path trigger on `contracts/**` for both.
- [ ] **Step 2: Verify locally** with the same two commands → PASS (the workflow itself runs only once the owner pushes).
- [ ] **Step 3: Commit** `ci: run TypeScript and Kotlin tests`.

### Task 6: Web demo (`providers` page)

**Files:**
- Create: `demos/web/package.json`, `vite.config.ts`, `index.html`, `src/main.tsx`, `src/pages/ProvidersPage.tsx`, `src/i18n.ts` (es/en), `src/pages/ProvidersPage.test.tsx`

**Interfaces:**
- Consumes: `runFixture` and the fixtures (imported through Vite from `contracts/providers/`).
- Produces: page at route `/providers`: a fixture selector and a timeline of rows ("call → error rate_limit", "wait 100 ms", "call → ok") plus the final result.

- [ ] **Step 1: Write the failing test:** selecting `retry-then-success` shows rows `rate_limit`, `100 ms`, `ok` in that order; selecting `fallback-empty` shows `unavailable`; the language toggle switches a visible label between en and es.
- [ ] **Step 2: Run** `pnpm --filter web test` → FAIL.
- [ ] **Step 3: Implement** the page; timeline built from the outcome of `runFixture`, extended to expose an ordered `events` list (`{type: "call"|"wait", ...}`) — add `events` to `runFixture`'s return in Task 3 if absent.
- [ ] **Step 4: Run** the test → PASS; start `pnpm --filter web dev` and verify in the browser pane: all fixtures render, no console errors, usable at 375 px width and desktop, both languages, light and dark.
- [ ] **Step 5: Commit** `feat(demo-web): add providers page`.

### Task 7: Android demo (`providers` screen)

**Files:**
- Create: `demos/android/` Gradle app module (`es.sebas1705.ilabs.demo`), `ProvidersScreen.kt`, `ProvidersViewModel.kt`, `res/values/strings.xml`, `res/values-es/strings.xml`, a Gradle task copying `contracts/providers/*.json` into the app assets, `ProvidersViewModelTest.kt`
- Modify: `settings.gradle.kts`

**Interfaces:**
- Consumes: Kotlin `runFixture` (Task 4), fixtures as assets.
- Produces: screen with a fixture list and the same timeline as the web page.

- [ ] **Step 1: Write the failing `ProvidersViewModelTest`:** selecting `retry-then-success` exposes events `[call rate_limit, wait 100, call ok]`; selecting `fallback-empty` exposes `unavailable`.
- [ ] **Step 2: Run** `./gradlew :demos:android:testDebugUnitTest` → FAIL.
- [ ] **Step 3: Implement** the ViewModel and the Compose screen (Material 3, window-size aware via the `adaptive-ui` skill: centered content on tablets).
- [ ] **Step 4: Run** the unit test → PASS; then install on an emulator (`adb install -r -t`), and verify: all fixtures render, es and en, light and dark, phone and tablet sizes, rotation and back keep the selection. Report anything not testable.
- [ ] **Step 5: Commit** `feat(demo-android): add providers screen`.

---

## Self-review notes

- Spec coverage: first-batch module `providers` (Tasks 2-4), contracts-first and CI (2, 5), demos on both platforms (6, 7), repo/MIT/no secrets/English (1). The other three modules, Worker and full-stack demo belong to later plans.
- Types match across tasks: `Provider`, `ProviderError`/`ProviderException`, `RetryOptions`, `runFixture` with `events`, `sleeps`, `calls`, `result`.
- Not covered by this plan, by design: GitHub repo creation, Doppler, push/tag (ask the owner).
