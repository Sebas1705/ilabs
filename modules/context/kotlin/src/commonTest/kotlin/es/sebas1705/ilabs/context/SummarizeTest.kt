package es.sebas1705.ilabs.context

import es.sebas1705.ilabs.providers.CompletionRequest
import es.sebas1705.ilabs.providers.CompletionResponse
import es.sebas1705.ilabs.providers.ErrorKind
import es.sebas1705.ilabs.providers.Message
import es.sebas1705.ilabs.providers.Provider
import es.sebas1705.ilabs.providers.ProviderException
import es.sebas1705.ilabs.providers.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SummarizeTest {
    private fun sys(c: String) = Message(Role.SYSTEM, c)
    private fun user(c: String) = Message(Role.USER, c)
    private fun bot(c: String) = Message(Role.ASSISTANT, c)

    private class Recording(private val action: suspend (CompletionRequest) -> String) : Provider {
        override val id = "rec"
        val requests = mutableListOf<CompletionRequest>()
        override suspend fun complete(req: CompletionRequest): CompletionResponse {
            requests += req
            return CompletionResponse(action(req), id)
        }
    }

    private fun replying(text: String) = Recording { text }

    private val convo = listOf(
        sys("Be brief."),
        user("User turn 1"), bot("Bot turn 1"),
        user("User turn 2"), bot("Bot turn 2"),
        user("User turn 3"), bot("Bot turn 3"),
    )

    @Test
    fun keepRecentDefaultsToTheContractValue() {
        assertEquals(4, DEFAULT_KEEP_RECENT)
        assertEquals(DEFAULT_KEEP_RECENT, SummarizeOptions(maxTokens = 10).keepRecent)
    }

    @Test
    fun rejectsInvalidOptions() {
        assertFailsWith<IllegalArgumentException> { SummarizeOptions(maxTokens = 0) }
        assertFailsWith<IllegalArgumentException> { SummarizeOptions(maxTokens = -1) }
        assertFailsWith<IllegalArgumentException> { SummarizeOptions(maxTokens = 10, keepRecent = 0) }
        assertFailsWith<IllegalArgumentException> { SummarizeOptions(maxTokens = 10, keepRecent = -1) }
    }

    @Test
    fun fitsMeansNoCallAndTheSameMessages() = runTest {
        val p = replying("x")
        val r = summarizeContext(p, convo, SummarizeOptions(maxTokens = 1000))
        assertEquals(convo, r.messages)
        assertEquals(false, r.summarized)
        assertEquals(0, r.summaryCalls)
        assertEquals(0, r.droppedCount)
        assertEquals(r.tokensBefore, r.tokensAfter)
        assertNull(r.summarizerRequest)
        assertTrue(p.requests.isEmpty())
    }

    @Test
    fun summarizesOlderMessagesWithOneCall() = runTest {
        val p = replying("They said hello.")
        val r = summarizeContext(p, convo, SummarizeOptions(maxTokens = 40, keepRecent = 2))
        assertEquals(
            listOf(sys("Be brief."), sys(SUMMARY_PREFIX + "They said hello."), user("User turn 3"), bot("Bot turn 3")),
            r.messages,
        )
        assertEquals(true, r.summarized)
        assertEquals(1, r.summaryCalls)
        assertEquals(4, r.droppedCount)
        assertEquals(49, r.tokensBefore)
        assertEquals(39, r.tokensAfter)
        assertEquals(1, p.requests.size)
        assertSame(p.requests[0], r.summarizerRequest)
        val req = p.requests[0]
        assertEquals(listOf(Role.SYSTEM, Role.USER), req.messages.map { it.role })
        assertTrue(req.messages.all { it.content.isNotEmpty() })
        assertNull(req.maxTokens)
        val transcript = req.messages[1].content
        for (s in listOf("User turn 1", "Bot turn 1", "User turn 2", "Bot turn 2")) assertTrue(s in transcript, s)
        for (s in listOf("Be brief.", "User turn 3", "Bot turn 3")) assertTrue(s !in transcript, s)
    }

    @Test
    fun customCounterDrivesTheWholeFlow() = runTest {
        val counter: TokenCounter = { it.content.length }
        val msgs = listOf(user("aaaa"), bot("bbbb"), user("cc"), bot("dd"))
        val p = replying("s")
        // total 12 > 10; keepRecent 2 -> summary "…\ns" has 38 chars, so the counter makes it never fit: window runs.
        val e = assertFailsWith<BudgetTooSmallException> {
            summarizeContext(p, msgs, SummarizeOptions(10, 2, counter))
        }
        assertEquals(1, p.requests.size)
        assertEquals(SUMMARY_PREFIX.length + 1 + 2, e.required)
    }

    @Test
    fun customCounterAppliesToFitsAndTokens() = runTest {
        val counter: TokenCounter = { 1 }
        val p = replying("x")
        val r = summarizeContext(p, convo, SummarizeOptions(maxTokens = 7, keepRecent = 1, counter = counter))
        assertEquals(7, r.tokensBefore)
        assertEquals(7, r.tokensAfter)
        assertTrue(p.requests.isEmpty())
    }

    @Test
    fun doesNotMutateItsInput() = runTest {
        val msgs = convo.toMutableList()
        summarizeContext(replying("s"), msgs, SummarizeOptions(maxTokens = 40, keepRecent = 2))
        assertEquals(convo, msgs)
    }

    @Test
    fun providerExceptionsPropagateUnchanged() = runTest {
        val boom = ProviderException(ErrorKind.RATE_LIMIT, 5)
        val p = Recording { throw boom }
        val e = assertFailsWith<ProviderException> { summarizeContext(p, convo, SummarizeOptions(40, 2)) }
        assertSame(boom, e)
        assertEquals(1, p.requests.size)
    }

    @Test
    fun otherExceptionsPropagateUnchanged() = runTest {
        val boom = IllegalStateException("boom")
        val p = Recording { throw boom }
        val e = assertFailsWith<IllegalStateException> { summarizeContext(p, convo, SummarizeOptions(40, 2)) }
        assertSame(boom, e)
    }

    @Test
    fun cancellationIsRethrownUnchanged() = runTest {
        val boom = CancellationException("cancelled")
        val p = Recording { throw boom }
        val e = assertFailsWith<CancellationException> { summarizeContext(p, convo, SummarizeOptions(40, 2)) }
        assertSame(boom, e)
    }

    @Test
    fun errorsAreNotCaught() = runTest {
        val boom = AssertionError("fatal")
        val p = Recording { throw boom }
        val e = assertFailsWith<AssertionError> { summarizeContext(p, convo, SummarizeOptions(40, 2)) }
        assertSame(boom, e)
    }

    @Test
    fun noCallWhenNothingIsOlderEvenWithASummary() = runTest {
        val msgs = listOf(sys(SUMMARY_PREFIX + "old"), user("User turn 1"), bot("Bot turn 1"))
        val p = replying("x")
        val r = summarizeContext(p, msgs, SummarizeOptions(maxTokens = 21, keepRecent = 4))
        assertTrue(p.requests.isEmpty())
        assertEquals(false, r.summarized)
        assertEquals(0, r.summaryCalls)
        assertEquals(listOf(sys(SUMMARY_PREFIX + "old"), bot("Bot turn 1")), r.messages)
        assertEquals(1, r.droppedCount)
    }

    @Test
    fun existingSummariesAreMergedIntoOneAtTheEarliestPosition() = runTest {
        val msgs = listOf(
            sys(SUMMARY_PREFIX + "Part one."), user("User turn 1"), sys("Use English."),
            sys(SUMMARY_PREFIX + "Part two."), bot("Bot turn 1"), user("User turn 2"), bot("Bot turn 2"),
        )
        val p = replying("Merged.")
        val r = summarizeContext(p, msgs, SummarizeOptions(maxTokens = 50, keepRecent = 2))
        assertEquals(listOf(sys(SUMMARY_PREFIX + "Merged."), sys("Use English."), user("User turn 2"), bot("Bot turn 2")), r.messages)
        assertEquals(4, r.droppedCount)
        val transcript = p.requests[0].messages[1].content
        assertTrue(SUMMARY_PREFIX + "Part one." in transcript)
        assertTrue(SUMMARY_PREFIX + "Part two." in transcript)
        assertTrue("Use English." !in transcript)
    }

    @Test
    fun replyIsUsedAsIs() = runTest {
        val r = summarizeContext(replying("  Hello.  \n"), convo, SummarizeOptions(maxTokens = 40, keepRecent = 2))
        assertEquals(sys(SUMMARY_PREFIX + "  Hello.  \n"), r.messages[1])
    }
}
