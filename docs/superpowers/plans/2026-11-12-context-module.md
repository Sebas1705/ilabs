# `context` Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Run independent tasks in parallel** (see "Execution order"): each parallel implementer works in its own git worktree cut from the CURRENT branch tip (verify the base commit before accepting its result), and its commits are cherry-picked onto the branch after review.

**Goal:** Ship the third iLabs module, `context` (token estimation, sliding window, incremental summary), in Kotlin and TypeScript, pinned by shared fixtures and shown in a web page and an Android screen, after a small Android groundwork task.

**Architecture:** `contracts/context/` holds the spec and fixtures. Each language implements `estimateTokens`, `slidingWindow`, `summarizeContext` and a `runFixture` harness on top of the existing `providers` types (`Message`, `Provider`, `MockProvider`); contract tests run every fixture. Demos use the live implementations with the fixtures as sample conversations.

**Tech Stack:** as the previous modules: Kotlin Multiplatform (Android + JVM), TypeScript (pnpm workspace, Vitest), Vite + React, Compose + Material 3, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-12-context-design.md` (binding; parent: `2026-11-11-ilabs-design.md`). Read the `structured` module (modules/structured/{ts,kotlin}, contracts/structured/README.md, its demos) for conventions: fixture runner pattern, aggregated Kotlin contract failures, `invalid fixture:` errors, test guard `>= <real count>`.

## Global Constraints

- Public repo `Sebas1705/ilabs`, MIT; no secrets, no local paths or usernames in tracked files. Commits conventional, no trailers (no Co-Authored-By, no "Generated with"); do NOT push; push/PR/tag only when the owner asks.
- Repo content in English (code, docs, commits); demo UI texts in es + en (identical key sets; Android keys prefixed `context_`).
- Same results in Kotlin and TS = deterministic logic only; the summarizer instruction wording is NOT in the contract.
- Kotlin targets Android + JVM only; Gradle project `:modules:context-kotlin` (dir `modules/context/kotlin`), package `es.sebas1705.ilabs.context`; TS package `@ilabs/context` (dir `modules/context/ts`).
- Demos work offline with the mock provider; no keys, no network.
- Tooling on this machine: pnpm via `corepack pnpm`; Kotlin builds need JDK 21 (`JAVA_HOME`) and the Android SDK (`ANDROID_HOME` or `local.properties`).
- CI aggregates Kotlin module tests through `./gradlew jvmContractTests` (picks `:modules:*-kotlin` automatically); do not edit CI per module unless a step says so.

## Review Focus

1. Empty message list, only system messages, and no non-system message: each fits/errs exactly per the spec (Tasks 2/3/4).
2. Emoji and astral characters in message content: costs count code points, equal in both languages (Tasks 2/3/4).
3. Interleaved system messages and the position of the new summary (position of the earliest removed message); several existing summary messages anywhere, including inside what would be the protected window, merged into one (Tasks 2/3/4).
4. Option validation: `maxTokens`/`keepRecent` of 0, negative, 1.5, NaN, Infinity → `RangeError` / `require` (Tasks 3/4).
5. Failure after the provider call (still too big, `budget_too_small`), empty reply, provider errors and an exhausted script propagate unchanged and `calls` is exact (Tasks 2/3/4).

---

## Contract reference (used by Tasks 2-6)

The binding rules are the "Rules" section of the spec. Fixture shape (`contracts/context/<name>.json`):

```json
{
  "name": "summarize-one-summary",
  "setup": {"kind": "summarize", "maxTokens": 40, "keepRecent": 2},
  "messages": [{"role": "system", "content": "Be brief."}, {"role": "user", "content": "..."}],
  "provider": {"id": "a", "script": [{"response": "They discussed X."}]},
  "expect": {
    "result": {"messages": [{"role": "system", "content": "Be brief."}, {"role": "system", "content": "Summary of the earlier conversation:\nThey discussed X."}],
               "droppedCount": 2, "tokensBefore": 61, "tokensAfter": 28, "summarized": true, "summaryCalls": 1},
    "calls": 1,
    "transcriptContains": ["first older message text", "second older message text"]
  }
}
```

`expect.result` is the result object or `{"error": {"kind": "budget_too_small" | <provider kind>, "required": <int>?}}` (`required` only for `budget_too_small`, compared when present). `setup.kind` is `window` (no `provider`) or `summarize`. Comparison: `messages` by deep equality in order, numbers and booleans exact, `calls` exact, `transcriptContains` as ordered non-overlapping substrings of the `user` message of the single summarizer request. The harness records the summarizer request (the provider is wrapped to record the messages it receives) and checks it has exactly 2 messages (`system` then `user`, both non-empty) whenever a call was made.

---

### Task 1: Android groundwork (independent; run in parallel with Task 2)

**Files:**
- Modify: `demos/android/src/main/kotlin/es/sebas1705/ilabs/demo/StructuredViewModel.kt` (and every file that references `FixtureGroup` / `ProviderFailure`), `StructuredTimeline.kt`, `ProvidersScreen.kt`/`Timeline.kt` (the "1 calls" string), `res/values/strings.xml`, `res/values-es/strings.xml`, the Android unit tests that reference the renamed types

**Interfaces:**
- Produces: the structured-specific top-level names are nested or prefixed so a third module can define its own without clashing: `FixtureGroup` → `StructuredGroup` (or nested `StructuredViewModel.Group`) and `ProviderFailure` → `StructuredProviderFailure`; the same code blocks used by the providers and structured screens are selectable (`SelectionContainer`).

- [ ] **Step 1: Failing test first.** In `StructuredViewModelTest` use the new names (`StructuredGroup`, `StructuredProviderFailure`); add a test for the providers "calls" label: a pure function (e.g. `callsLabelRes(n)` or a `plurals` lookup abstraction testable on the JVM) returning the singular form for 1 and the plural for 0 and ≥ 2 in both locales if a resource-free seam exists; otherwise assert the plural resources exist in both locales with `one` and `other` quantities (parse the XML in a unit test). Run `./gradlew :demos:android:testDebugUnitTest` → FAIL.
- [ ] **Step 2: Implement.** Rename the types and fix all references; replace the providers screen's "N calls" string by `plurals` (`one`/`other` in en; `one`/`other` in es); wrap the code blocks' text in `SelectionContainer` (keep semantics and spoken sentences).
- [ ] **Step 3: Verify.** `./gradlew :demos:android:testDebugUnitTest :demos:android:assembleDebug --rerun-tasks` → PASS; install on an emulator (dedicated AVD `ilabs_api36`; never wipe other projects' AVDs) and check the providers and structured screens in es and en for the plural ("1 llamada"/"1 call") and that a long reply can be selected. Report what could not be verified.
- [ ] **Step 4: Commit** `refactor(demo-android): give structured names a home, add plurals and selectable code`.

### Task 2: `context` contract and fixtures (independent; run in parallel with Task 1)

**Files:**
- Create: `contracts/context/README.md` (the spec's Rules section adapted as the contract, plus the fixture shape above and a fixture table), `contracts/context/*.json` (about 40 fixtures)

**Interfaces:**
- Produces: fixtures consumed by Tasks 3-6. Names (each one rule): estimator — `window-empty-list`, `window-emoji-cost`, `window-cost-multiple-of-four`; window — `window-fits-unchanged`, `window-only-system-fits`, `window-only-system-too-big`, `window-last-too-big`, `window-last-fits-exactly`, `window-budget-equal-total`, `window-drops-oldest`, `window-interleaved-system`, `window-stops-at-first-nonfitting`, `window-system-never-dropped`, `window-summary-message-is-system`; summarize — `summarize-fits-no-call`, `summarize-empty-list`, `summarize-one-summary`, `summarize-incremental`, `summarize-several-summaries-merged`, `summarize-summary-in-recent-window`, `summarize-interleaved-system`, `summarize-nothing-older-window-only`, `summarize-nothing-older-budget-too-small`, `summarize-still-too-big-window`, `summarize-budget-too-small-after-call`, `summarize-keeprecent-larger-than-list`, `summarize-keeprecent-default`, `summarize-empty-reply`, `summarize-whitespace-reply-not-trimmed`, `summarize-provider-error`, `summarize-script-exhausted`, `summarize-position-first-removed`, `summarize-transcript-order`, `summarize-plain-system-never-sent`; add extras for rules you find unpinned (state them in the README).

- [ ] **Step 1: Write `contracts/context/README.md`** (the spec's Rules adapted, English, plus the fixture format and a table of fixtures with the single rule each pins).
- [ ] **Step 2: Write the fixtures**, deriving every number by hand from the rules (cost = `4 + ceil(codePoints/4)`; `tokensBefore`, `tokensAfter`, `droppedCount`, positions, `required`, `calls`). Keep the sample conversations short and readable. Examples that must be exact: `window-emoji-cost` has a message of 5 emoji (5 code points → 2 tokens + 4 = 6); `window-cost-multiple-of-four` pins 4 chars → 1 token and 5 chars → 2 tokens; `window-last-too-big` fails with `required = cost(system) + cost(last non-system)`; `window-interleaved-system` keeps system messages at their original positions; `summarize-position-first-removed` has a plain system message BETWEEN removed messages and the summary at the earliest removed position; `summarize-empty-reply` yields a summary message equal to the prefix alone; `summarize-budget-too-small-after-call` expects `calls: 1` and error `budget_too_small`; `summarize-script-exhausted` uses `script: []` → error `unavailable`, `calls: 1`.
- [ ] **Step 3: Verify** every `expect` with a throwaway Node verifier kept OUTSIDE the repo (an independent implementation of the rules, ~120 lines; run it over all fixtures and report the output; also run it against a mutated copy to prove it detects wrong expectations). Check JSON well-formedness, `name` == file name, formatting (2-space indent, trailing newline, LF).
- [ ] **Step 4: Commit** `docs: add context contract and fixtures`.

### Task 3: TypeScript `context` (after Task 2; parallel with Task 4)

**Files:**
- Create: `modules/context/ts/package.json` (name `@ilabs/context`, `@ilabs/providers` as `workspace:*`, same scripts/devDependencies as `@ilabs/structured`), `tsconfig.json`, `src/tokens.ts`, `src/window.ts`, `src/summarize.ts`, `src/contract.ts`, `src/index.ts`, tests (`contract.test.ts`, `tokens.test.ts`, `window.test.ts`, `summarize.test.ts`)

**Interfaces:**
- Consumes: Task 2 fixtures; `Message`, `Role`, `Provider`, `CompletionRequest`, `MockProvider`, `ProviderError` from `@ilabs/providers`.
- Produces:
  - `type TokenCounter = (message: Message) => number`; `estimateTokens(text: string): number`; `defaultCounter: TokenCounter`
  - `SUMMARY_PREFIX: string`; `isSummaryMessage(message: Message): boolean`
  - `class BudgetTooSmallError extends Error { readonly kind: "budget_too_small"; readonly required: number }`
  - `interface WindowOptions { maxTokens: number; counter?: TokenCounter }`; `interface WindowResult { messages: Message[]; droppedCount: number; tokensBefore: number; tokensAfter: number }`; `slidingWindow(messages: Message[], options: WindowOptions): WindowResult`
  - `interface SummarizeOptions extends WindowOptions { keepRecent?: number }`; `interface SummarizeResult extends WindowResult { summarized: boolean; summaryCalls: 0 | 1; summarizerRequest: CompletionRequest | null }`; `summarizeContext(provider: Provider, messages: Message[], options: SummarizeOptions): Promise<SummarizeResult>`
  - `runFixture(fixture: Fixture): Promise<FixtureRun>` with `FixtureRun = { result: {messages, droppedCount, tokensBefore, tokensAfter, summarized?, summaryCalls?} | {error: {kind: string; required?: number}}; calls: number; summarizerRequest: CompletionRequest | null }`, browser-safe (no Node APIs outside tests); `invalid fixture: <reason>` for unusable fixtures. Export everything from `src/index.ts`.

- [ ] **Step 1: Write the failing tests:** `contract.test.ts` runs every `contracts/context/*.json` (path relative to the test file; guard `fixtures.length >= <real count>`; per-fixture `it`); unit tests for `estimateTokens` (empty, code points, emoji, lone surrogate), option validation (`maxTokens`/`keepRecent` 0, -1, 1.5, NaN, Infinity → `RangeError`), a custom counter applied to fits/`tokensBefore`/`tokensAfter`/windowing, the input array not mutated, a thrown non-provider exception rethrown unchanged, provider errors propagate unchanged. Run `corepack pnpm --filter @ilabs/context test` → FAIL (record output).
- [ ] **Step 2: Implement** per the spec's Rules. Code points via `[...s].length`; summaries detected by exact prefix on a `system` message; one provider call at most; the new summary placed at the earliest removed position; `droppedCount` as specified; windowing never moves system messages.
- [ ] **Step 3: Run** the same command plus `corepack pnpm --filter @ilabs/context typecheck` and `corepack pnpm -r typecheck && corepack pnpm -r test` → PASS, no noise.
- [ ] **Step 4: Commit** `feat(context): add TypeScript implementation`.

### Task 4: Kotlin `context` (after Task 2; parallel with Task 3)

**Files:**
- Create: `modules/context/kotlin/build.gradle.kts` (same plugins/targets/SDK levels as the other modules, namespace `es.sebas1705.ilabs.context`, `api(project(":modules:providers-kotlin"))`, the jvmTest input declaration for `contracts/context` like the other modules), `src/commonMain/kotlin/es/sebas1705/ilabs/context/{Tokens,Window,Summarize,Contract}.kt`, `src/commonTest/.../{TokensTest,WindowTest,SummarizeTest}.kt`, `src/jvmTest/.../ContractTest.kt`
- Modify: `settings.gradle.kts` (`include(":modules:context-kotlin")` + `projectDir = file("modules/context/kotlin")`)

**Interfaces:**
- Consumes: Task 2 fixtures; `Message`, `Role`, `Provider`, `CompletionRequest`, `MockProvider`, `ScriptEntry`, `ProviderException`, `ErrorKind` from `:modules:providers-kotlin` (annotate DTOs `@Serializable` if needed).
- Produces (mirror of Task 3): `typealias TokenCounter = (Message) -> Int`; `fun estimateTokens(text: String): Int`; `val defaultCounter: TokenCounter`; `const val SUMMARY_PREFIX`; `fun isSummaryMessage(message: Message): Boolean`; `class BudgetTooSmallException(val required: Int) : Exception()`; `data class WindowOptions(val maxTokens: Int, val counter: TokenCounter = defaultCounter)` (`require(maxTokens >= 1)`); `data class WindowResult(val messages: List<Message>, val droppedCount: Int, val tokensBefore: Int, val tokensAfter: Int)`; `fun slidingWindow(messages: List<Message>, options: WindowOptions): WindowResult`; `data class SummarizeOptions(val maxTokens: Int, val keepRecent: Int = 4, val counter: TokenCounter = defaultCounter)` (`require` both `>= 1`); `data class SummarizeResult(val messages: List<Message>, val droppedCount: Int, val tokensBefore: Int, val tokensAfter: Int, val summarized: Boolean, val summaryCalls: Int, val summarizerRequest: CompletionRequest?)`; `suspend fun summarizeContext(provider: Provider, messages: List<Message>, options: SummarizeOptions): SummarizeResult`; `parseFixture(json: String): Fixture` and `suspend fun runFixture(fixture: Fixture): FixtureOutcome` in `commonMain` (reused by the Android demo; no `java.*`), `FixtureOutcome(result, calls, summarizerRequest)`.

- [ ] **Step 1: Write the failing tests:** JVM `ContractTest` (walks up from `user.dir`, guard `size >= <real count>`, collects every failing check and fails once with the joined list), plus unit tests mirroring Task 3's (code points incl. surrogate pairs and a lone surrogate, option validation, custom counter, input list not mutated, non-provider exception and CancellationException rethrown unchanged, provider exceptions propagate). Run `./gradlew :modules:context-kotlin:jvmTest` → FAIL (record output).
- [ ] **Step 2: Implement** per the spec's Rules, mirroring Task 3 exactly (code point counting in common code, one provider call at most, summary position, `droppedCount`, `Int` arithmetic without overflow for realistic sizes). Never catch `CancellationException` or `Error`.
- [ ] **Step 3: Run** `./gradlew :modules:context-kotlin:jvmTest :modules:context-kotlin:assemble jvmContractTests` → PASS; `./gradlew jvmContractTests --dry-run` lists the three modules.
- [ ] **Step 4: Commit** `feat(context): add Kotlin implementation`.

### Task 5: Web demo (after Task 3; parallel with Tasks 6 and 7)

**Files:**
- Create: `demos/web/src/pages/ContextPage.tsx`, `src/components/TokenBar.tsx` (or similar), `src/contextFixtures.ts`, `src/pages/ContextPage.test.tsx`
- Modify: `demos/web/package.json` (`@ilabs/context` as `workspace:*`; lockfile committed), `src/routes.ts` (one registry entry), `src/i18n.ts` (new keys, es + en), `src/styles.css`

**Interfaces:**
- Consumes: `slidingWindow`, `summarizeContext`, `messageTokens`/`defaultCounter`, `runFixture`, `BudgetTooSmallError` from `@ilabs/context`; the context fixtures through `import.meta.glob`; the route registry, shared shell and conventions of the existing pages (language/theme, focus on navigation, named regions, deterministic sorting).
- Produces: route `#/context` and a nav entry; a fixture picker (grouped `window-` / `summarize-`); for `window` fixtures a budget slider (min 1, max = total tokens of the conversation, step 1) that re-runs `slidingWindow` live and shows each message as kept or dropped with a token bar and the totals (`tokensBefore`, `tokensAfter`, `droppedCount`), and a "budget too small (needs N)" state instead of clamping; for `summarize` fixtures the budget is fixed to `setup.maxTokens`, the slider is disabled, and the page shows the summarizer request, the resulting summary message and the totals; empty and error states; every string in es and en.

- [ ] **Step 1: Write the failing tests:** navigation to `#/context` through the registry; moving the slider on `window-drops-oldest` changes the kept/dropped rows and totals as `slidingWindow` computes; a budget below the requirement on `window-last-too-big` shows the "needs N" state; `summarize-one-summary` shows the summarizer request and the summary message and the slider is disabled; every fixture renders without throwing (count derived from the contracts directory, with a lower bound); dictionary parity; a rejected run shows the error state. Run `corepack pnpm --filter @ilabs/demo-web test` → FAIL.
- [ ] **Step 2: Implement** page, token bars (text values and labels, not colour alone; bars are decorative with `aria-hidden` plus a text cost), slider with an accessible name and value text, i18n, registry entry.
- [ ] **Step 3: Verify:** tests, `typecheck`, `build`, `corepack pnpm -r typecheck && corepack pnpm -r test`; then in a real browser (dev server; stop it afterwards): every fixture group renders, the slider updates live, no console errors, 375 px and 1280 px without horizontal page scroll, es and en, light and dark, keyboard operation of the slider (arrow keys). Report what could not be verified.
- [ ] **Step 4: Commit** `feat(demo-web): add the context page`.

### Task 6: Android demo (after Tasks 1 and 4; parallel with Tasks 5 and 7)

**Files:**
- Create: `demos/android/src/main/kotlin/es/sebas1705/ilabs/demo/{ContextScreen,ContextViewModel,ContextTimeline}.kt`, `demos/android/src/test/kotlin/es/sebas1705/ilabs/demo/ContextViewModelTest.kt`
- Modify: `demos/android/build.gradle.kts` (`implementation(project(":modules:context-kotlin"))`), `ModuleHome.kt`, `MainActivity.kt`, `res/values/strings.xml`, `res/values-es/strings.xml`

**Interfaces:**
- Consumes: Task 4's API; `FixtureLoader` and the module home/navigation of the existing demo; assets `contracts/context/*.json` (copied by `copyContracts`).
- Produces: a third module on the home list; `ContextViewModel(loader: FixtureLoader, savedState: SavedStateHandle, runner = ...)` exposing fixture names/groups, selected fixture, conversation with per-message cost, slider budget (saved state), the window/summarize result, the "needs N" state, the summarizer request and summary message for summarize fixtures, partial `loadError`, `runError`; a screen with the same content and behavior as the web page (slider with accessible value, token bars with text, 48 dp targets, centered max width on tablets, insets, es + en, `plurals` for counts, one spoken sentence per row).

- [ ] **Step 1: Write the failing `ContextViewModelTest` (JVM):** budget changes recompute kept/dropped rows exactly as `slidingWindow`; a too-small budget exposes the needs-N state; a summarize fixture exposes the summarizer request and the summary; one malformed fixture among valid ones keeps the valid ones with a partial `loadError`; a throwing runner sets `runError` without crashing; selection and budget survive via `SavedStateHandle`; every fixture (count derived from the contracts directory, lower bound) runs and matches its `expect`. Run `./gradlew :demos:android:testDebugUnitTest` → FAIL.
- [ ] **Step 2: Implement** ViewModel, screen, module home entry, strings.
- [ ] **Step 3: Verify:** `./gradlew jvmContractTests :demos:android:testDebugUnitTest :demos:android:assembleDebug --rerun-tasks` → PASS; emulator (dedicated AVD `ilabs_api36`; never wipe other AVDs): home lists three modules; for each fixture group check the screen against the fixtures; drag the slider; es and en, light and dark, phone and tablet size (`wm size`, reset afterwards), font scale 1.5, rotation, back to home, process death and "Don't keep activities" keep module, selection and budget. Screenshots stay outside the repo. Report what could not be verified (TalkBack, foldables).
- [ ] **Step 4: Commit** `feat(demo-android): add the context screen`.

### Task 7: Module docs (after Tasks 3 and 4; parallel with Tasks 5 and 6)

**Files:**
- Create: `modules/context/README.md`
- Modify: `README.md` (Modules table: `context` done; run commands)

**Interfaces:** consumes the APIs of Tasks 3 and 4.

- [ ] **Step 1: Write `modules/context/README.md`:** purpose, token cost rule, `slidingWindow` and `summarizeContext` behavior in short, the summary message format, errors (`budget_too_small`, provider errors propagate), usage snippets in TypeScript and Kotlin that compile against the real APIs (a conversation over budget; a summarize call with `MockProvider`), link to `contracts/context/README.md`. Style follows `modules/structured/README.md`. Update the root README Modules table and commands.
- [ ] **Step 2: Verify** both snippets compile and run (throwaway copies outside the repo or temporary tests deleted before the commit) and their printed output matches the README; links resolve.
- [ ] **Step 3: Commit** `docs: document the context module`.

---

## Execution order (parallelism)

1. **Wave 1 (parallel, independent worktrees):** Task 1 (Android groundwork) and Task 2 (contract + fixtures); review both in parallel; integrate (cherry-pick).
2. **Wave 2 (parallel):** Task 3 (TS) and Task 4 (Kotlin) from the integrated tip; review both in parallel with the strongest model for the reviewer comparing them against the contract (and against each other); integrate; fix rounds as needed.
3. **Wave 3 (parallel):** Tasks 5 (web), 6 (Android) and 7 (docs); review in parallel; integrate.
4. **Final:** one whole-branch review (cross-language parity hunt with a differential harness, security/hygiene, clean-clone verification of the exact CI commands), ONE fix wave, one scoped re-review, then squash-merge only when the owner asks.

## Self-review notes

- Spec coverage: groundwork (Task 1), token cost, window, summarize, fixtures (Tasks 2-4), demos (5-6), docs (7); CI needs no edit (the aggregate task).
- Types consistent across tasks: `TokenCounter`, `WindowOptions/Result`, `SummarizeOptions/Result`, `BudgetTooSmallError/Exception`, `SUMMARY_PREFIX`, `runFixture` with `calls` and `summarizerRequest`.
- Not covered by design: real tokenizers, RAG, pushing or tagging (owner's call).
