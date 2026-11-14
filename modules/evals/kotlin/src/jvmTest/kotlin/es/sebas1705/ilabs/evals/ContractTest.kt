package es.sebas1705.ilabs.evals

import es.sebas1705.ilabs.evals.contract.Fixture
import es.sebas1705.ilabs.evals.contract.checkOutcome
import es.sebas1705.ilabs.evals.contract.parseFixture
import es.sebas1705.ilabs.evals.contract.runFixture
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContractTest {
    private fun contractsDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "contracts/evals")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("contracts/evals not found above ${System.getProperty("user.dir")}")
    }

    private val files: List<File> by lazy {
        contractsDir().listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
    }

    private val fixtures: List<Pair<File, Fixture>> by lazy { files.map { it to parseFixture(it.readText()) } }

    private fun byName(name: String): Fixture = fixtures.first { it.first.nameWithoutExtension == name }.second

    @Test
    fun loadsAllFixtures() {
        assertTrue(fixtures.size >= 162, "expected at least 162 fixtures, got ${fixtures.size}")
    }

    @Test
    fun everyFixtureMatchesItsExpectation() = runTest {
        val failures = mutableListOf<String>()
        for ((file, fx) in fixtures) {
            val name = file.nameWithoutExtension
            val out = try {
                runFixture(fx)
            } catch (e: Exception) {
                failures += "$name: threw $e"
                continue
            }
            failures += checkOutcome(fx, out).map { "$name: $it" }
        }
        assertTrue(failures.isEmpty(), "${failures.size} contract check(s) failed:\n" + failures.joinToString("\n"))
    }

    /** Raw-JSON shape rules of the fixture files that the typed harness cannot see (unknown keys, names, suite type). */
    @Test
    fun fixtureFilesFollowTheFormat() {
        val problems = mutableListOf<String>()
        fun keys(where: String, o: JsonElement, allowed: Set<String>) {
            if (o !is JsonObject) {
                problems += "$where: not an object"
                return
            }
            val extra = o.keys - allowed
            if (extra.isNotEmpty()) problems += "$where: unknown keys $extra"
        }
        fun script(where: String, el: JsonElement?) {
            val entries = el as? JsonArray ?: return run { problems += "$where: script is not an array" }
            entries.forEachIndexed { i, e ->
                keys("$where script[$i]", e, setOf("response", "error"))
                val o = e as? JsonObject ?: return@forEachIndexed
                (o["error"] as? JsonObject)?.let { keys("$where script[$i].error", it, setOf("kind")) }
            }
        }
        for ((file, fx) in fixtures) {
            val name = file.nameWithoutExtension
            val root = fx.root
            keys(name, root, setOf("name", "suite", "targets", "judge", "expect"))
            if ((root["name"] as? JsonPrimitive)?.content != name) problems += "$name: name differs from the file name"
            val suite = root["suite"]
            if (suite is JsonPrimitive && suite.isString) problems += "$name: a string-valued suite is not allowed"
            if (suite !is JsonObject && name != "invalid-suite-null" && name != "invalid-suite-array") {
                problems += "$name: suite is not an object"
            }
            (root["targets"] as? JsonArray)?.forEachIndexed { i, t ->
                keys("$name targets[$i]", t, setOf("id", "script"))
                script("$name targets[$i]", (t as? JsonObject)?.get("script"))
            }
            (root["judge"] as? JsonObject)?.let { j ->
                keys("$name judge", j, setOf("script"))
                script("$name judge", j["script"])
            }
            val expect = root["expect"] as? JsonObject ?: run { problems += "$name: expect is not an object"; continue }
            keys("$name expect", expect, setOf("results", "summary", "calls", "judgeContains", "invalidSuite"))
            if (expect["invalidSuite"] != null) {
                if (expect.keys != setOf("invalidSuite") || expect["invalidSuite"] != JsonPrimitive(true)) {
                    problems += "$name: invalid-suite expectation must be exactly {invalidSuite: true}"
                }
                continue
            }
            if (expect["calls"] !is JsonObject) problems += "$name: a fixture that makes calls must declare expect.calls"
            (expect["results"] as? JsonArray)?.forEachIndexed { i, r ->
                keys("$name results[$i]", r, setOf("caseId", "targetId", "status", "reply", "error", "graders"))
                if ((r as? JsonObject)?.get("graders") !is JsonArray) problems += "$name results[$i]: graders must be listed"
                ((r as? JsonObject)?.get("graders") as? JsonArray)?.forEachIndexed { j, g ->
                    keys("$name results[$i].graders[$j]", g, setOf("kind", "passed", "issues", "error"))
                    ((g as? JsonObject)?.get("issues") as? JsonArray)?.forEach { issue ->
                        keys("$name results[$i].graders[$j].issues", issue, setOf("path", "code"))
                    }
                }
            }
            (expect["summary"] as? JsonArray)?.forEachIndexed { i, s ->
                keys("$name summary[$i]", s, setOf("targetId", "total", "passed", "failed", "errored", "passRate"))
            }
            if (expect["judgeContains"] != null && fx.root["judge"] == null) problems += "$name: judgeContains without a judge"
        }
        assertTrue(problems.isEmpty(), "${problems.size} fixture format problem(s):\n" + problems.joinToString("\n"))
    }

    @Test
    fun literalNumbersAreSeenByValue() = runTest {
        fun graderOf(name: String): Grader {
            val suite = byName(name).root["suite"]!!
            return parseSuite(suite).cases.first().graders.single()
        }
        assertEquals(Length(max = 2), graderOf("length-integral-float-bound"))
        assertEquals(Length(max = 100), graderOf("length-exponent-bound"))
        val equalsValue = (graderOf("jsonequals-float-value") as JsonEquals).value
        assertTrue(es.sebas1705.ilabs.structured.jsonEqual(equalsValue, JsonPrimitive(1)))
        // the literal text the file keeps, as the lenient kotlinx parser records it
        val text = files.first { it.nameWithoutExtension == "length-exponent-bound" }.readText()
        assertTrue("\"max\": 1e2" in text)
        assertTrue("\"max\": 2.0" in files.first { it.nameWithoutExtension == "length-integral-float-bound" }.readText())
        assertTrue("\"value\": 1.0" in files.first { it.nameWithoutExtension == "jsonequals-float-value" }.readText())
    }

    @Test
    fun loneSurrogateEscapesSurviveParsing() {
        val script = (byName("length-lone-surrogate").root["targets"] as JsonArray)[0] as JsonObject
        val replies = (script["script"] as JsonArray).map { ((it as JsonObject)["response"] as JsonPrimitive).content }
        assertEquals(listOf("x\ud83dy", "\ude00\ud83dA"), replies)
    }
}
