package es.sebas1705.ilabs.structured

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import es.sebas1705.ilabs.providers.MockProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContractTest {
    private fun contractsDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "contracts/structured")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("contracts/structured not found above ${System.getProperty("user.dir")}")
    }

    private val fixtures: List<Fixture> by lazy {
        contractsDir().listFiles { f -> f.extension == "json" }!!
            .sortedBy { it.name }
            .map { parseFixture(it.readText()) }
    }

    @Test
    fun loadsAllFixtures() {
        assertTrue(fixtures.size >= 70, "expected at least 70 fixtures, got ${fixtures.size}")
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
                    check("error kind", (wantError["kind"] as JsonPrimitive).content, got.kind.wire)
                    val wantIssues = wantError["issues"] as? JsonArray
                    if (wantIssues != null) {
                        val expected = wantIssues.map {
                            val o = it as JsonObject
                            (o["path"] as JsonPrimitive).content to (o["code"] as JsonPrimitive).content
                        }
                        check("issues", expected, got.issues?.map { it.path to it.code.wire })
                    }
                } else {
                    failures += "${fx.name}: expected an error but got <$got>"
                }
            } else if ("value" in want) {
                if (got is FixtureResult.Success) {
                    if (!jsonEqual(want.getValue("value"), got.value)) {
                        failures += "${fx.name}: value: expected <${want["value"]}> but was <${got.value}>"
                    }
                } else {
                    failures += "${fx.name}: expected a value but got <$got>"
                }
            } else {
                failures += "${fx.name}: expect.result has neither value nor error"
            }
            check("attempts", fx.expect.attempts, out.attempts)
            check("appended", fx.expect.appended, out.appended)
        }
        assertTrue(failures.isEmpty(), "${failures.size} contract check(s) failed:\n" + failures.joinToString("\n"))
    }

    private fun byName(name: String): Fixture = fixtures.first { it.name == name }

    @Test
    fun keepsTheTraceOfRepliesReceivedBeforeAProviderError() = runTest {
        val fx = byName("loop-provider-error-mid")
        val out = runFixture(fx)
        assertEquals(2, out.attempts)
        assertEquals(1, out.trace.size)
        val reply = fx.provider.script[0].response!!
        val extracted = extractJson(reply)
        assertTrue(extracted is Extraction.Found)
        val want = validate(fx.schema, extracted.value)
        assertTrue(want.isNotEmpty())
        assertEquals(AttemptTrace(reply, extracted, want), out.trace[0])
    }

    @Test
    fun derivesNoJsonIssueForAnUnparseableReplyBeforeScriptExhaustion() = runTest {
        val out = runFixture(byName("loop-script-exhausted"))
        assertEquals(2, out.attempts)
        assertEquals(1, out.trace.size)
        assertEquals("no json here", out.trace[0].reply)
        assertEquals(listOf(Issue("", IssueCode.NO_JSON)), out.trace[0].issues)
        assertTrue(out.trace[0].extracted is Extraction.Failed)
    }

    @Test
    fun hasAnEmptyTraceWhenTheFirstCallFails() = runTest {
        val out = runFixture(byName("loop-provider-error-first"))
        assertEquals(1, out.attempts)
        assertTrue(out.trace.isEmpty())
    }

    @Test
    fun leavesANormalRunTraceEqualToStructuredsOwn() = runTest {
        val fx = byName("loop-repair-second")
        val out = runFixture(fx)
        val own = structured(MockProvider(fx.provider.id, fx.provider.script), fx.request, fx.schema, StructuredOptions(fx.setup.maxRepairs))
        assertEquals(own.trace, out.trace)
        assertEquals(2, out.trace.size)
    }

    @Test
    fun traceLengthEqualsAttemptsForEverySuccessOrStructuredFailure() = runTest {
        var checked = 0
        for (fx in fixtures) {
            val err = fx.expect.result["error"] as? JsonObject
            val issues = err?.get("issues") as? JsonArray
            if (err != null && (issues == null || issues.isEmpty())) continue
            val out = runFixture(fx)
            assertEquals(out.attempts, out.trace.size, fx.name)
            checked++
        }
        assertTrue(checked > 50, "checked only $checked fixtures")
    }
}
