package es.sebas1705.ilabs.providers

import es.sebas1705.ilabs.providers.contract.*

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ValidationTest {
    private val ok = MockProvider("a", listOf(ScriptEntry(response = "ok")))

    private fun retrying(maxAttempts: Int = 1, baseDelayMs: Long = 0, maxDelayMs: Long = 0) =
        RetryingProvider(ok, RetryOptions(maxAttempts, baseDelayMs, maxDelayMs) { })

    @Test
    fun retryingProviderRejectsOutOfRangeOptions() {
        assertFailsWith<IllegalArgumentException> { retrying(maxAttempts = 0) }
        assertFailsWith<IllegalArgumentException> { retrying(maxAttempts = -1) }
        assertFailsWith<IllegalArgumentException> { retrying(baseDelayMs = -1) }
        assertFailsWith<IllegalArgumentException> { retrying(maxDelayMs = -1) }
    }

    @Test
    fun retryingProviderAcceptsBoundaryOptions() {
        retrying()
    }

    private fun fixture(
        kind: String = "retry",
        providers: List<FixtureProvider> = listOf(FixtureProvider("a", listOf(ScriptEntry(response = "ok")))),
    ) = Fixture(
        name = "x",
        setup = FixtureSetup(kind, maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0),
        providers = providers,
        request = CompletionRequest(emptyList()),
        expect = Expectation(ExpectedResult(text = "ok", provider = "a"), emptyList(), mapOf("a" to 1)),
    )

    @Test
    fun runFixtureRejectsRetryWithoutProviders() = runTest {
        val e = assertFailsWith<IllegalStateException> { runFixture(fixture(providers = emptyList())) }
        assertEquals("invalid fixture: retry setup needs a provider", e.message)
    }

    @Test
    fun runFixtureRejectsUnknownSetupKind() = runTest {
        val e = assertFailsWith<IllegalStateException> { runFixture(fixture(kind = "bogus")) }
        assertEquals("invalid fixture: unknown setup kind: bogus", e.message)
    }

    @Test
    fun runFixtureRejectsScriptEntryWithoutResponseOrError() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            runFixture(fixture(providers = listOf(FixtureProvider("a", listOf(ScriptEntry())))))
        }
        assertEquals("invalid fixture: script entry 1 of provider a has neither response nor error", e.message)
    }
}
