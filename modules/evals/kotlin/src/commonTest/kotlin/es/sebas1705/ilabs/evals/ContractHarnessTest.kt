package es.sebas1705.ilabs.evals

import es.sebas1705.ilabs.evals.contract.FixtureOutcome
import es.sebas1705.ilabs.evals.contract.checkOutcome
import es.sebas1705.ilabs.evals.contract.parseFixture
import es.sebas1705.ilabs.evals.contract.runFixture
import es.sebas1705.ilabs.providers.CompletionRequest
import es.sebas1705.ilabs.providers.Message
import es.sebas1705.ilabs.providers.Role
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Tests of the shared fixture harness itself: it must fail when it should, not only pass on the real fixtures. */
class ContractHarnessTest {
    private val suite = """{"cases": [{"id": "c1", "messages": [{"role": "user", "content": "Hi"}],
        "graders": [{"kind": "contains", "value": "a"}, {"kind": "judge", "rubric": "polite?"}]}]}"""

    private fun fixture(
        expect: String =
            """{"results": [{"caseId": "c1", "targetId": "a", "status": "pass", "reply": "abc", "graders": [{"kind": "contains", "passed": true}, {"kind": "judge", "passed": true}]}],
               "summary": [{"targetId": "a", "total": 1, "passed": 1, "failed": 0, "errored": 0, "passRate": 1}],
               "calls": {"a": 1, "judge": 1}, "judgeContains": ["polite?", "Hi", "abc"]}""",
        suiteText: String = suite,
        targets: String = """[{"id": "a", "script": [{"response": "abc"}]}]""",
        judge: String = """, "judge": {"script": [{"response": "PASS"}]}""",
        extra: String = "",
    ) = parseFixture("""{"name": "t", "suite": $suiteText, "targets": $targets$judge, "expect": $expect$extra}""")

    private suspend fun failuresOf(fx: es.sebas1705.ilabs.evals.contract.Fixture, tweak: (FixtureOutcome) -> FixtureOutcome = { it }): List<String> {
        return checkOutcome(fx, tweak(runFixture(fx)))
    }


    @Test
    fun aCorrectFixturePasses() = runTest {
        assertEquals(emptyList(), failuresOf(fixture()))
    }

    @Test
    fun passRateIsComparedAsAValue() = runTest {
        val ok = fixture(expect = """{"results": [], "summary": [], "calls": {"a": 1, "judge": 1}}""")
        assertTrue(failuresOf(ok).isNotEmpty())
        val zero = parseFixture(
            """{"name": "t", "suite": {"cases": [{"id": "c1", "messages": [], "graders": [{"kind": "contains", "value": "zzz"}]}]},
               "targets": [{"id": "a", "script": [{"response": "abc"}]}],
               "expect": {"results": [{"caseId": "c1", "targetId": "a", "status": "fail", "reply": "abc", "graders": [{"kind": "contains", "passed": false}]}],
                          "summary": [{"targetId": "a", "total": 1, "passed": 0, "failed": 1, "errored": 0, "passRate": 0.0}], "calls": {"a": 1}}}""",
        )
        assertEquals(emptyList(), failuresOf(zero))
    }

    @Test
    fun detectsEveryKindOfMismatch() = runTest {
        val base = """"graders": [{"kind": "contains", "passed": true}, {"kind": "judge", "passed": true}]"""
        val summary = """"summary": [{"targetId": "a", "total": 1, "passed": 1, "failed": 0, "errored": 0, "passRate": 1}], "calls": {"a": 1, "judge": 1}"""
        fun withResult(r: String) = fixture(expect = """{"results": [$r], $summary}""")
        val good = """"caseId": "c1", "targetId": "a", "status": "pass", "reply": "abc", $base"""
        assertEquals(emptyList(), failuresOf(withResult("{$good}")))
        assertTrue(failuresOf(withResult("""{"caseId": "c2", "targetId": "a", "status": "pass", "reply": "abc", $base}""")).isNotEmpty())
        assertTrue(failuresOf(withResult("""{"caseId": "c1", "targetId": "b", "status": "pass", "reply": "abc", $base}""")).isNotEmpty())
        assertTrue(failuresOf(withResult("""{"caseId": "c1", "targetId": "a", "status": "fail", "reply": "abc", $base}""")).isNotEmpty())
        assertTrue(failuresOf(withResult("""{"caseId": "c1", "targetId": "a", "status": "pass", "reply": "abd", $base}""")).isNotEmpty())
        assertTrue(failuresOf(withResult("""{"caseId": "c1", "targetId": "a", "status": "pass", "reply": "abc", "error": "timeout", $base}""")).isNotEmpty())
        assertTrue(failuresOf(withResult("""{"caseId": "c1", "targetId": "a", "status": "pass", "reply": "abc", "graders": [{"kind": "contains", "passed": true}]}""")).isNotEmpty())
        assertTrue(failuresOf(withResult("""{"caseId": "c1", "targetId": "a", "status": "pass", "reply": "abc", "graders": [{"kind": "contains", "passed": false}, {"kind": "judge", "passed": true}]}""")).isNotEmpty())
        assertTrue(failuresOf(withResult("""{"caseId": "c1", "targetId": "a", "status": "pass", "reply": "abc", "graders": [{"kind": "equals", "passed": true}, {"kind": "judge", "passed": true}]}""")).isNotEmpty())
        assertTrue(failuresOf(withResult("""{"caseId": "c1", "targetId": "a", "status": "pass", "reply": "abc", "graders": [{"kind": "contains", "passed": true, "error": "no_judge"}, {"kind": "judge", "passed": true}]}""")).isNotEmpty())
        assertTrue(failuresOf(withResult("""{"caseId": "c1", "targetId": "a", "status": "pass", "reply": "abc", "graders": [{"kind": "contains", "passed": true, "issues": [{"path": "", "code": "no_json"}]}, {"kind": "judge", "passed": true}]}""")).isNotEmpty())
        // summary, calls and judge expectations
        fun withSummary(s: String, calls: String = """{"a": 1, "judge": 1}""", extra: String = "") =
            fixture(expect = """{"results": [{$good}], "summary": [$s], "calls": $calls$extra}""")
        val s = """"targetId": "a", "total": 1, "passed": 1, "failed": 0, "errored": 0"""
        assertEquals(emptyList(), failuresOf(withSummary("{$s, \"passRate\": 1.0}")))
        assertTrue(failuresOf(withSummary("{$s, \"passRate\": 0.5}")).isNotEmpty())
        assertTrue(failuresOf(withSummary("""{"targetId": "a", "total": 2, "passed": 1, "failed": 0, "errored": 0, "passRate": 1}""")).isNotEmpty())
        assertTrue(failuresOf(withSummary("{$s, \"passRate\": 1}", calls = """{"a": 1}""")).isNotEmpty())
        assertTrue(failuresOf(withSummary("{$s, \"passRate\": 1}", calls = """{"a": 2, "judge": 1}""")).isNotEmpty())
        assertTrue(failuresOf(withSummary("{$s, \"passRate\": 1}", extra = """, "judgeContains": ["abc", "Hi"]""")).isNotEmpty())
        assertTrue(failuresOf(withSummary("{$s, \"passRate\": 1}", extra = """, "judgeContains": ["a", "a", "a", "a"]""")).isNotEmpty())
        assertTrue(failuresOf(withSummary("{$s, \"passRate\": 1}", extra = """, "judgeContains": ["nope"]""")).isNotEmpty())
    }

    @Test
    fun issuesAreComparedWithMissingMeaningEmpty() = runTest {
        val jsonSuite = """{"cases": [{"id": "c1", "messages": [], "graders": [{"kind": "jsonEquals", "value": 1}]}]}"""
        fun fx(graderExpect: String, reply: String) = fixture(
            suiteText = jsonSuite, judge = "", targets = """[{"id": "a", "script": [{"response": "$reply"}]}]""",
            expect = """{"results": [{"caseId": "c1", "targetId": "a", "status": "fail", "reply": "$reply", "graders": [$graderExpect]}],
                "summary": [{"targetId": "a", "total": 1, "passed": 0, "failed": 1, "errored": 0, "passRate": 0}], "calls": {"a": 1}}""",
        )
        // a mismatch has issues [] and the expectation may omit them
        assertEquals(emptyList(), failuresOf(fx("""{"kind": "jsonEquals", "passed": false}""", "2")))
        assertEquals(emptyList(), failuresOf(fx("""{"kind": "jsonEquals", "passed": false, "issues": []}""", "2")))
        // an extraction failure must be listed when the actual has them
        assertTrue(failuresOf(fx("""{"kind": "jsonEquals", "passed": false}""", "nope")).isNotEmpty())
        assertEquals(emptyList(), failuresOf(fx("""{"kind": "jsonEquals", "passed": false, "issues": [{"path": "", "code": "no_json"}]}""", "nope")))
        assertTrue(failuresOf(fx("""{"kind": "jsonEquals", "passed": false, "issues": [{"path": "", "code": "invalid_json"}]}""", "nope")).isNotEmpty())
        assertTrue(failuresOf(fx("""{"kind": "jsonEquals", "passed": false, "issues": [{"path": "/x", "code": "no_json"}]}""", "nope")).isNotEmpty())
    }

    @Test
    fun checksTheRequestRulesOnTheRecordedRequests() = runTest {
        val fx = fixture()
        assertEquals(emptyList(), failuresOf(fx))
        val goodJudge = listOf(Message(Role.SYSTEM, "s"), Message(Role.USER, "polite? Hi abc"))
        assertTrue(failuresOf(fx) { it.copy(judgeRequests = listOf(CompletionRequest(goodJudge.take(1)))) }.isNotEmpty())
        assertTrue(failuresOf(fx) { it.copy(judgeRequests = listOf(CompletionRequest(goodJudge.reversed()))) }.isNotEmpty())
        assertTrue(failuresOf(fx) { it.copy(judgeRequests = listOf(CompletionRequest(listOf(Message(Role.SYSTEM, ""), goodJudge[1])))) }.isNotEmpty())
        assertTrue(failuresOf(fx) { it.copy(judgeRequests = listOf(CompletionRequest(goodJudge, maxTokens = 5))) }.isNotEmpty())
        assertTrue(failuresOf(fx) { it.copy(judgeRequests = emptyList()) }.isNotEmpty())
        val wrongMessages = listOf(Message(Role.USER, "Other"))
        assertTrue(failuresOf(fx) { it.copy(targetRequests = mapOf("a" to listOf(CompletionRequest(wrongMessages)))) }.isNotEmpty())
        assertTrue(failuresOf(fx) { it.copy(targetRequests = mapOf("a" to listOf(CompletionRequest(listOf(Message(Role.USER, "Hi")), maxTokens = 1)))) }.isNotEmpty())
        assertTrue(failuresOf(fx) { it.copy(output = it.output!!.copy(results = it.output.results.map { r -> r.copy(error = "x") })) }.isNotEmpty())
    }

    @Test
    fun anErroredResultMustHaveNoReply() = runTest {
        val fx = fixture(
            expect = """{"results": [{"caseId": "c1", "targetId": "a", "status": "error", "error": "timeout", "graders": []}],
                "summary": [{"targetId": "a", "total": 1, "passed": 0, "failed": 0, "errored": 1, "passRate": 0}], "calls": {"a": 1, "judge": 0}}""",
            targets = """[{"id": "a", "script": [{"error": {"kind": "timeout"}}]}]""",
        )
        assertEquals(emptyList(), failuresOf(fx))
        assertTrue(failuresOf(fx) { it.copy(output = it.output!!.copy(results = it.output.results.map { r -> r.copy(reply = "x") })) }.isNotEmpty())
    }

    @Test
    fun invalidSuiteExpectations() = runTest {
        val bad = """{"cases": []}"""
        val fx = fixture(suiteText = bad, expect = """{"invalidSuite": true}""")
        assertEquals(emptyList(), failuresOf(fx))
        assertTrue(failuresOf(fx) { it.copy(recordedCalls = mapOf("a" to 1, "judge" to 0)) }.isNotEmpty())
        // a valid suite where an invalid one is expected, and the reverse
        assertTrue(failuresOf(fixture(expect = """{"invalidSuite": true}""")).isNotEmpty())
        assertTrue(failuresOf(fixture(suiteText = bad)).isNotEmpty())
    }

    @Test
    fun targetIdsAreValidatedByRunSuiteThroughTheHarness() = runTest {
        val fx = fixture(
            expect = """{"invalidSuite": true}""", targets = """[{"id": "judge", "script": []}]""",
        )
        assertEquals(emptyList(), failuresOf(fx))
    }

    @Test
    fun malformedFixturesFailWithAnExplicitError() = runTest {
        suspend fun failsWith(text: String, part: String) {
            val e = assertFailsWith<IllegalStateException>(text) { runFixture(parseFixture(text)) }
            assertTrue(e.message!!.startsWith("invalid fixture:"), e.message)
            assertTrue(part in e.message!!, e.message)
        }
        val okSuite = """{"cases": [{"id": "c1", "messages": [], "graders": [{"kind": "contains", "value": ""}]}]}"""
        val okExpect = """{"invalidSuite": true}"""
        failsWith("not json", "")
        failsWith("[]", "")
        failsWith("""{"name": "t", "suite": $okSuite, "targets": [], "expect": $okExpect, "bogus": 1}""", "bogus")
        failsWith("""{"name": "t", "suite": $okSuite, "targets": [{"script": []}], "expect": $okExpect}""", "id")
        failsWith("""{"name": "t", "suite": $okSuite, "targets": [{"id": 1, "script": []}], "expect": $okExpect}""", "id")
        failsWith("""{"name": "t", "suite": $okSuite, "targets": [{"id": "a"}], "expect": $okExpect}""", "script")
        failsWith(
            """{"name": "t", "suite": $okSuite, "targets": [{"id": "a", "script": [{"response": "x"}, {}]}], "expect": $okExpect}""",
            "script entry 2 of provider a has neither response nor error",
        )
        failsWith("""{"name": "t", "suite": $okSuite, "targets": [], "expect": {"results": []}}""", "expect")
        failsWith("""{"name": "t", "suite": $okSuite, "targets": [], "expect": {"invalidSuite": false}}""", "expect")
        failsWith("""{"name": "t", "suite": $okSuite, "targets": [], "expect": []}""", "expect")
        failsWith("""{"name": "t", "suite": $okSuite, "targets": [], "judge": {"script": 1}, "expect": $okExpect}""", "judge")
        failsWith("""{"name": "t", "suite": $okSuite, "targets": [], "judge": {"script": [{}]}, "expect": $okExpect}""", "script entry 1 of provider judge has neither response nor error")
    }
}
