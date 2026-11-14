package es.sebas1705.ilabs.context

import es.sebas1705.ilabs.context.contract.*

import es.sebas1705.ilabs.providers.Message
import es.sebas1705.ilabs.providers.Role
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContractTest {
    private fun contractsDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "contracts/context")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("contracts/context not found above ${System.getProperty("user.dir")}")
    }

    private val fixtures: List<Fixture> by lazy {
        contractsDir().listFiles { f -> f.extension == "json" }!!
            .sortedBy { it.name }
            .map { parseFixture(it.readText()) }
    }

    @Test
    fun loadsAllFixtures() {
        assertTrue(fixtures.size >= 48, "expected at least 48 fixtures, got ${fixtures.size}")
    }

    @Test
    fun everyFixtureMatchesItsExpectation() = runTest {
        val failures = mutableListOf<String>()
        for (fx in fixtures) {
            fun check(what: String, expected: Any?, actual: Any?) {
                if (expected != actual) failures += "${fx.name}: $what: expected <$expected> but was <$actual>"
            }
            val out = try {
                runFixture(fx)
            } catch (e: Exception) {
                failures += "${fx.name}: threw $e"
                continue
            }
            val want: JsonObject = fx.expect.result
            val got = out.result
            val wantError = want["error"] as? JsonObject
            if (wantError != null) {
                if (got is FixtureResult.Failure) {
                    check("error kind", wantError.getValue("kind").jsonPrimitive.content, got.kind)
                    val required = wantError["required"] as? JsonPrimitive
                    if (required != null) check("required", required.int, got.required) else check("required", null, got.required)
                } else {
                    failures += "${fx.name}: expected an error but got <$got>"
                }
            } else if (got is FixtureResult.Success) {
                val messages = Json.decodeFromJsonElement(ListSerializer(Message.serializer()), want.getValue("messages"))
                check("messages", messages, got.messages)
                check("droppedCount", want.getValue("droppedCount").jsonPrimitive.int, got.droppedCount)
                check("tokensBefore", want.getValue("tokensBefore").jsonPrimitive.int, got.tokensBefore)
                check("tokensAfter", want.getValue("tokensAfter").jsonPrimitive.int, got.tokensAfter)
                val wantSummarized = want["summarized"] as? JsonPrimitive
                val wantSummaryCalls = want["summaryCalls"] as? JsonPrimitive
                if (fx.setup.kind == "summarize" && (wantSummarized == null || wantSummaryCalls == null)) {
                    failures += "${fx.name}: a summarize success expectation must carry summarized and summaryCalls"
                }
                wantSummarized?.let { check("summarized", it.boolean, got.summarized) }
                wantSummaryCalls?.let { check("summaryCalls", it.int, got.summaryCalls) }
            } else {
                failures += "${fx.name}: expected a result but got <$got>"
            }
            if (fx.setup.kind == "summarize") {
                if (fx.expect.calls == null) failures += "${fx.name}: summarize fixtures must declare expect.calls"
                check("calls", fx.expect.calls, out.calls)
            } else {
                check("calls", 0, out.calls)
            }

            val req = out.summarizerRequest
            if (out.calls == 0) {
                check("summarizerRequest when no call was made", null, req)
                continue
            }
            if (req == null) {
                failures += "${fx.name}: a call was made but no summarizer request was recorded"
                continue
            }
            check("summarizer request roles", listOf(Role.SYSTEM, Role.USER), req.messages.map { it.role })
            check("summarizer request maxTokens", null, req.maxTokens)
            req.messages.forEachIndexed { i, m ->
                if (m.content.isEmpty()) failures += "${fx.name}: summarizer request message $i has empty content"
            }
            val user = req.messages.lastOrNull { it.role == Role.USER }?.content.orEmpty()
            var from = 0
            for (s in fx.expect.transcriptContains.orEmpty()) {
                val at = user.indexOf(s, from)
                if (at < 0) {
                    failures += "${fx.name}: transcript does not contain <$s> after offset $from"
                    break
                }
                from = at + s.length
            }
            for (s in fx.expect.requestExcludes.orEmpty()) {
                if (req.messages.any { it.content.contains(s) }) failures += "${fx.name}: request contains excluded <$s>"
            }
        }
        assertTrue(failures.isEmpty(), "${failures.size} contract check(s) failed:\n" + failures.joinToString("\n"))
    }

    @Test
    fun fixturesHaveOnlyKnownKeysAndTheFieldsTheirKindRequires() {
        val failures = mutableListOf<String>()
        val resultKeys = mapOf(
            "window" to setOf("messages", "droppedCount", "tokensBefore", "tokensAfter"),
            "summarize" to setOf("messages", "droppedCount", "tokensBefore", "tokensAfter", "summarized", "summaryCalls"),
        )
        for (file in contractsDir().listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
            val raw = Json.parseToJsonElement(file.readText()).jsonObject
            val name = file.name
            fun unknown(where: String, obj: JsonObject, allowed: Set<String>) {
                val extra = obj.keys - allowed
                if (extra.isNotEmpty()) failures += "$name: unknown key(s) in $where: $extra"
            }
            unknown("fixture", raw, setOf("name", "setup", "messages", "provider", "expect"))
            val setup = raw.getValue("setup").jsonObject
            unknown("setup", setup, setOf("kind", "maxTokens", "keepRecent"))
            val expect = raw.getValue("expect").jsonObject
            unknown("expect", expect, setOf("result", "calls", "transcriptContains", "requestExcludes"))
            val result = expect.getValue("result").jsonObject
            val kind = setup.getValue("kind").jsonPrimitive.content
            val error = result["error"] as? JsonObject
            if (error != null) {
                unknown("result", result, setOf("error"))
                unknown("result.error", error, setOf("kind", "required"))
            } else {
                val allowed = resultKeys[kind]
                if (allowed == null) failures += "$name: unknown setup kind $kind"
                else if (result.keys != allowed) failures += "$name: result keys ${result.keys} differ from $allowed"
            }
            if (kind == "summarize") {
                if ("provider" !in raw) failures += "$name: summarize fixtures need a provider"
                if ("calls" !in expect) failures += "$name: summarize fixtures need expect.calls"
            } else {
                if ("provider" in raw) failures += "$name: window fixtures have no provider"
                if ("calls" in expect) failures += "$name: window fixtures have no calls"
            }
        }
        assertTrue(failures.isEmpty(), "${failures.size} fixture shape problem(s):\n" + failures.joinToString("\n"))
    }

    @Test
    fun parsesTheLoneSurrogateEscapesIntoTwoCodePoints() {
        val fx = fixtures.first { it.name == "window-lone-surrogate-cost" }
        val content = fx.messages[0].content
        assertEquals(5, content.length)
        assertEquals('\uDC00', content[0])
        assertEquals('\uD800', content[1])
        assertEquals(2, estimateTokens(content))
    }

    private fun fixtureJson(setup: String, provider: String? = null): String =
        """{"name":"x","setup":$setup,"messages":[],${provider?.let { "\"provider\":$it," } ?: ""}"expect":{"result":{"messages":[]}}}"""

    @Test
    fun rejectsAnUnknownSetupKind() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            runFixture(parseFixture(fixtureJson("""{"kind":"other","maxTokens":10}""")))
        }
        assertEquals("invalid fixture: unknown setup kind: other", e.message)
    }

    @Test
    fun rejectsASummarizeSetupWithoutProvider() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            runFixture(parseFixture(fixtureJson("""{"kind":"summarize","maxTokens":10}""")))
        }
        assertEquals("invalid fixture: summarize setup needs a provider", e.message)
    }

    @Test
    fun rejectsAScriptEntryWithNeitherResponseNorError() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            runFixture(
                parseFixture(
                    fixtureJson(
                        """{"kind":"summarize","maxTokens":10}""",
                        """{"id":"p","script":[{"response":"ok"},{}]}""",
                    ),
                ),
            )
        }
        assertEquals("invalid fixture: script entry 2 of provider p has neither response nor error", e.message)
    }
}
