package es.sebas1705.ilabs.context

// Wildcard imports of BOTH module packages, exactly as the README snippet does. This file must compile without any
// import alias: it documents that the main packages expose only the library API (the fixture harness lives in
// `...context.contract` / `...providers.contract`), so there are no name clashes between `context` and `providers`.
import es.sebas1705.ilabs.context.*
import es.sebas1705.ilabs.providers.*
import kotlin.test.Test
import kotlin.test.assertEquals

class WildcardImportsTest {
    @Test
    fun contextAndProvidersWildcardImportsDoNotClash() {
        val messages = listOf(Message(Role.SYSTEM, "s"), Message(Role.USER, "hello"))
        val provider: Provider = MockProvider("a", listOf(ScriptEntry(response = "ok")))
        val window = slidingWindow(messages, WindowOptions(maxTokens = 100))
        assertEquals(messages, window.messages)
        assertEquals("a", provider.id)
    }
}
