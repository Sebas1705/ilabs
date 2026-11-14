package es.sebas1705.ilabs.context

import es.sebas1705.ilabs.providers.Message
import es.sebas1705.ilabs.providers.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokensTest {
    @Test
    fun emptyTextCostsNothing() = assertEquals(0, estimateTokens(""))

    @Test
    fun roundsUpEveryFourCodePoints() {
        assertEquals(1, estimateTokens("abcd"))
        assertEquals(2, estimateTokens("abcde"))
        assertEquals(2, estimateTokens("abcdefgh"))
        assertEquals(3, estimateTokens("abcdefghi"))
    }

    @Test
    fun surrogatePairsCountAsOneCodePoint() {
        assertEquals(2, estimateTokens("😀😁😂🤣😃")) // 5 code points, 10 UTF-16 units
        assertEquals(1, estimateTokens("😀😁😂🤣")) // 4 code points
    }

    @Test
    fun loneSurrogatesCountAsOneCodePointEach() {
        assertEquals(1, estimateTokens("\uD800")) // lone high
        assertEquals(1, estimateTokens("\uDC00")) // lone low
        assertEquals(1, estimateTokens("\uDC00𐀀\uD800")) // low+high is not a pair: 4 code points
        assertEquals(2, estimateTokens("\uDC00\uD800abc")) // 5 code points
        assertEquals(2, estimateTokens("\uD800\uD800\uD800\uD800\uD800")) // never swallows its neighbour
        assertEquals(1, estimateTokens("😀😀")) // two valid pairs: 2 code points
        assertEquals(1, estimateTokens("a\uD83D")) // trailing lone high
    }

    @Test
    fun defaultCounterAddsFourOfOverhead() {
        assertEquals(4, defaultCounter(Message(Role.USER, "")))
        assertEquals(5, defaultCounter(Message(Role.ASSISTANT, "Okay")))
        assertEquals(6, defaultCounter(Message(Role.SYSTEM, "Sure!")))
    }

    @Test
    fun summaryPrefixIsThe37CodePointsEndingWithLf() {
        assertEquals("Summary of the earlier conversation:\n", SUMMARY_PREFIX)
        assertEquals(37, SUMMARY_PREFIX.length)
    }

    @Test
    fun isSummaryMessageIsExact() {
        assertTrue(isSummaryMessage(Message(Role.SYSTEM, SUMMARY_PREFIX)))
        assertTrue(isSummaryMessage(Message(Role.SYSTEM, SUMMARY_PREFIX + "anything")))
        assertFalse(isSummaryMessage(Message(Role.SYSTEM, "Summary of the earlier conversation: x")))
        assertFalse(isSummaryMessage(Message(Role.SYSTEM, "Summary of the earlier conversation:")))
        assertFalse(isSummaryMessage(Message(Role.SYSTEM, "summary of the earlier conversation:\nx")))
        assertFalse(isSummaryMessage(Message(Role.SYSTEM, " " + SUMMARY_PREFIX)))
        assertFalse(isSummaryMessage(Message(Role.USER, SUMMARY_PREFIX + "x")))
        assertFalse(isSummaryMessage(Message(Role.ASSISTANT, SUMMARY_PREFIX + "x")))
    }
}
