package es.sebas1705.ilabs.evals.contract

import es.sebas1705.ilabs.evals.CaseResult
import es.sebas1705.ilabs.evals.EvalCase
import es.sebas1705.ilabs.evals.GraderResult
import es.sebas1705.ilabs.evals.RunOutput
import es.sebas1705.ilabs.evals.TargetSummary
import es.sebas1705.ilabs.evals.parseSuite
import es.sebas1705.ilabs.evals.runSuite
import es.sebas1705.ilabs.providers.CompletionRequest
import es.sebas1705.ilabs.providers.CompletionResponse
import es.sebas1705.ilabs.providers.ErrorKind
import es.sebas1705.ilabs.providers.MockProvider
import es.sebas1705.ilabs.providers.Provider
import es.sebas1705.ilabs.providers.Role
import es.sebas1705.ilabs.providers.ScriptEntry
import es.sebas1705.ilabs.providers.ScriptError
import es.sebas1705.ilabs.structured.parseStrict
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** A contract fixture as raw JSON: the suite may be malformed on purpose, so nothing is decoded eagerly. */
class Fixture(val root: JsonObject) {
    val name: String get() = (root["name"] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content ?: ""
}

/**
 * What a run produced. `output` is null when the suite was rejected (`invalidSuite`); `recordedCalls` counts the calls
 * the harness saw per provider id (the key `judge` only when a judge was supplied), whatever `runSuite` reported.
 */
data class FixtureOutcome(
    val invalidSuite: Boolean,
    val invalidSuiteMessage: String?,
    val output: RunOutput?,
    val judgeRequests: List<CompletionRequest>,
    val targetRequests: Map<String, List<CompletionRequest>>,
    val recordedCalls: Map<String, Int>,
)

private fun invalid(reason: String): Nothing = throw IllegalStateException("invalid fixture: $reason")

fun parseFixture(json: String): Fixture {
    val el = parseStrict(json) ?: invalid("not valid strict JSON")
    return Fixture(el as? JsonObject ?: invalid("a fixture must be a JSON object"))
}

private val TOP_LEVEL_KEYS = setOf("name", "suite", "targets", "judge", "expect")

private class ParsedFixture(
    val suite: JsonElement,
    val targets: List<Pair<String, List<ScriptEntry>>>,
    val judge: List<ScriptEntry>?,
    val invalidSuiteExpected: Boolean,
)

private fun isString(el: JsonElement?) = el is JsonPrimitive && el !is JsonNull && el.isString

private fun parseScript(id: String, el: JsonElement?): List<ScriptEntry> {
    val entries = el as? JsonArray ?: invalid("provider $id needs a script array")
    return entries.mapIndexed { i, e ->
        val n = i + 1
        val o = e as? JsonObject ?: invalid("script entry $n of provider $id is not an object")
        val response = o["response"]
        val error = o["error"]
        if (response == null && error == null) invalid("script entry $n of provider $id has neither response nor error")
        if (response != null && !isString(response)) invalid("script entry $n of provider $id has a non-string response")
        val scriptError = error?.let {
            val eo = it as? JsonObject ?: invalid("script entry $n of provider $id has a malformed error")
            val kind = (eo["kind"] as? JsonPrimitive)?.takeIf { k -> isString(k) }?.content
            val errorKind = ErrorKind.entries.firstOrNull { k -> k.wire == kind } ?: invalid("script entry $n of provider $id has an unknown error kind")
            ScriptError(errorKind, (eo["retryAfterMs"] as? JsonPrimitive)?.longOrNull)
        }
        ScriptEntry((response as? JsonPrimitive)?.content, scriptError)
    }
}

private fun isInvalidSuiteExpectation(expect: JsonObject) = expect["invalidSuite"] == JsonPrimitive(true)

private fun validateFixture(fixture: Fixture): ParsedFixture {
    val root = fixture.root
    val unknown = root.keys.firstOrNull { it !in TOP_LEVEL_KEYS }
    if (unknown != null) invalid("unknown top-level key \"$unknown\"")
    val suite = root["suite"] ?: invalid("missing suite")
    val targets = (root["targets"] as? JsonArray ?: invalid("targets must be an array")).map { t ->
        val o = t as? JsonObject ?: invalid("a target must be an object")
        val id = (o["id"] as? JsonPrimitive)?.takeIf { isString(it) }?.content ?: invalid("a target needs a string id")
        id to parseScript(id, o["script"])
    }
    val judge = root["judge"]?.let { j ->
        val o = j as? JsonObject ?: invalid("judge must be an object with a script")
        parseScript("judge", o["script"])
    }
    val expect = root["expect"] as? JsonObject ?: invalid("expect must be an object")
    val invalidExpected = isInvalidSuiteExpectation(expect)
    if (!invalidExpected && (expect["results"] !is JsonArray || expect["summary"] !is JsonArray || expect["calls"] !is JsonObject)) {
        invalid("expect must be {\"invalidSuite\": true} or an object with results, summary and calls")
    }
    return ParsedFixture(suite, targets, judge, invalidExpected)
}

private class Recorder(private val inner: Provider) : Provider {
    val requests = mutableListOf<CompletionRequest>()
    override val id: String get() = inner.id
    override suspend fun complete(req: CompletionRequest): CompletionResponse {
        requests += req
        return inner.complete(req)
    }
}

/**
 * Runs a contract fixture against `evals`: the suite goes through `parseSuite`, then `runSuite` with mock targets
 * and the optional judge (id `judge`), every provider wrapped to record its requests. A rejected suite
 * (`invalid suite:`) is reported as `invalidSuite`. Common code: no java.* (usable from the Android demo).
 */
suspend fun runFixture(fixture: Fixture): FixtureOutcome {
    val parsed = validateFixture(fixture)
    val targets = parsed.targets.map { (id, script) -> Recorder(MockProvider(id, script)) }
    val judge = parsed.judge?.let { Recorder(MockProvider("judge", it)) }

    fun outcome(message: String?, output: RunOutput?): FixtureOutcome {
        val recorded = LinkedHashMap<String, Int>()
        for (t in targets) recorded[t.id] = (recorded[t.id] ?: 0) + t.requests.size
        if (judge != null) recorded["judge"] = judge.requests.size
        return FixtureOutcome(
            invalidSuite = message != null,
            invalidSuiteMessage = message,
            output = output,
            judgeRequests = judge?.requests?.toList() ?: emptyList(),
            targetRequests = targets.groupBy({ it.id }, { it.requests.toList() }).mapValues { (_, v) -> v.flatten() },
            recordedCalls = recorded,
        )
    }

    return try {
        val suite = parseSuite(parsed.suite)
        outcome(null, runSuite(suite, targets, judge))
    } catch (e: IllegalArgumentException) {
        val message = e.message
        if (message == null || !message.startsWith("invalid suite:")) throw e
        outcome(message, null)
    }
}

// ---- checks -------------------------------------------------------------------------------------------------------

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull
private fun JsonElement?.int(): Int? = number()?.takeIf { it % 1.0 == 0.0 }?.toInt()
private fun JsonElement?.bool(): Boolean? = when ((this as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.content) {
    "true" -> true
    "false" -> false
    else -> null
}

private fun expectedIssues(g: JsonObject, out: MutableList<String>, at: String): List<Pair<String, String>> =
    (g["issues"] ?: JsonArray(emptyList())).arr()?.map {
        (it.obj()?.get("path").str() ?: "") to (it.obj()?.get("code").str() ?: "")
    } ?: run {
        out += "$at: malformed expected issues"
        emptyList()
    }

private fun checkGrader(at: String, want: JsonObject, got: GraderResult, out: MutableList<String>) {
    if (want["kind"].str() != got.kind) out += "$at: kind: expected <${want["kind"].str()}> but was <${got.kind}>"
    if (want["passed"].bool() != got.passed) out += "$at: passed: expected <${want["passed"].bool()}> but was <${got.passed}>"
    val wantError = want["error"].str()
    if (wantError != got.error) out += "$at: error: expected <$wantError> but was <${got.error}>"
    val wantIssues = expectedIssues(want, out, at)
    val gotIssues = (got.issues ?: emptyList()).map { it.path to it.code.wire }
    if (wantIssues != gotIssues) out += "$at: issues: expected <$wantIssues> but was <$gotIssues>"
}

private fun checkResult(at: String, want: JsonObject, got: CaseResult, out: MutableList<String>) {
    if (want["caseId"].str() != got.caseId) out += "$at: caseId: expected <${want["caseId"].str()}> but was <${got.caseId}>"
    if (want["targetId"].str() != got.targetId) out += "$at: targetId: expected <${want["targetId"].str()}> but was <${got.targetId}>"
    if (want["status"].str() != got.status.wire) out += "$at: status: expected <${want["status"].str()}> but was <${got.status.wire}>"
    if ("reply" in want && want["reply"].str() != got.reply) out += "$at: reply: expected <${want["reply"].str()}> but was <${got.reply}>"
    val wantError = want["error"].str()
    if (wantError != got.error) out += "$at: error: expected <$wantError> but was <${got.error}>"
    if (got.error != null && got.reply != null) out += "$at: a result with an error must have no reply"
    val wantGraders = want["graders"]
    if (wantGraders != null) {
        val list = wantGraders.arr()
        if (list == null) {
            out += "$at: malformed expected graders"
        } else if (list.size != got.graders.size) {
            out += "$at: graders: expected ${list.size} entries but was ${got.graders.size}"
        } else {
            list.forEachIndexed { i, g -> checkGrader("$at grader[$i]", g.obj() ?: JsonObject(emptyMap()), got.graders[i], out) }
        }
    }
}

private fun checkSummary(at: String, want: JsonObject, got: TargetSummary, out: MutableList<String>) {
    if (want["targetId"].str() != got.targetId) out += "$at: targetId: expected <${want["targetId"].str()}> but was <${got.targetId}>"
    for ((key, actual) in listOf("total" to got.total, "passed" to got.passed, "failed" to got.failed, "errored" to got.errored)) {
        if (want[key].int() != actual) out += "$at: $key: expected <${want[key].int()}> but was <$actual>"
    }
    // passRate is an IEEE-754 double VALUE: `0` and `0.0` are equal.
    if (want["passRate"].number() != got.passRate) out += "$at: passRate: expected <${want["passRate"].number()}> but was <${got.passRate}>"
}

private fun checkJudgeRequest(at: String, req: CompletionRequest, out: MutableList<String>) {
    if (req.maxTokens != null) out += "$at: a judge request carries only messages"
    if (req.messages.size != 2) {
        out += "$at: a judge request has exactly 2 messages but has ${req.messages.size}"
        return
    }
    if (req.messages[0].role != Role.SYSTEM || req.messages[1].role != Role.USER) out += "$at: the roles must be system then user"
    if (req.messages.any { it.content.isEmpty() }) out += "$at: both judge messages must be non-empty"
}

/** Finds each string, in order, as non-overlapping substrings of [text]; returns the first one that is not found. */
private fun firstMissingInOrder(text: String, parts: List<String>): String? {
    var from = 0
    for (p in parts) {
        val at = text.indexOf(p, from)
        if (at < 0) return p
        from = at + p.length
    }
    return null
}

/**
 * Compares an outcome with the fixture's expectation and applies the harness rules (target and judge request shapes,
 * `judgeContains`, call counts). Returns every failing check; an empty list means the fixture passed.
 */
fun checkOutcome(fixture: Fixture, outcome: FixtureOutcome): List<String> {
    val parsed = validateFixture(fixture)
    val expect = fixture.root["expect"].obj()!!
    val out = mutableListOf<String>()

    if (parsed.invalidSuiteExpected) {
        if (!outcome.invalidSuite) out += "expected an invalid suite but the run succeeded"
        outcome.recordedCalls.filterValues { it != 0 }.forEach { (id, n) -> out += "invalid suite: $id was called $n time(s)" }
        return out
    }
    val output = outcome.output
    if (outcome.invalidSuite || output == null) {
        out += "unexpected invalid suite: ${outcome.invalidSuiteMessage}"
        return out
    }

    val wantResults = expect["results"].arr()!!
    if (wantResults.size != output.results.size) {
        out += "results: expected ${wantResults.size} entries but was ${output.results.size}"
    } else {
        wantResults.forEachIndexed { i, r -> checkResult("results[$i]", r.obj() ?: JsonObject(emptyMap()), output.results[i], out) }
    }
    val wantSummary = expect["summary"].arr()!!
    if (wantSummary.size != output.summary.size) {
        out += "summary: expected ${wantSummary.size} entries but was ${output.summary.size}"
    } else {
        wantSummary.forEachIndexed { i, s -> checkSummary("summary[$i]", s.obj() ?: JsonObject(emptyMap()), output.summary[i], out) }
    }
    val wantCalls = expect["calls"].obj()!!.mapValues { it.value.int() }
    if (wantCalls != output.calls) out += "calls: expected <$wantCalls> but was <${output.calls}>"
    if (output.calls != outcome.recordedCalls) out += "calls: reported <${output.calls}> but the harness recorded <${outcome.recordedCalls}>"

    // Harness rules: every target request is exactly the case messages (target-minor within case-major).
    val cases: List<EvalCase> = parseSuite(parsed.suite).cases
    for ((id, requests) in outcome.targetRequests) {
        if (requests.size != cases.size) out += "target $id: expected ${cases.size} requests but saw ${requests.size}"
        requests.forEachIndexed { i, req ->
            val case = cases.getOrNull(i) ?: return@forEachIndexed
            if (req.maxTokens != null) out += "target $id request ${i + 1}: carries more than messages"
            if (req.messages != case.messages) out += "target $id request ${i + 1}: messages differ from case ${case.id}"
        }
    }
    outcome.judgeRequests.forEachIndexed { i, req -> checkJudgeRequest("judge request ${i + 1}", req, out) }

    val wantContains = expect["judgeContains"]
    if (wantContains != null) {
        val parts = wantContains.arr()?.map { it.str() }
        if (parts == null || parts.any { it == null }) {
            out += "judgeContains: malformed"
        } else if (outcome.judgeRequests.isEmpty()) {
            out += "judgeContains: the run made no judge call"
        } else {
            val user = outcome.judgeRequests.first().messages.getOrNull(1)?.content ?: ""
            val missing = firstMissingInOrder(user, parts.filterNotNull())
            if (missing != null) out += "judgeContains: <$missing> not found in order in the first judge request <$user>"
        }
    }
    return out
}
