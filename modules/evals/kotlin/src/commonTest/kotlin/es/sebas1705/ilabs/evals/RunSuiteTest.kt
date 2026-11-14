package es.sebas1705.ilabs.evals

import es.sebas1705.ilabs.providers.CompletionRequest
import es.sebas1705.ilabs.providers.CompletionResponse
import es.sebas1705.ilabs.providers.ErrorKind
import es.sebas1705.ilabs.providers.Message
import es.sebas1705.ilabs.providers.MockProvider
import es.sebas1705.ilabs.providers.Provider
import es.sebas1705.ilabs.providers.ProviderException
import es.sebas1705.ilabs.providers.Role
import es.sebas1705.ilabs.providers.ScriptEntry
import es.sebas1705.ilabs.providers.ScriptError
import es.sebas1705.ilabs.structured.Issue
import es.sebas1705.ilabs.structured.IssueCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Recording(private val inner: Provider) : Provider {
    val requests = mutableListOf<CompletionRequest>()
    override val id get() = inner.id
    override suspend fun complete(req: CompletionRequest): CompletionResponse {
        requests += req
        return inner.complete(req)
    }
}

private fun script(vararg replies: String) = replies.map { ScriptEntry(response = it) }
private fun mock(id: String, vararg replies: String) = Recording(MockProvider(id, script(*replies)))
private fun user(text: String) = Message(Role.USER, text)
private fun case(id: String, messages: List<Message>, vararg graders: Grader) = EvalCase(id, messages, graders.toList())

private class Boom : Error("boom")

class RunSuiteTest {
    @Test
    fun runsCaseMajorThenTargetMinorWithExactlyTheCaseMessages() = runTest {
        val m1 = listOf(Message(Role.SYSTEM, "s"), user("one"))
        val m2 = listOf(user("two"))
        val suite = Suite(listOf(case("c1", m1, Contains("a")), case("c2", m2, Contains("a"))))
        val a = mock("a", "a1", "a2")
        val b = mock("b", "b1", "b2")
        val out = runSuite(suite, listOf(a, b))
        assertEquals(listOf("c1" to "a", "c1" to "b", "c2" to "a", "c2" to "b"), out.results.map { it.caseId to it.targetId })
        assertEquals(listOf("a1", "b1", "a2", "b2"), out.results.map { it.reply })
        assertEquals(listOf(CompletionRequest(m1), CompletionRequest(m2)), a.requests)
        assertEquals(listOf(CompletionRequest(m1), CompletionRequest(m2)), b.requests)
        assertNull(a.requests[0].maxTokens)
        assertEquals(mapOf("a" to 2, "b" to 2), out.calls)
    }

    @Test
    fun providerErrorSkipsGradersContinuesAndCounts() = runTest {
        val flaky = Recording(MockProvider("a", listOf(ScriptEntry(error = ScriptError(ErrorKind.RATE_LIMIT)), ScriptEntry(response = "ok"))))
        val judge = mock("j", "PASS")
        val suite = Suite(listOf(
            case("c1", listOf(user("q")), Judge("r"), Contains("x")),
            case("c2", listOf(user("q")), Contains("ok")),
        ))
        val out = runSuite(suite, listOf(flaky), judge)
        val first = out.results[0]
        assertEquals(Status.ERROR, first.status)
        assertEquals("rate_limit", first.error)
        assertNull(first.reply)
        assertEquals(emptyList(), first.graders)
        assertEquals(Status.PASS, out.results[1].status)
        assertNull(out.results[1].error)
        assertEquals(mapOf("a" to 2, "judge" to 0), out.calls)
        assertTrue(judge.requests.isEmpty())
    }

    @Test
    fun scriptExhaustionIsAnUnavailableErrorThatIsCounted() = runTest {
        val suite = Suite(listOf(case("c1", emptyList(), Contains("")), case("c2", emptyList(), Contains(""))))
        val out = runSuite(suite, listOf(mock("a", "x")))
        assertEquals("unavailable", out.results[1].error)
        assertEquals(mapOf("a" to 2), out.calls)
    }

    @Test
    fun noGradersShortCircuitAndStatusPrecedence() = runTest {
        val judge = mock("j", "PASS")
        val suite = Suite(listOf(case("c1", listOf(user("q")), Contains("zzz"), Judge("r"), Length(max = 1))))
        val out = runSuite(suite, listOf(mock("a", "hello")), judge)
        val r = out.results.single()
        assertEquals(Status.FAIL, r.status)
        assertEquals(listOf(false, true, false), r.graders.map { it.passed })
        assertEquals(1, judge.requests.size)

        val noJudge = runSuite(
            Suite(listOf(case("c1", listOf(user("q")), Contains("zzz"), Judge("r"), Contains("zzz")))),
            listOf(mock("a", "hello")),
        ).results.single()
        assertEquals(Status.ERROR, noJudge.status)
        assertEquals(listOf(null, "no_judge", null), noJudge.graders.map { it.error })
        assertEquals(listOf(false, false, false), noJudge.graders.map { it.passed })
    }

    @Test
    fun judgeRequestShapeAndCallCounts() = runTest {
        val messages = listOf(Message(Role.SYSTEM, "SYS-TEXT"), user("first"), Message(Role.ASSISTANT, "ASSISTANT-TEXT"), user("LAST-USER"), Message(Role.ASSISTANT, "TRAILING"))
        val judge = mock("anything", "PASS", "FAIL: no")
        val suite = Suite(listOf(case("c1", messages, Judge("RUBRIC"), Judge("RUBRIC 2"))))
        val out = runSuite(suite, listOf(mock("a", "THE-REPLY")), judge)
        assertEquals(mapOf("a" to 1, "judge" to 2), out.calls)
        assertEquals(listOf(true, false), out.results.single().graders.map { it.passed })
        val req = judge.requests.first()
        assertNull(req.maxTokens)
        assertEquals(listOf(Role.SYSTEM, Role.USER), req.messages.map { it.role })
        assertTrue(req.messages[0].content.isNotEmpty())
        val u = req.messages[1].content
        val i1 = u.indexOf("RUBRIC")
        val i2 = u.indexOf("LAST-USER", i1 + "RUBRIC".length)
        val i3 = u.indexOf("THE-REPLY", i2 + "LAST-USER".length)
        assertTrue(i1 >= 0 && i2 > i1 && i3 > i2, u)
        for (other in listOf("SYS-TEXT", "ASSISTANT-TEXT", "TRAILING", "first")) assertTrue(other !in u, other)
        assertTrue("RUBRIC 2" in judge.requests[1].messages[1].content)
    }

    @Test
    fun judgeRequestUsesTheEmptyStringWhenThereIsNoUserMessage() = runTest {
        val judge = mock("j", "PASS")
        val suite = Suite(listOf(case("c1", listOf(Message(Role.SYSTEM, "SYS-TEXT"), Message(Role.ASSISTANT, "ASSISTANT-TEXT")), Judge("R"))))
        runSuite(suite, listOf(mock("a", "reply")), judge)
        assertEquals("R\n---\n\n---\nreply", judge.requests.single().messages[1].content)
    }

    private suspend fun verdictOf(reply: String): GraderResult {
        val out = runSuite(Suite(listOf(case("c1", listOf(user("q")), Judge("r")))), listOf(mock("a", "x")), mock("j", reply))
        return out.results.single().graders.single()
    }

    @Test
    fun verdictGrammar() = runTest {
        for (pass in listOf("PASS", " \n\t\r PASS", "PASS.", "PASS: ok", "PASS\nbecause", "PASSé", "PASS-x", "PASS ")) {
            val g = verdictOf(pass)
            assertTrue(g.passed && g.error == null, pass)
        }
        for (fail in listOf("FAIL", "FAIL: too curt", "  FAIL!")) {
            val g = verdictOf(fail)
            assertTrue(!g.passed && g.error == null, fail)
        }
        for (bad in listOf("", "   ", "pass", "fail", "Pass", "PASSED", "Passing", "PASS1", "PASS_x", "FAIL_x", "FAILED", "Verdict: PASS", " PASS", "\u000bPASS", "PASSPASS")) {
            val g = verdictOf(bad)
            assertTrue(!g.passed && g.error == "bad_verdict", bad)
        }
    }

    @Test
    fun judgeProviderErrorIsReportedAndCounted() = runTest {
        val judge = Recording(MockProvider("j", listOf(ScriptEntry(error = ScriptError(ErrorKind.TIMEOUT)))))
        val suite = Suite(listOf(case("c1", listOf(user("q")), Judge("r"), Judge("r2"))))
        val out = runSuite(suite, listOf(mock("a", "x")), judge)
        assertEquals(listOf("timeout", "unavailable"), out.results.single().graders.map { it.error })
        assertEquals(mapOf("a" to 1, "judge" to 2), out.calls)
        assertEquals(Status.ERROR, out.results.single().status)
    }

    @Test
    fun jsonGradersCarryIssuesInResults() = runTest {
        val suite = Suite(listOf(case("c1", emptyList(), JsonEquals(kotlinx.serialization.json.JsonNull), Json(JsonObject(emptyMap())))))
        val g = runSuite(suite, listOf(mock("a", "no json"))).results.single().graders
        assertEquals(listOf(Issue("", IssueCode.NO_JSON)), g[0].issues)
        assertEquals(listOf(Issue("", IssueCode.NO_JSON)), g[1].issues)
    }

    @Test
    fun emptyTargetListIsValidAndMakesNoCalls() = runTest {
        val suite = Suite(listOf(case("c1", emptyList(), Contains(""))))
        val out = runSuite(suite, emptyList(), mock("j"))
        assertEquals(emptyList(), out.results)
        assertEquals(emptyList(), out.summary)
        assertEquals(mapOf("judge" to 0), out.calls)
        assertEquals(emptyMap(), runSuite(suite, emptyList()).calls)
    }

    @Test
    fun invalidTargetsFailBeforeAnyCall() = runTest {
        val suite = Suite(listOf(case("c1", emptyList(), Contains(""))))
        val a = mock("a", "x")
        for (targets in listOf(listOf(a, mock("a", "x")), listOf(a, mock("", "x")), listOf(a, mock("judge", "x")))) {
            val e = assertFailsWith<IllegalArgumentException> { runSuite(suite, targets, mock("j", "PASS")) }
            assertTrue(e.message!!.startsWith("invalid suite:"))
        }
        assertFailsWith<IllegalArgumentException> { runSuite(suite, listOf(mock("judge", "x"))) }
        assertTrue(a.requests.isEmpty())
    }

    @Test
    fun invalidTypedSuitesFailBeforeAnyCall() = runTest {
        val good = case("c1", listOf(user("q")), Contains("x"))
        val bad = listOf(
            Suite(emptyList()),
            Suite(listOf(good, good)),
            Suite(listOf(good, case("", emptyList(), Contains("x")))),
            Suite(listOf(good, EvalCase("c2", emptyList(), emptyList()))),
            Suite(listOf(good, case("c2", emptyList(), OneOf(emptyList())))),
            Suite(listOf(good, case("c2", emptyList(), Length()))),
            Suite(listOf(good, case("c2", emptyList(), Length(min = -1)))),
            Suite(listOf(good, case("c2", emptyList(), Length(min = 3, max = 2)))),
            Suite(listOf(good, case("c2", emptyList(), Judge("")))),
            Suite(listOf(good, case("c2", emptyList(), Json(JsonObject(mapOf("type" to kotlinx.serialization.json.JsonPrimitive("nope")))))))
        )
        for (suite in bad) {
            val a = mock("a", "x", "x", "x")
            val e = assertFailsWith<IllegalArgumentException> { runSuite(suite, listOf(a)) }
            assertTrue(e.message!!.startsWith("invalid suite:"), e.message)
            assertTrue(a.requests.isEmpty())
        }
    }

    private class Throwing(private val failure: () -> Throwable) : Provider {
        var calls = 0
        override val id = "t"
        override suspend fun complete(req: CompletionRequest): CompletionResponse {
            calls++
            throw failure()
        }
    }

    @Test
    fun aTargetFailureThatIsNotAProviderErrorAbortsTheRun() = runTest {
        val suite = Suite(listOf(case("c1", emptyList(), Contains("")), case("c2", emptyList(), Contains(""))))
        val failures = listOf<() -> Throwable>(
            { IllegalStateException("bad") }, { RuntimeException("x") }, { CancellationException("cancelled") }, { Boom() },
        )
        for (f in failures) {
            val p = Throwing(f)
            val thrown = runCatching { runSuite(suite, listOf(p)) }.exceptionOrNull()
            assertTrue(thrown != null && thrown::class == f()::class, "$thrown")
            assertEquals(1, p.calls)
        }
    }

    @Test
    fun aJudgeFailureThatIsNotAProviderErrorAbortsTheRun() = runTest {
        val suite = Suite(listOf(case("c1", emptyList(), Judge("r"))))
        for (f in listOf<() -> Throwable>({ IllegalStateException("bad") }, { CancellationException("cancelled") }, { Boom() })) {
            val thrown = runCatching { runSuite(suite, listOf(mock("a", "x")), Throwing(f)) }.exceptionOrNull()
            assertTrue(thrown != null && thrown::class == f()::class, "$thrown")
        }
        // a provider error from the judge is not an abort
        val ok = runSuite(suite, listOf(mock("a", "x")), Throwing { ProviderException(ErrorKind.RATE_LIMIT) })
        assertEquals("rate_limit", ok.results.single().graders.single().error)
    }

    @Test
    fun inputsAreNotMutated() = runTest {
        val messages = mutableListOf(user("q"))
        val graders = mutableListOf<Grader>(Contains("a"), Judge("r"))
        val cases = mutableListOf(EvalCase("c1", messages, graders))
        val targets = mutableListOf<Provider>(mock("a", "abc"))
        val snapshot = Triple(messages.toList(), graders.toList(), cases.toList())
        val targetsSnapshot = targets.toList()
        runSuite(Suite(cases), targets, mock("j", "PASS"))
        assertEquals(snapshot, Triple(messages.toList(), graders.toList(), cases.toList()))
        assertEquals(targetsSnapshot, targets.toList())
    }
}
