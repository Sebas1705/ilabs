package es.sebas1705.ilabs.providers

import es.sebas1705.ilabs.providers.contract.*

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ContractTest {
    private fun contractsDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "contracts/providers")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("contracts/providers not found above ${System.getProperty("user.dir")}")
    }

    private val fixtures: List<Fixture> by lazy {
        contractsDir().listFiles { f -> f.extension == "json" }!!
            .sortedBy { it.name }
            .map { parseFixture(it.readText()) }
    }

    @Test
    fun loadsAllFixtures() {
        assertTrue(fixtures.size >= 13, "expected at least 13 fixtures, got ${fixtures.size}")
    }

    @Test
    fun everyFixtureMatchesItsExpectation() = runTest {
        val failures = mutableListOf<String>()
        for (fx in fixtures) {
            val out = runFixture(fx)
            val want = fx.expect.result
            val got = out.result
            fun check(what: String, expected: Any?, actual: Any?) {
                if (expected != actual) failures += "${fx.name}: $what: expected <$expected> but was <$actual>"
            }
            if (want.error != null) {
                if (got is FixtureResult.Failure) {
                    check("error kind", want.error.kind, got.kind)
                    if (want.error.retryAfterMs != null) check("retryAfterMs", want.error.retryAfterMs, got.retryAfterMs)
                } else {
                    failures += "${fx.name}: expected an error but got <$got>"
                }
            } else {
                check("result", FixtureResult.Success(want.text!!, want.provider!!), got)
            }
            check("sleeps", fx.expect.sleeps, out.sleeps)
            check("calls", fx.expect.calls, out.calls)
            check("events/sleeps", out.sleeps, out.events.filterIsInstance<Event.Wait>().map { it.ms })
            fx.expect.events?.let { want -> check("event trace", want.map { it.toEvent() }, out.events) }
        }
        assertTrue(failures.isEmpty(), "${failures.size} contract check(s) failed:\n" + failures.joinToString("\n"))
    }
}
