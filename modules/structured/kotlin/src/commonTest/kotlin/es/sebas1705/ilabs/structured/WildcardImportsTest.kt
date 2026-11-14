package es.sebas1705.ilabs.structured

// Wildcard imports of BOTH module packages, as a consumer would write them. This file must compile without any
// import alias: the main packages expose only the library API (the fixture harness lives in `...contract`).
import es.sebas1705.ilabs.providers.*
import es.sebas1705.ilabs.structured.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class WildcardImportsTest {
    @Test
    fun structuredAndProvidersWildcardImportsDoNotClash() = runTest {
        val provider: Provider = MockProvider("a", listOf(ScriptEntry(response = "{}")))
        val request = CompletionRequest(listOf(Message(Role.USER, "go")))
        val result = structured(provider, request, JsonObject(mapOf("type" to JsonPrimitive("object"))))
        assertEquals(1, result.attempts)
    }
}
