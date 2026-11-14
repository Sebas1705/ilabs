package es.sebas1705.ilabs.context

import es.sebas1705.ilabs.providers.Message
import es.sebas1705.ilabs.providers.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WindowTest {
    private fun sys(c: String) = Message(Role.SYSTEM, c)
    private fun user(c: String) = Message(Role.USER, c)
    private fun bot(c: String) = Message(Role.ASSISTANT, c)

    @Test
    fun rejectsANonPositiveBudget() {
        assertFailsWith<IllegalArgumentException> { WindowOptions(0) }
        assertFailsWith<IllegalArgumentException> { WindowOptions(-1) }
        WindowOptions(1)
    }

    @Test
    fun emptyListFits() {
        val r = slidingWindow(emptyList(), WindowOptions(1))
        assertEquals(WindowResult(emptyList(), 0, 0, 0), r)
    }

    @Test
    fun customCounterDecidesFitsAndWindowing() {
        // Every message costs its content length.
        val counter: TokenCounter = { it.content.length }
        val msgs = listOf(sys("ss"), user("aaaa"), bot("bbb"), user("cc"))
        // total 11 > 8: S=2, L=2 -> 4 <= 8; "bbb" fits (7), "aaaa" would make 11 > 8.
        val r = slidingWindow(msgs, WindowOptions(8, counter))
        assertEquals(listOf(sys("ss"), bot("bbb"), user("cc")), r.messages)
        assertEquals(1, r.droppedCount)
        assertEquals(11, r.tokensBefore)
        assertEquals(7, r.tokensAfter)
        val e = assertFailsWith<BudgetTooSmallException> { slidingWindow(msgs, WindowOptions(3, counter)) }
        assertEquals(4, e.required)
    }

    @Test
    fun customCounterIsUsedWhenEverythingFits() {
        val counter: TokenCounter = { 100 }
        val r = slidingWindow(listOf(user("a")), WindowOptions(100, counter))
        assertEquals(100, r.tokensBefore)
        assertEquals(100, r.tokensAfter)
        assertFailsWith<BudgetTooSmallException> { slidingWindow(listOf(user("a")), WindowOptions(99, counter)) }
    }

    @Test
    fun doesNotMutateItsInput() {
        val msgs = mutableListOf(sys("Be brief."), user("User turn 1"), bot("Bot turn 1"), user("User turn 2"))
        val copy = msgs.toList()
        val r = slidingWindow(msgs, WindowOptions(21))
        assertEquals(copy, msgs)
        assertEquals(1, r.droppedCount)
    }

    @Test
    fun returnedListIsIndependentOfTheInputWhenItFits() {
        val msgs = mutableListOf(user("Okay"))
        val r = slidingWindow(msgs, WindowOptions(100))
        msgs.add(user("later"))
        assertEquals(listOf(user("Okay")), r.messages)
    }

    @Test
    fun onlySystemMessagesThatDoNotFitReportTheirCost() {
        val e = assertFailsWith<BudgetTooSmallException> {
            slidingWindow(listOf(sys("Be brief."), sys("Use English.")), WindowOptions(10))
        }
        assertEquals(14, e.required)
    }
}
