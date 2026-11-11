package dev.ilabs.providers

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MockProviderTest {
    private val empty = CompletionRequest(messages = emptyList())

    @Test
    fun emptyMessagesReturnsNormally() = runTest {
        val p = MockProvider("a", listOf(ScriptEntry(response = "ok")))
        assertEquals(CompletionResponse("ok", "a"), p.complete(empty))
    }

    @Test
    fun exhaustedScriptFailsWithUnavailable() = runTest {
        val p = MockProvider("a", listOf(ScriptEntry(response = "ok")))
        p.complete(empty)
        assertEquals(ErrorKind.UNAVAILABLE, assertFailsWith<ProviderException> { p.complete(empty) }.kind)
        assertEquals(ErrorKind.UNAVAILABLE, assertFailsWith<ProviderException> { p.complete(empty) }.kind)
    }

    @Test
    fun emptyFallbackFailsWithUnavailable() = runTest {
        val e = assertFailsWith<ProviderException> { FallbackProvider(emptyList()).complete(empty) }
        assertEquals(ErrorKind.UNAVAILABLE, e.kind)
    }

    @Test
    fun nonProviderExceptionIsRethrownUnchanged() = runTest {
        val boom = IllegalStateException("boom")
        val bad = object : Provider {
            override val id = "bad"
            override suspend fun complete(req: CompletionRequest): CompletionResponse = throw boom
        }
        val retrying = RetryingProvider(bad, RetryOptions(3, 1, 1) { })
        assertEquals(boom, assertFailsWith<IllegalStateException> { retrying.complete(empty) })
        assertEquals(boom, assertFailsWith<IllegalStateException> { FallbackProvider(listOf(bad)).complete(empty) })
    }
}
