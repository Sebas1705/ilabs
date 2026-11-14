package es.sebas1705.ilabs.structured

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublicApiTest {
    @Test
    fun parseStrictAcceptsStrictJsonAndTheLastDuplicateKeyWins() {
        assertEquals(JsonObject(mapOf("a" to JsonPrimitive(2))), parseStrict("""{"a": 1, "a": 2}"""))
    }

    @Test
    fun parseStrictRejectsLenientForms() {
        for (bad in listOf("{\"a\": True}", "{'a': 1}", "[1,]", "NaN", "// c\n1", "", "1 2", "{\"a\": 1,}")) {
            assertNull(parseStrict(bad), bad)
        }
    }

    @Test
    fun parseStrictLimitsTheNestingDepthTo128() {
        assertNotNull(parseStrict("[".repeat(128) + "]".repeat(128)))
        assertNull(parseStrict("[".repeat(129) + "]".repeat(129)))
    }

    @Test
    fun jsonEqualComparesNumbersByValueAndIgnoresKeyOrder() {
        assertTrue(jsonEqual(parseStrict("{\"a\": 1.0, \"b\": [1e2]}")!!, parseStrict("{\"b\": [100], \"a\": 1}")!!))
        assertFalse(jsonEqual(JsonPrimitive("1"), JsonPrimitive(1)))
        assertFalse(jsonEqual(JsonArray(listOf(JsonPrimitive(1))), JsonArray(emptyList())))
    }
}
