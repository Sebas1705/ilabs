package dev.ilabs.providers

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
        for (fx in fixtures) {
            val out = runFixture(fx)
            val want = fx.expect.result
            val got = out.result
            if (want.error != null) {
                assertIs<FixtureResult.Failure>(got, "${fx.name}: expected an error")
                assertEquals(want.error.kind, got.kind, "${fx.name}: error kind")
                if (want.error.retryAfterMs != null) {
                    assertEquals(want.error.retryAfterMs, got.retryAfterMs, "${fx.name}: retryAfterMs")
                }
            } else {
                assertEquals(FixtureResult.Success(want.text!!, want.provider!!), got, "${fx.name}: result")
            }
            assertEquals(fx.expect.sleeps, out.sleeps, "${fx.name}: sleeps")
            assertEquals(fx.expect.calls, out.calls, "${fx.name}: calls")
            assertEquals(out.sleeps, out.events.filterIsInstance<Event.Wait>().map { it.ms }, "${fx.name}: events/sleeps")
        }
    }
}
