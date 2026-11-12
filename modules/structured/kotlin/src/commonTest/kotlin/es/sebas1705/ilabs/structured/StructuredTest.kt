package es.sebas1705.ilabs.structured

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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StructuredTest {
    private val schema = obj("""{"type":"object","required":["n"],"properties":{"n":{"type":"integer"}}}""")

    private class Recording(val mock: MockProvider) : Provider {
        override val id = mock.id
        val seen = mutableListOf<List<Message>>()
        override suspend fun complete(req: CompletionRequest): CompletionResponse {
            seen += req.messages.toList()
            return mock.complete(req)
        }
    }

    private fun recording(vararg responses: String) =
        Recording(MockProvider("m", responses.map { ScriptEntry(response = it) }))

    private fun request() = CompletionRequest(listOf(Message(Role.USER, "go")))

    @Test
    fun returnsTheValueOnTheFirstTry() = runTest {
        val r = structured(recording("""{"n": 1}"""), request(), schema)
        assertEquals(j("""{"n":1}"""), r.value)
        assertEquals(1, r.attempts)
        assertEquals(listOf(AttemptTrace("""{"n": 1}""", Extraction.Found(j("""{"n":1}""")), emptyList())), r.trace)
    }

    @Test
    fun rejectsAnInvalidMaxRepairs() {
        assertFailsWith<IllegalArgumentException> { StructuredOptions(maxRepairs = -1) }
        assertEquals(2, StructuredOptions().maxRepairs)
        StructuredOptions(maxRepairs = 0)
    }

    @Test
    fun rejectsAMalformedSchemaBeforeCallingTheProvider() = runTest {
        val p = recording("{}")
        val e = assertFailsWith<InvalidSchemaException> { structured(p, request(), obj("""{"type":"nope"}""")) }
        assertTrue(e.message!!.startsWith("invalid schema:"))
        assertEquals(0, p.seen.size)
    }

    @Test
    fun repairsWithTheVerbatimReplyAndANonEmptyUserMessage() = runTest {
        val p = recording("not json", """{"n": 2}""")
        val r = structured(p, request(), schema)
        assertEquals(2, r.attempts)
        assertEquals(3, p.seen[1].size)
        assertEquals(Message(Role.ASSISTANT, "not json"), p.seen[1][1])
        assertEquals(Role.USER, p.seen[1][2].role)
        assertTrue(p.seen[1][2].content.isNotEmpty())
    }

    @Test
    fun accumulatesMessagesOverTwoRepairs() = runTest {
        val p = recording("first", """{"n": "x"}""", """{"n": 3}""")
        val r = structured(p, request(), schema, StructuredOptions(maxRepairs = 2))
        assertEquals(3, r.attempts)
        assertEquals(listOf(1, 3, 5), p.seen.map { it.size })
        assertEquals("first", p.seen[2][1].content)
        assertEquals("""{"n": "x"}""", p.seen[2][3].content)
        assertEquals(
            listOf(Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT, Role.USER),
            p.seen[2].map { it.role },
        )
    }

    @Test
    fun doesNotMutateTheOriginalMessages() = runTest {
        val req = request()
        val before = req.messages.toList()
        structured(recording("x", """{"n": 1}"""), req, schema)
        assertEquals(before, req.messages)
        assertEquals(1, req.messages.size)
    }

    @Test
    fun throwsStructuredExceptionWithTheIssuesOfTheLastAttempt() = runTest {
        val p = recording("x", """{"n": 1.5}""")
        val e = assertFailsWith<StructuredException> { structured(p, request(), schema, StructuredOptions(1)) }
        assertEquals(ErrorKind.INVALID_OUTPUT, e.kind)
        assertEquals(2, e.attempts)
        assertEquals(listOf(Issue("/n", IssueCode.TYPE)), e.issues)
        assertEquals(2, e.trace.size)
        assertEquals(Extraction.Failed(IssueCode.NO_JSON), e.trace[0].extracted)
    }

    @Test
    fun defaultsToTwoRepairs() = runTest {
        val p = recording("a", "b", "c", """{"n": 1}""")
        assertFailsWith<StructuredException> { structured(p, request(), schema) }
        assertEquals(3, p.seen.size)
    }

    @Test
    fun propagatesProviderErrorsUnchanged() = runTest {
        val script = listOf(ScriptEntry(response = "x"), ScriptEntry(error = ScriptError(ErrorKind.RATE_LIMIT)))
        val p = Recording(MockProvider("m", script))
        val e = assertFailsWith<ProviderException> { structured(p, request(), schema) }
        assertEquals(ErrorKind.RATE_LIMIT, e.kind)
    }

    @Test
    fun rethrowsNonProviderExceptionsUnchanged() = runTest {
        val boom = IllegalStateException("boom")
        val provider = object : Provider {
            override val id = "x"
            override suspend fun complete(req: CompletionRequest): CompletionResponse = throw boom
        }
        val e = assertFailsWith<IllegalStateException> { structured(provider, request(), schema) }
        assertSame(boom, e)
    }
}
