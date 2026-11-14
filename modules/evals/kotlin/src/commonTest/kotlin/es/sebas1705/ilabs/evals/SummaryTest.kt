package es.sebas1705.ilabs.evals

import kotlin.test.Test
import kotlin.test.assertEquals

class SummaryTest {
    private fun r(target: String, status: Status, case: String = "c") =
        CaseResult(case, target, status, if (status == Status.ERROR) null else "x", if (status == Status.ERROR) "timeout" else null, emptyList())

    @Test
    fun emptyListGivesEmptySummary() {
        assertEquals(emptyList(), summarize(emptyList()))
    }

    @Test
    fun targetsByFirstAppearanceWithCountsPerStatusInArbitraryOrder() {
        val results = listOf(
            r("zeta", Status.FAIL), r("alpha", Status.PASS), r("zeta", Status.PASS), r("alpha", Status.ERROR),
            r("zeta", Status.ERROR), r("zeta", Status.PASS), r("alpha", Status.PASS), r("mid", Status.FAIL),
        )
        assertEquals(
            listOf(
                TargetSummary("zeta", total = 4, passed = 2, failed = 1, errored = 1, passRate = 0.5),
                TargetSummary("alpha", total = 3, passed = 2, failed = 0, errored = 1, passRate = 2.0 / 3.0),
                TargetSummary("mid", total = 1, passed = 0, failed = 1, errored = 0, passRate = 0.0),
            ),
            summarize(results),
        )
    }

    @Test
    fun passRateIsTheExactDouble() {
        val third = summarize(listOf(r("a", Status.PASS), r("a", Status.FAIL), r("a", Status.ERROR))).single().passRate
        assertEquals(0.3333333333333333, third)
        val two = summarize(listOf(r("a", Status.PASS), r("a", Status.PASS), r("a", Status.FAIL))).single().passRate
        assertEquals(0.6666666666666666, two)
        assertEquals(1.0, summarize(listOf(r("a", Status.PASS))).single().passRate)
    }

    @Test
    fun statusWireNames() {
        assertEquals(listOf("pass", "fail", "error"), Status.entries.map { it.wire })
    }
}
