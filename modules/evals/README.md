# evals

Compare models and prompts on a small suite, deterministically, with identical behavior in Kotlin and TypeScript. A suite is a list of cases (a conversation plus graders); `runSuite` sends every case to one or more targets, grades each reply and reports who passed what. The behavior is pinned by the shared fixtures in [`contracts/evals`](../../contracts/evals/README.md); read that README for the exact rules and the fixture format.

It builds on [`providers`](../providers/README.md) for the calls: every target and the optional judge is a `Provider`, including `MockProvider` for tests and demos (no API key needed). The `json` and `jsonEquals` graders reuse [`structured`](../structured/README.md)'s extraction, schema validation and JSON equality. It is independent of [`context`](../context/README.md).

## Suite format

```json
{
  "cases": [
    {
      "id": "capital",
      "messages": [{ "role": "user", "content": "What is the capital of France?" }],
      "graders": [{ "kind": "equals", "value": "Paris", "trim": true, "ignoreCase": true }, { "kind": "length", "max": 20 }]
    }
  ]
}
```

- `cases` is a non-empty list. Case ids are non-empty and unique.
- `messages` is a list of `providers` messages (`role` is `system`, `user` or `assistant`, `content` a string). It may be empty, and it is sent to each target as is.
- `graders` is a non-empty list. Every grader runs, in order, on the reply exactly as returned (no normalization and no short circuit).

In TypeScript a suite is a plain object typed `Suite`. In Kotlin it is `Suite(cases: List<EvalCase>)` and the graders are a sealed type: `Equals`, `Contains`, `NotContains`, `StartsWith`, `EndsWith`, `OneOf`, `Length`, `Json`, `JsonEquals` (all `PureGrader`) and `Judge`.

## Graders

| Kind | Parameters | Passes when |
|---|---|---|
| `equals` | `value`, `trim?`, `ignoreCase?` | the reply equals `value` |
| `contains` | same | the reply contains `value` (`""` always passes) |
| `notContains` | same | the reply does not contain `value` (`""` always fails) |
| `startsWith` | same | the reply starts with `value` (`""` always passes) |
| `endsWith` | same | the reply ends with `value` (`""` always passes) |
| `oneOf` | `values` (non-empty), `trim?`, `ignoreCase?` | the reply equals one of `values` |
| `length` | `min?`, `max?` (at least one, integers `>= 0`, `min <= max`) | the reply length in Unicode code points is within the bounds, inclusive |
| `json` | `schema` (must pass `structured`'s `checkSchema`) | a JSON value can be extracted from the reply and it has no schema issues |
| `jsonEquals` | `value` (any JSON value, `null` included; the key is required) | a JSON value can be extracted and deep-equals `value` (key order ignored, numbers by value) |
| `judge` | `rubric` (a non-empty string) | the judge provider answers `PASS` (see below) |

The string options are exact and the same for every string kind:

- `trim` removes only JSON whitespace (space, tab, LF and CR) from both ends of the reply and of the compared value (each candidate in `oneOf`). A no-break space is kept.
- `ignoreCase` then folds ASCII letters only (`A`-`Z` to `a`-`z`) on both sides. Every other character is compared as is: `É` does not match `é`, `ß` does not match `SS`, and `İ`/`ı` do not match `i`/`I`. Locale and Unicode case rules differ between platforms, so they are left out.
- The empty-string rules apply after these steps, so a value that `trim` reduces to `""` behaves as `""`.
- `length` counts code points, not UTF-16 units: an emoji is one, and a lone surrogate counts as one on its own.

`json` and `jsonEquals` extract the value as `structured` does (the whole text, then a fenced code block, then the first balanced `{...}` or `[...]` region), so prose or a markdown fence around the JSON is fine. They always report `issues`: `[{ path: "", code: "no_json" | "invalid_json" }]` when extraction fails, the schema issues for `json`, and an empty list otherwise (a `jsonEquals` mismatch has no issues). The other graders never report issues.

There is no `regex` grader on purpose: the ECMAScript and Java regex dialects differ at the edges (`$`, `\w`, case folding), so the same pattern could pass in one language and fail in the other, which would break the identical-results contract. A `regex` kind is rejected as an unknown kind.

`evaluateGrader(grader, reply)` is the pure form of one grader other than `judge` (the demos' grader playground uses it). It takes a `PureGrader` and returns a `GraderResult`.

## The judge

A `judge` grader asks the `judge` provider passed to `runSuite` to rate the reply against the rubric. When a judge is supplied and the target call succeeded, each judge grader makes exactly one judge call, in grader order, case by case and target by target, and every call is counted (also a failing one). Otherwise no judge call is made: without a judge the grader errors with `no_judge`, and a failed target call gives `graders: []`.

- **Request.** Exactly 2 messages and no other fields: a `system` instruction, then a `user` message that contains, in this order, the `rubric`, the content of the case's last `user` message (the empty string when there is none) and the reply. The contract pins this shape, not the wording: the instruction text and the labels around the three parts differ between the Kotlin and TypeScript implementations.
- **Verdict.** Leading JSON whitespace is removed from the judge reply; it must then start with `PASS` or `FAIL` (case-sensitive), followed by the end of the text or a character that is not an ASCII letter, digit or underscore. `PASS`, `PASS.`, `PASS: polite enough` and `FAIL - too curt` are verdicts; `PASSED`, `pass`, `PASS1` and `Verdict: PASS` are not.
- **Outcome.** `PASS` passes and `FAIL` fails. Anything else is the error `bad_verdict`. With no judge supplied the grader errors with `no_judge` and no call is made. A judge provider error gives its error kind (`rate_limit`, `timeout`, `invalid_output` or `unavailable`). An errored grader has `passed: false`.

## Results

`runSuite` returns `{ results, summary, calls }`.

**`results`** has one entry per (case, target), case-major then target-minor, with `caseId`, `targetId`, `status`, `reply`, `error` and `graders` (one `{ kind, passed, issues?, error? }` per grader). Exactly one call is made per pair, with exactly `case.messages`.

| Status | When |
|---|---|
| `pass` | every grader passed |
| `fail` | at least one grader failed and none errored |
| `error` | a grader errored (`no_judge`, `bad_verdict` or a judge provider error), or the target call failed |

When the target call fails with a provider error, the result has `status: "error"`, `error` set to the error kind, no `reply` and `graders: []` (so no judge call is made), and the run continues with the next pair. Any other exception (and, in Kotlin, `CancellationException` or an `Error`) aborts the run and is rethrown unchanged.

**`summary`** is `summarize(results)`: one `{ targetId, total, passed, failed, errored, passRate }` per target, in order of first appearance, with `passRate = passed / total` as a double. `summarize` is exported, so you can summarize a filtered list of results too; an empty list gives an empty summary.

**`calls`** maps every target id to the number of calls made to it, and has the key `judge` only when a judge was supplied (0 when it was never called).

## Validation

The suite and the targets are validated completely before any call. On any problem `runSuite` throws `invalid suite: <reason>` and no provider is called. The prefix is pinned, the reason wording is not (it differs between the two implementations). TypeScript throws `InvalidSuiteError` (an `Error`); Kotlin throws `IllegalArgumentException`.

- The suite rules: the shape above, a known grader `kind` (case-sensitive), well-formed grader parameters, non-empty and unique case ids.
- Target ids must be non-empty and unique, and `judge` is reserved (it is the judge's key in `calls`), whether or not a judge is supplied.
- `parseSuite(json)` is the way in for a suite stored as JSON (fixtures, demos, files). It accepts a parsed JSON value or a string (parsed as strict JSON: TypeScript uses `JSON.parse`, with no depth limit, and Kotlin uses `structured`'s strict parser, which rejects nesting deeper than 128 levels; see [Limits](#limits)) and checks everything except the targets and the judge. It also rejects unknown keys in the suite, a case, a message or a grader (so `trim` on `length`, or the typo `ignorecase`), except inside `json.schema` and `jsonEquals.value`.
- A typed suite cannot carry unknown keys in Kotlin. In TypeScript, `runSuite` on a hand-built object checks the typed fields and does not inspect extra properties, so pass untrusted JSON through `parseSuite` first.

## TypeScript

```ts
import { MockProvider } from "@ilabs/providers";
import { evaluateGrader, InvalidSuiteError, parseSuite, runSuite, type Suite } from "@ilabs/evals";

const suite: Suite = {
  cases: [
    {
      id: "capital",
      messages: [{ role: "user", content: "What is the capital of France?" }],
      graders: [
        { kind: "equals", value: "Paris", trim: true, ignoreCase: true },
        { kind: "length", max: 20 },
      ],
    },
    {
      id: "greeting",
      messages: [{ role: "user", content: "Say hello" }],
      graders: [
        { kind: "contains", value: "hello", ignoreCase: true },
        { kind: "judge", rubric: "The reply is a friendly greeting." },
      ],
    },
  ],
};

// Two mock targets (one scripted reply per case) and a mock judge (one verdict per judge call).
const small = new MockProvider("small", [{ response: "  paris\n" }, { response: "Hey there!" }]);
const large = new MockProvider("large", [{ response: "The capital of France is Paris." }, { response: "Hello! How can I help?" }]);
const judge = new MockProvider("judge-model", [{ response: "PASS" }, { response: "Looks friendly to me." }]);

const out = await runSuite(suite, { targets: [small, large], judge });
for (const r of out.results) {
  console.log(`${r.caseId}/${r.targetId}: ${r.status}`, r.graders.map((g) => g.error ?? g.passed));
}
// capital/small: pass [ true, true ]
// capital/large: fail [ false, false ]
// greeting/small: fail [ false, true ]
// greeting/large: error [ true, 'bad_verdict' ]
for (const s of out.summary) {
  console.log(s.targetId, s.total, s.passed, s.failed, s.errored, s.passRate);
}
// small 2 1 1 0 0.5
// large 2 0 1 1 0
console.log(out.calls); // { small: 2, large: 2, judge: 2 }

// A suite stored as JSON goes through parseSuite, which also rejects unknown keys.
const parsed = parseSuite('{"cases": [{"id": "c1", "messages": [], "graders": [{"kind": "length", "min": 1}]}]}');
console.log(parsed.cases[0]?.graders); // [ { kind: 'length', min: 1 } ]
try {
  parseSuite({ cases: [{ id: "c1", messages: [], graders: [{ kind: "length", min: 1, trim: true }] }] });
} catch (error) {
  if (error instanceof InvalidSuiteError) console.log(error.message); // invalid suite: unknown key "trim" in cases[0].graders[0]
}

// One pure grader on its own (code points: two emoji are 2, not 4).
console.log(evaluateGrader({ kind: "length", max: 2 }, "😀😀")); // { kind: 'length', passed: true }
const json = evaluateGrader({ kind: "json", schema: { type: "object", required: ["name"] } }, "Sure: {}");
console.log(json.passed, json.issues); // false [ { path: '/name', code: 'required' } ]
```

The second judge reply, `Looks friendly to me.`, does not start with a verdict, so that grader errors with `bad_verdict` and the result is `error` even though the reply was fine.

## Kotlin

```kotlin
import es.sebas1705.ilabs.evals.*
import es.sebas1705.ilabs.evals.Json as JsonGrader
import es.sebas1705.ilabs.providers.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

suspend fun demo() {
    val suite = Suite(
        listOf(
            EvalCase(
                "capital",
                listOf(Message(Role.USER, "What is the capital of France?")),
                listOf(Equals("Paris", trim = true, ignoreCase = true), Length(max = 20)),
            ),
            EvalCase(
                "greeting",
                listOf(Message(Role.USER, "Say hello")),
                listOf(Contains("hello", ignoreCase = true), Judge("The reply is a friendly greeting.")),
            ),
        ),
    )

    // Two mock targets (one scripted reply per case) and a mock judge (one verdict per judge call).
    val small = MockProvider("small", listOf(ScriptEntry(response = "  paris\n"), ScriptEntry(response = "Hey there!")))
    val large = MockProvider(
        "large",
        listOf(ScriptEntry(response = "The capital of France is Paris."), ScriptEntry(response = "Hello! How can I help?")),
    )
    val judge = MockProvider("judge-model", listOf(ScriptEntry(response = "PASS"), ScriptEntry(response = "Looks friendly to me.")))

    val out = runSuite(suite, listOf(small, large), judge)
    for (r in out.results) {
        println("${r.caseId}/${r.targetId}: ${r.status.wire} ${r.graders.map { it.error ?: it.passed }}")
    }
    // capital/small: pass [true, true]
    // capital/large: fail [false, false]
    // greeting/small: fail [false, true]
    // greeting/large: error [true, bad_verdict]
    for (s in out.summary) println("${s.targetId} ${s.total} ${s.passed} ${s.failed} ${s.errored} ${s.passRate}")
    // small 2 1 1 0 0.5
    // large 2 0 1 1 0.0
    println(out.calls) // {small=2, large=2, judge=2}

    // A suite stored as JSON goes through parseSuite, which also rejects unknown keys.
    val parsed = parseSuite("""{"cases": [{"id": "c1", "messages": [], "graders": [{"kind": "length", "min": 1}]}]}""")
    println(parsed.cases[0].graders) // [Length(min=1, max=null)]
    try {
        parseSuite("""{"cases": [{"id": "c1", "messages": [], "graders": [{"kind": "length", "min": 1, "trim": true}]}]}""")
    } catch (e: IllegalArgumentException) {
        println(e.message) // invalid suite: unknown key "trim" in case 1 grader 1 (length)
    }

    // One pure grader on its own (code points: two emoji are 2, not 4).
    println(evaluateGrader(Length(max = 2), "😀😀")) // GraderResult(kind=length, passed=true, issues=null, error=null)
    // `Json` here is kotlinx's; the evals grader is imported as `JsonGrader` to avoid the name clash.
    val schema = Json.parseToJsonElement("""{"type": "object", "required": ["name"]}""").jsonObject
    val r = evaluateGrader(JsonGrader(schema), "Sure: {}")
    println("${r.passed} ${r.issues}") // false [Issue(path=/name, code=REQUIRED)]
}
```

In Kotlin `runSuite(suite, targets, judge = null)` takes the targets and the judge as parameters, `status` is the `Status` enum (its `wire` is the contract string), a missing `reply`, `error` or `issues` is `null`, and issue codes are `structured`'s `IssueCode` enum. `runSuite` is a `suspend` function.

## Composing with `providers`

Retries, fallbacks and concurrency are not part of `evals`: compose them from [`providers`](../providers/README.md) around a target (or the judge). Wrap it in `RetryingProvider` and/or `FallbackProvider` and pass the result as the target:

```ts
// `suite` is the suite from the TypeScript example above.
import { MockProvider, RetryingProvider } from "@ilabs/providers";
import { runSuite } from "@ilabs/evals";

// The first call fails with rate_limit; RetryingProvider retries it, so the run sees one successful call per case.
const primary = new MockProvider("primary", [{ error: { kind: "rate_limit" } }, { response: "Paris" }, { response: "hello" }]);
const target = new RetryingProvider(primary, {
  maxAttempts: 2,
  baseDelayMs: 100,
  maxDelayMs: 1000,
  sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
});

const out = await runSuite(suite, { targets: [target], judge: new MockProvider("judge-model", [{ response: "PASS" }]) });
console.log(out.results.map((r) => r.status), out.calls); // [ 'pass', 'pass' ] { primary: 2, judge: 1 }
```

- `calls` counts the calls `evals` makes to the target (one per case), not the attempts inside the chain: above, `primary` made 3 calls but `calls` says 2.
- The target id is the wrapper's `id`. `RetryingProvider` keeps the inner provider's id, but `FallbackProvider`'s id is always `fallback`, so two fallback chains in one run are duplicate target ids (`invalid suite`). Give each chain its own id with a small `Provider` that delegates to it.
- In Kotlin build the chain the same way (`RetryOptions(maxAttempts = 2, baseDelayMs = 100, maxDelayMs = 1000, sleep = { ms -> delay(ms) })`) and pass it in the `targets` list.

## Limits

- Calls are sequential: one at a time, case-major then target-minor. There are no scoring weights, cost or latency metrics, or persistence of results.
- No `regex` grader (see [Graders](#graders)).
- The judge request wording is not pinned: only the shape and the order of the rubric, the last user message and the reply are. A real judge model may need a rubric that asks for `PASS` or `FAIL` first; anything else is `bad_verdict`.
- `length` bounds above 2^31 - 1 are out of scope. Kotlin clamps them to `Int.MAX_VALUE` (after checking `min <= max` on the original values), which keeps the outcome since no reply is that long; TypeScript keeps them as numbers.
- Kotlin `parseSuite(String)` rejects JSON nested deeper than 128 levels (the limit of `structured`'s strict parser) while TypeScript accepts it; the contract allows both. `parseSuite(JsonElement)` expects a strictly parsed element: output of a lenient parser is the caller's responsibility.
- `runSuite` validates in a different order: TypeScript checks the suite first, then the targets and the judge; Kotlin checks the targets first. Both finish before any call, so only the reported reason can differ when both the suite and the targets are invalid, and the reason wording is not pinned anyway.
- Kotlin: the grader class `Json` clashes with `kotlinx.serialization.json.Json` when both packages are star-imported. Import one of them explicitly or alias the grader: `import es.sebas1705.ilabs.evals.Json as JsonGrader`, as in the Kotlin example.

## Contract

[`contracts/evals/README.md`](../../contracts/evals/README.md) defines the behavior and the fixture format. Both implementations must pass every fixture in that folder: `runFixture` (exported by the TypeScript package and, in Kotlin, from `es.sebas1705.ilabs.evals.contract`) runs one fixture with recording mock providers. The design is in [the evals design spec](../../docs/superpowers/specs/2026-11-14-evals-design.md).
