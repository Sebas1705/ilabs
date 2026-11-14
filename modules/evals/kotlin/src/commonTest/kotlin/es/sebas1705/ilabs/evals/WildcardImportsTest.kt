package es.sebas1705.ilabs.evals

// Wildcard imports of ALL THREE module packages, as a consumer would write them. This file must compile without any
// import alias: the main packages expose only the library API (the fixture harness lives in `...evals.contract`).
import es.sebas1705.ilabs.evals.*
import es.sebas1705.ilabs.providers.*
import es.sebas1705.ilabs.structured.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WildcardImportsTest {
    @Test
    fun evalsProvidersAndStructuredWildcardImportsDoNotClash() = runTest {
        val provider: Provider = MockProvider("a", listOf(ScriptEntry(response = """{"n": 1}""")))
        val suite = Suite(listOf(EvalCase("c1", listOf(Message(Role.USER, "go")), listOf(Equals("x"), Length(min = 1)))))
        val out = runSuite(suite, listOf(provider))
        assertEquals(Status.FAIL, out.results.single().status)
        assertEquals(mapOf("a" to 1), out.calls)
        val extracted: Extraction = extractJson(out.results.single().reply!!)
        assertEquals(true, extracted is Extraction.Found)
        assertEquals(emptyList<Issue>(), evaluateGrader(JsonEquals(parseStrict("""{"n": 1.0}""")!!), "{\"n\": 1}").issues)
    }
}
